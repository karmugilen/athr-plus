package io.ather.pro.domain.ride

import io.ather.pro.domain.model.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLogTest {
    @Test
    fun localLegClosesWithoutBecomingAnOpenableRide() {
        val closed = RideLog.close(
            RideLog.Leg(
                previousOdoKm = 10.0,
                previousSoc = 80.0,
                openedAtMs = 1_000L,
                odoKm = 11.0,
                soc = 78.0,
                nowMs = 20_000L,
                vehicleState = "Parked",
                speedKmh = 0.0,
                charging = false,
                avgWhPerKm = 30.0,
                usableCapacityWh = 3_240.0,
                tariffRatePerKWh = 8.0
            ),
            id = "local-1"
        ) as RideLog.LegOutcome.Closed
        assertEquals("local-1", closed.trip.id)
        assertFalse(closed.trip.isOfficialRide)
        assertNull(closed.trip.averageSpeedKmh)
        assertFalse(RideLog.canOpen(closed.trip))
        assertTrue(RideLog.openable(listOf(closed.trip, official())).single().isOfficialRide)
    }

    @Test
    fun chargingResetsTheBaselineInsteadOfClosingARide() {
        val reset = RideLog.close(
            RideLog.Leg(
                previousOdoKm = 10.0,
                previousSoc = 80.0,
                openedAtMs = 1_000L,
                odoKm = 11.0,
                soc = 82.0,
                nowMs = 20_000L,
                vehicleState = "Charging",
                speedKmh = 0.0,
                charging = true,
                avgWhPerKm = 30.0,
                usableCapacityWh = 3_240.0,
                tariffRatePerKWh = 8.0
            ),
            id = "ignored"
        )
        assertTrue(reset is RideLog.LegOutcome.Reset)
    }

    private fun official() = TripRecord(
        id = "ather-1",
        startTimeMs = 1L,
        endTimeMs = 2L,
        distanceKm = 1.0,
        socConsumed = 1.0,
        energyConsumedWh = 20.0,
        efficiencyWhPerKm = 20.0,
        electricityCostInr = 0.2,
        startOdoKm = 0.0,
        endOdoKm = 0.0,
        isOfficialRide = true,
        averageSpeedKmh = 18.0
    )
}
