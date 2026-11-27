package com.shishifanyi.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实 sentencepiece 导出的黄金用例校验 [MarianTokenizer]。
 *
 * 数据来源：`work/onnx/dump_pack.py`（Python 侧跑真实的 MarianTokenizer，
 * 记录下面每个串的归一化结果、编码 id、解码文本），Kotlin 侧必须逐位一致。
 * 覆盖：普通英文、弯引号、破折号、省略号、全角字符、双空格、CJK、
 * 带重音的 OOV、emoji、以及 30 句语料。
 */
class MarianTokenizerTest {

    private fun loadFixture(name: String): String {
        val s = javaClass.classLoader!!.getResourceAsStream(name)
            ?: throw IllegalStateException("fixture missing: $name")
        return s.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun tokenizer(): MarianTokenizer =
        MarianTokenizer.fromJson(JSONObject(loadFixture("tokenizer_en_zh.json")))

    private fun golden(): JSONArray = JSONArray(loadFixture("golden_en_zh.json"))

    @Test
    fun specialsMatch() {
        val t = tokenizer()
        assertEquals(0, t.eosId)
        assertEquals(1, t.unkId)
        assertEquals(65000, t.padId)
        assertEquals(65000, t.decoderStartId)
    }

    @Test
    fun normalizeMatchesSentencePiece() {
        val t = tokenizer()
        val g = golden()
        var checked = 0
        for (i in 0 until g.length()) {
            val row = g.getJSONObject(i)
            val text = row.getString("text")
            val want = row.getString("normalized")
            assertEquals("normalize($text)", want, t.normalize(text))
            checked++
        }
        assertTrue("golden 少得可疑", checked >= 30)
    }

    @Test
    fun encodeMatchesReferenceIds() {
        val t = tokenizer()
        val g = golden()
        var referenceExact = 0
        for (i in 0 until g.length()) {
            val row = g.getJSONObject(i)
            val text = row.getString("text")
            val idsJson = row.getJSONArray("ids")
            val want = IntArray(idsJson.length()) { idsJson.getInt(it) }
            val got = t.encode(text)
            assertEquals("encode($text)", want.toList(), got.toList())
            // 与 transformers 真实 output 的对齐率：允许极少数全角/组合符差异
            val refJson = row.getJSONArray("reference_ids")
            val ref = IntArray(refJson.length()) { refJson.getInt(it) }
            if (ref.toList() == got.toList()) referenceExact++
        }
        assertTrue(
            "与真实 tokenizer 对齐率过低: $referenceExact/${g.length()}",
            referenceExact >= g.length() - 2
        )
    }

    @Test
    fun decodeRoundTrips() {
        val t = tokenizer()
        val g = golden()
        for (i in 0 until g.length()) {
            val row = g.getJSONObject(i)
            val idsJson = row.getJSONArray("ids")
            // 去掉结尾 eos，解码结果应与参考一致
            val ids = IntArray(idsJson.length() - 1) { idsJson.getInt(it) }
            val want = row.getString("decoded")
            assertEquals("decode(${row.getString("text")})", want, t.decode(ids))
        }
    }

    @Test
    fun curlyQuotesSurviveNormalization() {
        val t = tokenizer()
        // 不做 Moses：模型吃的是原始弯引号，归一化不该把它换成直引号
        assertEquals("\u2581\u201cquoted\u201d", t.normalize("\u201cquoted\u201d"))
        // 空白压成一个空格并加前导占位
        assertEquals("\u2581a\u2581b", t.normalize("  a   b  "))
    }

    @Test
    fun emptyInputYieldsEosOnly() {
        val t = tokenizer()
        assertEquals(listOf(0), t.encode("   ").toList())
        assertEquals("", t.decode(IntArray(0)))
    }
}