package com.example.contacto.overlay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.ImageButton
import android.widget.TextView
import com.example.contacto.R
import java.util.Locale
import android.widget.FrameLayout

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var tvStep: TextView? = null
    private lateinit var tts: TextToSpeech
    @Volatile private var currentText: String = "Abriendo SESCAM…"

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Receivers (internos de la app) ---
    private val sayReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getStringExtra("text")?.let { updateStep(it) }
        }
    }
    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getStringExtra("text")?.let { updateStep(it) }
        }
    }

    private fun registerLocal(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 32; y = 140
        }

        // Inflate con padre temporal (evita warning de lint)
        val tempParent = FrameLayout(this)
        root = LayoutInflater.from(this).inflate(R.layout.overlay_guide, tempParent, false)
        wm?.addView(root, params)

        // Drag + accesibilidad
        root!!.setOnTouchListener(object : View.OnTouchListener {
            var lastX = 0f; var lastY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; return false }
                    MotionEvent.ACTION_MOVE -> {
                        params.x -= (e.rawX - lastX).toInt()
                        params.y += (e.rawY - lastY).toInt()
                        wm?.updateViewLayout(root, params)
                        lastX = e.rawX; lastY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> v.performClick()
                }
                return false
            }
        })

        tvStep = root!!.findViewById(R.id.tvStep)
        root!!.findViewById<ImageButton>(R.id.btnSpeak).setOnClickListener { speak(currentText) }
        root!!.findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            sendBroadcast(Intent("com.example.contacto.NEXT_STEP"))
        }
        root!!.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            sendBroadcast(Intent("com.example.contacto.PREV_STEP"))
        }

        registerLocal(sayReceiver,  IntentFilter(GuideOverlay.ACTION_SAY))
        registerLocal(stepReceiver, IntentFilter(GuideOverlay.ACTION_STEP))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("firstText")?.let { updateStep(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(sayReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(stepReceiver) } catch (_: Exception) {}
        try { wm?.removeView(root) } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            speak(currentText)
        }
    }

    fun updateStep(text: String) {
        currentText = text
        tvStep?.text = text
        speak(text)
    }

    private fun speak(text: String) {
        if (::tts.isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "overlay")
        }
    }
}
