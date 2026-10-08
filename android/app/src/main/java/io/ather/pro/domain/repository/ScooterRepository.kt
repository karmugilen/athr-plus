package io.ather.pro.domain.repository

import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import kotlinx.coroutines.flow.StateFlow

interface ScooterRepository {
    val dashboard: StateFlow<ScooterDashboardState>

    val chargeLimit: StateFlow<ChargeLimitController.Snapshot>
    val insights: StateFlow<io.ather.pro.domain.insights.RiderInsights>
    fun updateDailyPlan(distanceKm: Double, reserve: Int)
    fun setTyreReminders(enabled: Boolean)

    fun refresh()

    fun disconnect()

    fun updateModel(model: ScooterModel)

    fun updateTariff(tariffRate: Double)

    fun clearTrips()

    fun pauseCharging(): Boolean

    fun resumeCharging(): Boolean

    /** Clears a stale pending/error latch so the rider can retry Start/Stop. */
    fun clearRemoteChargingLatch()

    /** Advance pending→ERROR on timeout even when telemetry is quiet (keeps buttons usable). */
    fun tickRemoteChargingTimeouts()

    fun setChargeLimit(enabled: Boolean, percent: Int, chargerPowerW: Int? = null)

    fun retryChargeLimit()
}
