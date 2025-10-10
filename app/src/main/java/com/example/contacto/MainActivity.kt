package com.example.contacto

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.contacto.nfc.NfcRewriteActivity
import com.example.contacto.web.SescamGuideActivity // <- agente en WebView (opcional)
import com.example.contacto.nfc.NfcReadNowActivity
import com.example.contacto.data.SettingsActivity
import com.example.contacto.nfc.NfcReadNowActivity
import com.example.contacto.nfc.NfcReaderActivity
import com.example.contacto.nfc.NfcRewriteActivity
import com.example.contacto.ui.screens.HomeScreen
import com.example.contacto.ui.theme.ConTactoTheme
import com.example.contacto.web.SescamGuideActivity

class MainActivity : ComponentActivity() {

    // ===== NFC foreground dispatch =====
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private lateinit var intentFiltersArray: Array<IntentFilter>
    private lateinit var techListsArray: Array<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializa NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Acepta NDEF / TECH / TAG para foreground dispatch
        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            addDataType("*/*")
        }
        val tech = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tag = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        intentFiltersArray = arrayOf(ndef, tech, tag)
        techListsArray = arrayOf(arrayOf(Ndef::class.java.name))

        // UI Compose (se mantienen las demás acciones; sin botón de “Guía SESCAM”)
        setContent {
            ConTactoTheme {
                HomeScreen(
                    userName = null,
                    onRewriteNfcClick = {
                        startActivity(Intent(this, NfcRewriteActivity::class.java))
                    },

                    onReadNowClick = {startActivity(Intent(this,NfcReadNowActivity::class.java))},
                    onOpenSettingsClick = {startActivity(Intent(this, SettingsActivity::class.java))}
                )
            }
        }

    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(
            this,
            pendingIntent,
            intentFiltersArray,
            techListsArray
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Si más adelante manejas intents (deep links / NFC a Main), hazlo aquí.
        // Si llega un tag mientras esta pantalla está en primer plano, lo procesamos o lo delegamos:
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        if (tag != null) {
            startActivity(
                Intent(this, com.example.contacto.nfc.NfcReaderActivity::class.java).apply {
                    putExtra(NfcAdapter.EXTRA_TAG, tag)
                }
            )
        }
    }

    // ========= Helpers =========

    /**
     * Lee la primera URL disponible en el NDEF del Tag.
     * Soporta TNF_WELL_KNOWN (RTD_URI), TNF_ABSOLUTE_URI y records con toUri().
     */
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

    /**
     * Determina si la URL pertenece al dominio del SESCAM
     * o a rutas conocidas de MiSaludDigital.
     */
    private fun isSescamUrl(url: String): Boolean {
        return try {
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

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No se pudo abrir el navegador.", Toast.LENGTH_SHORT).show()
        }
    }
}
