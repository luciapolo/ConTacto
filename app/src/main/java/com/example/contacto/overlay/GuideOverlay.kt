package com.example.contacto.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.os.Build

object GuideOverlay {
    private var windowManager: WindowManager? = null
    private var view: HighlightView? = null

    fun show(ctx: Context, rects: List<Rect>) {
        val wm = windowManager ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .also { windowManager = it }

        val overlay = view ?: HighlightView(ctx).also { view = it }

        overlay.rects = rects
        overlay.invalidate()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        if (overlay.parent == null) {
            wm.addView(overlay, params)
        } else {
            wm.updateViewLayout(overlay, params)
        }
    }

    fun hide() {
        view?.let { v ->
            windowManager?.removeViewImmediate(v)
        }
        view = null
        windowManager = null
    }

    private class HighlightView(context: Context) : View(context) {
        var rects: List<Rect> = emptyList()
        private val paint = Paint().apply {
            style = Paint.STROKE
            strokeWidth = 8f
            color = Color.RED
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            rects.forEach { canvas.drawRect(it, paint) }
        }
    }
}
