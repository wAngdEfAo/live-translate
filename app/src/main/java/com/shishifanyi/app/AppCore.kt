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

    /** 每次取词批次自增；旧批次的翻译结果一律丢弃，避免盖住新屏幕。
     *  process() 会被 filterWorker 与主线程（OCR 回调）同时调用，`++gen` 不是原子操作，
     *  两批可能拿到同一个号 → 旧结果盖掉新屏，所以用原子量。 */
    private val gen = java.util.concurrent.atomic.AtomicInteger(0)

    /** 正在处理中的屏幕指纹；与 lastKey 分开，避免"没画上去却认账"。 */
    @Volatile
    private var pendingKey: String = ""

    @Volatile
    private var ocrBusy: Boolean = false

    /** 本次 OCR 的起始时刻；回调丢失时靠它判断「忙碌」是不是僵住了。 */
    @Volatile
    private var ocrStartedAt: Long = 0L

    /** OCR 回合号：迟到的旧回调不许动新回合的忙碌标记。 */
    @Volatile
    private var ocrRound: Int = 0

    @Volatile
    private var lastCaptureAt: Long = 0L

    /** 连续几次没有可用内容后，暂缓取词，避免空转耗电。 */
    @Volatile
    private var emptyStreak = 0

    @Volatile
    private var backoffUntil = 0L

    /** 连续几次「整屏一块都没翻成」；模型没下载时屏幕事件会把整批重跑，必须退避。 */
    @Volatile
    private var failStreak = 0

    /** 连续几次「预算用完只翻了一部分」；同样要退避，否则会一直卡在前几块。 */
    @Volatile
    private var truncStreak = 0

    /** truncStreak 是给哪一屏累计的：换屏必须清零，否则新屏一上来就吃 5 秒退避。 */
    @Volatile
    private var truncKey: String = ""

    private const val MIN_CAPTURE_GAP = 600L

    /** 截断标记：拼在指纹后面，表示「这一屏只处理了一部分」。 */
    private const val PARTIAL_SUFFIX = "#part"

    /** OCR 回调超过这个时间没回来，就认为它不会再回来了。 */
    private const val OCR_STALE_MS = 6000L

    fun init(c: Context) {
        if (app == null) {
            app = c.applicationContext
            Prefs.init(c)
            // 上次替换模型目录时如果在 rename 与 delete 之间被杀，会留下 ~147MB 的
            // packs/<key>.old-* 目录，一直算进存储占用。启动时清一次（导入时也会清）。
            try {
                Thread {
                    try {
                        EnginePack.sweepOrphans(c.applicationContext)
                    } catch (t: Throwable) {
                        Log.w(TAG, "启动清理残留失败: " + t.message)
                    }
                }.start()
            } catch (t: Throwable) {
                Log.w(TAG, "启动清理线程失败: " + t.message)
            }
        }
    }

    /** 供引擎层拿应用上下文（模型文件路径需要）。 */
    fun context(): Context? = app

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

    /**
     * 屏幕像素尺寸的唯一口径。
     * 必须先取 maximumWindowMetrics：分屏、折叠屏、带任务栏时 displayMetrics
     * 与窗口实际可用区域并不相等，OCR 行框和区域过滤一旦各用一套就会错位或漏块。
     */
    fun screenSize(): Pair<Int, Int> {
        val c = app ?: return 0 to 0
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = c.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val b = wm.maximumWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
            } catch (ex: Throwable) {
                Log.w(TAG, "AppCore@82: " + ex.message)
            }
        }
        val dm = c.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun screenHeight(): Int {
        // 必须和 OverlayService.applyScreenMetrics() 用同一套口径，
        // 否则分屏 / 手势导航时区域边界与渲染窗口坐标系对不上
        return screenSize().second
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
     * @param fromNullRoot 这次「空」是因为压根拿不到根节点（WebView/微信阅读器），
     *   而不是内容被判空 —— 后者才该退避，前者退避会让这类界面 5 秒才抓一帧
     */
    fun onBlocks(blocks: List<TextBlock>, fromNullRoot: Boolean = false) {
        if (!Prefs.translating) return
        // 悬浮窗没开就没有人消费译文，白翻译只是发热耗电
        if (!OverlayService.isShowing()) return
        // 过滤与按行合并是纯 CPU 活，一屏上千节点时放主线程会直接掉帧；
        // 用单独的单线程串行处理，顺带保证 emptyStreak 这类计数不会并发写坏
        filterWorker.execute { handleBlocks(blocks, fromNullRoot) }
    }

    private fun handleBlocks(blocks: List<TextBlock>, fromNullRoot: Boolean) {
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
        // 分母只算「在区域内、过了尺寸与长度基本检查」的块。用 total 会把出区、太小、
        // 太短的噪声算进去，中文页判不出「整屏中文」，于是每 600ms 拉起一次截屏镜像白白耗电
        val usable = stats.chinese + stats.kept + stats.script +
            stats.lowLetters + stats.url + stats.masked
        val mostlyChinese = stats.chinese > 0 && stats.chinese * 2 >= usable

        if (filtered.isEmpty()) {
            if (mostlyChinese) {
                emptyStreak = 0
                return
            }
            if (fromNullRoot) {
                // 拿不到根节点（微信阅读器 / 内嵌 WebView）：这类界面永远"空"，
                // 退避会让它 5 秒才抓一帧。只走正常的取词间隔（MIN_CAPTURE_GAP），不累计退避。
                emptyStreak = 0
                if (useOcr()) {
                    captureOcr(topY, maxY)
                } else {
                    hintNoSource()
                }
                return
            }
            emptyStreak++
            // 连续 3 次空白就退避：避免屏幕静止时反复抓取
            if (emptyStreak >= 3) {
                backoffUntil = now + 5000L
                Log.d(TAG, "连续无内容，暂停取词 5 秒")
            }
            // 无障碍读不到字，才动 OCR
            if (useOcr()) {
                captureOcr(topY, maxY)
            } else {
                hintNoSource()
            }
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

    /** 全 ASCII 字母（无变音符号）才需要额外确认，避免把西语/法语误当英文。 */
    private fun allAsciiLetters(text: String): Boolean {
        for (c in text) {
            if (c.isLetter() && c.code > 127) return false
        }
        return true
    }

    /** 只提示一次：安全窗口或 OCR 不可用，否则每次内容变化都弹一次太吵。 */
    @Volatile
    private var secureHintShown = false

    @Volatile
    private var noSourceHintShown = false

    /** 一屏纯黑且 OCR 也读不出字：可能是安全窗口，也可能只是全黑画面。文案要保守。 */
    private fun hintSecureWindow() {
        if (secureHintShown) return
        secureHintShown = true
        val c = app ?: return
        Log.w(TAG, "这一屏接近纯黑且 OCR 无输出，判定为不可识别")
        main.post {
            try {
                android.widget.Toast.makeText(
                    c, "这一屏没有可识别的文字（可能是禁止截屏的界面）", android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                Log.w(TAG, "提示失败: " + t.message)
            }
        }
    }

    /** 无障碍读不到、OCR 又不可用：告诉用户该去开哪个开关。 */
    private fun hintNoSource() {
        if (noSourceHintShown) return
        noSourceHintShown = true
        val c = app ?: return
        Log.w(TAG, "无障碍读不到内容，且 OCR 兜底不可用")
        main.post {
            try {
                android.widget.Toast.makeText(
                    c,
                    "读不到屏幕文字：请打开「OCR 兜底」并授权截屏（微信/网页类界面需要）",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                Log.w(TAG, "提示失败: " + t.message)
            }
        }
    }

    /**
     * 只有日/韩这种「文字过滤分不出英文标签」的源语言，才把纯拉丁行按英语处理。
     * 其它源语言（法/西/德/俄…）的拉丁行必须按原语言路由，否则会绕过 X→en→目标 的中转。
     */
    private fun isCjkSourceWithLatinLabels(src: String): Boolean = src == "ja" || src == "ko"

    /** 目标语言判定结果缓存：句 → 语言码（只存成功结果，失败不入缓存） */
    private val detectCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 256
    }

    @Synchronized
    private fun cachedLang(text: String): Pair<Boolean, String?> {
        val v = detectCache[text] ?: return false to null
        return true to v
    }

    @Synchronized
    private fun rememberLang(text: String, lang: String) {
        detectCache[text] = lang
    }

    /**
     * 整屏批量判定：之前逐块 await(300ms)，30 块最坏 9 秒，超出 8 秒预算后
     * 后面的块直接被 break 掉。改成一次性并发提交 + 总预算等待 + 结果缓存。
     */
    private fun detectBatch(texts: List<String>, budgetMs: Long): Map<String, String?> {
        // 用并发容器：await 超时返回后回调可能仍在写，普通 HashMap 跨线程读写不安全
        val result = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pending = ArrayList<String>(texts.size)
        for (t in texts) {
            val (has, lang) = cachedLang(t)
            // 命中且确定语言才写入；命中但识别不出（null）当作未知，不进入结果
            if (has && lang != null) result[t] = lang else if (!has) pending.add(t)
        }
        if (pending.isEmpty()) {
            val early = HashMap<String, String?>(result.size * 2)
            for ((k, v) in result) early[k] = v
            return early
        }

        val latch = CountDownLatch(pending.size)
        for (t in pending) {
            LanguageDetector.detectStrict(t) { lang ->
                // 只缓存成功结果：超时/失败写成"und"会让这句永久不再重判
                if (lang != null) {
                    result[t] = lang
                    rememberLang(t, lang)
                }
                latch.countDown()
            }
        }
        latch.await(budgetMs, TimeUnit.MILLISECONDS)
        // 返回快照，调用方拿到的是稳定副本
        val snapshot = HashMap<String, String?>(result.size * 2)
        for ((k, v) in result) snapshot[k] = v
        return snapshot
    }

    private fun useOcr(): Boolean = Prefs.ocrFallback && OcrBridge.available()

    private fun captureOcr(topY: Int, maxY: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (ocrBusy) {
            // OCR 服务中途死掉（系统回收 / 用户撤销投影）时回调永远不会来，
            // 没有超时兜底就会永久卡在「忙碌」，OCR 静默失效直到进程重启
            if (now - ocrStartedAt < OCR_STALE_MS) return
            Log.w(TAG, "OCR 回调超时未归，复位忙碌标记")
            ocrBusy = false
            // 让上一轮那个迟到的回调作废，否则它回来时会把新一轮的忙碌标记清掉，
            // 两轮抓取就会叠在一起
            ocrRound++
        }
        if (now - lastCaptureAt < MIN_CAPTURE_GAP) return
        lastCaptureAt = now
        ocrStartedAt = now
        ocrBusy = true
        val round = ++ocrRound
        OcrBridge.captureBlocks { list ->
            // 只认自己这一轮：迟到的旧回调不许动新回合的状态
            if (round == ocrRound) ocrBusy = false
            if (!Prefs.translating) return@captureBlocks
            if (list.isEmpty() && OcrBridge.consumeSecureFrame()) hintSecureWindow()
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
        val myGen = gen.incrementAndGet()

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
                    if (gen.get() == myGen) OverlayService.showBlocks(emptyList())
                }
                return@execute
            }
            // 整屏总预算 8 秒：30 块 × 4 秒会拖到两分钟，用户会以为卡死
            val deadline = t0 + 8000L
            var failed = 0
            var skipped = 0
            // 目标=英语且源不是英语时，先整屏批量判定哪些块本来就是英文
            val needTargetCheck = tgt == Languages.TARGET_EN && src != Languages.TARGET_EN
            val detected: Map<String, String?> = if (needTargetCheck) {
                val cands = blocks.map { it.text }.filter { allAsciiLetters(it) }.distinct()
                detectBatch(cands, 1200L)
            } else {
                emptyMap()
            }

            val pairs = ArrayList<Pair<TextBlock, String>>(blocks.size)
            // 本轮是否被预算/代次截断：截断时不能把整屏指纹认作「已处理」，
            // 否则没轮到的后半屏要等内容或位置变才会重翻
            var truncated = false
            // 引擎可用性整屏只判一次（含偏好与已装模型），逐块只做纯内存判断
            val reach = app?.let { Engines.reach(it) }
            for (b in blocks) {
                if (gen.get() != myGen) {
                    truncated = true
                    break
                }
                if (android.os.SystemClock.elapsedRealtime() > deadline) {
                    truncated = true
                    break
                }
                // 目标是英语时，逐块判断这块是不是本来就英文；是就不翻，避免"英译英"盖一层块。
                // 必须逐块：一屏里中英西混排时，整屏判定只会按多数语言一刀切。
                if (needTargetCheck && allAsciiLetters(b.text) && detected[b.text] == tgt) {
                    skipped++
                    continue
                }
                // 命中缓存的不再调用模型
                // 拉丁字母行只在「日/韩源语言」时才按英语处理：这两个语言的文字过滤看不出
                // 英文标签，直接喂 ja→en 模型只是白白多一跳。
                // 其他源语言绝不能这么干：法语/西语/德语里没有变音符号的行会被当成英语，
                // 目标=中文时走 en→zh 翻出垃圾，目标=英文时干脆被 skipped 掉整屏不翻。
                val effSrc = if (isCjkSourceWithLatinLabels(src) && allAsciiLetters(b.text)) {
                    Languages.TARGET_EN
                } else {
                    src
                }
                if (effSrc == tgt) {
                    // 本来就是目标语言（拉丁标签行在英文目标下常见）
                    skipped++
                    continue
                }
                // 引擎按设置挑：默认 ML Kit；选了高精度且是英⇄中长句时才用 ONNX
                var engine = if (reach != null) Engines.pick(reach, effSrc, tgt, b.text)
                else Engines.pick(effSrc, tgt, b.text)
                // 这一句上一轮刚超时过：直接走 ML Kit。
                // 否则截断重试每轮从第 0 块重来，那几块又要先白等 6 秒。
                if (engine === OnnxEngine && OnnxEngine.coolingDown(b.text)) {
                    engine = MlKitEngine
                }
                val hit = engine.peek(effSrc, tgt, b.text)
                if (hit != null && hit.isNotBlank()) {
                    pairs.add(Pair(b, hit))
                    continue
                }
                var zh = ""
                var ok = false
                val latch = CountDownLatch(1)
                engine.translate(effSrc, tgt, b.text) { err, t ->
                    if (err == null && t.isNotBlank()) {
                        zh = t
                        ok = true
                    }
                    latch.countDown()
                }
                // 高精度单句可能要一两秒，给它更长的等待；ML Kit 保持原来的 2 秒
                val finished = latch.await(if (engine === OnnxEngine) 6000L else 2L, TimeUnit.SECONDS)
                // 先取一次状态：等我们自己回退完，closeNow 可能已经把 releasePending 清掉了
                val releasing = engine === OnnxEngine && OnnxEngine.isReleasing()
                if (!ok && engine === OnnxEngine) {
                    // 只有「等到超时」才进冷却。会话被内存压力关掉、模型被删、加载失败
                    // 都是错误路径，下一句应当重新加载再试，而不是让接着两分钟所有长句
                    // 静默降级成 ML Kit（5.5.7 里 releaseUnderPressure 正好会踩到）。
                    if (!finished) OnnxEngine.markSlow(b.text)
                    // 高精度失败不能让这一块留空：退回 ML Kit 再试一次。
                    val fallback = CountDownLatch(1)
                    MlKitEngine.translate(effSrc, tgt, b.text) { err, t ->
                        if (err == null && t.isNotBlank()) {
                            zh = t
                            ok = true
                        }
                        fallback.countDown()
                    }
                    fallback.await(2, TimeUnit.SECONDS)
                    if (ok) {
                        when {
                            // 释放进行中：这是内存压力导致的，不是翻译失败，别让人误读日志
                            releasing -> Log.d(TAG, "高精度会话正在释放，本轮改用 ML Kit")
                            !finished -> Log.d(TAG, "高精度超时，已回退 ML Kit")
                            else -> Log.d(TAG, "高精度报错，已回退 ML Kit")
                        }
                    }
                }
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
                    if (gen.get() != myGen) return@post
                    if (!Prefs.translating) return@post
                    if (pairs.isEmpty()) {
                        // 这一屏什么都没翻出来：把旧块清掉，
                        // 否则刷新/滚动后旧译文会一直停在屏幕上
                        OverlayService.showBlocks(emptyList())
                        if (failed > 0) {
                            // 全失败（常见：模型还没下载）。不写 lastKey，恢复后还能重试；
                            // 但必须退避，否则每个无障碍事件都会再跑满一次 8 秒预算
                            failStreak++
                            if (failStreak >= 2) {
                                backoffUntil =
                                    android.os.SystemClock.elapsedRealtime() + 5000L
                                Log.d(
                                    TAG,
                                    "连续 " + failStreak + " 次整屏翻译失败，暂停取词 5 秒"
                                )
                            }
                        } else {
                            // 全是「本来就是目标语言」被跳过：这一屏确实处理完了
                            failStreak = 0
                            lastKey = key
                        }
                        return@post
                    }
                    failStreak = 0
                    OverlayService.showBlocks(pairs)
                    if (truncated) {
                        // 只翻译了一部分：不能记成「整屏已处理」，否则后半屏再也不翻；
                        // 但也不能不设防 —— 否则以 300–600ms 的节奏无限重跑，
                        // 每轮都翻出前一两块再超时，永远推进不到后面。
                        // 计数按屏幕隔离：换了屏就是新的一轮，不该继承上一屏的退避级别。
                        if (truncKey != key) {
                            truncKey = key
                            truncStreak = 0
                        }
                        truncStreak++
                        lastKey = key + PARTIAL_SUFFIX + truncStreak
                        val wait = if (truncStreak >= 2) 5000L else 1200L
                        backoffUntil = android.os.SystemClock.elapsedRealtime() + wait
                        Log.d(TAG, "本轮被预算截断（第 $truncStreak 次），退避 ${wait}ms")
                    } else {
                        truncStreak = 0
                        truncKey = ""
                        lastKey = key
                    }
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
