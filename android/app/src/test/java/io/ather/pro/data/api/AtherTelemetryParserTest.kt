package io.ather.pro.data.api

import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic parser tests over sanitized Cerberus-shaped fixtures.
 * No live network, credentials, or location logging.
 */
class AtherTelemetryParserTest {

    private val client = AtherApiClient()

    @Test
    fun parseDirectNestedTelemetry() {
        val telem = parseFixture("telemetry_fixtures/direct_nested.json")

        assertEquals(84.07, telem.batterySoc!!, 0.001)
        assertEquals(120.5, telem.rangeKm!!, 0.001)
        assertEquals(27659.418, telem.odoKm!!, 0.001)
        assertEquals("parked", telem.vehicleState)
        assertEquals("Ride", telem.mode)
        assertEquals(68423.6, telem.savingsInr!!, 0.01)
        assertEquals("6.2.1", telem.softwareVersion)
        assertEquals(18, telem.connectivityStrength)
        assertEquals(false, telem.charging)
        assertEquals(true, telem.chargerConnected)
        assertEquals("Completed", telem.chargingStatus)
        assertEquals("home", telem.chargerType)
        assertEquals("stop", telem.remoteChargingAction)

        assertEquals(12.9716, telem.gps!!.latitude!!, 0.0001)
        assertEquals(77.5946, telem.gps!!.longitude!!, 0.0001)
        assertEquals(920.0, telem.gps!!.altitudeMeters!!, 0.1)
        assertEquals(6.4, telem.gps!!.accuracyMeters!!, 0.1)
        assertEquals(45.0, telem.gps!!.heading!!, 0.1)
        assertEquals(0.0, telem.gps!!.speed!!, 0.1)

        assertEquals(140.0, telem.modeRanges["SmartEco"]!!.rawRangeKm!!, 0.1)
        assertEquals(130.0, telem.modeRanges["Eco"]!!.rawRangeKm!!, 0.1)
        assertEquals(120.0, telem.modeRanges["Ride"]!!.rawRangeKm!!, 0.1)
        assertEquals(127.78, telem.modeRanges["Ride"]!!.predictedRangeKm!!, 0.01)
        assertEquals(95.0, telem.modeRanges["Sport"]!!.rawRangeKm!!, 0.1)
        assertEquals(80.0, telem.modeRanges["Warp"]!!.rawRangeKm!!, 0.1)
        assertEquals(70.0, telem.modeRanges["WarpPlus"]!!.rawRangeKm!!, 0.1)

        assertEquals(32.0, telem.tpms!!.frontPressurePsi!!, 0.1)
        assertEquals(36.0, telem.tpms!!.rearPressurePsi!!, 0.1)
        assertEquals(28.5, telem.tpms!!.frontTemperatureC!!, 0.1)

        assertEquals(true, telem.featureFlags["anti_theft"])
        assertEquals(false, telem.featureFlags["vacation_mode"])
        assertEquals(true, telem.featureFlags["coasting_regen"])
    }

    @Test
    fun parseEnvelopedStateReported() {
        val telem = parseFixture("telemetry_fixtures/enveloped_reported.json")

        assertEquals(72.5, telem.batterySoc!!, 0.001)
        assertEquals(98.0, telem.rangeKm!!, 0.001)
        assertEquals("charging", telem.vehicleState)
        assertEquals("SmartEco", telem.mode)
        assertEquals("6.3.0", telem.softwareVersion)
        assertEquals(22, telem.connectivityStrength)
        assertEquals(true, telem.charging)
        assertEquals(true, telem.chargerConnected)
        assertEquals("Charging", telem.chargingStatus)
        // Fixtures store Cerberus counters in seconds. The domain field is minutes.
        assertEquals(95.0 / 60.0, telem.timeToFullChargeMin!!, 0.001)
        assertEquals(40.0 / 60.0, telem.timeToEightyChargeMin!!, 0.001)
        assertEquals("public", telem.chargerType)
        assertEquals("start", telem.remoteChargingAction)

        assertEquals(13.0827, telem.gps!!.latitude!!, 0.0001)
        assertEquals(80.2707, telem.gps!!.longitude!!, 0.0001)
        assertEquals(180.0, telem.gps!!.heading!!, 0.1)

        assertEquals(145.0, telem.modeRanges["SmartEco"]!!.rawRangeKm!!, 0.1)
        assertEquals(110.0, telem.modeRanges["Ride"]!!.rawRangeKm!!, 0.1)

        assertEquals(31.5, telem.tpms!!.frontPressurePsi!!, 0.1)
        assertEquals(35.0, telem.tpms!!.rearPressurePsi!!, 0.1)

        assertEquals(true, telem.featureFlags["anti_theft"])
        assertEquals(true, telem.featureFlags["skid_control"])
    }

