package com.example.contacto.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun CallScreen(tts: TextToSpeech) {
    val ctx = LocalContext.current
    val uri = (ctx as Activity).intent.data
    val number = uri?.getQueryParameter("number")
    val name = uri?.getQueryParameter("name") ?: "Contacto"

    LaunchedEffect(Unit) {
        tts.speak(
            "Vas a llamar a $name. Pulsa el botón grande para llamar.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "call_intro"
        )
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                ctx.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .height(96.dp)
        ) {
            Text("Llamar a $name")
        }
    }
}
