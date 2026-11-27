package com.shishifanyi.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 高精度引擎：OPUS-MT（Marian，英⇄中）的 int8 ONNX 推理。
 *
 * 图被拆成两个：encoder 一次前向出隐状态，decoder 每步吃「完整前缀」再出 logits。
 * 不用 KV cache —— 图不依赖 past/present，导出稳定、手机端只跑两个会话；
 * 代价是解码 O(n²)，实测桌面 34ms/句（96 token 上限），手机上量级仍可接受。
 *
 * 只在「手动选了高精度」且语言对是 en⇄中时才用；实时覆盖默认仍走 ML Kit。
 */
object OnnxEngine : TransEngine {

    private const val TAG = "OnnxEngine"
    private const val MAX_NEW_TOKENS = 96
    private const val CACHE_MAX = 60

    /** 去重表上限：超过就退化为不去重，避免无限增长。 */
    private const val MAX_INFLIGHT = 32

    /** 超时过的句子在这么久内不再走高精度。 */
    private const val COOLDOWN_MS = 120_000L

    /** 压力释放后多久之内不重建会话。 */
    private const val PRESSURE_COOL_MS = 60_000L
    private const val IDLE_MS = 180_000L

    override val id: String = "onnx"

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "onnx-translate").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicInteger(0)

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_MAX
    }

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var enc: OrtSession? = null

    @Volatile
    private var dec: OrtSession? = null

    @Volatile
    private var tokenizer: MarianTokenizer? = null

    @Volatile
    private var loadedKey: String? = null

    /** 内存压力下请求的「等推理退出后释放」。 */
    @Volatile
    private var releasePending = false

    /** 压力释放后的静默期截止时间：这段时间内不再重建会话。 */
    @Volatile
    private var noLoadUntil = 0L

    private val idleClose = Runnable { release() }

    /** 同句去重：同一句已经在队列里跑，就把回调挂上去，别重复压任务。 */
    private val inflight = HashMap<String, MutableList<(String?, String) -> Unit>>()

    fun supports(src: String, tgt: String): Boolean = EnginePack.pairKey(src, tgt) != null

    /** 至少装了一个方向，用于整屏判一次是否启用高精度。 */
    fun isAnyInstalled(): Boolean {
        val ctx = AppCore.context() ?: return false
        return installedNow(ctx).isNotEmpty()
    }

    /**
     * 已装方向集合的短时缓存：`Engines.reach` 只在整屏开头算一次，
     * 但 translate() 每块还会来问一次「这个方向装了吗」，一次就是 3 次 stat + 清单查表。
     * 5 秒 TTL 足够，因为装机/删包不会在一屏之内发生。
     */
    @Volatile
    private var installedAt = 0L

    @Volatile
    private var installedPairs: Set<String> = emptySet()

    private fun installedNow(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        val cached = installedPairs
        if (now - installedAt < 5000L && cached.isNotEmpty()) return cached
        val fresh = try {
            EnginePack.installed(ctx).map { it.first }.toSet()
        } catch (t: Throwable) {
            Log.w(TAG, "读取已装模型失败: " + t.message)
            cached
        }
        installedPairs = fresh
        installedAt = now
        return fresh
    }

    override fun peek(src: String, tgt: String, text: String): String? = synchronized(cache) {
        cache[ck(src, tgt, text)]
    }

    private fun remember(key: String, value: String) = synchronized(cache) {
        cache[key] = value
    }

    private fun ck(src: String, tgt: String, text: String) = src + "|" + tgt + "|" + text

    override fun translate(
        src: String,
        tgt: String,
        text: String,
        cb: (err: String?, out: String) -> Unit
    ) {
        if (text.isBlank()) {
            cb(null, "")
            return
        }
        val key = ck(src, tgt, text)
        val hit = peek(src, tgt, text)
        if (hit != null) {
            cb(null, hit)
            return
        }
        val ctx = AppCore.context()
        if (ctx == null) {
            cb("引擎未初始化", "")
            return
        }
        val pair = EnginePack.pairKey(src, tgt)
        if (pair == null) {
            cb("高精度引擎只支持英⇄中", "")
            return
        }
        if (pair !in installedNow(ctx)) {
            cb("高精度模型未安装", "")
            return
        }
        // 同一句已经在队列里跑：挂到它的回调上，不再压一份任务。
        // 但表本身要有上限：屏幕滚动时 key 会不断变化，而 AppCore 6 秒后就不再等，
        // 回调留在表里直到任务跑完；不设上限这张表会一直涨。
        var registered = false
        synchronized(inflight) {
            val waiters = inflight[key]
            if (waiters != null) {
                waiters.add(cb)
                return
            }
            if (inflight.size < MAX_INFLIGHT) {
                inflight[key] = mutableListOf(cb)
                registered = true
            }
        }
        active.incrementAndGet()
        exec.execute {
            var err: String? = null
            var out = ""
            try {
                val loaded = ensureLoaded(ctx, pair)
                out = greedy(loaded.first, loaded.second.first, loaded.second.second, text)
                if (out.isNotBlank()) remember(key, out)
            } catch (t: Throwable) {
                Log.w(TAG, "推理失败: " + t.message)
                err = "高精度翻译失败：" + (t.message ?: "未知错误")
            } finally {
                active.decrementAndGet()
                // 推理已经结束（还是在本线程里），此时释放会话才是安全的
                if (releasePending && active.get() == 0) closeNow("内存压力（延迟）")
                scheduleIdleClose()
            }
            val waiters = if (registered) synchronized(inflight) { inflight.remove(key) } else null
            if (waiters != null) {
                for (w in waiters) {
                    try {
                        w(err, out)
                    } catch (t: Throwable) {
                        Log.w(TAG, "回调异常: " + t.message)
                    }
                }
            } else {
                cb(err, out)
            }
        }
    }

    private fun scheduleIdleClose() {
        try {
            main.removeCallbacks(idleClose)
            main.postDelayed(idleClose, IDLE_MS)
        } catch (ex: Throwable) {
            Log.w(TAG, "计时失败: " + ex.message)
        }
    }

    /** 确保目标方向的会话与分词器就位；返回 (词表, (encoder, decoder))。 */
    @Synchronized
    private fun ensureLoaded(
        ctx: Context,
        pair: String
    ): Pair<MarianTokenizer, Pair<OrtSession, OrtSession>> {
        if (loadedKey == pair) {
            val t = tokenizer
            val e = enc
            val d = dec
            if (t != null && e != null && d != null) return t to (e to d)
        }
        // 刚因内存压力释放过：这段时间内不重建，直接报错让调用方回退 ML Kit。
        // 与「失败回退」是同一条路径，只是省掉一次 146MB 的读盘与分配。
        val until = noLoadUntil
        if (until > 0L && System.currentTimeMillis() < until) {
            throw IllegalStateException("模型刚因内存压力释放，稍后重建")
        }
        closeSessions()
        tokenizer = null
        val dir = EnginePack.pairDir(ctx, pair)
        val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val e = OrtEnvironment.getEnvironment()
        // SessionOptions 持有原生对象，用完必须 close，否则每次重载都漏一份
        val opts = OrtSession.SessionOptions()
        val se: OrtSession
        val sd: OrtSession
        try {
            opts.setIntraOpNumThreads(nThreads)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            Log.d(TAG, "加载模型 $pair (threads=$nThreads)")
            se = e.createSession(File(dir, "encoder.onnx").absolutePath, opts)
            try {
                sd = e.createSession(File(dir, "decoder.onnx").absolutePath, opts)
            } catch (t: Throwable) {
                // decoder 建不起来（半截/损坏）时，已经建好的 encoder 会话必须关掉：
                // 这条路径每次翻译都会重试一次，不关就是每次漏 53MB 原生内存
                try {
                    se.close()
                } catch (t2: Throwable) {
                    Log.w(TAG, "回滚 encoder 会话失败: " + t2.message)
                }
                throw t
            }
        } finally {
            try {
                opts.close()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 SessionOptions 失败: " + t.message)
            }
        }
        val t = MarianTokenizer.fromJson(
            org.json.JSONObject(
                File(dir, "tokenizer.json").readText(Charsets.UTF_8)
            )
        )
        env = e
        enc = se
        dec = sd
        tokenizer = t
        loadedKey = pair
        return t to (se to sd)
    }

    /** 贪心解码：encoder 一次 + decoder 逐 token（无 KV cache）。 */
    private fun greedy(
        tok: MarianTokenizer,
        se: OrtSession,
        sd: OrtSession,
        text: String
    ): String {
        val e = env ?: throw IllegalStateException("环境未初始化")
        val srcIds = tok.encode(text)
        if (srcIds.isEmpty()) return ""
        val n = srcIds.size
        val srcBuf = LongArray(n) { srcIds[it].toLong() }
        val maskBuf = LongArray(n) { 1L }

        var encResult: OrtSession.Result? = null
        var maskTensor: OnnxTensor? = null
        try {
            val idsTensor = OnnxTensor.createTensor(
                e, LongBuffer.wrap(srcBuf), longArrayOf(1, n.toLong())
            )
            val srcMask = OnnxTensor.createTensor(
                e, LongBuffer.wrap(maskBuf), longArrayOf(1, n.toLong())
            )
            try {
                // Result 不能 close：它持有的隐状态张量后面每步都要用
                encResult = se.run(mapOf("input_ids" to idsTensor, "attention_mask" to srcMask))
            } finally {
                idsTensor.close()
                srcMask.close()
            }
            val hidden = encResult!!.get(0) as OnnxTensor

            maskTensor = OnnxTensor.createTensor(
                e, LongBuffer.wrap(maskBuf), longArrayOf(1, n.toLong())
            )
            val prefix = IntArray(MAX_NEW_TOKENS + 1)
            prefix[0] = tok.decoderStartId
            var len = 1
            // 贪心解码没有重复惩罚，Marian 偶尔会在个别句子上退化成死循环，
            // 连续吐出同一个 token 就提前收尾，别把 96 步全烧在这上面
            var prevTok = -1
            var sameRun = 0
            while (len < prefix.size) {
                // 内存压力要释放会话：不能等 96 步跑完（积压时那正是内存最紧的时刻）。
                // 抛出去让上层拿到错误并回退 ML Kit，同时 finally 里就能关会话。
                if (releasePending) throw IllegalStateException("会话被内存压力释放")
                val buf = LongArray(len) { prefix[it].toLong() }
                val dt = OnnxTensor.createTensor(e, LongBuffer.wrap(buf), longArrayOf(1, len.toLong()))
                var next = -1
                try {
                    sd.run(
                        mapOf(
                            "decoder_input_ids" to dt,
                            "encoder_hidden_states" to hidden,
                            "encoder_attention_mask" to maskTensor
                        )
                    ).use { r ->
                        val logits = r.get(0) as OnnxTensor
                        val vocab = logits.info.shape[2].toInt()
                        val fb = logits.floatBuffer
                        val base = (len - 1) * vocab
                        var bestI = 0
                        var bestV = Float.NEGATIVE_INFINITY
                        var i = 0
                        while (i < vocab) {
                            val v = fb.get(base + i)
                            if (v > bestV) {
                                bestV = v
                                bestI = i
                            }
                            i++
                        }
                        next = bestI
                    }
                } finally {
                    dt.close()
                }
                if (next < 0 || next == tok.eosId) break
                if (next == prevTok) {
                    sameRun++
                    if (sameRun >= 6) {
                        Log.d(TAG, "解码退化（同 token 连发），提前结束")
                        break
                    }
                } else {
                    sameRun = 0
                    prevTok = next
                }
                prefix[len] = next
                len++
            }
            return tok.decode(java.util.Arrays.copyOfRange(prefix, 1, len))
        } finally {
            try {
                maskTensor?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 mask 失败: " + t.message)
            }
            try {
                encResult?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "释放隐状态失败: " + t.message)
            }
        }
    }

    /** 释放会话与词表，回收原生内存。 */
    fun release() {
        if (active.get() > 0) {
            scheduleIdleClose()
            return
        }
        synchronized(this) {
            closeSessions()
            tokenizer = null
            loadedKey = null
        }
        // 文本缓存只有 60 条字符串，对内存没帮助；清掉只会让同一屏必须重新推理 146MB 的模型
    }

    /**
     * 内存压力专用。
     *
     * 不能在别的线程 close 会话：ORT 的 close/run 没有并发保证，而 encResult 持有的张量
     * 属于该会话的 arena —— 更可能是 use-after-free 的原生崩溃（进程直接死，捕获不到）。
     * 即使此刻 active == 0 也不能直接关：另一个线程可能正好 incrementAndGet() 并排上任务、
     * ensureLoaded 已经拿到会话引用。所以统一置标记，并往 exec 投一个空任务，
     * 让清理发生在推理线程的空闲边界上。
     */
    fun releaseUnderPressure() {
        releasePending = true
        // 关键：释放后要有一段「别再加载」的静默期。
        // 否则队列里下一个任务会立刻进 ensureLoaded 重新读 146MB 模型 ——
        // 而触发释放的正是内存最紧张的时刻，"释放"就变成了"关掉再打开"，
        // LMK 压力下下一次分配就可能被杀或 OOM。
        noLoadUntil = System.currentTimeMillis() + PRESSURE_COOL_MS
        try {
            exec.execute {
                if (active.get() == 0) closeNow("内存压力") else {
                    Log.d(TAG, "内存压力：仍有推理在跑，等它退出后释放")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "投递释放任务失败: " + t.message)
        }
    }

    /**
     * 高精度某句是否刚超时/失败过。超时的句子下一轮直接走 ML Kit：
     * 否则截断重试会每轮从第 0 块重来，而那几块又要先等 6 秒超时。
     */
    private val cooldown = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 64
    }

    fun coolingDown(text: String): Boolean = synchronized(cooldown) {
        val until = cooldown[text] ?: return false
        if (until > System.currentTimeMillis()) true else {
            cooldown.remove(text)
            false
        }
    }

    fun markSlow(text: String) = synchronized(cooldown) {
        cooldown[text] = System.currentTimeMillis() + COOLDOWN_MS
    }

    /** 真正关会话；只在确认没有推理在跑时调用（Idle 线程或推理线程自己）。 */
    private fun closeNow(reason: String) {
        synchronized(this) {
            releasePending = false
            closeSessions()
            tokenizer = null
            loadedKey = null
        }
        Log.d(TAG, "已释放 ONNX 会话（$reason）")
    }

    private fun closeSessions() {
        try {
            enc?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭 encoder 失败: " + t.message)
        }
        try {
            dec?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭 decoder 失败: " + t.message)
        }
        enc = null
        dec = null
    }

    /** 模型被删/替换后让缓存的会话失效。 */
    fun forget(pair: String) {
        if (loadedKey == pair) release()
    }

    fun forgetAll() = release()
}