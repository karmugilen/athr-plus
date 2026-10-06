package io.ather.pro.data.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.VehicleProfile
import io.ather.pro.domain.ride.RideLog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

class AtherApiClient : AtherCloudApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val httpClient = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val gson = Gson()

    override fun connect(token: String, uuid: String, listener: AtherCloudApi.Listener): WebSocket {
        val request = Request.Builder()
            .url("wss://cerberus.ather.io/api/v1/ws/devices/shadows/onchange?uuid=$uuid")
            .addHeader("Source", "ATHER_APP/13.2.0")
            .addHeader("X-Platform", "Android")
            .addHeader("X-Platform-Version", "14")
            .addHeader("X-Device-Info", "Google Pixel 8 Pro")
            .addHeader("User-Agent", "Ather/13.2.0 android/14 (Google Pixel 8 Pro)")
            .addHeader("Authorization", "Bearer $token")
            .build()

        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                listener.onConnected(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    parseTelemetry(text)?.let(listener::onTelemetry)
                } catch (error: Exception) {
                    listener.onError("Could not read telemetry: ${error.message ?: "invalid response"}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val message = if (response?.code == 401) "Session expired (401)" else t.message ?: "Connection failed"
                listener.onDisconnected(message)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected(reason.ifBlank { "Connection closed ($code)" })
            }
        })
    }

    override fun subscribe(socket: WebSocket): Boolean = socket.send(SUBSCRIPTION)

    /**
     * Ather remote charging is an HTTP device-shadow mutation. WebSocket.send() only
     * queues bytes and is not an acknowledgement, so commands never travel over the
     * telemetry socket and UI state is left to the subsequent shadow/telemetry update.
     */
    fun setRemoteCharging(
        token: String,
        uuid: String,
        start: Boolean,
        callback: (Result<Unit>) -> Unit
    ) {
        val payload = buildRemoteChargingShadowPayload(uuid, start, System.currentTimeMillis())
        val request = Request.Builder()
            .url("https://cerberus.ather.io/api/v1/devices/shadows/scooters?uuid=$uuid")
            .atherHeaders(token)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).enqueue(resultCallback(callback))
    }

    /** Gateway adapter for [io.ather.pro.domain.charging.RemoteChargingDispatcher]. */
    override fun asRemoteChargingGateway(): io.ather.pro.domain.charging.RemoteChargingGateway =
        io.ather.pro.domain.charging.RemoteChargingGateway { token, scooterUuid, start, callback ->
            setRemoteCharging(token, scooterUuid, start, callback)
        }

    /**
     * Exact verified Cerberus desired-shadow mutation for remote charging.
     * Do not alter field names/values without a live field re-verify.
     */
    internal fun buildRemoteChargingShadowPayload(
        uuid: String,
        start: Boolean,
        timestampMs: Long
    ): JsonObject {
        val command = JsonObject().apply {
            addProperty("state", 1)
            addProperty("action", if (start) "start" else "stop")
            addProperty("error", "0")
            addProperty("timestamp", timestampMs)
        }
        val desired = JsonObject().apply { add("remote_charging", command) }
        return JsonObject().apply {
            add("state", JsonObject().apply { add("desired", desired) })
            addProperty("request_id", "ma_$uuid")
        }
    }

    override fun fetchVehicleProfile(
        token: String,
        uuid: String,
        callback: (Result<VehicleProfile>) -> Unit
    ) {
        val request = Request.Builder()
            .url("https://cerberus.ather.io/api/v1/devices/shadows/scooters/properties?uuid=$uuid&state=reported")
            .atherHeaders(token)
            .get()
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                callback(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Scooter properties HTTP ${it.code}")))
                        return
                    }
                    val data = runCatching {
                        gson.fromJson(it.body?.string(), JsonObject::class.java)
                            ?.objectOrNull("data")
                            ?: error("Scooter properties data missing")
                    }.getOrElse { error ->
                        callback(Result.failure(error))
                        return
                    }
                    callback(
                        Result.success(
                            VehicleProfile(
                                scooterId = data.text("bike_id"),
                                modelType = data.text("model_type"),
                                modelCode = data.text("model"),
                                generation = data.text("generation"),
                                bikeType = data.text("bike_type"),
                                platform = data.text("platform"),
                                colour = data.text("colour")
                            )
                        )
                    )
                }
            }
        })
    }

    override fun fetchRides(
        token: String,
        scooterId: String,
        callback: (Result<List<RideLog.CloudFields>>) -> Unit
    ) {
        val request = Request.Builder()
            .url("https://cerberus.ather.io/api/v1/rides?scooterid=$scooterId&limit=100&page=1")
            .atherHeaders(token)
            .addHeader("X-Request-Source", "ATHER_APP")
            .get()
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                callback(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Rides HTTP ${it.code}")))
                        return
                    }
                    val result = runCatching {
                        val raw = it.body?.string() ?: return@runCatching emptyList()
                        parseRides(raw)
                    }
                    callback(result)
                }
            }
        })
    }

    /**
     * Parses a Cerberus rides payload. A top-level "trips" array wins over data.trips.
     * Rides without a polyline are kept; route fields stay null.
     */
    internal fun parseRides(raw: String): List<RideLog.CloudFields> {
        val root = gson.fromJson(raw, JsonObject::class.java)
        val trips = root?.get("trips")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: root?.objectOrNull("data")?.get("trips")
                ?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: JsonArray()
        return trips.mapNotNull { item ->
            val ride = item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val id = ride.text("ride_id") ?: return@mapNotNull null
            val start = ride.epochMillis("ride_start_time") ?: return@mapNotNull null
            val end = ride.epochMillis("ride_end_time") ?: start
            val distanceKm = (ride.decimal("distance_m") ?: return@mapNotNull null) / 1000.0
            if (distanceKm <= 0.0) return@mapNotNull null
            val polyline = ride.objectOrNull("polyline_details")
            RideLog.CloudFields(
                id = "ather-$id",
                startTimeMs = start,
                endTimeMs = end,
                distanceKm = distanceKm,
                efficiencyWhPerKm = ride.decimal("efficiency_wh_km")?.takeIf { value -> value > 0.0 },
                durationSeconds = ride.nonNegativeFinite("duration_secs"),
                averageSpeedKmh = ride.nonNegativeFinite("avg_display_speed_kmph"),
                topSpeedKmh = ride.nonNegativeFinite("max_display_speed_kmph"),
                encodedPolyline = polyline?.text("polyline"),
                routeSpeedsKmh = polyline.speedSamples()
            )
        }
    }

    private fun JsonObject.nonNegativeFinite(key: String): Double? =
        decimal(key)?.takeIf { value -> value.isFinite() && value >= 0.0 }

    private fun JsonObject?.speedSamples(): List<Double>? {
        val array = this?.get("speed")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null
        return array.mapNotNull { element ->
            element.asSafeDouble()?.takeIf { speed -> speed.isFinite() && speed >= 0.0 }
        }.takeIf { it.isNotEmpty() }
    }

    private fun resultCallback(callback: (Result<Unit>) -> Unit) = object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            callback(Result.failure(error))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (it.isSuccessful) callback(Result.success(Unit))
                else callback(Result.failure(IOException("Command failed HTTP ${it.code}")))
            }
        }
    }

    private fun Request.Builder.atherHeaders(token: String): Request.Builder = this
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Source", "ATHER_APP/13.2.0")
        .addHeader("X-Platform", "Android")
        .addHeader("X-Platform-Version", "14")
        .addHeader("Accept", "application/json")
        .addHeader("Accept-Charset", "UTF-8")
        .addHeader("User-Agent", "ktor-client")

    /**
     * Parses a Cerberus shadow/telemetry JSON frame into [ScooterTelemetry].
     * Internal for deterministic unit tests; public connect/subscribe API unchanged.
     */
    internal fun parseTelemetry(raw: String): ScooterTelemetry? {
        val parsed = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val root = unwrapShadowRoot(parsed)

        val bike = root.resolveObject("telemetry.bike", "bike")
        val charging = root.resolveObject("telemetry.charging", "charging")
        val appFeatures = root.resolveObject(
            "app.ather_stack_features",
            "ather_stack_features",
            "stack_features"
        )
        val remoteCharging = root.resolveObject(
            "scooters.remote_charging",
            "remote_charging"
        )

        val battery = bike.decimal("battery_soc", "soc", "shortSOC")
            ?: root.decimal(
                "telemetry.bike.battery_soc",
                "battery_soc",
                "soc",
                "shortSOC"
            )
        val range = bike.decimal("range")
            ?: root.decimal("telemetry.bike.range", "range")
        val odo = bike.decimal("odo")
            ?: root.decimal("telemetry.bike.odo", "odo")
        val vehicleState = bike.text("vehicle_state")
            ?: root.text("telemetry.bike.vehicle_state", "vehicle_state")
        val mode = bike.text("mode")
            ?: root.text("telemetry.bike.mode", "mode")
        val savings = bike.decimal("savings")
            ?: root.decimal("telemetry.bike.savings", "savings")
            ?: appFeatures.decimal("savings")
        val remoteChargingAction = remoteCharging.text("action")
            ?: root.text("scooters.remote_charging.action", "remote_charging.action")

        val chargingStatus = charging.text("chargingStatus")
            ?: root.text("telemetry.charging.chargingStatus", "chargingStatus")
        val heartbeat = charging.text("chargingHeartBeat")
            ?: root.text("telemetry.charging.chargingHeartBeat", "chargingHeartBeat")
        val chargerConnected = charging.booleanLike("chargerConnected")
            ?: root.booleanLike("telemetry.charging.chargerConnected")
            ?: root.booleanLike("chargerConnected")
        // Cerberus exposes remaining-time counters in seconds; the domain uses minutes.
        val time2Full = charging.decimal("time2FullCharge")
            ?: root.decimal("telemetry.charging.time2FullCharge", "time2FullCharge")
        val time2Eighty = charging.decimal("time2EightyCharge")
            ?: root.decimal("telemetry.charging.time2EightyCharge", "time2EightyCharge")
        val chargerType = charging.text("chargerType", "charger_type", "type")
            ?: root.text(
                "telemetry.charging.chargerType",
                "telemetry.charging.charger_type",
                "chargerType",
                "charger_type",
                "type"
            )

        // Live home-charging snapshots can say Charging + heartbeat On while
        // chargerConnected is Off. The physical charging status takes precedence.
        val isCharging = when {
            io.ather.pro.domain.charging.ChargingControl.isStoppedStatus(chargingStatus) -> false
            io.ather.pro.domain.charging.ChargingControl.isActiveStatus(chargingStatus) -> true
            heartbeat.equals("On", ignoreCase = true) -> true
            heartbeat.equals("Off", ignoreCase = true) -> false
            chargerConnected == false -> false
            vehicleState.equals("charging", ignoreCase = true) -> true
            else -> null
        }

        val gpsObj = bike.resolveObject("gps_location")
            ?: root.resolveObject("telemetry.bike.gps_location", "gps_location")
        val gps = gpsObj?.let { location ->
            val lat = location.decimal("lat", "latitude")
            val lng = location.decimal("lng", "lon", "longitude")
            val alt = location.decimal("ALT_M", "altitude")
            val accuracy = location.decimal("Accuracy", "accuracy")
            val heading = location.decimal("heading", "bearing")
            val speed = location.decimal("speed", "gps_speed")
            if (lat != null || lng != null || alt != null || accuracy != null ||
                heading != null || speed != null
            ) {
                io.ather.pro.domain.model.GpsData(
                    latitude = lat,
                    longitude = lng,
                    altitudeMeters = alt,
                    accuracyMeters = accuracy,
                    heading = heading,
                    speed = speed
                )
            } else {
                null
            }
        }

        val modeRangeObj = bike.resolveObject("mode_range")
            ?: root.resolveObject("telemetry.bike.mode_range", "mode_range")
        val predictedRangeObj = bike.resolveObject("predicted_mode_range")
            ?: root.resolveObject("telemetry.bike.predicted_mode_range", "predicted_mode_range")

        val modeRanges = mutableMapOf<String, io.ather.pro.domain.model.ModeRange>()
        modeRangeObj?.entrySet()?.forEach { (rawKey, value) ->
            val modeName = canonicalModeName(rawKey) ?: return@forEach
            val rawRange = value.asSafeDouble() ?: return@forEach
            modeRanges[modeName] = io.ather.pro.domain.model.ModeRange(rawRangeKm = rawRange)
        }
        predictedRangeObj?.entrySet()?.forEach { (rawKey, value) ->
            val modeName = canonicalModeName(rawKey) ?: return@forEach
            val predictedRange = value.asSafeDouble() ?: return@forEach
            val current = modeRanges[modeName] ?: io.ather.pro.domain.model.ModeRange()
            modeRanges[modeName] = current.copy(predictedRangeKm = predictedRange)
        }

        val tpmsObj = root.resolveObject("telemetry.tpms", "tpms")
            ?: bike.resolveObject("tpms")
        val tpms = tpmsObj?.let {
            val frontPress = it.decimal(
                "front_pressure", "front", "front_tire_pressure", "frontTyrePressure", "front_psi"
            )
            val rearPress = it.decimal(
                "rear_pressure", "rear", "rear_tire_pressure", "rearTyrePressure", "rear_psi"
            )
            val frontTemp = it.decimal("front_temperature", "front_temp", "frontTemperature")
            val rearTemp = it.decimal("rear_temperature", "rear_temp", "rearTemperature")
            if (frontPress != null || rearPress != null || frontTemp != null || rearTemp != null) {
                io.ather.pro.domain.model.TpmsData(
                    frontPressurePsi = frontPress,
                    rearPressurePsi = rearPress,
                    frontTemperatureC = frontTemp,
                    rearTemperatureC = rearTemp
                )
            } else {
                null
            }
        }

        val softwareVersion = bike.text(
            "user_facing_software_version",
            "vehicle_software_version",
            "software_version",
            "ota_version",
            "stack_version"
        ) ?: root.text(
            "telemetry.bike.software_version",
            "telemetry.bike.user_facing_software_version",
            "software_version",
            "ota_version",
            "stack_version"
        )

        val connectivityStrength = bike.integer("gsm_signal", "csq", "network_strength")
            ?: root.integer(
                "telemetry.bike.gsm_signal",
                "telemetry.bike.csq",
                "gsm_signal",
                "csq",
                "network_strength"
            )

        val featureFlags = mutableMapOf<String, Boolean>()
        appFeatures?.entrySet()?.forEach { (k, v) ->
            if (!v.isJsonPrimitive) return@forEach
            val prim = v.asJsonPrimitive
            val flagVal = when {
                prim.isBoolean -> prim.asBoolean
                prim.isString -> when (prim.asString.lowercase()) {
                    "on", "true", "1", "enabled" -> true
                    "off", "false", "0", "disabled" -> false
                    else -> null
                }
                prim.isNumber -> prim.asInt == 1
                else -> null
            }
            if (flagVal != null) featureFlags[k] = flagVal
        }

        // Only explicit percentage fields qualify. A health score, capacity, or age is not SoH.
        val bms = root.resolveObject("telemetry.bms", "bms")
        val reportedSoh = (bike.decimal("soh_percent", "battery_soh_percent")
            ?: bms.decimal("soh_percent", "state_of_health_percent")
            ?: root.decimal("telemetry.bike.soh_percent", "telemetry.bike.battery_soh_percent"))
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }

        val hasMeaningfulData = reportedSoh != null || battery != null ||
            range != null ||
            odo != null ||
            vehicleState != null ||
            mode != null ||
            isCharging != null ||
            chargerConnected != null ||
            chargingStatus != null ||
            time2Full != null ||
            time2Eighty != null ||
            savings != null ||
            gps != null ||
            modeRanges.isNotEmpty() ||
            tpms != null ||
            chargerType != null ||
            softwareVersion != null ||
            connectivityStrength != null ||
            featureFlags.isNotEmpty() ||
            remoteChargingAction != null

        if (!hasMeaningfulData) return null

        return ScooterTelemetry(
            batterySoc = battery,
            sourceTimestampMs = bike?.epochMillis("last_synced_time")
                ?: root.epochMillis("telemetry.bike.last_synced_time")
                ?: root.epochMillis("last_synced_time"),
            reportedSohPercent = reportedSoh,
            rangeKm = range,
            odoKm = odo,
            vehicleState = vehicleState,
            mode = mode,
            charging = isCharging,
            chargerConnected = chargerConnected,
            chargingStatus = chargingStatus,
            timeToFullChargeMin = time2Full?.takeIf { it.isFinite() && it >= 0.0 }?.div(60.0),
            timeToEightyChargeMin = time2Eighty?.takeIf { it.isFinite() && it >= 0.0 }?.div(60.0),
            savingsInr = savings,
            gps = gps,
            modeRanges = modeRanges,
            tpms = tpms,
            chargerType = chargerType,
            softwareVersion = softwareVersion,
            connectivityStrength = connectivityStrength,
            featureFlags = featureFlags,
            remoteChargingAction = remoteChargingAction
        )
    }

    /** Prefer state.reported, merge state.delta when both exist; else raw root. */
    private fun unwrapShadowRoot(root: JsonObject): JsonObject {
        val state = root.objectOrNull("state")
        val reported = state?.objectOrNull("reported")
            ?: root.resolveObject("state.reported")
        val delta = state?.objectOrNull("delta")
            ?: root.resolveObject("state.delta")
        return when {
            reported != null && delta != null -> mergeJsonObjects(reported, delta)
            reported != null -> reported
            delta != null -> delta
            else -> root
        }
    }

    private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject): JsonObject {
        val out = base.deepCopy()
        for ((key, value) in overlay.entrySet()) {
            val existing = out.get(key)
            if (existing != null && existing.isJsonObject && value.isJsonObject) {
                out.add(key, mergeJsonObjects(existing.asJsonObject, value.asJsonObject))
            } else {
                out.add(key, value)
            }
        }
        return out
    }

    /**
     * Resolve a JSON object by literal key, nested dotted path, or synthetic object
     * assembled from flattened `"prefix.field"` keys.
     */
    private fun JsonObject?.resolveObject(vararg keys: String): JsonObject? {
        if (this == null) return null
        for (key in keys) {
            objectOrNull(key)?.let { return it }
            nestedObject(key)?.let { return it }
            prefixedObject(key)?.let { return it }
        }
        return null
    }

    private fun JsonObject.nestedObject(path: String): JsonObject? {
        val parts = path.split('.')
        if (parts.size < 2) return null
        var current: JsonObject = this
        for (part in parts) {
            current = current.objectOrNull(part) ?: return null
        }
        return current
    }

    private fun JsonObject.prefixedObject(prefix: String): JsonObject? {
        val needle = "$prefix."
        val out = JsonObject()
        var found = false
        for ((key, value) in entrySet()) {
            if (!key.startsWith(needle)) continue
            found = true
            putDotted(out, key.removePrefix(needle), value)
        }
        return out.takeIf { found }
    }

    private fun putDotted(target: JsonObject, path: String, value: JsonElement) {
        val parts = path.split('.')
        if (parts.size == 1) {
            target.add(parts[0], value)
            return
        }
        var current = target
        for (i in 0 until parts.lastIndex) {
            val part = parts[i]
            val existing = current.get(part)
            val next = when {
                existing != null && existing.isJsonObject -> existing.asJsonObject
                else -> JsonObject().also { current.add(part, it) }
            }
            current = next
        }
        current.add(parts.last(), value)
    }

    private fun canonicalModeName(key: String): String? = io.ather.pro.domain.range.RideMode.from(key)?.apiName

    private fun JsonObject?.objectOrNull(key: String): JsonObject? =
        this?.get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject?.decimal(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
        getPrimitive(key)?.asSafeDouble()
    }

    private fun JsonElement?.asSafeDouble(): Double? = runCatching {
        when {
            this == null || isJsonNull -> null
            isJsonPrimitive && asJsonPrimitive.isNumber -> asDouble
            isJsonPrimitive -> asString.trim().replace(Regex("[^0-9.+-]"), "").toDoubleOrNull()
            else -> null
        }
    }.getOrNull()

    private fun JsonObject?.integer(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        getPrimitive(key)?.let { element ->
            runCatching { if (element.isNumber) element.asInt else element.asString.toIntOrNull() }.getOrNull()
        }
    }

    private fun JsonObject?.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        getPrimitive(key)?.let { runCatching { it.asString }.getOrNull()?.takeIf(String::isNotBlank) }
    }

    private fun JsonObject?.booleanLike(key: String): Boolean? = getPrimitive(key)?.let { element ->
        runCatching {
            if (element.isBoolean) element.asBoolean
            else when (element.asString.lowercase()) {
                "on", "true", "1", "connected" -> true
                "off", "false", "0", "disconnected" -> false
                else -> null
            }
        }.getOrNull()
    }

    private fun JsonObject?.getPrimitive(key: String): com.google.gson.JsonPrimitive? =
        this?.get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asJsonPrimitive

    private fun JsonObject.epochMillis(key: String): Long? = get(key)?.let { element ->
        runCatching {
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asLong
                element.isJsonPrimitive -> element.asString.toLongOrNull()
                else -> null
            }
        }.getOrNull()
    }

    private companion object {
        val SUBSCRIPTION = """
            {
              "paths": [
                "telemetry.bike",
                "telemetry.charging",
                "telemetry.tpms",
                "scooters.remote_charging",
                "scooters.properties",
                "scooters.bike",
                "app.ather_stack_features"
              ]
            }
        """.trimIndent()

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
