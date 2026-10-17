package io.ather.pro.data.auth

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class AtherAuthApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    private val gson = Gson()

    fun requestOtp(
        phone: String,
        countryCode: String = "IN",
        callback: (Result<Unit>) -> Unit
    ) {
        val payload = JsonObject().apply {
            addProperty("contact_no", phone)
            addProperty("country_code", countryCode)
            add("notification_medium", JsonObject().apply {
                addProperty("whatsapp", false)
                addProperty("sms", true)
            })
        }
        postJson(GENERATE_OTP_URL, payload, token = null) { result ->
            callback(result.map { })
        }
    }

    fun verifyOtp(
        phone: String,
        otp: String,
        countryCode: String = "IN",
        callback: (Result<String>) -> Unit
    ) {
        val payload = JsonObject().apply {
            addProperty("contact_no", phone)
            addProperty("userOtp", otp)
            // The login service expects a boolean here; a string value is rejected.
            addProperty("is_mobile_login", true)
            addProperty("country_code", countryCode)
        }
        postJson(VERIFY_OTP_URL, payload, token = null) { result ->
            callback(
                result.mapCatching { root ->
                    extractToken(root) ?: error("Login succeeded but token was missing")
                }
            )
        }
    }

    fun fetchScooters(token: String, callback: (Result<List<DiscoveredScooter>>) -> Unit) {
        getJson(ME_URL, token) { meResult ->
            meResult.fold(
                onSuccess = { root ->
                    val fromMe = parseVehicles(root)
                    if (fromMe.isNotEmpty()) {
                        callback(Result.success(fromMe))
                    } else {
                        // /me often returns vehicles: [] even when scooters exist.
                        fetchScootersFromFirebaseDbs(token, callback)
                    }
                },
                onFailure = { error ->
                    if (error is AuthExpiredException) {
                        callback(Result.failure(error))
                    } else {
                        fetchScootersFromFirebaseDbs(token, callback)
                    }
                }
            )
        }
    }

    private fun fetchScootersFromFirebaseDbs(
        token: String,
        callback: (Result<List<DiscoveredScooter>>) -> Unit
    ) {
        getJson(FIREBASE_SCOOTERS_URL, token) { result ->
            result.fold(
                onSuccess = { root ->
                    val stubs = parseFirebaseScooters(root)
                    if (stubs.isEmpty()) {
                        callback(Result.success(emptyList()))
                        return@fold
                    }
                    enrichScooters(token, stubs, callback)
                },
                onFailure = { error -> callback(Result.failure(error)) }
            )
        }
    }

    private fun enrichScooters(
        token: String,
        stubs: List<DiscoveredScooter>,
        callback: (Result<List<DiscoveredScooter>>) -> Unit
    ) {
        if (stubs.isEmpty()) {
            callback(Result.success(emptyList()))
            return
        }
        val enriched = arrayOfNulls<DiscoveredScooter>(stubs.size)
        val remaining = java.util.concurrent.atomic.AtomicInteger(stubs.size)
        var hardFailure: Exception? = null
        stubs.forEachIndexed { index, stub ->
            validateScooter(token, stub.uuid) { result ->
                result.fold(
                    onSuccess = { scooter -> enriched[index] = scooter },
                    onFailure = { error ->
                        if (error is AuthExpiredException) {
                            hardFailure = error
                        }
                        enriched[index] = stub
                    }
                )
                if (remaining.decrementAndGet() == 0) {
                    val fatal = hardFailure
                    if (fatal != null) {
                        callback(Result.failure(fatal))
                    } else {
                        callback(Result.success(enriched.filterNotNull()))
                    }
                }
            }
        }
    }

    fun validateScooter(
        token: String,
        uuid: String,
        callback: (Result<DiscoveredScooter>) -> Unit
    ) {
        val request = Request.Builder()
            .url("$SCOOTER_PROPERTIES_URL?uuid=$uuid&state=reported")
            .atherHeaders(token)
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.code == 401 || it.code == 403) {
                        callback(Result.failure(AuthExpiredException(it.message)))
                        return
                    }
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Scooter lookup HTTP ${it.code}")))
                        return
                    }
                    val root = runCatching {
                        gson.fromJson(it.body?.string(), JsonObject::class.java)
                    }.getOrElse { error ->
                        callback(Result.failure(error))
                        return
                    }
                    val data = root?.get("data")?.takeIf { el -> el.isJsonObject }?.asJsonObject
                    if (data == null) {
                        callback(Result.failure(IOException("Scooter not found for that UUID")))
                        return
                    }
                    val resolvedUuid = data.text("uuid") ?: uuid
                    callback(
                        Result.success(
                            DiscoveredScooter(
                                uuid = resolvedUuid,
                                displayName = data.text("display_name", "registration", "bike_id")
                                    ?: "Scooter",
                                registration = data.text("registration"),
                                modelType = data.text("model_type", "model", "bike_type"),
                                colour = data.text("colour")
                            )
                        )
                    )
                }
            }
        })
    }

    private fun getJson(
        url: String,
        token: String,
        callback: (Result<JsonObject>) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .atherHeaders(token)
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.code == 401 || it.code == 403) {
                        callback(Result.failure(AuthExpiredException(it.message)))
                        return
                    }
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("HTTP ${it.code}")))
                        return
                    }
                    val body = it.body?.string().orEmpty()
                    val root = runCatching {
                        if (body.isBlank()) JsonObject()
                        else gson.fromJson(body, JsonObject::class.java) ?: JsonObject()
                    }.getOrElse { error ->
                        callback(Result.failure(error))
                        return
                    }
                    callback(Result.success(root))
                }
            }
        })
    }

    private fun postJson(
        url: String,
        payload: JsonObject,
        token: String?,
        callback: (Result<JsonObject>) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .atherHeaders(token)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val errorJson = runCatching {
                            gson.fromJson(body, JsonObject::class.java)
                        }.getOrNull()
                        val errorCode = runCatching {
                            errorJson
                                ?.get("errorCode")
                                ?.takeIf { element -> element.isJsonPrimitive }
                                ?.asInt
                        }.getOrNull()
                        if (errorCode == APP_CHECK_MISSING || errorCode == APP_CHECK_INVALID) {
                            // The login routes demand a Firebase App Check attestation that only
                            // the official Ather app can produce. Surface it as such instead of
                            // the server's generic "try again" message.
                            callback(Result.failure(AppCheckRequiredException(errorCode)))
                            return
                        }
                        val message = errorJson?.text("message", "error", "msg") ?: "HTTP ${it.code}"
                        callback(Result.failure(IOException(message)))
                        return
                    }
                    val root = runCatching {
                        if (body.isBlank()) JsonObject()
                        else gson.fromJson(body, JsonObject::class.java) ?: JsonObject()
                    }.getOrElse { error ->
                        callback(Result.failure(error))
                        return
                    }
                    callback(Result.success(root))
                }
            }
        })
    }

    private fun extractToken(root: JsonObject): String? {
        root.text("token", "access_token")?.let { return it }
        root.get("token")?.takeIf { it.isJsonObject }?.asJsonObject?.text("token", "access_token")
            ?.let { return it }
        root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.text("token", "access_token")
            ?.let { return it }
        return null
    }

    private fun parseVehicles(root: JsonObject?): List<DiscoveredScooter> {
        if (root == null) return emptyList()
        val vehicles = root.arrayOrNull("vehicles")
            ?: root.objectOrNull("data")?.arrayOrNull("vehicles")
            ?: root.arrayOrNull("scooters")
            ?: JsonArray()
        return vehicles.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val uuid = obj.text("uuid", "vehicle_uuid", "scooter_uuid", "id") ?: return@mapNotNull null
            DiscoveredScooter(
                uuid = uuid,
                displayName = obj.text("display_name", "name", "nickname", "registration")
                    ?: "Scooter",
                registration = obj.text("registration", "reg_no"),
                modelType = obj.text("model_type", "model", "bike_type", "type"),
                colour = obj.text("colour", "color")
            )
        }
    }

    private fun parseFirebaseScooters(root: JsonObject?): List<DiscoveredScooter> {
        if (root == null) return emptyList()
        val shards = root.arrayOrNull("shardDetails")
            ?: root.objectOrNull("data")?.arrayOrNull("shardDetails")
            ?: JsonArray()
        return shards.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val uuid = obj.text("scooter_uuid", "uuid", "vehicle_uuid") ?: return@mapNotNull null
            DiscoveredScooter(
                uuid = uuid,
                displayName = obj.text("scooter", "display_name", "name") ?: "Scooter"
            )
        }
    }

    private fun Request.Builder.atherHeaders(token: String?): Request.Builder {
        addHeader("Content-Type", "application/json")
        addHeader("Accept", "application/json")
        addHeader("Accept-Charset", "UTF-8")
        addHeader("User-Agent", "ktor-client")
        addHeader("Source", "ATHER_APP/$APP_VERSION")
        addHeader("X-Platform", "Android")
        addHeader("X-Platform-Version", "14")
        addHeader("X-Device-Info", "Google Pixel 8 Pro")
        // Official clients always send this header, empty during login.
        addHeader("Authorization", "Bearer ${token.orEmpty()}")
        return this
    }

    private fun JsonObject?.objectOrNull(key: String): JsonObject? =
        this?.get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject?.arrayOrNull(key: String): JsonArray? =
        this?.get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray

    private fun JsonObject?.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        this?.get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val APP_VERSION = "13.5.1"

        // Ather moved login to its versioned "req" routes. The old /auth/v2 routes answer every
        // client with a hard "Outdated App version." response and can no longer authenticate.
        // Both current routes are gated by Firebase App Check (see AppCheckRequiredException).
        private const val GENERATE_OTP_URL = "https://cerberus.ather.io/api/v2/req/9a11"
        private const val VERIFY_OTP_URL = "https://cerberus.ather.io/api/v2/req/9a12"

        private const val APP_CHECK_MISSING = 1013
        private const val APP_CHECK_INVALID = 1014
        private const val ME_URL = "https://cerberus.ather.io/api/v1/me"
        private const val FIREBASE_SCOOTERS_URL =
            "https://cerberus.ather.io/api/v2/auth/user/scooters/firebase-dbs"
        private const val SCOOTER_PROPERTIES_URL =
            "https://cerberus.ather.io/api/v1/devices/shadows/scooters/properties"
    }
}

class AuthExpiredException(message: String? = null) :
    IOException(message ?: "Session expired. Please sign in again.")

/**
 * Raised when Ather's login service rejects a request because it carries no acceptable Firebase
 * App Check attestation (errorCode 1013 when missing, 1014 when invalid).
 *
 * Since the official 13.x app, the OTP login routes only accept attestations minted by the
 * official Ather app, bound to its package, signing certificate, and Firebase project.
 * Third-party clients cannot produce one, so OTP sign-in cannot complete in Athr+.
 * Already-imported sessions are unaffected and keep working.
 */
class AppCheckRequiredException(val errorCode: Int? = null) :
    IOException(appCheckBlockedMessage(errorCode))

private fun appCheckBlockedMessage(errorCode: Int?): String {
    val suffix = errorCode?.let { " (App Check error $it)" }.orEmpty()
    return "Ather now allows OTP sign-in only in its official app$suffix. " +
        "Import a session file to keep using Athr+ — see the README, Sign-in status."
}
