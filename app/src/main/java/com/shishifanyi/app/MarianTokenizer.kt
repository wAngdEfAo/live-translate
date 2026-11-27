package com.shishifanyi.app

import org.json.JSONObject
import java.text.Normalizer

/**
 * OPUS-MT（Marian）用的分词器，纯 Kotlin，无 JNI、无 protobuf。
 *
 * 真实的 Marian 分词链条是：
 *   1. sentencepiece 归一化：NFKC + 一张字符映射表 + 空白压成 U+2581 + 前导占位
 *      （注意：transformers 的 MarianTokenizer 虽然带一套 Moses 标点归一化，
 *        但 `__call__` 路径并不调用它，模型实际吃的是原始标点，这里同样不做）
 *   2. unigram Viterbi 切分（连续 OOV 合成一个未知片段）
 *   3. 用 vocab.json（65001 项）把 piece 映射成模型 id —— 与 spm 自带 id 不同名次
 *
 * 每一步都和 Python 参考实现逐串比对过（见 `MarianTokenizerTest` 的黄金用例）。
 */
class MarianTokenizer private constructor(
    private val charMap: HashMap<Int, String>,
    private val protect: IntArray,
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val spmIdOf: HashMap<String, Int>,
    private val vocab: Array<String?>,
    private val pieceToId: HashMap<String, Int>,
    val unkId: Int,
    val eosId: Int,
    val padId: Int,
    val decoderStartId: Int,
    private val nfkcFirst: Boolean
) {

    private val maxPieceLen: Int = run {
        var m = 1
        for (p in pieces) if (p.length > m) m = p.length
        m.coerceAtMost(64)
    }

    private val minScore: Float = run {
        var v = 0f
        for (s in scores) if (s < v) v = s
        v - 10f
    }

    /** 文本 → 模型 id（含结尾 eos）。 */
    fun encode(text: String): IntArray {
        val norm = normalize(text)
        val ids = ArrayList<Int>(norm.length / 2 + 2)
        val n = norm.length
        if (n > 0) {
            val best = FloatArray(n + 1)
            val stepLen = IntArray(n + 1)
            val stepPiece = arrayOfNulls<String>(n + 1)
            for (i in 0..n) best[i] = Float.NEGATIVE_INFINITY
            best[n] = 0f
            for (i in n - 1 downTo 0) {
                var bs = Float.NEGATIVE_INFINITY
                val limit = minOf(maxPieceLen, n - i)
                var l = 1
                while (l <= limit) {
                    val sub = norm.substring(i, i + l)
                    val spmId = spmIdOf[sub]
                    if (spmId != null) {
                        val sc = scores[spmId] + best[i + l]
                        if (sc > bs) {
                            bs = sc
                            stepLen[i] = l
                            stepPiece[i] = sub
                        }
                    } else if (l == 1) {
                        val sc = minScore + best[i + 1]
                        if (sc > bs) {
                            bs = sc
                            stepLen[i] = 1
                            stepPiece[i] = null
                        }
                    }
                    l++
                }
                best[i] = bs
                if (stepLen[i] <= 0) stepLen[i] = 1
            }
            var i = 0
            while (i < n) {
                val p = stepPiece[i]
                if (p == null) {
                    // 连续未知字符合成一个片段，与 sentencepiece 的 EncodeAsPieces 一致
                    var j = i + 1
                    while (j < n && stepPiece[j] == null) j++
                    ids.add(pieceToId[norm.substring(i, j)] ?: unkId)
                    i = j
                } else {
                    ids.add(pieceToId[p] ?: unkId)
                    i += stepLen[i].coerceAtLeast(1)
                }
            }
        }
        ids.add(eosId)
        return ids.toIntArray()
    }

    /** 模型 id → 文本（跳过 eos/pad，▁ 还原成空格）。 */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder(ids.size * 2)
        for (id in ids) {
            if (id == eosId || id == padId) break
            if (id < 0 || id >= vocab.size) continue
            vocab[id]?.let { sb.append(it) }
        }
        var s = sb.toString().replace(SPACE_MARK, ' ')
        s = s.trim()
        return s
    }

    /** 与 Python 参考实现同序的归一化（不含 Moses：模型实际吃的就是原始标点）。 */
    fun normalize(text: String): String {
        var s = text
        if (nfkcFirst) {
            // 占位符用的是 BMP 私用区：原文里恰好含这些字符时不能做掩码替换，
            // 否则 restore() 会把原文的私用区字符换成保护字符（宁可放弃那点 NFKC 精度）
            s = if (protect.isEmpty() || hasPlaceholder(s)) {
                nfkc(s)
            } else {
                restore(nfkc(mask(s)))
            }
            s = mapChars(s)
        } else {
            s = nfkc(mapChars(s))
        }
        s = collapseSpaces(s)
        // 空白输入：sentencepiece 直接返回空串，不加占位前缀（否则会多出一个 ▁ token）
        if (s.isEmpty()) return ""
        s = s.replace(' ', SPACE_MARK)
        return SPACE_MARK + s
    }

    private fun mapChars(s: String): String {
        if (charMap.isEmpty()) return s
        var hit = false
        for (ch in s) {
            if (charMap.containsKey(ch.code)) {
                hit = true
                break
            }
        }
        if (!hit) return s
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            val rep = charMap[ch.code]
            if (rep != null) sb.append(rep) else sb.append(ch)
        }
        return sb.toString()
    }

    private fun nfkc(s: String): String = try {
        Normalizer.normalize(s, Normalizer.Form.NFKC)
    } catch (t: Throwable) {
        s
    }

    /** 把「sentencepiece 不改但 NFKC 会改」的码位换成私用区占位符，避免被 NFKC 动掉。 */
    private fun mask(s: String): String {
        var hit = false
        for (ch in s) {
            if (protectIndex(ch.code) >= 0) {
                hit = true
                break
            }
        }
        if (!hit) return s
        val sb = StringBuilder(s.length + 4)
        for (ch in s) {
            val idx = protectIndex(ch.code)
            if (idx >= 0) sb.append((PLACEHOLDER_BASE + idx).toChar()) else sb.append(ch)
        }
        return sb.toString()
    }

    private fun restore(s: String): String {
        var hit = false
        for (ch in s) {
            if (ch.code >= PLACEHOLDER_BASE && ch.code < PLACEHOLDER_BASE + protect.size) {
                hit = true
                break
            }
        }
        if (!hit) return s
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val off = ch.code - PLACEHOLDER_BASE
            if (off in protect.indices) sb.append(protect[off].toChar()) else sb.append(ch)
        }
        return sb.toString()
    }

    /** 原文里是否已经含占位符区间内的字符（那样就不能做掩码替换）。 */
    private fun hasPlaceholder(s: String): Boolean {
        val hi = PLACEHOLDER_BASE + protect.size
        for (ch in s) if (ch.code >= PLACEHOLDER_BASE && ch.code < hi) return true
        return false
    }

    private fun protectIndex(cp: Int): Int {
        // protect 已排序，二分即可
        var lo = 0
        var hi = protect.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = protect[mid]
            when {
                v < cp -> lo = mid + 1
                v > cp -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** 空白压成一个空格并去掉首尾（等价 Python 的 " ".join(s.split())）。 */
    private fun collapseSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        var pending = false
        for (ch in s) {
            if (isSpace(ch)) {
                if (sb.isNotEmpty()) pending = true
            } else {
                if (pending) {
                    sb.append(' ')
                    pending = false
                }
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isSpace(ch: Char): Boolean =
        ch.isWhitespace() || Character.isSpaceChar(ch)

    companion object {
        /** sentencepiece 的空白占位符 U+2581。 */
        const val SPACE_MARK = '\u2581'

        /** 私有区起点：保护字符的临时占位在此之上。 */
        private const val PLACEHOLDER_BASE = 0xE000

        /**
         * 从模型包里的 tokenizer.json 构造。
         * 解析 65001 + 32000 项词表，放在后台线程调用（约 0.2–0.5 秒）。
         */
        fun fromJson(o: JSONObject): MarianTokenizer {
            val charMapJson = o.optJSONArray("char_map")
            val charMap = HashMap<Int, String>(if (charMapJson != null) charMapJson.length() * 2 else 16)
            if (charMapJson != null) {
                for (i in 0 until charMapJson.length()) {
                    val pair = charMapJson.getJSONArray(i)
                    charMap[pair.getInt(0)] = pair.getString(1)
                }
            }

            val protectJson = o.optJSONArray("protect")
            val protect = if (protectJson == null) IntArray(0) else IntArray(protectJson.length()) {
                protectJson.getInt(it)
            }
            protect.sort()

            val spm = o.getJSONObject("spm")
            val piecesJson = spm.getJSONArray("pieces")
            val scoresJson = spm.getJSONArray("scores")
            val nPieces = piecesJson.length()
            val pieces = Array(nPieces) { piecesJson.getString(it) }
            val scores = FloatArray(nPieces) { scoresJson.getDouble(it).toFloat() }
            val spmIdOf = HashMap<String, Int>(nPieces * 2)
            for (i in 0 until nPieces) if (!spmIdOf.containsKey(pieces[i])) spmIdOf[pieces[i]] = i

            val vocabJson = o.getJSONArray("vocab")
            val nVocab = vocabJson.length()
            val vocab = arrayOfNulls<String>(nVocab)
            val pieceToId = HashMap<String, Int>(nVocab * 2)
            for (i in 0 until nVocab) {
                if (vocabJson.isNull(i)) continue
                val p = vocabJson.getString(i)
                vocab[i] = p
                if (!pieceToId.containsKey(p)) pieceToId[p] = i
            }


            val sp = o.getJSONObject("specials")
            return MarianTokenizer(
                charMap = charMap,
                protect = protect,
                pieces = pieces,
                scores = scores,
                spmIdOf = spmIdOf,
                vocab = vocab,
                pieceToId = pieceToId,
                unkId = sp.optInt("unk", 1),
                eosId = sp.optInt("eos", 0),
                padId = sp.optInt("pad", 65000),
                decoderStartId = sp.optInt("decoder_start", 65000),
                nfkcFirst = o.optString("normalize_order", "nfkc_first") == "nfkc_first"
            )
        }
    }
}