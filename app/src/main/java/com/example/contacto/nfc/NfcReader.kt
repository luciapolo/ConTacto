package com.example.contacto.nfc

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class NfcReader : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private var lastReadState by mutableStateOf("Acerca una etiqueta NFC…")
    private val diagnostic = false

    // Guardamos un tel: pendiente mientras pedimos permiso
    private var pendingTelUri: Uri? = null

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingTelUri?.let { telUri ->
            if (granted) {
                startActivity(Intent(Intent.ACTION_CALL, telUri))
            } else {
                // Sin permiso, al menos abrimos el marcador
                startActivity(Intent(Intent.ACTION_DIAL, telUri))
            }
            pendingTelUri = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            if (diagnostic) Log.w("NFC", "El dispositivo no tiene NFC")
            lastReadState = "Este dispositivo no dispone de NFC"
        }
        processNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processNfcIntent(intent)
    }

    private fun processNfcIntent(intent: Intent) {
        // 1) Si el intent trae directamente una URI (p.ej., tel:...), úsala
        intent.data?.let { data ->
            if (data.scheme.equals("tel", ignoreCase = true)) {
                lastReadState = "Detectado teléfono: ${data.schemeSpecificPart}"
                makePhoneCall(data)
                vibrateOk()
                return
            }
        }

        // 2) Si no trae data, extrae NDEF y busca URI/texto
        val msgs = getNdefMessages(intent)
        if (msgs.isNotEmpty()) {
            val uri = extractUri(msgs[0])          // tu helper para RTD_URI / ABSOLUTE
            val text = extractText(msgs[0])        // tu helper para RTD_TEXT

            when {
                uri != null && uri.scheme.equals("tel", true) -> {
                    lastReadState = "Detectado teléfono: ${uri.schemeSpecificPart}"
                    makePhoneCall(uri)
                }
                text != null && text.trim().lowercase().startsWith("tel:") -> {
                    val telUri = android.net.Uri.parse(text.trim())
                    lastReadState = "Detectado teléfono: ${telUri.schemeSpecificPart}"
                    makePhoneCall(telUri)
                }
                uri != null -> lastReadState = "Leído URI: $uri"
                text != null -> lastReadState = "Leído texto: $text"
                else -> lastReadState = "Etiqueta detectada (NDEF sin datos útiles)"
            }
            vibrateOk()
        } else {
            lastReadState = "Etiqueta detectada (no NDEF)"
        }
    }

    // Compat para EXTRA_NDEF_MESSAGES (API 33+ y anteriores)
    private fun getNdefMessages(intent: Intent): Array<NdefMessage> {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(
                NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java
            ) ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                    as? Array<Parcelable>)?.mapNotNull { it as? NdefMessage }?.toTypedArray()
                ?: emptyArray()
        }
    }

    override fun onResume() {
        super.onResume()
        // (Opcional) Reader Mode. Si estás usando Foreground Dispatch en MainActivity, puedes omitirlo.
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, // silencia beep del sistema
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // ReaderCallback (si tienes Reader Mode activo)
    override fun onTagDiscovered(tag: Tag?) {
        tag?.let { handleTag(it) }
    }

    // --------- LÓGICA ----------
    private fun handleTag(tag: Tag) {
        val (text, uri) = readNdef(tag)

        // 1) Si trae URI tel:, llamamos
        if (uri != null && uri.scheme.equals("tel", ignoreCase = true)) {
            lastReadState = "Detectado teléfono: ${uri.schemeSpecificPart}"
            makePhoneCall(uri)
            vibrateOk()
            return
        }

        // 2) Si trae texto que empieza por tel:, también llamamos
        if (text != null && text.trim().lowercase().startsWith("tel:")) {
            val telUri = Uri.parse(text.trim())
            lastReadState = "Detectado teléfono: ${telUri.schemeSpecificPart}"
            makePhoneCall(telUri)
            vibrateOk()
            return
        }

        // 3) Otra cosa → lo mostramos
        lastReadState = when {
            uri != null -> "Leído URI: $uri"
            text != null -> "Leído texto: $text"
            else -> "Etiqueta detectada (no NDEF o sin payload)"
        }
        vibrateOk()
    }

    private fun makePhoneCall(telUri: Uri) {
        // Si ya tenemos permiso, llamamos directo
        val hasPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasPerm) {
            startActivity(Intent(Intent.ACTION_CALL, telUri))
        } else {
            // Pedimos permiso; si lo deniegan, abrimos marcador
            pendingTelUri = telUri
            callPermLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private data class NdefData(val text: String?, val uri: Uri?)

    private fun readNdef(tag: Tag): NdefData {
        val ndef = Ndef.get(tag) ?: return NdefData(null, null)
        return try {
            ndef.connect()
            val msg: NdefMessage? = ndef.cachedNdefMessage ?: ndef.ndefMessage
            if (msg == null) NdefData(null, null)
            else NdefData(extractText(msg), extractUri(msg))
        } catch (_: Exception) {
            NdefData(null, null)
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    // --- EXTRAER TEXTO (RTD_TEXT) ---
    private fun extractText(message: NdefMessage): String? {
        for (r in message.records) {
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN &&
                r.type?.contentEquals(NdefRecord.RTD_TEXT) == true) {
                try {
                    val p = r.payload
                    val status = p[0].toInt()
                    val isUtf16 = (status and 0x80) != 0
                    val langLen = status and 0x3F
                    val bytes = p.copyOfRange(1 + langLen, p.size)
                    return String(bytes, if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8)
                } catch (_: Exception) { /* ignore */ }
            }
        }
        return null
    }

    // --- EXTRAER URI (RTD_URI o Absolute URI) ---
    private fun extractUri(message: NdefMessage): Uri? {
        for (r in message.records) {
            when {
                // Well-known URI (TNF_WELL_KNOWN + RTD_URI con prefijos)
                r.tnf == NdefRecord.TNF_WELL_KNOWN &&
                        r.type?.contentEquals(NdefRecord.RTD_URI) == true -> {
                    decodeWellKnownUri(r)?.let { return it }
                }
                // Absolute URI
                r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> {
                    return try { Uri.parse(String(r.payload, Charsets.UTF_8)) } catch (_: Exception) { null }
                }
            }
        }
        return null
    }

    private fun decodeWellKnownUri(record: NdefRecord): Uri? = try {
        val payload = record.payload
        val prefixCode = payload[0].toInt() and 0xFF
        val uriBody = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        val prefix = NDEF_URI_PREFIXES.getOrElse(prefixCode) { "" }
        Uri.parse(prefix + uriBody)
    } catch (_: Exception) { null }

    private val NDEF_URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://",
        "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
        "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:",
        "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:",
        "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
        "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:"
    )

    private fun vibrateOk() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(50)
            }
        } catch (_: Exception) {}
    }


}
