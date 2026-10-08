package io.ather.pro.domain.insights

import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TripRecord
import io.ather.pro.domain.ride.RidePolyline
import kotlin.math.abs
import kotlin.math.ceil

data class ChargeSession(
    val startedAt: Long, val lastSeenAt: Long, val startSoc: Double, val endSoc: Double,
    val target: Int?, val endedAt: Long? = null, val cutoffConfirmed: Boolean = false,
    val interruptedObservation: Boolean = false
)
data class ParkedPeriod(
    val startedAt: Long, val lastSeenAt: Long, val startSoc: Double, val endSoc: Double,
    val odometerKm: Double
) {
    val dropPercent: Double get() = startSoc - endSoc
    val hours: Double get() = (lastSeenAt - startedAt) / 3_600_000.0
}
data class BatteryObservation(val at: Long, val soc: Double, val mode: String?)
data class RiderInsights(
    val dailyDistanceKm: Double = 30.0,
    val reservePercent: Int = 15,
    val tyreReminders: Boolean = true,
    val chargeSessions: List<ChargeSession> = emptyList(),
    val parkedPeriods: List<ParkedPeriod> = emptyList(),
    val activeParked: ParkedPeriod? = null,
    val observations: List<BatteryObservation> = emptyList(),
    /** Recent cloud rides fetched for this scooter; local odometer legs are excluded. */
    val rides: List<TripRecord> = emptyList(),
    val lastReportAt: Long = 0L,
    val lastEfficiencyAlertRide: String? = null,
    val lastEfficiencyAlertAt: Long = 0L
)

/** Records observed endpoints only. Gaps never become invented charging or parked history. */
object RiderInsightRecorder {
    const val MAX_GAP_MS = 30 * 60_000L
    private const val RETENTION_MS = 30L * 24 * 60 * 60_000

    fun observe(state: RiderInsights, report: ScooterTelemetry, now: Long,
        limit: ChargeLimitController.Snapshot): RiderInsights {
        val at = report.sourceTimestampMs ?: return state
        val soc = report.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return state
        if (at <= state.lastReportAt || now - at !in 0..120_000L) return state
        var sessions = state.chargeSessions
        val previous = sessions.firstOrNull()?.takeIf { it.endedAt == null }
        val gap = previous != null && at - previous.lastSeenAt > MAX_GAP_MS
        if (gap) sessions = listOf(previous!!.copy(endedAt = previous.lastSeenAt,
            interruptedObservation = true)) + sessions.drop(1)
        val active = if (gap) null else previous
        // Require a physical charging field on this packet; merged old state is insufficient.
        when (report.charging) {
            true -> {
                val session = active?.copy(lastSeenAt = at, endSoc = soc,
                    target = if (limit.enabled) limit.percent else active.target)
                    ?: ChargeSession(at, at, soc, soc, limit.percent.takeIf { limit.enabled })
                sessions = listOf(session) + if (active != null) sessions.drop(1) else sessions
            }
            false -> if (active != null) {
                sessions = listOf(active.copy(lastSeenAt = at, endSoc = soc, endedAt = at,
                    cutoffConfirmed = limit.status == ChargeLimitController.Status.CONFIRMED)) + sessions.drop(1)
            }
            null -> Unit
        }
        val odo = report.odoKm?.takeIf { it.isFinite() && it >= 0 }
        val parked = report.vehicleState?.lowercase()?.let { text ->
            listOf("park", "sleep", "standby", "off").any { text.contains(it) }
        } == true && report.charging == false && odo != null && (report.gps?.speed ?: 0.0) <= 1.0
        val anchor = state.activeParked
        val continuous = parked && anchor != null && at - anchor.lastSeenAt <= MAX_GAP_MS &&
            abs(odo!! - anchor.odometerKm) <= 0.05 && soc <= anchor.endSoc + 0.2
        val nextParked = when {
            continuous -> anchor!!.copy(lastSeenAt = at, endSoc = soc)
            parked -> ParkedPeriod(at, at, soc, soc, odo!!)
            else -> null
        }
        val completed = if (!continuous && anchor != null && anchor.hours >= 2.0) {
            (listOf(anchor) + state.parkedPeriods).take(60)
        } else state.parkedPeriods
        return state.copy(chargeSessions = sessions.take(60), parkedPeriods = completed,
            activeParked = nextParked, lastReportAt = at,
            observations = (state.observations.filter { it.at >= at - RETENTION_MS } +
                BatteryObservation(at, soc, report.mode)).takeLast(7_200))
    }

