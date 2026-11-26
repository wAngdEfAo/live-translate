package com.yg.livetranslate

import com.google.mlkit.nl.languageid.LanguageIdentification

/** 自动识别屏幕文字的源语言（离线）。 */
object LanguageDetector {

    /** 支持自动检测的语言（与语言列表一致）。 */
    val SUPPORTED = setOf(
        "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it", "th", "vi", "ar", "zh"
    )

    private val client by lazy { LanguageIdentification.getClient() }

    private var lastText: String = ""
    private var lastLang: String = "en"

    fun detect(text: String, cb: (String) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) {
            cb("en")
            return
        }
        if (t == lastText) {
            cb(lastLang)
            return
        }
        try {
            client.identifyLanguage(t.take(400))
                .addOnSuccessListener { code ->
                    val lang = normalize(code)
                    lastText = t
                    lastLang = lang
                    cb(lang)
                }
                .addOnFailureListener { cb("en") }
        } catch (e: Throwable) {
            cb("en")
        }
    }

    fun normalize(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return "en"
        val base = code.substringBefore('-').lowercase()
        return if (SUPPORTED.contains(base)) base else "en"
    }
}
