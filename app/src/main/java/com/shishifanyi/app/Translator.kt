package com.shishifanyi.app

import com.google.mlkit.common.model.DownloadConditions

object Translator {

    private const val CACHE_MAX = 60

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > CACHE_MAX
        }
    }

    @Synchronized
    private fun cached(key: String): String? = cache[key]

    @Synchronized
    private fun remember(key: String, value: String) {
        cache[key] = value
    }

    /** 命中缓存直接返回，避免重复调用模型（省 CPU 与延时）。 */
    fun peek(src: String, text: String): String? {
        val lang = if (src == "auto" || src.isBlank()) "en" else src
        return cached(lang + "|" + text)
    }

    fun translate(text: String, cb: (String?, String) -> Unit) {
        translateWith(Prefs.srcLang, text, cb)
    }

    /**
     * @param src 源语言代码；传 "auto" 时按英语处理（调用方应先做检测）
     */
    fun translateWith(src: String, text: String, cb: (String?, String) -> Unit) {
        if (text.isBlank()) {
            cb(null, "")
            return
        }
        val lang = if (src == "auto" || src.isBlank()) "en" else src
        val tgt = Prefs.tgtLang

        val key = lang + "|" + text
        val hit = cached(key)
        if (hit != null) {
            cb(null, hit)
            return
        }

        // 标记「有一次翻译在路上」：LanguagePack 回收模型前会看这个计数
        LanguagePack.beginUse()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun done(err: String?, out: String) {
            if (finished.compareAndSet(false, true)) {
                LanguagePack.endUse()
                cb(err, out)
            }
        }

        try {
            val client = LanguagePack.clientFor(lang, tgt)
            client.translate(text)
                .addOnSuccessListener { zh ->
                    val out = zh ?: ""
                    if (out.isNotBlank()) remember(key, out)
                    done(null, out)
                }
                .addOnFailureListener { first ->
                    // 只有「模型确实没下载」才补下载。原来是无条件下载，
                    // 任何失败（包括内存不足、语种不支持）都会偷偷联网，和隐私承诺冲突
                    LanguagePack.isDownloaded(lang) { downloaded ->
                        if (downloaded) {
                            done(first.message ?: "翻译失败", "")
                            return@isDownloaded
                        }
                        try {
                            client.downloadModelIfNeeded(DownloadConditions.Builder().build())
                                .addOnSuccessListener {
                                    client.translate(text)
                                        .addOnSuccessListener { zh ->
                                            val out = zh ?: ""
                                            if (out.isNotBlank()) remember(key, out)
                                            done(null, out)
                                        }
                                        .addOnFailureListener { done(it.message ?: "翻译失败", "") }
                                }
                                .addOnFailureListener { done(first.message ?: "翻译失败", "") }
                        } catch (t: Throwable) {
                            done(first.message ?: "翻译失败", "")
                        }
                    }
                }
        } catch (t: Throwable) {
            done(t.message ?: "翻译失败", "")
        }
    }
}