    fun confirmCutoff(state: RiderInsights, limit: ChargeLimitController.Snapshot): RiderInsights {
        if (limit.status != ChargeLimitController.Status.CONFIRMED) return state
        val first = state.chargeSessions.firstOrNull() ?: return state
        val attempt = limit.lastAttemptMs ?: return state
        if (attempt < first.startedAt || attempt > first.lastSeenAt || first.target == null || first.cutoffConfirmed) return state
        return state.copy(chargeSessions = listOf(first.copy(cutoffConfirmed = true,
            endedAt = first.endedAt ?: first.lastSeenAt)) + state.chargeSessions.drop(1))
    }

    fun observeStopped(state: RiderInsights, report: ScooterTelemetry, snapshot: Boolean, now: Long): RiderInsights {
        if (report.charging != false) return state
        val at = report.sourceTimestampMs ?: now.takeIf { !snapshot } ?: return state
        val first = state.chargeSessions.firstOrNull()?.takeIf { it.endedAt == null } ?: return state
        if (now - at !in 0..120_000L || at < first.lastSeenAt) return state
        val soc = report.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: first.endSoc
        return state.copy(chargeSessions = listOf(first.copy(lastSeenAt = at, endSoc = soc, endedAt = at,
            interruptedObservation = at - first.lastSeenAt > MAX_GAP_MS)) + state.chargeSessions.drop(1))
    }
}

data class TomorrowEstimate(val neededPercent: Double, val targetPercent: Double,
    val whPerKm: Double, val ridesUsed: Int, val enough: Boolean?)

object TomorrowPlanner {
    fun estimate(distanceKm: Double, reservePercent: Int, capacityWh: Double,
        currentSoc: Double?, trips: List<TripRecord>): TomorrowEstimate? {
        if (!distanceKm.isFinite() || distanceKm <= 0 || !capacityWh.isFinite() || capacityWh <= 0 || reservePercent !in 0..50) return null
        val valid = trips.filter { it.isOfficialRide && it.distanceKm >= 2.0 &&
            it.efficiencyWhPerKm.isFinite() && it.efficiencyWhPerKm in 5.0..150.0 }
            .distinctBy { it.id }.sortedByDescending { it.endTimeMs }.take(20)
        if (valid.size < 3) return null
        // Upper quartile offers a more cautious estimate than the best ride or simple average.
        val values = valid.map { it.efficiencyWhPerKm }.sorted()
        val whPerKm = values[ceil((values.size - 1) * 0.75).toInt()]
        val needed = distanceKm * whPerKm / capacityWh * 100.0
        val target = needed + reservePercent
        return TomorrowEstimate(needed, target, whPerKm, valid.size,
            currentSoc?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { it >= target })
    }
}

data class EfficiencyChange(val rideId: String, val increasePercent: Double, val baselineRides: Int)
data class EfficientSpeedBand(val fromKmh: Int, val toKmh: Int, val whPerKm: Double, val rides: Int)

