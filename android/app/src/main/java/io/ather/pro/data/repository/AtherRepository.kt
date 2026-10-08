package io.ather.pro.data.repository

import android.content.Context
import io.ather.pro.data.api.AtherApiClient
import io.ather.pro.data.api.AtherCloudApi
import io.ather.pro.data.charging.ChargeLimitStore
import io.ather.pro.data.local.DashboardLocalStore
import io.ather.pro.data.local.SavedScooterReading
import io.ather.pro.data.local.SavedScooterReadingPrefs
import io.ather.pro.data.local.SavedScooterReadingStore
import io.ather.pro.data.local.TripBaseline
import io.ather.pro.domain.charging.ChargingEvidence
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargeLimitSession
import io.ather.pro.domain.charging.ChargeSnapshotRefresh
import io.ather.pro.domain.battery.BatteryHistory
import io.ather.pro.domain.battery.RideHistory
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.charging.RemoteChargingDispatcher
import io.ather.pro.domain.charging.RemoteChargingGateway
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.monitoring.MonitoringCadence
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.model.ScooterSettings
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TripRecord
import io.ather.pro.domain.repository.ScooterRepository
import io.ather.pro.domain.ride.RideLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import okhttp3.WebSocket
import java.util.UUID

class AtherRepository(
    context: Context? = null,
    remoteChargingGateway: RemoteChargingGateway? = null,
    private val api: AtherCloudApi = AtherApiClient(),
    repositoryScope: CoroutineScope? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    savedReadingStore: SavedScooterReadingStore? = null
) : ScooterRepository {
    private val appContext = context?.applicationContext
    private val localStore: DashboardLocalStore? by lazy { appContext?.let(::DashboardLocalStore) }
    private val preferences = appContext?.getSharedPreferences("ather_dashboard_settings", Context.MODE_PRIVATE)
    private val chargeLimitStore: ChargeLimitStore? = appContext?.let(::ChargeLimitStore)
    private val savedReadings: SavedScooterReadingStore? = savedReadingStore ?: appContext?.let { SavedScooterReadingPrefs(it, clock) }
    private val riderInsights = io.ather.pro.data.insights.RiderInsightsRepository(appContext)
    override val insights = riderInsights.state

    override fun updateDailyPlan(distanceKm: Double, reserve: Int) { scope.launch { riderInsights.updatePlan(distanceKm, reserve) } }
    override fun setTyreReminders(enabled: Boolean) { scope.launch { riderInsights.setTyreReminders(enabled) } }
    private var lastReadingSaveAt = 0L
    private val chargingDispatcher = RemoteChargingDispatcher(
        gateway = remoteChargingGateway ?: api.asRemoteChargingGateway(),
        nowMs = clock
    )
    // One application-owned worker serializes telemetry, persistence and command decisions.
    private val scope = repositoryScope ?: CoroutineScope(SupervisorJob() +
        Executors.newSingleThreadExecutor { task -> Thread(task, "ather-repository") }.asCoroutineDispatcher())
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var commandTimeoutJob: Job? = null
    private var manuallyDisconnected = false

    // Persisted sync-to-sync trip baseline. Odometer and SoC often arrive in separate
    // WebSocket deltas, so this baseline must not be reset by either field alone.
    private var lastSavedOdo: Double? = null
    private var lastSavedSoc: Double? = null
    private var lastSavedTimestamp: Long = clock()
    private var lastHistoryPersistAt = 0L
    private var evidence = ChargingEvidence()
    private val chargeSession = ChargeLimitSession()
    @Volatile private var generation = 0L
    private var reconnectAttempt = 0
    @Volatile private var snapshotConnectedAt: Long? = null

    private val _dashboard = MutableStateFlow(ScooterDashboardState(
        settings = ScooterSettings(
            selectedModel = runCatching { ScooterModel.valueOf(preferences?.getString("model", null).orEmpty()) }
                .getOrDefault(ScooterModel.ATHER_450X_3_7),
            tariffRatePerKWh = preferences?.getFloat("tariff", 8f)?.toDouble() ?: 8.0
        ),
        connection = ConnectionStatus.DISCONNECTED
    ))
    private val storageReady = scope.async {
        runCatching {
        val restored = localStore?.loadTripBaseline()
        lastSavedOdo = restored?.odometerKm
        lastSavedSoc = restored?.batterySoc
        lastSavedTimestamp = restored?.timestampMs ?: clock()
        val trips = localStore?.loadTrips().orEmpty()
        val history = localStore?.loadTelemetryHistory().orEmpty()
        val rides = localStore?.loadRideHistory().orEmpty()
        _dashboard.update { it.copy(recentTrips = trips, telemetryHistory = history, rideHistory = rides) }
        }.onFailure {
            _dashboard.update { it.copy(errorMessage = "Saved history is unavailable. Live scooter data can still connect.") }
        }
        Unit
    }
    override val dashboard: StateFlow<ScooterDashboardState> = _dashboard.asStateFlow()

    private val _chargeLimit = MutableStateFlow(ChargeLimitController.Snapshot())
    override val chargeLimit: StateFlow<ChargeLimitController.Snapshot> = _chargeLimit.asStateFlow()

    @Volatile
    private var authToken: String? = null

    @Volatile
    private var vehicleUuid: String? = null

    @Volatile
    private var authInvalidated = false

    private val _authenticationRequired = MutableStateFlow<String?>(null)
    val authenticationRequired: StateFlow<String?> = _authenticationRequired.asStateFlow()

    init {
        INSTANCE = this
        commandTimeoutJob = scope.launch {
            combine(dashboard, chargeLimit) { state, limit ->
                // Restart the timer for lifecycle/command changes, not every telemetry packet.
                Triple(state.connection, state.remoteChargingCommand, limit.enabled)
            }.distinctUntilChanged().collectLatest { (connection, command, limitEnabled) ->
                while (isActive) {
                    delay(MonitoringCadence.nextTickDelayMs(
                        limitEnabled = limitEnabled,
                        commandPending = command.phase == RemoteCommandPhase.SENDING ||
                            command.phase == RemoteCommandPhase.ACCEPTED,
                        connectedAtMs = snapshotConnectedAt.takeIf { connection == ConnectionStatus.CONNECTED },
                        nowMs = clock()
                    ))
                    tickRemoteChargingTimeouts()
                    tickChargeMonitoring()
                }
            }
        }
    }

    /** Called on the repository worker; a fresh connection also covers quiet sockets. */
    internal fun tickChargeMonitoring() {
        if (!hasCredentials() || manuallyDisconnected) return
        val now = clock()
        processChargeLimit(now)
        val state = _dashboard.value
        // Incoming packets do not postpone the fixed snapshot check.
        if (ChargeSnapshotRefresh.isDue(true, state.connection, snapshotConnectedAt, now)) {
            connect(refreshSnapshot = true)
        }
    }

    @Synchronized
    fun applyCredentials(token: String, uuid: String) {
        if (token.isBlank() || uuid.isBlank()) return
        val changed = authToken != token || vehicleUuid != uuid
        authToken = token
        vehicleUuid = uuid
        authInvalidated = false
        _authenticationRequired.value = null
        if (changed) {
            riderInsights.selectVehicle(uuid)
            generation += 1
            evidence = ChargingEvidence()
            chargeSession.resetRate()
            _dashboard.update {
                val cleared = it.copy(telemetry = null, lastUpdated = null, batteryUpdatedAt = null,
                    chargingUpdatedAt = null, batteryReportedAt = null, chargingRatePercentPerMinute = null,
                    chargingRateMinutes = 0.0, gpsUpdatedAt = null,
                    remoteChargingCommand = RemoteChargingCommand())
                dashboardWithSavedReading(cleared, uuid)
            }
            loadChargeLimitForVehicle(uuid)
        }
        if (changed || socket == null) {
            loadVehicleProfileAndRides()
            connect()
        }
    }

    @Synchronized
    fun clearCredentials() {
        riderInsights.selectVehicle(null)
        generation += 1
        evidence = ChargingEvidence()
        chargeSession.resetRate()
        authToken = null
        vehicleUuid = null
        manuallyDisconnected = true
        reconnectJob?.cancel()
        socket?.close(1000, "Signed out")
        socket = null
        _chargeLimit.value = ChargeLimitController.Snapshot()
        _dashboard.update {
            it.copy(
                connection = ConnectionStatus.DISCONNECTED,
                errorMessage = null,
                telemetry = null,
                lastUpdated = null, gpsUpdatedAt = null, batteryUpdatedAt = null, chargingUpdatedAt = null,
                batteryReportedAt = null, chargingRatePercentPerMinute = null, chargingRateMinutes = 0.0,
                vehicleProfile = null,
                remoteChargingCommand = RemoteChargingCommand()
            )
        }
    }

    fun hasCredentials(): Boolean = !authToken.isNullOrBlank() && !vehicleUuid.isNullOrBlank()

    private fun requireCredentials(): Pair<String, String>? {
        val token = authToken
        val uuid = vehicleUuid
        if (token.isNullOrBlank() || uuid.isNullOrBlank()) {
            _dashboard.update {
                it.copy(
                    connection = ConnectionStatus.DISCONNECTED,
                    errorMessage = "Sign in required"
                )
            }
            return null
        }
        return token to uuid
    }

    private fun handleAuthFailure(message: String) {
        if (authInvalidated) return
        authInvalidated = true
        generation += 1
        manuallyDisconnected = true
        reconnectJob?.cancel()
        socket?.close(1000, "Authentication expired")
        socket = null
        authToken = null
        vehicleUuid = null
        _dashboard.update {
            it.copy(
                connection = ConnectionStatus.ERROR,
                errorMessage = message.ifBlank { "Session expired. Please sign in again." }
            )
        }
        _authenticationRequired.value = message.ifBlank { "Your session has expired." }
    }

    private fun isAuthFailureMessage(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("session expired") ||
            lower.contains("unauthorized") ||
            Regex("\\b401\\b").containsMatchIn(lower) ||
            lower.contains("not authorized")
    }

    private fun loadVehicleProfileAndRides() {
        val (token, uuid) = requireCredentials() ?: return
        api.fetchVehicleProfile(token, uuid) { result ->
            if (authToken != token || vehicleUuid != uuid) return@fetchVehicleProfile
            result.onSuccess { profile ->
                _dashboard.update { state ->
                    val apiModel = profile.resolvedModel
                    state.copy(
                        vehicleProfile = profile,
                        settings = if (apiModel != null && preferences?.contains("model") != true) {
                            state.settings.copy(selectedModel = apiModel)
                        } else state.settings
                    )
                }
                profile.scooterId?.let(::loadOfficialRides)
            }.onFailure { error ->
                val message = error.message.orEmpty()
                if (isAuthFailureMessage(message)) {
                    handleAuthFailure(message)
                } else {
                    _dashboard.update { state ->
                        state.copy(errorMessage = state.errorMessage ?: "Vehicle details unavailable: ${error.message}")
                    }
                }
            }
        }
    }

    private fun loadOfficialRides(scooterId: String) {
        val (token, selectedVehicle) = requireCredentials() ?: return
        api.fetchRides(token = token, scooterId = scooterId) { result ->
            scope.launch {
                storageReady.await()
                if (authToken != token || vehicleUuid != selectedVehicle) return@launch
                result.onSuccess { mapped ->
                    val priced = _dashboard.value
                    val officialRides = mapped.map { fields ->
                        RideLog.fromCloud(fields, priced.modelForRange.usableCapacityWh, priced.settings.tariffRatePerKWh)
                    }
                    val localRides = _dashboard.value.recentTrips.filterNot { it.isOfficialRide }
                    val merged = (officialRides + localRides).distinctBy(TripRecord::id)
                        .sortedByDescending(TripRecord::startTimeMs).take(100)
                    _dashboard.update { it.copy(recentTrips = merged) }
                    riderInsights.reviewTrips(merged, clock())
                    persist { saveTrips(merged) }
                }.onFailure { error ->
                    if (isAuthFailureMessage(error.message.orEmpty())) handleAuthFailure(error.message.orEmpty())
                }
            }
        }
    }

    @Synchronized
    private fun connect(refreshSnapshot: Boolean = false) {
        val (token, uuid) = requireCredentials() ?: return
        manuallyDisconnected = false
        reconnectJob?.cancel()
        val connectionGeneration = ++generation
        socket?.cancel()
        snapshotConnectedAt = null
        _dashboard.update { it.copy(connection = if (refreshSnapshot && it.connection == ConnectionStatus.CONNECTED)
            ConnectionStatus.CONNECTED else ConnectionStatus.CONNECTING, errorMessage = null) }
        socket = api.connect(token, uuid, object : AtherCloudApi.Listener {
            private var receivedSnapshot = false
            override fun onConnected(socket: WebSocket) {
                if (generation != connectionGeneration || manuallyDisconnected) { socket.cancel(); return }
                reconnectAttempt = 0
                this@AtherRepository.socket = socket
                snapshotConnectedAt = clock()
                _dashboard.update { it.copy(connection = ConnectionStatus.CONNECTED, errorMessage = null) }
                api.subscribe(socket)
            }

            override fun onTelemetry(telemetry: ScooterTelemetry) {
                scope.launch {
                    storageReady.await()
                    if (generation == connectionGeneration && !manuallyDisconnected) {
                        handleIncomingTelemetry(telemetry, snapshot = !receivedSnapshot, expectedGeneration = connectionGeneration)
                        receivedSnapshot = true
                    }
                }
            }

            override fun onDisconnected(reason: String) {
                if (generation != connectionGeneration) return
                socket = null
                if (!manuallyDisconnected) {
                    if (isAuthFailureMessage(reason)) {
                        handleAuthFailure(reason)
                    } else {
                        _dashboard.update { it.copy(connection = ConnectionStatus.DISCONNECTED, errorMessage = reason) }
                        scheduleReconnect()
                    }
                }
            }

            override fun onError(message: String) {
                if (generation != connectionGeneration || manuallyDisconnected) return
                if (isAuthFailureMessage(message)) {
                    handleAuthFailure(message)
                } else {
                    _dashboard.update { it.copy(connection = ConnectionStatus.ERROR, errorMessage = message) }
                }
            }
        })
    }

    @Synchronized
    private fun handleIncomingTelemetry(rawTelemetry: ScooterTelemetry, snapshot: Boolean = false,
        expectedGeneration: Long = generation) {
        if (generation != expectedGeneration || manuallyDisconnected) return
        val observedAt = clock()
        val existingTelemetry = _dashboard.value.telemetry
        if (rawTelemetry.sourceTimestampMs != null && existingTelemetry?.sourceTimestampMs != null &&
            rawTelemetry.sourceTimestampMs < existingTelemetry.sourceTimestampMs) return
        evidence = evidence.observe(rawTelemetry, observedAt, snapshot)
        val mergedTelemetry = existingTelemetry?.mergeWith(rawTelemetry) ?: rawTelemetry

        val currentSettings = _dashboard.value.settings
        val pack = _dashboard.value.modelForRange
        val usableCapacityWh = pack.usableCapacityWh

        // 1. Calculate Mode Efficiencies (Algorithm A)
        val soc = mergedTelemetry.batterySoc ?: 0.0
        val remainingEnergyWh = usableCapacityWh * (soc / 100.0)

        val enrichedModeRanges = mergedTelemetry.modeRanges.mapValues { (_, modeRange) ->
            val predKm = modeRange.predictedRangeKm ?: modeRange.rawRangeKm
            val derivedWhPerKm = if (predKm != null && predKm > 0.0 && remainingEnergyWh > 0.0) {
                remainingEnergyWh / predKm
            } else null
            val kmPerKWh = if (derivedWhPerKm != null && derivedWhPerKm > 0.0) {
                1000.0 / derivedWhPerKm
            } else null
            modeRange.copy(
                derivedWhPerKm = derivedWhPerKm,
                kmPerKWh = kmPerKWh
            )
        }

        val rideEff = enrichedModeRanges["Ride"]?.derivedWhPerKm
        val smartEcoEff = enrichedModeRanges["SmartEco"]?.derivedWhPerKm
        val avgWhPerKm = when {
            rideEff != null && rideEff > 0.0 -> rideEff
            smartEcoEff != null && smartEcoEff > 0.0 -> smartEcoEff
            else -> usableCapacityWh / pack.referenceCycleRangeKm
        }

        // Ather's available properties/telemetry endpoints do not provide battery SoH.
        // Keep it null instead of presenting an age/cycle formula as a BMS measurement.
        val fullTelemetry = mergedTelemetry.copy(
            modeRanges = enrichedModeRanges,
            batteryHealth = null
        )

        // 3. Auto-detect Trips (Algorithm B)
        val previousOdo = lastSavedOdo
        val previousSoc = lastSavedSoc
        val now = clock()

        var updatedTrips = _dashboard.value.recentTrips

        val currentOdo = fullTelemetry.odoKm
        val currentBatterySoc = fullTelemetry.batterySoc
        if (previousOdo != null && previousSoc != null && currentOdo != null && currentBatterySoc != null) {
            when (val leg = RideLog.close(
                RideLog.Leg(
                    previousOdoKm = previousOdo,
                    previousSoc = previousSoc,
                    openedAtMs = lastSavedTimestamp,
                    odoKm = currentOdo,
                    soc = currentBatterySoc,
                    nowMs = now,
                    vehicleState = fullTelemetry.vehicleState.orEmpty(),
                    speedKmh = fullTelemetry.gps?.speed ?: Double.NaN,
                    charging = fullTelemetry.charging == true,
                    avgWhPerKm = avgWhPerKm,
                    usableCapacityWh = usableCapacityWh,
                    tariffRatePerKWh = currentSettings.tariffRatePerKWh
                ),
                id = UUID.randomUUID().toString()
            )) {
                RideLog.LegOutcome.Wait -> Unit
                is RideLog.LegOutcome.Reset -> updateTripBaseline(leg.odoKm, leg.soc, leg.atMs)
                is RideLog.LegOutcome.Closed -> {
                    updatedTrips = (listOf(leg.trip) + updatedTrips).take(100)
                    persist { saveTrips(updatedTrips) }
                    updateTripBaseline(leg.odoKm, leg.soc, leg.atMs)
                }
            }
        } else if (currentOdo != null && currentBatterySoc != null) {
            updateTripBaseline(currentOdo, currentBatterySoc, now)
        }

        val currentCount = _dashboard.value.packetCount + 1
        val updatedTimestamps = (_dashboard.value.recentPacketTimestamps + now).takeLast(60)

        val batterySampleTime = if (rawTelemetry.batterySoc?.let { it.isFinite() && it in 0.0..100.0 } == true)
            rawTelemetry.sourceTimestampMs ?: if (!snapshot) now else null
            else null
        val updatedHistory = if (batterySampleTime != null && now - batterySampleTime in 0L..120_000L) BatteryHistory.record(
            history = _dashboard.value.telemetryHistory,
            report = rawTelemetry,
            observedAt = batterySampleTime
        ) else _dashboard.value.telemetryHistory

        val rideHistory = RideHistory.record(_dashboard.value.rideHistory, rawTelemetry, now)
        if (now - lastHistoryPersistAt >= HISTORY_PERSIST_INTERVAL_MS) {
            persist { saveRideHistory(rideHistory) }
            persist { saveTelemetryHistory(updatedHistory) }
            lastHistoryPersistAt = now
        }

        rememberReading(fullTelemetry)
        _dashboard.update {
            val chargeIsNew = evidence.chargingAt?.let { at ->
                ChargeLimitController.isFresh(at, now) && at > (it.remoteChargingCommand.requestedAt ?: Long.MAX_VALUE)
            } == true
            val command = if (ChargingEvidence.hasChargeReading(rawTelemetry) && chargeIsNew) ChargingControl.advanceCommand(
                command = it.remoteChargingCommand,
                telemetry = fullTelemetry,
                nowMs = now
            ) else it.remoteChargingCommand
            it.copy(
                telemetry = fullTelemetry,
                lastUpdated = now,
                batteryUpdatedAt = evidence.batteryAt,
                batteryReportedAt = batterySampleTime ?: it.batteryReportedAt,
                chargingUpdatedAt = evidence.chargingAt,
                gpsUpdatedAt = if (rawTelemetry.gps?.latitude != null && rawTelemetry.gps.longitude != null) now else it.gpsUpdatedAt,
                recentTrips = updatedTrips,
                packetCount = currentCount,
                recentPacketTimestamps = updatedTimestamps,
                telemetryHistory = updatedHistory,
                rideHistory = rideHistory,
                remoteChargingCommand = command
            )
        }
        processChargeLimit(now)
        riderInsights.observe(rawTelemetry, snapshot, now, _chargeLimit.value)
        riderInsights.confirmCutoff(_chargeLimit.value)
    }

    private fun loadChargeLimitForVehicle(uuid: String) {
        _chargeLimit.value = chargeLimitStore?.load(uuid) ?: ChargeLimitController.Snapshot()
    }

    private fun updateChargeLimit(next: ChargeLimitController.Snapshot): Boolean {
        val uuid = vehicleUuid ?: return false
        val saved = chargeLimitStore?.save(uuid, next) ?: true
        _chargeLimit.value = if (saved) next else next.copy(status = ChargeLimitController.Status.ERROR,
            armed = false, message = "Could not save the charge limit. Free phone storage and retry.")
        riderInsights.confirmCutoff(_chargeLimit.value)
        return saved
    }

    override fun setChargeLimit(enabled: Boolean, percent: Int, chargerPowerW: Int?) {
        scope.launch {
            updateChargeLimit(ChargeLimitController.applySettings(_chargeLimit.value, enabled, percent,
                chargerPowerW ?: _chargeLimit.value.chargerPowerW))
            processChargeLimit(clock())
        }
    }

    override fun retryChargeLimit() {
        scope.launch {
            updateChargeLimit(ChargeLimitController.retry(_chargeLimit.value))
            processChargeLimit(clock())
        }
    }

    /** A saved reading can paint the screen. Pause waits for a live packet in this process. */
    private fun dashboardWithSavedReading(state: ScooterDashboardState, uuid: String): ScooterDashboardState {
        val saved = savedReadings?.load(uuid) ?: return state
        return state.copy(
            telemetry = saved.toTelemetry(),
            lastUpdated = saved.savedAtMs,
            batteryUpdatedAt = saved.savedAtMs,
            batteryReportedAt = saved.sourceTimestampMs ?: saved.savedAtMs,
            gpsUpdatedAt = if (saved.latitude != null && saved.longitude != null) saved.savedAtMs else null
        )
    }

    private fun rememberReading(telemetry: ScooterTelemetry) {
        val store = savedReadings ?: return
        val uuid = vehicleUuid ?: return
        val now = clock()
        if (now - lastReadingSaveAt < 30_000L) return
        val reading = SavedScooterReading.fromTelemetry(uuid, telemetry, now) ?: return
        lastReadingSaveAt = now
        store.save(reading)
    }

    @Synchronized
    private fun processChargeLimit(nowMs: Long) {
        if (!hasCredentials() || manuallyDisconnected) return
        if (evidence.batteryAt == null && evidence.chargingAt == null) return
        val dashboard = _dashboard.value
        val telemetry = dashboard.telemetry
        val reportedAt = dashboard.batteryReportedAt ?: evidence.batteryAt
        val step = chargeSession.observe(
            state = _chargeLimit.value,
            telemetry = telemetry,
            batteryFreshAtMs = evidence.batteryAt,
            batteryReportedAtMs = reportedAt,
            chargingFreshAtMs = evidence.chargingAt,
            nowMs = nowMs,
            capacityWh = dashboard.modelForRange.usableCapacityWh
        )
        if (dashboard.chargingRatePercentPerMinute != step.liveRate ||
            dashboard.chargingRateMinutes != step.liveMinutes
        ) {
            _dashboard.update {
                it.copy(
                    chargingRatePercentPerMinute = step.liveRate,
                    chargingRateMinutes = step.liveMinutes
                )
            }
        }
        val decision = step.decision
        when (decision) {
            ChargeLimitController.Decision.None -> {
                if (step.snapshot != _chargeLimit.value) updateChargeLimit(step.snapshot)
            }
            is ChargeLimitController.Decision.StateOnly -> {
                updateChargeLimit(decision.next)
            }
            is ChargeLimitController.Decision.RequestStop -> {
                if (!updateChargeLimit(decision.next)) return
                val command = _dashboard.value.remoteChargingCommand
                val stopAlreadyPending =
                    (command.phase == RemoteCommandPhase.SENDING ||
                        command.phase == RemoteCommandPhase.ACCEPTED) &&
                        command.action.equals("stop", ignoreCase = true)
                if (stopAlreadyPending) {
                    // A manual stop is already in flight. Attach the limit state to
                    // its telemetry confirmation without sending a duplicate command.
                    return
                }
                val dispatched = requestPause { result ->
                    val current = _chargeLimit.value
                    if (current.status == ChargeLimitController.Status.PENDING &&
                        current.pendingSinceMs == decision.next.pendingSinceMs) {
                        updateChargeLimit(result.fold(
                            onSuccess = {
                                current.copy(message = if (current.stopWasEstimated)
                                    "Estimated Pause accepted by Ather; waiting for a new charging reading to confirm the stop."
                                    else "Pause accepted by Ather; waiting for scooter confirmation.")
                            },
                            onFailure = { error ->
                                current.copy(status = ChargeLimitController.Status.ERROR,
                                    message = error.message ?: "Automatic stop rejected. Tap Retry limit.",
                                    pendingSinceMs = null)
                            }
                        ))
                    }
                }
                if (!dispatched) {
                    val reason = _dashboard.value.remoteChargingCommand.message
                        ?: "Automatic stop could not be dispatched. Tap Retry limit."
                    updateChargeLimit(decision.next.copy(
                        status = ChargeLimitController.Status.ERROR,
                        message = reason,
                        pendingSinceMs = null
                    ))
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || manuallyDisconnected) return
        reconnectJob = scope.launch {
            val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(6)).coerceAtMost(60_000L)
            reconnectAttempt += 1
            delay(delayMs)
            if (isActive && !manuallyDisconnected) connect()
        }
    }

    override fun updateModel(model: ScooterModel) {
        _dashboard.update { state ->
            val updatedSettings = state.settings.copy(selectedModel = model)
            state.copy(settings = updatedSettings)
        }
        preferences?.edit()?.putString("model", model.name)?.apply()
    }

    override fun updateTariff(tariffRate: Double) {
        if (!tariffRate.isFinite() || tariffRate !in 0.0..100.0) return
        preferences?.edit()?.putFloat("tariff", tariffRate.toFloat())?.apply()
        _dashboard.update { state ->
            val updatedSettings = state.settings.copy(tariffRatePerKWh = tariffRate)
            state.copy(settings = updatedSettings)
        }
    }

    override fun clearTrips() {
        _dashboard.update { it.copy(recentTrips = emptyList()) }
        scope.launch { storageReady.await(); persist { saveTrips(emptyList()) } }
    }

    override fun refresh() {
        if (_dashboard.value.connection == ConnectionStatus.CONNECTING && socket != null) return
        loadVehicleProfileAndRides()
        if (socket != null && _dashboard.value.connection == ConnectionStatus.CONNECTED && snapshotConnectedAt == null) return
        connect(refreshSnapshot = true)
    }

    @Synchronized
    override fun disconnect() {
        generation += 1
        manuallyDisconnected = true
        reconnectJob?.cancel()
        socket?.close(1000, "App closed")
        socket = null
        scope.launch {
            storageReady.await()
            persist { saveTelemetryHistory(_dashboard.value.telemetryHistory) }
            persist { saveRideHistory(_dashboard.value.rideHistory) }
        }
        _dashboard.update { it.copy(connection = ConnectionStatus.DISCONNECTED, errorMessage = null) }
    }

    @Synchronized
    private fun ensureConnected() {
        val status = _dashboard.value.connection
        if (status != ConnectionStatus.CONNECTING &&
            (socket == null || status != ConnectionStatus.CONNECTED)
        ) {
            connect()
        }
    }

    override fun pauseCharging(): Boolean {
        val sent = sendRemoteCharging(start = false)
        if (sent) invalidateChargeEstimateAfterManualCommand(clock())
        return sent
    }

    /** The limit and the visible Pause button share this exact HTTP command path. */
    private fun requestPause(onHttpResult: ((Result<Unit>) -> Unit)? = null): Boolean =
        sendRemoteCharging(start = false, onHttpResult = onHttpResult)

    override fun resumeCharging(): Boolean {
        val sent = sendRemoteCharging(start = true)
        if (sent) invalidateChargeEstimateAfterManualCommand(clock())
        return sent
    }

    private fun invalidateChargeEstimateAfterManualCommand(requestedAt: Long) {
        scope.launch {
            chargeSession.resetRate()
            val state = _chargeLimit.value
            updateChargeLimit(state.copy(estimate = null,
                estimateBlockedThroughMs = maxOf(state.estimateBlockedThroughMs ?: 0L, requestedAt)))
        }
    }

    override fun clearRemoteChargingLatch() {
        _dashboard.update {
            it.copy(remoteChargingCommand = chargingDispatcher.clearLatch())
        }
    }

    override fun tickRemoteChargingTimeouts() {
        val state = _dashboard.value
        val telem = state.telemetry
        val current = state.remoteChargingCommand
        if (current.phase != RemoteCommandPhase.SENDING &&
            current.phase != RemoteCommandPhase.ACCEPTED &&
            !(current.phase == RemoteCommandPhase.CONFIRMED &&
                current.action.equals("stop", ignoreCase = true) &&
                ChargingControl.isActivelyCharging(telem))
        ) {
            return
        }
        val view = ChargingControl.resolveView(null, current, nowMs = clock())
        if (view.command != current) {
            _dashboard.update { it.copy(remoteChargingCommand = view.command) }
        }
    }

    @Synchronized
    private fun sendRemoteCharging(
        start: Boolean,
        onHttpResult: ((Result<Unit>) -> Unit)? = null
    ): Boolean {
        val token = authToken
        val uuid = vehicleUuid
        // The gateway callback can be synchronous in tests or unusually fast in
        // production. Do not apply it until SENDING has been committed to StateFlow.
        val stateCommitted = CompletableDeferred<Unit>()
        var expectedRequestedAt: Long? = null
        val attempt = chargingDispatcher.attempt(
            start = start,
            token = token,
            scooterUuid = uuid,
            telemetry = _dashboard.value.telemetry,
            current = _dashboard.value.remoteChargingCommand,
            supersedePending = true
        ) { result ->
            scope.launch {
                stateCommitted.await()
                if (authToken != token || vehicleUuid != uuid) return@launch
                val action = if (start) "start" else "stop"
                if (result.isFailure && isAuthFailureMessage(result.exceptionOrNull()?.message.orEmpty())) {
                    handleAuthFailure(result.exceptionOrNull()?.message.orEmpty())
                }
                _dashboard.update { state ->
                    state.copy(
                        remoteChargingCommand = chargingDispatcher.applyHttpResult(
                            current = state.remoteChargingCommand,
                            expectedAction = action,
                            result = result,
                            expectedRequestedAt = expectedRequestedAt
                        )
                    )
                }
                onHttpResult?.invoke(result)
            }
        }
        expectedRequestedAt = attempt.command.requestedAt
        _dashboard.update { it.copy(remoteChargingCommand = attempt.command) }
        stateCommitted.complete(Unit)
        return attempt.dispatched
    }

    companion object {
        @Volatile
        private var INSTANCE: AtherRepository? = null

        fun getInstance(context: Context? = null): AtherRepository {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: AtherRepository(context?.applicationContext).also { INSTANCE = it }
            }
        }

        private const val HISTORY_PERSIST_INTERVAL_MS = 15_000L
    }

    private fun persist(action: DashboardLocalStore.() -> Unit) {
        runCatching { localStore?.action() }.onFailure {
            _dashboard.update { it.copy(errorMessage = "Could not save history. Check available phone storage.") }
        }
    }

    private fun updateTripBaseline(odometerKm: Double, batterySoc: Double, timestampMs: Long) {
        lastSavedOdo = odometerKm
        lastSavedSoc = batterySoc
        lastSavedTimestamp = timestampMs
        persist { saveTripBaseline(TripBaseline(odometerKm, batterySoc, timestampMs)) }
    }
}
