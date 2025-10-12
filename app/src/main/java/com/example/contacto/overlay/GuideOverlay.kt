package com.example.contacto.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Rect

object GuideOverlay {
    const val ACTION_SAY  = "com.example.contacto.overlay.SAY"
    const val ACTION_STEP = "com.example.contacto.overlay.STEP"

    fun start(context: Context, firstText: String) {
        context.startService(
            Intent(context, OverlayService::class.java)
                .putExtra("firstText", firstText)
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, OverlayService::class.java))
    }

    fun say(context: Context, text: String) {
        context.sendBroadcast(
            Intent(ACTION_SAY)
                .setPackage(context.packageName)
                .putExtra("text", text)
        )
    }

    // Por ahora solo actualizamos texto; si luego quieres dibujar rectángulos,
    // ampliaremos OverlayService para recibir una lista de Rect parcelables.
    fun show(context: Context, rects: List<Rect>) {
        context.sendBroadcast(
            Intent(ACTION_STEP)
                .setPackage(context.packageName)
                .putExtra("text", "Sigue las indicaciones en la página")
        )
    }
}
