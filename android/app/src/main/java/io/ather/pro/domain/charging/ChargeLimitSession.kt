package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry

/**
 * One charge-limit session: learned speed, measured pause, and the estimated deadline.
 * Measured freshness stays a separate check from the estimated deadline.
 * The repository persists the snapshot and sends Pause. The card reads [shown].
 */
class ChargeLimitSession(
    private val rate: ChargingRateTracker = ChargingRateTracker()
) {
    fun resetRate() = rate.reset()

    data class Step(
        val snapshot: ChargeLimitController.Snapshot,
        val decision: ChargeLimitController.Decision,
        val liveRate: Double?,
        val liveMinutes: Double
    )

    fun observe(
        state: ChargeLimitController.Snapshot,
        telemetry: ScooterTelemetry?,
        batteryFreshAtMs: Long?,
        batteryReportedAtMs: Long?,
        chargingFreshAtMs: Long?,
        nowMs: Long,
        capacityWh: Double
    ): Step {
        val finished = rate.observe(telemetry, batteryReportedAtMs, nowMs)
        var next = if (finished != null) ChargeRateMemory.remember(state, finished) else state
        next = EstimatedChargeCutoff.refresh(
            next, telemetry, batteryReportedAtMs, nowMs, capacityWh,
            rate.percentPerMinute, rate.observedMinutes,
            next.learnedPercentPerMinute, next.learnedMinutes
        )
        val measured = ChargeLimitController.onTelemetry(
            state = next,
            telemetry = telemetry,
            lastUpdatedMs = batteryFreshAtMs,
            nowMs = nowMs,
            chargingUpdatedMs = chargingFreshAtMs
        )
        val decision = if (measured == ChargeLimitController.Decision.None) {
            EstimatedChargeCutoff.onDeadline(next, telemetry, nowMs)
        } else {
            measured
        }
        val published = when (decision) {
            is ChargeLimitController.Decision.RequestStop -> decision.next
            is ChargeLimitController.Decision.StateOnly -> decision.next
            ChargeLimitController.Decision.None -> next
        }
        return Step(published, decision, rate.percentPerMinute, rate.observedMinutes)
    }

    companion object {
        data class Shown(
            val estimate: ChargeTimeEstimate?,
            /** True only for an applied, armed, monitoring limit that already has a stored deadline. */
            val timerArmed: Boolean
        )

        fun shown(
            snapshot: ChargeLimitController.Snapshot,
            telemetry: ScooterTelemetry?,
            selectedPercent: Int,
            capacityWh: Double,
            reportedAtMs: Long?,
            nowMs: Long,
            liveRate: Double?,
            liveMinutes: Double
        ): Shown {
            val applied = selectedPercent == snapshot.percent
            val active = ChargingControl.isActivelyCharging(telemetry)
            val stored = snapshot.estimate
            val estimate = if (snapshot.enabled && applied && active && stored != null) {
                stored
            } else {
                ChargeDuration.learned(
                    telemetry = telemetry,
                    target = selectedPercent,
                    capacityWh = capacityWh,
                    powerW = snapshot.chargerPowerW,
                    readingAtMs = reportedAtMs ?: 0L,
                    nowMs = nowMs,
                    observedRate = liveRate,
                    learnedRate = snapshot.learnedPercentPerMinute,
                    learnedMinutes = snapshot.learnedMinutes,
                    liveMinutes = liveMinutes
                )
            }
            val timerArmed = snapshot.enabled && applied && snapshot.armed && stored != null &&
                snapshot.status == ChargeLimitController.Status.MONITORING
            return Shown(estimate, timerArmed)
        }
    }
}
