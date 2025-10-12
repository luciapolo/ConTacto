package com.example.contacto.nfc

import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.graphics.graphicsLayer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.contacto.web.SescamGuideActivity
import com.example.contacto.web.BankGuideActivity

class NfcReadNowActivity : ComponentActivity() {

    // Dominios válidos del SESCAM
    private val SESCAM_HOSTS = setOf("sescam.jccm.es", "www.sescam.jccm.es")

    // Guardamos un tel: pendiente mientras pedimos permiso
    private var pendingTelUri: Uri? = null


    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingTelUri?.let { tel ->
            if (granted) startActivity(Intent(Intent.ACTION_CALL, tel))
            else startActivity(Intent(Intent.ACTION_DIAL, tel)) // fallback sin permiso
            pendingTelUri = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ReadNowScreen(this, ::onActionTel, ::onActionLink) } }
    }

    /** Acción de llamada */
    private fun onActionTel(tel: Uri) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startActivity(Intent(Intent.ACTION_CALL, tel))
        } else {
            pendingTelUri = tel
            callPermLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    /** Acción abrir enlace o lanzar guía si es SESCAM */
    private fun onActionLink(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val host = (uri.host ?: "").lowercase()
            val path = (uri.path ?: "").lowercase()
            val frag = (uri.fragment ?: "").lowercase()

            val looksLikeRuralvia =
                host == "bancadigital.ruralvia.com" &&
                        path.startsWith("/ca-front/nbe/web/particulares") &&
                        (frag?.contains("/login") == true)

            val looksLikeSescam =
                host.contains("sescam.jccm.es") ||
                        host.contains("sescam.castillalamancha.es") ||
                        path.contains("/misaluddigital")

            when {
                looksLikeRuralvia -> {
                    startActivity(
                        Intent(this, com.example.contacto.web.BankGuideActivity::class.java)
                            .putExtra(com.example.contacto.web.BankGuideActivity.EXTRA_START_URL, uri.toString())
                    )
                    return
                }
                looksLikeSescam -> {
                    startActivity(
                        Intent(this, com.example.contacto.web.SescamGuideActivity::class.java)
                            .putExtra(com.example.contacto.web.SescamGuideActivity.EXTRA_START_URL, uri.toString())
                    )
                    return
                }
                else -> {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                }
            }
        }
    }
}

