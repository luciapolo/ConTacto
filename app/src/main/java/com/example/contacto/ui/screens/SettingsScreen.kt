package com.example.contacto.ui.screens
import androidx.lifecycle.viewModelScope
import com.example.contacto.data.observeOutsideReading
import com.example.contacto.data.setOutsideReading
import kotlinx.coroutines.launch




import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private fun setNfcOutsideReadingEnabled(context: Context, enabled: Boolean) {
    val pm = context.packageManager
    val component = ComponentName(context, "com.example.contacto.nfc.NfcReaderAlias")
    pm.setComponentEnabledSetting(
        component,
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP
    )
}

class NfcSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val _outside = MutableStateFlow(false)
    val outside: StateFlow<Boolean> = _outside

    init {
        // Carga inicial y escucha cambios del DataStore
        viewModelScope.launch {
            getApplication<Application>()
                .observeOutsideReading()
                .collect { _outside.value = it }
        }
    }

    fun setOutside(enabled: Boolean) {
        _outside.value = enabled                 // UI inmediata
        viewModelScope.launch {
            // persiste en DataStore
            getApplication<Application>().setOutsideReading(enabled)
            // aplica al alias/receiver del NFC
            setNfcOutsideReadingEnabled(getApplication(), enabled)
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcSettingsScreen(
    vm: NfcSettingsViewModel = viewModel(),
    onBack: () -> Unit
) {
    val outside by vm.outside.collectAsState()
    val brand = Color(0xFF0E2138) // mismo azul que Home/Rewrite

    Scaffold(
        containerColor = Color.White,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Ajustes", color = brand) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = brand
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = brand,
                    navigationIconContentColor = brand
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Panel tintado (más contraste)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = brand.copy(alpha = 0.08f),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Configuración NFC",
                        style = MaterialTheme.typography.titleMedium,
                        color = brand
                    )

                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        ListItem(
                            headlineContent = {
                                Text("Leer etiquetas fuera de la app", color = brand)
                            },
                            supportingContent = {
                                Text(
                                    if (outside)
                                        "Al tocar un tag, se abrirá ConTacto aunque esté cerrada."
                                    else
                                        "Solo leerá si abres la app y pulsas el botón de leer.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            }

        }
    }
}

