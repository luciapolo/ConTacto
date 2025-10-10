package com.example.contacto.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")
private val OUTSIDE_NFC = booleanPreferencesKey("outside_nfc")

class SettingsRepository(private val context: Context) {
    val outsideNfcFlow: Flow<Boolean> =
        context.dataStore.data.map { it[OUTSIDE_NFC] ?: false }

    suspend fun setOutsideNfc(enabled: Boolean) {
        context.dataStore.edit { it[OUTSIDE_NFC] = enabled }
    }
}
