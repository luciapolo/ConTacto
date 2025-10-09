package com.example.contacto.nfc

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.contacto.overlay.GuideOverlay

class NfcReader : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private var lastReadState by mutableStateOf("Aún no se ha leído ninguna etiqueta")
    private val DIAGNOSTIC = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            if (DIAGNOSTIC) Log.w("NFC", "El dispositivo no tiene NFC")
            lastReadState = "Este dispositivo no dispone de NFC"
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Lectura NFC continua (primer plano)", style = MaterialTheme.typography.titleMedium)
                        Text(lastReadState, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { /* ayuda */ },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = MaterialTheme.shapes.extraLarge
                        ) { Text("Ayuda") }
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); enableReaderMode() }
    override fun onPause()  { super.onPause();  disableReaderMode() }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            if (DIAGNOSTIC) Log.w("NFC", "NFC del sistema desactivado")
            lastReadState = "Activa el NFC en ajustes para leer etiquetas"
            return
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, this, flags, null)
        if (DIAGNOSTIC) Log.d("NFC", "ReaderMode habilitado")
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
        if (DIAGNOSTIC) Log.d("NFC", "ReaderMode deshabilitado")
    }

    override fun onTagDiscovered(tag: Tag?) {
        val result = readNdefPayload(tag)
        runOnUiThread {
            when {
                result == null -> {
                    lastReadState = "Etiqueta detectada (no NDEF o sin payload)"; vibrateOk()
                }
                result.startsWith("tel:", true) -> {
                    lastReadState = "Teléfono detectado: $result"; vibrateOk()
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(result)))
                }
                result.startsWith("http://", true) || result.startsWith("https://", true) -> {
                    lastReadState = "URL detectada: $result"; vibrateOk()
                    val host = runCatching { Uri.parse(result).host?.lowercase() ?: "" }.getOrDefault("")
                    if (host.contains("sescam")) {
                        // 1) Permiso de superposición si falta
                        if (!Settings.canDrawOverlays(this)) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        // 2) Arranca el overlay y 3) abre el navegador
                        GuideOverlay.start(this, "Abriendo SESCAM…")
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result)))
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result)))
                    }
                }
                else -> {
                    lastReadState = "Leído: $result"; vibrateOk()
                }
            }
        }
    }

    private fun readNdefPayload(tag: Tag?): String? {
        if (tag == null) return null
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val msg: NdefMessage? = ndef.cachedNdefMessage ?: ndef.ndefMessage
            if (msg == null) null
            else decodeFirstUri(msg) ?: extractTextFromNdef(msg)
            ?: msg.toByteArray().joinToString(" ") { "%02X".format(it) }
        } catch (e: Exception) {
            if (DIAGNOSTIC) Log.e("NFC", "Error leyendo NDEF", e); null
        } finally { runCatching { ndef.close() } }
    }

    private fun decodeFirstUri(message: NdefMessage): String? {
        for (r in message.records) {
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI)) {
                val u = r.toUri()?.toString()
                if (u != null && (u.startsWith("tel:", true) || u.startsWith("http://", true) || u.startsWith("https://", true)))
                    return u
            }
        }
        return null
    }

    private fun extractTextFromNdef(message: NdefMessage): String? {
        for (record in message.records) {
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                return decodeTextRecord(record)
            }
        }
        return null
    }

    private fun decodeTextRecord(record: NdefRecord): String? = try {
        val payload = record.payload
        val status = payload[0].toInt()
        val isUtf16 = (status and 0x80) != 0
        val langLen = status and 0x3F
        val textBytes = payload.copyOfRange(1 + langLen, payload.size)
        val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
        String(textBytes, charset)
    } catch (_: Exception) { null }

    private fun vibrateOk() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(50)
            }
        } catch (_: Exception) {}
    }
}
