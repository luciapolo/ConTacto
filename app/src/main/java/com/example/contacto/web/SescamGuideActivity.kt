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
        const val EXTRA_START_URL = "extra_start_url"
    }

    // ===== Sin logs de depuración visibles =====
    private fun dbg(@Suppress("UNUSED_PARAMETER") msg: String) { /* no-op */ }

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

    // callback para ejecutar justo al terminar de hablar (p. ej. resaltar CIP)
    private var onTtsDone: (() -> Unit)? = null

    // Contexto detectado por la página (para saber si el usuario ha vuelto atrás manualmente)
    private var lastContext: String? = null  // "HOME" | "PRIMARY" | "CIP" | "FINAL"

    // Intención rápida pendiente tras decir "farmacia" o "wifi"
    // Valores posibles: "FARMACIA" | "WIFI" | null
    private var pendingQuickClick: String? = null

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
            setOnClickListener { lastBanner?.let { banner(it) } }
        }
        val btnMic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Micrófono"
            setBackgroundResource(android.R.drawable.btn_default_small)
            setOnClickListener { reListenCurrent() } // Micrófono manual
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
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                val action = onTtsDone
                onTtsDone = null
                action?.let { runOnUiThread { it.invoke() } }
            }
            override fun onError(id: String?) { onTtsDone = null }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            reqAudio.launch(Manifest.permission.RECORD_AUDIO)
        }

        // ------- WebView
        setupWebView()

        val startUrl = intent.getStringExtra(EXTRA_START_URL)
            ?: "https://sescam.jccm.es/misaluddigital/app/inicio"
        webView.loadUrl(startUrl)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            // Sin voz durante la carga inicial; solo mostramos al entrar en HOME
            askIntentSpeakOnly()
            if (jsReady) askIntent() else pendingAskIntent = true
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { stt?.cancel() }
        runCatching { tts.stop() }
        onTtsDone = null
    }

    override fun onDestroy() {
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

    private fun speakThen(textHtml: String, afterSpeak: (() -> Unit)? = null) {
        lastBanner = textHtml
        banner(textHtml)
        val uttId = UUID.randomUUID().toString()
        onTtsDone = afterSpeak
        runCatching { tts.stop() }
        val plain = textHtml.replace(Regex("<[^>]+>"), " ")
        tts.speak(plain, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    private fun askIntentSpeakOnly() {
        step = Step.ASK_INTENT
        // Sin voz aquí para no hablar mientras la app abre.
    }

    /** Banner visual (HTML simple) */
    private fun banner(html: String) {
        val safe = JSONObject.quote(html)
        evalJs("""if (window.__agent) { __agent.banner($safe); }""", queueIfNotReady = true)
    }

    private fun listenOnce(onText: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (stt == null) stt = SpeechRecognizer.createSpeechRecognizer(this)
        runCatching { stt?.cancel() }

        stt?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { /* sin reintento automático */ }
            override fun onResults(results: Bundle) {
                val said = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
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
        mainHandler.postDelayed({ stt?.startListening(i) }, 150)
    }

    /** Micrófono manual */
    private fun reListenCurrent() {
        when (step) {
            Step.ASK_INTENT -> listenIntent()
            Step.PRIMARY -> listenPrimary()
            Step.CIP -> {
                evalJs("""__agent && __agent.showCip();""", true)
                listenCip()
            }
            Step.FINAL -> listenFinal()
        }
    }

    private fun goBackOneStep() {
        // limpiar intención rápida si el usuario va atrás
        pendingQuickClick = null
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            askIntentSpeakOnly()
            askIntent()
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
                jsReady = true
                while (jsQueue.isNotEmpty()) {
                    val js = jsQueue.removeFirst()
                    if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null)
                    else webView.loadUrl("javascript:$js")
                }
                if (pendingAskIntent) { pendingAskIntent = false; askIntent() }
            }

            @JavascriptInterface fun onPrimaryDetected() {
                runOnUiThread { if (step != Step.CIP) askCip() }
            }

            @JavascriptInterface fun onContext(ctx: String) {
                runOnUiThread { handleContextChange(ctx) }
            }

            @JavascriptInterface fun debug(@Suppress("UNUSED_PARAMETER") msg: String) { /* no-op */ }
        }, "AndroidGuide")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injectAgent()
            }
        }
    }

    private fun handleContextChange(ctx: String) {
        val prev = lastContext
        lastContext = ctx

        when (ctx) {
            "HOME" -> {
                step = Step.ASK_INTENT
                pendingQuickClick = null
                speakThen("<b>¿Qué quieres hacer?</b> Di: <b>cita</b>, <b>farmacia</b> o <b>wifi</b>. Pulsa el micrófono para hablar.")
                askIntent()
            }
            "PRIMARY" -> {
                step = Step.PRIMARY
                pendingQuickClick = null
                evalJs("""__agent && __agent.showPrimary();""", true)
                speakThen("<b>Cita de Atención Primaria</b>. Pulsa el botón o di: pulsa por mí.")
            }
            "CIP" -> {
                step = Step.CIP
                pendingQuickClick = null
                speakThen("Dime tu CIP o escríbelo en el recuadro resaltado.", afterSpeak = {
                    evalJs("""__agent && __agent.showCip();""", true)
                })
            }
            "FINAL" -> {
                step = Step.FINAL
                pendingQuickClick = null
                chosenFinal = null
                speakThen("<b>¿Qué quieres hacer?</b> Di: <b>pedir</b> cita o <b>ver</b> citas.")
            }
        }
        dbg("Context $prev -> $ctx")
    }

    private fun evalJs(code: String, queueIfNotReady: Boolean = false) {
        if (!jsReady) {
            if (queueIfNotReady) jsQueue.addLast(code)
            return
        }
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(code, null)
        else webView.loadUrl("javascript:$code")
    }

    private fun injectAgent() {
        jsReady = false
        val js = """
      (function () {
        if (window.__agent) { 
          window.__agent._rebind && window.__agent._rebind();
          AndroidGuide.agentReady(); 
          return; 
        }

        const norm = s => (s||"").toString().normalize("NFD").replace(/[\u0300-\u036f]/g,"").replace(/\s+/g," ").trim().toLowerCase();

        const pickClickable = (el) => {
          if (!el) return null;
          return el.closest("ion-button,button,a,[role='button'],ion-card,ion-item,ion-card-content") || el;
        };

        const highlight = (el) => {
          if (!el) return;
          el.scrollIntoView({behavior:"smooth", block:"center"});
          document.querySelectorAll(".__guide_highlight").forEach(x=>{
            x.classList.remove("__guide_highlight");
            x.style.outline = ""; x.style.outlineOffset = ""; x.style.borderRadius = ""; x.style.boxShadow = "";
          });
          el.classList.add("__guide_highlight");
          el.style.outline = "4px solid #ff9800";
          el.style.outlineOffset = "2px";
          el.style.borderRadius = "12px";
          el.style.boxShadow = "0 0 0 6px rgba(255,152,0,0.15)";
        };

        const clickSafely = (el) => {
          if (!el) return false;
          try {
            el.dispatchEvent(new MouseEvent("mousedown",{bubbles:true}));
            el.click?.();
            el.dispatchEvent(new MouseEvent("mouseup",{bubbles:true}));
            return true;
          } catch(e){ return false; }
        };

        const getInnerInput = (host) => {
          if (!host) return null;
          let inp = host.querySelector("input");
          if (inp) return inp;
          const sr = host.shadowRoot;
          if (sr) {
            inp = sr.querySelector("input, textarea");
            if (inp) return inp;
          }
          const near = host.closest("ion-item, .item");
          return near ? (near.querySelector("input") || near.shadowRoot?.querySelector("input")) : null;
        };

        const textMatch = (el, ...phrases) => {
          const t = norm(el.textContent);
          return phrases.some(p => t.includes(norm(p)));
        };

        const findPrimary = () => {
          const nodes = Array.from(document.querySelectorAll("ion-card, ion-item, ion-button, button, a, [role='button'], ion-card-title, h2"));
          const hit = nodes.find(el => textMatch(el, "cita atencion primaria"));
          return pickClickable(hit);
        };

        const findCipHost = () => document.querySelector("#input-cip") || document.querySelector("ion-input[id*='cip' i]");
        const findCipInput = () => getInnerInput(findCipHost());

        const findFarmacia = () => {
          const nodes = Array.from(document.querySelectorAll("ion-card, ion-item, ion-button, button, a, [role='button'], ion-card-title, h2"));
          const hit = nodes.find(el => textMatch(el, "encuentra tu farmacia", "farmacia"));
          return pickClickable(hit);
        };

        const findWifi = () => {
          const nodes = Array.from(document.querySelectorAll("ion-card, ion-item, ion-button, button, a, [role='button'], ion-card-title, h2"));
          const hit = nodes.find(el => textMatch(el, "wifi", "wi-fi", "wificam", "wisescam", "conectate a wifi"));
          return pickClickable(hit);
        };

        const findFinalBtn = (mode) => {
          if (mode === "VER") {
            const byId = document.getElementById("btn-ver-citas");
            if (byId) return pickClickable(byId);
            const nodes = Array.from(document.querySelectorAll("ion-button,button,a,[role='button'],ion-item,ion-card"));
            const hit = nodes.find(el => textMatch(el, "ver citas", "consultar citas"));
            return pickClickable(hit);
          } else {
            const byId = document.getElementById("btn-pedir-cita");
            if (byId) return pickClickable(byId);
            const nodes = Array.from(document.querySelectorAll("ion-button,button,a,[role='button'],ion-item,ion-card"));
            const hit = nodes.find(el => textMatch(el, "pedir cita", "solicitar cita"));
            return pickClickable(hit);
          }
        };

        const ensureBanner = () => {
          let b = document.getElementById("__guide_banner");
          if (!b) {
            b = document.createElement("div");
            b.id="__guide_banner";
            Object.assign(b.style,{
              position:"fixed",left:"16px",right:"16px",bottom:"16px",zIndex:2147483647,
              background:"rgba(33,33,33,.95)",color:"#fff",padding:"14px 16px",borderRadius:"14px",
              fontSize:"17px",lineHeight:"1.35",boxShadow:"0 10px 26px rgba(0,0,0,.35)",pointerEvents:"none"
            });
            b.innerHTML = "";
            document.body.appendChild(b);
          }
          return b;
        };

        const computeContext = () => {
          const cip = findCipInput() || findCipHost();
          if (cip) return "CIP";
          const ver = findFinalBtn("VER");
          const pedir = findFinalBtn("PEDIR");
          if (ver || pedir) return "FINAL";
          const prim = findPrimary();
          if (prim) {
            const far = findFarmacia();
            const wi = findWifi();
            if (far || wi) return "HOME";
            return "PRIMARY";
          }
          return "HOME";
        };

        let lastCtx = null;
        const emitContextIfChanged = () => {
          const ctx = computeContext();
          if (ctx !== lastCtx) {
            lastCtx = ctx;
            AndroidGuide.onContext(ctx);
          }
        };

        window.__agent = {
          banner: (html) => { const b = ensureBanner(); b.innerHTML = html; },

          showPrimary: () => { const el = findPrimary(); if (el){ el.scrollIntoView({behavior:"smooth", block:"center"}); highlight(el); } },
          clickPrimary: () => { const el = findPrimary(); if (clickSafely(el)) { AndroidGuide.onPrimaryDetected(); } },

          showFarmacia: () => { const el = findFarmacia(); if (el){ highlight(el); } },
          clickFarmacia: () => { const el = findFarmacia(); if (clickSafely(el)) {} },

          showWifi: () => { const el = findWifi(); if (el){ highlight(el); } },
          clickWifi: () => { const el = findWifi(); if (clickSafely(el)) {} },

          showCip: () => {
            const host = findCipHost(); const inp = findCipInput();
            const target = inp || host;
            if (target){ highlight(target); }
          },
          fillCip: (val) => {
            const host = findCipHost(); const inp = findCipInput();
            const target = inp || host;
            if (!target){ return; }
            target.focus();
            target.value = val;
            target.dispatchEvent(new Event("input", {bubbles:true}));
            target.dispatchEvent(new Event("change", {bubbles:true}));
            target.dispatchEvent(new CustomEvent("ionInput", {bubbles:true}));
            target.dispatchEvent(new CustomEvent("ionChange", {bubbles:true}));
            highlight(target);
          },

          showFinal: (mode) => { const el = findFinalBtn(mode); if (el){ highlight(el); } },
          clickFinal: (mode) => { const el = findFinalBtn(mode); if (clickSafely(el)) {} },

          _rebind: () => { emitContextIfChanged(); }
        };

        const mo = new MutationObserver(() => { emitContextIfChanged(); });
        mo.observe(document.documentElement || document.body, {childList:true, subtree:true});
        window.addEventListener("popstate", emitContextIfChanged);
        window.addEventListener("hashchange", emitContextIfChanged);
        window.addEventListener("load", emitContextIfChanged);
        setTimeout(emitContextIfChanged, 50);

        AndroidGuide.agentReady();
      })();
    """.trimIndent()

        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null)
        else webView.loadUrl("javascript:$js")
    }

    // ==================== Pasos ====================

    private fun askIntent() {
        step = Step.ASK_INTENT
        evalJs("""__agent && __agent.showPrimary();""", true)
        evalJs("""__agent && __agent.showFarmacia();""", true)
        evalJs("""__agent && __agent.showWifi();""", true)
    }

    private fun listenIntent() {
        listenOnce { said ->
            // Si hay intención rápida pendiente y el usuario dice "pulsa", ejecutar y limpiar
            if (pendingQuickClick != null && "pulsa" in said) {
                when (pendingQuickClick) {
                    "FARMACIA" -> evalJs("""__agent && __agent.clickFarmacia();""", true)
                    "WIFI" -> evalJs("""__agent && __agent.clickWifi();""", true)
                }
                pendingQuickClick = null
                return@listenOnce
            }

            when {
                "atrás" in said -> {
                    goBackOneStep()
                }
                "cita" in said -> {
                    pendingQuickClick = null
                    chosenFinal = null
                    goPrimary()
                }
                "farmacia" in said -> {
                    pendingQuickClick = "FARMACIA"
                    speakThen("<b>Farmacia</b>. Pulsa el botón o di: pulsa por mí.")
                    evalJs("""__agent && __agent.showFarmacia();""", true)
                    // Micrófono NO automático: el usuario vuelve a pulsar si quiere decir "pulsa por mí"
                }
                "wifi" in said -> {
                    pendingQuickClick = "WIFI"
                    speakThen("<b>Wi-Fi</b>. Pulsa el botón o di: pulsa por mí.")
                    evalJs("""__agent && __agent.showWifi();""", true)
                }
                else -> {
                    speakThen("<b>No te he entendido.</b> Di: cita, farmacia o wifi.")
                }
            }
        }
    }

    private fun goPrimary() {
        step = Step.PRIMARY
        evalJs("""__agent && __agent.showPrimary();""", true)
        speakThen("<b>Cita de Atención Primaria</b>. Pulsa el botón o di: pulsa por mí.")
    }

    private fun listenPrimary() {
        listenOnce { said ->
            when {
                "atrás" in said -> goBackOneStep()
                "pulsa" in said -> evalJs("""__agent && __agent.clickPrimary();""", true)
            }
        }
    }

    private fun askCip() {
        step = Step.CIP
        speakThen(
            "Dime tu CIP o escríbelo en el recuadro resaltado.",
            afterSpeak = { evalJs("""__agent && __agent.showCip();""", true) }
        )
    }

    private fun listenCip() {
        listenOnce { said ->
            if ("atrás" in said) {
                goBackOneStep()
                return@listenOnce
            }
            val cip = said.uppercase(Locale.ROOT).replace("[^A-Z0-9]".toRegex(), "")
            if (cip.length < 4) {
                evalJs("""__agent && __agent.showCip();""", true)
                speakThen("<b>No lo he entendido.</b> Escríbelo o repítelo despacio.")
                return@listenOnce
            }
            evalJs("""__agent && __agent.fillCip(${JSONObject.quote(cip)});""", true)
            askFinal()
        }
    }

    private fun askFinal() {
        step = Step.FINAL
        chosenFinal = null
        speakThen("<b>¿Qué quieres hacer?</b> Di: <b>pedir</b> cita o <b>ver</b> citas.")
    }

    private fun listenFinal() {
        listenOnce { said ->
            when {
                "atrás" in said -> {
                    goBackOneStep()
                }
                "ver" in said -> {
                    chosenFinal = FinalAction.VER
                    evalJs("""__agent && __agent.showFinal("VER");""", true)
                    speakThen("<b>Ver citas</b>. Pulsa el botón o di: pulsa por mí.")
                }
                "pedir" in said -> {
                    chosenFinal = FinalAction.PEDIR
                    evalJs("""__agent && __agent.showFinal("PEDIR");""", true)
                    speakThen("<b>Pedir cita</b>. Pulsa el botón o di: pulsa por mí.")
                }
                "pulsa" in said -> {
                    val act = if (chosenFinal == FinalAction.VER) "VER" else "PEDIR"
                    evalJs("""__agent && __agent.clickFinal("$act");""", true)
                }
                else -> speakThen("<b>No te he entendido.</b> Di: pedir o ver.")
            }
        }
    }
}
