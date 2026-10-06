package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry
import kotlin.math.roundToLong

/** An approximate charging deadline; never a measured or guaranteed final battery percentage. */
data class ChargeTimeEstimate(
    val readingAtMs: Long,
    val batterySoc: Double,
    val targetPercent: Int,
    val chargerPowerW: Int,
    val capacityWh: Double,
    val minutesFromReading: Double,
    val targetAtMs: Long,
    val stopAtMs: Long,
    val basisCode: Int,
    /** How much measured charging backs this time. Never 100. */
    val accuracyPercent: Int = 0
) {
    val basisLabel: String get() = when (basisCode) {
        2 -> "This charge's speed"
        4 -> "Previous charges"
        5 -> "This charge, checked against previous charges"
        else -> "Charging speed"
    }
    fun isValid(): Boolean = readingAtMs > 0 && batterySoc.isFinite() && batterySoc in 0.0..100.0 &&
        minutesFromReading.isFinite() && minutesFromReading in 0.0..2_880.0 &&
        targetAtMs >= readingAtMs && stopAtMs in readingAtMs..targetAtMs &&
        targetPercent in 0..100 && capacityWh.isFinite() && capacityWh > 0 &&
        basisCode in setOf(2, 4, 5)
}

object ChargeTimeEstimator {
    val CHARGER_POWERS = listOf(350, 700, 900)
    const val MAX_INITIAL_READING_AGE_MS = 30 * 60_000L
    private const val EARLY_STOP_MARGIN_MS = 60_000L

    fun estimate(telemetry: ScooterTelemetry?, target: Int, capacityWh: Double, powerW: Int,
        readingAtMs: Long, nowMs: Long, observedRate: Double? = null,
        charging: Boolean = ChargingControl.isActivelyCharging(telemetry),
        learnedRate: Double? = null, learnedMinutes: Double = 0.0,
        liveMinutes: Double = 0.0): ChargeTimeEstimate? {
        val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        if (!capacityWh.isFinite() || capacityWh <= 0) return null
        val anchor = if (charging) readingAtMs else nowMs
        if (anchor <= 0 || nowMs - anchor !in 0L..MAX_INITIAL_READING_AGE_MS) return null
        val percent = target.coerceIn(0, 100)
        val usingLive = charging && liveMinutes > 0.0 && observedRate != null && observedRate in 0.01..10.0
        val usingPast = learnedRate != null && learnedRate in 0.01..10.0 && learnedMinutes > 0.0
        val chosen = ChargeRateBlend.rate(
            liveRate = if (usingLive) observedRate else null,
            liveMinutes = if (usingLive) liveMinutes else 0.0,
            learnedRate = if (usingPast) learnedRate else null
        ) ?: return null
        val remaining = (percent - soc).coerceAtLeast(0.0)
        val minutes = if (remaining == 0.0) 0.0 else remaining / chosen.percentPerMinute
        if (!minutes.isFinite() || minutes !in 0.0..2_880.0) return null
        val duration = (minutes * 60_000).roundToLong()
        val margin = minOf(EARLY_STOP_MARGIN_MS, duration / 10)
        val accuracy = ChargeAccuracy.percent(
            liveMinutes = if (usingLive) liveMinutes else 0.0,
            learnedMinutes = if (usingPast) learnedMinutes else 0.0
        )
        return ChargeTimeEstimate(anchor, soc, percent, powerW, capacityWh, minutes,
            anchor + duration, anchor + duration - margin, chosen.basisCode, accuracy)
    }
}

/**
 * Share of the estimate backed by measured charging. Ten points for having a speed,
 * up to 35 more from this charge, up to 55 more from earlier charges. Capped at 95.
 */
object ChargeAccuracy {
    fun percent(liveMinutes: Double, learnedMinutes: Double): Int {
        val livePart = liveMinutes.coerceIn(0.0, ChargeRateBlend.LIVE_FULL_WEIGHT_MINUTES) /
            ChargeRateBlend.LIVE_FULL_WEIGHT_MINUTES * 35.0
        val pastPart = learnedMinutes.coerceIn(0.0, ChargeRateMemory.MAX_MINUTES) /
            ChargeRateMemory.MAX_MINUTES * 55.0
        return (10.0 + livePart + pastPart).toInt().coerceIn(10, 95)
    }
}

/** One finished look at how fast the battery rose. */
data class FinishedCharge(val percentPerMinute: Double, val minutes: Double)

