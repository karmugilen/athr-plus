package io.ather.pro.domain.insights

import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TripRecord
import org.junit.Assert.*
import org.junit.Test

class RiderInsightsTest {
    private val start = 1_000_000L
    private val limit = ChargeLimitController.Snapshot(enabled = true, percent = 80,
        status = ChargeLimitController.Status.MONITORING)
    private fun report(at: Long, soc: Double = 60.0, charging: Boolean? = false, odo: Double = 100.0) =
        ScooterTelemetry(batterySoc = soc, charging = charging, odoKm = odo,
            vehicleState = if (charging == true) "Charging" else "Parked", mode = "Ride", sourceTimestampMs = at)
    private fun observe(state: RiderInsights, at: Long, soc: Double = 60.0, charging: Boolean? = false, odo: Double = 100.0) =
        RiderInsightRecorder.observe(state, report(at, soc, charging, odo), at, limit)

    @Test fun chargingHistoryRecordsObservedLevelsAndConfirmedCutoff() {
        var state = observe(RiderInsights(), start, 60.0, true)
        state = observe(state, start + 360_000, 65.0, true)
        state = RiderInsightRecorder.observe(state, report(start + 720_000, 80.0, false), start + 720_000,
            limit.copy(status = ChargeLimitController.Status.CONFIRMED))
        val session = state.chargeSessions.single()
        assertEquals(60.0, session.startSoc, 0.0)
        assertEquals(80.0, session.endSoc, 0.0)
        assertEquals(80, session.target)
        assertEquals(start + 720_000, session.endedAt)
        assertTrue(session.cutoffConfirmed)
    }

    @Test fun acceptedStopDoesNotBecomeConfirmed() {
        var state = observe(RiderInsights(), start, 60.0, true)
        state = observe(state, start + 360_000, 65.0, false)
        state = RiderInsightRecorder.confirmCutoff(state, limit.copy(status = ChargeLimitController.Status.PENDING))
        assertFalse(state.chargeSessions.single().cutoffConfirmed)
    }

    @Test fun duplicateAndOldBatteryReportsCannotInventHistory() {
        val state = observe(RiderInsights(), start)
        assertEquals(state, observe(state, start))
        assertEquals(state, RiderInsightRecorder.observe(state, report(start + 1), start + 200_000, limit))
        assertEquals(state, RiderInsightRecorder.observe(state, report(start + 1).copy(sourceTimestampMs = null), start + 1, limit))
        assertEquals(state, observe(state, start - 1))
    }

    @Test fun longChargingGapProducesAnInterruptedSession() {
        val first = observe(RiderInsights(), start, 60.0, true)
        val state = observe(first, start + RiderInsightRecorder.MAX_GAP_MS + 1, 70.0, true)
        assertEquals(2, state.chargeSessions.size)
        assertTrue(state.chargeSessions.last().interruptedObservation)
        assertEquals(start, state.chargeSessions.last().endedAt)
    }

    @Test fun sparsePhysicalStopClosesSessionWithoutInventingBatteryLevel() {
        val first = observe(RiderInsights(), start, 60.0, true)
        val state = RiderInsightRecorder.observeStopped(first, ScooterTelemetry(charging = false), false, start + 60_000)
        assertEquals(start + 60_000, state.chargeSessions.single().endedAt)
        assertEquals(60.0, state.chargeSessions.single().endSoc, 0.0)
        assertEquals(first, RiderInsightRecorder.observeStopped(first, ScooterTelemetry(charging = false), true, start + 60_000))
    }

    private fun parkedNight(): RiderInsights {
        var state = RiderInsights()
        repeat(21) { index -> state = observe(state, start + index * 360_000L, 60.0 - index * 0.01) }
        return state
    }

    @Test fun parkedChangeNeedsObservedStationaryEndpoints() {
        val state = parkedNight()
        assertEquals(2.0, state.activeParked!!.hours, 0.0)
        assertEquals(0.2, state.activeParked.dropPercent, 0.00001)
        val moved = observe(state, start + 21 * 360_000L, 59.8, odo = 101.0)
        assertEquals(1, moved.parkedPeriods.size)
        assertEquals(0.0, moved.activeParked!!.hours, 0.0)
    }

    @Test fun observationGapDoesNotBecomeOvernightDrain() {
        val first = observe(RiderInsights(), start)
        val after = observe(first, start + 8 * 3_600_000, 55.0)
        assertTrue(after.parkedPeriods.isEmpty())
        assertEquals(0.0, after.activeParked!!.hours, 0.0)
    }

    @Test fun chargeOrBatteryIncreaseBreaksParkedPeriod() {
        val state = parkedNight()
        assertNull(observe(state, start + 21 * 360_000L, 61.0, true).activeParked)
        val increased = observe(state, start + 21 * 360_000L, 61.0)
        assertEquals(0.0, increased.activeParked!!.hours, 0.0)
    }

