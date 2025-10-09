package com.example.elder.deeplink


sealed class Route(val path: String) {
    data object Home: Route("home")
    data object Call: Route("call")
    data object Sescam: Route("sescam")
    data object WhatsApp: Route("wa")
}