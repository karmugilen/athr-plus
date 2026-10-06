package io.ather.pro.data.charging

import android.content.Context
import android.content.SharedPreferences
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargeTimeEstimate
import io.ather.pro.domain.charging.ChargeTimeEstimator
import com.google.gson.Gson

/**
 * Per-scooter charge-limit preferences (plain private prefs; not credentials).
 */
class ChargeLimitStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(vehicleUuid: String): ChargeLimitController.Snapshot {
        if (vehicleUuid.isBlank()) {
            return ChargeLimitController.Snapshot()
        }
        val enabled = prefs.getBoolean(keyEnabled(vehicleUuid), false)
        val percent = ChargeLimitController.clampPercent(
            prefs.getInt(keyPercent(vehicleUuid), ChargeLimitController.DEFAULT_PERCENT)
        )
        val power = prefs.getInt("${vehicleUuid}_charger_power", 900)
            .takeIf { it in ChargeTimeEstimator.CHARGER_POWERS } ?: 900
        val currentTimingPolicy = prefs.getInt("${vehicleUuid}_timing_policy", 1) >= 2
        // v1 incorrectly created barriers from connector Off despite Charging.
        // Its forecasts also used unconverted second counters; rebuild both.
        val barrier = if (currentTimingPolicy) prefs.getLong("${vehicleUuid}_estimate_barrier", -1L)
            .takeIf { it > 0 } else null
        val learned = learnedRate(vehicleUuid)
        if (!enabled) return ChargeLimitController.Snapshot(percent = percent, chargerPowerW = power,
            estimateBlockedThroughMs = barrier,
            learnedPercentPerMinute = learned.first,
            learnedMinutes = learned.second,
            learnedSessions = learned.third)
        val status = runCatching {
            ChargeLimitController.Status.valueOf(prefs.getString("${vehicleUuid}_status", "MONITORING")!!)
        }.getOrDefault(ChargeLimitController.Status.MONITORING)
        return ChargeLimitController.Snapshot(
            enabled = true,
            percent = percent,
            status = status,
            armed = prefs.getBoolean("${vehicleUuid}_armed", true),
            pendingSinceMs = prefs.getLong("${vehicleUuid}_pending_since", -1L).takeIf { it > 0 },
            attempts = prefs.getInt("${vehicleUuid}_attempts", 0),
            lastAttemptMs = prefs.getLong("${vehicleUuid}_last_attempt", -1L).takeIf { it > 0 },
            message = prefs.getString("${vehicleUuid}_message", null),
            chargerPowerW = power,
            estimate = if (!currentTimingPolicy) null else runCatching {
                Gson().fromJson(prefs.getString("${vehicleUuid}_estimate", null), ChargeTimeEstimate::class.java)
                    ?.takeIf { it.isValid() && it.targetPercent == percent && it.chargerPowerW == power }
            }.getOrNull(),
            stopWasEstimated = prefs.getBoolean("${vehicleUuid}_estimated_stop", false),
            estimateBlockedThroughMs = barrier,
            learnedPercentPerMinute = learned.first,
            learnedMinutes = learned.second,
            learnedSessions = learned.third
        )
    }

    /** Persist the command latch before dispatch so process recreation cannot send a duplicate. */
    fun save(vehicleUuid: String, snapshot: ChargeLimitController.Snapshot): Boolean {
        if (vehicleUuid.isBlank()) return false
        return prefs.edit()
            .putBoolean(keyEnabled(vehicleUuid), snapshot.enabled)
            .putInt(keyPercent(vehicleUuid), ChargeLimitController.clampPercent(snapshot.percent))
            .putString("${vehicleUuid}_status", snapshot.status.name)
            .putBoolean("${vehicleUuid}_armed", snapshot.armed)
            .putLong("${vehicleUuid}_pending_since", snapshot.pendingSinceMs ?: -1L)
            .putString("${vehicleUuid}_message", snapshot.message)
            .putInt("${vehicleUuid}_timing_policy", 2)
            .putInt("${vehicleUuid}_charger_power", snapshot.chargerPowerW)
            .putString("${vehicleUuid}_estimate", snapshot.estimate?.let { Gson().toJson(it) })
            .putBoolean("${vehicleUuid}_estimated_stop", snapshot.stopWasEstimated)
            .putLong("${vehicleUuid}_estimate_barrier", snapshot.estimateBlockedThroughMs ?: -1L)
            .putInt("${vehicleUuid}_attempts", snapshot.attempts)
            .putLong("${vehicleUuid}_last_attempt", snapshot.lastAttemptMs ?: -1L)
            .putLong("${vehicleUuid}_learned_rate", snapshot.learnedPercentPerMinute?.let(java.lang.Double::doubleToRawLongBits) ?: -1L)
            .putLong("${vehicleUuid}_learned_minutes", java.lang.Double.doubleToRawLongBits(snapshot.learnedMinutes))
            .putInt("${vehicleUuid}_learned_sessions", snapshot.learnedSessions)
            .commit()
    }

    private fun learnedRate(vehicleUuid: String): Triple<Double?, Double, Int> {
        val bits = prefs.getLong("${vehicleUuid}_learned_rate", -1L)
        val rate = if (bits == -1L) null else java.lang.Double.longBitsToDouble(bits)
            .takeIf { it.isFinite() && it in 0.01..10.0 }
        val minutes = java.lang.Double.longBitsToDouble(prefs.getLong("${vehicleUuid}_learned_minutes", 0L))
            .takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val sessions = prefs.getInt("${vehicleUuid}_learned_sessions", 0).coerceAtLeast(0)
        return Triple(rate, if (rate == null) 0.0 else minutes, sessions)
    }

    companion object {
        const val PREFS_NAME = "ather_charge_limit"

        private fun keyEnabled(uuid: String) = "${uuid}_enabled"
        private fun keyPercent(uuid: String) = "${uuid}_percent"
    }
}
