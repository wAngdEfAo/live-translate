package com.shishifanyi.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * ML Kit 离线模型管理：按「翻译路径」下载 / 删除 / 校验。
 *
 * ML Kit 的模型是「某语言 ↔ 英语」的，不是任意语言对直连。
 * 所以 ja→zh 实际需要 ja 和 zh 两个模型，en→zh 只需要 zh 一个。
 * 需要哪些模型由 [Languages.route] 决定。
 */
object LanguagePack {
    private const val TAG = "LanguagePack"

    // 主线程（下载 / 删除）与 worker 线程（翻译）都会访问，必须用并发容器
    private val clients =
        java.util.concurrent.ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()

    /** 正在进行的翻译批次数；大于 0 时不能 close，否则进行中的翻译会莫名失败。 */
    private val active = java.util.concurrent.atomic.AtomicInteger(0)

    fun beginUse() {
        active.incrementAndGet()
    }

    fun endUse() {
        active.decrementAndGet()
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 空闲这么久就把模型从内存里放掉，下次用到再加载。 */
    private val idleClose = Runnable { releaseAll() }

    private fun scheduleIdleClose() {
        try {
            handler.removeCallbacks(idleClose)
            handler.postDelayed(idleClose, IDLE_MS)
        } catch (ex: Throwable) {
            Log.w(TAG, "LanguagePack@42: " + ex.message)
        }
    }

    /** 释放所有已加载的翻译模型，回收原生内存。 */
    fun releaseAll() {
        try {
            handler.removeCallbacks(idleClose)
        } catch (ex: Throwable) {
            Log.w(TAG, "LanguagePack@50: " + ex.message)
        }
        // 还有批次在翻：等它结束再来一次，别把人家正用的 client close 掉
        if (active.get() > 0) {
            scheduleIdleClose()
            return
        }
        for (c in clients.values) {
            try {
                c.close()
            } catch (ex: Throwable) {
                Log.w(TAG, "LanguagePack@55: " + ex.message)
            }
        }
        clients.clear()
    }

    /**
     * 已确认就绪的语言路径集合。
     * 之前是单个字符串槽：并发检查多个语言时，最后完成的那个覆盖掉别人的结果，
     * 导致 isReadyCached 基本失效、每次回到界面都要重新问 ML Kit。
     */
    private val readyKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isReadyCached(src: String, tgt: String): Boolean = readyKeys.contains(key(src, tgt))

    fun markReady(src: String, tgt: String) {
        readyKeys.add(key(src, tgt))
    }

    fun clearReady() {
        readyKeys.clear()
    }

    fun clearReady(src: String, tgt: String) {
        readyKeys.remove(key(src, tgt))
    }

    @Volatile
    var busy: Boolean = false
        private set

    private const val IDLE_MS = 180_000L

    private fun key(src: String, tgt: String) = src + "-" + tgt

    fun clientFor(src: String, tgt: String): com.google.mlkit.nl.translate.Translator {
        val k = key(src, tgt)
        // computeIfAbsent 保证并发下只创建一个 client，不会出现两个线程各建一个
        // 注意：这里不能"只保留一个语言对"。
        // 两跳翻译（如 日→英→中）会在同一批里交替请求两个语言对，
        // 一旦互相 close，每块都要重建客户端、重载模型，整屏必然超时。
        // 内存交给 IDLE_MS 空闲统一释放。
        val c = clients.computeIfAbsent(k) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }
        scheduleIdleClose()
        return c
    }

    /**
     * 这条路径需要哪几个语言模型。
     * ja→zh 需要 ja（ja↔en）和 zh（zh↔en）；en→zh 只需要 zh。
     * 英语是枢纽语言，本身不需要单独模型。
     */
    fun neededModels(src: String, tgt: String): List<String> =
        Languages.route(src, tgt)
            .map { (a, b) -> if (a == Languages.TARGET_EN) b else a }
            .distinct()

