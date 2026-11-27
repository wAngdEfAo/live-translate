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

    private val idleClose = Runnable { release() }

    fun supports(src: String, tgt: String): Boolean = EnginePack.pairKey(src, tgt) != null

    fun isInstalled(src: String, tgt: String): Boolean {
        val ctx = AppCore.context() ?: return false
        val key = EnginePack.pairKey(src, tgt) ?: return false
        return EnginePack.isInstalled(ctx, key)
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
        if (!EnginePack.isInstalled(ctx, pair)) {
            cb("高精度模型未安装", "")
            return
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
                scheduleIdleClose()
            }
            cb(err, out)
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
        closeSessions()
        tokenizer = null
        val dir = EnginePack.pairDir(ctx, pair)
        val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(nThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        Log.d(TAG, "加载模型 $pair (threads=$nThreads)")
        val se = e.createSession(File(dir, "encoder.onnx").absolutePath, opts)
        val sd = e.createSession(File(dir, "decoder.onnx").absolutePath, opts)
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
            while (len < prefix.size) {
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
            synchronized(cache) { cache.clear() }
        }
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