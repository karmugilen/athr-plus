package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry

/** Timed fallback supplements measured cutoff without relaxing measured-data freshness checks. */
object EstimatedChargeCutoff {
    fun refresh(state: ChargeLimitController.Snapshot, telemetry: ScooterTelemetry?,
        readingAtMs: Long?, nowMs: Long, capacityWh: Double, observedRate: Double?,
        liveMinutes: Double = 0.0, learnedRate: Double? = state.learnedPercentPerMinute,
        learnedMinutes: Double = state.learnedMinutes): ChargeLimitController.Snapshot {
        if (!state.enabled || !state.armed || state.status != ChargeLimitController.Status.MONITORING) return state
        if (!ChargingControl.isActivelyCharging(telemetry)) {
            // Cancel the timer, but do not permanently block a later Charging
            // update that shares the bike's cached battery timestamp.
            return if (state.estimate != null) state.copy(estimate = null) else state
        }
        val at = readingAtMs ?: return state
        if (at <= (state.estimateBlockedThroughMs ?: 0L)) return state.copy(estimate = null)
        val existing = state.estimate
        // Cached snapshots must never move an already-armed deadline later.
        if (existing != null && existing.readingAtMs == at && existing.batterySoc == telemetry?.batterySoc &&
            existing.targetPercent == state.percent && existing.chargerPowerW == state.chargerPowerW &&
            existing.capacityWh == capacityWh) return state
        val estimate = ChargeTimeEstimator.estimate(telemetry, state.percent, capacityWh,
            state.chargerPowerW, at, nowMs, observedRate, learnedRate = learnedRate,
            learnedMinutes = learnedMinutes, liveMinutes = liveMinutes)
            ?: return if (existing?.capacityWh != capacityWh) state.copy(estimate = null) else state
        return state.copy(estimate = estimate)
    }

    fun onDeadline(state: ChargeLimitController.Snapshot, telemetry: ScooterTelemetry?, nowMs: Long): ChargeLimitController.Decision {
        val estimate = state.estimate ?: return ChargeLimitController.Decision.None
        if (!state.enabled || !state.armed || state.status != ChargeLimitController.Status.MONITORING ||
            !estimate.isValid() || estimate.targetPercent != state.percent ||
            estimate.chargerPowerW != state.chargerPowerW || nowMs < estimate.stopAtMs ||
            !ChargingControl.isActivelyCharging(telemetry)) return ChargeLimitController.Decision.None
        return ChargeLimitController.Decision.RequestStop(state.copy(
            status = ChargeLimitController.Status.PENDING,
            armed = false, pendingSinceMs = nowMs, lastAttemptMs = nowMs,
            attempts = state.attempts + 1, stopWasEstimated = true,
            message = "Estimated cutoff time reached for ${state.percent}%. Sending Pause; final battery percentage is approximate."
        ))
    }
}