    @Test
    fun parseFlattenedDottedKeys() {
        val telem = parseFixture("telemetry_fixtures/flattened_dotted.json")

        assertEquals(55.0, telem.batterySoc!!, 0.001)
        assertEquals(70.0, telem.rangeKm!!, 0.001)
        assertEquals(30000.0, telem.odoKm!!, 0.001)
        assertEquals("riding", telem.vehicleState)
        assertEquals("Sport", telem.mode)
        assertEquals("5.9.4", telem.softwareVersion)
        assertEquals(12, telem.connectivityStrength)
        assertEquals(false, telem.charging)
        assertEquals(false, telem.chargerConnected)
        assertEquals("Initializing", telem.chargingStatus)
        assertEquals("portable", telem.chargerType)
        assertEquals("stop", telem.remoteChargingAction)

        assertEquals(19.076, telem.gps!!.latitude!!, 0.001)
        assertEquals(72.8777, telem.gps!!.longitude!!, 0.001)
        assertEquals(32.5, telem.gps!!.speed!!, 0.1)

        assertEquals(100.0, telem.modeRanges["SmartEco"]!!.rawRangeKm!!, 0.1)
        assertEquals(85.0, telem.modeRanges["Ride"]!!.rawRangeKm!!, 0.1)
        assertEquals(82.0, telem.modeRanges["Ride"]!!.predictedRangeKm!!, 0.1)
        assertEquals(65.0, telem.modeRanges["Sport"]!!.rawRangeKm!!, 0.1)

        assertEquals(30.0, telem.tpms!!.frontPressurePsi!!, 0.1)
        assertEquals(34.0, telem.tpms!!.rearPressurePsi!!, 0.1)

        assertEquals(false, telem.featureFlags["vacation_mode"])
        assertEquals(true, telem.featureFlags["anti_theft"])
    }

    @Test
    fun parsePartialDeltaAndMergeWithoutFakeFallbacks() {
        val baseline = parseFixture("telemetry_fixtures/direct_nested.json")
        val chargingDelta = parseFixture("telemetry_fixtures/partial_delta_charging.json")
        val modeDelta = parseFixture("telemetry_fixtures/partial_delta_modes.json")

        // Partial frames must not invent battery/odo when absent.
        assertNull(chargingDelta.batterySoc)
        assertNull(chargingDelta.odoKm)
        assertNull(chargingDelta.rangeKm)
        assertEquals(true, chargingDelta.charging)
        assertEquals(true, chargingDelta.chargerConnected)
        assertEquals(1.0, chargingDelta.timeToFullChargeMin!!, 0.001)
        assertEquals("start", chargingDelta.remoteChargingAction)

        assertNull(modeDelta.batterySoc)
        assertNull(modeDelta.charging)
        assertEquals(88.0, modeDelta.rangeKm!!, 0.1)
        assertEquals(88.0, modeDelta.modeRanges["Ride"]!!.rawRangeKm!!, 0.1)
        assertEquals(91.5, modeDelta.modeRanges["Ride"]!!.predictedRangeKm!!, 0.1)

        val afterCharge = baseline.mergeWith(chargingDelta)
        assertEquals(84.07, afterCharge.batterySoc!!, 0.001)
        assertEquals(27659.418, afterCharge.odoKm!!, 0.001)
        assertEquals(true, afterCharge.charging)
        assertEquals("Charging", afterCharge.chargingStatus)
        assertEquals(1.0, afterCharge.timeToFullChargeMin!!, 0.001)
        assertEquals("start", afterCharge.remoteChargingAction)
        assertEquals(32.0, afterCharge.tpms!!.frontPressurePsi!!, 0.1)
        assertEquals(140.0, afterCharge.modeRanges["SmartEco"]!!.rawRangeKm!!, 0.1)

        val afterModes = afterCharge.mergeWith(modeDelta)
        assertEquals(84.07, afterModes.batterySoc!!, 0.001)
        assertEquals(88.0, afterModes.rangeKm!!, 0.1)
        assertEquals(true, afterModes.charging)
        assertEquals(88.0, afterModes.modeRanges["Ride"]!!.rawRangeKm!!, 0.1)
        assertEquals(91.5, afterModes.modeRanges["Ride"]!!.predictedRangeKm!!, 0.1)
        // Unrelated modes preserved across partial mode delta.
        assertEquals(140.0, afterModes.modeRanges["SmartEco"]!!.rawRangeKm!!, 0.1)
        assertEquals(95.0, afterModes.modeRanges["Sport"]!!.rawRangeKm!!, 0.1)
        assertEquals(32.0, afterModes.tpms!!.frontPressurePsi!!, 0.1)
    }

