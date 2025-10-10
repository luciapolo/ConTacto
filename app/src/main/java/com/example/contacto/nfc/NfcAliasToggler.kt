package com.example.contacto.nfc

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object NfcAliasToggler {
    /** Activa/desactiva el alias que lanza la app desde NFC (fuera de la app). */
    fun setOutsideReadingEnabled(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        // Usa el FQCN EXACTO del alias que tienes en el manifest
        val component = ComponentName(
            context,
            "com.example.contacto.nfc.NfcReaderAlias"
        )
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        pm.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP
        )
    }
}
