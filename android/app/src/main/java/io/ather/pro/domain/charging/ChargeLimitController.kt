package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry

/**
 * Phone-app charge-limit automation (not a firmware SoC cap).
 *
 * Uses the same stop command as Pause at or above the selected percentage.
 * Waits for physical confirmation, with rate-limited retries while still charging.
 * Measured cutoff requires fresh readings. The estimated-time fallback is a
 * separate policy and shares this persisted command latch and confirmation flow.
 */
object ChargeLimitController {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 100
    // Safe initial slider position only. The limit starts DISABLED and no stop is
    // issued until the rider explicitly chooses/enables a percentage.
    const val DEFAULT_PERCENT = MAX_PERCENT
    const val CONFIRM_TIMEOUT_MS: Long = 45_000L
    const val FRESH_MAX_AGE_MS: Long = 30_000L
    // Charging state changes less often than SoC in sparse telemetry. A recent
    // active session is usable for cutoff; confirmation still needs a NEW physical reading.
    const val ACTIVE_CHARGE_MAX_AGE_MS: Long = 120_000L
    const val AUTO_RETRY_DELAY_MS: Long = 60_000L
    const val FAST_RETRY_ATTEMPTS = 3
    const val SLOW_RETRY_DELAY_MS: Long = 300_000L

    enum class Status {
        DISABLED,
        MONITORING,
        PENDING,
        CONFIRMED,
        ERROR
    }

    data class Snapshot(
        val enabled: Boolean = false,
        val percent: Int = DEFAULT_PERCENT,
        val status: Status = Status.DISABLED,
        val message: String? = null,
        /** When true, a threshold crossing may request exactly one stop. */
        val armed: Boolean = false,
        val pendingSinceMs: Long? = null,
        val attempts: Int = 0,
        val lastAttemptMs: Long? = null,
        val chargerPowerW: Int = 900,
        val estimate: ChargeTimeEstimate? = null,
        val stopWasEstimated: Boolean = false,
        /** A manual pause/resume invalidates readings from before that request. */
        val estimateBlockedThroughMs: Long? = null,
        /** Saved charging speed, in percent per minute, from earlier charges. */
        val learnedPercentPerMinute: Double? = null,
        val learnedMinutes: Double = 0.0,
        val learnedSessions: Int = 0
    )

    sealed class Decision {
        data object None : Decision()
        data class StateOnly(val next: Snapshot) : Decision()
        /** Caller must issue exactly one existing remote stop request. */
        data class RequestStop(val next: Snapshot) : Decision()
    }

    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun applySettings(
        previous: Snapshot,
        enabled: Boolean,
        percent: Int,
        chargerPowerW: Int = previous.chargerPowerW
    ): Snapshot {
        val clamped = clampPercent(percent)
        val power = chargerPowerW.takeIf { it in ChargeTimeEstimator.CHARGER_POWERS } ?: previous.chargerPowerW
        if (!enabled) {
            return Snapshot(
                enabled = false,
                percent = clamped,
                status = Status.DISABLED,
                message = null,
                armed = false,
                pendingSinceMs = null,
                chargerPowerW = power,
                estimateBlockedThroughMs = previous.estimateBlockedThroughMs,
                learnedPercentPerMinute = previous.learnedPercentPerMinute,
                learnedMinutes = previous.learnedMinutes,
                learnedSessions = previous.learnedSessions
            )
        }
        val settingsChanged =
            !previous.enabled || previous.percent != clamped || previous.chargerPowerW != power
        if (!settingsChanged) return previous
        return Snapshot(
            enabled = true,
            percent = clamped,
            status = Status.MONITORING,
            message = if (settingsChanged) {
                "Limit set to $clamped%. Phone-app automation — not a firmware charge limit."
            } else {
                previous.message
            },
            // Deliberate enable / percent change re-arms for this session.
            armed = true,
            pendingSinceMs = null,
            chargerPowerW = power,
            estimateBlockedThroughMs = previous.estimateBlockedThroughMs,
            learnedPercentPerMinute = previous.learnedPercentPerMinute,
            learnedMinutes = previous.learnedMinutes,
            learnedSessions = previous.learnedSessions
        )
    }

