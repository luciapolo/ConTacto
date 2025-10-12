// app/src/main/java/com/example/contacto/data/Prefs.kt
package com.example.contacto.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DS_NAME = "settings_prefs"

val Context.dataStore by preferencesDataStore(name = DS_NAME)

object PrefKeys {
    val OUTSIDE_READING = booleanPreferencesKey("outside_reading")
}

suspend fun Context.setOutsideReading(enabled: Boolean) {
    dataStore.edit { it[PrefKeys.OUTSIDE_READING] = enabled }
}

fun Context.observeOutsideReading(): Flow<Boolean> =
    dataStore.data.map { it[PrefKeys.OUTSIDE_READING] ?: false }
