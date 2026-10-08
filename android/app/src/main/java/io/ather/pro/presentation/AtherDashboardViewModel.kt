package io.ather.pro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.repository.ScooterRepository
import kotlinx.coroutines.flow.StateFlow

class AtherDashboardViewModel(
    private val repository: ScooterRepository
) : ViewModel() {
    val dashboard: StateFlow<ScooterDashboardState> = repository.dashboard
    val chargeLimit: StateFlow<ChargeLimitController.Snapshot> = repository.chargeLimit
    val insights = repository.insights
    fun updateDailyPlan(distanceKm: Double, reserve: Int) = repository.updateDailyPlan(distanceKm, reserve)
    fun setTyreReminders(enabled: Boolean) = repository.setTyreReminders(enabled)

    fun refresh() = repository.refresh()

    fun setScooterModel(model: io.ather.pro.domain.model.ScooterModel) {
        repository.updateModel(model)
    }

    fun setTariffRate(rate: Double) {
        repository.updateTariff(rate)
    }

    fun clearTripHistory() {
        repository.clearTrips()
    }

    fun pauseCharging(): Boolean = repository.pauseCharging()

    fun resumeCharging(): Boolean = repository.resumeCharging()

    fun clearRemoteChargingLatch() {
        repository.clearRemoteChargingLatch()
    }

    fun tickRemoteChargingTimeouts() {
        repository.tickRemoteChargingTimeouts()
    }

    fun setChargeLimit(enabled: Boolean, percent: Int, chargerPowerW: Int? = null) {
        repository.setChargeLimit(enabled, percent, chargerPowerW)
    }

    fun retryChargeLimit() {
        repository.retryChargeLimit()
    }

    class Factory(
        private val repository: ScooterRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AtherDashboardViewModel::class.java)) {
                return AtherDashboardViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
