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
                    // Pequeño debounce para evitar rebotes
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
        val url = "https://sescam.jccm.es/misaluddigital/app/inicio"
        dbg("Cargando URL inicial: $url")
        webView.loadUrl(url)
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
        // Limpia WebView para evitar fugas
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

        // Cancela sesión previa por si acaso
        runCatching { stt?.cancel() }

        stt?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { dbg("STT onReadyForSpeech") }
            override fun onBeginningOfSpeech() { dbg("STT onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { dbg("STT onEndOfSpeech") }
            override fun onError(error: Int) {
                dbg("STT onError: $error")
                // Reintento breve sólo 1 vez por UX
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

              const D = (m)=>{ try{ console.debug("[AGENT]", m); AndroidGuide.debug(m); }catch(_){} };

              D("inject start");

              // ===== CSS
              const CSS_ID="__agent_style";
              if (!document.getElementById(CSS_ID)) {
                const st=document.createElement("style"); st.id=CSS_ID;
                st.textContent = `
                  #__agent_overlay{position:fixed;border:4px solid #2dd4bf;border-radius:12px;box-shadow:0 0 0 6px rgba(45,212,191,.35);pointer-events:none;z-index:2147483646;display:none}
                  #__agent_banner{position:fixed;left:16px;right:16px;bottom:16px;background:#111827;color:#fff;padding:12px 16px;border-radius:12px;font-size:16px;z-index:2147483647}
                `;
                document.documentElement.appendChild(st);
                D("style attached");
              }
              function ensure(id){
                let e=document.getElementById(id);
                if(!e){ e=document.createElement("div"); e.id=id; document.body.appendChild(e); D("created "+id); }
                return e;
              }
              const bannerEl=()=>ensure("__agent_banner");
              const overlayEl=()=>ensure("__agent_overlay");
              let currentOverlayTarget=null;
              function banner(text){ bannerEl().textContent = text; D("banner: "+text); }
              function placeOverlay(el){
                const ov=overlayEl();
                if(!el){
                  ov.style.display="none";
                  currentOverlayTarget=null;
                  D("overlay hidden");
                  return;
                }
                const r=el.getBoundingClientRect();
                ov.style.display="block";
                ov.style.left=(r.left-8)+"px";
                ov.style.top =(r.top-8)+"px";
                ov.style.width =(r.width+16)+"px";
                ov.style.height=(r.height+16)+"px";
                currentOverlayTarget = el;
                D("overlay placed l="+ov.style.left+" t="+ov.style.top+" w="+ov.style.width+" h="+ov.style.height);
              }
              ["scroll","resize"].forEach(evt=>{
                window.addEventListener(evt, ()=>{ if(currentOverlayTarget) placeOverlay(currentOverlayTarget) }, {passive:true});
              });

              // ===== Utils
              function norm(s){ return (s||"").toLowerCase().normalize("NFD").replace(/\p{Diacritic}/gu,""); }

              function* walk(root){
                const st=[root];
                while(st.length){
                  const n=st.pop(); if(!n) continue; yield n;
                  if(n.shadowRoot) st.push(n.shadowRoot);
                  if(n.childNodes) for(let i=n.childNodes.length-1;i>=0;--i) st.push(n.childNodes[i]);
                }
              }
              function allClickables(){
                const out=[];
                for(const n of walk(document.body)){
                  if(!(n instanceof Element)) continue;
                  const type=(n.getAttribute("type")||"").toLowerCase();
                  const role=(n.getAttribute("role")||"").toLowerCase();
                  const clickable = n.onclick || n.href || role==="button" ||
                                    n.tagName==="BUTTON" || n.tagName==="A" ||
                                    type==="button" || type==="submit" ||
                                    (n.tabIndex !== undefined && n.tabIndex >= 0);
                  if (clickable) out.push(n);
                }
                D("allClickables count="+out.length);
                return out;
              }
              function closestClickable(el){
                if(!el) return null;
                const c = el.closest?.("button,a,[role=button],input[type=submit],input[type=button]") || el;
                return c;
              }
              function findByWords(words){
                const W=words.map(norm);
                for(const el of allClickables()){
                  const t=norm(el.innerText||el.value||el.ariaLabel||"");
                  if(W.every(w=>t.includes(w))) { D("findByWords HIT: "+t); return el; }
                }
                D("findByWords MISS: "+JSON.stringify(words));
                return null;
              }
              function findByHref(substr){
                substr = (substr||"").toLowerCase();
                for(const el of allClickables()){
                  const href = (el.getAttribute("href")||"").toLowerCase();
                  if (href.includes(substr)) { D("findByHref HIT: "+href); return el; }
                }
                D("findByHref MISS: "+substr);
                return null;
              }

              // Click sintético (touch + pointer + mouse)
              function synthClick(el){
                el = closestClickable(el);
                if(!el){ D("synthClick: no clickable ancestor"); return; }
                D("synthClick on "+(el.outerHTML?.slice(0,120)||el.tagName));
                try { el.scrollIntoView({block:"center"}); } catch(e){}
                try { el.focus({preventScroll:true}); } catch(e){}
                try {
                  const touchInit = {bubbles:true,cancelable:true,composed:true};
                  if (window.TouchEvent) {
                    el.dispatchEvent(new TouchEvent("touchstart", touchInit));
                    el.dispatchEvent(new TouchEvent("touchend", touchInit));
                  } else { D("TouchEvent not supported"); }
                } catch(e){ D("touch seq error: "+e); }
                try {
                  const pe=(t)=>el.dispatchEvent(new PointerEvent(t,{bubbles:true,cancelable:true,composed:true}));
                  const me=(t)=>el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,composed:true}));
                  pe("pointerover"); pe("pointerenter"); pe("pointerdown"); me("mousedown");
                  pe("pointerup");   me("mouseup");      me("click");
                } catch(e){ D("pointer/mouse seq error: "+e); }
                try { el.click?.(); } catch(e){ D("el.click error: "+e); }
              }

              // Targets
              function btnPrimary(){
                const el =
                  findByWords(["cita","atencion","primaria"]) ||
                  findByWords(["cita","primaria"]) ||
                  findByHref("citas/primaria") || findByHref("primaria");
                if(!el) D("btnPrimary NOT FOUND");
                return el;
              }
              function btnFarmacia(){
                const el = findByWords(["farmacia"]) || findByWords(["farmacias"]) || findByHref("farmacia");
                if(!el) D("btnFarmacia NOT FOUND");
                return el;
              }
              function btnWifi(){
                const el = findByWords(["wifi"]) || findByWords(["wi","fi"]) || findByHref("wifi");
                if(!el) D("btnWifi NOT FOUND");
                return el;
              }

              function cipInput(){
                let el=document.querySelector("input[placeholder*='CIP' i], input[placeholder*='Introduzca su CIP' i]");
                if(!el) el=document.querySelector("input[name*='cip' i], input[id*='cip' i]");
                if(!el){
                  const labels=[...document.querySelectorAll("label")].filter(l=>norm(l.textContent).includes("cip"));
                  for(const lb of labels){
                    const forId=lb.getAttribute("for");
                    if(forId){ const c=document.getElementById(forId); if(c) { el=c; break; } }
                    const near=lb.parentElement?.querySelector?.("input"); if(near){ el=near; break; }
                  }
                }
                if(!el) D("cipInput NOT FOUND"); else D("cipInput FOUND");
                return el;
              }
              function btnFinal(act){
                let el=null;
                if (act==="PEDIR") el = findByWords(["pedir","cita"]);
                if (act==="VER")   el = findByWords(["ver","citas"]) || findByWords(["consultar","cita"]);
                if(!el) D("btnFinal("+act+") NOT FOUND"); else D("btnFinal("+act+") FOUND");
                return el;
              }

              function waitFor(name, finder, cb){
                D("waitFor("+name+") start");
                const tryFind = ()=>{
                  const el=finder();
                  if(el){ D("waitFor("+name+") HIT"); cb(el); return true }
                  return false;
                };
                if (tryFind()) return;
                const mo=new MutationObserver(()=>{ if(tryFind()){ mo.disconnect(); D("waitFor("+name+") via MO"); }});
                mo.observe(document,{childList:true,subtree:true,attributes:true,characterData:true});
              }

              // Señales de usuario
              document.addEventListener("click", (e)=>{
                const p = e.target && closestClickable(e.target);
                const label = (p && (p.innerText||p.value||p.ariaLabel)) ? (p.innerText||p.value||p.ariaLabel) : "(no label)";
                D("DOM click on: "+label.slice(0,80));
                if(!p) return;
                const t = (p.innerText||p.value||p.ariaLabel||"").toLowerCase();
                if (t.includes("cita") && (t.includes("atencion")||t.includes("primaria"))) {
                  setTimeout(()=>AndroidGuide.onPrimaryDetected(), 80);
                }
                if ((t.includes("pedir")&&t.includes("cita")) || (t.includes("ver")&&t.includes("citas")) || (t.includes("consultar")&&t.includes("cita"))) {
                  setTimeout(()=>AndroidGuide.onFinalDetected(), 80);
                }
              }, true);

              // Detección por ruta (path o hash)
              let lastHref = location.href;
              function notifyByUrl(){
                const url = location.href; lastHref = url;
                const h = (location.hash||"").toLowerCase();
                const p = (location.pathname||"").toLowerCase();
                D("notifyByUrl: "+url);
                if (h.includes("citas") && h.includes("primaria")) AndroidGuide.onPrimaryDetected();
                if (p.includes("/citas/primaria")) AndroidGuide.onPrimaryDetected();
              }
              window.addEventListener("hashchange", notifyByUrl);
              const _ps = history.pushState; history.pushState = function(a,b,c){ const r=_ps.apply(this,arguments); try{notifyByUrl();}catch(_){} return r; };
              const _rs = history.replaceState; history.replaceState = function(a,b,c){ const r=_rs.apply(this,arguments); try{notifyByUrl();}catch(_){} return r; };
              const urlPoll = setInterval(()=>{ if (location.href !== lastHref) notifyByUrl(); }, 600);
              notifyByUrl();

              // Observador global y polling
              const globalMO = new MutationObserver(()=>{
                if (cipInput()) AndroidGuide.onPrimaryDetected();
              });
              globalMO.observe(document.body, {childList:true,subtree:true,attributes:true,characterData:false});

              setInterval(()=>{
                try{
                  if (btnPrimary()) { /* opcional */ }
                  if (cipInput()) AndroidGuide.onPrimaryDetected();
                }catch(e){ D("poll err: "+e); }
              }, 1000);

              window.__agent = {
                banner: (txt)=>banner(txt),

                // Home
                showPrimary: ()=>{ D("showPrimary called"); waitFor("primary", btnPrimary, el=>{ banner("Pulsa: Cita Atención Primaria"); placeOverlay(el); }); return true; },
                clickPrimary: ()=>{ D("clickPrimary called"); const el=btnPrimary(); if(!el){ D("clickPrimary NO TARGET"); return false; } placeOverlay(el); synthClick(el); return true; },

                showFarmacia: ()=>{ D("showFarmacia called"); waitFor("farm", btnFarmacia, el=>{ banner("Pulsa: Farmacia"); placeOverlay(el); }); return true; },
                clickFarmacia: ()=>{ D("clickFarmacia called"); const el=btnFarmacia(); if(!el){ D("clickFarmacia NO TARGET"); return false; } placeOverlay(el); synthClick(el); return true; },

                showWifi: ()=>{ D("showWifi called"); waitFor("wifi", btnWifi, el=>{ banner("Pulsa: Wi-Fi"); placeOverlay(el); }); return true; },
                clickWifi: ()=>{ D("clickWifi called"); const el=btnWifi(); if(!el){ D("clickWifi NO TARGET"); return false; } placeOverlay(el); synthClick(el); return true; },

                // Cita
                showCip: ()=>{ D("showCip called"); waitFor("cip", cipInput, el=>{ banner("Introduce tu CIP en este campo"); placeOverlay(el); try{el.focus();}catch(e){} }); return true; },
                fillCip: (cip)=>{ D("fillCip called: "+cip); const el=cipInput(); if(!el){ D("fillCip NO TARGET"); return false; } placeOverlay(el); try{el.focus();}catch(e){} el.value=cip; el.dispatchEvent(new Event("input",{bubbles:true})); return true; },

                showFinal: (act)=>{ D("showFinal("+act+") called"); waitFor("final"+act, ()=>btnFinal(act), el=>{ banner(act==="PEDIR"?"Pulsa: Pedir cita":"Pulsa: Ver/Consultar citas"); placeOverlay(el); }); return true; },
                clickFinal: (act)=>{ D("clickFinal("+act+") called"); const el=btnFinal(act); if(!el){ D("clickFinal NO TARGET"); return false; } placeOverlay(el); synthClick(el); return true; }
              };

              AndroidGuide.agentReady();
              D("inject end / agent ready");
            })();
        """.trimIndent()

        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null) else webView.loadUrl("javascript:$js")
        // IMPORTANTE: NO marcar jsReady aquí; esperamos a AndroidGuide.agentReady()
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
