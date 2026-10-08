package io.ather.pro.domain.location

import org.junit.Assert.*
import org.junit.Test

class LocationGeometryTest {
    @Test fun geographicDistanceAndBearingAreStableAtEdges() {
        assertEquals(0.0, distanceMeters(13.0, 80.0, 13.0, 80.0), 0.00001)
        assertEquals(90.0, bearingDegrees(0.0, 0.0, 0.0, 1.0), 0.00001)
        assertTrue(distanceMeters(0.0, 0.0, 0.0, 180.0).isFinite())
        assertTrue(distanceMeters(0.0, 179.999, 0.0, -179.999) < 300)
    }
}