    private fun ride(index: Int, efficiency: Double = 30.0, speed: Double = 30.0): TripRecord {
        val at = start + index * 1_800_000L
        return TripRecord("ride-$index", at, at + 600_000, 5.0, 0.0, efficiency * 5, efficiency,
            0.0, 0.0, 0.0, isOfficialRide = true, averageSpeedKmh = speed,
            encodedPolyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@")
    }

    private fun samples(trips: List<TripRecord>, soc: Double = 65.0) = trips.flatMap {
        listOf(BatteryObservation(it.startTimeMs + 60_000, soc, "Ride"),
            BatteryObservation(it.endTimeMs - 60_000, soc - 1, "Ride"))
    }

    @Test fun tomorrowPlannerUsesRecentReportedEfficiencyAndReserve() {
        val trips = listOf(ride(1, 20.0), ride(2, 30.0), ride(3, 40.0))
        val estimate = TomorrowPlanner.estimate(30.0, 15, 3_000.0, 60.0, trips)!!
        assertEquals(40.0, estimate.whPerKm, 0.0)
        assertEquals(40.0, estimate.neededPercent, 0.0)
        assertEquals(55.0, estimate.targetPercent, 0.0)
        assertEquals(true, estimate.enough)
        assertNull(TomorrowPlanner.estimate(30.0, 15, 3_000.0, 60.0, trips.take(2)))
    }

    @Test fun plannerDoesNotUseLocalEstimatesOrBadNumbersAsMeasurements() {
        val trips = (1..4).map { ride(it).copy(isOfficialRide = false) }
        assertNull(TomorrowPlanner.estimate(30.0, 15, 3_000.0, 60.0, trips))
        assertNull(TomorrowPlanner.estimate(Double.NaN, 15, 3_000.0, 60.0, trips))
        assertNull(TomorrowPlanner.estimate(30.0, 15, 0.0, 60.0, trips))
    }

    @Test fun plannerKeepsImpossiblePlansAboveOneHundredAndUnknownBatteryUnknown() {
        val result = TomorrowPlanner.estimate(100.0, 15, 3_000.0, null, (1..3).map { ride(it, 40.0) })!!
        assertTrue(result.targetPercent > 100)
        assertNull(result.enough)
    }

    private fun deterioratingRides() = (1..8).map { ride(it, if (it >= 6) 40.0 else 30.0) }

    @Test fun sustainedEfficiencyChangeAgainstComparableHistoryFlagsATyreCheck() {
        val rides = deterioratingRides()
        val result = TyreEfficiencyReview.evaluate(rides, samples(rides))!!
        assertEquals("ride-8", result.rideId)
        assertEquals(5, result.baselineRides)
        assertEquals(33.333, result.increasePercent, 0.001)
    }

    @Test fun oneBadRideDoesNotTriggerAnAlert() {
        val rides = (1..8).map { ride(it, if (it == 8) 50.0 else 30.0) }
        assertNull(TyreEfficiencyReview.evaluate(rides, samples(rides)))
    }

    @Test fun missingContextOrHighBatteryCannotBecomePressureEvidence() {
        val rides = deterioratingRides()
        assertNull(TyreEfficiencyReview.evaluate(rides, emptyList()))
        assertNull(TyreEfficiencyReview.evaluate(rides, samples(rides, 95.0)))
        assertNull(TyreEfficiencyReview.evaluate(rides, samples(rides).mapIndexed { i, sample ->
            sample.copy(mode = if (i % 2 == 0) "Ride" else "Sport") }))
    }

    @Test fun changedSpeedOrRouteIsNotComparedAsTyreDeterioration() {
        val changedSpeed = deterioratingRides().map { if (it.id in listOf("ride-6", "ride-7", "ride-8")) it.copy(averageSpeedKmh = 60.0) else it }
        assertNull(TyreEfficiencyReview.evaluate(changedSpeed, samples(changedSpeed)))
        val missingRoute = deterioratingRides().map { it.copy(encodedPolyline = null) }
        assertNull(TyreEfficiencyReview.evaluate(missingRoute, samples(missingRoute)))
    }

    @Test fun duplicateRidesDoNotInflateTheLearningBaseline() {
        val rides = deterioratingRides().takeLast(4)
        assertNull(TyreEfficiencyReview.evaluate(rides + rides + rides, samples(rides)))
    }

    @Test fun observedEfficientSpeedBandRequiresRepeatedComparableRides() {
        val rides = (1..6).map { ride(it, if (it <= 3) 30.0 else 40.0, if (it <= 3) 30.0 else 40.0) }
        val band = TyreEfficiencyReview.efficientSpeedBand(rides, samples(rides))!!
        assertEquals(30, band.fromKmh)
        assertEquals(35, band.toKmh)
        assertEquals(3, band.rides)
        assertNull(TyreEfficiencyReview.efficientSpeedBand(rides.take(3), samples(rides.take(3))))
    }
}
