package io.ather.pro.domain.monitoring

import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargeTimeEstimate
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.*
import org.junit.Test

class MonitorNoticeTest {
    private val now = 1_000_000L

    @Test fun chargingShowsACountdownToTheChargeStop() {
        val notice = MonitorNotice.from(now, dashboard(charging = true, status = "Charging", soc = 60.0), limit(
            status = ChargeLimitController.Status.MONITORING, stopAtMs = now + 20 * 60_000
        ))
        assertEquals("Charging · 60%", notice.title)
        assertEquals(now + 20 * 60_000, notice.countdownToMs)
        assertEquals(60, notice.socPercent)
    }

    @Test fun stoppedClearsTheCountdown() {
        val notice = MonitorNotice.from(now, dashboard(charging = false, status = "Paused", soc = 80.0), limit(
            status = ChargeLimitController.Status.CONFIRMED, stopAtMs = now + 60_000
        ))
        assertEquals("Charging stopped · 80%", notice.title)
        assertNull(notice.countdownToMs)
        assertTrue(notice.detail.contains("Charging is stopped"))
    }

    private fun dashboard(charging: Boolean, status: String, soc: Double) = ScooterDashboardState(
        connection = ConnectionStatus.CONNECTED,
        lastUpdated = now,
        telemetry = ScooterTelemetry(batterySoc = soc, charging = charging, chargingStatus = status, chargerConnected = true)
    )

    private fun limit(status: ChargeLimitController.Status, stopAtMs: Long) = ChargeLimitController.Snapshot(
        enabled = true, percent = 80, status = status, armed = status == ChargeLimitController.Status.MONITORING,
        estimate = ChargeTimeEstimate(
            readingAtMs = now - 60_000, batterySoc = 60.0, targetPercent = 80, chargerPowerW = 900,
            capacityWh = 3240.0, minutesFromReading = 20.0, targetAtMs = stopAtMs + 60_000,
            stopAtMs = stopAtMs, basisCode = 2, accuracyPercent = 45
        )
    )
}
