package com.kvi.osquio.notifications

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object NotificationPrefsRepository {
    private const val PREFS_NAME = "prefs"
    private const val KEY_JSON = "notif_prefs_json"
    private const val KEY_VERSION = "notif_prefs_version"

    // Bump this to force all users back to silent defaults on a future upgrade.
    private const val CURRENT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): NotificationPreferences {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = sharedPrefs.getString(KEY_JSON, null)
        val decoded = stored?.let { runCatching { json.decodeFromString<NotificationPreferences>(it) }.getOrNull() }
            ?: NotificationPreferences()

        val storedVersion = sharedPrefs.getInt(KEY_VERSION, 0)
        if (storedVersion < CURRENT_VERSION) {
            val forced = decoded.copy(soundEnabled = false)
            save(context, forced)
            sharedPrefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).apply()
            return forced
        }
        return decoded
    }

    fun save(context: Context, prefs: NotificationPreferences) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_JSON, json.encodeToString(prefs)).apply()
    }
}
