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
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import com.example.contacto.ui.theme.ConTactoTheme
import com.example.contacto.R

class NfcRewriteActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    private var onWriteResult: ((WriteResult) -> Unit)? = null

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        val msg = pendingMessage
        val result: WriteResult = if (msg != null) {
            writeNdefToTagWithReason(tag, msg)
        } else {
            WriteResult(false, "No hay mensaje preparado.")
        }
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
                    onStartListening = { message, _ ->
                        pendingMessage = message
                        enableReaderMode()
                    },
                    onStopListening = {
                        disableReaderMode()
                        pendingMessage = null
                    },
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
    registerResultHandler: ((WriteResult) -> Unit) -> Unit
) {
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

    val messageToWrite by remember(type, input) {
        mutableStateOf(buildMessage(type, input.text))
    }

    val estimatedBytes = remember(messageToWrite) { messageToWrite.toByteArray().size }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Text("Reescribir NFC", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (nfcAvailable) "Prepara y escribe una etiqueta" else stringResource(R.string.nfc_not_available),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.alpha(0.85f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (waiting) onStopListening()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    StatChip(icon = Icons.Filled.SdCard, label = "${estimatedBytes} B")
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (!nfcAvailable) WarningCard()

            // Tarjeta principal
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Selector con iconos
                    PayloadSelector(
                        selected = type,
                        onSelect = { type = it; error = null; status = null }
                    )

                    Crossfade(targetState = type, label = "field") { current ->
                        when (current) {
                            PayloadType.CALL -> OutlinedTextField(
                                value = input,
                                onValueChange = { input = it; error = null },
                                leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                                label = { Text("Número a llamar (p.ej. +34911222333)") },
                                placeholder = { Text("+34…") },
                                isError = error != null,
                                supportingText = {
                                    AnimatedVisibility(visible = error != null) {
                                        Text(text = error ?: "", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            PayloadType.URL -> OutlinedTextField(
                                value = input,
                                onValueChange = { input = it; error = null },
                                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                                label = { Text("URL (p.ej. https://miweb.com)") },
                                placeholder = { Text("https://…") },
                                isError = error != null,
                                supportingText = {
                                    AnimatedVisibility(visible = error != null) {
                                        Text(text = error ?: "", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Acciones
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = nfcAvailable && !waiting,
                            onClick = {
                                val validation = validateInput(type, input.text)
                                if (validation != null) { error = validation; return@Button }

                                status = "Acerca una etiqueta para escribir…"
                                lastReason = null
                                waiting = true

                                registerResultHandler { result ->
                                    waiting = false
                                    status = if (result.success) "¡Escritura completada!" else "No se pudo escribir."
                                    lastReason = result.reason
                                }

                                onStartListening(messageToWrite) { }
                            }
                        ) { Text("Listo") }

                        TextButton(
                            enabled = waiting,
                            onClick = {
                                onStopListening()
                                waiting = false
                                status = "Escritura cancelada."
                            }
                        ) { Text("Cancelar") }
                    }

                    if (type == PayloadType.CALL) {
                        OutlinedButton(onClick = { if (!waiting) pickFromContacts() }) {
                            Icon(Icons.Filled.Person, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Elegir de contactos")
                        }
                    }

                    AnimatedVisibility(visible = waiting) {
                        StatusBanner(text = "Acerca la etiqueta al móvil")
                    }

                    lastReason?.let {
                        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                            Text("Detalle: $it")
                        }
                    }

                    status?.let { msg ->
                        val success = msg.startsWith("")
                        val icon = if (success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline
                        val tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        AssistChip(
                            onClick = { /* no-op */ },
                            label = { Text(msg.removePrefix("").removePrefix("")) },
                            leadingIcon = {
                                Icon(icon, contentDescription = null, tint = tint)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Pie con mini ayuda
            HelpStrip()

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** =========== Helpers UI =========== */

@Composable
private fun PayloadSelector(selected: PayloadType, onSelect: (PayloadType) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == PayloadType.CALL,
            onClick = { onSelect(PayloadType.CALL) },
            label = { Text("Llamar") },
            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) }
        )
        FilterChip(
            selected = selected == PayloadType.URL,
            onClick = { onSelect(PayloadType.URL) },
            label = { Text("URL") },
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) }
        )
    }
}

@Composable
private fun StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    AssistChip(
        onClick = { },
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) }
    )
}

@Composable
private fun StatusBanner(text: String) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                    )
                )
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
        ) {
            // Punto "parpadeante"
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .alpha(alpha)
            )
        }
        Text(text)
    }
}

@Composable
private fun WarningCard(
    @StringRes messageRes: Int = R.string.nfc_not_available
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(messageRes),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun HelpStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.SdCard, contentDescription = null)
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            Text("Consejo: mantén el móvil firme sobre la etiqueta durante 1–2 segundos.")
        }
    }
}

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
    return NdefMessage(arrayOf(record))
}

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
