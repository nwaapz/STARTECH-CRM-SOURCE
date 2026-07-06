package com.datacall.ccp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

class AppPreferences(private val context: Context) {
    private val lastModeKey = stringPreferencesKey("last_mode")

    val lastMode: Flow<String?> = context.dataStore.data.map { it[lastModeKey] }

    suspend fun setLastMode(mode: String) {
        context.dataStore.edit { it[lastModeKey] = mode }
    }
}
