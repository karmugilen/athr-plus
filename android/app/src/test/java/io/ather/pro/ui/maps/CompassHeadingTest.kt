package io.ather.pro.ui.maps

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassHeadingTest {
    @Test fun cardinalDirectionsAndOrdinaryTiltKeepTheSameHeading() {
        for (heading in listOf(0.0, 90.0, 180.0, 270.0, 359.0)) {
            for (tilt in listOf(-75.0, 0.0, 45.0, 80.0)) {
                assertEquals(heading.toFloat(), CompassHeading.magneticAzimuth(matrix(heading, tilt))!!, 0.001f)
            }
        }
    }

    @Test fun nearVerticalPhoneDoesNotInventADirection() {
        for (heading in listOf(0.0, 90.0, 225.0)) {
            assertNull(CompassHeading.magneticAzimuth(matrix(heading, 85.0)))
            assertNull(CompassHeading.magneticAzimuth(matrix(heading, -90.0)))
        }
    }

    @Test fun invalidMatricesDoNotPublishAHeading() {
        assertNull(CompassHeading.magneticAzimuth(FloatArray(8)))
        assertNull(CompassHeading.magneticAzimuth(matrix(0.0).apply { this[3] = Float.NaN }))
        assertNull(CompassHeading.magneticAzimuth(matrix(0.0).apply { this[1] = Float.POSITIVE_INFINITY }))
    }

    @Test fun trueNorthCorrectionHasTheRightSignAndWrapsAcrossNorth() {
        assertEquals(3f, CompassHeading.trueNorth(358f, 5f, 3)!!, 0.001f)
        assertEquals(357f, CompassHeading.trueNorth(2f, -5f, 3)!!, 0.001f)
        assertEquals(0f, CompassHeading.trueNorth(355f, 5f, 3)!!, 0.001f)
    }

    @Test fun missingLocationOrUnreliableSensorNeverLooksLikeTrueNorth() {
        assertNull(CompassHeading.trueNorth(20f, null, 3))
        assertNull(CompassHeading.trueNorth(null, 5f, 3))
        assertNull(CompassHeading.trueNorth(20f, 5f, 0))
        assertNull(CompassHeading.trueNorth(20f, 5f, -1))
        assertNull(CompassHeading.trueNorth(Float.NaN, 5f, 3))
        assertNull(CompassHeading.trueNorth(20f, Float.POSITIVE_INFINITY, 3))
        assertEquals(25f, CompassHeading.trueNorth(20f, 5f, 1)!!, 0.001f)
    }

    @Test fun largeReportedHeadingErrorFallsBackEvenIfAccuracyStatusIsHigh() {
        assertNull(CompassHeading.trueNorth(20f, 5f, 3, 25f))
        assertNull(CompassHeading.trueNorth(20f, 5f, 3, Float.NaN))
        assertEquals(25f, CompassHeading.trueNorth(20f, 5f, 3, 10f)!!, 0.001f)
        assertEquals(25f, CompassHeading.trueNorth(20f, 5f, 3, 20f)!!, 0.001f)
    }

    @Test fun invalidPhoneCoordinatesCannotBecomeAReferenceLocation() {
        assertTrue(CompassHeading.validCoordinates(0.0, 0.0)) // A real fix here is valid.
        assertTrue(CompassHeading.validCoordinates(12.97, 77.59))
        assertFalse(CompassHeading.validCoordinates(91.0, 77.59))
        assertFalse(CompassHeading.validCoordinates(12.97, -181.0))
        assertFalse(CompassHeading.validCoordinates(Double.NaN, 77.59))
    }

    @Test fun sensorAndLocationFixesExpireEvenWithoutNewSamples() {
        val timestamp = 100_000_000_000L
        for (maxAge in listOf(CompassHeading.SENSOR_MAX_AGE_NS, CompassHeading.LOCATION_MAX_AGE_NS)) {
            assertTrue(CompassHeading.isFresh(timestamp, timestamp + maxAge, maxAge))
            assertFalse(CompassHeading.isFresh(timestamp, timestamp + maxAge + 1, maxAge))
            assertFalse(CompassHeading.isFresh(timestamp, timestamp - 1, maxAge))
            assertFalse(CompassHeading.isFresh(0, timestamp, maxAge))
        }
    }

    @Test fun headingErrorEstimateSupportsMissingAndUnknownAccuracy() {
        assertEquals(10f, CompassHeading.headingErrorDegrees(floatArrayOf(0f, 0f, 0f, 1f,
            Math.toRadians(10.0).toFloat()))!!, 0.001f)
        assertNull(CompassHeading.headingErrorDegrees(floatArrayOf(0f, 0f, 0f, 1f)))
        for (error in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(CompassHeading.headingErrorDegrees(floatArrayOf(0f, 0f, 0f, 1f, error)))
        }
    }

    @Test fun subDegreeChangesArePublishedInsteadOfStickingAtZero() {
        val gate = HeadingSampleGate()
        val delivered = listOf(0f, 0.4f, 0.8f, 1.2f).mapIndexedNotNull { index, heading ->
            if (gate.shouldPublish(1L + index * 20_000_000L)) heading else null
        }
        assertEquals(listOf(0f, 0.4f, 0.8f, 1.2f), delivered)
    }

    @Test fun throttlingUsesTimeAndResetAllowsAnImmediateResumeSample() {
        val gate = HeadingSampleGate()
        assertTrue(gate.shouldPublish(1L))
        assertFalse(gate.shouldPublish(10_000_001L))
        assertTrue(gate.shouldPublish(20_000_001L))
        gate.reset()
        assertTrue(gate.shouldPublish(21_000_001L))
    }

    @Test fun slightlyEarlySensorSamplesDoNotHalveTheHeadingUpdateRate() {
        val gate = HeadingSampleGate()
        assertTrue(gate.shouldPublish(1L))
        assertTrue(gate.shouldPublish(19_900_001L))
        assertTrue(gate.shouldPublish(39_800_001L))
    }

    private fun matrix(heading: Double, tilt: Double = 0.0): FloatArray {
        val h = Math.toRadians(heading)
        val p = Math.toRadians(tilt)
        return floatArrayOf(
            cos(h).toFloat(), (sin(h) * cos(p)).toFloat(), (-sin(h) * sin(p)).toFloat(),
            -sin(h).toFloat(), (cos(h) * cos(p)).toFloat(), (-cos(h) * sin(p)).toFloat(),
            0f, sin(p).toFloat(), cos(p).toFloat()
        )
    }
}
