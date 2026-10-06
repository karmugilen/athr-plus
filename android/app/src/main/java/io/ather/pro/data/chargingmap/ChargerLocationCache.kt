package io.ather.pro.data.chargingmap

import com.google.gson.Gson
import io.ather.pro.domain.chargingmap.ChargerConnector
import io.ather.pro.domain.chargingmap.ChargerLocation
import io.ather.pro.domain.chargingmap.TariffLine
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/** Last public-charger list for one area. A later search replaces it. */
class ChargerLocationCache(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val maxDistanceKm: Double = MAX_DISTANCE_KM
) {
    fun loadNear(latitude: Double, longitude: Double): List<ChargerLocation>? {
        val saved = read() ?: return null
        if (now() - saved.savedAtMs !in 0L..maxAgeMs) return null
        if (kmBetween(latitude, longitude, saved.latitude, saved.longitude) > maxDistanceKm) return null
        return onlyLocations(saved.locations)
    }

    fun save(latitude: Double, longitude: Double, locations: List<ChargerLocation>) {
        if (locations.isEmpty() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        val saved = Saved(latitude, longitude, now(), locations)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Gson().toJson(saved))
        }
    }

    private fun read(): Saved? {
        if (!file.isFile) return null
        return runCatching { Gson().fromJson(file.readText(), Saved::class.java) }.getOrNull()
    }

    /**
     * A release build can decode this list as raw JSON maps. Hand those back only
     * when every row is a real charger, and let the screen fetch again otherwise.
     */
    private fun onlyLocations(raw: List<*>?): List<ChargerLocation>? {
        if (raw.isNullOrEmpty()) return null
        val out = ArrayList<ChargerLocation>(raw.size)
        for (item in raw) {
            if (item !is ChargerLocation) return null
            if (!instancesOf(item.connectors, ChargerConnector::class.java)) return null
            if (!instancesOf(item.tariffLines, TariffLine::class.java)) return null
            if (!instancesOf(item.locationTags, String::class.java)) return null
            out.add(item)
        }
        return out
    }

    private fun instancesOf(raw: List<*>?, type: Class<*>): Boolean {
        if (raw == null) return true
        for (item in raw) {
            if (!type.isInstance(item)) return false
        }
        return true
    }

    private data class Saved(
        val latitude: Double,
        val longitude: Double,
        val savedAtMs: Long,
        val locations: List<ChargerLocation>?
    )

    companion object {
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val MAX_DISTANCE_KM = 2.0

        fun kmBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2.0))
            val y = Math.toRadians(lat2 - lat1)
            return 6371.0 * hypot(x, y)
        }
    }
}
