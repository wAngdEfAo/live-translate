package com.yg.livetranslate

import com.google.mlkit.common.model.DownloadConditions

/** 翻译引擎接口，方便以后换成别的离线模型。 */
interface TransEngine {
    fun translate(text: String, cb: (String?, String) -> Unit)
}

/** 默认引擎：ML Kit 离线翻译。 */
object MlKitEngine : TransEngine {
    override fun translate(text: String, cb: (String?, String) -> Unit) {
        Translator.translateWith(Prefs.srcLang, text, cb)
    }
}

object Translator {

    private const val CACHE_MAX = 60

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > CACHE_MAX
        }
    }

    @Volatile
    private var engine: TransEngine = MlKitEngine

    fun setEngine(e: TransEngine) {
        engine = e
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

        try {
            val client = LanguagePack.clientFor(lang, tgt)
            client.translate(text)
                .addOnSuccessListener { zh ->
                    val out = zh ?: ""
                    if (out.isNotBlank()) remember(key, out)
                    cb(null, out)
                }
                .addOnFailureListener { first ->
                    // 缺语言包时自动下一次，再重试一次
                    try {
                        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
                            .addOnSuccessListener {
                                client.translate(text)
                                    .addOnSuccessListener { zh ->
                                        val out = zh ?: ""
                                        if (out.isNotBlank()) remember(key, out)
                                        cb(null, out)
                                    }
                                    .addOnFailureListener { cb(it.message ?: "翻译失败", "") }
                            }
                            .addOnFailureListener { cb(first.message ?: "翻译失败", "") }
                    } catch (t: Throwable) {
                        cb(first.message ?: "翻译失败", "")
                    }
                }
        } catch (t: Throwable) {
            cb(t.message ?: "翻译失败", "")
        }
    }
}
