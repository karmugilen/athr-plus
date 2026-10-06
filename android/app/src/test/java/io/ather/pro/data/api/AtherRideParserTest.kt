package io.ather.pro.data.api

import io.ather.pro.domain.ride.RideLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtherRideParserTest {

    private val client = AtherApiClient()
    private val usableCapacityWh = 3000.0
    private val tariffRatePerKWh = 8.0

    @Test
    fun parseRides_keepsRouteStatsAndExistingCostMath() {
        val raw = """
            {"data":{"page":1,"trips":[
              {
                "ride_id":"1",
                "ride_start_time":1700000000000,
                "ride_end_time":1700000209300,
                "distance_m":968,
                "efficiency_wh_km":17.8,
                "duration_secs":209.3,
                "avg_display_speed_kmph":18.3,
                "max_display_speed_kmph":30.0,
                "status":"success",
                "ride_start_lat":0,
                "ride_start_lon":0,
                "tags":["sample"],
                "polyline_details":{"polyline":"ENCODED","sampling_in_km":0.1,"speed":[18.0,20.0]}
              },
              {
                "ride_id":"2",
                "ride_start_time":1700001000000,
                "ride_end_time":1700001100000,
                "distance_m":1500,
                "efficiency_wh_km":20.0,
                "status":"success"
              }
            ]}}
        """.trimIndent()

        val trips = client.parseRides(raw).map { RideLog.fromCloud(it, usableCapacityWh, tariffRatePerKWh) }
        assertEquals(2, trips.size)

        val ride = trips[0]
        assertEquals("ather-1", ride.id)
        assertEquals(1700000000000L, ride.startTimeMs)
        assertEquals(1700000209300L, ride.endTimeMs)
        assertEquals(0.97, ride.distanceKm, 0.001)
        assertEquals(17.8, ride.efficiencyWhPerKm, 0.001)
        assertEquals(17.2, ride.energyConsumedWh, 0.001)
        assertEquals(0.6, ride.socConsumed, 0.001)
        assertEquals(0.14, ride.electricityCostInr, 0.001)
        assertTrue(ride.isOfficialRide)
        assertEquals(209.3, ride.durationSeconds!!, 0.001)
        assertEquals(18.3, ride.averageSpeedKmh!!, 0.001)
        assertEquals(30.0, ride.topSpeedKmh!!, 0.001)
        assertEquals("ENCODED", ride.encodedPolyline)
        assertEquals(listOf(18.0, 20.0), ride.routeSpeedsKmh)

        val withoutRoute = trips[1]
        assertEquals("ather-2", withoutRoute.id)
        assertEquals(1.5, withoutRoute.distanceKm, 0.001)
        assertEquals(20.0, withoutRoute.efficiencyWhPerKm, 0.001)
        assertEquals(30.0, withoutRoute.energyConsumedWh, 0.001)
        assertEquals(1.0, withoutRoute.socConsumed, 0.001)
        assertEquals(0.24, withoutRoute.electricityCostInr, 0.001)
        assertNull(withoutRoute.durationSeconds)
        assertNull(withoutRoute.averageSpeedKmh)
        assertNull(withoutRoute.topSpeedKmh)
        assertNull(withoutRoute.encodedPolyline)
        assertNull(withoutRoute.routeSpeedsKmh)
    }

    @Test
    fun parseRides_acceptsTopLevelTripsAndDropsInvalidSpeeds() {
        val raw = """
            {"trips":[{
              "ride_id":"3",
              "ride_start_time":1700000000000,
              "ride_end_time":1700000005000,
              "distance_m":500,
              "efficiency_wh_km":10,
              "duration_secs":-1,
              "avg_display_speed_kmph":-4,
              "max_display_speed_kmph":12.5,
              "polyline_details":{"polyline":"  ","speed":[0.0,18.0,-1,20.5,"nope",null]}
            }]}
        """.trimIndent()

        val trip = client.parseRides(raw).map { RideLog.fromCloud(it, usableCapacityWh, tariffRatePerKWh) }.single()
        assertEquals("ather-3", trip.id)
        assertEquals(0.5, trip.distanceKm, 0.001)
        assertEquals(5.0, trip.energyConsumedWh, 0.001)
        assertNull(trip.durationSeconds)
        assertNull(trip.averageSpeedKmh)
        assertEquals(12.5, trip.topSpeedKmh!!, 0.001)
        assertNull(trip.encodedPolyline)
        assertEquals(listOf(0.0, 18.0, 20.5), trip.routeSpeedsKmh)
    }
}
