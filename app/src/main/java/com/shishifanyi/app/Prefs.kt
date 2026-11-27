package com.shishifanyi.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(c: Context) {
        if (sp == null) {
            sp = c.applicationContext.getSharedPreferences("live_translate", Context.MODE_PRIVATE)
        }
    }

    private val p: SharedPreferences
        get() = sp ?: throw IllegalStateException("Prefs 未初始化")

    var srcLang: String
        get() = p.getString("src_lang", "en") ?: "en"
        set(v) = p.edit().putString("src_lang", v).apply()

    var tgtLang: String
        get() = p.getString("tgt_lang", "zh") ?: "zh"
        set(v) = p.edit().putString("tgt_lang", v).apply()

    /** 处理区域：0~1，从屏幕顶部起算的比例。 */
    var regionRatio: Float
        get() = p.getFloat("region_ratio", 0.6f)
        set(v) = p.edit().putFloat("region_ratio", v.coerceIn(0.2f, 1f)).apply()

    /** true = 从顶部向下覆盖；false = 从底部向上覆盖。 */
    var regionFromTop: Boolean
        get() = p.getBoolean("region_from_top", true)
        set(v) = p.edit().putBoolean("region_from_top", v).apply()

    var translating: Boolean
        get() = p.getBoolean("translating", true)
        set(v) = p.edit().putBoolean("translating", v).apply()

    /** true = 每次都截图识别（更准更费电）；false = 只在矩形异常时补 OCR。 */
    var accurateMode: Boolean
        get() = p.getBoolean("accurate_mode", false)
        set(v) = p.edit().putBoolean("accurate_mode", v).apply()

    /** 翻译引擎：mlkit（默认，实时）/ onnx（高精度，英⇄中，慢）。 */
    var engine: String
        get() = p.getString("engine", "mlkit") ?: "mlkit"
        set(v) = p.edit().putString("engine", v).apply()

    /** 高精度只用于长句；短 UI 文案继续走 ML Kit，避免实时路径被拖慢。 */
    var onnxLongOnly: Boolean
        get() = p.getBoolean("onnx_long_only", true)
        set(v) = p.edit().putBoolean("onnx_long_only", v).apply()

    /** 「长句」的字符阈值。 */
    var onnxMinChars: Int
        get() = p.getInt("onnx_min_chars", 24)
        set(v) = p.edit().putInt("onnx_min_chars", v.coerceIn(1, 400)).apply()

    /** 是否曾经成功授权过屏幕捕获；用于区分"从未授权"和"授权失效"。 */
    var ocrEverGranted: Boolean
        get() = p.getBoolean("ocr_ever_granted", false)
        set(v) = p.edit().putBoolean("ocr_ever_granted", v).apply()

    var ocrFallback: Boolean
        get() = p.getBoolean("ocr", true)
        set(v) = p.edit().putBoolean("ocr", v).apply()

    var barX: Int
        get() = p.getInt("bar_x", -1)
        set(v) = p.edit().putInt("bar_x", v).apply()

    var barY: Int
        get() = p.getInt("bar_y", -1)
        set(v) = p.edit().putInt("bar_y", v).apply()
}
