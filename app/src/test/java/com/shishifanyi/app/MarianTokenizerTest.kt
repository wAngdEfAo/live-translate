package com.shishifanyi.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实 sentencepiece 导出的黄金用例校验 [MarianTokenizer]，英⇄中两个方向都跑。
 *
 * 夹具（`tokenizer_<pair>.json` / `golden_<pair>.json`）由 `tools/onnx/dump_pack.py`
 * 与 `tools/onnx/regen_golden.py` 生成，与发布 zip 里的那份同源（都已去掉 moses 表），
 * 所以这里验证的就是实际会跑在手机上的文件，不是另做的一份。
 *
 * 覆盖：普通英文、弯引号、破折号、省略号、全角字符、双空格、CJK、
 * 带重音的 OOV、emoji、中英混排、以及纯空白输入。
 */
class MarianTokenizerTest {

    private val pairs = listOf("en_zh", "zh_en")

    private fun loadFixture(name: String): String {
        val s = javaClass.classLoader!!.getResourceAsStream(name)
            ?: throw IllegalStateException("fixture missing: $name")
        return s.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun tokenizer(pair: String): MarianTokenizer =
        MarianTokenizer.fromJson(JSONObject(loadFixture("tokenizer_$pair.json")))

    private fun golden(pair: String): JSONArray = JSONArray(loadFixture("golden_$pair.json"))

    @Test
    fun specialsMatch() {
        for (pair in pairs) {
            val t = tokenizer(pair)
            assertEquals("$pair eos", 0, t.eosId)
            assertEquals("$pair unk", 1, t.unkId)
            assertEquals("$pair pad", 65000, t.padId)
            // 起始符必须等于模型自己的 pad，不能写死一个无关的值
            assertEquals("$pair decoder_start", t.padId, t.decoderStartId)
        }
    }

    @Test
    fun normalizeMatchesSentencePiece() {
        for (pair in pairs) {
            val t = tokenizer(pair)
            val g = golden(pair)
            var checked = 0
            for (i in 0 until g.length()) {
                val row = g.getJSONObject(i)
                val text = row.getString("text")
                assertEquals("$pair normalize($text)", row.getString("normalized"), t.normalize(text))
                checked++
            }
            assertTrue("$pair golden 少得可疑", checked >= 20)
        }
    }

    @Test
    fun encodeMatchesReferenceIds() {
        for (pair in pairs) {
            val t = tokenizer(pair)
            val g = golden(pair)
            var referenceExact = 0
            for (i in 0 until g.length()) {
                val row = g.getJSONObject(i)
                val text = row.getString("text")
                val idsJson = row.getJSONArray("ids")
                val want = IntArray(idsJson.length()) { idsJson.getInt(it) }
                val got = t.encode(text)
                assertEquals("$pair encode($text)", want.toList(), got.toList())
                val refJson = row.getJSONArray("reference_ids")
                val ref = IntArray(refJson.length()) { refJson.getInt(it) }
                if (ref.toList() == got.toList()) referenceExact++
            }
            assertTrue(
                "$pair 与真实 tokenizer 对齐率过低: $referenceExact/${g.length()}",
                referenceExact >= g.length() - 1
            )
        }
    }

    @Test
    fun decodeRoundTrips() {
        for (pair in pairs) {
            val t = tokenizer(pair)
            val g = golden(pair)
            for (i in 0 until g.length()) {
                val row = g.getJSONObject(i)
                val idsJson = row.getJSONArray("ids")
                // 去掉结尾 eos，解码结果应与参考一致
                val ids = IntArray(idsJson.length() - 1) { idsJson.getInt(it) }
                assertEquals(
                    "$pair decode(${row.getString("text")})",
                    row.getString("decoded"),
                    t.decode(ids)
                )
            }
        }
    }

    @Test
    fun curlyQuotesSurviveNormalization() {
        val t = tokenizer("en_zh")
        // 不做 Moses：模型吃的是原始弯引号，归一化不该把它换成直引号
        assertEquals("\u2581\u201cquoted\u201d", t.normalize("\u201cquoted\u201d"))
        // 空白压成一个空格并加前导占位
        assertEquals("\u2581a\u2581b", t.normalize("  a   b  "))
    }

    @Test
    fun privateUseInputDoesNotCorrupt() {
        // 原文含 BMP 私用区字符时，掩码替换必须整体跳过，不能把原文换掉
        val t = tokenizer("en_zh")
        val input = "a\uE005b"
        assertEquals("\u2581a\uE005b", t.normalize(input))
    }

    @Test
    fun emptyInputYieldsEosOnly() {
        for (pair in pairs) {
            val t = tokenizer(pair)
            assertEquals("$pair blank", listOf(t.eosId), t.encode("   ").toList())
            assertEquals("$pair empty decode", "", t.decode(IntArray(0)))
        }
    }
}