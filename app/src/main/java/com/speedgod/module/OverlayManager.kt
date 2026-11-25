package com.speedgod.module
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.widget.*
import java.util.Locale
import kotlin.math.*

object OverlayManager {
    private const val TAG = "SpeedGod_Root_V1"
    private val handler = Handler(Looper.getMainLooper())
    private var xFrac = 0.03f
    private var yFrac = 0.25f
    private var expanded = false
    private var dimmed = false

    fun attachToActivity(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        handler.post {
            try {
                val decor = activity.window?.decorView as? ViewGroup ?: return@post
                decor.findViewWithTag<View>(TAG)?.let { decor.removeView(it) }
                val root = buildUi(activity, decor).apply { tag = TAG }
                decor.addView(root, FrameLayout.LayoutParams(-1, -1))
            } catch (_: Throwable) {}
        }
    }
    fun detachFromActivity(activity: Activity) {
        handler.post {
            try { (activity.window?.decorView as? ViewGroup)?.let { d -> d.findViewWithTag<View>(TAG)?.let { d.removeView(it) } } } catch (_: Throwable) {}
        }
    }
    private fun buildUi(ctx: Context, decor: ViewGroup): FrameLayout {
        val root = FrameLayout(ctx).apply { isClickable = false; isFocusable = false; elevation = dp(ctx, 24f).toFloat() }
        val pill = TextView(ctx).apply {
            text = fmtPill(NativeSpeedBridge.currentSpeed)
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 22f).toFloat(); setColor(Color.parseColor("#E60F172A")); setStroke(dp(ctx, 1.5f), Color.parseColor("#22D3EE")) }
            setPadding(dp(ctx, 13f), dp(ctx, 8f), dp(ctx, 13f), dp(ctx, 8f))
            alpha = if (dimmed) 0.30f else 0.94f
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 18f).toFloat(); setColor(Color.parseColor("#F00F172A")); setStroke(dp(ctx, 1.5f), Color.parseColor("#334155")) }
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        titleCol.addView(TextView(ctx).apply { text = "⚡ SpeedGod"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD })
        titleCol.addView(TextView(ctx).apply {
            text = "● ${NativeSpeedBridge.statusText}"
            setTextColor(if (NativeSpeedBridge.isNativeLoaded) Color.parseColor("#34D399") else Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        })
        header.addView(titleCol)
        header.addView(chip(ctx, "✕", "#1E293B", "#CBD5E1") { expanded = false; panel.visibility = View.GONE; pill.visibility = View.VISIBLE })
        panel.addView(header)

        val spdText = TextView(ctx).apply {
            text = fmtBig(NativeSpeedBridge.currentSpeed); setTextColor(Color.parseColor("#22D3EE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val slider = SeekBar(ctx).apply { max = 100; progress = toProg(NativeSpeedBridge.currentSpeed) }
        fun setSpd(v: Double, updSlider: Boolean = true) {
            val r = if (v == 0.0) 0.0 else (v * 100.0).roundToInt() / 100.0
            NativeSpeedBridge.setSpeed(r); spdText.text = fmtBig(r); pill.text = fmtPill(r)
            if (updSlider) slider.progress = toProg(r)
        }
        val stepRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stepRow.addView(chip(ctx, "−0.5", "#1E293B", "#F8FAFC") { setSpd((NativeSpeedBridge.currentSpeed - 0.5).coerceAtLeast(0.0)) })
        stepRow.addView(spdText)
        stepRow.addView(chip(ctx, "+0.5", "#1E293B", "#F8FAFC") { setSpd((NativeSpeedBridge.currentSpeed + 0.5).coerceAtMost(50.0)) })
        panel.addView(stepRow)

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) setSpd(fromProg(p), false) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        panel.addView(slider)

        for (rowItems in listOf(listOf("⏸ 0x" to 0.0, "0.25x" to 0.25, "0.5x" to 0.5, "1x" to 1.0), listOf("2x" to 2.0, "3x" to 3.0, "5x" to 5.0, "10x" to 10.0, "20x" to 20.0))) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 4f), 0, 0) }
            for ((lbl, valSpd) in rowItems) {
                val btn = chip(ctx, lbl, if (valSpd == 1.0) "#0E7490" else "#1E293B", "#F8FAFC") { setSpd(valSpd) }
                row.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(ctx, 2f), 0, dp(ctx, 2f), 0) })
            }
            panel.addView(row)
        }

        val togRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 6f), 0, 0) }
        lateinit var rtB: TextView
        rtB = chip(ctx, "RT:${if (NativeSpeedBridge.hookRealtime) "ON" else "OFF"}", "#164E63", "#67E8F9") {
            val n = !NativeSpeedBridge.hookRealtime; NativeSpeedBridge.setHookRealtime(n); rtB.text = "RT:${if (n) "ON" else "OFF"}"
        }
        lateinit var slB: TextView
        slB = chip(ctx, "Sleep:${if (NativeSpeedBridge.hookSleep) "ON" else "OFF"}", "#1E293B", "#94A3B8") {
            val n = !NativeSpeedBridge.hookSleep; NativeSpeedBridge.setHookSleep(n); slB.text = "Sleep:${if (n) "ON" else "OFF"}"
        }
        lateinit var dmB: TextView
        dmB = chip(ctx, "Прозр", "#1E293B", "#94A3B8") {
            dimmed = !dimmed; pill.alpha = if (dimmed) 0.30f else 0.94f
        }
        for (b in listOf(rtB, slB, dmB)) togRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(ctx, 2f), 0, dp(ctx, 2f), 0) })
        panel.addView(togRow)

        root.addView(pill, FrameLayout.LayoutParams(-2, -2))
        root.addView(panel, FrameLayout.LayoutParams(dp(ctx, 286f), -2).apply { gravity = Gravity.CENTER })
        pill.visibility = if (expanded) View.GONE else View.VISIBLE
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        root.post {
            val mw = (decor.width - pill.width).coerceAtLeast(1); val mh = (decor.height - pill.height).coerceAtLeast(1)
            pill.x = (xFrac * mw).coerceIn(0f, mw.toFloat()); pill.y = (yFrac * mh).coerceIn(dp(ctx, 28f).toFloat(), mh.toFloat())
        }
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var dx0 = 0f; var dy0 = 0f; var sx = 0f; var sy = 0f; var drag = false; var t0 = 0L
        pill.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; sx = v.x; sy = v.y; drag = false; t0 = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> {
                    if (!drag && (abs(e.rawX - dx0) > slop || abs(e.rawY - dy0) > slop)) drag = true
                    if (drag) {
                        val mw = (decor.width - v.width).coerceAtLeast(1).toFloat(); val mh = (decor.height - v.height).coerceAtLeast(1).toFloat()
                        v.x = (sx + e.rawX - dx0).coerceIn(0f, mw); v.y = (sy + e.rawY - dy0).coerceIn(0f, mh)
                        xFrac = v.x / mw; yFrac = v.y / mh
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!drag) {
                        if (System.currentTimeMillis() - t0 >= 550L) { setSpd(1.0) }
                        else { expanded = true; pill.visibility = View.GONE; panel.visibility = View.VISIBLE }
                    }; true
                }
                else -> false
            }
        }
        return root
    }
    private fun chip(ctx: Context, lbl: String, bg: String, fg: String, click: () -> Unit) = TextView(ctx).apply {
        text = lbl; setTextColor(Color.parseColor(fg)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(ctx, 10f).toFloat(); setColor(Color.parseColor(bg)) }
        setPadding(dp(ctx, 7f), dp(ctx, 7f), dp(ctx, 7f), dp(ctx, 7f)); setOnClickListener { click() }
    }
    private fun fromProg(p: Int): Double { val r = 0.1 * 250.0.pow(p / 100.0); return if (r < 1) (r * 20).roundToInt() / 20.0 else if (r < 5) (r * 10).roundToInt() / 10.0 else (r * 2).roundToInt() / 2.0 }
    private fun toProg(s: Double): Int = if (s <= 0.1) 0 else if (s >= 25.0) 100 else ((ln(s / 0.1) / ln(250.0)) * 100).roundToInt()
    private fun fmtPill(s: Double) = if (s == 0.0) "⏸ 0x" else String.format(Locale.US, "⚡ %.2gx", s)
    private fun fmtBig(s: Double) = if (s == 0.0) "PAUSED (0x)" else String.format(Locale.US, "%.2fx", s)
    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).roundToInt()
}
