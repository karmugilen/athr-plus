package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeTimeEstimatorTest {
    private val charging = ScooterTelemetry(batterySoc = 50.0, charging = true, chargingStatus = "Charging")

    @Test
    fun estimate_waitsUntilAChargingSpeedExists() {
        val estimate = ChargeTimeEstimator.estimate(
            charging, target = 80, capacityWh = 3700.0, powerW = 900,
            readingAtMs = 1_000, nowMs = 1_000
        )
        assertNull(estimate)
    }

    @Test
    fun estimate_usesOnlyTheLiveSpeedOnTheFirstCharge() {
        val estimate = ChargeTimeEstimator.estimate(
            charging, target = 80, capacityWh = 3700.0, powerW = 900,
            readingAtMs = 1_000, nowMs = 1_000, observedRate = 1.0, liveMinutes = 2.0
        )!!
        assertEquals(30.0, estimate.minutesFromReading, 0.001)
        assertEquals(2, estimate.basisCode)
        assertTrue(estimate.isValid())
    }

    @Test
    fun estimate_givesTheLiveChargeMoreWeightAsItContinues() {
        val early = ChargeTimeEstimator.estimate(
            charging, target = 80, capacityWh = 3700.0, powerW = 900,
            readingAtMs = 1_000, nowMs = 1_000, observedRate = 1.0,
            learnedRate = 0.5, learnedMinutes = 60.0, liveMinutes = 0.0
        )!!
        val later = ChargeTimeEstimator.estimate(
            charging, target = 80, capacityWh = 3700.0, powerW = 900,
            readingAtMs = 1_000, nowMs = 1_000, observedRate = 1.0,
            learnedRate = 0.5, learnedMinutes = 60.0, liveMinutes = 10.0
        )!!
        assertEquals(4, early.basisCode)
        assertEquals(60.0, early.minutesFromReading, 0.001)
        assertEquals(5, later.basisCode)
        assertEquals(40.0, later.minutesFromReading, 0.001)
        assertTrue(later.accuracyPercent > early.accuracyPercent)
        assertTrue(later.accuracyPercent in 11..94)
    }

    @Test
    fun memory_keepsEarlierChargesAndMovesTowardTheLatestOne() {
        val first = ChargeRateMemory.remember(
            ChargeLimitController.Snapshot(),
            FinishedCharge(percentPerMinute = 0.4, minutes = 30.0)
        )
        val second = ChargeRateMemory.remember(first, FinishedCharge(percentPerMinute = 0.8, minutes = 30.0))
        assertEquals(1, first.learnedSessions)
        assertEquals(0.4, first.learnedPercentPerMinute!!, 0.001)
        assertEquals(2, second.learnedSessions)
        assertEquals(0.6, second.learnedPercentPerMinute!!, 0.001)
    }

    @Test
    fun tracker_recordsAChargeOnlyAfterItStops() {
        val tracker = ChargingRateTracker()
        val start = 1_000_000L
        assertNull(tracker.observe(charging.copy(batterySoc = 40.0), start, start))
        val stillCharging = tracker.observe(charging.copy(batterySoc = 42.0), start + 120_000L, start + 120_000L)
        assertNull(stillCharging)
        assertEquals(1.0, tracker.percentPerMinute!!, 0.001)
        val stopped = ScooterTelemetry(batterySoc = 42.0, charging = false, chargingStatus = "Stopped")
        val finished = tracker.observe(stopped, start + 130_000L, start + 130_000L)
        assertEquals(1.0, finished!!.percentPerMinute, 0.001)
        assertEquals(2.0, finished.minutes, 0.001)
        assertNull(tracker.observe(stopped, start + 140_000L, start + 140_000L))
    }
}
