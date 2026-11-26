package com.shishifanyi.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 中枢：取词（带矩形） -> 筛选 -> 逐块翻译 -> 覆盖显示。
 */
object AppCore {
    private const val TAG = "LiveTranslate"

    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** 过滤 / 合并 / 取词判定的串行线程，避免占用主线程。 */
    private val filterWorker = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastKey: String = ""

    /** 每次取词批次自增；旧批次的翻译结果一律丢弃，避免盖住新屏幕。 */
    @Volatile
    private var gen: Int = 0

    /** 正在处理中的屏幕指纹；与 lastKey 分开，避免"没画上去却认账"。 */
    @Volatile
    private var pendingKey: String = ""

    @Volatile
    private var ocrBusy: Boolean = false

    @Volatile
    private var lastCaptureAt: Long = 0L

    /** 连续几次没有可用内容后，暂缓取词，避免空转耗电。 */
    @Volatile
    private var emptyStreak = 0

    @Volatile
    private var backoffUntil = 0L

    private const val MIN_CAPTURE_GAP = 600L

    fun init(c: Context) {
        if (app == null) {
            app = c.applicationContext
            Prefs.init(c)
        }
    }

    /** 翻译开关或 OCR 开关变化后，按需启停截屏服务，暂停时不留常驻。 */
    fun syncOcrService() {
        val ctx = app ?: return
        try {
            // token 用掉后 hasPermission() 会变 false，但服务可能正跑着；两个条件都要认
            val usable = OcrService.hasPermission() || OcrService.isRunning()
            if (Prefs.translating && Prefs.ocrFallback && usable && OverlayService.isShowing()) {
                OcrService.start(ctx)
            } else {
                OcrService.stop(ctx)
            }
        } catch (ex: Throwable) {
            Log.w(TAG, "AppCore@50: " + ex.message)
        }
    }

    fun resetDedupe() {
        lastKey = ""
        pendingKey = ""
    }

