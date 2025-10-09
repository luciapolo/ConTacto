package com.example.contacto

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.contacto.nfc.NfcRewriteActivity
import com.example.contacto.nfc.NfcReader          // <- lector con overlay + navegador
import com.example.contacto.web.SescamGuideActivity // <- agente en WebView (opcional)
import com.example.contacto.ui.screens.HomeScreen
import com.example.contacto.ui.theme.ConTactoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var tts: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("es", "ES")
            }
        }

        setContent {
            ConTactoTheme {
                HomeScreen(
                    userName = null,
                    onRewriteNfcClick = {
                        startActivity(Intent(this, NfcRewriteActivity::class.java))
                    },
                    // NUEVO: abre el lector NFC (al leer una URL del SESCAM, mostrará overlay + navegador)
                    onOpenNfcReader = {
                        startActivity(Intent(this, NfcReader::class.java))
                    },
                    // NUEVO (opcional): abre el agente integrado en WebView
                    onOpenSescamGuide = {
                        // Si quieres pasar una URL concreta de cita, cámbiala aquí.
                        startActivity(
                            Intent(this, SescamGuideActivity::class.java)
                                .putExtra("url", "https://sescam.jccm.es")
                        )
                    }
                )
            }
            HomeScreen(
                userName = null,
                onRewriteNfcClick = { startActivity(Intent(this, NfcRewriteActivity::class.java)) },
                onOpenNfcReader = { startActivity(Intent(this, com.example.contacto.nfc.NfcReader::class.java)) }, // si quieres mantener lector NFC
                onOpenSescamGuide = {
                    startActivity(
                        Intent(this, SescamGuideActivity::class.java)
                            .putExtra("url", "https://sescam.jccm.es/misaluddigital/app/inicio") // pon aquí la URL que prefieras
                    )
                }
            )
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Si más adelante manejas intents (deep links / NFC a Main), hazlo aquí.
    }

    override fun onDestroy() {
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