    fun retry(previous: Snapshot): Snapshot {
        if (!previous.enabled) return previous
        if (previous.status != Status.ERROR && previous.status != Status.PENDING) {
            return previous
        }
        return previous.copy(
            status = Status.MONITORING,
            message = "Retry armed — will stop once at ${previous.percent}% if still charging.",
            armed = true,
            pendingSinceMs = null,
            attempts = 0,
            lastAttemptMs = null,
            stopWasEstimated = false
        )
    }

    fun onTelemetry(
        state: Snapshot,
        telemetry: ScooterTelemetry?,
        lastUpdatedMs: Long?,
        nowMs: Long,
        freshMaxAgeMs: Long = FRESH_MAX_AGE_MS,
        confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
        chargingUpdatedMs: Long? = lastUpdatedMs
    ): Decision {
        if (!state.enabled) {
            return if (state.status == Status.DISABLED && !state.armed && state.message == null) {
                Decision.None
            } else {
                Decision.StateOnly(
                    Snapshot(
                        enabled = false,
                        percent = state.percent,
                        chargerPowerW = state.chargerPowerW,
                        estimateBlockedThroughMs = state.estimateBlockedThroughMs,
                        learnedPercentPerMinute = state.learnedPercentPerMinute,
                        learnedMinutes = state.learnedMinutes,
                        learnedSessions = state.learnedSessions,
                        status = Status.DISABLED,
                        message = null,
                        armed = false,
                        pendingSinceMs = null
                    )
                )
            }
        }

        val plugged = ChargingControl.isPluggedIn(telemetry)
        val active = ChargingControl.isActivelyCharging(telemetry)
        val fresh = isFresh(lastUpdatedMs, nowMs, freshMaxAgeMs)
        val chargeFresh = isFresh(chargingUpdatedMs, nowMs, freshMaxAgeMs)
        val activeChargeRecent = isFresh(chargingUpdatedMs, nowMs, ACTIVE_CHARGE_MAX_AGE_MS)
        val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }

