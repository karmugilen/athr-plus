package io.ather.pro.domain.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RidePolylineTest {

    @Test
    fun decodesStandardVector() {
        val points = RidePolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertPoint(points[0], 38.5, -120.2)
        assertPoint(points[1], 40.7, -120.95)
        assertPoint(points[2], 43.252, -126.453)
    }

    @Test
    fun emptyAndGarbageReturnEmpty() {
        assertTrue(RidePolyline.decode(null).isEmpty())
        assertTrue(RidePolyline.decode("").isEmpty())
        assertTrue(RidePolyline.decode("   ").isEmpty())
        assertTrue(RidePolyline.decode("garbage").isEmpty())
        assertTrue(RidePolyline.decode("_p~iF").isEmpty())
    }

    @Test
    fun dropsPointsOutsideLatLngRange() {
        assertTrue(RidePolyline.decode("_mljP_qvoa@").isEmpty())
        val kept = RidePolyline.decode("_uybQ~jtfc@~utyPo||xb@")
        assertEquals(1, kept.size)
        assertPoint(kept[0], 1.5, -2.25)
    }

    private fun assertPoint(point: RidePoint, latitude: Double, longitude: Double) {
        assertEquals(latitude, point.latitude, 1e-5)
        assertEquals(longitude, point.longitude, 1e-5)
    }
}