/**
 * Mixes this charge with earlier charges. This charge counts more as it runs,
 * and fully replaces the old speed after [LIVE_FULL_WEIGHT_MINUTES].
 */
object ChargeRateBlend {
    const val LIVE_FULL_WEIGHT_MINUTES = 20.0

    data class Chosen(val percentPerMinute: Double, val basisCode: Int)

    fun rate(liveRate: Double?, liveMinutes: Double, learnedRate: Double?): Chosen? {
        val live = liveRate?.takeIf { it in 0.01..10.0 && liveMinutes > 0.0 }
        val past = learnedRate?.takeIf { it in 0.01..10.0 }
        return when {
            live != null && past != null -> {
                val weight = (liveMinutes / LIVE_FULL_WEIGHT_MINUTES).coerceIn(0.0, 1.0)
                Chosen(live * weight + past * (1.0 - weight), if (weight >= 0.999) 2 else 5)
            }
            live != null -> Chosen(live, 2)
            past != null -> Chosen(past, 4)
            else -> null
        }
    }
}

/** Folds a finished charge into the saved speed. Recent minutes keep influencing the average. */
object ChargeRateMemory {
    const val MAX_MINUTES = 180.0
    private const val MAX_SESSION_MINUTES = 60.0

    fun remember(state: ChargeLimitController.Snapshot, sample: FinishedCharge): ChargeLimitController.Snapshot {
        val rate = sample.percentPerMinute
        val minutes = sample.minutes.coerceAtMost(MAX_SESSION_MINUTES)
        if (rate !in 0.01..10.0 || minutes < 1.0) return state
        val pastRate = state.learnedPercentPerMinute
        val pastMinutes = state.learnedMinutes
        if (pastRate == null || pastRate !in 0.01..10.0 || pastMinutes <= 0.0 || state.learnedSessions <= 0) {
            return state.copy(learnedPercentPerMinute = rate, learnedMinutes = minutes, learnedSessions = 1)
        }
        val pastWeight = pastMinutes.coerceIn(0.0, MAX_MINUTES)
            .coerceAtMost((MAX_MINUTES - minutes).coerceAtLeast(0.0))
        val total = pastWeight + minutes
        val blended = (pastRate * pastWeight + rate * minutes) / total
        return state.copy(
            learnedPercentPerMinute = blended,
            learnedMinutes = total.coerceAtMost(MAX_MINUTES),
            learnedSessions = state.learnedSessions + 1
        )
    }
}

/** Uses only new source-timestamped charging measurements; repeated snapshots cannot learn a rate. */
class ChargingRateTracker {
    private var anchorTime: Long? = null
    private var anchorSoc: Double? = null
    private var latestTime: Long? = null
    var percentPerMinute: Double? = null
        private set
    var observedMinutes: Double = 0.0
        private set

    fun reset() {
        anchorTime = null
        anchorSoc = null
        latestTime = null
        percentPerMinute = null
        observedMinutes = 0.0
    }

    /** Returns a finished charge once, when charging stops or the 30-minute window rolls over. */
    fun observe(telemetry: ScooterTelemetry?, readingAtMs: Long?, nowMs: Long): FinishedCharge? {
        if (!ChargingControl.isActivelyCharging(telemetry)) {
            val finished = finishedSample()
            reset()
            return finished
        }
        val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        val at = readingAtMs ?: return null
        if (nowMs - at !in 0L..ChargeTimeEstimator.MAX_INITIAL_READING_AGE_MS || at <= (latestTime ?: 0L)) return null
        val start = anchorTime
        val first = anchorSoc
        if (start == null || first == null || at - start > 30 * 60_000L || soc < first) {
            val rolled = if (start != null && at - start > 30 * 60_000L) finishedSample() else null
            anchorTime = at
            anchorSoc = soc
            percentPerMinute = null
            observedMinutes = 0.0
            latestTime = at
            return rolled
        }
        if (at - start >= 30_000L && soc - first >= 0.1) {
            val minutes = (at - start) / 60_000.0
            val rate = ((soc - first) * 60_000 / (at - start)).takeIf { it in 0.01..10.0 }
            percentPerMinute = rate
            observedMinutes = if (rate == null) 0.0 else minutes
        }
        latestTime = at
        return null
    }

    private fun finishedSample(): FinishedCharge? {
        val rate = percentPerMinute ?: return null
        val minutes = observedMinutes
        if (minutes < 1.0 || rate !in 0.01..10.0) return null
        return FinishedCharge(rate, minutes)
    }
}
