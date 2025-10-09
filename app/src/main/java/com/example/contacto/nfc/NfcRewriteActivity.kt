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
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.contacto.R

// Icons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

class NfcRewriteActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    // Handler que la UI registrará para recibir el resultado detallado
    private var onWriteResult: ((WriteResult) -> Unit)? = null

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        // ========= Diagnóstico =========
        /*
        val idHex = tag.id.joinToString("") { "%02X".format(it) }
        val techsCsv = tag.techList.joinToString()
        val ndef = Ndef.get(tag)
        val fmt = NdefFormatable.get(tag)
        val diagHeader = buildString {
            append("UID=$idHex  techs=$techsCsv")
            append(
                if (ndef != null) " | NDEF OK (max=${ndef.maxSize}B)"
                else if (fmt != null) " | NdefFormatable OK"
                else " | No NDEF/Formatable"
            )
        }
        */

        // ========= Flujo mínimo: escribir si hay mensaje pendiente =========
        val msg = pendingMessage
        val result: WriteResult = if (msg != null) {
            writeNdefToTagWithReason(tag, msg)
        } else {
            WriteResult(false, "No hay mensaje preparado.")
        }

        // (Opcional) Si activaste el diagnóstico ampliado, añade el header:
        // val merged = result.copy(reason = listOf(diagHeader, result.reason).filterNotNull().joinToString(" | "))
        // onWriteResult?.invoke(merged)

        // Diagnóstico mínimo: solo confirma éxito/fracaso
        onWriteResult?.invoke(result)
    }


    private var pendingMessage: NdefMessage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            ConTactoTheme {
                NfcRewriteScreen(
                    onBack = { finish() },
                    nfcAvailable = nfcAdapter != null,
                    // La UI me dice qué mensaje escribir y yo activo el ReaderMode
                    onStartListening = { message, _ /* ignoramos el boolean callback */ ->
                        pendingMessage = message
                        enableReaderMode()
                    },
                    onStopListening = {
                        disableReaderMode()
                        pendingMessage = null
                    },
                    // La UI registra aquí su handler para recibir el resultado
                    registerResultHandler = { handler ->
                        onWriteResult = handler
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
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
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
    onStopListening: () -> Unit,
    // NUEVO: en vez de onWriteResult (callback directo), registramos el handler
    registerResultHandler: ((WriteResult) -> Unit) -> Unit
){
    val context = LocalContext.current

    var type by remember { mutableStateOf(PayloadType.CALL) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var waiting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastReason by remember { mutableStateOf<String?>(null) }

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

    // Tamaño estimado (ayuda para ver si cabe en el tag)
    val estimatedBytes = remember(messageToWrite) { messageToWrite.toByteArray().size }

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
                if (!nfcAvailable) WarningCard()

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
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Tamaño del mensaje: $estimatedBytes B (límite aprox. 106 B)",
                    style = MaterialTheme.typography.bodySmall
                )

                // Botón extra: Elegir de contactos (solo en modo Llamar)
                if (type == PayloadType.CALL) {
                    OutlinedButton(onClick = {
                        if (!waiting) pickFromContacts()
                    }) {
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
                            if (validation != null) { error = validation; return@Button }

                            status = "Acerca una etiqueta para escribir…"
                            lastReason = null
                            waiting = true

                            // 1) Registramos el handler que la Activity invocará al escribir
                            registerResultHandler { result ->
                                waiting = false
                                status = if (result.success) "✅ ¡Escritura completada!" else "❌ No se pudo escribir."
                                lastReason = result.reason
                            }

                            // 2) Activamos el modo escucha/escritura
                            onStartListening(messageToWrite) { /* ignorado */ }
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

                lastReason?.let {
                    Text("Detalle: $it", style = MaterialTheme.typography.bodySmall)
                }

                if (waiting) AssistCard()
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
private fun WarningCard(
    @StringRes messageRes: Int = R.string.nfc_not_available
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = stringResource(messageRes),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun AssistCard(
    @StringRes messageRes: Int = R.string.assist_default
) {
    ElevatedCard {
        Text(
            text = stringResource(messageRes),
            modifier = Modifier.padding(16.dp)
        )
    }
}

/** =========== Construcción del NDEF =========== */

/** =========== Construcción del NDEF =========== */
private fun buildMessage(type: PayloadType, rawInput: String): NdefMessage {
    val record: NdefRecord = when (type) {
        PayloadType.CALL -> {
            val tel = normalizePhone(rawInput)
            NdefRecord.createUri("tel:$tel")
        }
        PayloadType.URL -> {
            val url = ensureUrlScheme(rawInput)
            NdefRecord.createUri(url)
        }
    }
    // Un único record para minimizar tamaño (no AAR, ni texto extra)
    return NdefMessage(arrayOf(record))
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

private fun normalizePhone(input: String): String {
    val cleaned = input.trim().replace("[\\s-]".toRegex(), "")
    return if (cleaned.matches(Regex("^\\+?[0-9]{5,}$"))) cleaned else ""
}

private fun ensureUrlScheme(input: String): String {
    val t = input.trim()
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}

/** Escritura al tag con motivo */
data class WriteResult(
    val success: Boolean,
    val reason: String? = null
)

private fun writeNdefToTagWithReason(tag: Tag, message: NdefMessage): WriteResult {
    return try {
        Ndef.get(tag)?.let { ndef ->
            ndef.connect()
            if (!ndef.isWritable) {
                ndef.close()
                return WriteResult(false, "El tag está bloqueado (no es escribible).")
            }
            val size = message.toByteArray().size
            val max = ndef.maxSize
            if (size > max) {
                ndef.close()
                return WriteResult(false, "Mensaje demasiado grande (${size}B > ${max}B). Prueba sin AAR o usa un tag con más memoria.")
            }
            ndef.writeNdefMessage(message)
            ndef.close()
            return WriteResult(true)
        }

        NdefFormatable.get(tag)?.let { fmt ->
            fmt.connect()
            fmt.format(message)
            fmt.close()
            return WriteResult(true)
        }

        WriteResult(false, "Tipo de etiqueta no compatible (no NDEF / no formateable).")
    } catch (e: Exception) {
        WriteResult(false, "Excepción al escribir: ${e.message ?: e::class.java.simpleName}")
    }
}
