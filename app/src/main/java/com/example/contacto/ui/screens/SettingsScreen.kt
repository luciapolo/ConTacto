package com.example.contacto.ui.screens

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// -------- Helper para activar/desactivar el alias --------
private fun setNfcOutsideReadingEnabled(context: Context, enabled: Boolean) {
    val pm = context.packageManager
    val component = ComponentName(
        context, "com.example.contacto.nfc.NfcReaderAlias" // FQCN exacto del alias
    )
    pm.setComponentEnabledSetting(
        component,
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP
    )
}

// ---------------- ViewModel + estado ----------------
class NfcSettingsViewModel(app: Application) : AndroidViewModel(app) {
    // En real usarías DataStore; aquí mantenemos el estado en memoria para lo esencial
    private val _outside = MutableStateFlow(false)
    val outside: StateFlow<Boolean> = _outside

    fun setOutside(enabled: Boolean) {
        _outside.update { enabled }
        setNfcOutsideReadingEnabled(getApplication(), enabled)
    }
}

// ---------------- Pantalla Compose ----------------
@Composable
fun NfcSettingsScreen(vm: NfcSettingsViewModel = viewModel()) {
    val outside by vm.outside.collectAsState()

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Configuración NFC", style = MaterialTheme.typography.titleMedium)

        ListItem(
            headlineContent = { Text("Leer etiquetas fuera de la app") },
            supportingContent = {
                Text(
                    if (outside)
                        "Al tocar un tag, se abrirá ConTacto aunque esté cerrada."
                    else
                        "Solo leerá si abres la app y pulsas el botón de leer."
                )
            },
            trailingContent = {
                Switch(
                    checked = outside,
                    onCheckedChange = { vm.setOutside(it) }
                )
            }
        )
        Divider()
    }
}
