package io.ather.pro.domain.model

import io.ather.pro.domain.range.RangeEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelForRangeTest {
    @Test fun detected450SOverridesSaved450XForRangeAndModes() {
        val state = ScooterDashboardState(
            telemetry = reportedModes(),
            vehicleProfile = VehicleProfile(modelType = "450S"),
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_450X_3_7)
        )
        assertEquals(ScooterModel.ATHER_450S, state.modelForRange)
        assertEquals(ScooterModel.ATHER_450S.usableCapacityWh, state.usablePackWh, 0.0)
        assertEquals(
            listOf("SmartEco", "Eco", "Ride", "Sport"),
            RangeEstimator.modes(state.telemetry, state.modelForRange).map { it.name }
        )
    }

    @Test fun unresolved450XKeepsSaved450XAndExcludesWarpPlus() {
        val state = ScooterDashboardState(
            telemetry = reportedModes(),
            vehicleProfile = VehicleProfile(modelType = "450X"),
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_450X_3_7)
        )
        assertEquals(ScooterModel.ATHER_450X_3_7, state.modelForRange)
        assertEquals(ScooterModel.ATHER_450X_3_7.usableCapacityWh, state.usablePackWh, 0.0)
        val names = RangeEstimator.modes(state.telemetry, state.modelForRange).map { it.name }
        assertEquals(listOf("SmartEco", "Eco", "Ride", "Sport", "Warp"), names)
        assertFalse(names.contains("Warp+"))
    }

    @Test fun unresolvedProfileKeepsSaved450SAndItsFourModes() {
        val state = ScooterDashboardState(
            telemetry = reportedModes(),
            vehicleProfile = VehicleProfile(modelType = "450X"),
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_450S)
        )
        assertEquals(ScooterModel.ATHER_450S, state.modelForRange)
        assertEquals(
            listOf("SmartEco", "Eco", "Ride", "Sport"),
            RangeEstimator.modes(state.telemetry, state.modelForRange).map { it.name }
        )
    }

    @Test fun unresolvedProfileKeepsSavedApexAndShowsWarpPlusNotWarp() {
        val state = ScooterDashboardState(
            telemetry = reportedModes(),
            vehicleProfile = VehicleProfile(modelType = "450X"),
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_APEX)
        )
        assertEquals(ScooterModel.ATHER_APEX, state.modelForRange)
        val names = RangeEstimator.modes(state.telemetry, state.modelForRange).map { it.name }
        assertEquals(listOf("SmartEco", "Eco", "Ride", "Sport", "Warp+"), names)
        assertFalse(names.contains("Warp"))
    }

    private fun reportedModes() = ScooterTelemetry(mode = "Ride", modeRanges = mapOf(
        "SmartEco" to ModeRange(rawRangeKm = 40.0),
        "Eco" to ModeRange(rawRangeKm = 36.0),
        "Ride" to ModeRange(rawRangeKm = 32.0),
        "Sport" to ModeRange(rawRangeKm = 28.0),
        "Warp" to ModeRange(rawRangeKm = 24.0),
        "WarpPlus" to ModeRange(rawRangeKm = 20.0)
    ))
}
