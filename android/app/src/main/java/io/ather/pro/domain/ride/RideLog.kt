package io.ather.pro.domain.ride

import io.ather.pro.domain.model.TripRecord
import kotlin.math.roundToInt

/** Official rides and local odometer legs. AUTO TRIP LOGS opens only [canOpen] rides. */
object RideLog {
    fun canOpen(trip: TripRecord): Boolean {
        val speed = trip.averageSpeedKmh ?: return false
        return speed.isFinite() && speed >= 0.0
    }

    fun openable(trips: List<TripRecord>): List<TripRecord> = trips.filter(::canOpen)

    /** Fields mapped from a cloud ride payload. Energy and cost are not decided here. */
    data class CloudFields(
        val id: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val distanceKm: Double,
        val efficiencyWhPerKm: Double?,
        val durationSeconds: Double?,
        val averageSpeedKmh: Double?,
        val topSpeedKmh: Double?,
        val encodedPolyline: String?,
        val routeSpeedsKmh: List<Double>?
    )

    fun fromCloud(fields: CloudFields, usableCapacityWh: Double, tariffRatePerKWh: Double): TripRecord {
        val efficiency = fields.efficiencyWhPerKm?.takeIf { it > 0.0 }
        val energyWh = efficiency?.times(fields.distanceKm) ?: 0.0
        val estimatedSoc = if (energyWh > 0.0 && usableCapacityWh > 0.0) {
            energyWh / usableCapacityWh * 100.0
        } else 0.0
        return TripRecord(
            id = fields.id,
            startTimeMs = fields.startTimeMs,
            endTimeMs = fields.endTimeMs,
            distanceKm = fields.distanceKm.round(2),
            // The rides payload has no SoC. This estimate is for cost math and is not shown as measured.
            socConsumed = estimatedSoc.round(1),
            energyConsumedWh = energyWh.round(1),
            efficiencyWhPerKm = (efficiency ?: 0.0).round(1),
            electricityCostInr = (energyWh / 1000.0 * tariffRatePerKWh).round(2),
            startOdoKm = 0.0,
            endOdoKm = 0.0,
            estimatedPackCapacityWh = null,
            isOfficialRide = true,
            durationSeconds = fields.durationSeconds,
            averageSpeedKmh = fields.averageSpeedKmh,
            topSpeedKmh = fields.topSpeedKmh,
            encodedPolyline = fields.encodedPolyline,
            routeSpeedsKmh = fields.routeSpeedsKmh
        )
    }

    data class Leg(
        val previousOdoKm: Double,
        val previousSoc: Double,
        val openedAtMs: Long,
        val odoKm: Double,
        val soc: Double,
        val nowMs: Long,
        val vehicleState: String,
        val speedKmh: Double,
        val charging: Boolean,
        val avgWhPerKm: Double,
        val usableCapacityWh: Double,
        val tariffRatePerKWh: Double
    )

    sealed class LegOutcome {
        data object Wait : LegOutcome()
        data class Reset(val odoKm: Double, val soc: Double, val atMs: Long) : LegOutcome()
        data class Closed(val trip: TripRecord, val odoKm: Double, val soc: Double, val atMs: Long) : LegOutcome()
    }

    fun close(leg: Leg, id: String): LegOutcome {
        val deltaOdo = leg.odoKm - leg.previousOdoKm
        val deltaSoc = leg.previousSoc - leg.soc
        val vehicleState = leg.vehicleState.trim().lowercase()
        val speedKmh = leg.speedKmh.takeIf(Double::isFinite) ?: 0.0
        val riding = vehicleState.contains("rid") || vehicleState.contains("mov") || speedKmh > 1.0
        val parked = vehicleState.contains("park") ||
            vehicleState.contains("sleep") ||
            vehicleState.contains("charg") ||
            vehicleState.contains("standby") ||
            vehicleState.contains("off")
        val baselineInvalid = deltaOdo < -0.05 || deltaSoc < -0.5 || leg.charging
        if (baselineInvalid) return LegOutcome.Reset(leg.odoKm, leg.soc, leg.nowMs)
        if (deltaOdo >= 0.15 && (parked || (!riding && deltaSoc >= 0.1))) {
            val rangeEstimatedEnergyWh = deltaOdo * leg.avgWhPerKm.coerceIn(8.0, 120.0)
            val socEstimatedEnergyWh = deltaSoc.coerceAtLeast(0.0) * (leg.usableCapacityWh / 100.0)
            val socEfficiency = if (deltaOdo > 0.0) socEstimatedEnergyWh / deltaOdo else 0.0
            val energyWh = if (deltaSoc >= 0.25 && socEfficiency in 8.0..120.0) {
                socEstimatedEnergyWh
            } else {
                rangeEstimatedEnergyWh
            }
            val tripWhPerKm = energyWh / deltaOdo
            val tripCost = (energyWh / 1000.0) * leg.tariffRatePerKWh
            val estimatedPackCapacityWh = if (deltaSoc >= 5.0) {
                (rangeEstimatedEnergyWh / (deltaSoc / 100.0))
                    .coerceIn(leg.usableCapacityWh * 0.50, leg.usableCapacityWh * 1.20)
            } else {
                null
            }
            val trip = TripRecord(
                id = id,
                startTimeMs = leg.openedAtMs,
                endTimeMs = leg.nowMs,
                distanceKm = (deltaOdo * 100.0).roundToInt() / 100.0,
                socConsumed = (deltaSoc.coerceAtLeast(0.0) * 10.0).roundToInt() / 10.0,
                energyConsumedWh = (energyWh * 10.0).roundToInt() / 10.0,
                efficiencyWhPerKm = (tripWhPerKm * 10.0).roundToInt() / 10.0,
                electricityCostInr = (tripCost * 100.0).roundToInt() / 100.0,
                startOdoKm = leg.previousOdoKm,
                endOdoKm = leg.odoKm,
                estimatedPackCapacityWh = estimatedPackCapacityWh
            )
            return LegOutcome.Closed(trip, leg.odoKm, leg.soc, leg.nowMs)
        }
        return LegOutcome.Wait
    }

    private fun Double.round(decimals: Int): Double {
        val scale = if (decimals == 1) 10.0 else 100.0
        return (this * scale).roundToInt() / scale
    }
}
