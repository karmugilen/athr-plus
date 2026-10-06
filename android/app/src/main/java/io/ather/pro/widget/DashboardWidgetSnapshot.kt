package io.ather.pro.widget

import android.content.Context
import io.ather.pro.domain.range.RangeEstimator
import io.ather.pro.domain.range.RideMode
import io.ather.pro.domain.range.RideModeRange
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargingControl
import com.google.gson.Gson
import io.ather.pro.domain.battery.kmPerUnitOrNull
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.TripRecord
import io.ather.pro.domain.ride.RideLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lightweight prefs snapshot for the home-screen widget (no Room/auth coupling). */
data class DashboardWidgetSnapshot(
    val socPercent: Double? = null,
    val rangeKm: Double? = null,
    val syncLabel: String = "Never synced",
    val connectionLabel: String = "OFFLINE",
    val updatedAtMs: Long = 0L,
    val modeRanges: List<RideModeRange> = emptyList(),
    val chargeLabel: String = "Limit off",
    val charging: Boolean = false,
    val currentMode: String? = null,
    val vehicleName: String = "ATHR+",
    val limitPercent: Int? = null,
    val estimatedStopAtMs: Long? = null,
    /** Last ride the trip list can open, for example "Last ride 56.2 km/unit". */
    val lastRideText: String = ""
) {
    val socText: String
        get() = socPercent?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: "—"

    val rangeText: String
        get() = rangeKm?.let { String.format(Locale.getDefault(), "%.0f km", it) } ?: "— km"

    val modesLabel: String
        get() = socPercent?.let { "Range at $socText battery" } ?: "Range by mode"

    val modesText: String
        get() = modeRanges.joinToString(" · ") {
            "${it.name} ${String.format(Locale.getDefault(), "%.0f", it.km)} km"
        }.ifBlank { "Open app to sync mode ranges" }

    companion object {
        private const val PREFS = "ather_dashboard_widget"
        private const val KEY_SOC = "soc"
        private const val KEY_RANGE = "range"
        private const val KEY_SYNC = "sync"
        private const val KEY_CONN = "conn"
        private const val KEY_UPDATED = "updated"

        fun fromDashboard(state: ScooterDashboardState, limit: ChargeLimitController.Snapshot = ChargeLimitController.Snapshot()): DashboardWidgetSnapshot {
            val telemetry = state.telemetry
            val model = state.modelForRange
            val range = RangeEstimator.current(telemetry, model)
            val batteryUpdatedAt = state.batteryReportedAt ?: telemetry?.sourceTimestampMs ?: state.batteryUpdatedAt ?: state.lastUpdated
            val sync = batteryUpdatedAt?.let {
                "Synced " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it))
            } ?: "Never synced"
            return DashboardWidgetSnapshot(
                socPercent = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 },
                rangeKm = range?.takeIf { it.isFinite() && it >= 0.0 },
                syncLabel = sync,
                connectionLabel = when (state.connection) {
                    ConnectionStatus.CONNECTED -> "LIVE"
                    ConnectionStatus.CONNECTING -> "CONNECTING"
                    ConnectionStatus.DISCONNECTED -> "OFFLINE"
                    ConnectionStatus.ERROR -> "ERROR"
                },
                updatedAtMs = batteryUpdatedAt ?: 0L,
                modeRanges = RangeEstimator.modes(telemetry, model),
                chargeLabel = if (limit.enabled) "Limit ${limit.percent}% · " + when (limit.status) {
                    ChargeLimitController.Status.PENDING -> "Stopping"
                    ChargeLimitController.Status.CONFIRMED -> "Paused"
                    ChargeLimitController.Status.ERROR -> "Check app"
                    else -> limit.estimate?.let { "Est. stop " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it.stopAtMs)) } ?: "Watching"
                } else "Limit off",
                charging = ChargingControl.isActivelyCharging(telemetry),
                currentMode = RideMode.from(telemetry?.mode)?.takeIf { it.supportedBy(model) }?.displayName,
                vehicleName = state.vehicleProfile?.displayName ?: model.displayName,
                limitPercent = limit.percent.takeIf { limit.enabled },
                estimatedStopAtMs = limit.estimate?.stopAtMs?.takeIf {
                    limit.enabled && limit.status == ChargeLimitController.Status.MONITORING
                },
                lastRideText = lastRideLabel(state.recentTrips)
            )
        }

        /** Newest ride AUTO TRIP LOGS can open. km/unit is that ride's distance divided by kWh. */
        fun lastRideLabel(trips: List<TripRecord>): String {
            val ride = trips.filter(RideLog::canOpen).maxByOrNull { it.endTimeMs } ?: return ""
            val kmPerUnit = ride.kmPerUnitOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return ""
            return "Last ride ${String.format(Locale.US, "%.1f km/unit", kmPerUnit)}"
        }

        fun load(context: Context): DashboardWidgetSnapshot {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return runCatching {
                val socBits = prefs.getLong(KEY_SOC, Long.MIN_VALUE)
                val rangeBits = prefs.getLong(KEY_RANGE, Long.MIN_VALUE)
                DashboardWidgetSnapshot(
                    socPercent = socBits.takeIf { it != Long.MIN_VALUE }?.let { Double.fromBits(it) }?.takeIf { it.isFinite() && it in 0.0..100.0 },
                    rangeKm = rangeBits.takeIf { it != Long.MIN_VALUE }?.let { Double.fromBits(it) }?.takeIf { it.isFinite() && it >= 0 },
                    syncLabel = prefs.getString(KEY_SYNC, "Never synced") ?: "Never synced",
                    connectionLabel = prefs.getString(KEY_CONN, "OFFLINE") ?: "OFFLINE",
                    updatedAtMs = prefs.getLong(KEY_UPDATED, 0L),
                    // The old formatted string could contain unsupported modes; wait for a filtered snapshot.
                    modeRanges = runCatching {
                        Gson().fromJson(prefs.getString("mode_ranges_v2", "[]"), Array<RideModeRange>::class.java)
                            .orEmpty().filter { it != null && !it.name.isNullOrBlank() && it.km.isFinite() && it.km >= 0 }.take(6)
                    }.getOrDefault(emptyList()),
                    chargeLabel = prefs.getString("charge_label", "Limit off").orEmpty(),
                    charging = prefs.getBoolean("charging", false),
                    currentMode = prefs.getString("current_mode", null),
                    vehicleName = prefs.getString("vehicle_name", "ATHR+") ?: "ATHR+",
                    limitPercent = prefs.getInt("limit_percent", -1).takeIf { it in 0..100 },
                    estimatedStopAtMs = prefs.getLong("estimated_stop_at", 0).takeIf { it > 0 },
                    lastRideText = prefs.getString("last_ride", "").orEmpty()
                )
            }.getOrDefault(DashboardWidgetSnapshot())
        }

        fun save(context: Context, snapshot: DashboardWidgetSnapshot) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    if (snapshot.socPercent != null) {
                        putLong(KEY_SOC, snapshot.socPercent.toRawBits())
                    } else {
                        remove(KEY_SOC)
                    }
                    if (snapshot.rangeKm != null) {
                        putLong(KEY_RANGE, snapshot.rangeKm.toRawBits())
                    } else {
                        remove(KEY_RANGE)
                    }
                    putString(KEY_SYNC, snapshot.syncLabel)
                    putString(KEY_CONN, snapshot.connectionLabel)
                    putLong(KEY_UPDATED, snapshot.updatedAtMs)
                    remove("modes")
                    putString("mode_ranges_v2", Gson().toJson(snapshot.modeRanges))
                    remove("history")
                    putString("charge_label", snapshot.chargeLabel)
                    putInt("limit_percent", snapshot.limitPercent ?: -1)
                    putLong("estimated_stop_at", snapshot.estimatedStopAtMs ?: 0)
                    putBoolean("charging", snapshot.charging)
                    putString("current_mode", snapshot.currentMode)
                    putString("vehicle_name", snapshot.vehicleName)
                    putString("last_ride", snapshot.lastRideText)
                }
                .apply()
        }
    }
}