    private fun isModelDownloaded(code: String, cb: (Boolean) -> Unit) {
        try {
            val model = TranslateRemoteModel.Builder(code).build()
            RemoteModelManager.getInstance().isModelDownloaded(model)
                .addOnSuccessListener { cb(it) }
                .addOnFailureListener { cb(false) }
        } catch (t: Throwable) {
            cb(false)
        }
    }

    /** 这条路径所需模型是否全部就绪。 */
    fun isReady(src: String, tgt: String, cb: (Boolean) -> Unit) {
        // "auto" 要等检测出具体语言才知道下什么模型
        if (src.isBlank() || src == "auto") {
            cb(false)
            return
        }
        val models = neededModels(src, tgt)
        if (models.isEmpty()) {
            cb(false)
            return
        }
        var left = models.size
        var ok = true
        for (m in models) {
            isModelDownloaded(m) { downloaded ->
                if (!downloaded) ok = false
                left--
                if (left == 0) {
                    if (ok) markReady(src, tgt) else clearReady(src, tgt)
                    cb(ok)
                }
            }
        }
    }

    /** 依次下载这条路径需要的模型；任一失败即停。 */
    fun download(src: String, tgt: String, cb: (Boolean, String) -> Unit) {
        if (busy) {
            cb(false, "正在下载中，请稍候")
            return
        }
        // "auto" 不是 ML Kit 认识的语种标签，拿它下载必然失败
        if (src.isBlank() || src == "auto") {
            cb(false, "请先选具体语言，「自动检测」不能用来下载")
            return
        }
        val models = neededModels(src, tgt)
        if (models.isEmpty()) {
            cb(false, "该语言组合不需要模型")
            return
        }
        busy = true
        val cond = DownloadConditions.Builder().build()
        downloadStep(src, tgt, models, 0, cond, cb)
    }

    private fun downloadStep(
        src: String,
        tgt: String,
        models: List<String>,
        index: Int,
        cond: DownloadConditions,
        cb: (Boolean, String) -> Unit
    ) {
        if (index >= models.size) {
            busy = false
            markReady(src, tgt)
            releaseAll()
            cb(true, "已就绪")
            return
        }
        val code = models[index]
        val model = try {
            TranslateRemoteModel.Builder(code).build()
        } catch (t: Throwable) {
            busy = false
            cb(false, "模型创建失败：" + (t.message ?: code))
            return
        }
        RemoteModelManager.getInstance().download(model, cond)
            .addOnSuccessListener {
                downloadStep(src, tgt, models, index + 1, cond, cb)
            }
            .addOnFailureListener { e ->
                busy = false
                cb(false, "下载失败（" + Languages.name(code) + "）：" + (e.message ?: "网络错误"))
            }
            .addOnCanceledListener {
                busy = false
                cb(false, "下载已取消")
            }
    }

    /** 删除某语言的模型（英语是枢纽，不提供删除）。 */
    fun deleteSource(src: String, cb: (Boolean) -> Unit) {
        if (src.isBlank() || src == Languages.TARGET_EN || src == "auto") {
            cb(false)
            return
        }
        try {
            val model = TranslateRemoteModel.Builder(src).build()
            RemoteModelManager.getInstance().deleteDownloadedModel(model)
                .addOnSuccessListener {
                    // 复位就绪状态：否则界面见缓存就短路，删完还显示"已就绪"
                    clearReady()
                    // 所有涉及该语言的 client 都要摘掉并 close
                    val it = clients.keys.iterator()
                    while (it.hasNext()) {
                        val k = it.next()
                        if (k.startsWith(src + "-") || k.endsWith("-" + src)) {
                            val gone = clients.remove(k)
                            try {
                                gone?.close()
                            } catch (ex: Throwable) {
                                Log.w(TAG, "LanguagePack@204: " + ex.message)
                            }
                        }
                    }
                    cb(true)
                }
                .addOnFailureListener { cb(false) }
        } catch (t: Throwable) {
            cb(false)
        }
    }

    /** 判断这条路径能不能用（不再试翻，避免中转路径把验证搞复杂）。 */
    fun verify(src: String, tgt: String, cb: (Boolean) -> Unit) {
        isReady(src, tgt, cb)
    }
}