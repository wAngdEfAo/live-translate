package com.shishifanyi.app

import android.annotation.SuppressLint
import android.util.Log

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 覆盖式译文：只在原文文字所在的矩形上覆盖译文（中文或英文），空白与图片保持透明。
 * 只处理屏幕顶部 60% 区域。另有一条很窄的控制条。
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "Overlay"
        private const val MAX_HISTORY = 3
        private const val DEFAULT_BG = 0xFF10131A.toInt()
        // 调试描边：只在排查对齐问题时临时改 true，发布包必须是 false
        private const val DEBUG_OUTLINE = false
        private const val FONT_SCALE = 1.42f
        private const val CH_ID = "live_translate_overlay"
        private const val NOTI_ID = 1001

        @Volatile
        private var inst: OverlayService? = null

        fun isShowing(): Boolean = inst != null

        fun start(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, OverlayService::class.java))
            } catch (ex: Throwable) {
                Log.w(TAG, "OverlayService@62: " + ex.message)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, OverlayService::class.java))
            } catch (ex: Throwable) {
                Log.w(TAG, "OverlayService@69: " + ex.message)
            }
        }

        fun showBlocks(list: List<Pair<TextBlock, String>>) {
            inst?.renderBlocks(list)
        }

        fun pushHistory(text: String) {
            inst?.addHistory(text)
        }

        fun refreshForeground() {
            inst?.updateNotification()
            inst?.renderBlocks(emptyList())
        }
    }

    private lateinit var wm: WindowManager
    private var blockLayer: FrameLayout? = null
    private var lineWin: View? = null
    private var lineLabel: TextView? = null
    private var dirBtn: TextView? = null
    private var bar: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var historyWin: View? = null
    private var toggleBtn: TextView? = null

    private var screenW = 0
    private var screenH = 0
    private var regionTop = 0
    private var regionH = 0

    /** 实时读取块层窗口在屏幕上的 y 原点，避免一次性测量后失效。 */
    private fun liveLayerOffsetY(): Int {
        val l = blockLayer ?: return insetY
        return try {
            val loc = IntArray(2)
            l.getLocationOnScreen(loc)
            loc[1]
        } catch (t: Throwable) {
            insetY
        }
    }

    /** 块层窗口在屏幕上的 x 原点：分屏 / 折叠屏 / 横屏时不一定贴左边 0。 */
    private fun liveLayerOffsetX(): Int {
        val l = blockLayer ?: return 0
        return try {
            val loc = IntArray(2)
            l.getLocationOnScreen(loc)
            loc[0]
        } catch (t: Throwable) {
            0
        }
    }

    /** 只更新分界线位置与标签；块层固定覆盖全屏，靠坐标过滤区域。 */
    private fun updateRegion() {
        val (t, b) = AppCore.regionBounds()
        regionTop = t
        regionH = b
        val y = if (Prefs.regionFromTop) b else t
        val line = lineWin
        if (line != null) {
            val lp = line.layoutParams as WindowManager.LayoutParams
            lp.y = (y - dp(6f) - liveLayerOffsetY()).coerceAtLeast(0)
            try {
                wm.updateViewLayout(line, lp)
            } catch (ex: Throwable) {
                Log.w(TAG, "OverlayService@126: " + ex.message)
            }
        }
        val pct = Math.round(Prefs.regionRatio * 100).toString()
        lineLabel?.text = pct + "% " + (if (Prefs.regionFromTop) "上" else "下")
    }

    private val main = Handler(Looper.getMainLooper())
    private var foregroundStarted = false

    private data class Snap(val time: String, val text: String)

    private val snaps = ArrayList<Snap>()

    /** 悬浮窗原点在屏幕上的 y 偏移（状态栏等内缩），用来把矩形对回去。 */
    private var insetY = 0
    private var insetReady = false
    private var lastBlocks: List<Pair<TextBlock, String>> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AppCore.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        applyScreenMetrics()
        // 初值必须来自用户设置的比例，不能写死 0.6（否则改过比例后重启覆盖层会错位）
        val (t0, b0) = AppCore.regionBounds()
        regionTop = t0
        regionH = b0

        startForegroundSafely()
        addBlockLayer()
        addBoundaryLine()
        addBar()
        inst = this
        // 覆盖层起来了才需要 OCR；这里主动同步一次，避免上次残留状态
        AppCore.syncOcrService()
        main.postDelayed(watchdog, 30000L)
    }

    /** 重新读取当前屏幕尺寸；旋转、分屏、折叠屏切换后必须重算。 */
    private fun applyScreenMetrics() {
        screenW = resources.displayMetrics.widthPixels
        screenH = resources.displayMetrics.heightPixels
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val b = wm.maximumWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) {
                    screenW = b.width()
                    screenH = b.height()
                }
            } catch (ex: Throwable) {
                Log.w(TAG, "OverlayService@179: " + ex.message)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyScreenMetrics()
        updateRegion()
        // 横竖屏切换后状态栏高度会变，必须重测内缩，否则整体偏一截
        remeasureInsets()
        // 块层是按旧尺寸创建的，不跟着改就只在左上角一小块里画
        val l = blockLayer
        if (l != null) {
            val lp = l.layoutParams as WindowManager.LayoutParams
            lp.width = screenW
            lp.height = screenH
            try {
                wm.updateViewLayout(l, lp)
            } catch (ex: Throwable) {
                Log.w(TAG, "OverlayService@196: " + ex.message)
            }
        }
        lastRenderKey = ""
        renderBlocks(lastBlocks)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
        return START_STICKY
    }

    // ---------- 窗口 ----------

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun addBlockLayer() {
        // 刻意不加 FLAG_KEEP_SCREEN_ON：翻译是短时查看，熄屏交给系统更省电。
        // 若以后要做「看字幕不熄屏」，给这个窗口的 flags 加上 FLAG_KEEP_SCREEN_ON 即可。
        val layer = FrameLayout(this)
        layer.isClickable = false
        val p = WindowManager.LayoutParams(
            screenW, screenH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 0
        p.y = 0
        try {
            wm.addView(layer, p)
            blockLayer = layer
        } catch (t: Throwable) {
            blockLayer = null
        }
    }

    /** 标明「只处理这条线以上」的 60% 分界线。 */
    private fun addBoundaryLine() {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val line = View(this)
        line.setBackgroundColor(0x994C9AFF.toInt())
        row.addView(line, LinearLayout.LayoutParams(0, dp(1.5f), 1f))

        val label = TextView(this)
        label.text = "60%"
        label.textSize = 8.5f
        label.setTextColor(0xCC6FB4FF.toInt())
        label.setPadding(dp(4f), 0, dp(4f), 0)
        row.addView(label)
        lineLabel = label

        val p = WindowManager.LayoutParams(
            screenW, dp(26f), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 0
        p.y = regionH - dp(6f)
        // 上下拖动这条线 = 调整处理区域比例
        var downRawY = 0f
        var startY = 0
        var moved = false
        row.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    startY = p.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (abs(dy) > dp(4f)) moved = true
                    val lineY = (e.rawY - dp(6f).toFloat()).coerceIn(
                        dp(40f).toFloat(), (screenH - dp(40f)).toFloat()
                    )
                    val sh = screenH.toFloat()
                    val ratio = if (Prefs.regionFromTop) lineY / sh else 1f - lineY / sh
                    Prefs.regionRatio = ratio
                    updateRegion()
                    renderBlocks(lastBlocks)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        Prefs.regionFromTop = !Prefs.regionFromTop
                        updateRegion()
                        AppCore.resetDedupe()
                        ScreenReaderService.requestCapture()
                    }
                    true
                }

                else -> false
            }
        }

        try {
            wm.addView(row, p)
            lineWin = row
        } catch (_: Throwable) {
            lineWin = null
            return
        }

        remeasureInsets()
    }

    /**
     * 测量窗口在屏幕上的实际内缩（状态栏高度）。
     * 必须可重复调用：横竖屏切换时状态栏高度会变，只测一次会让分界线与译文整体偏一截。
     */
    private fun remeasureInsets() {
        val row = lineWin ?: return
        row.post {
            try {
                val loc = IntArray(2)
                row.getLocationOnScreen(loc)
                insetY = loc[1] - (regionH - dp(6f))
                insetReady = true
                // 把分界线挪回真正的区域边界
                val rp = row.layoutParams as WindowManager.LayoutParams
                rp.y = (regionH - dp(6f) - insetY).coerceAtLeast(0)
                wm.updateViewLayout(row, rp)
                // 控制条默认也贴到线下方
                val b = bar
                val bp = barLp
                if (b != null && bp != null && Prefs.barY < 0) {
                    bp.y = regionH + dp(6f) - insetY
                    try {
                        wm.updateViewLayout(b, bp)
                    } catch (ex: Throwable) {
                        Log.w(TAG, "OverlayService@362: " + ex.message)
                    }
                }
                if (lastBlocks.isNotEmpty()) renderBlocks(lastBlocks)
            } catch (ex: Throwable) {
                Log.w(TAG, "OverlayService@369: " + ex.message)
            }
        }
    }

    private fun addBar() {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        row.background = pill(0xD910131A.toInt(), 12f)

        // 拖动把手：整条几乎被按钮占满，必须单独留一块可抓的区域
        val handle = TextView(this)
        handle.text = "⠿"
        handle.textSize = 14f
        handle.setTextColor(0xFF9AA4B2.toInt())
        handle.setPadding(dp(8f), dp(6f), dp(10f), dp(6f))
        handle.isClickable = true
        row.addView(handle)

        toggleBtn = barButton(
            if (Prefs.translating) "暂停" else "开始",
            0xFF6FB4FF.toInt()
        ) {
            Prefs.translating = !Prefs.translating
            AppCore.resetDedupe()
            refreshToggle()
            updateNotification()
            AppCore.syncOcrService()
            if (!Prefs.translating) renderBlocks(emptyList())
            else ScreenReaderService.requestCapture()
        }
        row.addView(toggleBtn)

        dirBtn = barButton(
            if (Prefs.regionFromTop) "上↓" else "下↑",
            0xFFCFD8E3.toInt()
        ) {
            Prefs.regionFromTop = !Prefs.regionFromTop
            AppCore.resetDedupe()
            updateRegion()
            dirBtn?.text = if (Prefs.regionFromTop) "上↓" else "下↑"
            ScreenReaderService.requestCapture()
        }
        row.addView(dirBtn)

        row.addView(barButton("刷新", 0xFFCFD8E3.toInt()) {
            AppCore.resetDedupe()
            ScreenReaderService.requestCapture()
        })

        row.addView(barButton("历史", 0xFFCFD8E3.toInt()) { toggleHistory() })

        row.addView(barButton("✕", 0xFFCFD8E3.toInt()) { stopSelf() })

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = if (Prefs.barX >= 0) Prefs.barX else dp(8f)
        p.y = if (Prefs.barY >= 0) Prefs.barY else regionH + dp(6f)
        barLp = p

        var hDownX = 0f
        var hDownY = 0f
        var hStartX = 0
        var hStartY = 0
        var hMoved = false
        var hDownAt = 0L
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hDownX = e.rawX
                    hDownY = e.rawY
                    hStartX = p.x
                    hStartY = p.y
                    hMoved = false
                    hDownAt = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - hDownX).toInt()
                    val dy = (e.rawY - hDownY).toInt()
                    if (abs(dx) > dp(4f) || abs(dy) > dp(4f)) hMoved = true
                    p.x = hStartX + dx
                    p.y = hStartY + dy
                    try {
                        wm.updateViewLayout(row, p)
                    } catch (ex: Throwable) {
                        Log.w(TAG, "OverlayService@405: " + ex.message)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val held = System.currentTimeMillis() - hDownAt
                    if (!hMoved && held > 600L) {
                        // 长按把手：复位到默认位置
                        Prefs.barX = -1
                        Prefs.barY = -1
                        p.x = dp(8f)
                        p.y = regionH + dp(6f) - insetY
                        try {
                            wm.updateViewLayout(row, p)
                        } catch (ex: Throwable) {
                            Log.w(TAG, "OverlayService@420: " + ex.message)
                        }
                        Log.d("Overlay", "控制条已复位")
                    } else {
                        Prefs.barX = p.x
                        Prefs.barY = p.y
                    }
                    true
                }

                else -> false
            }
        }

        row.isClickable = true
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        row.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = p.x
                    startY = p.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (e.rawX - downRawX).toInt()
                    p.y = startY + (e.rawY - downRawY).toInt()
                    try {
                        wm.updateViewLayout(row, p)
                    } catch (ex: Throwable) {
                        Log.w(TAG, "OverlayService@454: " + ex.message)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Prefs.barX = p.x
                    Prefs.barY = p.y
                    true
                }

                else -> false
            }
        }

        try {
            wm.addView(row, p)
            bar = row
        } catch (t: Throwable) {
            bar = null
        }
    }

    private fun barButton(label: String, color: Int, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 11.5f
        tv.setTextColor(color)
        tv.setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun pill(color: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    // ---------- 覆盖渲染 ----------

    /** 同一段落可能被父子节点重复上报：矩形重叠超过 60% 的只留最大那块。
     *  注意：同一句出现在页面上两个不同位置（标题 + 图标说明）时要都盖，
     *  所以这里只按「位置重叠」去重，不再按文本去重。 */
    private fun dedupe(list: List<Pair<TextBlock, String>>): List<Pair<TextBlock, String>> {
        val sorted = list.sortedByDescending {
            it.first.rect.width().toLong() * it.first.rect.height().toLong()
        }
        val keptRects = ArrayList<android.graphics.Rect>()
        val out = ArrayList<Pair<TextBlock, String>>()
        for (p in sorted) {
            val r = p.first.rect
            val area = (r.width().toLong() * r.height().toLong()).coerceAtLeast(1L)
            var overlapped = 0L
            for (k in keptRects) {
                val ix = maxOf(0, minOf(r.right, k.right) - maxOf(r.left, k.left))
                val iy = maxOf(0, minOf(r.bottom, k.bottom) - maxOf(r.top, k.top))
                overlapped += ix.toLong() * iy.toLong()
            }
            // 仅当垂直方向真正重叠时才视为重复，避免把上下两段并成一块
            var vOverlap = false
            for (k in keptRects) {
                val iy = minOf(r.bottom, k.bottom) - maxOf(r.top, k.top)
                if (iy > r.height() * 6 / 10) vOverlap = true
            }
            if (!vOverlap) {
                keptRects.add(r)
                out.add(p)
                continue
            }
            if (overlapped * 100 / area > 60) continue
            keptRects.add(r)
            out.add(p)
        }
        return out
    }

    /** 用同宽度排版，反推能填满该矩形高度的字号（即原文行高对应的字号）。 */
    private val fitCache = HashMap<String, Float>(64)
    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun fitSize(text: String, width: Int, targetH: Int): Float {
        if (width <= 0 || targetH <= 0 || text.isEmpty()) return 12f
        // key 必须含文本本身：只用「长度:宽:高」时，同长度同尺寸的不同句子会串号
        val key = width.toString() + ":" + targetH + ":" + text
        val hit = fitCache[key]
        if (hit != null) return hit

        // 二分查找：从 39 次排版降到 6 次
        val paint = measurePaint
        var lo = 6
        var hi = 44
        while (lo < hi) {
            val mid = (lo + hi) / 2
            paint.textSize = mid.toFloat()
            if (layoutHeight(text, paint, width) < targetH) lo = mid + 1 else hi = mid
        }
        val v = lo.toFloat()
        if (fitCache.size < 200) fitCache[key] = v
        return v
    }

    private fun layoutHeight(text: String, paint: TextPaint, width: Int): Int {
        return try {
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false).height
        } catch (t: Throwable) {
            paint.textSize.toInt()
        }
    }

    private var lastRenderKey: String = ""

    // Layout.BREAK_STRATEGY_SIMPLE 与 LineBreaker.BREAK_STRATEGY_SIMPLE 都是 0，
// 用 Layout 的常量无需 API 29 的类，lint 的 WrongConstant 在这里是误判
    @SuppressLint("WrongConstant")
    private fun renderBlocks(raw: List<Pair<TextBlock, String>>) {
        val layer = blockLayer ?: return
        val key = raw.joinToString("\u0001") { it.first.text + "\u0002" + it.second }
        if (key == lastRenderKey) return
        lastRenderKey = key
        lastBlocks = raw
        layer.removeAllViews()
        if (!Prefs.translating) return

        val list = dedupe(raw)
        if (list.isEmpty()) return
        // 按上边界排序，供「向下扩块」查找正下方最近的块
        val ordered = list.sortedBy { it.first.rect.top }
        // 缓存靠 200 条上限自然淘汰；以前每次渲染都 clear()，等于缓存从未生效

        val offY = liveLayerOffsetY()
        val offX = liveLayerOffsetX()
        Log.d(
            "Overlay",
            "绘制 " + list.size + " 块，区域 " + regionTop + "-" + regionH + "，窗口偏移 " + offY
        )
        for (pair in list) {
            val r = pair.first.rect
            val en = pair.first.text
            val zh = pair.second
            if (zh.isBlank()) continue

            // 四周外扩，盖住文字抗锯齿边缘，避免英文露出一丝
            val pad = dp(2f)

            // 统一换算成块层内坐标：屏幕坐标 - 窗口内缩
            val layerTop = (regionTop - offY).coerceAtLeast(0)
            val layerBottom = (regionH - offY).coerceAtLeast(layerTop + 1)

            val rawTop = r.top - offY - pad
            val rawBottom = rawTop + r.height() + pad * 2
            // 完全在区域外：跳过
            if (rawBottom <= layerTop || rawTop >= layerBottom) continue
            // 大半在区域外：跳过，避免压到状态栏或边界以外
            if (rawTop < layerTop && (rawBottom - layerTop) * 2 < r.height()) continue

            var top = maxOf(rawTop, layerTop)
            val bottom = minOf(rawBottom, layerBottom)

            // 严格按原文字块自身的矩形定位：窄元素（徽标/标签）也保持原位
            val rawLeft = r.left
            val rawWidth = r.width()

            var left = (rawLeft - offX - pad).coerceAtLeast(0)
            var width = rawWidth + pad * 2
            if (width <= 8) continue
            if (left + width > screenW) width = screenW - left
            // 高度取原文矩形（裁到区域内）：整段英文都要被盖住
            var height = bottom - top
            if (height <= 0) continue

            // 1) 按原文在该矩形里的排版反推字号，再整体放大 15%
            var size = fitSize(en, width - dp(2f), height) * FONT_SCALE

            // 2) 中文更占地方：等比缩到能装下，最多缩到 65%
            val paint = measurePaint
            paint.textSize = size
            var h = layoutHeight(zh, paint, width - dp(2f))
            var guard = 0
            val floor = size * 0.85f
            while (h > height && size > floor && guard < 12) {
                size *= 0.95f
                paint.textSize = size
                h = layoutHeight(zh, paint, width - dp(2f))
                guard++
            }

            // 还是装不下就向下扩块，宁可多盖一点，也不压扁字
            // 但扩张不能盖住下一行：以正下方最近的同列文字块为上限
            if (h > height) {
                val nextTop = ordered.firstOrNull {
                    val nr = it.first.rect
                    nr.top > r.bottom && nr.left < r.right && nr.right > r.left
                }?.first?.rect?.top
                val limit = if (nextTop != null) nextTop - offY else layerBottom
                val room = (minOf(layerBottom, limit) - top).coerceAtLeast(height)
                height = h.coerceAtMost(room)
            }

            val tv = CoverView(this)
            tv.text = zh
            tv.includeFontPadding = false
            tv.setPadding(dp(1f), 0, dp(1f), 0)
            tv.ellipsize = null
            tv.maxLines = Int.MAX_VALUE
            tv.gravity = if (h <= height) Gravity.CENTER_VERTICAL else Gravity.TOP
            tv.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE

            // 完全不透明：CoverView 内部反复涂满
            val sampled = OcrBridge.sampleColor(r)
            val bgColor = withAlphaOpaque(sampled ?: DEFAULT_BG)
            tv.coverColor = bgColor
            tv.outlineColor = if (DEBUG_OUTLINE) 0x554C9AFF else null
            tv.alpha = 1f
            tv.setTextColor(if (isLight(bgColor)) 0xFF101214.toInt() else 0xFFFFFFFF.toInt())
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)

            val lp = FrameLayout.LayoutParams(width, height)
            lp.leftMargin = left
            lp.topMargin = top
            layer.addView(tv, lp)
        }
    }

    private fun withAlphaOpaque(c: Int): Int = (0xFF shl 24) or (c and 0x00FFFFFF)

    private fun luminance(c: Int): Double {
        val r = (c shr 16 and 0xFF) / 255.0
        val g = (c shr 8 and 0xFF) / 255.0
        val b = (c and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun isLight(c: Int): Boolean = luminance(c) > 0.6

    // ---------- 历史 ----------

    private fun addHistory(text: String) {
        if (text.isBlank()) return
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        snaps.add(0, Snap(t, text))
        while (snaps.size > MAX_HISTORY) snaps.removeAt(snaps.size - 1)
        if (historyWin != null) {
            hideHistory()
            showHistory()
        }
    }

    private fun toggleHistory() {
        if (historyWin != null) hideHistory() else showHistory()
    }

    private fun hideHistory() {
        val v = historyWin ?: return
        try {
            wm.removeView(v)
        } catch (ex: Throwable) {
            Log.w(TAG, "OverlayService@701: " + ex.message)
        }
        historyWin = null
    }

    private fun showHistory() {
        hideHistory()
        if (snaps.isEmpty()) return

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = pill(0xF210131A.toInt(), 14f)
        box.setPadding(dp(14f), dp(10f), dp(14f), dp(12f))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        box.addView(head)

        val title = TextView(this)
        title.text = "最近 ${snaps.size} 条译文"
        title.textSize = 12f
        title.setTextColor(0xFF9AA4B2.toInt())
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val close = TextView(this)
        close.text = "收起"
        close.textSize = 11f
        close.setTextColor(0xFF6FB4FF.toInt())
        close.isClickable = true
        close.setOnClickListener { hideHistory() }
        head.addView(close)

        val sc = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        sc.addView(list)

        for (i in snaps.indices) {
            val s = snaps[i]
            val rowHead = TextView(this)
            rowHead.text = if (i == 0) "● 最新  ${s.time}" else "○ 历史 ${i + 1}  ${s.time}"
            rowHead.textSize = 10.5f
            rowHead.setTextColor(if (i == 0) 0xFF3DD68C.toInt() else 0xFFF5A524.toInt())
            rowHead.setPadding(0, if (i == 0) 0 else dp(10f), 0, dp(2f))
            list.addView(rowHead)

            val body = TextView(this)
            body.text = s.text
            body.textSize = 13f
            body.setTextColor(if (i == 0) 0xFFFFFFFF.toInt() else 0xB3C7D2E0.toInt())
            list.addView(body)
        }

        box.addView(sc)

        val p = WindowManager.LayoutParams(
            (screenW * 0.9f).toInt(),
            (screenH * 0.45f).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = (screenW * 0.05f).toInt()
        p.y = (regionH * 0.12f).toInt()

        try {
            wm.addView(box, p)
            historyWin = box
        } catch (_: Throwable) {
            historyWin = null
        }
    }

    // ---------- 常驻 ----------

    private val watchdog = object : Runnable {
        override fun run() {
            updateNotification()
            main.postDelayed(this, 30000L)
        }
    }

    private fun accEnabled(): Boolean {
        return try {
            val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
        } catch (t: Throwable) {
            false
        }
    }

    private fun startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CH_ID) == null) {
                    val ch = NotificationChannel(CH_ID, "覆盖翻译", NotificationManager.IMPORTANCE_MIN)
                    ch.setShowBadge(false)
                    nm.createNotificationChannel(ch)
                }
            }
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTI_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTI_ID, n)
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            foregroundStarted = false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH_ID)
        else
            @Suppress("DEPRECATION")
            Notification.Builder(this)

        val acc = accEnabled()
        val title = when {
            !acc -> "无障碍已关闭"
            !Prefs.translating -> "翻译已暂停"
            else -> "覆盖翻译运行中"
        }
        val text = when {
            !acc -> "点此重新开启无障碍取词"
            !Prefs.translating -> "点此回到设置"
            else -> "屏幕上 60% 区域显示" +
                (if (Prefs.tgtLang == Languages.TARGET_EN) "英文" else "中文")
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTI_ID, buildNotification())
        } catch (ex: Throwable) {
            Log.w(TAG, "OverlayService@859: " + ex.message)
        }
    }

    private fun refreshToggle() {
        toggleBtn?.text = if (Prefs.translating) "暂停" else "开始"
        toggleBtn?.setTextColor(if (Prefs.translating) 0xFF6FB4FF.toInt() else 0xFF3DD68C.toInt())
    }

    // ---------- 生命周期 ----------

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (inst === this) inst = null
        // 覆盖层没了就不该继续截屏：所有退出路径（✕ 按钮、被系统回收、设置页关闭）都走这里
        AppCore.syncOcrService()
        hideHistory()
        for (v in listOfNotNull(blockLayer, lineWin, bar)) {
            try {
                wm.removeView(v)
            } catch (ex: Throwable) {
                Log.w(TAG, "OverlayService@877: " + ex.message)
            }
        }
        blockLayer = null
        lineWin = null
        bar = null
        try {
            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ex: Throwable) {
            Log.w(TAG, "OverlayService@885: " + ex.message)
        }
        super.onDestroy()
    }

    private fun dp(v: Float): Int = (resources.displayMetrics.density * v).toInt()
}