    @Test
    fun emptyNoiseReturnsNull() {
        assertNull(client.parseTelemetry("{}"))
        assertNull(client.parseTelemetry("""{"state":{"reported":{}}}"""))
        assertNull(client.parseTelemetry("""{"foo":"bar"}"""))
    }

    @Test
    fun gpsPartialDoesNotFabricateCoordinates() {
        val json = """
            {
              "state": {
                "delta": {
                  "telemetry.bike.gps_location.heading": 270.0,
                  "telemetry.bike.gps_location.speed": 12.0
                }
              }
            }
        """.trimIndent()
        val telem = client.parseTelemetry(json)
        assertNotNull(telem)
        assertNull(telem!!.gps!!.latitude)
        assertNull(telem.gps!!.longitude)
        assertEquals(270.0, telem.gps!!.heading!!, 0.1)
        assertEquals(12.0, telem.gps!!.speed!!, 0.1)
        assertNull(telem.batterySoc)
        assertTrue(telem.featureFlags.isEmpty())
    }

    @Test fun acceptsZipAndKeepsWarpPlusDistinctFromWarp() {
        val telemetry = client.parseTelemetry("""{"telemetry":{"bike":{"mode_range":{"zip":30,"Warp+":24,"warp":26}}}}""")!!
        assertEquals(setOf("Zip", "WarpPlus", "Warp"), telemetry.modeRanges.keys)
        assertEquals(24.0, telemetry.modeRanges.getValue("WarpPlus").rawRangeKm!!, 0.0)
    }

    @Test fun desiredStopIsNotPhysicalChargingEvidence() {
        val echo = client.parseTelemetry("""{"scooters":{"remote_charging":{"action":"stop"}}}""")!!
        assertNull(echo.charging)
        assertFalse(io.ather.pro.domain.charging.ChargingEvidence.hasChargeReading(echo))
        val unknown = client.parseTelemetry("""{"telemetry":{"charging":{"chargingStatus":"Unknown"}}}""")!!
        assertNull(unknown.charging)
        assertFalse(io.ather.pro.domain.charging.ChargingEvidence.hasChargeReading(unknown))
    }

    @Test fun freshPausedStatusWinsOverHeartbeatAndHeartbeatOffIsAStopReading() {
        val paused = client.parseTelemetry("""{"telemetry":{"charging":{"chargingStatus":"Paused","chargingHeartBeat":"On"}}}""")!!
        assertEquals(false, paused.charging)
        val off = client.parseTelemetry("""{"telemetry":{"charging":{"chargingHeartBeat":"Off"}}}""")!!
        assertEquals(false, off.charging)
    }

    private fun parseFixture(path: String): ScooterTelemetry {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing fixture: $path"
        }
        val raw = stream.bufferedReader().use { it.readText() }
        return requireNotNull(client.parseTelemetry(raw)) { "Parser returned null for $path" }
    }
}
