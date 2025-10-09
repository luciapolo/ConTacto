package com.example.contacto.nfc

import android.app.Activity
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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class NfcReader: ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null

    // Estado sencillo para reflejar en la UI el último resultado
    private val lastReadState = mutableStateOf("Aún no se ha leído ninguna etiqueta")

    // Flag para activar logs y toasts de diagnóstico cuando lo necesites
    private val DIAGNOSTIC = false // <-- pon true cuando quieras depurar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            if (DIAGNOSTIC) Log.w("NFC", "El dispositivo no tiene NFC")
            // Podrías mostrar un mensaje en UI
            lastReadState.value = "Este dispositivo no dispone de NFC"
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text = "Lectura NFC continua (primer plano)", style = MaterialTheme.typography.titleMedium)
                        Text(text = lastReadState.value, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { /* Botón de ayuda temporal, no hace nada crítico */ },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Text("Ayuda")
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            if (DIAGNOSTIC) Log.w("NFC", "NFC del sistema desactivado")
            lastReadState.value = "Activa el NFC en ajustes para leer etiquetas"
            return
        }

        // Flags comunes; añade/quita según tus necesidades
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        // Opcional: pasar un Bundle con extras (por ejemplo, desactivar sonido del sistema)
        val extras = Bundle().apply {
            // putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) // ejemplo
        }

        adapter.enableReaderMode(
            /* activity = */ this,
            /* callback = */ this,
            /* flags = */ flags,
            /* extras = */ extras
        )

        if (DIAGNOSTIC) Log.d("NFC", "ReaderMode habilitado")
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
        if (DIAGNOSTIC) Log.d("NFC", "ReaderMode deshabilitado")
    }

    // Callback de lectura: se dispara cada vez que se descubre una etiqueta mientras la actividad está en primer plano
    override fun onTagDiscovered(tag: Tag?) {
        if (DIAGNOSTIC) Log.d("NFC", "Tag descubierta: ${tag?.id?.joinToString { b -> "%02X".format(b) }}")

        // Intento de lectura NDEF
        val result = readNdefPayload(tag)
        runOnUiThread {
            if (result != null) {
                lastReadState.value = "Leído: $result"
                vibrateOk()
            } else {
                lastReadState.value = "Etiqueta detectada (no NDEF o sin payload)"
                vibrateOk()
            }
        }
    }

    private fun readNdefPayload(tag: Tag?): String? {
        if (tag == null) return null

        val ndef = Ndef.get(tag) ?: run {
            if (DIAGNOSTIC) Log.i("NFC", "La etiqueta no está formateada como NDEF")
            return null
        }

        return try {
            ndef.connect()
            val msg: NdefMessage? = ndef.cachedNdefMessage ?: ndef.ndefMessage
            if (msg == null) {
                if (DIAGNOSTIC) Log.i("NFC", "Sin NDEFMessage")
                null
            } else {
                // Intenta extraer un texto legible (por ejemplo, del primer Text Record)
                extractTextFromNdef(msg)
                    ?: msg.toByteArray().joinToString(separator = " ") { b -> "%02X".format(b) }
            }
        } catch (e: Exception) {
            if (DIAGNOSTIC) Log.e("NFC", "Error leyendo NDEF", e)
            null
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    private fun extractTextFromNdef(message: NdefMessage): String? {
        for (record in message.records) {
            if (isTextRecord(record)) {
                return decodeTextRecord(record)
            }
            // Si quieres, añade aquí lectura de URI:
            // if (isUriRecord(record)) return decodeUriRecord(record)
        }
        return null
    }

    private fun isTextRecord(record: NdefRecord): Boolean {
        return record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type?.contentEquals(NdefRecord.RTD_TEXT) == true
    }

    private fun decodeTextRecord(record: NdefRecord): String? {
        return try {
            val payload = record.payload
            // Formato RTD_TEXT:
            // payload[0] = status byte: bit7 = 0 UTF-8 / 1 UTF-16, bits0-5 = length del código de idioma
            val status = payload[0].toInt()
            val isUtf16 = (status and 0x80) != 0
            val langLen = status and 0x3F
            val textBytes = payload.copyOfRange(1 + langLen, payload.size)
            val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
            String(textBytes, charset)
        } catch (e: Exception) {
            if (DIAGNOSTIC) Log.e("NFC", "Error decodificando Text Record", e)
            null
        }
    }

    private fun vibrateOk() {
        // Vibración corta para confirmar lectura
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vm.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            if (DIAGNOSTIC) Log.w("NFC", "No se pudo vibrar: ${e.message}")
        }
    }
}
