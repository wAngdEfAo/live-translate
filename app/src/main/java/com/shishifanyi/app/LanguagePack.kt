package com.shishifanyi.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/** ML Kit 离线模型管理：按语言对下载 / 删除 / 校验。 */
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
            Log.w(TAG, "LanguagePack@26: " + ex.message)
        }
    }

    /** 释放所有已加载的翻译模型，回收原生内存。 */
    fun releaseAll() {
        try {
            handler.removeCallbacks(idleClose)
        } catch (ex: Throwable) {
            Log.w(TAG, "LanguagePack@34: " + ex.message)
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
                Log.w(TAG, "LanguagePack@39: " + ex.message)
            }
        }
        clients.clear()
    }

    @Volatile
    var ready: Boolean = false

    @Volatile
    var busy: Boolean = false
        private set

    private const val IDLE_MS = 60_000L

    private fun key(src: String, tgt: String) = src + "-" + tgt

    fun clientFor(src: String, tgt: String): com.google.mlkit.nl.translate.Translator {
        val k = key(src, tgt)
        // computeIfAbsent 保证并发下只创建一个 client，不会出现两个线程各建一个
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

    /** 该源语言模型是否已下载。 */
    fun isDownloaded(src: String, cb: (Boolean) -> Unit) {
        try {
            val model = TranslateRemoteModel.Builder(src).build()
            RemoteModelManager.getInstance().isModelDownloaded(model)
                .addOnSuccessListener { cb(it) }
                .addOnFailureListener { cb(false) }
        } catch (t: Throwable) {
            cb(false)
        }
    }

    fun download(src: String, tgt: String, cb: (Boolean, String) -> Unit) {
        if (busy) return
        // "auto" 不是 ML Kit 认识的语种标签，拿它下载必然失败
        if (src.isBlank() || src == "auto") {
            cb(false, "请先选具体语言，「自动检测」不能用来下载")
            return
        }
        busy = true
        try {
            val cond = DownloadConditions.Builder().build()
            clientFor(src, tgt).downloadModelIfNeeded(cond)
                .addOnSuccessListener {
                    busy = false
                    ready = true
                    cb(true, "已就绪")
                }
                .addOnFailureListener { e ->
                    busy = false
                    cb(false, "下载失败：" + (e.message ?: "网络错误"))
                }
        } catch (t: Throwable) {
            // getClient 对非法语种会直接抛；不复位 busy 的话，之后每次下载都被 if (busy) 静默吞掉
            busy = false
            cb(false, "下载失败：" + (t.message ?: "未知错误"))
        }
    }

    /** 导入 zip 后试翻一句，验证模型真的可用。 */
    fun verify(src: String, tgt: String, cb: (Boolean) -> Unit) {
        try {
            clientFor(src, tgt).translate("test").addOnSuccessListener {
                ready = true
                cb(true)
            }.addOnFailureListener {
                ready = false
                cb(false)
            }
        } catch (t: Throwable) {
            ready = false
            cb(false)
        }
    }

    fun delete(src: String, cb: (Boolean) -> Unit) {
        try {
            val model = TranslateRemoteModel.Builder(src).build()
            RemoteModelManager.getInstance().deleteDownloadedModel(model)
                .addOnSuccessListener {
                    // 复位就绪状态：否则 refresh() 见 ready==true 会短路，删完还显示"已就绪"
                    ready = false
                    // 摘掉并 close：只从 map 里移除会让原生模型一直挂着
                    val gone = clients.remove(key(src, Prefs.tgtLang))
                    try {
                        gone?.close()
                    } catch (ex: Throwable) {
                        Log.w(TAG, "LanguagePack@146: " + ex.message)
                    }
                    cb(true)
                }
                .addOnFailureListener { cb(false) }
        } catch (t: Throwable) {
            cb(false)
        }
    }
}
