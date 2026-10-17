package io.ather.pro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ather.pro.data.auth.AuthExpiredException
import io.ather.pro.data.auth.AuthSession
import io.ather.pro.data.auth.AuthStep
import io.ather.pro.data.auth.AuthUiState
import io.ather.pro.data.auth.AtherAuthApi
import io.ather.pro.data.auth.DiscoveredScooter
import io.ather.pro.data.auth.PhoneNumbers
import io.ather.pro.data.auth.JwtExpiry
import io.ather.pro.data.auth.SecureSessionStore
import io.ather.pro.data.repository.AtherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(
    private val sessionStore: SecureSessionStore,
    private val authApi: AtherAuthApi,
    private val repository: AtherRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.session.collect { session ->
                if (session?.isComplete == true) {
                    repository.applyCredentials(session.token, session.vehicleUuid)
                    _ui.update {
                        it.copy(
                            step = AuthStep.READY,
                            session = session,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                } else if (session == null && _ui.value.step == AuthStep.READY) {
                    repository.clearCredentials()
                    _ui.update {
                        AuthUiState(step = AuthStep.PHONE, phone = it.phone, countryCode = it.countryCode)
                    }
                }
            }
        }
    }

    fun onPhoneChanged(value: String) {
        if (_ui.value.isLoading) return
        val input = value.filter { it in '0'..'9' || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }.take(32)
        _ui.update { state ->
            val international = if (input.trim().startsWith("+")) PhoneNumbers.normalize(input, state.countryCode) else null
            state.copy(phone = international?.national ?: input,
                countryCode = international?.region ?: state.countryCode, errorMessage = null)
        }
    }

    fun onCountryChanged(region: String) {
        if (_ui.value.isLoading || _ui.value.step != AuthStep.PHONE) return
        if (PhoneNumbers.countries.none { it.region == region }) return
        _ui.update { it.copy(countryCode = region, errorMessage = null) }
    }

    fun onOtpChanged(value: String) {
        if (_ui.value.isLoading) return
        _ui.update { it.copy(otp = value.filter { ch -> ch in '0'..'9' }.take(8), errorMessage = null) }
    }

    fun requestOtp() {
        val current = _ui.value
        if (current.isLoading || current.step !in listOf(AuthStep.PHONE, AuthStep.OTP)) return
        if (current.step == AuthStep.OTP && System.currentTimeMillis() < current.resendAvailableAtMillis) return
        val number = PhoneNumbers.normalize(current.phone, current.countryCode)
        if (number == null) {
            _ui.update { it.copy(errorMessage = "Enter a valid mobile number for the selected country") }
            return
        }
        _ui.update { it.copy(phone = number.national, countryCode = number.region, isLoading = true, errorMessage = null) }
        authApi.requestOtp(number.national, number.region) { result ->
            _ui.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(step = AuthStep.OTP, otp = "", isLoading = false, errorMessage = null,
                            resendAvailableAtMillis = System.currentTimeMillis() + 30_000L)
                    },
                    onFailure = { error ->
                        state.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Could not send OTP"
                        )
                    }
                )
            }
        }
    }

    fun verifyOtp() {
        val state = _ui.value
        if (state.isLoading || state.step != AuthStep.OTP) return
        val phone = state.phone.trim()
        val otp = state.otp.trim()
        if (otp.length < 4) {
            _ui.update { it.copy(errorMessage = "Enter the OTP from SMS") }
            return
        }
        _ui.update { it.copy(isLoading = true, errorMessage = null) }
        authApi.verifyOtp(phone, otp, state.countryCode) { result ->
            result.fold(
                onSuccess = { token ->
                    discoverScooters(token, phone)
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "OTP verification failed"
                        )
                    }
                }
            )
        }
    }

    fun selectScooter(scooter: DiscoveredScooter) {
        val token = _ui.value.pendingToken ?: return
        completeSession(token, scooter, _ui.value.phone)
    }

    fun retryScooters() {
        val token = _ui.value.pendingToken ?: return
        _ui.update { it.copy(isLoading = true, errorMessage = null) }
        discoverScooters(token, _ui.value.phone)
    }

    fun backToPhone() {
        _ui.update {
            it.copy(
                step = AuthStep.PHONE,
                otp = "",
                pendingToken = null,
                scooters = emptyList(),
                isLoading = false,
                errorMessage = null
            )
        }
    }

    fun logout() {
        repository.clearCredentials()
        sessionStore.clear()
        _ui.value = AuthUiState()
    }

    fun importSeed() {
        if (_ui.value.isLoading) return
        val imported = sessionStore.importSeedNow()
        if (imported == null) {
            _ui.update {
                it.copy(
                    errorMessage = "No valid ather_session.json found. Copy it to " +
                        "Android/data/io.ather.pro/files/ather_session.json and try again."
                )
            }
        } else {
            repository.applyCredentials(imported.token, imported.vehicleUuid)
            _ui.update {
                it.copy(
                    step = AuthStep.READY,
                    session = imported,
                    isLoading = false,
                    errorMessage = null
                )
            }
        }
    }

    private fun discoverScooters(token: String, phone: String) {
        authApi.fetchScooters(token) { result ->
            result.fold(
                onSuccess = { scooters ->
                    if (scooters.size == 1) {
                        completeSession(token, scooters.first(), phone)
                    } else {
                        _ui.update {
                            it.copy(
                                step = AuthStep.SCOOTER_SELECT,
                                pendingToken = token,
                                scooters = scooters,
                                isLoading = false,
                                errorMessage = if (scooters.isEmpty()) {
                                    "No scooters linked on this account yet."
                                } else {
                                    null
                                }
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (error is AuthExpiredException) {
                        sessionStore.clear()
                        _ui.update {
                            AuthUiState(
                                step = AuthStep.PHONE,
                                phone = phone,
                                countryCode = _ui.value.countryCode,
                                errorMessage = error.message
                            )
                        }
                    } else {
                        _ui.update {
                            it.copy(
                                step = AuthStep.SCOOTER_SELECT,
                                pendingToken = token,
                                scooters = emptyList(),
                                isLoading = false,
                                errorMessage = error.message
                                    ?: "Could not load scooters. Tap Retry."
                            )
                        }
                    }
                }
            )
        }
    }

    private fun completeSession(token: String, scooter: DiscoveredScooter, phone: String) {
        val session = AuthSession(
            token = token,
            vehicleUuid = scooter.uuid,
            phone = phone,
            countryCode = _ui.value.countryCode,
            displayName = scooter.displayName,
            expiresAtEpochSec = JwtExpiry.expiresAtEpochSec(token)
        )
        sessionStore.save(session)
        repository.applyCredentials(session.token, session.vehicleUuid)
        _ui.update {
            it.copy(
                step = AuthStep.READY,
                session = session,
                isLoading = false,
                errorMessage = null,
                pendingToken = null
            )
        }
    }

    private fun initialState(): AuthUiState {
        val session = sessionStore.current()
        return if (session?.isComplete == true) {
            AuthUiState(step = AuthStep.READY, session = session, phone = session.phone.orEmpty(), countryCode = session.countryCode)
        } else {
            AuthUiState()
        }
    }

    class Factory(
        private val sessionStore: SecureSessionStore,
        private val authApi: AtherAuthApi,
        private val repository: AtherRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                return AuthViewModel(sessionStore, authApi, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
