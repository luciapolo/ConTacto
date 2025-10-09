class GuideAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        val targets = findByText(root, listOf("Cita Previa", "Continuar", "Confirmar"))
        GuideOverlay.show(this, targets.mapNotNull { it.getBoundsInScreen() })
    }
    override fun onInterrupt() {}


    private fun findByText(node: AccessibilityNodeInfo, keys: List<String>): List<AccessibilityNodeInfo> = buildList {
        if (node.text?.let { t -> keys.any { t.contains(it, ignoreCase = true) } } == true) add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(findByText(it, keys)) }
    }
}