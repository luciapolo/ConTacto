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

/**
 * Asistente de login para banca online:
 * - Resalta DNI/NIE → escucha → rellena
 * - Resalta Contraseña → escucha (opcional) → rellena en SILENCIO (no se pronuncia)
 * - Resalta “Acceder” → permite “pulsa por mí”
 *
 * Por defecto abre Ruralvía (#/login). Puedes pasar otra URL con EXTRA_START_URL.
 */
class BankGuideActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_START_URL = "extra_start_url"
    }

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var stt: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class Step { DNI, PASS, SUBMIT }
    private var step: Step = Step.DNI

    private var didWelcome = false

    private var jsReady = false
    private val jsQueue = ArrayDeque<String>()
    private var lastBanner: String? = null

    // Nunca repetimos en voz lo que el usuario diga en contraseña
    private var suppressSpeakOnce = false

    // ===== permisos mic =====
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
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

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

        // TTS
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            reqAudio.launch(Manifest.permission.RECORD_AUDIO)
        }

        setupWebView()

        val startUrl = intent.getStringExtra(EXTRA_START_URL)
            ?: "https://banca.ruralvia.com/eai/sm/#/login"
        webView.loadUrl(startUrl)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            if (!didWelcome) {
                didWelcome = true
                // Mostrar y decir el mensaje de bienvenida SOLO una vez
                speakThen("<b>Login de Ruralvía.</b> Primero <b>DNI o NIE</b>. Pulsa el micrófono para hablar.")
            }
        }
    }


    override fun onPause() {
        super.onPause()
        runCatching { stt?.cancel() }
        runCatching { tts.stop() }
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

    // =================== WebView + JS ===================

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
                lastBanner?.let { banner(it) }
                // Al inyectar, enseña el DNI
                showDni()
            }
            @JavascriptInterface fun onPassManual() {
                runOnUiThread {
                    // no hablamos la contraseña; solo avanzamos
                    if (step == Step.PASS) askSubmit()
                }
            }
            @JavascriptInterface fun onDniIdleDone(value: String) {
                runOnUiThread {
                    if (step == Step.DNI && value.isNotBlank()) {
                        askPass() // pasa al siguiente paso cuando el usuario deja de escribir el DNI
                    }
                }
            }

            @JavascriptInterface fun onDniReady(value: String) {
                runOnUiThread {
                    if (step == Step.DNI && value.isNotBlank() && value.length == 9) {
                        askPass()
                    }
                }
            }


            @JavascriptInterface fun onPassTypingIdle() {
                runOnUiThread {
                    if (step == Step.PASS) {
                        askSubmit() // avanza cuando deja de escribir la contraseña (inactividad)
                    }
                }
            }

        }, "AndroidBank")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injectAgent()
            }
        }
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
(function(){
  if (window.__bank) { AndroidBank.agentReady(); return; }

  const q  = (sel,root=document)=>root.querySelector(sel);
  const qa = (sel,root=document)=>Array.from(root.querySelectorAll(sel));

  // ---- CSS highlight persistente
  const styleId="__guide_style_bank";
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

  // ---- Selectores del banco
  const findDni   = () => q('input[name="dniNie"]') || q('input[id*="dni" i], input[id*="nie" i]');
  const findPass  = () => q('input[type="password"]');
  const findAcceder = () => {
    const nodes = qa('button,ion-button,a,[role="button"]');
    return nodes.find(el => /acceder/i.test(el.textContent||"")) || null;
  };

  // ---- Estado y utilidades
  let currentStep = null; // "dni" | "pass" | "submit"
  let highlightedEl = null;

  const ensureHighlight = (el) => {
    if (!el) return false;
    try { el.scrollIntoView({behavior:"smooth", block:"center"}); } catch(e){}
    if (highlightedEl && highlightedEl !== el) highlightedEl.classList.remove("__guide_highlight");
    highlightedEl = el;
    el.classList.add("__guide_highlight");
    return true;
  };

  const focusEnableHighlight = (el) => {
    if (!el) return false;
    try { el.removeAttribute?.("disabled"); } catch(e){}
    try { el.focus?.(); } catch(e){}
    return ensureHighlight(el);
  };

  // Setter nativo para value (frameworks)
  const nativeSetValue = (el, val) => {
    try {
      const proto = Object.getPrototypeOf(el);
      const desc = Object.getOwnPropertyDescriptor(proto, "value");
      if (desc && desc.set) desc.set.call(el, val);
      else el.value = val;
    } catch(e) { el.value = val; }
  };

  const fireInputEvents = (el) => {
    try { el.dispatchEvent(new Event("input",{bubbles:true})); } catch(e){}
    try { el.dispatchEvent(new Event("keyup",{bubbles:true})); } catch(e){}
    try { el.dispatchEvent(new Event("change",{bubbles:true})); } catch(e){}
  };

  const retry = (fn, tries=18, delay=110) => new Promise(res=>{
    let n=0;
    const tick=()=>{ if (fn() || n>=tries) res(true); else { n++; setTimeout(tick,delay); } };
    tick();
  });

  // ---- Detección manual (DNI y PASS)
  const DNI_IDLE_MS  = 600;
  const PASS_IDLE_MS = 900;

  let boundDniEl = null, dniTimer = null, dniValLast = null, dniWatcher = null, dniProgrammaticSetAt = 0;
  const cleanDni = v => (v||"").toString().replace(/\s+/g,"").replace(/-/g,"").toUpperCase();

  const bindDniInput = () => {
    const el = findDni();
    if (!el || el === boundDniEl) return;
    boundDniEl = el;

    const maybeReady = () => {
      // evita falsos positivos justo tras set programático
      if (Date.now() - dniProgrammaticSetAt < 500) return;
      const v9 = cleanDni(el.value);
      if (v9.length === 9) {
        try { AndroidBank.onDniReady(v9); } catch(e){}
      }
    };

    const onAny = () => {
      if (dniTimer) clearTimeout(dniTimer);
      dniTimer = setTimeout(maybeReady, DNI_IDLE_MS);
      // además, si ya llegó a 9, avisa sin esperar el idle
      const v9 = cleanDni(el.value);
      if (v9.length === 9) {
        try { AndroidBank.onDniReady(v9); } catch(e){}
      }
    };

    el.addEventListener("input", onAny, {passive:true});
    el.addEventListener("change", onAny, {passive:true});
    el.addEventListener("keyup", onAny, {passive:true});
    el.addEventListener("paste", onAny, {passive:true});
    el.addEventListener("blur",  maybeReady,  {passive:true});
    el.addEventListener("keydown", (ev)=>{ if (ev.key === "Enter") maybeReady(); }, {passive:true});
    el.addEventListener("compositionend", onAny, {passive:true});

    // watcher de valor por si la web no emite eventos
    try { if (dniWatcher) clearInterval(dniWatcher); } catch(e){}
    dniValLast = el.value;
    dniWatcher = setInterval(()=>{
      const cur = el.value;
      if (cur !== dniValLast) { dniValLast = cur; onAny(); }
    }, 150);
  };

  let boundPassEl = null, passTimer = null, passValLast = null, passWatcher = null;
  const bindPassInput = () => {
    const el = findPass();
    if (!el || el === boundPassEl) return;
    boundPassEl = el;
    try { el.removeAttribute("disabled"); } catch(e){}

    const kick = () => { if ((el.value||"").length > 0) { try { AndroidBank.onPassTypingIdle(); } catch(e){} } };
    const onAny = () => {
      if (passTimer) clearTimeout(passTimer);
      passTimer = setTimeout(kick, PASS_IDLE_MS);
    };

    el.addEventListener("input", onAny, {passive:true});
    el.addEventListener("change", onAny, {passive:true});
    el.addEventListener("keyup", onAny, {passive:true});
    el.addEventListener("paste", onAny, {passive:true});
    el.addEventListener("blur",  kick,  {passive:true});
    el.addEventListener("keydown", (ev)=>{ if (ev.key === "Enter") kick(); }, {passive:true});
    el.addEventListener("compositionend", onAny, {passive:true});

    try { if (passWatcher) clearInterval(passWatcher); } catch(e){}
    passValLast = el.value;
    passWatcher = setInterval(()=>{
      const cur = el.value;
      if (cur !== passValLast) { passValLast = cur; onAny(); }
    }, 150);
  };

  // ---- API del agente
  // --- reemplaza ensureBanner() ---
const ensureBanner = () => {
  let b = document.getElementById("__guide_banner");
  if (!b) {
    b = document.createElement("div");
    b.id="__guide_banner";
    // baja el banner un poco (ajusta 88 si quieres)
    const OFFSET = 88; 
    Object.assign(b.style,{
      position:"fixed",
      top: OFFSET + "px",
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



  window.__bank = {
    banner: (html) => { ensureBanner().innerHTML = html; },

    showDniStrong: () => {
      currentStep = "dni";
      retry(()=>{ const el = findDni(); if (!el) return false; bindDniInput(); return focusEnableHighlight(el); });
      // Mientras estemos en DNI, re-aplica highlight por si la SPA cambia el nodo
      if (!window.__dniHighlighter) {
        window.__dniHighlighter = setInterval(()=>{ if (currentStep==="dni"){ const el=findDni(); if (el) ensureHighlight(el); } }, 400);
      }
    },
    showPassStrong: () => {
      currentStep = "pass";
      retry(()=>{ const el = findPass(); if (!el) return false; bindPassInput(); return focusEnableHighlight(el); });
      if (!window.__passHighlighter) {
        window.__passHighlighter = setInterval(()=>{ if (currentStep==="pass"){ const el=findPass(); if (el) ensureHighlight(el); } }, 400);
      }
    },

    fillDni: (value) => {
      const el = findDni(); if (!el) return;
      dniProgrammaticSetAt = Date.now();
      nativeSetValue(el, value); fireInputEvents(el); ensureHighlight(el);
    },

    fillPass: (value) => {
      const el = findPass(); if (!el) return;
      el.removeAttribute("disabled");
      nativeSetValue(el, value); fireInputEvents(el); ensureHighlight(el);
    },

    fillPassStrong: (value) => {
      currentStep = "pass";
      retry(()=>{ 
        const el = findPass(); if (!el) return false;
        el.removeAttribute("disabled");
        nativeSetValue(el, value);
        fireInputEvents(el);
        ensureHighlight(el);
        bindPassInput();
        try {
          if (passTimer) clearTimeout(passTimer);
          passTimer = setTimeout(()=>{ try{ AndroidBank.onPassTypingIdle(); }catch(e){} }, PASS_IDLE_MS);
        } catch(e){}
        return true;
      });
    },

    showSubmit: () => { currentStep="submit"; const el = findAcceder(); if (el){ ensureHighlight(el); } },
    clickSubmit: () => { const el = findAcceder(); if (el){ 
      try { el.dispatchEvent(new MouseEvent("mousedown",{bubbles:true})); } catch(e){}
      el.click?.();
      try { el.dispatchEvent(new MouseEvent("mouseup",{bubbles:true})); } catch(e){}
    } },
  };
  
  ensureBanner();

  // ---- Observador DOM (re-binds y highlight)
  const mo = new MutationObserver(()=>{
    if (currentStep === "dni") {
      bindDniInput();
      const el = findDni(); if (el) ensureHighlight(el);
    } else if (currentStep === "pass") {
      bindPassInput();
      const el = findPass(); if (el) ensureHighlight(el);
    }
  });
  mo.observe(document.documentElement || document.body, { childList:true, subtree:true });

  AndroidBank.agentReady();
})();
""".trimIndent()








        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null)
        else webView.loadUrl("javascript:$js")
    }

    // =================== Flujo ===================

    private fun showDni() {
        step = Step.DNI
        evalJs("""window.__bank && __bank.showDniStrong();""", true)
        mainHandler.postDelayed({ evalJs("""window.__bank && __bank.showDniStrong();""") }, 600)
        speakThen("<b>Introduce tu DNI o N I E.</b> Pulsa el micrófono para dictarlo o escríbelo.")
    }

    private fun askPass() {
        step = Step.PASS
        evalJs("""window.__bank && __bank.showPassStrong();""", true)
        mainHandler.postDelayed({ evalJs("""window.__bank && __bank.showPassStrong();""") }, 600)
        speakThen("<b>Introduce tu contraseña.</b> Puedes escribirla o dictarla tras pulsar el micrófono. No la repetiré en voz alta.")
    }



    private fun askSubmit() {
        step = Step.SUBMIT
        evalJs("""__bank && __bank.showSubmit();""", true)
        speakThen("<b>Listo.</b> Pulsa <b>Acceder</b> o di: <b>pulsa por mí</b>.")
    }

    private fun reListenCurrent() {
        when (step) {
            Step.DNI   -> listenDni()
            Step.PASS  -> listenPass()
            Step.SUBMIT-> listenSubmit()
        }
    }

    // =================== TTS/STT helpers ===================

    private fun speakThen(textHtml: String) {
        lastBanner = textHtml
        banner(textHtml)
        if (suppressSpeakOnce) {
            suppressSpeakOnce = false
            return
        }
        val uttId = UUID.randomUUID().toString()
        val plain = textHtml.replace(Regex("<[^>]+>"), " ")
        runCatching { tts.stop() }
        tts.speak(plain, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    private fun banner(html: String) {
        val safe = JSONObject.quote(html)
        evalJs("""if (window.__bank) { __bank.banner($safe); }""", queueIfNotReady = true)
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
            override fun onError(error: Int) {}
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

    // =================== Pasos de voz ===================


    private fun listenPass() {
        listenOnce { saidRaw ->
            suppressSpeakOnce = true
            val pass = normalizePasswordSpeech(saidRaw)
            evalJs("""window.__bank && __bank.fillPassStrong(${JSONObject.quote(pass)});""", true)
            speakThen("<i>Contraseña recibida.</i>")
            askSubmit()
        }
    }



    /** Convierte dictado en español a una contraseña con mayúsculas, minúsculas, dígitos y símbolos. */
    private fun normalizePasswordSpeech(input: String): String {
        val s = input.lowercase(Locale.ROOT)
            .replace(Regex("[áà]"), "a")
            .replace(Regex("[éè]"), "e")
            .replace(Regex("[íì]"), "i")
            .replace(Regex("[óò]"), "o")
            .replace(Regex("[úù]"), "u")
            .replace(Regex("[^a-z0-9\\s]"), " ") // limpieza conservadora

        val sym = mapOf(
            "arroba" to "@",
            "punto" to ".",
            "coma" to ",",
            "guion" to "-",
            "guion bajo" to "_",
            "subrayado" to "_",
            "barra" to "/",
            "barra invertida" to "\\",
            "dos puntos" to ":",
            "punto y coma" to ";",
            "exclamacion" to "!",
            "admiracion" to "!",
            "interrogacion" to "?",
            "mas" to "+",
            "menos" to "-",
            "igual" to "=",
            "asterisco" to "*",
            "almohadilla" to "#",
            "numeral" to "#",
            "dolar" to "$",
            "porcentaje" to "%",
            "ampersand" to "&",
            "comillas" to "\"",
            "comilla simple" to "'",
            "espacio" to " "
        )

        val letters = ('a'..'z').associateBy({ it.toString() }, { it })
        val numbers = mapOf(
            "cero" to '0',"uno" to '1',"dos" to '2',"tres" to '3',"cuatro" to '4',
            "cinco" to '5',"seis" to '6',"siete" to '7',"ocho" to '8',"nueve" to '9'
        )

        // tokens con soporte a bigramas ("guion bajo", "punto y coma", "dos puntos", "comilla simple")
        val raw = s.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            val a = raw[i]
            val b = if (i+1 < raw.size) raw[i+1] else null
            val two = if (b!=null) "$a $b" else null
            when {
                two!=null && sym.containsKey(two) -> { tokens += two; i += 2 }
                else -> { tokens += a; i += 1 }
            }
        }

        val out = StringBuilder()
        var forceUpper = false
        var forceLower = false

        var j = 0
        while (j < tokens.size) {
            val t = tokens[j]

            when (t) {
                "mayuscula","mayusculas" -> { forceUpper = true; forceLower = false; j++; continue }
                "minuscula","minusculas" -> { forceLower = true; forceUpper = false; j++; continue }
            }

            when {
                // símbolos nombrados
                sym.containsKey(t) -> { out.append(sym[t]); j++ }

                // dígitos por nombre o ya numéricos
                numbers.containsKey(t) -> { out.append(numbers[t]); j++ }
                t.length == 1 && t[0].isDigit() -> { out.append(t); j++ }

                // letras por nombre o literal
                letters.containsKey(t) -> {
                    val ch = letters[t]!!
                    out.append(if (forceUpper) ch.uppercase() else ch.toString())
                    if (forceUpper) forceUpper = false
                    j++
                }
                t.length == 1 && t[0].isLetter() -> {
                    val ch = t[0]
                    out.append(if (forceUpper) ch.uppercaseChar() else if (forceLower) ch.lowercaseChar() else ch)
                    if (forceUpper) forceUpper = false
                    j++
                }

                else -> { j++ } // ignora ruido ("y", etc.)
            }
        }

        return out.toString()
    }



    private fun listenDni() {
        listenOnce { saidRaw ->
            val clean = normalizeDniSpeech(saidRaw)
            // Rellena y pasa a contraseña
            evalJs("""__bank && __bank.fillDni(${JSONObject.quote(clean)});""", true)
            askPass()
        }
    }

    private fun repeatPromptAndRehighlight() {
        lastBanner?.let { html ->
            // re-highlight del paso actual
            when (step) {
                Step.DNI  -> evalJs("""window.__bank && __bank.showDniStrong();""")
                Step.PASS -> evalJs("""window.__bank && __bank.showPassStrong();""")
                Step.SUBMIT -> evalJs("""window.__bank && __bank.showSubmit();""")
            }
            // fuerza TTS (ignora suppressSpeakOnce)
            val plain = html.replace(Regex("<[^>]+>"), " ")
            runCatching { tts.stop() }
            tts.speak(plain, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }


    /** Convierte español hablado a dígitos (48 25 -> "4825", "cuarentayochoveinticinco" -> "4825") */
    private fun normalizeDniSpeech(input: String): String {
        var s = input.lowercase(Locale.ROOT)
            .replace(Regex("[áà]"), "a")
            .replace(Regex("[éè]"), "e")
            .replace(Regex("[íì]"), "i")
            .replace(Regex("[óò]"), "o")
            .replace(Regex("[úù]"), "u")
            .replace(Regex("[^a-z0-9\\s#-]"), "") // limpia rarezas, deja dígitos si ya vinieran

        // Separa pegados típicos: "treintay", "cuarentay", "veinticinco"…
        val tens = listOf("veinte","treinta","cuarenta","cincuenta","sesenta","setenta","ochenta","noventa")
        tens.forEach { t ->
            s = s.replace(Regex("${t}y(?=[a-z])"), "$t y ")
        }
        // “veintiuno”..“veintinueve” => “veinte y uno”..“veinte y nueve”
        s = s.replace(Regex("veinti(?=[a-z])"), "veinte y ")

        // Tokeniza
        val tokens = s.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Mapas básicos
        val units = mapOf(
            "cero" to 0,"uno" to 1,"dos" to 2,"tres" to 3,"cuatro" to 4,"cinco" to 5,
            "seis" to 6,"siete" to 7,"ocho" to 8,"nueve" to 9
        )
        val specials = mapOf(
            "diez" to 10,"once" to 11,"doce" to 12,"trece" to 13,"catorce" to 14,"quince" to 15
        )
        val tensMap = mapOf(
            "diez" to 10,"veinte" to 20,"treinta" to 30,"cuarenta" to 40,"cincuenta" to 50,
            "sesenta" to 60,"setenta" to 70,"ochenta" to 80,"noventa" to 90
        )

        fun parseTwoDigits(i0: Int): Pair<Int, Int>? {
            var i = i0
            if (i >= tokens.size) return null
            val t = tokens[i]

            // Dígitos tal cual
            if (t.all { it.isDigit() }) return Pair(t.toIntOrNull() ?: return null, i + 1)

            // Especiales 10..15
            specials[t]?.let { return Pair(it, i + 1) }

            // Veinte + (y + uno..nueve)
            if (t == "veinte") {
                if (i + 2 < tokens.size && tokens[i + 1] == "y" && units.containsKey(tokens[i + 2])) {
                    val v = 20 + units[tokens[i + 2]]!!
                    return Pair(v, i + 3)
                }
                return Pair(20, i + 1)
            }

            // Treinta..noventa (+ y + uno..nueve)
            if (tensMap.containsKey(t) && tensMap[t]!! >= 30) {
                val base = tensMap[t]!!
                if (i + 2 < tokens.size && tokens[i + 1] == "y" && units.containsKey(tokens[i + 2])) {
                    val v = base + units[tokens[i + 2]]!!
                    return Pair(v, i + 3)
                }
                return Pair(base, i + 1)
            }

            // Unidades
            units[t]?.let { return Pair(it, i + 1) }

            return null
        }

        val out = StringBuilder()

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]

            when {
                // Si el token ya es numérico, lo añadimos tal cual
                t.all { it.isDigit() } -> {
                    out.append(t)
                    i++
                }
                // “guion” -> “-” (por si alguien lo dicta)
                t == "guion" -> {
                    out.append('-'); i++
                }
                // intenta parsear número 0..99
                else -> {
                    val parsed = parseTwoDigits(i)
                    if (parsed != null) {
                        out.append(parsed.first.toString())
                        i = parsed.second
                    } else {
                        // Deja letras (posible letra del DNI) como están, pero sin espacios
                        if (t.length == 1 && t[0].isLetter()) out.append(t.uppercase())
                        i++
                    }
                }
            }
        }

        return out.toString()
    }


    private fun listenSubmit() {
        listenOnce { said ->
            when {
                "pulsa" in said || "entrar" in said || "acceder" in said -> {
                    evalJs("""__bank && __bank.clickSubmit();""", true)
                }
                "atrás" in said || "volver" in said -> {
                    showDni()
                }
                else -> {
                    speakThen("Di <b>pulsa por mí</b> o toca <b>Acceder</b>.")
                }
            }
        }
    }
}
