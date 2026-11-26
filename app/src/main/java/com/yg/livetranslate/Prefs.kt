package com.yg.livetranslate

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

    var translating: Boolean
        get() = p.getBoolean("translating", true)
        set(v) = p.edit().putBoolean("translating", v).apply()

    /** true = 每次都截图识别（更准更费电）；false = 只在矩形异常时补 OCR。 */
    var accurateMode: Boolean
        get() = p.getBoolean("accurate_mode", false)
        set(v) = p.edit().putBoolean("accurate_mode", v).apply()

    var ocrFallback: Boolean
        get() = p.getBoolean("ocr", true)
        set(v) = p.edit().putBoolean("ocr", v).apply()

    var resident: Boolean
        get() = p.getBoolean("resident", true)
        set(v) = p.edit().putBoolean("resident", v).apply()

    var winX: Int
        get() = p.getInt("win_x", -1)
        set(v) = p.edit().putInt("win_x", v).apply()

    var winY: Int
        get() = p.getInt("win_y", -1)
        set(v) = p.edit().putInt("win_y", v).apply()

    var barX: Int
        get() = p.getInt("bar_x", -1)
        set(v) = p.edit().putInt("bar_x", v).apply()

    var barY: Int
        get() = p.getInt("bar_y", -1)
        set(v) = p.edit().putInt("bar_y", v).apply()

    var winW: Int
        get() = p.getInt("win_w", 0)
        set(v) = p.edit().putInt("win_w", v).apply()

    var winH: Int
        get() = p.getInt("win_h", 0)
        set(v) = p.edit().putInt("win_h", v).apply()
}
