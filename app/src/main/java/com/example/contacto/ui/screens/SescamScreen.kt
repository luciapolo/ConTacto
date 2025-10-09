import androidx.compose.runtime.Composable

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