package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeDurationTest {
    @Test
    fun cloudMinutesFollowActiveChargingRatherThanTheBooleanFlagAlone() {
        val charging = ScooterTelemetry(
            batterySoc = 50.0,
            chargingStatus = "Charging",
            timeToEightyChargeMin = 60.0,
            timeToFullChargeMin = 150.0
        )
        assertEquals(60.0, ChargeDuration.cloudMinutes(charging, 80, 3_240.0, 8.0, null)!!, 0.001)

        val stopped = charging.copy(chargingStatus = "Stopped", charging = false)
        assertNull(ChargeDuration.cloudMinutes(stopped, 80, 3_240.0, 8.0, null))
    }
}
