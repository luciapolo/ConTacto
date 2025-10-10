package com.example.contacto.nfc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.example.contacto.web.SescamGuideActivity

class NfcRouterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            handleIntent(intent)
        } finally {
            // Esta activity no muestra UI
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            handleIntent(intent)
        } finally {
            finish()
        }
    }

    private fun handleIntent(intent: Intent) {
        // 1) Primero intent.data (muchas veces viene ya la URI en ACTION_NDEF_DISCOVERED/VIEW)
        val dataUri = intent.data
        if (dataUri != null) {
            routeByUrl(dataUri.toString())
            return
        }

        // 2) Si no hay data, intentamos leer el Tag (por si el sistema no puso la URI)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        val url = tag?.let { readUrlFromTag(it) }
        if (!url.isNullOrBlank()) {
            routeByUrl(url)
            return
        }

        // 3) Nada legible
        Toast.makeText(this, "Etiqueta NFC sin URL válida.", Toast.LENGTH_SHORT).show()
    }

    private fun routeByUrl(url: String) {
        if (isSescamUrl(url)) {
            // Lanza la guía interna con la URL del SESCAM
            startActivity(
                Intent(this, SescamGuideActivity::class.java)
                    .putExtra("url", url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            // Si no es SESCAM, abre en navegador (comportamiento por omisión)
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No se pudo abrir el navegador.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** -------- Utils -------- */

    private fun readUrlFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        ndef.connect()
        return try {
            val msg: NdefMessage = ndef.cachedNdefMessage ?: ndef.ndefMessage ?: return null
            msg.records.firstNotNullOfOrNull { rec ->
                rec.toUri()?.toString()
                    ?: when {
                        rec.tnf == NdefRecord.TNF_ABSOLUTE_URI ->
                            String(rec.payload, Charsets.UTF_8)
                        else -> null
                    }
            }
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun isSescamUrl(url: String): Boolean = try {
        val u = Uri.parse(url)
        val host = (u.host ?: "").lowercase()
        val path = (u.path ?: "").lowercase()
        host.contains("sescam.jccm.es") ||
                host.contains("sescam.castillalamancha.es") ||
                path.contains("/misaluddigital")
    } catch (_: Throwable) {
        false
    }
}
