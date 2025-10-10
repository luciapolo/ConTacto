package com.example.contacto.web

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class SescamGuideActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        /** Extra opcional para indicar con qué URL debe arrancar la guía. */
        const val EXTRA_START_URL = "extra_start_url"
    }

    // ===== DEBUG =====
    private val TAG = "SescamGuide"
    private val TOAST_DEBUG = true
    private fun dbg(msg: String) {
        Log.d(TAG, msg)
        if (TOAST_DEBUG) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ===== Motores
    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var stt: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ===== Estado conversacional
    private enum class Step { ASK_INTENT, PRIMARY, CIP, FINAL }
    private enum class FinalAction { PEDIR, VER }
    private var step: Step = Step.ASK_INTENT
    private var chosenFinal: FinalAction? = null
    private var jsReady = false
    private val jsQueue = ArrayDeque<String>()
    private var lastBanner: String? = null

    // ===== Permisos
    private val reqAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Sin permiso de micrófono no puedo escucharte. Puedes habilitarlo en Ajustes.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbg("onCreate()")

        val root = FrameLayout(this)
        setContentView(root)

        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val btnRepeat = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = "Repetir"
            setBackgroundResource(android.R.drawable.btn_default_small)
            setOnClickListener {
                dbg("UI: repetir banner")
                lastBanner?.let { banner(it) }
            }
        }
        val btnMic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Escuchar"
            setBackgroundResource(android.R.drawable.btn_default_small)
            setOnClickListener {
                dbg("UI: reListenCurrent()")
                reListenCurrent()
            }
        }
        root.addView(FrameLayout(this).apply {
            addView(
                btnRepeat,
                FrameLayout.LayoutParams(150, 150, Gravity.END or Gravity.BOTTOM).apply {
                    rightMargin = 32; bottomMargin = 220
                }
            )
            addView(
                btnMic,
                FrameLayout.LayoutParams(150, 150, Gravity.END or Gravity.BOTTOM).apply {
                    rightMargin = 32; bottomMargin = 40
                }
            )
        })

        // ------- TTS
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { dbg("TTS onStart: $id") }
            override fun onDone(id: String?) {
                dbg("TTS onDone: $id, step=$step")
                runOnUiThread {
                    mainHandler.postDelayed({
                        when (step) {
                            Step.ASK_INTENT -> listenIntent()
                            Step.PRIMARY -> listenPrimary()
                            Step.CIP -> listenCip()
                            Step.FINAL -> listenFinal()
                        }
                    }, 150)
                }
            }
            override fun onError(id: String?) { dbg("TTS onError: $id") }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            dbg("Pidiendo permiso de micrófono")
            reqAudio.launch(Manifest.permission.RECORD_AUDIO)
        }

        // ------- WebView
        setupWebView()

        // URL de inicio: viene del NFC (NfcReadNowActivity) o cae en la URL por defecto
        val startUrl = intent.getStringExtra(EXTRA_START_URL)
            ?: "https://sescam.jccm.es/misaluddigital/app/inicio"
        dbg("Cargando URL inicial: $startUrl")
        webView.loadUrl(startUrl)
    }

    override fun onInit(status: Int) {
        dbg("onInit(TTS): status=$status")
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            // Hablar SIEMPRE al arrancar
            askIntentSpeakOnly()
            // Cuando JS esté listo, pintamos overlays
            if (jsReady) {
                dbg("JS ya listo en onInit -> askIntent()")
                askIntent()
            } else {
                dbg("JS no listo aún; pendiente askIntent()")
                pendingAskIntent = true
            }
        } else {
            dbg("TTS init FAILURE")
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { stt?.cancel() }
        runCatching { tts.stop() }
    }

    override fun onDestroy() {
        dbg("onDestroy()")
        runCatching { stt?.destroy() }
        if (::tts.isInitialized) runCatching { tts.stop(); tts.shutdown() }
        try {
            webView.loadUrl("about:blank")
            webView.stopLoading()
            webView.webChromeClient = null
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ================= TTS / STT =================

    private fun speakThen(text: String) {
        lastBanner = text
        banner(text)
        val uttId = UUID.randomUUID().toString()
        dbg("speakThen: $text (uttId=$uttId)")
        runCatching { tts.stop() }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    private fun askIntentSpeakOnly() {
        dbg("askIntentSpeakOnly()")
        step = Step.ASK_INTENT
        speakThen("¿Qué quieres hacer? Di: cita, farmacia o wifi.")
    }

    private fun banner(text: String) {
        dbg("banner(): $text")
        evalJs(
            """if (window.__agent) { __agent.banner(${JSONObject.quote(text)}); }""",
            queueIfNotReady = true
        )
    }

    private fun listenOnce(onText: (String) -> Unit) {
        dbg("listenOnce()")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            dbg("STT no disponible")
            return
        }
        if (stt == null) stt = SpeechRecognizer.createSpeechRecognizer(this)

        runCatching { stt?.cancel() }

        stt?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { dbg("STT onReadyForSpeech") }
            override fun onBeginningOfSpeech() { dbg("STT onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { dbg("STT onEndOfSpeech") }
            override fun onError(error: Int) {
                dbg("STT onError: $error")
                mainHandler.postDelayed({ reListenCurrent() }, 400)
            }
            override fun onResults(results: Bundle) {
                val said = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                dbg("STT onResults: $said")
                if (!said.isNullOrBlank()) onText(said.lowercase(Locale.ROOT))
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { tts.stop() }
        val i = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        mainHandler.postDelayed({
            dbg("STT startListening()")
            stt?.startListening(i)
        }, 250)
    }

    private fun reListenCurrent() {
        dbg("reListenCurrent(): step=$step")
        when (step) {
            Step.ASK_INTENT -> listenIntent()
            Step.PRIMARY -> listenPrimary()
            Step.CIP -> listenCip()
            Step.FINAL -> listenFinal()
        }
    }

    // ================= WebView + JS Agent =================

    private var pendingAskIntent = false

    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
        }
        WebView.setWebContentsDebuggingEnabled(true)

        webView.addJavascriptInterface(object {
            @JavascriptInterface fun agentReady() {
                dbg("JS agentReady()")
                jsReady = true
                while (jsQueue.isNotEmpty()) {
                    val js = jsQueue.removeFirst()
                    if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null)
                    else webView.loadUrl("javascript:$js")
                }
                if (pendingAskIntent) {
                    pendingAskIntent = false
                    dbg("pendingAskIntent -> askIntent()")
                    askIntent()
                }
            }

            @JavascriptInterface fun onPrimaryDetected() {
                dbg("JS->Android onPrimaryDetected()")
                runOnUiThread {
                    if (step != Step.CIP) askCip() else dbg("Ya estábamos en CIP, ignoramos")
                }
            }

            @JavascriptInterface fun onFinalDetected() {
                dbg("JS->Android onFinalDetected() (placeholder)")
            }

            @JavascriptInterface fun debug(msg: String) {
                Log.d(TAG, "JS: $msg")
                if (TOAST_DEBUG) Toast.makeText(this@SescamGuideActivity, "JS: $msg", Toast.LENGTH_SHORT).show()
            }
        }, "AndroidGuide")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                dbg("onPageFinished: $url -> injectAgent()")
                injectAgent()
                when (step) {
                    Step.PRIMARY -> evalJs("""__agent && __agent.showPrimary();""", true)
                    Step.CIP -> evalJs("""__agent && __agent.showCip();""", true)
                    Step.FINAL -> {
                        val act = if (chosenFinal == FinalAction.VER) "VER" else "PEDIR"
                        evalJs("""__agent && __agent.showFinal("$act");""", true)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun evalJs(code: String, queueIfNotReady: Boolean = false) {
        if (!jsReady) {
            dbg("evalJs() JS NOT READY -> queued")
            if (queueIfNotReady) jsQueue.addLast(code)
            return
        }
        dbg("evalJs(): ${code.take(120)}...")
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(code, null)
        else webView.loadUrl("javascript:$code")
    }

    private fun injectAgent() {
        jsReady = false
        val js = """
            (function(){
              if (window.__agent) { AndroidGuide.debug("agent already present"); AndroidGuide.agentReady(); return; }
              // … (tu mismo bloque JS de overlay sin cambios)
            })();
        """.trimIndent()

        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null)
        else webView.loadUrl("javascript:$js")
    }

    // ==================== Pasos ====================

    private fun askIntent() {
        dbg("askIntent() overlays")
        step = Step.ASK_INTENT
        evalJs("""__agent && __agent.showPrimary();""", true)
        evalJs("""__agent && __agent.showFarmacia();""", true)
        evalJs("""__agent && __agent.showWifi();""", true)
    }

    private fun listenIntent() {
        dbg("listenIntent()")
        listenOnce { said ->
            dbg("Intent heard: $said")
            when {
                "cita" in said -> {
                    chosenFinal = null
                    goPrimary()
                }
                "farmacia" in said -> {
                    banner("Pulsa: Farmacia")
                    evalJs("""__agent && __agent.showFarmacia();""", true)
                    listenOnce { s2 ->
                        dbg("Farmacia follow-up: $s2")
                        if ("pulsa" in s2) {
                            dbg("Farmacia: pulsa por mí -> clickFarmacia")
                            evalJs("""__agent && __agent.clickFarmacia();""")
                        } else {
                            listenIntent()
                        }
                    }
                }
                "wifi" in said -> {
                    banner("Pulsa: Wi-Fi")
                    evalJs("""__agent && __agent.showWifi();""", true)
                    listenOnce { s2 ->
                        dbg("WiFi follow-up: $s2")
                        if ("pulsa" in s2) {
                            dbg("WiFi: pulsa por mí -> clickWifi")
                            evalJs("""__agent && __agent.clickWifi();""")
                        } else {
                            listenIntent()
                        }
                    }
                }
                else -> {
                    dbg("Intent desconocido -> repetir pregunta")
                    askIntentSpeakOnly()
                }
            }
        }
    }

    private fun goPrimary() {
        dbg("goPrimary()")
        step = Step.PRIMARY
        evalJs("""__agent && __agent.showPrimary();""", true)
        speakThen("Pulsa el botón Cita Atención Primaria. Si quieres que lo pulse por ti, di: pulsa por mí.")
    }

    private fun listenPrimary() {
        dbg("listenPrimary()")
        listenOnce { said ->
            dbg("Primary heard: $said")
            if ("pulsa" in said) {
                dbg("Primary: pulsa por mí -> clickPrimary")
                evalJs("""__agent && __agent.clickPrimary();""", true)
            } else {
                listenPrimary()
            }
        }
    }

    private fun askCip() {
        dbg("askCip()")
        step = Step.CIP
        evalJs("""__agent && __agent.showCip();""", true)
        speakThen("Dime tu C I P, o introdúcelo en el recuadro resaltado.")
    }

    private fun listenCip() {
        dbg("listenCip()")
        listenOnce { said ->
            dbg("CIP heard raw: $said")
            val cip = said.uppercase(Locale.ROOT).replace("[^A-Z0-9]".toRegex(), "")
            dbg("CIP parsed: $cip")
            if (cip.length < 4) {
                evalJs("""__agent && __agent.showCip();""", true)
                speakThen("No he entendido bien el C I P. Puedes escribirlo o dictarlo de nuevo.")
                return@listenOnce
            }
            evalJs("""__agent && __agent.fillCip(${JSONObject.quote(cip)});""", true)
            askFinal()
        }
    }

    private fun askFinal() {
        dbg("askFinal()")
        step = Step.FINAL
        if (chosenFinal == null) chosenFinal = FinalAction.PEDIR
        val act = if (chosenFinal == FinalAction.VER) "VER" else "PEDIR"
        evalJs("""__agent && __agent.showFinal("$act");""", true)
        speakThen(
            if (act == "VER")
                "Pulsa el botón Ver o Consultar citas. Si quieres que lo pulse por ti, di: pulsa por mí."
            else
                "Pulsa el botón Pedir cita. Si quieres que lo pulse por ti, di: pulsa por mí."
        )
    }

    private fun listenFinal() {
        dbg("listenFinal()")
        listenOnce { said ->
            dbg("Final heard: $said")
            if ("pulsa" in said) {
                val act = if (chosenFinal == FinalAction.VER) "VER" else "PEDIR"
                dbg("Final: pulsa por mí -> clickFinal($act)")
                evalJs("""__agent && __agent.clickFinal("$act");""", true)
            } else {
                listenFinal()
            }
        }
    }
}
