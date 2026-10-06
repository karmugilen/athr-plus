package io.ather.pro.data.local

import com.google.gson.internal.LinkedTreeMap
import io.ather.pro.domain.model.GpsData
import io.ather.pro.domain.model.ModeRange
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.range.RangeEstimator
import org.junit.Assert.*
import org.junit.Test

class SavedScooterReadingTest {
    @Test fun roundTripKeepsBatteryRangeAndMapPointWithoutCharging() {
        val reading = SavedScooterReading.fromTelemetry(
            "scooter-1",
            ScooterTelemetry(
                batterySoc = 64.0, rangeKm = 48.0, mode = "Ride", charging = true, chargingStatus = "Charging",
                gps = GpsData(latitude = 13.08, longitude = 80.27),
                modeRanges = mapOf("Ride" to ModeRange(predictedRangeKm = 48.0)),
                sourceTimestampMs = 5_000
            ),
            savedAtMs = 9_000
        )!!
        val restored = SavedScooterReading.decode(reading.encode())!!
        assertTrue(restored.usableFor("scooter-1", 9_000 + 60_000))
        assertFalse(restored.usableFor("other", 9_000))
        assertFalse(restored.usableFor("scooter-1", 9_000 + SavedScooterReading.MAX_AGE_MS + 1))
        val telemetry = restored.toTelemetry()
        assertEquals(64.0, telemetry.batterySoc!!, 0.0)
        assertEquals(13.08, telemetry.gps!!.latitude!!, 0.0)
        assertEquals(48.0, telemetry.modeRanges["Ride"]!!.predictedRangeKm!!, 0.0)
        assertNull(telemetry.charging)
        assertEquals(listOf("Ride"), RangeEstimator.modes(telemetry).map { it.name })
    }

    @Test fun olderMapShapedJsonStillRestoresNamedRanges() {
        val json = """
            {"vehicleUuid":"scooter-1","savedAtMs":9000,"batterySoc":64.0,
             "modeRanges":{"Ride":{"rawRangeKm":32.0,"predictedRangeKm":48.0}}}
        """.trimIndent()
        val telemetry = SavedScooterReading.decode(json)!!.toTelemetry()
        assertEquals(64.0, telemetry.batterySoc!!, 0.0)
        assertEquals(48.0, telemetry.modeRanges["Ride"]!!.predictedRangeKm!!, 0.0)
        assertEquals(48.0, RangeEstimator.modes(telemetry).single().km, 0.0)
    }

    @Test fun jsonMapsThatAreNotModeRangesDoNotCrashTheHomeScreen() {
        val bad = LinkedTreeMap<String, Any?>()
        bad["a"] = 1.0
        bad["b"] = 48.0
        @Suppress("UNCHECKED_CAST")
        val reading = SavedScooterReading(
            vehicleUuid = "scooter-1",
            savedAtMs = 9_000,
            batterySoc = 64.0,
            modeRanges = mapOf("Ride" to bad) as Map<String, ModeRange>
        )
        val telemetry = reading.toTelemetry()
        assertTrue(telemetry.modeRanges.isEmpty())
        assertTrue(RangeEstimator.modes(telemetry).isEmpty())
    }
}
