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
import com.example.contacto.web.BankGuideActivity.Step
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.Calendar

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
    private enum class Step { ASK_INTENT, PRIMARY, CIP, FINAL, FAR_PROV, FAR_LOC, FAR_FECHA }
    private enum class FinalAction { PEDIR, VER }
    private var step: Step = Step.ASK_INTENT
    private var chosenFinal: FinalAction? = null
    private var jsReady = false
    private val jsQueue = ArrayDeque<String>()
    private var lastBanner: String? = null

    // callback para ejecutar justo al terminar de hablar (p. ej. resaltar CIP)
    private var onTtsDone: (() -> Unit)? = null

    // Contexto detectado por la página
    private var lastContext: String? = null  // "HOME" | "PRIMARY" | "CIP" | "FINAL" | "FARMACIA"

    // Intención rápida pendiente tras decir "farmacia" o "wifi"
    private var pendingQuickClick: String? = null  // "FARMACIA" | "WIFI" | null

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

    private fun Int.dp(ctx: android.content.Context) =
        (this * ctx.resources.displayMetrics.density).toInt()

    private fun Float.dp(ctx: android.content.Context) =
        (this * ctx.resources.displayMetrics.density)

    // Botón redondo tipo FAB con ripple y tinte blanco
    private fun makeRoundFab(
        ctx: android.content.Context,
        iconRes: Int,
        contentDesc: String
    ): ImageButton {
        val btn = ImageButton(ctx)

        // Icono
        btn.setImageResource(iconRes)
        btn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        btn.scaleType = android.widget.ImageView.ScaleType.CENTER

        // Fondo redondo con ripple
        val base = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20f.dp(ctx)
            setColor(0xFF4F46E5.toInt()) // Indigo
        }
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            base,
            null
        )
        btn.background = ripple

        // Padding y elevación
        btn.setPadding(12.dp(ctx), 12.dp(ctx), 12.dp(ctx), 12.dp(ctx))
        androidx.core.view.ViewCompat.setElevation(btn, 8f)
        btn.contentDescription = contentDesc
        return btn
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
            setOnClickListener { repeatPromptAndRehighlight() }  // <— cambio clave
        }
        val btnMic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Micrófono"
            setBackgroundResource(android.R.drawable.btn_default_small)
            setOnClickListener { reListenCurrent() }
        }
        val size = 72.dp(this)          // antes 150px; ahora ~104dp, se ven grandes y consistentes
        val gap = 12.dp(this)
        val spacing = 12.dp(this)
        val micBottom = 24.dp(this)
        val repeatBottom = micBottom + size + spacing

        root.addView(FrameLayout(this).apply {
            addView(btnRepeat, FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                rightMargin = gap; bottomMargin = repeatBottom
            })
            addView(btnMic, FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                rightMargin = gap; bottomMargin = micBottom
            })
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
    private fun String.isBackCmd(): Boolean {
        val s = this.lowercase()
        return s.contains("atrás") || s.contains("atras") || s.contains("volver") || s.contains("retrocede")
    }

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
        // sin voz aquí
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
            Step.FAR_PROV -> listenFarProvincia()
            Step.FAR_LOC -> listenFarLocalidad()
            Step.FAR_FECHA -> listenFarFecha()
        }
    }

    private fun goBackOneStep() {
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
    private var lastCtxTs: Long = 0L
    private fun handleContextChange(ctx: String) {
        val now = System.currentTimeMillis()
        if (ctx == lastContext && now - lastCtxTs < 300) return  // <- evita doble disparo inmediato
        lastCtxTs = now

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
            "FARMACIA" -> {
                step = Step.FAR_PROV
                pendingQuickClick = null
                speakThen("<b>Farmacia</b>. Dime la <b>provincia</b>.", afterSpeak = {
                    evalJs("""__agent && __agent.focusProvincia();""", true)
                    listenFarProvincia()   // <- esto faltaba
                })
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
        
        (function ensureHighlightCss(){
    const styleId="__guide_style_common";
    if (!document.getElementById(styleId)) {
      const st=document.createElement("style");
      st.id=styleId;
      st.textContent = `
        @keyframes __pulse_v2 {
          0%   { box-shadow: 0 0 0 0 rgba(255,152,0,.55), 0 0 0 10px rgba(255,152,0,0); }
          70%  { box-shadow: 0 0 0 6px rgba(255,152,0,.35), 0 0 0 18px rgba(255,152,0,0); }
          100% { box-shadow: 0 0 0 0 rgba(255,152,0,.35), 0 0 0 0 rgba(255,152,0,0); }
        }
        .__guide_highlight{
          outline:3px solid #ff9800 !important;
          outline-offset:2px !important;
          border-radius:14px !important;
          animation:__pulse_v2 1.8s ease-out infinite;
          transition: outline-color .2s ease, box-shadow .2s ease;
        }
      `;
      document.head.appendChild(st);
    }
  })();

        const norm = s => (s||"").toString().normalize("NFD").replace(/[\u0300-\u036f]/g,"").replace(/\s+/g," ").trim().toLowerCase();

        const pickClickable = (el) => {
          if (!el) return null;
          return el.closest("ion-button,button,a,[role='button'],ion-card,ion-item,ion-card-content,select,input") || el;
        };

        const highlight = (el) => {
  if (!el) return;
  el.scrollIntoView({behavior:"smooth", block:"center"});
  document.querySelectorAll(".__guide_highlight").forEach(x=>x.classList.remove("__guide_highlight"));
  el.classList.add("__guide_highlight");
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
          let inp = host.querySelector("input, select, textarea");
          if (inp) return inp;
          const sr = host.shadowRoot;
          if (sr) {
            inp = sr.querySelector("input, select, textarea");
            if (inp) return inp;
          }
          const near = host.closest("ion-item, .item");
          return near ? (near.querySelector("input,select") || near.shadowRoot?.querySelector("input,select")) : null;
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

        // ---------- Farmacia: provincia / localidad / fecha ----------
        const findProvincia = () =>
          document.querySelector("select#provincia, ion-select#provincia, [name='provincia'], ion-select[name='provincia']");
        const findLocalidad = () =>
          document.querySelector("select#localidad, ion-select#localidad, [name='localidad'], ion-select[name='localidad']");
        const findFecha = () =>
          document.querySelector("input[type='date']#fecha, input[type='date'][name='fecha'], ion-datetime#fecha, ion-datetime[name='fecha']");

        const setSelectByLabel = (sel, valueNorm) => {
          if (!sel) return;
          const isIon = sel.tagName?.toLowerCase().includes("ion-");
          if (!isIon && sel.tagName?.toLowerCase() === "select") {
            const opts = Array.from(sel.options || []);
            const hit = opts.find(o => (o.textContent||"").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g,"") === valueNorm);
            if (hit) { sel.value = hit.value; sel.dispatchEvent(new Event("change", {bubbles:true})); }
          } else {
            // ion-select: intentar establecer value por label
            const shadow = sel.shadowRoot || sel;
            const opts = Array.from(document.querySelectorAll("ion-select-option"));
            const hit = opts.find(o => (o.textContent||"").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g,"") === valueNorm);
            if (hit) {
              sel.value = hit.value ?? hit.getAttribute("value") ?? hit.textContent?.trim();
              sel.dispatchEvent(new CustomEvent("ionChange",{bubbles:true,detail:{value: sel.value}}));
            }
          }
        };

        const setDate = (el, yyyyMmDd) => {
          if (!el) return;
          if (el.tagName?.toLowerCase() === "ion-datetime") {
            el.value = yyyyMmDd;
            el.dispatchEvent(new CustomEvent("ionChange",{bubbles:true, detail:{value:yyyyMmDd}}));
          } else {
            el.value = yyyyMmDd;
            el.dispatchEvent(new Event("input",{bubbles:true}));
            el.dispatchEvent(new Event("change",{bubbles:true}));
          }
        };

        const ensureBanner = () => {
  let b = document.getElementById("__guide_banner");
  if (!b) {
    b = document.createElement("div");
    b.id="__guide_banner";
    const BANNER_OFFSET_TOP = 72;
    Object.assign(b.style,{
      position:"fixed",
      top:"calc(env(safe-area-inset-top, 0px) + " + BANNER_OFFSET_TOP + "px)",              
      left:"16px",
      right:"16px",
      zIndex:2147483647,
      background:"rgba(32,32,36,.96)",
      color:"#fff",
      padding:"14px 16px",
      borderRadius:"14px",
      fontSize:"17px",
      lineHeight:"1.35",
      boxShadow:"0 8px 24px rgba(0,0,0,.35)",
      border:"1px solid rgba(255,255,255,.08)",
      pointerEvents:"none",
      textAlign:"center"
    });
    b.innerHTML = "";
    document.body.appendChild(b);
  }
  return b;
};


        const computeContext = () => {
          // detectar formulario de farmacia por campos típicos
          const prov = findProvincia();
          const loc = findLocalidad();
          const fec = findFecha();
          if (prov || loc || fec) return "FARMACIA";

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
        
        findFarmaciasBtn: () => {
          const nodes = Array.from(document.querySelectorAll('ion-button,button,a,[role="button"],ion-item,ion-card'));
          const hit = nodes.find(el => /farmacias|buscar\s+farmacias/i.test((el.textContent||"")));
          return hit || null;
        },
        highlightFarmaciaBuscar: () => {
          const el = __agent.findFarmaciasBtn?.();
          if (el) { el.scrollIntoView({behavior:"smooth", block:"center"}); (window.__guide_highlight||function(e){e.style.outline="4px solid #ff9800";})(); }
        },
        clickBuscarFarmacias: () => {
          const el = __agent.findFarmaciasBtn?.();
          if (el) { el.dispatchEvent(new MouseEvent("mousedown",{bubbles:true})); el.click?.(); el.dispatchEvent(new MouseEvent("mouseup",{bubbles:true})); }
        },
        enableLocalidad: () => { /* si hay lógica de habilitar, colócala; si no, deja no-op */ },

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

          // ---- Farmacia helpers ----
          focusProvincia: () => { const el = findProvincia(); if (el){ highlight(el); el.focus?.(); } },
          focusLocalidad: () => { const el = findLocalidad(); if (el){ highlight(el); el.focus?.(); } },
          focusFecha: () => { const el = findFecha(); if (el){ highlight(el); el.focus?.(); } },

          setProvincia: (valueNorm) => {
            const el = findProvincia(); if (!el) return;
            setSelectByLabel(el, valueNorm);
            highlight(el);
          },
          setLocalidad: (valueNorm) => {
            const el = findLocalidad(); if (!el) return;
            setSelectByLabel(el, valueNorm);
            highlight(el);
          },
          setFecha: (yyyyMmDd) => {
            const el = findFecha(); if (!el) return;
            setDate(el, yyyyMmDd); highlight(el);
          },

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
            // Dentro de listenIntent(), justo donde ya tienes:
            if (pendingQuickClick != null && "pulsa" in said) {
                when (pendingQuickClick) {
                    "FARMACIA" -> evalJs("""__agent && __agent.clickFarmacia();""", true)
                    "WIFI"     -> evalJs("""__agent && __agent.clickWifi();""", true)
                    "FARMACIA_BUSCAR" -> {
                        evalJs("""__agent && __agent.clickBuscarFarmacias && __agent.clickBuscarFarmacias();""", true)
// Respaldo por si no existe la función del agente
                        evalJs(
                            """
    (function(){
      var btn=[...document.querySelectorAll('ion-button,button,a,[role="button"]')]
               .find(b=>/farmacias/i.test(b.textContent||'') && !b.disabled);
      if(btn){ btn.click(); return true } else { return false }
    })();
    """.trimIndent(),
                            true
                        )

                    }
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
                // Dentro de listenIntent(), en el when/if que detecta "farmacia"
                "farmacia" in said -> {
                    pendingQuickClick = "FARMACIA"
                    speakThen("<b>Farmacia</b>. Pulsa el <b>botón resaltado</b> o di: <b>pulsa por mí</b>.") {
                        evalJs("""__agent && __agent.showFarmacia();""", true)
                        // luego seguimos escuchando por si dice "pulsa"
                        listenIntent()
                    }
                    return@listenOnce
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

    // -------- CIP (con decodificador de letras) --------

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
            val decoded = decodeSpelledEs(said) // <-- convierte “vejek” en “BGK”, etc.
            val cip = decoded.uppercase(Locale.ROOT).replace("[^A-Z0-9]".toRegex(), "")
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

    // -------- Farmacia: provincia → localidad → fecha --------

    private fun listenFarProvincia() {
        listenOnce { said ->
            // Volver atrás robusto
            if (said.isBackCmd()) {
                goBackOneStep()
                return@listenOnce
            }

            // Si no han dicho provincia… repite prompt
            if (!said.contains("provincia") && !said.contains("toledo") && !said.contains("albacete")
            /* … añade tus provincias si ya haces matching simple … */) {
                speakThen(
                    "Dime la <b>provincia</b> donde buscas la farmacia " +
                            "o pulsa el <b>selector de provincia</b>. También puedes decir: <b>pulsa por mí</b>."
                ) {
                    evalJs("""__agent && __agent.focusProvincia && __agent.focusProvincia();""", true)
                    listenFarProvincia()
                }
                return@listenOnce
            }

            // Aquí tu lógica actual de detección y set de provincia…
            // Cuando detectes provincia correcta:
            step = Step.FAR_LOC
            speakThen(
                "Perfecto. Ahora dime la <b>localidad</b> o pulsa el <b>selector de localidad</b>. " +
                        "También puedes decir: <b>pulsa por mí</b>."
            ) {
                evalJs("""__agent && __agent.enableLocalidad && __agent.enableLocalidad();""", true)
                evalJs("""__agent && __agent.focusLocalidad && __agent.focusLocalidad();""", true)
                listenFarLocalidad()
            }
        }
    }


    private fun listenFarLocalidad() {
        listenOnce { said ->
            if (said.isBackCmd()) {
                // Vuelve al paso de provincia
                step = Step.FAR_PROV
                evalJs("""__agent && __agent.focusProvincia && __agent.focusProvincia();""", true)
                speakThen("Volvemos a <b>provincia</b>. Dime la provincia o di: <b>pulsa por mí</b>.") {
                    listenFarProvincia()
                }
                return@listenOnce
            }

            // Tu lógica de localidad…
            step = Step.FAR_FECHA
            speakThen(
                "Genial. Ahora elige la <b>fecha</b> en el calendario o dímela con voz. " +
                        "Después, pulsa el <b>botón Farmacias</b> o di: <b>pulsa por mí</b>."
            ) {
                evalJs("""__agent && __agent.focusFecha && __agent.focusFecha();""", true)
                // Si quieres, deja preparado el quick-click del botón de buscar farmacias
                pendingQuickClick = "FARMACIA_BUSCAR"
                listenFarFecha()
            }
        }
    }


    private fun listenFarFecha() {
        listenOnce { saidRaw ->
            val said = saidRaw.lowercase()

            if (said.isBackCmd()) {
                step = Step.FAR_LOC
                evalJs("""__agent && __agent.focusLocalidad && __agent.focusLocalidad();""", true)
                speakThen("Volvemos a <b>localidad</b>. Dime la localidad o di: <b>pulsa por mí</b>.") {
                    listenFarLocalidad()
                }
                return@listenOnce
            }

            if (said.contains("pulsa")) {
                evalJs("""__agent && __agent.clickBuscarFarmacias && __agent.clickBuscarFarmacias();""", true)
                // respaldo
                evalJs(
                    """
                (function(){
                  var btn=[...document.querySelectorAll('ion-button,button,a,[role="button"]')]
                           .find(b=>/farmacias/i.test(b.textContent||'') && !b.disabled);
                  if(btn){ btn.click(); return true } else { return false }
                })();
                """.trimIndent(),
                    true
                )
                return@listenOnce
            }

            // === usar parseSpokenDateIso ===
            val iso = parseSpokenDateIso(saidRaw) ?: run {
                speakThen(
                    "Dime una <b>fecha</b> como <b>hoy</b>, <b>mañana</b> o <b>12/10/2025</b>. " +
                            "Luego, pulsa <b>Farmacias</b> o di: <b>pulsa por mí</b>."
                ) {
                    evalJs("""__agent && __agent.focusFecha && __agent.focusFecha();""", true)
                    listenFarFecha()
                }
                return@listenOnce
            }

            evalJs("""__agent && __agent.setFecha && __agent.setFecha("$iso");""", true)
            speakThen("Fecha establecida. Cuando quieras, pulsa <b>Farmacias</b> o di: <b>pulsa por mí</b>.") {
                listenFarFecha()
            }
        }
    }

    // Repetir: vuelve a decir en voz y re-resalta el paso actual (Sescam)
    private fun repeatPromptAndRehighlight() {
        lastBanner?.let { html ->
            // Re-highlight según el paso
            when (step) {
                Step.ASK_INTENT -> {
                    evalJs("""__agent && __agent.showPrimary();""")
                    evalJs("""__agent && __agent.showFarmacia();""")
                    evalJs("""__agent && __agent.showWifi();""")
                }
                Step.PRIMARY -> evalJs("""__agent && __agent.showPrimary();""")
                Step.CIP     -> evalJs("""__agent && __agent.showCip();""")
                Step.FINAL   -> {
                    // Vuelve a resaltar el último botón sugerido (si hay), si no, nada
                    evalJs("""__agent && __agent.showFinal("VER");""")
                    evalJs("""__agent && __agent.showFinal("PEDIR");""")
                }
                Step.FAR_PROV -> evalJs("""__agent && __agent.focusProvincia && __agent.focusProvincia();""")
                Step.FAR_LOC  -> evalJs("""__agent && __agent.focusLocalidad && __agent.focusLocalidad();""")
                Step.FAR_FECHA-> evalJs("""__agent && __agent.focusFecha && __agent.focusFecha();""")
            }
            // Forzar que lo vuelva a decir
            val plain = html.replace(Regex("<[^>]+>"), " ")
            runCatching { tts.stop() }
            tts.speak(plain, TextToSpeech.QUEUE_FLUSH, null, java.util.UUID.randomUUID().toString())
        }
    }



}


    // ==================== Utilidades ====================

    private fun normalizeForSelect(s: String): String {
        return s.lowercase(Locale.ROOT)
            .normalizeToAscii()
            .trim()
    }

    private fun String.normalizeToAscii(): String =
        this.normalizeNFD().replace(Regex("[\\u0300-\\u036f]"), "")

    private fun String.normalizeNFD(): String =
        java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)

    private fun todayIso(): String {
        val cal = Calendar.getInstance()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        return fmt.format(cal.time)
    }

    private fun parseSpokenDateIso(saidRaw: String): String? {
        val said = saidRaw.lowercase(Locale.ROOT).trim()
        val cal = Calendar.getInstance()
        when {
            "hoy" in said -> { /* cal ahora */ }
            "mañana" in said || "manana" in said -> cal.add(Calendar.DAY_OF_YEAR, 1)
            else -> {
                // intenta dd/mm/aaaa o dd de <mes> de aaaa
                val meses = listOf(
                    "enero","febrero","marzo","abril","mayo","junio",
                    "julio","agosto","septiembre","octubre","noviembre","diciembre"
                )
                val ddmmyyyy = Regex("""\b(\d{1,2})[\/\-](\d{1,2})[\/\-](\d{4})\b""").find(said)
                if (ddmmyyyy != null) {
                    val d = ddmmyyyy.groupValues[1].toInt()
                    val m = ddmmyyyy.groupValues[2].toInt() - 1
                    val y = ddmmyyyy.groupValues[3].toInt()
                    cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
                } else {
                    val mLong = meses.indexOfFirst { said.contains(it) }
                    val dMatch = Regex("""\b(\d{1,2})\b""").find(said)?.groupValues?.get(1)?.toIntOrNull()
                    val yMatch = Regex("""\b(20\d{2})\b""").find(said)?.groupValues?.get(1)?.toIntOrNull()
                    if (mLong >= 0 && dMatch != null) {
                        val y = yMatch ?: cal.get(Calendar.YEAR)
                        cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, mLong); cal.set(Calendar.DAY_OF_MONTH, dMatch)
                    } else return null
                }
            }
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        return fmt.format(cal.time)
    }



    /**
     * Decodificador de letras/números en español para el CIP.
     * Convierte secuencias reconocidas como “vejek” en “BGK”.
     * Reglas:
     *  - “be” y “ve” → B (para evitar el típico error de “B” como “V” al dictar letra suelta)
     *  - “uve” / “ve corta” → V
     *  - “je” / “ge” → G
     *  - “ka” / “k” → K
     *  - “equis” → X, “jota” → J, “cu” → Q, etc.
     *  - números en texto (“cero”, “uno”…) → dígitos.
     */
    private fun decodeSpelledEs(inputRaw: String): String {
        val input = inputRaw.lowercase(Locale.ROOT).normalizeToAscii()

        // tokenización greedy por palabras y también dentro de palabras largas
        val map = linkedMapOf(
            // letras con nombres largos primero
            "doble u" to "W",
            "w" to "W",
            "uve" to "V",
            "ve corta" to "V",
            "ve" to "B",
            "be" to "B",
            "ce" to "C",
            "de" to "D",
            "efe" to "F",
            "ge" to "G",
            "je" to "G",
            "hache" to "H",
            "i griega" to "Y",
            "ye" to "Y",
            "i" to "I",
            "jota" to "J",
            "ka" to "K",
            "k" to "K",
            "ele" to "L",
            "eme" to "M",
            "ene" to "N",
            "enie" to "Ñ",
            "ene con tilde" to "Ñ",
            "o" to "O",
            "pe" to "P",
            "cu" to "Q",
            "erre" to "R",
            "ese" to "S",
            "te" to "T",
            "u" to "U",
            "equis" to "X",
            "zeta" to "Z",
            // dígitos
            "cero" to "0",
            "uno" to "1",
            "dos" to "2",
            "tres" to "3",
            "cuatro" to "4",
            "cinco" to "5",
            "seis" to "6",
            "siete" to "7",
            "ocho" to "8",
            "nueve" to "9"
        )

        // primero intenta por palabras separadas
        val words = input.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val byWords = words.map { w ->
            map[w] ?: w // si no es nombre de letra, deja tal cual (podría ser ya “BGK123”)
        }.joinToString("")

        // si ya salió algo con letras sueltas, úsalo
        if (byWords.any { it.isLetter() }) return byWords.uppercase(Locale.ROOT)

        // si vino todo pegado (ej. “vejek”), hacemos greedy dentro de la cadena original
        var i = 0
        val out = StringBuilder()
        val keys = map.keys.sortedByDescending { it.length } // greedy: más largas primero
        val s = input
        while (i < s.length) {
            var matched = false
            for (k in keys) {
                if (k.isEmpty()) continue
                if (k.contains(' ') ) {
                    // patrones con espacios no aplican al pegado
                    continue
                }
                if (i + k.length <= s.length && s.substring(i, i + k.length) == k) {
                    out.append(map[k])
                    i += k.length
                    matched = true
                    break
                }
            }
            if (!matched) {
                // si es dígito o letra A-Z, la dejamos
                val ch = s[i]
                if (ch.isDigit() || (ch in 'a'..'z')) out.append(ch)
                i++
            }
        }
        return out.toString().uppercase(Locale.ROOT)
    }

    // ==================== FIN utilidades ====================

