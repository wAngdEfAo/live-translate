package com.shishifanyi.app

import com.google.mlkit.nl.languageid.LanguageIdentification

/** 自动识别屏幕文字的源语言（离线）。 */
object LanguageDetector {

    /** 自动检测支持的语言：与 Languages.ALL 完全一致（59 种）。 */
    val SUPPORTED: Set<String> = Languages.ALL.map { it.code }.toSet()

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

    /** ML Kit 返回的是 BCP-47，部分语言码与工程内部码不同，需要归一。 */
    private val ALIAS = mapOf(
        "in" to "id",     // 旧印尼语码
        "he" to "iw",     // 希伯来语新旧码
        "fil" to "tl",    // 菲律宾语
        "cmn" to "zh",    // 普通话
        "zh-hans" to "zh",
        "zh-hant" to "zh",
        "nb" to "no",     // 挪威语书面体
        "nn" to "no"
    )

    /** 归一为工程内部语言码；无法识别时返回 null。 */
    fun resolve(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        val lower = code.lowercase()
        ALIAS[lower]?.let { return it }
        val base = lower.substringBefore('-')
        ALIAS[base]?.let { return it }
        return if (SUPPORTED.contains(base)) base else null
    }

    fun normalize(code: String?): String = resolve(code) ?: "en"

    /**
     * 严格版检测：识别不出 / 失败时回调 null，而不是兜底成 "en"。
     * 用于「屏幕是不是本来就用英文」这类判断——兜底成 en 会让西语屏幕被误跳过。
     */
    fun detectStrict(text: String, cb: (String?) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) {
            cb(null)
            return
        }
        try {
            client.identifyLanguage(t.take(400))
                .addOnSuccessListener { code -> cb(resolve(code)) }
                .addOnFailureListener { cb(null) }
        } catch (e: Throwable) {
            cb(null)
        }
    }
}