@Composable
private fun ReadNowScreen(
    activity: Activity,
    onTel: (Uri) -> Unit,
    onLink: (Uri) -> Unit
) {
    // ---- UI state ----
    var status by remember { mutableStateOf("Acerca una etiqueta…") }
    var statusType by remember { mutableStateOf(StatusType.Idle) }

    // Animación de pulso para el icono NFC
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val glowAlpha by pulse.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // ---- NFC: Reader Mode mientras esta pantalla está en primer plano ----
    DisposableEffect(Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        val callback = NfcAdapter.ReaderCallback { tag ->
            val ndef = Ndef.get(tag)
            val message: NdefMessage? = try {
                ndef?.connect()
                ndef?.cachedNdefMessage ?: ndef?.ndefMessage
            } catch (_: Exception) { null } finally {
                try { ndef?.close() } catch (_: Exception) {}
            }

            val (text, uri) = extractTextAndUri(message)
            val scheme = uri?.scheme?.lowercase()

            when {
                scheme == "tel" -> {
                    val u = uri
                    activity.runOnUiThread {
                        status = "Detectado teléfono: ${u!!.schemeSpecificPart}"
                        statusType = StatusType.Tel
                    }
                    onTel(u!!)
                }
                scheme == "http" || scheme == "https" -> {
                    val u = uri
                    activity.runOnUiThread {
                        status = "Abriendo enlace…"
                        statusType = StatusType.Link
                    }
                    onLink(u!!)
                }
                text?.trim()?.startsWith("tel:", ignoreCase = true) == true -> {
                    val tel = text.trim().toUri()
                    activity.runOnUiThread {
                        status = "Detectado teléfono: ${tel.schemeSpecificPart}"
                        statusType = StatusType.Tel
                    }
                    onTel(tel)
                }
                text?.trim()?.startsWith("http", ignoreCase = true) == true -> {
                    val link = text.trim().toUri()
                    activity.runOnUiThread {
                        status = "Abriendo enlace…"
                        statusType = StatusType.Link
                    }
                    onLink(link)
                }
                else -> {
                    activity.runOnUiThread {
                        status = if (message != null) "Etiqueta NDEF sin datos útiles"
                        else "Etiqueta detectada (no NDEF)"
                        statusType = StatusType.Neutral
                    }
                }
            }
        }

        adapter?.enableReaderMode(
            activity,
            callback,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null
        )
        onDispose { adapter?.disableReaderMode(activity) }
    }

    // ---- UI ----
    Surface(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    // Glow + icono
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(160.dp * scale)
                                .graphicsLayer { alpha = glowAlpha }
                                .background(
                                    color = when (statusType) {
                                        StatusType.Tel -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                                        StatusType.Link -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                        StatusType.Neutral, StatusType.Idle -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f)
                                    },
                                    shape = CircleShape
                                )
                        )
                        Icon(
                            imageVector = Icons.Outlined.Contactless,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp),
                            tint = when (statusType) {
                                StatusType.Tel -> MaterialTheme.colorScheme.tertiary
                                StatusType.Link -> MaterialTheme.colorScheme.primary
                                StatusType.Neutral, StatusType.Idle -> MaterialTheme.colorScheme.secondary
                            }
                        )
                    }

                    Text(
                        text = "Lectura dentro de la app",
                        style = MaterialTheme.typography.titleMedium
                    )

                    // Chip de estado
                    AssistChip(
                        onClick = {},
                        label = { Text(status) },
                        leadingIcon = {
                            val icon = when (statusType) {
                                StatusType.Tel -> Icons.Default.Call
                                StatusType.Link -> Icons.AutoMirrored.Filled.OpenInNew
                                StatusType.Neutral, StatusType.Idle -> Icons.Outlined.Contactless
                            }
                            Icon(icon, contentDescription = null)
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            containerColor = when (statusType) {
                                StatusType.Tel -> MaterialTheme.colorScheme.tertiaryContainer
                                StatusType.Link -> MaterialTheme.colorScheme.primaryContainer
                                StatusType.Neutral, StatusType.Idle -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { activity.finish() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}

private enum class StatusType { Idle, Tel, Link, Neutral }

/* ----------------- Helpers NDEF ----------------- */

private data class NdefData(val text: String?, val uri: Uri?)

private fun extractTextAndUri(msg: NdefMessage?): NdefData {
    if (msg == null) return NdefData(null, null)
    var text: String? = null
    var uri: Uri? = null
    for (r in msg.records) {
        // Texto (RTD_TEXT)
        if (text == null &&
            r.tnf == NdefRecord.TNF_WELL_KNOWN &&
            r.type?.contentEquals(NdefRecord.RTD_TEXT) == true
        ) {
            try {
                val p = r.payload
                val status = p[0].toInt()
                val isUtf16 = (status and 0x80) != 0
                val langLen = status and 0x3F
                val bytes = p.copyOfRange(1 + langLen, p.size)
                text = String(bytes, if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8)
            } catch (_: Exception) {}
        }
        // URI (RTD_URI o Absolute)
        if (uri == null) {
            when {
                r.tnf == NdefRecord.TNF_WELL_KNOWN &&
                        r.type?.contentEquals(NdefRecord.RTD_URI) == true -> {
                    uri = decodeWellKnownUri(r)
                }
                r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> {
                    try { uri = (String(r.payload, Charsets.UTF_8).toUri()) } catch (_: Exception) {}
                }
            }
        }
        if (text != null && uri != null) break
    }
    return NdefData(text, uri)
}

private fun decodeWellKnownUri(record: NdefRecord): Uri? = try {
    val p = record.payload
    val prefixCode = p[0].toInt() and 0xFF
    val uriBody = String(p, 1, p.size - 1, Charsets.UTF_8)
    val prefix = prefixes.getOrElse(prefixCode) { "" }
    (prefix + uriBody).toUri()
} catch (_: Exception) { null }

private val prefixes = arrayOf(
    "", "http://www.", "https://www.", "http://", "https://",
    "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
    "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:",
    "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:",
    "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
    "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:",
    "urn:epc:raw:", "urn:epc:", "urn:nfc:"
)
