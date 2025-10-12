package com.example.contacto.nfc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.contacto.web.SescamGuideActivity
import com.example.contacto.web.BankGuideActivity

class NfcReaderActivity : ComponentActivity() {

    private var pendingTelUri: Uri? = null

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingTelUri?.let { tel ->
            if (granted) startActivity(Intent(Intent.ACTION_CALL, tel))
            else startActivity(Intent(Intent.ACTION_DIAL, tel))
            pendingTelUri = null
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processNfcIntent(intent)
    }

    private fun processNfcIntent(intent: Intent) {
        // 1) Si el sistema ya te pasa la URI (tel/http/https) en data:
        intent.data?.let { data ->
            when (data.scheme?.lowercase()) {
                "tel" -> { makeCall(data); return }
                "http", "https" -> { routeByUrl(data.toString()); return }
            }
        }

        // 2) Si no viene en data, leemos el NDEF y buscamos tel:/http(s)
        val msg = getFirstNdefMessage(intent) ?: run { finish(); return }
        val (text, uri) = extractTextAndUri(msg)

        when {
            uri != null && uri.scheme.equals("tel", true) -> makeCall(uri)
            uri != null && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) -> routeByUrl(uri.toString())
            text != null && text.startsWith("tel:", ignoreCase = true) -> makeCall(text.trim().toUri())
            text != null && (text.startsWith("http://", true) || text.startsWith("https://", true)) -> routeByUrl(text.trim())
            else -> finish()
        }
    }

    /** -------- Router de enlaces: asistente Banco/SESCAM o navegador -------- */
    private fun routeByUrl(url: String) {
        when {
            isRuralviaUrl(url) -> {
                startActivity(
                    Intent(this, BankGuideActivity::class.java)
                        .putExtra(BankGuideActivity.EXTRA_START_URL, url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
            isSescamUrl(url) -> {
                startActivity(
                    Intent(this, SescamGuideActivity::class.java)
                        .putExtra(SescamGuideActivity.EXTRA_START_URL, url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
            else -> {
                // No coincide: abrir en navegador (comportamiento anterior)
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                startActivity(view)
                finish()
            }
        }
    }

    // --- Matchers de URL ---
    private fun isRuralviaUrl(url: String): Boolean = try {
        val u = Uri.parse(url)
        val host = (u.host ?: "").lowercase()
        val path = (u.path ?: "").lowercase()
        val frag = (u.fragment ?: "").lowercase()
        // https://bancadigital.ruralvia.com/CA-FRONT/NBE/web/particulares/#/login
        host == "bancadigital.ruralvia.com" &&
                path.startsWith("/ca-front/nbe/web/particulares") &&
                frag.contains("/login")
    } catch (_: Throwable) { false }

    private fun isSescamUrl(url: String): Boolean = try {
        val u = Uri.parse(url)
        val host = (u.host ?: "").lowercase()
        val path = (u.path ?: "").lowercase()
        host.contains("sescam.jccm.es") ||
                host.contains("sescam.castillalamancha.es") ||
                path.contains("/misaluddigital")
    } catch (_: Throwable) { false }

    // --- Abrir enlace tel: con permisos ---
    private fun makeCall(tel: Uri) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startActivity(Intent(Intent.ACTION_CALL, tel))
            finish()
        } else {
            pendingTelUri = tel
            callPermLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    // Helpers NDEF
    private fun getFirstNdefMessage(intent: Intent): NdefMessage? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(
                NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java
            )?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                    as? Array<android.os.Parcelable>)?.firstOrNull() as? NdefMessage
        }
    }

    private data class NdefData(val text: String?, val uri: Uri?)
    private fun extractTextAndUri(msg: NdefMessage): NdefData {
        var text: String? = null
        var uri: Uri? = null
        for (r in msg.records) {
            // Text (RTD_TEXT)
            if (r.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                r.type?.contentEquals(android.nfc.NdefRecord.RTD_TEXT) == true && text == null) {
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
                    r.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                            r.type?.contentEquals(android.nfc.NdefRecord.RTD_URI) == true -> {
                        decodeWellKnownUri(r)?.let { uri = it }
                    }
                    r.tnf == android.nfc.NdefRecord.TNF_ABSOLUTE_URI -> {
                        try { uri = String(r.payload, Charsets.UTF_8).toUri() } catch (_: Exception) {}
                    }
                }
            }
            if (text != null && uri != null) break
        }
        return NdefData(text, uri)
    }

    private fun decodeWellKnownUri(record: android.nfc.NdefRecord): Uri? = try {
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
}
