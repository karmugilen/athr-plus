package io.ather.pro.data.chargingmap

import io.ather.pro.domain.chargingmap.ChargerLocation
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChargerLocationCacheTest {
    private var now = 5_000_000L

    @Test fun sameAreaReturnsTheSavedListAndADistantSearchDoesNot() {
        val cache = ChargerLocationCache(tempFile(), now = { now })
        cache.save(13.08, 80.27, listOf(charger("Anna Nagar")))
        assertEquals("Anna Nagar", cache.loadNear(13.081, 80.271)!!.single().name)
        assertNull(cache.loadNear(12.9, 80.1))
        now += ChargerLocationCache.MAX_AGE_MS + 1
        assertNull(cache.loadNear(13.08, 80.27))
    }

    private fun tempFile() = File.createTempFile("chargers", ".json")

    private fun charger(name: String) = ChargerLocation(
        name = name, infraType = null, address = null, latitude = 13.08, longitude = 80.27,
        isOpenNow = true, closingIn = null, nextOpening = null, locationTags = emptyList(),
        dbsAvailable = 1, dbsInUse = 0, dbsUnderMaintenance = null, dbsOutOfOperatingHours = null,
        dbsTotal = 1, connectors = emptyList(), tariffLines = emptyList(), partyId = null, has6kwGrid = null
    )
}
