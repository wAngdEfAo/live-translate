package com.shishifanyi.app

import android.graphics.Rect

/** 屏幕上一块可翻译文字：原文 + 它占据的矩形。 */
data class TextBlock(val text: String, val rect: Rect)

/** 过滤时各类丢弃原因的计数，用于排查"为什么可用 0"。 */
data class FilterStats(
    var total: Int = 0,
    var small: Int = 0,
    var short: Int = 0,
    var lowLetters: Int = 0,
    var script: Int = 0,
    var chinese: Int = 0,
    var masked: Int = 0,
    var url: Int = 0,
    var outside: Int = 0,
    var kept: Int = 0
) {
    fun brief(): String =
        "总" + total + " 留" + kept +
            " | 出区" + outside + " 太小" + small + " 太短" + short +
            " 字母少" + lowLetters +
            " 脚本" + script + " 中文" + chinese + " 掩码" + masked + " 链接" + url
}

/** 屏幕原始文字块 -> 只保留值得翻译的英文块。 */
object TextPipeline {

    private val wordRe = Regex("[A-Za-z][A-Za-z'\\-]*")
    private val cjkRe = Regex("[\\u4e00-\\u9fff]")
    private val urlRe = Regex("(?i)(https?://|www\\.)|\\.(com|cn|net|org|io|dev|app)(/|$)")
    private val wsRe = Regex("\\s+")

    /** 密码框在 OCR 结果里是一串圆点/星号，这种行不该被当成待翻译文本。 */
    private val maskRe = Regex("^[\\u2022\\u00B7\\u25CF\\u2731\\uFF0A*\\u2219\\s]+$")

    /** 掩码字符集，与 maskRe 保持一致。 */
    private const val MASK_CHARS = "\u2022\u00B7\u25CF\u2731\uFF0A*\u2219"

    /**
     * 密码 / 验证码行判定。
     * 以前只认「整行都是掩码」，于是「密码 ••••••」这种「短标签 + 掩码」的混排行
     * 会原样送进翻译模型。这里额外认：掩码字符 ≥4 个、且不少于行内字母数
     * （字母只可能是前面那个短标签）。
     */
    private fun looksMasked(line: String): Boolean {
        if (maskRe.matches(line)) return true
        val mask = line.count { MASK_CHARS.indexOf(it) >= 0 }
        if (mask < 4) return false
        val letters = line.count { it.isLetter() }
        return letters <= 8 && mask >= letters
    }

    private const val MAX_BLOCKS = 30

    /**
     * 同一行被拆成多个节点时合并成一句，避免半句翻译与多块叠画。
     * 判定：垂直重叠超过 60%，且水平间距不大。
     */
    fun mergeLines(blocks: List<TextBlock>, gapPx: Int): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val sorted = blocks.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val out = ArrayList<TextBlock>()