        // Pending confirmation / timeout first.
        if (state.status == Status.PENDING) {
            val started = state.pendingSinceMs
            if (started == null || nowMs < started || nowMs - started >= confirmTimeoutMs) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.ERROR,
                        message = if (state.stopWasEstimated) "Estimated stop sent, but fresh confirmation has not arrived. Check the scooter or tap Retry."
                            else "Stop not confirmed. Will retry if fresh readings still show charging above ${state.percent}%.",
                        pendingSinceMs = null
                    )
                )
            }
            if (telemetry != null && chargeFresh && chargingUpdatedMs != null &&
                chargingUpdatedMs > started && ChargingEvidence.hasChargeReading(telemetry) && !active) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.CONFIRMED,
                        message = if (state.stopWasEstimated) "Scooter confirmed charging stopped after the estimated cutoff. Final battery percentage may differ from ${state.percent}%."
                            else "Stopped at ${state.percent}% limit. Monitoring remains enabled.",
                        armed = false,
                        pendingSinceMs = null
                    )
                )
            }
            // Duplicate / still-charging frames: do not request another stop.
            return Decision.None
        }

        // If the rider resumes while still at/above the selected limit, enforce the
        // limit again. This is one request per charging restart, not per telemetry frame.
        if (state.status == Status.CONFIRMED) {
            if (fresh && activeChargeRecent && plugged && active && soc != null) {
                return if (soc >= state.percent) {
                    Decision.RequestStop(
                        state.copy(
                            status = Status.PENDING,
                            message = "Charging restarted above ${state.percent}% (SoC ${soc.toInt()}%). Sending one stop…",
                            stopWasEstimated = false,
                            armed = false,
                            pendingSinceMs = nowMs,
                            attempts = 1,
                            lastAttemptMs = nowMs
                        )
                    )
                } else {
                    Decision.StateOnly(
                        state.copy(
                            status = Status.MONITORING,
                            message = "Charging restarted below the limit; monitoring ${state.percent}%.",
                            armed = true,
                            pendingSinceMs = null
                        )
                    )
                }
            }
            if (telemetry != null && chargeFresh && !plugged) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.MONITORING,
                        attempts = 0, lastAttemptMs = null,
                        message = "Ready to enforce ${state.percent}% on the next charging session.",
                        armed = true,
                        pendingSinceMs = null
                    )
                )
            }
            return Decision.None
        }

        // A temporary outage must not permanently exhaust the automatic limit.
        // Retry once per minute initially, then at most once every five minutes.
        if (state.status == Status.ERROR) {
            if (state.attempts > 0 && state.lastAttemptMs != null && telemetry != null &&
                chargeFresh && chargingUpdatedMs != null && chargingUpdatedMs > state.lastAttemptMs &&
                ChargingEvidence.hasChargeReading(telemetry) && !active) {
                return Decision.StateOnly(state.copy(status = Status.CONFIRMED, armed = false,
                    pendingSinceMs = null, message = "Charging stopped. Limit ${state.percent}% remains enabled."))
            }
            val retryDelay = if (state.attempts < FAST_RETRY_ATTEMPTS) AUTO_RETRY_DELAY_MS else SLOW_RETRY_DELAY_MS
            if (fresh && activeChargeRecent && active && plugged && soc != null && soc >= state.percent &&
                state.lastAttemptMs != null && nowMs - state.lastAttemptMs >= retryDelay) {
                return Decision.RequestStop(state.copy(
                    status = Status.PENDING, armed = false, pendingSinceMs = nowMs,
                    attempts = state.attempts + 1, lastAttemptMs = nowMs,
                    message = "Still at/above ${state.percent}%. Sending pause again…"
                ))
            }
            if (telemetry != null && chargeFresh && !plugged && !state.armed) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.MONITORING,
                        attempts = 0, lastAttemptMs = null,
                        message = "Ready for the next charging session.",
                        armed = true,
                        pendingSinceMs = null
                    )
                )
            }
            return Decision.None
        }

        // MONITORING path.
        if (!plugged) {
            // Stay monitoring; keep armed for when a charge session starts.
            if (state.status != Status.MONITORING || state.message != null && !state.armed) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.MONITORING,
                        message = state.message,
                        armed = true,
                        pendingSinceMs = null
                    )
                )
            }
            return Decision.None
        }

        // Never act on stale or incomplete live frames.
        if (!fresh || !activeChargeRecent || soc == null || !active) {
            return if (state.status != Status.MONITORING) {
                Decision.StateOnly(
                    state.copy(status = Status.MONITORING, pendingSinceMs = null)
                )
            } else {
                Decision.None
            }
        }

        if (state.armed && soc >= state.percent) {
            return Decision.RequestStop(
                state.copy(
                    status = Status.PENDING,
                    message = "Limit ${state.percent}% reached (SoC ${soc.toInt()}%). Sending one stop…",
                    stopWasEstimated = false,
                    armed = false,
                    pendingSinceMs = nowMs,
                    attempts = state.attempts + 1,
                    lastAttemptMs = nowMs
                )
            )
        }

        return if (state.status != Status.MONITORING) {
            Decision.StateOnly(state.copy(status = Status.MONITORING, pendingSinceMs = null))
        } else {
            Decision.None
        }
    }

    fun isFresh(lastUpdatedMs: Long?, nowMs: Long, maxAgeMs: Long = FRESH_MAX_AGE_MS): Boolean {
        if (lastUpdatedMs == null) return false
        val age = nowMs - lastUpdatedMs
        return age in 0 until maxAgeMs
    }
}
