package io.ather.pro.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * On-device encrypted session store.
 *
 * Migration: if encrypted prefs are empty, imports once from a local untracked
 * `ather_session.json` in the app files directory (adb push), then deletes that file.
 */
class SecureSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val prefs: SharedPreferences = createEncryptedPrefs(appContext)

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    init {
        if (_session.value == null) {
            importUntrackedSeedIfPresent()?.let { seeded ->
                save(seeded)
            }
        }
    }

    fun current(): AuthSession? = _session.value

    /**
     * Imports a pending `ather_session.json` on demand — for example after the user copies the
     * file into place while the app is already running. Returns the imported session, or null
     * when there is nothing valid to import. See docs/SIGN-IN.md.
     */
    fun importSeedNow(): AuthSession? {
        _session.value?.let { return it }
        val imported = importUntrackedSeedIfPresent() ?: return null
        save(imported)
        return imported
    }

    fun save(session: AuthSession) {
        val exp = session.expiresAtEpochSec ?: JwtExpiry.expiresAtEpochSec(session.token)
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_VEHICLE_UUID, session.vehicleUuid)
            .putString(KEY_PHONE, session.phone)
            .putString(KEY_COUNTRY_CODE, session.countryCode)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putLong(KEY_EXPIRES_AT, exp ?: -1L)
            .apply()
        _session.value = session.copy(expiresAtEpochSec = exp)
    }

    fun updateVehicle(uuid: String, displayName: String? = null) {
        val current = _session.value ?: return
        save(
            current.copy(
                vehicleUuid = uuid,
                displayName = displayName ?: current.displayName
            )
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun loadSession(): AuthSession? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val uuid = prefs.getString(KEY_VEHICLE_UUID, null)?.takeIf { it.isNotBlank() } ?: return null
        val expStored = prefs.getLong(KEY_EXPIRES_AT, -1L).takeIf { it > 0L }
            ?: JwtExpiry.expiresAtEpochSec(token)
        val session = AuthSession(
            token = token,
            vehicleUuid = uuid,
            phone = prefs.getString(KEY_PHONE, null),
            countryCode = prefs.getString(KEY_COUNTRY_CODE, "IN") ?: "IN",
            displayName = prefs.getString(KEY_DISPLAY_NAME, null),
            expiresAtEpochSec = expStored
        )
        return if (session.isExpired()) {
            prefs.edit().clear().apply()
            null
        } else {
            session
        }
    }

    private fun importUntrackedSeedIfPresent(): AuthSession? {
        val candidates = listOf(
            File(appContext.filesDir, SEED_FILE_NAME),
            appContext.getExternalFilesDir(null)?.let { File(it, SEED_FILE_NAME) }
        ).filterNotNull()

        for (file in candidates) {
            if (!file.isFile) continue
            val imported = runCatching {
                val root = gson.fromJson(file.readText(), JsonObject::class.java) ?: return@runCatching null
                val token = root.getAsString("token") ?: return@runCatching null
                val uuid = root.getAsString("vehicle_uuid")
                    ?: root.getAsString("vehicleUuid")
                    ?: root.getAsString("uuid")
                    ?: return@runCatching null
                AuthSession(
                    token = token,
                    vehicleUuid = uuid,
                    phone = root.getAsString("phone") ?: root.getAsString("contact_no"),
                    displayName = root.getAsString("display_name") ?: root.getAsString("displayName"),
                    expiresAtEpochSec = JwtExpiry.expiresAtEpochSec(token)
                ).takeIf { it.isComplete }
            }.getOrNull()

            if (imported != null) {
                runCatching { file.delete() }
                Log.i(TAG, "Imported local untracked session seed")
                return imported
            }
        }
        return null
    }

    private fun JsonObject.getAsString(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "SecureSessionStore"
        const val PREFS_NAME = "ather_secure_session"
        const val SEED_FILE_NAME = "ather_session.json"
        const val KEY_TOKEN = "token"
        const val KEY_VEHICLE_UUID = "vehicle_uuid"
        const val KEY_PHONE = "phone"
        const val KEY_COUNTRY_CODE = "country_code"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_EXPIRES_AT = "expires_at"

        fun createEncryptedPrefs(context: Context): SharedPreferences {
            return runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse { error ->
                Log.w(TAG, "Falling back to private prefs: ${error.message}")
                context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
    }
}
