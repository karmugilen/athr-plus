package io.ather.pro.data.local

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ather.pro.domain.model.GpsData
import io.ather.pro.domain.model.ModeRange
import io.ather.pro.domain.model.ScooterTelemetry

/** Last painted scooter reading. It is not a live packet and must not arm a Pause. */
data class SavedScooterReading(
    val vehicleUuid: String,
    val savedAtMs: Long,
    val sourceTimestampMs: Long? = null,
    val batterySoc: Double? = null,
    val rangeKm: Double? = null,
    val mode: String? = null,
    val vehicleState: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val modeRanges: Map<String, ModeRange> = emptyMap()
) {
    fun usableFor(vehicleUuid: String, nowMs: Long): Boolean {
        if (this.vehicleUuid != vehicleUuid) return false
        if (savedAtMs <= 0L || nowMs - savedAtMs !in 0L..MAX_AGE_MS) return false
        return hasSoc() || hasGps()
    }

    fun toTelemetry(): ScooterTelemetry = ScooterTelemetry(
        batterySoc = batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 },
        rangeKm = rangeKm?.takeIf { it.isFinite() && it >= 0.0 },
        vehicleState = vehicleState,
        mode = mode,
        gps = if (hasGps()) GpsData(latitude = latitude, longitude = longitude) else null,
        modeRanges = readableModeRanges(modeRanges),
        sourceTimestampMs = sourceTimestampMs
    )

    private fun hasSoc() = batterySoc?.let { it.isFinite() && it in 0.0..100.0 } == true

    private fun hasGps() = latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        longitude?.let { it.isFinite() && it in -180.0..180.0 } == true

    /** JSON keys are written out by name. Release shrinking must not rename them. */
    fun encode(): String {
        val root = JsonObject()
        root.addProperty("vehicleUuid", vehicleUuid)
        root.addProperty("savedAtMs", savedAtMs)
        sourceTimestampMs?.let { root.addProperty("sourceTimestampMs", it) }
        batterySoc?.let { root.addProperty("batterySoc", it) }
        rangeKm?.let { root.addProperty("rangeKm", it) }
        mode?.let { root.addProperty("mode", it) }
        vehicleState?.let { root.addProperty("vehicleState", it) }
        latitude?.let { root.addProperty("latitude", it) }
        longitude?.let { root.addProperty("longitude", it) }
        val ranges = JsonArray()
        for ((name, range) in readableModeRanges(modeRanges)) {
            val item = JsonObject()
            item.addProperty("name", name)
            range.rawRangeKm?.let { item.addProperty("rawRangeKm", it) }
            range.predictedRangeKm?.let { item.addProperty("predictedRangeKm", it) }
            ranges.add(item)
        }
        root.add("modeRanges", ranges)
        return root.toString()
    }

    /**
     * Release builds stored this map through Gson. Shrinking erased the value type,
     * so a saved entry can be a raw JSON map instead of a [ModeRange]. Reading it
     * as a mode range crashes the home screen.
     */
    private fun readableModeRanges(raw: Map<*, *>?): Map<String, ModeRange> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, ModeRange>()
        for (entry in raw) {
            if (out.size >= 8) break
            val name = entry.key as? String ?: continue
            if (name.isBlank()) continue
            val value = entry.value
            val range = when (value) {
                is ModeRange -> ModeRange(
                    rawRangeKm = value.rawRangeKm?.takeIf { it.isFinite() },
                    predictedRangeKm = value.predictedRangeKm?.takeIf { it.isFinite() }
                )
                is Map<*, *> -> ModeRange(
                    rawRangeKm = finiteNumber(value["rawRangeKm"]),
                    predictedRangeKm = finiteNumber(value["predictedRangeKm"])
                )
                else -> null
            } ?: continue
            if (range.rawRangeKm == null && range.predictedRangeKm == null) continue
            out[name] = range
        }
        return out
    }

    private fun finiteNumber(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun decode(json: String): SavedScooterReading? = runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            val uuid = root.optString("vehicleUuid")?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val savedAtMs = root.optLong("savedAtMs") ?: return@runCatching null
            SavedScooterReading(
                vehicleUuid = uuid,
                savedAtMs = savedAtMs,
                sourceTimestampMs = root.optLong("sourceTimestampMs"),
                batterySoc = root.optDouble("batterySoc"),
                rangeKm = root.optDouble("rangeKm"),
                mode = root.optString("mode"),
                vehicleState = root.optString("vehicleState"),
                latitude = root.optDouble("latitude"),
                longitude = root.optDouble("longitude"),
                modeRanges = decodeModeRanges(root.get("modeRanges"))
            )
        }.getOrNull()

        private fun decodeModeRanges(node: com.google.gson.JsonElement?): Map<String, ModeRange> {
            if (node == null || node.isJsonNull) return emptyMap()
            val out = LinkedHashMap<String, ModeRange>()
            if (node.isJsonArray) {
                for (el in node.asJsonArray) {
                    if (out.size >= 8 || !el.isJsonObject) continue
                    val item = el.asJsonObject
                    val name = item.optString("name")?.takeIf { it.isNotBlank() } ?: continue
                    putRange(out, name, item)
                }
            } else if (node.isJsonObject) {
                for ((name, el) in node.asJsonObject.entrySet()) {
                    if (out.size >= 8 || name.isBlank() || !el.isJsonObject) continue
                    putRange(out, name, el.asJsonObject)
                }
            }
            return out
        }

        private fun putRange(out: MutableMap<String, ModeRange>, name: String, item: JsonObject) {
            val range = ModeRange(
                rawRangeKm = item.optDouble("rawRangeKm"),
                predictedRangeKm = item.optDouble("predictedRangeKm")
            )
            if (range.rawRangeKm == null && range.predictedRangeKm == null) return
            out[name] = range
        }

        private fun JsonObject.optString(key: String): String? {
            val el = get(key) ?: return null
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) return null
            return el.asString
        }

        private fun JsonObject.optDouble(key: String): Double? {
            val el = get(key) ?: return null
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) return null
            return el.asDouble.takeIf { it.isFinite() }
        }

        private fun JsonObject.optLong(key: String): Long? {
            val el = get(key) ?: return null
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) return null
            return el.asLong
        }

        fun fromTelemetry(vehicleUuid: String, telemetry: ScooterTelemetry, savedAtMs: Long): SavedScooterReading? {
            val soc = telemetry.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }
            val latitude = telemetry.gps?.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
            val longitude = telemetry.gps?.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
            if (soc == null && (latitude == null || longitude == null)) return null
            val ranges = telemetry.modeRanges.entries
                .filter { it.key.isNotBlank() }
                .take(8)
                .associate { (name, range) ->
                    name to ModeRange(rawRangeKm = range.rawRangeKm, predictedRangeKm = range.predictedRangeKm)
                }
            return SavedScooterReading(
                vehicleUuid = vehicleUuid,
                savedAtMs = savedAtMs,
                sourceTimestampMs = telemetry.sourceTimestampMs,
                batterySoc = soc,
                rangeKm = telemetry.rangeKm?.takeIf { it.isFinite() && it >= 0.0 },
                mode = telemetry.mode,
                vehicleState = telemetry.vehicleState,
                latitude = latitude,
                longitude = longitude,
                modeRanges = ranges
            )
        }
    }
}

interface SavedScooterReadingStore {
    fun load(vehicleUuid: String): SavedScooterReading?
    fun save(reading: SavedScooterReading)
}

class SavedScooterReadingPrefs(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis
) : SavedScooterReadingStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(vehicleUuid: String): SavedScooterReading? {
        val json = prefs.getString(KEY, null) ?: return null
        return SavedScooterReading.decode(json)?.takeIf { it.usableFor(vehicleUuid, now()) }
    }

    override fun save(reading: SavedScooterReading) {
        prefs.edit().putString(KEY, reading.encode()).apply()
    }

    private companion object {
        const val PREFS = "ather_saved_reading"
        const val KEY = "reading"
    }
}
