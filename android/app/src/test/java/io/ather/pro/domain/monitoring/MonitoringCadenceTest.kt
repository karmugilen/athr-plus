package io.ather.pro.domain.monitoring

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringCadenceTest {
    @Test fun idleConnectionSleepsUntilFiveSecondRefresh() {
        assertEquals(5_000L, MonitoringCadence.nextTickDelayMs(false, false, 1_000L, 1_000L))
        assertEquals(2_000L, MonitoringCadence.nextTickDelayMs(false, false, 1_000L, 4_000L))
        assertEquals(1_000L, MonitoringCadence.nextTickDelayMs(false, false, 1_000L, 6_000L))
    }

    @Test fun cutoffAndManualCommandDeadlinesRemainResponsive() {
        assertEquals(1_000L, MonitoringCadence.nextTickDelayMs(true, false, 1_000L, 1_000L))
        assertEquals(1_000L, MonitoringCadence.nextTickDelayMs(false, true, 1_000L, 1_000L))
    }

    @Test fun disconnectedIdleRepositoryDoesNotPollEverySecond() {
        assertEquals(5_000L, MonitoringCadence.nextTickDelayMs(false, false, null, 1_000L))
    }

    @Test fun clockMovingBackwardSchedulesAPromptRecheck() {
        assertEquals(1_000L, MonitoringCadence.nextTickDelayMs(false, false, 2_000L, 1_000L))
    }
}
