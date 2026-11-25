package com.speedgod.module

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
    @Volatile private var lastSearchText = ""
    @Volatile private var lastEditText = ""
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

    private fun pillBg(color: Int, radiusDp: Int, ctx: Context, strokeColor: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(ctx, 1), strokeColor)
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
        val panelWidth = (dm.widthPixels - dp(activity, 24)).coerceAtMost(dp(activity, 340))
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(Color.parseColor("#F00B1120"), 16, activity, Color.parseColor("#334155"))
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 12))
            elevation = dp(activity, 10).toFloat()
            isClickable = true
            isFocusable = true
        }
        val panelLp = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(activity, 24)
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
                topMargin = dp(activity, 8)
                bottomMargin = dp(activity, 8)
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
                (dm.heightPixels * 0.56f).roundToInt().coerceAtMost(dp(activity, 420))
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
            max = 199 // 0.1 .. 20.0
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

        // Fine tune row
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

        // --- TAB 1: MINI GG ---
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

        fun makeNumInput(hintText: String, initial: String): EditText {
            return EditText(activity).apply {
                hint = hintText
                setText(initial)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                background = pillBg(Color.parseColor("#1E293B"), 8, activity, Color.parseColor("#475569"))
                setPadding(dp(activity, 10), dp(activity, 7), dp(activity, 10), dp(activity, 7))
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    requestFocus()
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        val searchInput = makeNumInput("Число для поиска (напр. 1500)", lastSearchText).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 7)
            }
        }
        ggContainer.addView(searchInput)

        val searchBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            }
        }
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
        val newSearchBtn = makeActionBtn("🔍 Поиск", Color.parseColor("#2563EB"), 1.2f)
        val refineBtn = makeActionBtn("🎯 Отсев", Color.parseColor("#D97706"), 1.2f)
        val clearBtn = makeActionBtn("🗑 Сброс", Color.parseColor("#475569"), 0.9f)
        searchBtnRow.addView(newSearchBtn)
        searchBtnRow.addView(refineBtn)
        searchBtnRow.addView(clearBtn)
        ggContainer.addView(searchBtnRow)

        // Status + Refresh row
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
        val refreshBtn = TextView(activity).apply {
            text = "↻ Обновить"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 6), dp(activity, 4))
            background = pillBg(Color.parseColor("#1E293B"), 6, activity)
        }
        statusRow.addView(statusTv)
        statusRow.addView(refreshBtn)
        ggContainer.addView(statusRow)

        // Edit All + Freeze row
        val editRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            }
        }
        val editInput = makeNumInput("Новое значение", lastEditText).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f).apply {
                rightMargin = dp(activity, 4)
            }
        }
        val editAllBtn = makeActionBtn("✏️ Все", Color.parseColor("#16A34A"), 0.85f)
        val freezeAllBtn = makeActionBtn("❄️", Color.parseColor("#0284C7"), 0.48f)
        val unfreezeAllBtn = makeActionBtn("🔥", Color.parseColor("#DC2626"), 0.48f)
        editRow.addView(editInput)
        editRow.addView(editAllBtn)
        editRow.addView(freezeAllBtn)
        editRow.addView(unfreezeAllBtn)
        ggContainer.addView(editRow)

        // Results list container
        val resultsList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            }
        }
        ggContainer.addView(resultsList)

        fun refreshResultsUi(customMsg: String? = null) {
            val total = MemScanner.getResultCount()
            val frozen = MemScanner.getFrozenCount()
            statusTv.text = customMsg ?: "Найдено: $total | ❄️ Заморожено: $frozen"
            resultsList.removeAllViews()
            if (total <= 0) return

            if (total > 100) {
                val hint = TextView(activity).apply {
                    text = "Показаны первые 25 из $total. Измените число в игре и нажмите «🎯 Отсев»."
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
                        val dlgInput = EditText(activity).apply {
                            setText(curVal)
                            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Изменить 0x$addr")
                            .setView(dlgInput)
                            .setPositiveButton("Сохранить") { _, _ ->
                                val nv = dlgInput.text.toString().trim()
                                if (nv.isNotEmpty()) {
                                    MemScanner.editOne(addr, selectedType, nv)
                                    refreshResultsUi()
                                }
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }
                val oneFreezeBtn = TextView(activity).apply {
                    text = if (isFrozen) "❄️" else "○"
                    setTextColor(if (isFrozen) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 6), dp(activity, 3))
                    setOnClickListener {
                        val targetVal = editInput.text.toString().trim().ifEmpty { curVal }
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

        newSearchBtn.setOnClickListener {
            val q = searchInput.text.toString().trim()
            lastSearchText = q
            if (q.isEmpty()) {
                statusTv.text = "Введите число для поиска"
                return@setOnClickListener
            }
            statusTv.text = "⏳ Идёт поиск..."
            Thread {
                val count = MemScanner.newSearch(selectedType, q)
                mainHandler.post {
                    when {
                        count == -2 -> statusTv.text = "Ошибка: нативный модуль не загружен"
                        count < 0 -> statusTv.text = "Некорректное число"
                        else -> refreshResultsUi()
                    }
                }
            }.start()
        }

        refineBtn.setOnClickListener {
            val q = searchInput.text.toString().trim()
            lastSearchText = q
            if (q.isEmpty()) {
                statusTv.text = "Введите новое число для отсева"
                return@setOnClickListener
            }
            statusTv.text = "⏳ Отсев..."
            Thread {
                val count = MemScanner.refineSearch(selectedType, q)
                mainHandler.post {
                    when {
                        count == -2 -> statusTv.text = "Ошибка: нативный модуль не загружен"
                        count < 0 -> statusTv.text = "Некорректное число"
                        else -> refreshResultsUi()
                    }
                }
            }.start()
        }

        clearBtn.setOnClickListener {
            MemScanner.clearSearch()
            refreshResultsUi("Поиск очищен | ❄️: ${MemScanner.getFrozenCount()}")
        }

        refreshBtn.setOnClickListener { refreshResultsUi() }

        editAllBtn.setOnClickListener {
            val nv = editInput.text.toString().trim()
            lastEditText = nv
            if (nv.isEmpty()) {
                statusTv.text = "Введите новое значение"
                return@setOnClickListener
            }
            val n = MemScanner.editAll(selectedType, nv)
            refreshResultsUi("Изменено адресов: $n | ❄️: ${MemScanner.getFrozenCount()}")
        }

        freezeAllBtn.setOnClickListener {
            val nv = editInput.text.toString().trim().ifEmpty { searchInput.text.toString().trim() }
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
            if (activeTab == 1) refreshResultsUi()
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
