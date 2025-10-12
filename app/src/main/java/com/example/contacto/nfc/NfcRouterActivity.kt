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
import com.example.contacto.web.BankGuideActivity

class NfcRouterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handleIntent(intent)
        } finally {
            finish() // Esta activity no muestra UI
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
        // 1) Primero intent.data (a veces viene ya la URI en ACTION_NDEF_DISCOVERED/VIEW)
        val dataUri = intent.data
        if (dataUri != null) {
            routeByUrl(dataUri.toString())
            return
        }

        // 2) Si no hay data, intentamos leer el Tag
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
        when {
            isSescamUrl(url) -> {
                // Lanza guía SESCAM
                startActivity(
                    Intent(this, SescamGuideActivity::class.java)
                        .putExtra(SescamGuideActivity.EXTRA_START_URL, url) // usa EXTRA propio si lo tienes
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            isRuralviaUrl(url) -> {
                // Lanza guía Banco (Ruralvía particulares /#/login)
                startActivity(
                    Intent(this, BankGuideActivity::class.java)
                        .putExtra(BankGuideActivity.EXTRA_START_URL, url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            else -> {
                // No coincide: abre en navegador
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
    } catch (_: Throwable) { false }

    private fun isRuralviaUrl(url: String): Boolean = try {
        val u = Uri.parse(url)
        val host = (u.host ?: "").lowercase()
        val path = (u.path ?: "").lowercase()
        val frag = (u.fragment ?: "").lowercase()
        // Coincide con https://bancadigital.ruralvia.com/CA-FRONT/NBE/web/particulares/#/login
        host == "bancadigital.ruralvia.com" &&
                path.startsWith("/ca-front/nbe/web/particulares") &&
                frag.contains("/login")
    } catch (_: Throwable) { false }
}
