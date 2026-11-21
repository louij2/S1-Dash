package com.louij2.s1dash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * A small round "S1" button drawn over other apps while S1 Dash is in the background, so one tap
 * brings it back from CarPlay (#1060). Draggable; remembers where it was left. Needs the
 * "draw over other apps" permission, which Settings in the UI offers to open.
 */
object ReturnBubble {
    private var view: TextView? = null

    fun allowed(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context) {
        if (view != null || !allowed(ctx)) return
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = app.resources.displayMetrics.density
        val size = (64 * density).toInt()
        val prefs = DataHub.prefs
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (16 * density).toInt())
            y = prefs.getInt(KEY_Y, (120 * density).toInt())
        }
        val v = TextView(app).apply {
            text = "S1"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6D1102A.toInt())
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 8 * density
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > SLOP * density || abs(dy) > SLOP * density) dragged = true
                    if (dragged) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    else backToDash(app)
                    true
                }
                else -> false
            }
        }
        try { wm.addView(v, lp); view = v } catch (_: Exception) { view = null }
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        view = null
        try { (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }

    private fun backToDash(ctx: Context) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private const val KEY_X = "bubbleX"
    private const val KEY_Y = "bubbleY"
    private const val SLOP = 10 // dp before a touch counts as a drag
}
