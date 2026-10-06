package io.ather.pro.domain.range

import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.ScooterModel

data class RideModeRange(val name: String, val km: Double, val active: Boolean)

data class ChargeTargetEstimate(
    val remainingPercent: Double,
    val energyKWh: Double,
    val costInr: Double,
    val rangeAtTargetKm: Double?,
    val minutesToTarget: Double?
)

/** Estimates use reported range and the selected usable battery capacity, never a fabricated SoH. */
object RangeEstimator {
    private fun valid(value: Double?) = value?.takeIf { it.isFinite() && it >= 0.0 }

    /** Anchor mode ratios to the current range, so every tile refers to the same battery reading. */
    fun modes(telemetry: ScooterTelemetry?, model: ScooterModel? = null): List<RideModeRange> {
        val reported = telemetry?.modeRanges.orEmpty().entries.groupBy { RideMode.from(it.key) }
        val active = RideMode.from(telemetry?.mode)
        val ranges = RideMode.entries.filter { it.supportedBy(model) }.mapNotNull { mode ->
            val values = reported[mode].orEmpty().map { it.value }
            val km = values.firstNotNullOfOrNull { valid(it.predictedRangeKm) }
                ?: values.firstNotNullOfOrNull { valid(it.rawRangeKm) }
            km?.let { RideModeRange(mode.displayName, it, mode == active) }
        }
        val currentRange = valid(telemetry?.rangeKm)
        val activeRange = ranges.firstOrNull { it.active }?.km?.takeIf { it > 0 }
        // Ratios work whether the API sends full-charge references or remaining
        // ranges. Never multiply already-reduced ranges by the SoC a second time.
        if (currentRange != null && active != null && active.supportedBy(model) && activeRange == null) {
            // The main live estimate is still useful when its mode-reference field is missing.
            // Without that reference we cannot anchor the other mode ratios reliably.
            return listOf(RideModeRange(active.displayName, currentRange, true))
        }
        return if (currentRange != null && activeRange != null) ranges.mapNotNull { mode ->
            valid(io.ather.pro.domain.computation.TelemetryComputation.engine().scaleRange(currentRange, mode.km, activeRange))?.let { mode.copy(km = it) }
        } else ranges
    }

    fun current(telemetry: ScooterTelemetry?, model: ScooterModel? = null): Double? = valid(telemetry?.rangeKm)
        ?: modes(telemetry, model).firstOrNull { it.active }?.km

    fun target(telemetry: ScooterTelemetry?, target: Int, capacityWh: Double, tariff: Double, model: ScooterModel? = null): ChargeTargetEstimate? {
        val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        if (!capacityWh.isFinite() || capacityWh <= 0 || !tariff.isFinite() || tariff < 0) return null
        val percent = target.coerceIn(0, 100).toDouble()
        val values = io.ather.pro.domain.computation.TelemetryComputation.engine().chargeEstimate(
            soc, percent, capacityWh, tariff, current(telemetry, model) ?: Double.NaN,
            telemetry.timeToEightyChargeMin ?: Double.NaN, telemetry.timeToFullChargeMin ?: Double.NaN)
        return ChargeTargetEstimate(values[0], values[1], values[2], valid(values[3]), valid(values[4]))
    }
}
