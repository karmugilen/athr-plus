package io.ather.pro.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPrefsMigrationTest {

    @Test
    fun parseTripsJson_readsOfficialAndLocalFlags() {
        val json = """
            [
              {
                "id": "official-1",
                "startTimeMs": 1000,
                "endTimeMs": 2000,
                "distanceKm": 12.5,
                "socConsumed": 0.0,
                "energyConsumedWh": 400.0,
                "efficiencyWhPerKm": 32.0,
                "electricityCostInr": 3.2,
                "startOdoKm": 0.0,
                "endOdoKm": 0.0,
                "isOfficialRide": true
              },
              {
                "id": "local-1",
                "startTimeMs": 3000,
                "endTimeMs": 4000,
                "distanceKm": 1.2,
                "socConsumed": 1.5,
                "energyConsumedWh": 40.0,
                "efficiencyWhPerKm": 33.3,
                "electricityCostInr": 0.32,
                "startOdoKm": 10.0,
                "endOdoKm": 11.2,
                "isOfficialRide": false
              }
            ]
        """.trimIndent()

        val trips = LegacyPrefsMigration.parseTripsJson(json)
        assertEquals(2, trips.size)
        assertTrue(trips[0].isOfficialRide)
        assertEquals("local-1", trips[1].id)
        assertEquals(1.5, trips[1].socConsumed, 0.001)
        assertNull(trips[0].durationSeconds)
        assertNull(trips[0].averageSpeedKmh)
        assertNull(trips[0].topSpeedKmh)
        assertNull(trips[0].encodedPolyline)
        assertNull(trips[0].routeSpeedsKmh)
    }

    @Test
    fun parseTripsJson_rejectsBlankOrInvalid() {
        assertTrue(LegacyPrefsMigration.parseTripsJson(null).isEmpty())
        assertTrue(LegacyPrefsMigration.parseTripsJson("").isEmpty())
        assertTrue(LegacyPrefsMigration.parseTripsJson("not-json").isEmpty())
        assertTrue(
            LegacyPrefsMigration.parseTripsJson(
                """[{"id":"","startTimeMs":0,"endTimeMs":1,"distanceKm":1.0,"socConsumed":0.0,"energyConsumedWh":0.0,"efficiencyWhPerKm":0.0,"electricityCostInr":0.0,"startOdoKm":0.0,"endOdoKm":0.0}]"""
            ).isEmpty()
        )
    }

    @Test
    fun parseTelemetryHistoryJson_filtersAndSorts() {
        val json = """
            [
              {"timestamp": 3000, "speedKmh": 20.0, "batterySoc": 80.0, "mode": "Ride"},
              {"timestamp": 1000, "speedKmh": 10.0, "batterySoc": 82.0, "mode": "Eco"},
              {"timestamp": 2000, "speedKmh": 999.0, "batterySoc": 150.0, "mode": "Bad"}
            ]
        """.trimIndent()

        val samples = LegacyPrefsMigration.parseTelemetryHistoryJson(json)
        assertEquals(2, samples.size)
        assertEquals(1000L, samples[0].timestamp)
        assertEquals(3000L, samples[1].timestamp)
    }

    @Test
    fun parseBaselineFromPrefs_requiresValidBits() {
        val odoBits = java.lang.Double.doubleToRawLongBits(1234.5)
        val socBits = java.lang.Double.doubleToRawLongBits(77.0)
        val baseline = LegacyPrefsMigration.parseBaselineFromPrefs(
            hasOdo = true,
            hasSoc = true,
            odoBits = odoBits,
            socBits = socBits,
            timestampMs = 42L
        )
        assertEquals(1234.5, baseline!!.odometerKm, 0.001)
        assertEquals(77.0, baseline.batterySoc, 0.001)
        assertEquals(42L, baseline.timestampMs)

        assertNull(
            LegacyPrefsMigration.parseBaselineFromPrefs(
                hasOdo = false,
                hasSoc = true,
                odoBits = odoBits,
                socBits = socBits,
                timestampMs = 42L
            )
        )
    }

    @Test
    fun tripEntity_roundTripsDomain() {
        val original = io.ather.pro.domain.model.TripRecord(
            id = "abc",
            startTimeMs = 1,
            endTimeMs = 2,
            distanceKm = 3.0,
            socConsumed = 4.0,
            energyConsumedWh = 5.0,
            efficiencyWhPerKm = 6.0,
            electricityCostInr = 7.0,
            startOdoKm = 8.0,
            endOdoKm = 9.0,
            estimatedPackCapacityWh = 2800.0,
            isOfficialRide = true
        )
        val restored = TripEntity.fromDomain(original).toDomain()
        assertEquals(original, restored)
    }

    @Test
    fun tripEntity_roundTripsRouteFieldsAndDropsNonFiniteSpeeds() {
        val original = io.ather.pro.domain.model.TripRecord(
            id = "route",
            startTimeMs = 10,
            endTimeMs = 20,
            distanceKm = 1.0,
            socConsumed = 0.0,
            energyConsumedWh = 10.0,
            efficiencyWhPerKm = 10.0,
            electricityCostInr = 0.1,
            startOdoKm = 0.0,
            endOdoKm = 0.0,
            durationSeconds = 209.3,
            averageSpeedKmh = 18.3,
            topSpeedKmh = 30.0,
            encodedPolyline = "ENCODED",
            routeSpeedsKmh = listOf(18.0, 20.0)
        )
        val entity = TripEntity.fromDomain(original)
        assertEquals("18.0,20.0", entity.routeSpeeds)
        assertEquals(original, entity.toDomain())

        val filtered = TripEntity.fromDomain(
            original.copy(routeSpeedsKmh = listOf(18.0, Double.NaN, Double.POSITIVE_INFINITY, 20.0))
        )
        assertEquals("18.0,20.0", filtered.routeSpeeds)
        assertEquals(listOf(18.0, 20.0), filtered.toDomain().routeSpeedsKmh)
        assertNull(TripEntity.fromDomain(original.copy(routeSpeedsKmh = emptyList())).routeSpeeds)
        assertNull(TripEntity.fromDomain(original.copy(routeSpeedsKmh = null)).toDomain().routeSpeedsKmh)
        assertNull(entity.copy(routeSpeeds = " ").toDomain().routeSpeedsKmh)
        assertNull(entity.copy(routeSpeeds = "nope").toDomain().routeSpeedsKmh)
        assertEquals(listOf(18.0, 21.5), entity.copy(routeSpeeds = "18.0, nope, 21.5").toDomain().routeSpeedsKmh)
    }
}
