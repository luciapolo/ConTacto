// app/src/main/java/com/example/contacto/data/SettingsRepository.kt
package com.example.contacto.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val context: Context) {
    // Flow que refleja el estado persistido del toggle
    val outsideNfcFlow: Flow<Boolean> = context.observeOutsideReading()

    // Setter para actualizar y persistir el estado
    suspend fun setOutsideReadingEnabled(enabled: Boolean) {
        context.setOutsideReading(enabled)
    }
}
