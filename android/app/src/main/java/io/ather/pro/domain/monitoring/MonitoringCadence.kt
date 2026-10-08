package io.ather.pro.domain.monitoring

import io.ather.pro.domain.charging.ChargeSnapshotRefresh

/** Idle telemetry needs a snapshot timer; charging automation still needs prompt deadlines. */
object MonitoringCadence {
    fun nextTickDelayMs(
        limitEnabled: Boolean,
        commandPending: Boolean,
        connectedAtMs: Long?,
        nowMs: Long
    ): Long {
        if (limitEnabled || commandPending) return 1_000L
        if (connectedAtMs == null) return ChargeSnapshotRefresh.INTERVAL_MS
        if (nowMs < connectedAtMs) return 1_000L
        return (ChargeSnapshotRefresh.INTERVAL_MS - (nowMs - connectedAtMs))
            .coerceIn(1_000L, ChargeSnapshotRefresh.INTERVAL_MS)
    }
}
