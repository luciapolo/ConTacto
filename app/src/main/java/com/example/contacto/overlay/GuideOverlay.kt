import android.content.Context

object GuideOverlay {
    private var windowManager: WindowManager? = null
    private var view: View? = null


    fun show(ctx: Context, rects: List<Rect>) {
        val wm = windowManager ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        val overlay = view ?: View(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }.also { view = it }


        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(overlay, params)


// TODO: dibujar rectángulos (usa un CustomView y onDraw(Canvas))
    }
}