# ConTacto

Aplicación Android pensada para acompañar a personas mayores en el uso de etiquetas NFC, accesos web sanitarios (SESCAM) y banca online (Ruralvía). Combina Jetpack Compose con servicios de accesibilidad, guías habladas y utilidades para escribir y leer tarjetas NFC de forma segura.

## Características principales

- **Inicio guiado**: pantalla Compose con accesos directos para leer etiquetas al instante, reescribirlas o abrir ajustes rápidos (`MainActivity`, `HomeScreen`).
- **Lectura inteligente de NFC**: interpreta `tel:` y URLs, redirige a guías asistidas o al navegador y solicita permisos de llamada cuando es necesario (`NfcReadNowActivity`, `NfcReaderActivity`, `NfcRouterActivity`, `MainActivity`).
- **Escritura asistida de etiquetas**: asistente visual para grabar números de teléfono o URLs, con selector de contactos, accesos rápidos y validación del tamaño del tag (`NfcRewriteActivity`, `NfcRewriteScreen`).
- **Guías web accesibles**: flujos específicos para SESCAM y Ruralvía con TTS/STT, resalte de campos, repetición de pasos y escucha por voz (`SescamGuideActivity`, `BankGuideActivity`).
- **Overlay y accesibilidad**: servicio de accesibilidad que detecta pasos clave en navegadores compatibles y muestra un overlay con instrucciones habladas (`GuideAccessibilityService`, `OverlayService`, `GuideOverlay`).
- **Persistencia y ajustes**: `DataStore` para recordar el estado del alias NFC externo y aplicar el cambio al arrancar la app (`SettingsRepository`, `App`, `NfcSettingsScreen`).

## Arquitectura rápida

| Capa | Contenido |
| --- | --- |
| UI Compose | `HomeScreen`, pantallas de ajustes y escritor NFC. |
| Activities nativas | Gestión de NFC, WebView guiado, voz y overlays. |
| Datos | `SettingsRepository` + `DataStore` para preferencias persistentes. |
| Servicios | AccessibilityService + overlay flotante + voz (`VoiceAgent`). |

El proyecto usa Kotlin 2.0, Android Gradle Plugin 8.5.2 y la BOM de Compose `2024.10.00`. Existe un único módulo (`app`) con `namespace` `com.example.contacto`.

## Requisitos

- Android Studio Koala (o más reciente) con Gradle 8.8+.
- JDK 17 (configurado en el IDE o en `JAVA_HOME`).
- Dispositivo o emulador con Android 8.0 (API 26) o superior; se recomienda hardware real con NFC.
- Permiso manual de *draw over other apps* para mostrar el overlay y activación del servicio de accesibilidad en Ajustes.
- Micrófono y conectividad a Internet para las guías por voz.

## Puesta en marcha

1. Clona el repositorio y ábrelo en Android Studio (`File > Open` sobre la carpeta `ConTacto`).
2. Sincroniza Gradle y espera a que se resuelvan las dependencias.
3. Opcional: desde terminal puedes compilar con:
   ```bash
   ./gradlew assembleDebug
   ```
4. Ejecuta en un dispositivo con NFC. La primera vez que abras funcionalidades avanzadas, concede los permisos solicitados (NFC, micrófono, contactos, overlay, llamadas).
5. En `Ajustes > Leer etiquetas fuera de la app`, activa el alias si quieres que cualquier tag lance directamente ConTacto (`SettingsActivity`, `NfcReaderAlias`).

## Funcionalidades clave y flujos

| Flujo | Descripción | Código relevante |
| --- | --- | --- |
| Lectura inmediata | Usa `NfcReadNowActivity` con *reader mode* para interpretar tags y dirigir a la acción adecuada (llamada, guía asistida, navegador). | `app/src/main/java/com/example/contacto/nfc/NfcReadNowActivity.kt` |
| Alias externo NFC | Permite que URLs o `tel:` lanzados desde tags abran la guía correspondiente sin tener la app en primer plano. | `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/example/contacto/nfc/NfcAliasToggler.kt`, `app/src/main/java/com/example/contacto/MainActivity.kt` |
| Reescritura de tags | Compose UI para preparar payloads (teléfono/URL), elegir contacto y validar memoria del tag antes de escribir. | `app/src/main/java/com/example/contacto/nfc/NfcRewriteActivity.kt` |
| Guía SESCAM | WebView con inyección JS, TTS/STT y overlay para pasos de Mi Salud Digital. | `app/src/main/java/com/example/contacto/web/SescamGuideActivity.kt` |
| Guía banca online | Asistente por voz para Ruralvía (`#/login`) que nunca repite la contraseña en voz alta. | `app/src/main/java/com/example/contacto/web/BankGuideActivity.kt` |
| Overlay accesible | Servicio flotante con botones de “Repetir” y navegación por pasos; recibe instrucciones del servicio de accesibilidad. | `app/src/main/java/com/example/contacto/overlay/OverlayService.kt`, `app/src/main/java/com/example/contacto/overlay/GuideOverlay.kt`, `app/src/main/java/com/example/contacto/accessibility/GuideAccessibilityService.kt` |

## Permisos y servicios especiales

- `android.permission.NFC`, `SYSTEM_ALERT_WINDOW`, `RECORD_AUDIO`, `CALL_PHONE`, `READ_CONTACTS`, `INTERNET`, `VIBRATE`.
- Servicio de accesibilidad (`GuideAccessibilityService`) declarado mediante `res/xml/accessibility_config.xml`.
- Overlay (`OverlayService`) que requiere habilitar la superposición manual desde Ajustes del dispositivo.
- Uso de `SpeechRecognizer` y `TextToSpeech`; la app solicita permisos de audio en tiempo de ejecución.

## Estructura de carpetas

```text
app/
├── src/main/java/com/example/contacto/
│   ├── accessibility/   # Servicio de accesibilidad y utilidades overlay
│   ├── data/            # DataStore + ajustes
│   ├── nfc/             # Lectura, escritura y routing de NFC
│   ├── overlay/         # Servicio flotante y fachada GuideOverlay
│   ├── ui/              # Pantallas Compose y tema
│   ├── voice/           # Agente de voz TTS/STT reutilizable
│   └── web/             # Guías asistidas en WebView
└── src/main/res/        # Recursos XML (layouts, strings, config)
```

## Desarrollo y pruebas

- Usa `./gradlew lintDebug` y `./gradlew test` para comprobar el estado del código (no hay tests unitarios todavía).
- Para QA manual, valida:
  - Escritura de tags con payloads pequeños y grandes (manejo de errores de memoria).
  - Lectura en frío vía alias externo con el toggle activado/desactivado.
  - Guías SESCAM/Ruralvía con micrófono concedido y denegado.
  - Overlay sobre Chrome/Firefox comprobando que el servicio de accesibilidad detecta los pasos.

## Próximos pasos sugeridos

- Añadir pruebas instrumentadas para flujos de `DataStore` y navegación Compose.
- Externalizar textos largos de voz a recursos para localización.
- Documentar políticas de privacidad y tratamiento de datos de voz
