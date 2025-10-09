package com.example.contacto.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.contacto.overlay.GuideOverlay

class GuideAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return

        val targets = findByText(root, listOf("Cita Previa", "Continuar", "Confirmar"))

        // AccessibilityNodeInfo.getBoundsInScreen(Rect) -> hay que pasar un Rect y rellenarlo
        val rects = targets.map { node ->
            Rect().also { node.getBoundsInScreen(it) }
        }

        // El servicio es un Context, así que this vale como Context
        GuideOverlay.show(this, rects)
    }

    override fun onInterrupt() { /* no-op */ }

    private fun findByText(
        node: AccessibilityNodeInfo?,
        keys: List<String>
    ): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()

        val text = node.text?.toString().orEmpty()
        if (keys.any { key -> text.contains(key, ignoreCase = true) }) {
            out.add(node)
        }

        for (i in 0 until node.childCount) {
            out.addAll(findByText(node.getChild(i), keys))
        }
        return out
    }
}
