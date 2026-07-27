package com.payamesh.licenseadmin

import android.content.Context

class AdminSettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isServiceEnabled(): Boolean = prefs.getBoolean(KEY_SERVICE, false)

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE, enabled).apply()
    }

    companion object {
        private const val PREFS = "license_admin_settings"
        private const val KEY_SERVICE = "service_enabled"

        @Volatile
        private var instance: AdminSettingsStore? = null

        fun getInstance(context: Context): AdminSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: AdminSettingsStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
