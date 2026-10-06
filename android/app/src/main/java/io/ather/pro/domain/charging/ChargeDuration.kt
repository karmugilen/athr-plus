package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.range.RangeEstimator

/**
 * Two labeled clocks. The learned pause time is observed charging speed.
 * Cloud minutes are the scooter's own ETA, scaled to the target, and only while charging is active.
 */
object ChargeDuration {
    fun learned(
        telemetry: ScooterTelemetry?,
        target: Int,
        capacityWh: Double,
        powerW: Int,
        readingAtMs: Long,
        nowMs: Long,
        observedRate: Double?,
        learnedRate: Double?,
        learnedMinutes: Double,
        liveMinutes: Double
    ): ChargeTimeEstimate? = ChargeTimeEstimator.estimate(
        telemetry, target, capacityWh, powerW, readingAtMs, nowMs, observedRate,
        learnedRate = learnedRate, learnedMinutes = learnedMinutes, liveMinutes = liveMinutes
    )

    /** Null unless the scooter is actively charging and the cloud ETA can be scaled. */
    fun cloudMinutes(
        telemetry: ScooterTelemetry?,
        targetPercent: Int,
        capacityWh: Double,
        tariffPerKWh: Double,
        model: ScooterModel?
    ): Double? {
        if (!ChargingControl.isActivelyCharging(telemetry)) return null
        return RangeEstimator.target(telemetry, targetPercent, capacityWh, tariffPerKWh, model)?.minutesToTarget
    }
}
