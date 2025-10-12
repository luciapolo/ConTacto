// SettingsActivity.kt
package com.example.contacto.data

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.example.contacto.ui.screens.NfcSettingsScreen

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NfcSettingsScreen(
                    onBack = { finish() }   // vuelve atrás; el toggle ya quedó persistido
                )
            }
        }
    }
}
