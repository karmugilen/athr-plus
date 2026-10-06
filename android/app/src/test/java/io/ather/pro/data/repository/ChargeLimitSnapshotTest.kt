package io.ather.pro.data.repository

import io.ather.pro.data.api.AtherApiClient
import io.ather.pro.data.api.AtherCloudApi
import io.ather.pro.data.local.SavedScooterReading
import io.ather.pro.data.local.SavedScooterReadingStore
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargeSnapshotRefresh
import io.ather.pro.domain.charging.RemoteChargingGateway
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.model.VehicleProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real repository + parser + dispatcher, with a quiet cloud transport and virtual clock. */
class ChargeLimitSnapshotTest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val cloud = QuietCloud()
    private val repository = AtherRepository(api = cloud, repositoryScope = scope, clock = { now })

    @After fun close() { scope.cancel() }

    private fun begin() {
        repository.applyCredentials("test-token", "test-vehicle")
        cloud.snapshot(97.76, "Charging", now)
        repository.setChargeLimit(true, 98)
    }

    private fun refresh() {
        now += ChargeSnapshotRefresh.INTERVAL_MS
        repository.tickChargeMonitoring()
    }

    @Test fun silentStreamRefreshesAndStopsOnceAtDecimalThreshold() {
        begin()
        assertEquals(1, cloud.sockets.size)
        assertTrue(cloud.commands.isEmpty())
        now += ChargeSnapshotRefresh.INTERVAL_MS - 1
        repository.tickChargeMonitoring()
        assertEquals(1, cloud.sockets.size)
        now += 1
        repository.tickChargeMonitoring()
        assertEquals(2, cloud.sockets.size)
        assertTrue(cloud.sockets.first().cancelled)
        cloud.snapshot(98.49, "Charging", now)
        assertEquals(listOf(false), cloud.commands)
        assertEquals(ChargeLimitController.Status.PENDING, repository.chargeLimit.value.status)
        assertEquals(RemoteCommandPhase.ACCEPTED, repository.dashboard.value.remoteChargingCommand.phase)

        // Echoed desired state and cached physical readings cannot confirm the stop.
        cloud.receive("""{"scooters.remote_charging":{"action":"stop"}}""")
        assertEquals(ChargeLimitController.Status.PENDING, repository.chargeLimit.value.status)
        val commandTime = now
        refresh()
        cloud.snapshot(98.49, "Stopped", commandTime)
        assertEquals(ChargeLimitController.Status.PENDING, repository.chargeLimit.value.status)
        assertEquals(RemoteCommandPhase.ACCEPTED, repository.dashboard.value.remoteChargingCommand.phase)
        refresh()
        cloud.snapshot(98.49, "Stopped", now)
        assertEquals(ChargeLimitController.Status.CONFIRMED, repository.chargeLimit.value.status)
        assertEquals(RemoteCommandPhase.CONFIRMED, repository.dashboard.value.remoteChargingCommand.phase)
        assertEquals(listOf(false), cloud.commands)
    }

    @Test fun refreshingOldHighBatteryNeverDispatchesAStop() {
        repository.applyCredentials("test-token", "test-vehicle")
        val staleSource = now - 300_000L
        cloud.snapshot(99.0, "Charging", staleSource)
        repository.setChargeLimit(true, 98)
        repeat(8) {
            refresh()
            cloud.snapshot(99.0, "Charging", staleSource)
        }
        assertTrue(cloud.commands.isEmpty())
        assertNull(repository.dashboard.value.batteryUpdatedAt)
    }

    @Test fun latePacketFromReplacedSocketCannotSendAStop() {
        begin()
        refresh()
        cloud.snapshot(99.0, "Charging", now, index = 0)
        assertTrue(cloud.commands.isEmpty())
        cloud.snapshot(97.99, "Charging", now)
        assertTrue(cloud.commands.isEmpty())
        cloud.snapshot(98.00, "Charging", now + 1)
        // Source time ahead of the phone is rejected until the clock catches up.
        assertTrue(cloud.commands.isEmpty())
        now += 1
        cloud.snapshot(98.00, "Charging", now)
        assertEquals(listOf(false), cloud.commands)
    }

    @Test fun disabledLimitStillRefreshesTheLiveSnapshot() {
        begin()
        repository.setChargeLimit(false, 98)
        repeat(4) { refresh() }
        assertEquals(5, cloud.sockets.size)
        assertTrue(cloud.sockets.dropLast(1).all { it.cancelled })
        assertTrue(cloud.commands.isEmpty())
        assertFalse(repository.chargeLimit.value.enabled)
    }

    @Test fun savedReadingPaintsTheHomeScreenAndDoesNotPause() {
        val readings = MemoryReadings { now }
        readings.current = SavedScooterReading(
            vehicleUuid = "test-vehicle", savedAtMs = now - 60_000, sourceTimestampMs = now - 60_000,
            batterySoc = 90.0, rangeKm = 40.0, latitude = 13.0, longitude = 80.0
        )
        val ownCloud = QuietCloud()
        val local = AtherRepository(
            api = ownCloud, repositoryScope = scope, clock = { now }, savedReadingStore = readings
        )
        local.applyCredentials("test-token", "test-vehicle")
        assertEquals(90.0, local.dashboard.value.telemetry!!.batterySoc!!, 0.0)
        assertEquals(13.0, local.dashboard.value.telemetry!!.gps!!.latitude!!, 0.0)
        assertNull(local.dashboard.value.telemetry!!.charging)
        local.setChargeLimit(true, 80)
        repeat(4) {
            now += ChargeSnapshotRefresh.INTERVAL_MS
            local.tickChargeMonitoring()
        }
        assertTrue(ownCloud.commands.isEmpty())
        assertTrue(local.chargeLimit.value.enabled)
    }

    @Test fun manualRefreshGetsANewSnapshotRatherThanResubscribingQuietSocket() {
        repository.applyCredentials("test-token", "test-vehicle")
        repository.refresh()
        assertEquals(2, cloud.sockets.size)
        assertTrue(cloud.sockets.first().cancelled)
    }

    private class QuietCloud : AtherCloudApi {
        private val parser = AtherApiClient()
        val sockets = mutableListOf<QuietSocket>()
        val commands = mutableListOf<Boolean>()

        override fun connect(token: String, uuid: String, listener: AtherCloudApi.Listener): WebSocket {
            val socket = QuietSocket(listener)
            sockets += socket
            listener.onConnected(socket)
            return socket
        }
        override fun subscribe(socket: WebSocket) = socket.send("subscribe")
        override fun asRemoteChargingGateway() = RemoteChargingGateway { _, _, start, callback ->
            commands += start
            callback(Result.success(Unit))
        }
        override fun fetchVehicleProfile(token: String, uuid: String, callback: (Result<VehicleProfile>) -> Unit) {
            callback(Result.success(VehicleProfile()))
        }
        override fun fetchRides(token: String, scooterId: String,
            callback: (Result<List<io.ather.pro.domain.ride.RideLog.CloudFields>>) -> Unit) {
            callback(Result.success(emptyList()))
        }

        fun snapshot(soc: Double, status: String, source: Long, index: Int = sockets.lastIndex) {
            receive("""{"telemetry.bike":{"battery_soc":$soc,"last_synced_time":$source},
                "telemetry.charging":{"chargingStatus":"$status","chargerConnected":"On"}}""", index)
        }
        fun receive(json: String, index: Int = sockets.lastIndex) {
            sockets[index].listener.onTelemetry(parser.parseTelemetry(json)!!)
        }
    }

    private class MemoryReadings(private val now: () -> Long) : SavedScooterReadingStore {
        var current: SavedScooterReading? = null
        override fun load(vehicleUuid: String) = current?.takeIf { it.usableFor(vehicleUuid, now()) }
        override fun save(reading: SavedScooterReading) { current = reading }
    }

    private class QuietSocket(val listener: AtherCloudApi.Listener) : WebSocket {
        var cancelled = false
        override fun request(): Request = Request.Builder().url("https://example.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
}
