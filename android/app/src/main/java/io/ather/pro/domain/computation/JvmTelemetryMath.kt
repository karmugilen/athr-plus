package io.ather.pro.domain.computation

import io.ather.pro.domain.charging.ChargeAccuracy
import io.ather.pro.domain.charging.ChargeRateBlend

/**
 * In-process adapter for tests and for a process that has not loaded Rust.
 * The phone installs [io.ather.pro.data.computation.RustTelemetryMath] at startup.
 * These formulas match `rust/ather-math`.
 */
object JvmTelemetryMath : TelemetryMath {
    override fun historyIndices(timestamps: LongArray, values: DoubleArray, start: Long, end: Long): IntArray {
        val indices = ArrayList<Int>()
        for (index in timestamps.indices) {
            val time = timestamps[index]
            val value = values.getOrNull(index) ?: continue
            if (time > 0L && time in start..end && value.isFinite() && value in 0.0..100.0) indices.add(index)
        }
        indices.sortBy { timestamps[it] }
        val unique = ArrayList<Int>(indices.size)
        var previous: Long? = null
        for (index in indices) {
            val time = timestamps[index]
            if (previous == time) continue
            unique.add(index)
            previous = time
        }
        return unique.toIntArray()
    }

    override fun scaleRange(current: Double, mode: Double, active: Double): Double {
        val value = current * mode / active
        return if (value.isFinite() && value >= 0.0) value else Double.NaN
    }

    override fun chargeEstimate(
        soc: Double,
        target: Double,
        capacity: Double,
        tariff: Double,
        range: Double,
        eta80: Double,
        eta100: Double
    ): DoubleArray {
        val clamped = target.coerceIn(0.0, 100.0)
        val remaining = (clamped - soc).coerceAtLeast(0.0)
        val energy = capacity * remaining / 100_000.0
        val eta = when {
            remaining == 0.0 -> 0.0
            clamped <= 80.0 && soc < 80.0 && eta80.isFinite() && eta80 >= 0.0 -> eta80 * remaining / (80.0 - soc)
            soc < 100.0 && eta100.isFinite() && eta100 >= 0.0 -> eta100 * remaining / (100.0 - soc)
            else -> Double.NaN
        }
        val projected = if (soc >= 5.0 && range.isFinite() && range >= 0.0) range * clamped / soc else Double.NaN
        return doubleArrayOf(remaining, energy, energy * tariff, projected, eta)
    }

    override fun learnedChargeNumbers(
        liveRate: Double,
        liveMinutes: Double,
        learnedRate: Double,
        learnedMinutes: Double
    ): DoubleArray {
        val chosen = ChargeRateBlend.rate(
            liveRate = liveRate.takeIf { it.isFinite() && learnedSide(liveMinutes) },
            liveMinutes = liveMinutes,
            learnedRate = learnedRate.takeIf { it.isFinite() && learnedMinutes > 0.0 }
        ) ?: return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
        val accuracy = ChargeAccuracy.percent(
            liveMinutes = if (liveRate.isFinite() && liveRate in 0.01..10.0 && liveMinutes > 0.0) liveMinutes else 0.0,
            learnedMinutes = if (learnedRate.isFinite() && learnedRate in 0.01..10.0 && learnedMinutes > 0.0) learnedMinutes else 0.0
        )
        return doubleArrayOf(chosen.percentPerMinute, chosen.basisCode.toDouble(), accuracy.toDouble())
    }

    private fun learnedSide(minutes: Double): Boolean = minutes.isFinite() && minutes > 0.0
}
