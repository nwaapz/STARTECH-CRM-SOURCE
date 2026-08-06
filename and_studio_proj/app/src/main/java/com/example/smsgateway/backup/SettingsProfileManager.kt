package com.example.smsgateway.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smsgateway.JalaliHelper
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.license.LicenseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SettingsProfileResult(
    val ok: Boolean,
    val message: String? = null,
)

/**
 * Exports/imports every key of the `gateway_settings` SharedPreferences file as a single
 * portable JSON file ("settings profile"), so the operator can save the whole configuration
 * (SMS processing mode, message templates, bot tokens, referral, LAN, cloud sync, backup,
 * Melipayamak, SIM/quota, WhatsApp, lottery) and reload it later or on another device.
 *
 * Each value keeps an explicit type tag because [android.content.SharedPreferences] is
 * type-strict per key (e.g. a key written with putLong throws if later read with getInt),
 * so plain JSON numbers/arrays are not enough to round-trip safely.
 */
object SettingsProfileManager {
    private const val TAG = "SettingsProfile"
    private const val KIND = "settings-profile"
    private const val FORMAT_VERSION = 1

    private const val TYPE_BOOL = "b"
    private const val TYPE_INT = "i"
    private const val TYPE_LONG = "l"
    private const val TYPE_FLOAT = "f"
    private const val TYPE_STRING = "s"
    private const val TYPE_STRING_SET = "ss"

    fun defaultFileName(context: Context): String {
        val phone = licensedPhoneForFileName(context)
        val stamp = JalaliHelper.nowJalaliDateTime()
            .replace('/', '-')
            .replace(' ', '_')
            .replace(':', '-')
        return "payamesh_settings_${phone}_$stamp.json"
    }

    private fun licensedPhoneForFileName(context: Context): String {
        val raw = LicenseStore.getInstance(context).getLicensedMsisdn().orEmpty()
        val normalized = PhoneUtils.formatForDisplay(raw)
            ?.let { PhoneUtils.normalizeToAsciiDigits(it) }
            ?.filter { it.isDigit() }
            .orEmpty()
        return normalized.ifBlank { "unknown" }
    }

    suspend fun exportToUri(context: Context, uri: Uri): SettingsProfileResult =
        withContext(Dispatchers.IO) {
            try {
                val snapshot = SettingsStore.getInstance(context).exportSnapshot()
                val json = JSONObject()
                    .put("app", "payamesh-gateway")
                    .put("kind", KIND)
                    .put("formatVersion", FORMAT_VERSION)
                    .put("exportedAt", System.currentTimeMillis())
                    .put("settings", snapshotToJson(snapshot))

                val output = context.contentResolver.openOutputStream(uri)
                    ?: return@withContext SettingsProfileResult(false, "Cannot open selected location")
                output.use { stream ->
                    stream.write(json.toString().toByteArray(Charsets.UTF_8))
                }
                SettingsProfileResult(true, "OK")
            } catch (e: Exception) {
                Log.w(TAG, "exportToUri failed", e)
                SettingsProfileResult(false, e.message ?: "Export failed")
            }
        }

    suspend fun importFromUri(context: Context, uri: Uri): SettingsProfileResult =
        withContext(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext SettingsProfileResult(false, "Cannot open selected file")
                val text = input.use { it.readBytes().toString(Charsets.UTF_8) }

                val json = JSONObject(text)
                if (json.optString("kind") != KIND) {
                    return@withContext SettingsProfileResult(false, "Not a settings profile file")
                }
                val settingsJson = json.optJSONObject("settings")
                    ?: return@withContext SettingsProfileResult(false, "Settings file is empty")

                val snapshot = jsonToSnapshot(settingsJson)
                SettingsStore.getInstance(context).importSnapshot(snapshot)
                SettingsProfileResult(true, "OK")
            } catch (e: Exception) {
                Log.w(TAG, "importFromUri failed", e)
                SettingsProfileResult(false, e.message ?: "Import failed")
            }
        }

    private fun snapshotToJson(snapshot: Map<String, Any?>): JSONObject {
        val out = JSONObject()
        snapshot.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put("t", TYPE_BOOL).put("v", value)
                is Int -> entry.put("t", TYPE_INT).put("v", value)
                is Long -> entry.put("t", TYPE_LONG).put("v", value)
                is Float -> entry.put("t", TYPE_FLOAT).put("v", value.toDouble())
                is String -> entry.put("t", TYPE_STRING).put("v", value)
                is Set<*> -> entry.put("t", TYPE_STRING_SET).put(
                    "v",
                    JSONArray(value.filterIsInstance<String>()),
                )
                else -> return@forEach
            }
            out.put(key, entry)
        }
        return out
    }

    private fun jsonToSnapshot(settingsJson: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        settingsJson.keys().forEach { key ->
            val entry = settingsJson.optJSONObject(key) ?: return@forEach
            when (entry.optString("t")) {
                TYPE_BOOL -> out[key] = entry.optBoolean("v")
                TYPE_INT -> out[key] = entry.optInt("v")
                TYPE_LONG -> out[key] = entry.optLong("v")
                TYPE_FLOAT -> out[key] = entry.optDouble("v").toFloat()
                TYPE_STRING -> out[key] = entry.optString("v")
                TYPE_STRING_SET -> {
                    val array = entry.optJSONArray("v") ?: JSONArray()
                    out[key] = (0 until array.length()).mapNotNull { array.optString(it) }.toSet()
                }
            }
        }
        return out
    }
}
