package com.example.contacto

import android.app.Application
import com.example.contacto.data.SettingsRepository
import com.example.contacto.nfc.NfcAliasToggler.setOutsideReadingEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var settingsRepo: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)

        // Aplica el estado del toggle al inicio
        CoroutineScope(Dispatchers.Default).launch {
            val enabled = settingsRepo.outsideNfcFlow.first()
            setOutsideReadingEnabled(this@App, enabled)
        }
    }
}