import androidx.compose.runtime.Composable

@Composable
fun CallScreen(tts: TextToSpeech) {
    val ctx = LocalContext.current
    val uri = (ctx as Activity).intent.data
    val number = uri?.getQueryParameter("number")
    val name = uri?.getQueryParameter("name") ?: "Contacto"


    LaunchedEffect(Unit) { tts.speak("Vas a llamar a $name. Pulsa el botón grande para llamar.", TextToSpeech.QUEUE_FLUSH, null, "call_intro") }


    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Button(onClick = {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            ctx.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth().padding(24.dp).height(96.dp)) {
            Text("Llamar a $name")
        }
    }
}