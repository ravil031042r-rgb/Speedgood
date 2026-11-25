package com.speedgod.module

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object OverlayManager {
    private const val TAG_ROOT = "speedgod_minigg_overlay"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var currentSpeed = 1.0
    @Volatile private var paused = false
    @Volatile private var speedBeforePause = 1.0
    @Volatile private var isExpanded = false
    @Volatile private var activeTab = 0 // 0 = Speed, 1 = Mini GG
    @Volatile private var selectedType = MemScanner.TYPE_DWORD
    @Volatile private var searchText = ""
    @Volatile private var editText = ""
    @Volatile private var activeInput = 0 // 0 = search, 1 = edit
    @Volatile private var numpadVisible = true
    @Volatile private var editingSingleAddr: String? = null
    @Volatile private var badgeX = 24
    @Volatile private var badgeY = 220

    private var currentActivityRef = WeakReference<Activity>(null)

    fun attachToActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val decor = activity.window?.decorView as? ViewGroup ?: return@runOnUiThread
                if (decor.findViewWithTag<View>(TAG_ROOT) != null) return@runOnUiThread
                val root = buildOverlay(activity)
                root.tag = TAG_ROOT
                decor.addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } catch (_: Throwable) {}
        }
    }

    fun detachFromActivity(activity: Activity) {
        activity.runOnUiThread {
            try {
                val decor = activity.window?.decorView as? ViewGroup ?: return@runOnUiThread
                val existing = decor.findViewWithTag<View>(TAG_ROOT) ?: return@runOnUiThread
                decor.removeView(existing)
            } catch (_: Throwable) {}
        }
    }

    private fun applySpeed(v: Double) {
        val clamped = v.coerceIn(0.05, 50.0)
        currentSpeed = clamped
        if (clamped > 0.06) paused = false
        invokeSpeedSetter("com.speedgod.module.NativeSpeedBridge", clamped)
        invokeSpeedSetter("com.speedgod.module.MainHook", clamped)
    }

    private fun invokeSpeedSetter(clsName: String, value: Double): Boolean {
        return try {
            val cls = Class.forName(clsName)
            val target: Any? = try {
                cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            } catch (_: Throwable) {
                try {
                    cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
                } catch (_: Throwable) { null }
            }
            val methods = (cls.declaredMethods.toList() + (target?.javaClass?.declaredMethods?.toList() ?: emptyList())).distinct()
            val candidates = methods.filter { m ->
                val n = m.name.lowercase(Locale.US)
                m.parameterTypes.size == 1 &&
                    (m.parameterTypes[0] == Double::class.javaPrimitiveType ||
                     m.parameterTypes[0] == Float::class.javaPrimitiveType ||
                     m.parameterTypes[0] == java.lang.Double::class.java ||
                     m.parameterTypes[0] == java.lang.Float::class.java) &&
                    (n.contains("speed") || n.contains("mult") || n.contains("scale") || n.contains("rate")) &&
                    !n.startsWith("get") && !n.startsWith("is")
            }.sortedBy { m ->
                val n = m.name.lowercase(Locale.US)
                when {
                    n == "setspeed" -> 0
                    n.startsWith("set") && !n.contains("native") -> 1
                    !n.contains("native") -> 2
                    else -> 3
                }
            }
            for (m in candidates) {
                try {
                    m.isAccessible = true
                    val isStatic = Modifier.isStatic(m.modifiers)
                    val receiver = if (isStatic) null else target
                    if (!isStatic && receiver == null) continue
                    val arg: Any = if (m.parameterTypes[0] == Float::class.javaPrimitiveType ||
                        m.parameterTypes[0] == java.lang.Float::class.java) value.toFloat() else value
                    m.invoke(receiver, arg)
                    return true
                } catch (_: Throwable) {}
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun pillBg(color: Int, radiusDp: Int, ctx: Context, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }
    }

    private fun buildOverlay(activity: Activity): FrameLayout {
        val root = FrameLayout(activity)
        val dm = activity.resources.displayMetrics

        // Floating collapsed badge
        val badge = TextView(activity).apply {
            text = "⚡GG"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = pillBg(Color.parseColor("#D90F172A"), 24, activity, Color.parseColor("#38BDF8"))
            elevation = dp(activity, 8).toFloat()
        }
        val badgeSize = dp(activity, 48)
        val badgeLp = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = badgeX.coerceIn(0, (dm.widthPixels - badgeSize).coerceAtLeast(0))
            topMargin = badgeY.coerceIn(0, (dm.heightPixels - badgeSize).coerceAtLeast(0))
        }

        // Expanded panel
        val panelWidth = (dm.widthPixels - dp(activity, 20)).coerceAtMost(dp(activity, 344))
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(Color.parseColor("#F20B1120"), 16, activity, Color.parseColor("#334155"))
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 10))
            elevation = dp(activity, 10).toFloat()
            isClickable = true
            isFocusable = true
        }
        val panelLp = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(activity, 18)
        }

        fun syncVisibility() {
            badge.visibility = if (isExpanded) View.GONE else View.VISIBLE
            panel.visibility = if (isExpanded) View.VISIBLE else View.GONE
        }

        // Header
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(activity).apply {
            text = "⚡ SpeedGod + MiniGG"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val speedBadgeTv = TextView(activity).apply {
            text = String.format(Locale.US, "%.2fx", currentSpeed)
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3))
            background = pillBg(Color.parseColor("#1E293B"), 8, activity)
        }
        val closeBtn = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 4), dp(activity, 4))
            setOnClickListener {
                isExpanded = false
                syncVisibility()
            }
        }
        header.addView(titleTv)
        header.addView(speedBadgeTv)
        header.addView(closeBtn)
        panel.addView(header)

        // Tabs
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
                bottomMargin = dp(activity, 6)
            }
        }
        val tabSpeedBtn = TextView(activity).apply {
            text = "⚡ Скорость"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(activity, 7), 0, dp(activity, 7))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(activity, 4)
            }
        }
        val tabGgBtn = TextView(activity).apply {
            text = "🔍 Mini GG"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(activity, 7), 0, dp(activity, 7))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(activity, 4)
            }
        }
        tabRow.addView(tabSpeedBtn)
        tabRow.addView(tabGgBtn)
        panel.addView(tabRow)

        // Scrollable content wrapper
        val contentScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.68f).roundToInt().coerceAtMost(dp(activity, 520))
            )
        }
        val contentHolder = FrameLayout(activity)
        contentScroll.addView(contentHolder)
        panel.addView(contentScroll)

        // --- TAB 0: SPEED ---
        val speedContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val bigSpeedTv = TextView(activity).apply {
            text = String.format(Locale.US, "%.2fx", currentSpeed)
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 4), 0, dp(activity, 6))
        }
        speedContainer.addView(bigSpeedTv)

        val seekBar = SeekBar(activity).apply {
            max = 199
            progress = ((currentSpeed * 10.0).roundToInt() - 1).coerceIn(0, 199)
        }

        val pauseBtn = TextView(activity).apply {
            text = if (paused) "▶ Продолжить" else "⏸ Пауза"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 9), 0, dp(activity, 9))
            background = pillBg(Color.parseColor("#D97706"), 8, activity)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(activity, 4)
            }
        }

        fun updateSpeedUi(spd: Double, updateSlider: Boolean = true) {
            val s = String.format(Locale.US, "%.2fx", spd)
            bigSpeedTv.text = s
            speedBadgeTv.text = s
            pauseBtn.text = if (paused) "▶ Продолжить" else "⏸ Пауза"
            if (updateSlider) {
                seekBar.progress = ((spd * 10.0).roundToInt() - 1).coerceIn(0, 199)
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val spd = (progress + 1) / 10.0
                    applySpeed(spd)
                    updateSpeedUi(currentSpeed, false)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        speedContainer.addView(seekBar)

        fun makePresetRow(values: List<Double>): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(activity, 6)
                }
                for (v in values) {
                    val b = TextView(activity).apply {
                        text = if (v == v.toInt().toDouble()) "${v.toInt()}x" else "${v}x"
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setPadding(0, dp(activity, 8), 0, dp(activity, 8))
                        background = pillBg(Color.parseColor("#1E293B"), 8, activity, Color.parseColor("#334155"))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(dp(activity, 2), 0, dp(activity, 2), 0)
                        }
                        setOnClickListener {
                            applySpeed(v)
                            updateSpeedUi(currentSpeed)
                        }
                    }
                    addView(b)
                }
            }
        }
        speedContainer.addView(makePresetRow(listOf(0.25, 0.5, 1.0, 2.0)))
        speedContainer.addView(makePresetRow(listOf(3.0, 5.0, 10.0, 20.0)))

        val fineRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            }
            for ((label, delta) in listOf("-0.5" to -0.5, "-0.1" to -0.1, "+0.1" to 0.1, "+0.5" to 0.5)) {
                val b = TextView(activity).apply {
                    text = label
                    setTextColor(Color.parseColor("#93C5FD"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, dp(activity, 7), 0, dp(activity, 7))
                    background = pillBg(Color.parseColor("#172554"), 8, activity)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(activity, 2), 0, dp(activity, 2), 0)
                    }
                    setOnClickListener {
                        applySpeed(((currentSpeed + delta) * 100.0).roundToInt() / 100.0)
                        updateSpeedUi(currentSpeed)
                    }
                }
                addView(b)
            }
        }
        speedContainer.addView(fineRow)

        val bottomSpeedRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 8)
            }
        }
        pauseBtn.setOnClickListener {
            if (!paused) {
                speedBeforePause = currentSpeed
                paused = true
                applySpeed(0.05)
                paused = true
            } else {
                paused = false
                applySpeed(speedBeforePause.coerceAtLeast(0.25))
            }
            updateSpeedUi(currentSpeed)
        }
        val resetSpeedBtn = TextView(activity).apply {
            text = "↺ Сброс (1x)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 9), 0, dp(activity, 9))
            background = pillBg(Color.parseColor("#2563EB"), 8, activity)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(activity, 4)
            }
            setOnClickListener {
                paused = false
                applySpeed(1.0)
                updateSpeedUi(currentSpeed)
            }
        }
        bottomSpeedRow.addView(pauseBtn)
        bottomSpeedRow.addView(resetSpeedBtn)
        speedContainer.addView(bottomSpeedRow)

        // --- TAB 1: MINI GG (WITH BUILT-IN NUMPAD) ---
        val ggContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // Type selector
        val typeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val typeButtons = mutableListOf<Pair<Int, TextView>>()
        val types = listOf(
            MemScanner.TYPE_DWORD to "Dword",
            MemScanner.TYPE_FLOAT to "Float",
            MemScanner.TYPE_DOUBLE to "Double",
            MemScanner.TYPE_QWORD to "Qword"
        )
        fun refreshTypeButtons() {
            for ((t, btn) in typeButtons) {
                val active = (t == selectedType)
                btn.background = pillBg(
                    if (active) Color.parseColor("#2563EB") else Color.parseColor("#1E293B"),
                    7, activity,
                    if (active) Color.parseColor("#60A5FA") else Color.parseColor("#334155")
                )
                btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#94A3B8"))
            }
        }
        for ((t, name) in types) {
            val b = TextView(activity).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 6), 0, dp(activity, 6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(activity, 2), 0, dp(activity, 2), 0)
                }
                setOnClickListener {
                    selectedType = t
                    refreshTypeButtons()
                }
            }
            typeButtons.add(t to b)
            typeRow.addView(b)
        }
        refreshTypeButtons()
        ggContainer.addView(typeRow)

        fun makeActionBtn(label: String, bgColor: Int, weight: Float = 1f): TextView {
            return TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), dp(activity, 8))
                background = pillBg(bgColor, 8, activity)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                    setMargins(dp(activity, 2), 0, dp(activity, 2), 0)
                }
            }
        }

        // Custom clickable display boxes (no system keyboard needed!)
        val searchBoxTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            }
        }
        ggContainer.addView(searchBoxTv)

        val searchBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 5)
            }
        }
        val newSearchBtn = makeActionBtn("🔍 Поиск", Color.parseColor("#2563EB"), 1.2f)
        val refineBtn = makeActionBtn("🎯 Отсев", Color.parseColor("#D97706"), 1.2f)
        val clearBtn = makeActionBtn("🗑 Сброс", Color.parseColor("#475569"), 0.9f)
        searchBtnRow.addView(newSearchBtn)
        searchBtnRow.addView(refineBtn)
        searchBtnRow.addView(clearBtn)
        ggContainer.addView(searchBtnRow)

        // Edit All + Freeze row
        val editRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            }
        }
        val editBoxTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(activity, 9), dp(activity, 8), dp(activity, 9), dp(activity, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.55f).apply {
                rightMargin = dp(activity, 4)
            }
        }
        val editAllBtn = makeActionBtn("✏️ Все", Color.parseColor("#16A34A"), 0.85f)
        val freezeAllBtn = makeActionBtn("❄️", Color.parseColor("#0284C7"), 0.45f)
        val unfreezeAllBtn = makeActionBtn("🔥", Color.parseColor("#DC2626"), 0.45f)
        editRow.addView(editBoxTv)
        editRow.addView(editAllBtn)
        editRow.addView(freezeAllBtn)
        editRow.addView(unfreezeAllBtn)
        ggContainer.addView(editRow)

        // Single-address edit banner (shown when tapping ✏️ on one address)
        val singleEditBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg(Color.parseColor("#14532D"), 8, activity, Color.parseColor("#22C55E"))
            setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 6), dp(activity, 5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 5)
            }
            visibility = View.GONE
        }
        val singleEditLabel = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val singleApplyBtn = TextView(activity).apply {
            text = "✅ Записать"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg(Color.parseColor("#16A34A"), 6, activity)
            setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 8), dp(activity, 5))
        }
        val singleCancelBtn = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 4), dp(activity, 4))
        }
        singleEditBar.addView(singleEditLabel)
        singleEditBar.addView(singleApplyBtn)
        singleEditBar.addView(singleCancelBtn)
        ggContainer.addView(singleEditBar)

        // Built-in Numpad
        val numpadContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(Color.parseColor("#0F172A"), 10, activity, Color.parseColor("#334155"))
            setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            }
        }

        // Status + Refresh + Keyboard toggle row
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            }
        }
        val statusTv = TextView(activity).apply {
            text = "Найдено: ${MemScanner.getResultCount()} | ❄️: ${MemScanner.getFrozenCount()}"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val togglePadBtn = TextView(activity).apply {
            text = "⌨️"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4))
            background = pillBg(Color.parseColor("#1E293B"), 6, activity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(activity, 4)
            }
        }
        val refreshBtn = TextView(activity).apply {
            text = "↻"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(activity, 9), dp(activity, 4), dp(activity, 9), dp(activity, 4))
            background = pillBg(Color.parseColor("#1E293B"), 6, activity)
        }
        statusRow.addView(statusTv)
        statusRow.addView(togglePadBtn)
        statusRow.addView(refreshBtn)

        ggContainer.addView(numpadContainer)
        ggContainer.addView(statusRow)

        // Results list container
        val resultsList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 5)
            }
        }
        ggContainer.addView(resultsList)

        fun syncInputsUi() {
            val sActive = (activeInput == 0)
            searchBoxTv.background = pillBg(
                Color.parseColor("#1E293B"), 8, activity,
                if (sActive) Color.parseColor("#38BDF8") else Color.parseColor("#475569"),
                if (sActive) 2 else 1
            )
            if (searchText.isEmpty()) {
                searchBoxTv.text = if (sActive) "Поиск: |" else "Нажмите для ввода числа..."
                searchBoxTv.setTextColor(Color.parseColor("#64748B"))
            } else {
                searchBoxTv.text = if (sActive) "Поиск: $searchText|" else "Поиск: $searchText"
                searchBoxTv.setTextColor(Color.WHITE)
            }

            val eActive = (activeInput == 1)
            editBoxTv.background = pillBg(
                Color.parseColor("#1E293B"), 8, activity,
                if (eActive) Color.parseColor("#22C55E") else Color.parseColor("#475569"),
                if (eActive) 2 else 1
            )
            if (editText.isEmpty()) {
                editBoxTv.text = if (eActive) "Новое: |" else "Новое значение..."
                editBoxTv.setTextColor(Color.parseColor("#64748B"))
            } else {
                editBoxTv.text = if (eActive) "$editText|" else editText
                editBoxTv.setTextColor(Color.WHITE)
            }

            numpadContainer.visibility = if (numpadVisible) View.VISIBLE else View.GONE
            togglePadBtn.background = pillBg(
                if (numpadVisible) Color.parseColor("#2563EB") else Color.parseColor("#1E293B"),
                6, activity
            )

            val sa = editingSingleAddr
            if (sa != null) {
                singleEditBar.visibility = View.VISIBLE
                val shortA = if (sa.length > 8) ".." + sa.takeLast(8) else sa
                singleEditLabel.text = "Меняем $shortA"
            } else {
                singleEditBar.visibility = View.GONE
            }
        }

        fun onNumpadKey(k: String) {
            val cur = if (activeInput == 0) searchText else editText
            val next = when (k) {
                "⌫" -> if (cur.isNotEmpty()) cur.dropLast(1) else ""
                "C" -> ""
                "-" -> if (cur.startsWith("-")) cur.drop(1) else "-$cur"
                "." -> when {
                    cur.contains(".") -> cur
                    cur.isEmpty() || cur == "-" -> "${cur}0."
                    else -> "$cur."
                }
                "999k" -> "999999"
                "000" -> if (cur.length <= 13) cur + "000" else cur
                else -> if (cur.length <= 16) cur + k else cur
            }
            if (activeInput == 0) searchText = next else editText = next
            syncInputsUi()
        }

        // Build 4x4 numpad grid
        val padRows = listOf(
            listOf("7", "8", "9", "⌫"),
            listOf("4", "5", "6", "C"),
            listOf("1", "2", "3", "."),
            listOf("-", "0", "000", "999k")
        )
        for (keys in padRows) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(activity, 2), 0, dp(activity, 2))
                }
            }
            for (k in keys) {
                val bgCol = when (k) {
                    "⌫" -> Color.parseColor("#7F1D1D")
                    "C" -> Color.parseColor("#475569")
                    "999k" -> Color.parseColor("#14532D")
                    ".", "-", "000" -> Color.parseColor("#1E3A5F")
                    else -> Color.parseColor("#1E293B")
                }
                val btn = TextView(activity).apply {
                    text = k
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, dp(activity, 8), 0, dp(activity, 8))
                    background = pillBg(bgCol, 7, activity, Color.parseColor("#334155"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(activity, 2), 0, dp(activity, 2), 0)
                    }
                    setOnClickListener { onNumpadKey(k) }
                }
                row.addView(btn)
            }
            numpadContainer.addView(row)
        }

        searchBoxTv.setOnClickListener {
            activeInput = 0
            numpadVisible = true
            syncInputsUi()
        }
        editBoxTv.setOnClickListener {
            activeInput = 1
            numpadVisible = true
            syncInputsUi()
        }
        togglePadBtn.setOnClickListener {
            numpadVisible = !numpadVisible
            syncInputsUi()
        }

        fun refreshResultsUi(customMsg: String? = null) {
            val total = MemScanner.getResultCount()
            val frozen = MemScanner.getFrozenCount()
            statusTv.text = customMsg ?: "Найдено: $total | ❄️: $frozen"
            resultsList.removeAllViews()
            if (total <= 0) return

            if (total > 100) {
                val hint = TextView(activity).apply {
                    text = "Показаны 25 из $total. Измените число в игре и нажмите «🎯 Отсев»."
                    setTextColor(Color.parseColor("#FBBF24"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(dp(activity, 4), dp(activity, 2), dp(activity, 4), dp(activity, 4))
                }
                resultsList.addView(hint)
            }

            val items = MemScanner.getResults(25)
            for (raw in items) {
                val parts = raw.split("|")
                if (parts.size < 3) continue
                val addr = parts[0]
                val curVal = parts[1]
                val isFrozen = parts[2] == "1"

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = pillBg(Color.parseColor("#1E293B"), 6, activity)
                    setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 6), dp(activity, 5))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(activity, 4)
                    }
                }
                val shortAddr = if (addr.length > 8) ".." + addr.takeLast(8) else addr
                val infoTv = TextView(activity).apply {
                    text = "$shortAddr  =  $curVal"
                    setTextColor(if (isFrozen) Color.parseColor("#38BDF8") else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val oneEditBtn = TextView(activity).apply {
                    text = "✏️"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3))
                    setOnClickListener {
                        editingSingleAddr = addr
                        if (editText.isEmpty()) editText = curVal
                        activeInput = 1
                        numpadVisible = true
                        syncInputsUi()
                        contentScroll.smoothScrollTo(0, 0)
                    }
                }
                val oneFreezeBtn = TextView(activity).apply {
                    text = if (isFrozen) "❄️" else "○"
                    setTextColor(if (isFrozen) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 6), dp(activity, 3))
                    setOnClickListener {
                        val targetVal = editText.trim().ifEmpty { curVal }
                        MemScanner.toggleFreeze(addr, selectedType, targetVal)
                        refreshResultsUi()
                    }
                }
                row.addView(infoTv)
                row.addView(oneEditBtn)
                row.addView(oneFreezeBtn)
                resultsList.addView(row)
            }
        }

        singleApplyBtn.setOnClickListener {
            val sa = editingSingleAddr ?: return@setOnClickListener
            val nv = editText.trim()
            if (nv.isEmpty()) {
                statusTv.text = "Введите число в поле «Новое значение»"
                return@setOnClickListener
            }
            val ok = MemScanner.editOne(sa, selectedType, nv)
            editingSingleAddr = null
            numpadVisible = false
            syncInputsUi()
            refreshResultsUi(if (ok) "Записано $nv | ❄️: ${MemScanner.getFrozenCount()}" else "Ошибка записи адреса")
        }

        singleCancelBtn.setOnClickListener {
            editingSingleAddr = null
            syncInputsUi()
        }

        newSearchBtn.setOnClickListener {
            val q = searchText.trim()
            if (q.isEmpty()) {
                statusTv.text = "Введите число на клавиатуре ниже"
                activeInput = 0
                numpadVisible = true
                syncInputsUi()
                return@setOnClickListener
            }
            statusTv.text = "⏳ Идёт поиск..."
            Thread {
                val count = MemScanner.newSearch(selectedType, q)
                mainHandler.post {
                    when {
                        count == -2 -> statusTv.text = "Ошибка: нативный модуль не загружен"
                        count < 0 -> statusTv.text = "Некорректное число"
                        else -> {
                            numpadVisible = false
                            syncInputsUi()
                            refreshResultsUi()
                        }
                    }
                }
            }.start()
        }

        refineBtn.setOnClickListener {
            val q = searchText.trim()
            if (q.isEmpty()) {
                statusTv.text = "Введите новое число для отсева"
                activeInput = 0
                numpadVisible = true
                syncInputsUi()
                return@setOnClickListener
            }
            statusTv.text = "⏳ Отсев..."
            Thread {
                val count = MemScanner.refineSearch(selectedType, q)
                mainHandler.post {
                    when {
                        count == -2 -> statusTv.text = "Ошибка: нативный модуль не загружен"
                        count < 0 -> statusTv.text = "Некорректное число"
                        else -> {
                            numpadVisible = false
                            syncInputsUi()
                            refreshResultsUi()
                        }
                    }
                }
            }.start()
        }

        clearBtn.setOnClickListener {
            MemScanner.clearSearch()
            editingSingleAddr = null
            searchText = ""
            activeInput = 0
            numpadVisible = true
            syncInputsUi()
            refreshResultsUi("Поиск очищен | ❄️: ${MemScanner.getFrozenCount()}")
        }

        refreshBtn.setOnClickListener { refreshResultsUi() }

        editAllBtn.setOnClickListener {
            val nv = editText.trim()
            if (nv.isEmpty()) {
                statusTv.text = "Введите новое значение"
                activeInput = 1
                numpadVisible = true
                syncInputsUi()
                return@setOnClickListener
            }
            val n = MemScanner.editAll(selectedType, nv)
            numpadVisible = false
            syncInputsUi()
            refreshResultsUi("Изменено адресов: $n | ❄️: ${MemScanner.getFrozenCount()}")
        }

        freezeAllBtn.setOnClickListener {
            val nv = editText.trim().ifEmpty { searchText.trim() }
            if (nv.isEmpty()) {
                statusTv.text = "Введите значение для заморозки"
                return@setOnClickListener
            }
            MemScanner.freezeAll(selectedType, nv)
            refreshResultsUi()
        }

        unfreezeAllBtn.setOnClickListener {
            MemScanner.unfreezeAll()
            refreshResultsUi("Заморозка снята | Найдено: ${MemScanner.getResultCount()}")
        }

        contentHolder.addView(speedContainer)
        contentHolder.addView(ggContainer)

        fun syncTabs() {
            speedContainer.visibility = if (activeTab == 0) View.VISIBLE else View.GONE
            ggContainer.visibility = if (activeTab == 1) View.VISIBLE else View.GONE
            tabSpeedBtn.background = pillBg(
                if (activeTab == 0) Color.parseColor("#2563EB") else Color.parseColor("#1E293B"),
                8, activity
            )
            tabSpeedBtn.setTextColor(if (activeTab == 0) Color.WHITE else Color.parseColor("#94A3B8"))
            tabGgBtn.background = pillBg(
                if (activeTab == 1) Color.parseColor("#2563EB") else Color.parseColor("#1E293B"),
                8, activity
            )
            tabGgBtn.setTextColor(if (activeTab == 1) Color.WHITE else Color.parseColor("#94A3B8"))
            if (activeTab == 1) {
                syncInputsUi()
                refreshResultsUi()
            }
        }

        tabSpeedBtn.setOnClickListener { activeTab = 0; syncTabs() }
        tabGgBtn.setOnClickListener { activeTab = 1; syncTabs() }

        // Draggable badge touch listener
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var moved = false
        badge.setOnTouchListener { v, ev ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).roundToInt()
                    val dy = (ev.rawY - downY).roundToInt()
                    if (abs(dx) > dp(activity, 6) || abs(dy) > dp(activity, 6)) moved = true
                    lp.leftMargin = (startLeft + dx).coerceIn(0, (dm.widthPixels - badgeSize).coerceAtLeast(0))
                    lp.topMargin = (startTop + dy).coerceIn(0, (dm.heightPixels - badgeSize).coerceAtLeast(0))
                    badgeX = lp.leftMargin
                    badgeY = lp.topMargin
                    v.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        isExpanded = true
                        syncVisibility()
                        syncTabs()
                    }
                    true
                }
                else -> false
            }
        }

        root.addView(badge, badgeLp)
        root.addView(panel, panelLp)
        syncVisibility()
        syncTabs()
        return root
    }
}
