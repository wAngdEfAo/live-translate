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

    @Volatile
    private var lastKey: String = ""

    /** 每次取词批次自增；旧批次的翻译结果一律丢弃，避免盖住新屏幕。 */
    @Volatile
    private var gen: Int = 0

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

    fun context(): Context? = app

    /** 翻译开关或 OCR 开关变化后，按需启停截屏服务，暂停时不留常驻。 */
    fun syncOcrService() {
        val ctx = app ?: return
        try {
            if (Prefs.translating && Prefs.ocrFallback && OcrService.hasPermission() &&
                OverlayService.isShowing()
            ) {
                OcrService.start(ctx)
            } else {
                OcrService.stop(ctx)
            }
        } catch (ex: Throwable) {
            Log.w(TAG, "AppCore@57: " + ex.message)
        }
    }

    fun resetDedupe() {
        lastKey = ""
    }

    fun screenHeight(): Int {
        val c = app ?: return 0
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
            if (useOcr()) captureOcr(maxY)
            return
        }
        emptyStreak = 0

        // 性能优先：矩形看着正常就直接用，不必截图；
        // 精准模式：每次都走 OCR，坐标更准但更费电。
        if (Prefs.accurateMode || TextPipeline.looksFragmented(filtered)) {
            if (useOcr()) {
                captureOcr(maxY)
                return
            }
        }
        process(filtered)
    }

    private fun useOcr(): Boolean = Prefs.ocrFallback && OcrBridge.available()

    private fun captureOcr(maxY: Int) {
        if (ocrBusy) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCaptureAt < MIN_CAPTURE_GAP) return
        lastCaptureAt = now
        ocrBusy = true
        OcrBridge.captureBlocks { list ->
            ocrBusy = false
            if (!Prefs.translating) return@captureBlocks
            val filtered = TextPipeline.filter(list, maxY, Prefs.srcLang)
            if (filtered.isNotEmpty()) process(filtered)
        }
    }

    private fun process(blocks: List<TextBlock>) {
        val key = blocks.joinToString("|") { it.text }
        if (key == lastKey) return
        lastKey = key
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
            // 整屏总预算 8 秒：30 块 × 4 秒会拖到两分钟，用户会以为卡死
            val deadline = t0 + 8000L
            var failed = 0
            val pairs = ArrayList<Pair<TextBlock, String>>(blocks.size)
            for (b in blocks) {
                if (gen != myGen) return@execute
                if (android.os.SystemClock.elapsedRealtime() > deadline) break
                // 命中缓存的不再调用模型
                val hit = Translator.peek(src, b.text)
                if (hit != null && hit.isNotBlank()) {
                    pairs.add(Pair(b, hit))
                    continue
                }
                var zh = ""
                var ok = false
                val latch = CountDownLatch(1)
                Translator.translateWith(src, b.text) { err, t ->
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
                    (if (failed > 0) "，失败 " + failed + " 块" else "")
            )
            main.post {
                if (gen != myGen) return@post
                if (!Prefs.translating) return@post
                if (pairs.isEmpty()) return@post
                OverlayService.showBlocks(pairs)
                val joined = pairs.joinToString("\n") { it.second }
                OverlayService.pushHistory(joined)
            }
        }
    }
}
