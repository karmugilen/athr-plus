package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ConnectionStatus
import org.junit.Assert.*
import org.junit.Test

class ChargeSnapshotRefreshTest {
    @Test fun connectsEveryFiveSecondsEvenWhenTheSocketLooksHealthy() {
        assertEquals(5_000L, ChargeSnapshotRefresh.INTERVAL_MS)
        assertFalse(ChargeSnapshotRefresh.isDue(true, ConnectionStatus.CONNECTED, 1_000, 5_999))
        assertTrue(ChargeSnapshotRefresh.isDue(true, ConnectionStatus.CONNECTED, 1_000, 6_000))
    }
    @Test fun doesNotInterruptAnInFlightConnectionOrDisabledLimit() {
        assertFalse(ChargeSnapshotRefresh.isDue(false, ConnectionStatus.CONNECTED, 1_000, 500_000))
        assertFalse(ChargeSnapshotRefresh.isDue(true, ConnectionStatus.CONNECTING, 1_000, 500_000))
        assertFalse(ChargeSnapshotRefresh.isDue(true, ConnectionStatus.DISCONNECTED, 1_000, 500_000))
        assertFalse(ChargeSnapshotRefresh.isDue(true, ConnectionStatus.CONNECTED, null, 500_000))
    }
}
