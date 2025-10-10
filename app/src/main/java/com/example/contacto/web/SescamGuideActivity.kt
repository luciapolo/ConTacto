package com.example.contacto.web

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.contacto.R
import org.json.JSONObject
import java.util.Locale

class SescamGuideActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var speech: SpeechRecognizer? = null
    private var launchedConversation = false

    private val requestAudioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
        }
        // Aunque no haya permiso, seguimos guiando por voz (solo TTS).
        startConversationIfReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Raíz simple: WebView + 2 botones flotantes (Repetir y Mic)
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
            contentDescription = "Repetir instrucción"
            setBackgroundResource(android.R.drawable.btn_default_small)
            setOnClickListener { evaluateJs("window.__guide?.repeatCurrent();") }
        }
        val btnMic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Dictar"
            setBackgroundResource(android.R.drawable.btn_default_small)
            setOnClickListener { askActionFlow(force = true) }
        }
        // Coloca los botones abajo a la derecha
        FrameLayout(this).apply {
            addView(btnRepeat, FrameLayout.LayoutParams(150, 150).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                rightMargin = 32; bottomMargin = 220
            })
            addView(btnMic, FrameLayout.LayoutParams(150, 150).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                rightMargin = 32; bottomMargin = 40
            })
            root.addView(this)
        }

        tts = TextToSpeech(this, this)

        // Permiso de micrófono si lo tenemos que pedir
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPerm.launch(Manifest.permission.RECORD_AUDIO)
        }

        setupWebView()

        // Si te pasan una URL concreta desde el NFC, úsala. Si no, abre la home.
        val startUrl = intent.getStringExtra("url") ?: "https://sescam.jccm.es"
        webView.loadUrl(startUrl)
    }

    override fun onDestroy() {
        try { speech?.destroy() } catch (_: Exception) {}
        if (::tts.isInitialized) {
            try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ---- TTS ----
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            speak("Guía del SESCAM. Te iré indicando los pasos.")
        }
    }

    private fun speak(text: String) {
        if (::tts.isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide")
        }
    }

    // ---- Reconocimiento de voz (escucha de una sola vez) ----
    private fun listenOnce(onResult: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("El reconocimiento de voz no está disponible en este dispositivo.")
            return
        }
        if (speech == null) {
            speech = SpeechRecognizer.createSpeechRecognizer(this)
        }
        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { speak("No te he entendido."); }
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onResult(text) else speak("No te he entendido.")
            }
            override fun onPartialResults(partialResults: Bundle) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speech?.startListening(intent)
    }

    // ---- WebView + Agente JS ----
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgentString + " ConTacto/1.0"
        }
        WebView.setWebContentsDebuggingEnabled(true)

        // Interfaces nativas accesibles desde JS (para hablar / toast si quisieras)
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun speak(text: String) { runOnUiThread { this@SescamGuideActivity.speak(text) } }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injectGuideJs()
                startConversationIfReady()
            }
        }
    }

    private fun evaluateJs(code: String) {
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(code, null)
        else webView.loadUrl("javascript:$code")
    }

    /** Inyecta funciones JS para localizar campo CIP y botones, y poder hacer click desde Kotlin. */
    private fun injectGuideJs() {
        val js = """
            (function(){
              if (window.__guide) return;

              function normalize(s){
                return (s||"").toLowerCase()
                  .normalize("NFD").replace(/\p{Diacritic}/gu,"");
              }

              function byTextInButtons(needle){
                const n = normalize(needle);
                const btns = document.querySelectorAll("button, a, input[type=button], input[type=submit]");
                for (const b of btns) {
                  const t = normalize(b.innerText || b.value || "");
                  if (t.includes(n)) return b;
                }
                return null;
              }

              function findCip(){
                let el = document.querySelector("input[placeholder*='CIP' i], input[placeholder*='Introduzca su CIP' i]");
                if (!el) el = document.querySelector("input[name*='cip' i], input[id*='cip' i]");
                if (!el) {
                  const ins = document.querySelectorAll("input[type='text'], input");
                  for (const i of ins) {
                    const p = normalize(i.placeholder || "");
                    if (p.includes("cip") || p.includes("introduzca su cip")) return i;
                  }
                }
                return el;
              }

              let chosen = null;

              window.__guide = {
                repeatCurrent: function(){
                  // Mejoramos si quieres guardar el último texto
                  try{ Android.speak("Repite: sigue la instrucción actual."); }catch(e){}
                },
                chooseAction: function(act){
                  const a = normalize(act||"");
                  if (a.startsWith("pedir")) {
                    chosen = byTextInButtons("pedir cita") || byTextInButtons("pedir");
                  } else {
                    chosen = byTextInButtons("ver citas") || byTextInButtons("ver");
                  }
                  if (chosen) { chosen.scrollIntoView({behavior:"smooth", block:"center"}); }
                  return !!chosen;
                },
                fillCip: function(cip){
                  const el = findCip();
                  if (!el) return false;
                  el.focus();
                  el.value = cip;
                  el.dispatchEvent(new Event("input", {bubbles:true}));
                  return true;
                },
                clickChosen: function(){
                  if (chosen) { chosen.click(); return true; }
                  return false;
                }
              };
            })();
        """.trimIndent()
        evaluateJs(js)
    }

    // ---- Conversación principal: preguntar → decidir → pedir CIP → rellenar → click ----
    private fun startConversationIfReady() {
        if (launchedConversation) return
        launchedConversation = true
        askActionFlow(force = false)
    }

    /** Si force=true, repite la pregunta de acción aunque ya se haya hecho. */
    private fun askActionFlow(force: Boolean) {
        if (force) launchedConversation = false
        if (launchedConversation) return
        launchedConversation = true

        speak("¿Qué quieres hacer? Di: pedir cita o ver citas.")
        listenOnce { said ->
            val action = when {
                said.contains("pedir", true) -> "pedir"
                said.contains("ver", true)    -> "ver"
                else -> null
            }
            if (action == null) {
                launchedConversation = false
                speak("No te he entendido. Di pedir cita o ver citas.")
                askActionFlow(force = false)
                return@listenOnce
            }

            // 1) Elegimos botón (Pedir cita / Ver citas)
            evaluateJs("window.__guide && window.__guide.chooseAction(${JSONObject.quote(action)});")

            // 2) Pedimos CIP, lo limpiamos y lo rellenamos
            speak("De acuerdo. Ahora dime tu C I P.")
            listenOnce { cipRaw ->
                val cip = cipRaw.uppercase().replace("[^A-Z0-9]".toRegex(), "")
                evaluateJs("window.__guide && window.__guide.fillCip(${JSONObject.quote(cip)});")
                speak("CIP introducido. Pulso el botón.")
                // 3) Pulsamos
                evaluateJs("window.__guide && window.__guide.clickChosen();")
            }
        }
    }
}
