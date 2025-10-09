package com.example.contacto.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.contacto.overlay.GuideOverlay

class GuideAccessibilityService : AccessibilityService() {

    // Navegadores soportados
    private val supportedPkgs = setOf(
        "com.android.chrome",
        "com.google.android.apps.chrome",
        "com.microsoft.emmx",
        "org.mozilla.firefox"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in supportedPkgs) return

        val root = rootInActiveWindow ?: return

        // Solo actuamos si detectamos contenido del SESCAM
        val page = collectText(root).lowercase()
        if (!page.contains("sescam")) return

        // Objetivos básicos
        val keys = listOf("Cita Previa", "Cita", "Continuar", "Confirmar", "Siguiente", "Entrar")
        val targets = findByText(root, keys)

        // Enviar rectángulos (placeholder) y voz
        val rects = targets.map { node -> Rect().also { node.getBoundsInScreen(it) } }
        GuideOverlay.show(this, rects)

        val msg = when {
            page.contains("cita previa", true)             -> "Pulsa en Cita Previa."
            page.contains("dni") || page.contains("nif")   -> "Escribe tu DNI o NIF."
            page.contains("cip") || page.contains("tarjeta sanitaria") -> "Escribe tu CIP o número de tarjeta sanitaria."
            page.contains("fecha")                          -> "Selecciona tu fecha de nacimiento."
            else                                            -> "Sigue las instrucciones en pantalla."
        }
        GuideOverlay.say(this, msg)
    }

    override fun onInterrupt() { /* no-op */ }

    private fun collectText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        fun dfs(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (!n.text.isNullOrBlank()) sb.append(n.text).append(' ')
            for (i in 0 until n.childCount) dfs(n.getChild(i))
        }
        dfs(node)
        return sb.toString()
    }

    private fun findByText(node: AccessibilityNodeInfo?, keys: List<String>): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        if (!node.text.isNullOrBlank()) {
            val t = node.text.toString()
            if (keys.any { k -> t.contains(k, ignoreCase = true) }) out.add(node)
        }
        for (i in 0 until node.childCount) out.addAll(findByText(node.getChild(i), keys))
        return out
    }
}