    fun screenHeight(): Int {
        val c = app ?: return 0
        // 必须和 OverlayService.applyScreenMetrics() 用同一套口径，
        // 否则分屏 / 手势导航时区域边界与渲染窗口坐标系对不上
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = c.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val b = wm.maximumWindowMetrics.bounds
                if (b.height() > 0) return b.height()
            } catch (ex: Throwable) {
                Log.w(TAG, "AppCore@70: " + ex.message)
            }
        }
        return c.resources.displayMetrics.heightPixels
    }

    /** 处理区域的上下边界（相对屏幕顶部）。 */
    fun regionBounds(): Pair<Int, Int> {
        val h = screenHeight()
        val r = Prefs.regionRatio
        return if (Prefs.regionFromTop) {
            Pair(0, (h * r).toInt())
        } else {
            Pair((h * (1f - r)).toInt(), h)
        }
    }

    /**
     * 无障碍读到的一屏文字块。
     * OCR 可用时优先用 OCR 行框（像素级准确），节点矩形只作兜底。
     */
    fun onBlocks(blocks: List<TextBlock>) {
        if (!Prefs.translating) return
        // 悬浮窗没开就没有人消费译文，白翻译只是发热耗电
        if (!OverlayService.isShowing()) return
        // 过滤与按行合并是纯 CPU 活，一屏上千节点时放主线程会直接掉帧；
        // 用单独的单线程串行处理，顺带保证 emptyStreak 这类计数不会并发写坏
        filterWorker.execute { handleBlocks(blocks) }
    }

    private fun handleBlocks(blocks: List<TextBlock>) {
        val (topY, maxY) = regionBounds()

        val now = android.os.SystemClock.elapsedRealtime()
        if (now < backoffUntil) return

        val stats = FilterStats()
        val filtered = TextPipeline.mergeLines(
            TextPipeline.filter(blocks, maxY, Prefs.srcLang, topY, stats),
            30
        )
        Log.d(
            TAG,
            "取词 " + blocks.size + " 块，可用 " + filtered.size + "（" + stats.brief() + "）"
        )

        // 整屏基本是中文：本来就没有可翻内容，不该退避（否则切到英文页要等 5 秒），也不必走 OCR
        val mostlyChinese = stats.chinese > 0 && stats.chinese * 2 >= stats.total

        if (filtered.isEmpty()) {
            if (mostlyChinese) {
                emptyStreak = 0
                return
            }
            emptyStreak++
            // 连续 3 次空白就退避：避免屏幕静止时反复抓取
            if (emptyStreak >= 3) {
                backoffUntil = now + 5000L
                Log.d(TAG, "连续无内容，暂停取词 5 秒")
            }
            // 无障碍读不到字，才动 OCR
            if (useOcr()) captureOcr(topY, maxY)
            return
        }
        emptyStreak = 0

        // 性能优先：矩形看着正常就直接用，不必截图；
        // 精准模式：每次都走 OCR，坐标更准但更费电。
        if (Prefs.accurateMode || TextPipeline.looksFragmented(filtered)) {
            if (useOcr()) {
                captureOcr(topY, maxY)
                return
            }
        }
        process(filtered)
    }

    /**
     * 这一块是不是已经是目标语言（目前只用于目标=英语）。
     *
     * 只看**非 ASCII 字母**：西语的 á、法语的 é、德语的 ü 都在这里被排除。
     * 不能看"任何非 ASCII 字符"——英文里的破折号 —、弯引号 ' ' 也是非 ASCII，
     * 那样会把整段英文误判成"不是英文"照翻一遍。
     * 剩下全 ASCII 字母的才交给 ML Kit 确认，避免把没有变音符号的西语当成英文漏翻。
     */
    private fun blockAlreadyTarget(text: String, tgt: String): Boolean {
        for (c in text) {
            if (c.isLetter() && c.code > 127) return false
        }
        val latch = CountDownLatch(1)
        var hit = false
        LanguageDetector.detectStrict(text) { lang ->
            hit = (lang == tgt)
            latch.countDown()
        }
        latch.await(300, TimeUnit.MILLISECONDS)
        return hit
    }

    private fun useOcr(): Boolean = Prefs.ocrFallback && OcrBridge.available()

    private fun captureOcr(topY: Int, maxY: Int) {
        if (ocrBusy) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCaptureAt < MIN_CAPTURE_GAP) return
        lastCaptureAt = now
        ocrBusy = true
        OcrBridge.captureBlocks { list ->
            ocrBusy = false
            if (!Prefs.translating) return@captureBlocks
            // 和无障碍路径一样要带上 minY：从底部往上的区域，上半屏不该翻
            val filtered = TextPipeline.filter(list, maxY, Prefs.srcLang, topY)
            if (filtered.isNotEmpty()) process(filtered)
        }
    }

    private fun process(blocks: List<TextBlock>) {
        // 指纹必须带位置：只按文本做 key 时，同一段文字换了位置（滚动、重新排版）
        // 会被当成"这一屏已经处理过"，译文就停在原地不动。
        // 坐标按 4px 量化，避免一两个像素的抖动导致反复重翻。
        val key = blocks.joinToString("|") {
            it.text + "@" + (it.rect.left / 4) + "," + (it.rect.top / 4)
        }
        // lastKey 只记「已经画上去的屏幕」；pendingKey 记「正在处理」，
        // 两者都不等于当前屏才需要再跑——否则截断后同一屏永远不会重试
        if (key == lastKey || key == pendingKey) return
        pendingKey = key
        val myGen = ++gen

        worker.execute {
            // 自动检测：整屏文字先判语言，再逐块翻
            var src = Prefs.srcLang
            if (src == "auto") {
                val joined = blocks.joinToString(" ") { it.text }.take(400)
                val latch0 = CountDownLatch(1)
                var detected = "en"
                LanguageDetector.detect(joined) { lang ->
                    detected = lang
                    latch0.countDown()
                }
                latch0.await(3, TimeUnit.SECONDS)
                src = detected
            }

            val t0 = android.os.SystemClock.elapsedRealtime()
            val tgt = Prefs.tgtLang
            // 源语言就是目标语言：没有可翻的内容。
            // 这种情况以前会把原文当译文画上去（看起来"翻了但没变"），必须直接清屏跳过。
            if (src == tgt) {
                Log.d(TAG, "源语言与目标相同（$src），跳过并清屏")
                main.post {
                    if (gen == myGen) OverlayService.showBlocks(emptyList())
                }
                return@execute
            }
            // 整屏总预算 8 秒：30 块 × 4 秒会拖到两分钟，用户会以为卡死
            val deadline = t0 + 8000L
            var failed = 0
            var skipped = 0
            val pairs = ArrayList<Pair<TextBlock, String>>(blocks.size)
            for (b in blocks) {
                if (gen != myGen) break
                if (android.os.SystemClock.elapsedRealtime() > deadline) break
                // 目标是英语时，逐块判断这块是不是本来就英文；是就不翻，避免"英译英"盖一层块。
                // 必须逐块：一屏里中英西混排时，整屏判定只会按多数语言一刀切。
                if (tgt == Languages.TARGET_EN && src != Languages.TARGET_EN &&
                    blockAlreadyTarget(b.text, tgt)
                ) {
                    skipped++
                    continue
                }
                // 命中缓存的不再调用模型
                val hit = Translator.peek(src, tgt, b.text)
                if (hit != null && hit.isNotBlank()) {
                    pairs.add(Pair(b, hit))
                    continue
                }
                var zh = ""
                var ok = false
                val latch = CountDownLatch(1)
                Translator.translateWith(src, tgt, b.text) { err, t ->
                    if (err == null && t.isNotBlank()) {
                        zh = t
                        ok = true
                    }
                    latch.countDown()
                }
                latch.await(2, TimeUnit.SECONDS)
                if (ok) {
                    pairs.add(Pair(b, zh))
                } else {
                    failed++
                }
            }
            val cost = android.os.SystemClock.elapsedRealtime() - t0
            Log.d(
                TAG,
                "翻译 " + pairs.size + "/" + blocks.size + " 块，耗时 " + cost + "ms，语言 " + src +
                    (if (failed > 0) "，失败 " + failed + " 块" else "") +
                    (if (skipped > 0) "，本就是目标语言 " + skipped + " 块" else "")
            )
            main.post {
                try {
                    if (gen != myGen) return@post
                    if (!Prefs.translating) return@post
                    if (pairs.isEmpty()) {
                        // 这一屏什么都没翻出来：把旧块清掉，
                        // 否则刷新/滚动后旧译文会一直停在屏幕上
                        OverlayService.showBlocks(emptyList())
                        return@post
                    }
                    OverlayService.showBlocks(pairs)
                    // 真的画上去才算这一屏处理过
                    lastKey = key
                    val joined = pairs.joinToString("\n") { it.second }
                    OverlayService.pushHistory(joined)
                } finally {
                    // 没画成功就放开，下次同一屏还能重试
                    if (pendingKey == key) pendingKey = ""
                }
            }
        }
    }
}