        for (b in sorted) {
            var merged = false
            for (i in out.indices.reversed()) {
                val o = out[i]
                val ov = minOf(o.rect.bottom, b.rect.bottom) - maxOf(o.rect.top, b.rect.top)
                val minH = minOf(o.rect.height(), b.rect.height()).coerceAtLeast(1)
                if (ov * 100 / minH < 60) continue
                val gap = if (b.rect.left >= o.rect.right) b.rect.left - o.rect.right
                else if (o.rect.left >= b.rect.right) o.rect.left - b.rect.right
                else 0
                if (gap > gapPx) continue

                val r = android.graphics.Rect(
                    minOf(o.rect.left, b.rect.left),
                    minOf(o.rect.top, b.rect.top),
                    maxOf(o.rect.right, b.rect.right),
                    maxOf(o.rect.bottom, b.rect.bottom)
                )
                out[i] = TextBlock(o.text + " " + b.text, r)
                merged = true
                break
            }
            if (!merged) out.add(b)
        }
        return out
    }

    /**
     * 判断无障碍给的矩形是否明显不可靠：太多块窄得离谱。
     * 用来决定要不要补一次 OCR。
     */
    fun looksFragmented(blocks: List<TextBlock>): Boolean {
        if (blocks.isEmpty()) return true
        var l = Int.MAX_VALUE
        var r = 0
        for (b in blocks) {
            if (b.rect.left < l) l = b.rect.left
            if (b.rect.right > r) r = b.rect.right
        }
        val colW = r - l
        if (colW <= 0) return true
        var narrow = 0
        for (b in blocks) {
            if (b.rect.width() * 100 / colW < 55) narrow++
        }
        return narrow * 100 / blocks.size >= 30
    }

    /** 该行是否属于当前源语言。 */
    // ---- 各文字区间判定：语言码必须映射到它真正使用的文字 ----

    private fun hasCjk(s: String) = s.any { it in '\u4e00'..'\u9fff' }
    private fun hasKana(s: String) = s.any { it in '\u3040'..'\u30ff' }
    private fun hasHangul(s: String) = s.any { it in '\uac00'..'\ud7af' }
    private fun hasCyrillic(s: String) = s.any { it in '\u0400'..'\u04ff' }
    private fun hasGreek(s: String) = s.any { it in '\u0370'..'\u03ff' || it in '\u1f00'..'\u1fff' }
    private fun hasHebrew(s: String) = s.any { it in '\u0590'..'\u05ff' }
    private fun hasArabic(s: String) = s.any { it in '\u0600'..'\u06ff' || it in '\u0750'..'\u077f' }
    private fun hasThai(s: String) = s.any { it in '\u0e00'..'\u0e7f' }
    private fun hasDevanagari(s: String) = s.any { it in '\u0900'..'\u097f' }
    private fun hasBengali(s: String) = s.any { it in '\u0980'..'\u09ff' }
    private fun hasGujarati(s: String) = s.any { it in '\u0a80'..'\u0aff' }
    private fun hasTamil(s: String) = s.any { it in '\u0b80'..'\u0bff' }
    private fun hasTelugu(s: String) = s.any { it in '\u0c00'..'\u0c7f' }
    private fun hasKannada(s: String) = s.any { it in '\u0c80'..'\u0cff' }
    private fun hasGeorgian(s: String) = s.any { it in '\u10a0'..'\u10ff' }

    /**
     * 该行是否属于当前源语言。
     * 之前只放行 6 种文字，其余一律套拉丁正则，导致中文/希腊/希伯来/
     * 格鲁吉亚/印度诸文/波斯/乌尔都等整屏被判为"脚本不符"丢弃。
     */
    private fun scriptOk(line: String, src: String): Boolean {
        if (src == "auto") return true
        return when (src) {
            "zh" -> hasCjk(line)
            // 日/韩界面里大量英文标签（WiFi、Settings、OK）。只放行假名/谚文会让这些行
            // 整行被丢，所以两种文字之外还允许「像英文的多词行」通过；
            // 它们由 AppCore 改走英语直连，不喂给 ja→en 模型。
            "ja" -> hasKana(line) || hasCjk(line) || latinWords(line) >= 2
            "ko" -> hasHangul(line) || hasCjk(line) || latinWords(line) >= 2
            // 西里尔
            "ru", "uk", "be", "bg", "mk" -> hasCyrillic(line)
            "el" -> hasGreek(line)
            "iw" -> hasHebrew(line)
            "ka" -> hasGeorgian(line)
            // 阿拉伯字母（波斯、乌尔都同用）
            "ar", "fa", "ur" -> hasArabic(line)
            "th" -> hasThai(line)
            // 印度诸文
            "hi", "mr" -> hasDevanagari(line)
            "bn" -> hasBengali(line)
            "gu" -> hasGujarati(line)
            "ta" -> hasTamil(line)
            "te" -> hasTelugu(line)
            "kn" -> hasKannada(line)
            // 其余为拉丁语系
            else -> latinWords(line) >= 2
        }
    }

    /** 拉丁词个数（与最终 else 分支同一套判据）。 */
    private fun latinWords(line: String): Int = wordRe.findAll(line).count()
    private const val MAX_TOTAL_CHARS = 1600

    fun filter(
        blocks: List<TextBlock>,
        maxY: Int,
        src: String,
        minY: Int = 0,
        stats: FilterStats? = null
    ): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        var prev = ""
        var total = 0

        for (b in blocks) {
            stats?.total = (stats?.total ?: 0) + 1
            val r = b.rect
            if (r.isEmpty) {
                if (stats != null) stats.small++
                continue
            }
            if (r.centerY() > maxY || r.centerY() < minY) {
                if (stats != null) stats.outside++
                continue
            }
            if (r.width() < 12 || r.height() < 8) {
                if (stats != null) stats.small++
                continue
            }

            val line = b.text.replace(wsRe, " ").trim()
            if (line.length < 3) {
                if (stats != null) stats.short++
                continue
            }
            if (line == prev) continue

            if (looksMasked(line)) {
                // 掩码行不是"脚本不符"，单独计数才看得懂日志
                if (stats != null) stats.masked++
                continue
            }

            val letters = line.count { it.isLetter() }
            if (letters < 2) {
                if (stats != null) stats.lowLetters++
                continue
            }
            if (letters * 2 < line.length) {
                if (stats != null) stats.lowLetters++
                continue
            }

            if (!scriptOk(line, src)) {
                if (stats != null) {
                    // 含中文的行不该算"脚本不符"，分开计数才是真实原因
                    if (cjkRe.findAll(line).count() > 0) stats.chinese++ else stats.script++
                }
                continue
            }

            // 源语言本身是中日韩时，中文/汉字行正是待翻内容，不能当"已是中文"丢掉
            if (src != "ja" && src != "ko" && src != "zh") {
                val cjk = cjkRe.findAll(line).count()
                if (cjk > 0 && cjk * 2 >= letters) {
                    if (stats != null) {
                        stats.chinese++
                    }
                    continue
                }
            }

            if (urlRe.containsMatchIn(line)) {
                if (stats != null) stats.url++
                continue
            }

            if (stats != null) stats.kept++
            out.add(TextBlock(line, r))
            prev = line
            total += line.length
            if (out.size >= MAX_BLOCKS) break
            if (total >= MAX_TOTAL_CHARS) break
        }
        return out
    }
}
