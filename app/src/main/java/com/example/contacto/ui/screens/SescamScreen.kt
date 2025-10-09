package com.example.contacto.ui.screens

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext


@Composable
fun SescamScreen(tts: TextToSpeech) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        tts.speak("Vamos a abrir SESCAM. Busca el botón Cita Previa y pulsa.", TextToSpeech.QUEUE_FLUSH, null, "sescam_intro")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://sescam.castillalamancha.es/ciudadanos/cita-previa"))
        ctx.startActivity(intent)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Abriendo SESCAM…")
    }
}