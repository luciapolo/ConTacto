package com.example.contacto

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.contacto.ui.screens.HomeScreen
import com.example.contacto.ui.theme.ConTactoTheme
import java.util.Locale
import com.example.contacto.nfc.NfcRewriteActivity


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
                    userName = null, // o "María" si lo tienes
                    onRewriteNfcClick = {
                        // Lanza tu flujo/pantalla de reescritura NFC
                        startActivity(Intent(this, NfcRewriteActivity::class.java))
                        //startActivity(Intent("com.example.contacto.ACTION_REWRITE_NFC"))
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Si necesitas reaccionar al nuevo intent, podrías guardar estado y recomponer
    }

    override fun onDestroy() {
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
