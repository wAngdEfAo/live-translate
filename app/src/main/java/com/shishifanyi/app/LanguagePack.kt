package com.shishifanyi.app

import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/** ML Kit 离线模型管理：按语言对下载 / 删除 / 校验。 */
object LanguagePack {

    private val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()

    private val handler = Handler(Looper.getMainLooper())

    /** 空闲这么久就把模型从内存里放掉，下次用到再加载。 */
    private val idleClose = Runnable { releaseAll() }

    private fun scheduleIdleClose() {
        try {
            handler.removeCallbacks(idleClose)
            handler.postDelayed(idleClose, IDLE_MS)
        } catch (t: Throwable) {
        }
    }

    /** 释放所有已加载的翻译模型，回收原生内存。 */
    fun releaseAll() {
        try {
            handler.removeCallbacks(idleClose)
        } catch (t: Throwable) {
        }
        for (c in clients.values) {
            try {
                c.close()
            } catch (t: Throwable) {
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
        var c = clients[k]
        if (c == null) {
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(tgt)
                .build()
            c = Translation.getClient(opts)
            clients[k] = c
        }
        scheduleIdleClose()
        return c!!
    }

    fun current(): com.google.mlkit.nl.translate.Translator =
        clientFor(Prefs.srcLang, Prefs.tgtLang)

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
        busy = true
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
                    clients.remove(key(src, Prefs.tgtLang))
                    cb(true)
                }
                .addOnFailureListener { cb(false) }
        } catch (t: Throwable) {
            cb(false)
        }
    }
}
