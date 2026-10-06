package io.ather.pro.domain.monitoring

import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ScooterDashboardState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Text for the ongoing status notification. The title follows the scooter:
 * charging, pausing, or stopped. A future charge-limit deadline becomes a countdown.
 */
data class MonitorNotice(
    val title: String,
    val detail: String,
    val socPercent: Int?,
    val countdownToMs: Long?
) {
    companion object {
        fun from(nowMs: Long, state: ScooterDashboardState, limit: ChargeLimitController.Snapshot): MonitorNotice {
            val telemetry = state.telemetry
            val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }?.roundToInt()
            val connected = state.connection == ConnectionStatus.CONNECTED
            val updatedAt = state.lastUpdated
            val stale = updatedAt == null || nowMs - updatedAt > 60_000L
            val charging = ChargingControl.isActivelyCharging(telemetry)
            val stopped = ChargingControl.isPaused(telemetry) ||
                limit.status == ChargeLimitController.Status.CONFIRMED ||
                ChargingControl.isStoppedStatus(telemetry?.chargingStatus)
            val stopAt = limit.estimate?.stopAtMs?.takeIf {
                charging && limit.enabled && limit.status == ChargeLimitController.Status.MONITORING && it > nowMs
            }
            val socText = soc?.let { " · $it%" }.orEmpty()
            val title = when {
                !connected -> "Reconnecting$socText"
                limit.status == ChargeLimitController.Status.PENDING -> "Pausing charge$socText"
                stopped -> "Charging stopped$socText"
                charging -> "Charging$socText"
                stale -> "Waiting for scooter$socText"
                limit.enabled -> "Limit ${limit.percent}%$socText"
                else -> "Athr+$socText"
            }
            val detail = buildString {
                if (limit.enabled) append("Stop at ${limit.percent}%")
                if (stopAt != null) {
                    if (isNotEmpty()) append(" · ")
                    append("estimated ")
                    append(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(stopAt)))
                }
                when {
                    limit.status == ChargeLimitController.Status.PENDING -> {
                        if (isNotEmpty()) append(" · ")
                        append("Sending Pause")
                    }
                    stopped -> {
                        if (isNotEmpty()) append(" · ")
                        append("Charging is stopped")
                    }
                    charging && stopAt == null && limit.enabled -> {
                        if (isNotEmpty()) append(" · ")
                        append("Watching the charge")
                    }
                }
            }.ifBlank { title }
            return MonitorNotice(title, detail, soc, stopAt)
        }
    }
}
