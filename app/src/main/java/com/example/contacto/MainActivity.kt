package com.example.contacto

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.contacto.nfc.NfcRewriteActivity
import com.example.contacto.nfc.NfcReaderActivity          // <- lector con overlay + navegador
import com.example.contacto.web.SescamGuideActivity // <- agente en WebView (opcional)
import com.example.contacto.nfc.NfcReadNowActivity
import com.example.contacto.data.SettingsActivity

import com.example.contacto.ui.screens.HomeScreen
import com.example.contacto.ui.theme.ConTactoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private lateinit var intentFiltersArray: Array<IntentFilter>
    private lateinit var techListsArray: Array<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            (if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT)
        )

        // Acepta NDEF / TECH / TAG
        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            addDataType("*/*")
        }
        val tech = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tag  = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        intentFiltersArray = arrayOf(ndef, tech, tag)

        techListsArray = arrayOf(arrayOf(Ndef::class.java.name))

        // UI
        setContent {
            ConTactoTheme {
                HomeScreen(
                    userName = null,
                    onRewriteNfcClick = {
                        startActivity(Intent(this, NfcRewriteActivity::class.java))
                    },
                    // NUEVO: abre el lector NFC (al leer una URL del SESCAM, mostrará overlay + navegador)
                    onOpenNfcReader = {
                        startActivity(Intent(this, NfcReaderActivity::class.java))
                    },
                    // NUEVO (opcional): abre el agente integrado en WebView
                    onOpenSescamGuide = {
                        startActivity(
                            Intent(this, SescamGuideActivity::class.java)
                                .putExtra("url", "https://sescam.jccm.es/misaluddigital/app/inicio") // pon aquí la URL que prefieras
                        )
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

}
