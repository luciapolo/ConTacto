package com.example.contacto.nfc

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import com.example.contacto.ui.theme.ConTactoTheme

// Icons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

class NfcRewriteActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        pendingMessage?.let { msg ->
            val success = writeNdefToTag(tag, msg)
            onWriteResult?.invoke(success)
        }
    }
    private var pendingMessage: NdefMessage? = null
    private var onWriteResult: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            ConTactoTheme {
                NfcRewriteScreen(
                    onBack = { finish() },
                    nfcAvailable = nfcAdapter != null,
                    onStartListening = { message, onResult ->
                        pendingMessage = message
                        onWriteResult = onResult
                        enableReaderMode()
                    },
                    onStopListening = {
                        disableReaderMode()
                        pendingMessage = null
                        onWriteResult = null
                    }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    private fun enableReaderMode() {
        nfcAdapter?.enableReaderMode(
            this,
            readerCallback,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }
}

/** =========== UI =========== */

private enum class PayloadType { CALL, URL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcRewriteScreen(
    onBack: () -> Unit,
    nfcAvailable: Boolean,
    onStartListening: (NdefMessage, (Boolean) -> Unit) -> Unit,
    onStopListening: () -> Unit
) {
    val context = LocalContext.current

    var type by remember { mutableStateOf(PayloadType.CALL) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var waiting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // --- Picker de contactos + permiso ---
    var pendingPick by remember { mutableStateOf(false) }

    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val number = c.getString(0) ?: ""
                    input = TextFieldValue(number)
                    error = null
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingPick) {
            pendingPick = false
            val intent = Intent(
                Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            )
            contactPicker.launch(intent)
        } else if (!granted) {
            error = "Necesitas permitir acceso a contactos para elegir un número."
        }
    }

    fun pickFromContacts() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val intent = Intent(
                Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            )
            contactPicker.launch(intent)
        } else {
            pendingPick = true
            permissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }
    // --- Fin picker de contactos ---

    // Construye el mensaje según el tipo elegido
    val messageToWrite by remember(type, input) {
        mutableStateOf(buildMessage(type, input.text))
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Reescribir NFC") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (waiting) onStopListening()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!nfcAvailable) WarningCard("Este dispositivo no tiene NFC o está desactivado.")

                // Selector de tipo
                SegmentedButtons(
                    type = type,
                    onTypeChange = { type = it; error = null; status = null }
                )

                // Campo según tipo
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = {
                        Text(
                            when (type) {
                                PayloadType.CALL -> "Número a llamar (p.ej. +34911222333)"
                                PayloadType.URL -> "URL (p.ej. https://miweb.com)"
                            }
                        )
                    },
                    supportingText = {
                        if (type == PayloadType.CALL)
                            Text("Se abrirá el marcador con el número (no llama automáticamente).")
                        else
                            Text("Se abrirá el navegador en esa página.")
                    },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )

                // Botón extra: Elegir de contactos (solo en modo Llamar)
                if (type == PayloadType.CALL) {
                    OutlinedButton(onClick = { pickFromContacts() }) {
                        Text("Elegir de contactos")
                    }
                }

                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        enabled = nfcAvailable && !waiting,
                        onClick = {
                            val validation = validateInput(type, input.text)
                            if (validation != null) {
                                error = validation
                                return@Button
                            }
                            status = "Acerca una etiqueta para escribir…"
                            waiting = true
                            onStartListening(messageToWrite) { success ->
                                waiting = false
                                status = if (success) "✅ ¡Escritura completada!" else "❌ No se pudo escribir."
                            }
                        }
                    ) { Text("Listo") }

                    OutlinedButton(
                        enabled = waiting,
                        onClick = {
                            onStopListening()
                            waiting = false
                            status = "Escritura cancelada."
                        }
                    ) { Text("Cancelar") }
                }

                if (waiting) AssistCard("Mantén el tag en la parte trasera del móvil hasta que vibre o aparezca el OK.")
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** =========== Helpers UI =========== */

@Composable
private fun SegmentedButtons(type: PayloadType, onTypeChange: (PayloadType) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = type == PayloadType.CALL,
            onClick = { onTypeChange(PayloadType.CALL) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) { Text("Llamar") }

        SegmentedButton(
            selected = type == PayloadType.URL,
            onClick = { onTypeChange(PayloadType.URL) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) { Text("URL") }
    }
}

@Composable
private fun WarningCard(message: String) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun AssistCard(message: String) {
    ElevatedCard { Text(message, modifier = Modifier.padding(16.dp)) }
}

/** =========== Construcción del NDEF =========== */

// URI NDEF (tel: o https:)
private fun buildMessage(type: PayloadType, rawInput: String): NdefMessage {
    val records = mutableListOf<NdefRecord>()
    when (type) {
        PayloadType.CALL -> {
            val tel = normalizePhone(rawInput)
            records += NdefRecord.createUri("tel:$tel")
        }
        PayloadType.URL -> {
            val url = ensureUrlScheme(rawInput)
            records += NdefRecord.createUri(url)
        }
    }
    // (Opcional) AAR de tu app
    records += NdefRecord.createApplicationRecord("com.example.contacto")
    return NdefMessage(records.toTypedArray())
}

/** Validaciones rápidas */
private fun validateInput(type: PayloadType, input: String): String? {
    return when (type) {
        PayloadType.CALL -> {
            val tel = normalizePhone(input)
            if (tel.isEmpty()) "Introduce un número válido (ej.: +34911222333)" else null
        }
        PayloadType.URL -> {
            val url = ensureUrlScheme(input)
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                "Introduce una URL válida (ej.: https://miweb.com)"
            else null
        }
    }
}

/** Quita espacios y guiones; permite prefijo + */
private fun normalizePhone(input: String): String {
    val cleaned = input.trim().replace("[\\s-]".toRegex(), "")
    return if (cleaned.matches(Regex("^\\+?[0-9]{5,}$"))) cleaned else ""
}

/** Si el usuario no puso esquema, forzamos https */
private fun ensureUrlScheme(input: String): String {
    val t = input.trim()
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}

/** Escritura al tag */
private fun writeNdefToTag(tag: Tag, message: NdefMessage): Boolean {
    return try {
        (Ndef.get(tag))?.let { ndef ->
            ndef.connect()
            return if (ndef.isWritable) {
                if (message.toByteArray().size > ndef.maxSize) { ndef.close(); false }
                else { ndef.writeNdefMessage(message); ndef.close(); true }
            } else { ndef.close(); false }
        }

        (NdefFormatable.get(tag))?.let { formatable ->
            formatable.connect()
            formatable.format(message)
            formatable.close()
            return true
        }

        false
    } catch (_: Exception) {
        false
    }
}
