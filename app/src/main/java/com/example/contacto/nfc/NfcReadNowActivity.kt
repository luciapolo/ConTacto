package com.example.contacto.nfc

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.nio.charset.Charset
import java.util.Locale

class NfcReadNowActivity : AppCompatActivity() {

    companion object {

        const val EXTRA_START_URL = "com.example.contacto.nfc.EXTRA_START_URL"

        private const val TAG_LOG = "NfcReadNow"
    }

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    // (opcional) URL de inicio que te envían al abrir la Activity
    private var startUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si tienes un layout, descomenta:
        // setContentView(R.layout.activity_nfc_read_now)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            toast("Este dispositivo no soporta NFC")
            finish()
            return
        }
        if (nfcAdapter?.isEnabled != true) {
            toast("NFC desactivado. Actívalo en Ajustes.")
            // Opcional: abrir ajustes NFC
            try {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            } catch (_: Exception) {
            }
        }

        // Lee la URL de inicio si te la pasan
        startUrl = intent.getStringExtra(EXTRA_START_URL)

        // Configura Foreground Dispatch para recibir tags cuando esta Activity está al frente
        pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            PendingIntent.getActivity(this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        } else {
            @Suppress("DEPRECATION")
            PendingIntent.getActivity(this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE)
        }

        // Si nos lanzaron ya con un tag (por ejemplo desde intent-filter)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        try {
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
        } catch (e: Exception) {
            Log.w(TAG_LOG, "No se pudo activar ForegroundDispatch: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        // Guarda/actualiza el valor en caso de relanzos con extras
        startUrl = intent.getStringExtra(EXTRA_START_URL) ?: startUrl

        if (action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED
        ) {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag == null) {
                toast("No se pudo leer el tag NFC")
                return
            }
            readNdefFromTag(tag)
        }
    }

    private fun readNdefFromTag(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                val message: NdefMessage? = ndef.cachedNdefMessage ?: ndef.ndefMessage
                ndef.close()
                if (message == null) {
                    toast("Tag sin mensajes NDEF")
                    return
                }
                onNdefMessage(message)
            } else {
                toast("Tag NDEF no soportado o vacío")
            }
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error leyendo NDEF: ${e.message}", e)
            toast("Error leyendo el tag")
        }
    }

    private fun onNdefMessage(message: NdefMessage) {
        // Procesa todos los records
        val parts = mutableListOf<String>()
        for (record in message.records) {
            when {
                record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                    readTextRecord(record)?.let { parts.add(it) }
                }
                record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI) -> {
                    readUriRecord(record)?.let { parts.add(it.toString()) }
                }
                record.tnf == NdefRecord.TNF_ABSOLUTE_URI -> {
                    readAbsoluteUri(record)?.let { parts.add(it.toString()) }
                }
                else -> {
                    // otros tipos; podemos ignorar o loguear
                    Log.d(TAG_LOG, "Record no manejado: tnf=${record.tnf} type=${String(record.type)}")
                }
            }
        }

        if (parts.isEmpty()) {
            toast("No se encontró texto/URI en el tag")
            return
        }

        // Heurística simple: si hay una URL válida, la usamos; si no, mostramos el texto.
        val firstUrl = parts.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (firstUrl != null) {
            // Si nos pasaron una startUrl y no coincide, decide qué hacer (abrir leída, respetar startUrl, etc.)
            val toOpen = firstUrl
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(toOpen)))
            } catch (e: Exception) {
                Log.w(TAG_LOG, "No se pudo abrir la URL: $toOpen")
                toast("Leído: $toOpen")
            }
        } else {
            // Muestra el contenido textual
            toast("Leído: ${parts.joinToString(" | ")}")
        }
    }

    private fun readTextRecord(record: NdefRecord): String? {
        return try {
            val payload = record.payload ?: return null
            // Formato NDEF texto: status byte + lang code + texto
            val status = payload[0].toInt()
            val isUtf8 = (status and 0x80) == 0
            val langLength = status and 0x3F
            val textEncoding = if (isUtf8) Charset.forName("UTF-8") else Charset.forName("UTF-16")
            val text = String(payload, 1 + langLength, payload.size - 1 - langLength, textEncoding)
            text
        } catch (e: Exception) {
            Log.w(TAG_LOG, "Error leyendo text record: ${e.message}")
            null
        }
    }

    private fun readUriRecord(record: NdefRecord): Uri? {
        return try {
            val payload = record.payload ?: return null
            // NDEF URI: primer byte es el código de prefijo
            val prefix = uriPrefix(payload[0].toInt())
            val uri = String(payload, 1, payload.size - 1, Charsets.UTF_8)
            Uri.parse(prefix + uri)
        } catch (e: Exception) {
            Log.w(TAG_LOG, "Error leyendo URI record: ${e.message}")
            null
        }
    }

    private fun readAbsoluteUri(record: NdefRecord): Uri? {
        return try {
            Uri.parse(String(record.payload ?: return null, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun uriPrefix(code: Int): String {
        // Tabla de prefijos según especificación NDEF (RTD_URI)
        return when (code) {
            0x00 -> ""
            0x01 -> "http://www."
            0x02 -> "https://www."
            0x03 -> "http://"
            0x04 -> "https://"
            0x05 -> "tel:"
            0x06 -> "mailto:"
            0x07 -> "ftp://anonymous:anonymous@"
            0x08 -> "ftp://ftp."
            0x09 -> "ftps://"
            0x0A -> "sftp://"
            0x0B -> "smb://"
            0x0C -> "nfs://"
            0x0D -> "ftp://"
            0x0E -> "dav://"
            0x0F -> "news:"
            0x10 -> "telnet://"
            0x11 -> "imap:"
            0x12 -> "rtsp://"
            0x13 -> "urn:"
            0x14 -> "pop:"
            0x15 -> "sip:"
            0x16 -> "sips:"
            0x17 -> "tftp:"
            0x18 -> "btspp://"
            0x19 -> "btl2cap://"
            0x1A -> "btgoep://"
            0x1B -> "tcpobex://"
            0x1C -> "irdaobex://"
            0x1D -> "file://"
            0x1E -> "urn:epc:id:"
            0x1F -> "urn:epc:tag:"
            0x20 -> "urn:epc:pat:"
            0x21 -> "urn:epc:raw:"
            0x22 -> "urn:epc:"
            0x23 -> "urn:nfc:"
            else -> ""
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
