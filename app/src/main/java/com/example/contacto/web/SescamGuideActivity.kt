package com.example.contacto.web

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import com.example.contacto.R
import org.json.JSONObject            // <-- IMPORT CLAVE
import java.util.Locale

class SescamGuideActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var speech: SpeechRecognizer? = null
    private var isListening = false

    private val requestAudioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout: WebView + 2 botones flotantes
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val btnRepeat = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = "Repetir instrucción"
            // Si no quieres drawable, comenta la línea de abajo o crea el XML del paso 2
            setBackgroundResource(R.drawable.btn_bg_round)
            setOnClickListener { evaluateJs("window.__guide?.repeatCurrent();") }
        }
        val btnMic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Dictar"
            setBackgroundResource(R.drawable.btn_bg_round)
            setOnClickListener { toggleDictation() }
        }

        val lp1 = FrameLayout.LayoutParams(160, 160).apply {
            marginEnd = 32; bottomMargin = 220; gravity = Gravity.BOTTOM or Gravity.END
        }
        val lp2 = FrameLayout.LayoutParams(160, 160).apply {
            marginEnd = 32; bottomMargin = 40;  gravity = Gravity.BOTTOM or Gravity.END
        }
        root.addView(btnRepeat, lp1)
        root.addView(btnMic, lp2)

        setContentView(root)

        tts = TextToSpeech(this, this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestAudioPerm.launch(Manifest.permission.RECORD_AUDIO)
        }

        setupWebView()
        webView.loadUrl("https://sescam.jccm.es")
    }

    override fun onDestroy() {
        try { speech?.destroy() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            speak("Guía del SESCAM. Te iré indicando los pasos para pedir cita.")
        }
    }

    private fun speak(text: String) {
        if (::tts.isInitialized) {
            if (Build.VERSION.SDK_INT >= 21) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide")
            else @Suppress("DEPRECATION") tts.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }
    }

    private fun toggleDictation() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (speech == null) {
            speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) { isListening = false; speak("No te he entendido.") }
                    override fun onResults(results: Bundle) {
                        isListening = false
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: return
                        val js = "window.__guide && window.__guide.fillActive(${JSONObject.quote(text)});"
                        evaluateJs(js)
                    }
                    override fun onPartialResults(partialResults: Bundle) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
        if (!isListening) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speech?.startListening(intent)   // <-- ahora el tipo coincide
            isListening = true
            speak("Te escucho. Di el dato a rellenar.")
        } else {
            speech?.stopListening()
            isListening = false
        }
    }

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

        webView.addJavascriptInterface(object {
            @JavascriptInterface fun speak(text: String) { runOnUiThread { this@SescamGuideActivity.speak(text) } }
            @JavascriptInterface fun toast(text: String) { runOnUiThread { Toast.makeText(this@SescamGuideActivity, text, Toast.LENGTH_SHORT).show() } }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injectGuideJs()
            }
        }
    }

    private fun evaluateJs(code: String) {
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(code, null)
        else webView.loadUrl("javascript:$code")
    }

    private fun injectGuideJs() {
        val js = """
        (function(){
          if (window.__guide) { window.__guide.start(); return; }

          const say = (t)=>{ try{ Android.speak(String(t)); }catch(e){} }
          const toast = (t)=>{ try{ Android.toast(String(t)); }catch(e){} }

          // Estilos para destacar el objetivo
          const styleId = "__guide_style";
          if (!document.getElementById(styleId)) {
            const st = document.createElement("style");
            st.id = styleId;
            st.textContent = `
              .__guide-focus { outline: 4px solid #2dd4bf !important; box-shadow: 0 0 0 6px rgba(45,212,191,.35) !important; scroll-margin: 100px; }
              .__guide-hint { position: absolute; background: #111827; color: #fff; padding: 8px 12px; border-radius: 12px; font-size: 14px; z-index: 999999; }
            `;
            document.documentElement.appendChild(st);
          }

          // Heurísticas de selectores
          const SELECTORS = [
            { key:"citaBtn",  q:["a[href*='cita']", "a:has(> span:matches('Cita|Cita previa'))", "a[title*='cita' i]" ], stepText:"Pulsa en Cita Previa." },
            { key:"dni",      q:["input[name*='dni' i]", "input[id*='dni' i]", "input[name*='nif' i]", "input[id*='nif' i]"], stepText:"Escribe tu DNI o NIF." },
            { key:"cip",      q:["input[name*='cip' i]", "input[id*='cip' i]", "input[name*='tarjeta' i]"], stepText:"Escribe tu CIP o número de tarjeta sanitaria." },
            { key:"fecha",    q:["input[type='date']", "input[name*='fecha' i]"], stepText:"Selecciona tu fecha de nacimiento." },
            { key:"siguiente",q:["button[type='submit']", "input[type='submit']", "button:matches('Siguiente|Confirmar|Entrar')", "a:matches('Continuar|Siguiente')"], stepText:"Pulsa Siguiente para continuar." }
          ];

          // Polyfill :matches -> :is
          const qAll = (sel)=>{ try { return document.querySelectorAll(sel.replace(/:matches/g, ":is")); } catch(e) { return []; } };

          function findOne(arr){
            for (const s of arr) {
              const els = qAll(s);
              if (els && els.length) return els[0];
            }
            return null;
          }

          function placeHint(el, text){
            removeHints();
            const r = el.getBoundingClientRect();
            const hint = document.createElement('div');
            hint.className = "__guide-hint";
            hint.textContent = text;
            hint.style.left = (window.scrollX + r.left) + "px";
            hint.style.top  = (window.scrollY + r.bottom + 6) + "px";
            document.body.appendChild(hint);
          }
          function removeHints(){
            document.querySelectorAll(".__guide-hint").forEach(n=>n.remove());
          }

          let current = null;
          let idx = 0;
          const order = ["citaBtn","dni","cip","fecha","siguiente"];

          function focusEl(el, text){
            if (!el) return;
            document.querySelectorAll(".__guide-focus").forEach(n=>n.classList.remove("__guide-focus"));
            el.classList.add("__guide-focus");
            el.scrollIntoView({behavior:"smooth", block:"center"});
            placeHint(el, text);
            say(text);
            if (el.tagName === "INPUT" || el.tagName === "TEXTAREA") el.focus();
            current = el;
          }

          function next(){
            if (idx >= order.length) { say("Proceso completado o siguiente pantalla."); return; }
            const item = SELECTORS.find(s=>s.key===order[idx]);
            const el = findOne(item.q);
            if (el) focusEl(el, item.stepText);
            idx++;
          }

          function start(){ idx = 0; next(); }

          // Avanza cuando se rellena un campo
          document.addEventListener("input", (e)=>{
            if (e.target === current && (e.target.value || "").length >= 2) {
              setTimeout(next, 300);
            }
          }, true);

          // API pública para Android
          window.__guide = {
            start,
            repeatCurrent(){ if (current) { const txt = document.querySelector(".__guide-hint")?.textContent || "Sigue la instrucción en pantalla."; say(txt); } },
            fillActive(text){ if (current && ('value' in current)) { current.value = text; current.dispatchEvent(new Event('input',{bubbles:true})); } }
          };

          start();
        })();
    """.trimIndent()

        evaluateJs(js)
    }

}
