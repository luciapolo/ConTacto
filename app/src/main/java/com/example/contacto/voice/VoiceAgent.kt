class VoiceAgent(private val context: Context, private val tts: TextToSpeech) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)


    fun ask(prompt: String, onResult: (String) -> Unit) {
        tts.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "ask")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                onResult(text)
            }
            /* implementa no-ops para el resto */
        })
        recognizer.startListening(intent)
    }
}