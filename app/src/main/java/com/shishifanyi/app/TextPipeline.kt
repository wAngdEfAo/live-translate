package com.shishifanyi.app

import android.graphics.Rect

/** 屏幕上一块可翻译文字：原文 + 它占据的矩形。 */
data class TextBlock(val text: String, val rect: Rect)

/** 过滤时各类丢弃原因的计数，用于排查"为什么可用 0"。 */
data class FilterStats(
    var total: Int = 0,
    val samples: MutableList<String> = ArrayList(),
    var small: Int = 0,
    var short: Int = 0,
    var fewWords: Int = 0,
    var lowLetters: Int = 0,
    var script: Int = 0,
    var chinese: Int = 0,
    var url: Int = 0,
    var outside: Int = 0,
    var kept: Int = 0
) {
    fun brief(): String =
        "总" + total + " 留" + kept +
            " | 出区" + outside + " 太小" + small + " 太短" + short +
            " 词少" + fewWords + " 字母少" + lowLetters +
            " 脚本" + script + " 中文" + chinese + " 链接" + url
}

/** 屏幕原始文字块 -> 只保留值得翻译的英文块。 */
object TextPipeline {

    private val wordRe = Regex("[A-Za-z][A-Za-z'\\-]*")
    private val cjkRe = Regex("[\\u4e00-\\u9fff]")
    private val urlRe = Regex("(?i)(https?://|www\\.)|\\.(com|cn|net|org|io|dev|app)(/|$)")
    private val wsRe = Regex("\\s+")

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
    private fun scriptOk(line: String, src: String): Boolean {
        if (src == "auto") return true
        return when (src) {
            "ja" -> line.any { it in '\u3040'..'\u30ff' } || line.any { it in '\u4e00'..'\u9fff' }
            "ko" -> line.any { it in '\uac00'..'\ud7af' }
            "ru" -> line.any { it in '\u0400'..'\u04ff' }
            "ar" -> line.any { it in '\u0600'..'\u06ff' }
            "th" -> line.any { it in '\u0e00'..'\u0e7f' }
            "hi" -> line.any { it in '\u0900'..'\u097f' }
            else -> wordRe.findAll(line).count() >= 2
        }
    }
    private const val MAX_TOTAL_CHARS = 1600

    /**
     * @param maxY 只处理屏幕这个 y 坐标以上的文字（顶部 60% 区域）。
     */
    fun filter(blocks: List<TextBlock>, maxY: Int): List<TextBlock> =
        filter(blocks, maxY, Prefs.srcLang)

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
                    if (stats.samples.size < 6) stats.samples.add("脚本:" + line.take(24))
                }
                continue
            }

            // 本来就是中文的，不用翻
            if (src != "ja" && src != "ko") {
                val cjk = cjkRe.findAll(line).count()
                if (cjk > 0 && cjk * 2 >= letters) {
                    if (stats != null) {
                        stats.chinese++
                        if (stats.samples.size < 6) stats.samples.add("中文:" + line.take(24))
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
