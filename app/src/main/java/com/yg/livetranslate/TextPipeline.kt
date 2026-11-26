package com.yg.livetranslate

import android.graphics.Rect

/** 屏幕上一块可翻译文字：原文 + 它占据的矩形。 */
data class TextBlock(val text: String, val rect: Rect)

/** 屏幕原始文字块 -> 只保留值得翻译的英文块。 */
object TextPipeline {

    private val wordRe = Regex("[A-Za-z][A-Za-z'\\-]*")
    private val cjkRe = Regex("[\\u4e00-\\u9fff]")
    private val urlRe = Regex("(?i)(https?://|www\\.)|\\.(com|cn|net|org|io|dev|app)(/|$)")
    private val wsRe = Regex("\\s+")

    private const val MAX_BLOCKS = 30

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

    fun filter(blocks: List<TextBlock>, maxY: Int, src: String): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        var prev = ""
        var total = 0

        for (b in blocks) {
            val r = b.rect
            if (r.isEmpty) continue
            if (r.centerY() > maxY) continue
            if (r.width() < 12 || r.height() < 8) continue

            val line = b.text.replace(wsRe, " ").trim()
            if (line.length < 3) continue
            if (line == prev) continue

            val letters = line.count { it.isLetter() }
            if (letters < 2) continue
            if (letters * 2 < line.length) continue

            if (!scriptOk(line, src)) continue

            // 本来就是中文的，不用翻
            if (src != "ja" && src != "ko") {
                val cjk = cjkRe.findAll(line).count()
                if (cjk > 0 && cjk * 2 >= letters) continue
            }

            if (urlRe.containsMatchIn(line)) continue

            out.add(TextBlock(line, r))
            prev = line
            total += line.length
            if (out.size >= MAX_BLOCKS) break
            if (total >= MAX_TOTAL_CHARS) break
        }
        return out
    }
}