/** A service reminder heuristic, never a pressure estimate or a puncture detector. */
object TyreEfficiencyReview {
    private data class Context(val trip: TripRecord, val mode: String, val socBand: Int)
    private fun context(trip: TripRecord, observations: List<BatteryObservation>): Context? {
        if (!trip.isOfficialRide || trip.distanceKm < 3 || trip.efficiencyWhPerKm !in 5.0..150.0 ||
            trip.averageSpeedKmh?.let { it.isFinite() && it in 10.0..80.0 } != true) return null
        val samples = observations.filter { it.at in trip.startTimeMs..trip.endTimeMs }
        if (samples.size < 2 || samples.first().at - trip.startTimeMs > 6 * 60_000L ||
            trip.endTimeMs - samples.last().at > 6 * 60_000L ||
            samples.zipWithNext().any { (a, b) -> b.at - a.at > 10 * 60_000L }) return null
        val modes = samples.map { it.mode }.distinct()
        if (modes.size != 1 || modes.first().isNullOrBlank()) return null
        val minSoc = samples.minOf { it.soc }; val maxSoc = samples.maxOf { it.soc }
        // Conservative comparison bands avoid mixing observed high-SoC rides with lower-SoC rides.
        // This is not a claim that regen is active at a particular battery percentage.
        if (minSoc < 10 || maxSoc >= 80 || (minSoc / 20).toInt() != (maxSoc / 20).toInt()) return null
        return Context(trip, modes.first()!!, (minSoc / 20).toInt())
    }

    private fun comparable(other: Context, reference: Context, matchSpeed: Boolean = true): Boolean {
        val route = RidePolyline.decode(reference.trip.encodedPolyline)
        val points = RidePolyline.decode(other.trip.encodedPolyline)
        fun near(a: io.ather.pro.domain.ride.RidePoint, b: io.ather.pro.domain.ride.RidePoint) =
            abs(a.latitude - b.latitude) < 0.002 && abs(a.longitude - b.longitude) < 0.002
        return route.size >= 2 && points.size >= 2 && other.mode == reference.mode && other.socBand == reference.socBand &&
            (!matchSpeed || abs(other.trip.averageSpeedKmh!! - reference.trip.averageSpeedKmh!!) <= 5) &&
            abs(other.trip.distanceKm / reference.trip.distanceKm - 1) <= 0.20 &&
            near(points.first(), route.first()) && near(points.last(), route.last())
    }

    /** Descriptive association with trip-average speed, not a recommended cruising speed. */
    fun efficientSpeedBand(trips: List<TripRecord>, observations: List<BatteryObservation>): EfficientSpeedBand? {
        val contexts = trips.distinctBy { it.id }.sortedByDescending { it.endTimeMs }.mapNotNull { context(it, observations) }
        val latest = contexts.firstOrNull() ?: return null
        val groups = contexts.filter { comparable(it, latest, matchSpeed = false) }
            .groupBy { (it.trip.averageSpeedKmh!! / 5).toInt() * 5 }.filterValues { it.size >= 3 }
        if (groups.size < 2) return null
        val best = groups.minBy { (_, rides) -> rides.map { it.trip.efficiencyWhPerKm }.sorted()[rides.size / 2] }
        val efficiencies = best.value.map { it.trip.efficiencyWhPerKm }.sorted()
        return EfficientSpeedBand(best.key, best.key + 5, efficiencies[efficiencies.size / 2], best.value.size)
    }

    fun evaluate(trips: List<TripRecord>, observations: List<BatteryObservation>): EfficiencyChange? {
        val contexts = trips.distinctBy { it.id }.sortedByDescending { it.endTimeMs }
            .mapNotNull { context(it, observations) }
        val latest = contexts.firstOrNull() ?: return null
        // Only evaluate the newest ride, never repeatedly alert for a historical outlier.
        if (latest.trip.id != trips.maxByOrNull { it.endTimeMs }?.id) return null
        val similar = contexts.drop(1).filter { comparable(it, latest) }
        if (similar.size < 7) return null
        val recent = listOf(latest) + similar.take(2)
        val baseline = similar.drop(2).take(10)
        val sorted = baseline.map { it.trip.efficiencyWhPerKm }.sorted()
        val median = sorted[sorted.size / 2]
        if (sorted.last() / sorted.first() > 1.4) return null
        if (recent.any { it.trip.efficiencyWhPerKm < median * 1.25 }) return null
        return EfficiencyChange(latest.trip.id,
            (recent.map { it.trip.efficiencyWhPerKm }.average() / median - 1) * 100, baseline.size)
    }
}
