package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeLimitSessionTest {
    private val readingAt = 1_000_000L
    private val estimate = ChargeTimeEstimate(
        readingAtMs = readingAt,
        batterySoc = 50.0,
        targetPercent = 80,
        chargerPowerW = 900,
        capacityWh = 3_240.0,
        minutesFromReading = 10.0,
        targetAtMs = readingAt + 600_000L,
        stopAtMs = readingAt + 540_000L,
        basisCode = 2,
        accuracyPercent = 20
    )
    private val monitoring = ChargeLimitController.Snapshot(
        enabled = true,
        percent = 80,
        status = ChargeLimitController.Status.MONITORING,
        armed = true,
        chargerPowerW = 900,
        estimate = estimate
    )
    private val charging = ScooterTelemetry(batterySoc = 50.0, charging = true, chargingStatus = "Charging")

    @Test
    fun estimatedDeadlineRequestsPauseWhileTheMeasuredReadingIsStillBelowTheLimit() {
        val now = estimate.stopAtMs
        val step = ChargeLimitSession().observe(
            state = monitoring,
            telemetry = charging,
            batteryFreshAtMs = now,
            batteryReportedAtMs = readingAt,
            chargingFreshAtMs = now,
            nowMs = now,
            capacityWh = 3_240.0
        )
        val decision = step.decision as ChargeLimitController.Decision.RequestStop
        assertTrue(decision.next.stopWasEstimated)
        assertEquals(ChargeLimitController.Status.PENDING, decision.next.status)
    }

    @Test
    fun shownTimeStaysAPreviewUntilTheAppliedLimitIsArmed() {
        val armed = ChargeLimitSession.shown(
            snapshot = monitoring,
            telemetry = charging,
            selectedPercent = 80,
            capacityWh = 3_240.0,
            reportedAtMs = readingAt,
            nowMs = readingAt,
            liveRate = 1.0,
            liveMinutes = 2.0
        )
        assertTrue(armed.timerArmed)
        assertEquals(estimate.stopAtMs, armed.estimate!!.stopAtMs)

        val preview = ChargeLimitSession.shown(
            snapshot = monitoring,
            telemetry = charging,
            selectedPercent = 90,
            capacityWh = 3_240.0,
            reportedAtMs = readingAt,
            nowMs = readingAt,
            liveRate = 1.0,
            liveMinutes = 2.0
        )
        assertFalse(preview.timerArmed)
        assertEquals(90, preview.estimate!!.targetPercent)
    }
}
