package com.example.contacto

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.contacto.ui.theme.ConTactoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var tts: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializa TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("es", "ES")
            }
        }

        setContent { App(tts = tts, startIntent = intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Si tu app reacciona a intents entrantes, vuelve a componer con el nuevo intent
        setContent { App(tts = tts, startIntent = intent) }
    }

    override fun onDestroy() {
        // Limpieza del motor TTS
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}

@Composable
fun App(tts: TextToSpeech, startIntent: Intent?) {
    ConTactoTheme {
        MaterialTheme {
            Surface {
                Button(onClick = {
                    tts.speak(
                        "Hola, esto es ConTacto.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "utt-1"
                    )
                }) {
                    Text("Hablar")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    // En preview no tenemos un TTS real; se puede pasar un stub si lo necesitas.
    ConTactoTheme {
        MaterialTheme {
            Surface { Text("Preview de ConTacto") }
        }
    }
}
