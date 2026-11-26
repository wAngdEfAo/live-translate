package com.shishifanyi.app

/**
 * 翻译入口。支持「中转」：ML Kit 的模型是「语言 ↔ 英语」结构，
 * 所以日语→中文实际是 ja→en 再 en→zh，两跳。
 * 路由由 [Languages.route] 决定，这里只负责按顺序执行。
 */
object Translator {

    private const val CACHE_MAX = 120

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

    private fun ck(src: String, tgt: String, text: String) = src + "|" + tgt + "|" + text

    private fun normalize(src: String) =
        if (src == "auto" || src.isBlank()) Languages.TARGET_EN else src

    /** 命中缓存直接返回，避免重复调用模型。 */
    fun peek(src: String, tgt: String, text: String): String? =
        cached(ck(normalize(src), tgt, text))

    fun translateWith(src: String, tgt: String, text: String, cb: (String?, String) -> Unit) {
        if (text.isBlank()) {
            cb(null, "")
            return
        }
        val s = normalize(src)
        val hops = Languages.route(s, tgt)
        if (hops.isEmpty()) {
            // 源和目标是同一语言，原文返回
            cb(null, text)
            return
        }
        if (hops.size == 1) {
            translateOnce(hops[0].first, hops[0].second, text, cb)
            return
        }
        // 两跳：先到英语，再从中转到目标语言
        translateOnce(hops[0].first, hops[0].second, text) { err, mid ->
            if (err != null || mid.isBlank()) {
                cb(err ?: "中转失败", "")
                return@translateOnce
            }
            translateOnce(hops[1].first, hops[1].second, mid, cb)
        }
    }

    private fun translateOnce(
        src: String,
        tgt: String,
        text: String,
        cb: (String?, String) -> Unit
    ) {
        val key = ck(src, tgt, text)
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
            val client = LanguagePack.clientFor(src, tgt)
            client.translate(text)
                .addOnSuccessListener { zh ->
                    val out = zh ?: ""
                    if (out.isNotBlank()) remember(key, out)
                    done(null, out)
                }
                .addOnFailureListener { e ->
                    // 不再自动补下载：多语言下可能意味着悄悄拉 70MB 以上。
                    // 缺模型请走界面上的「下载」按钮，那里有明确的进度与体积提示。
                    done(e.message ?: "翻译失败（模型可能未下载）", "")
                }
        } catch (t: Throwable) {
            done(t.message ?: "翻译失败", "")
        }
    }
}