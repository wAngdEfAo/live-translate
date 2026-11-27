package com.shishifanyi.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(c: Context) {
        if (sp == null) {
            sp = c.applicationContext.getSharedPreferences("live_translate", Context.MODE_PRIVATE)
            migrate()
        }
    }

    /**
     * 一次性迁移。
     *
     * 「OCR 兜底」从 5.5.1 起默认关闭。但老版本默认是开的，而且用户只要碰过一次开关，
     * `true` 就已经写进偏好了 —— 光把 getBoolean 的默认值改成 false 压不住它，
     * 升级上来的机器还是显示「开」。所以这里强制关一次，之后用户的开关照样生效。
     */
    private fun migrate() {
        val p = sp ?: return
        try {
            if (!p.getBoolean(KEY_OCR_MIGRATED, false)) {
                p.edit()
                    .putBoolean("ocr", false)
                    .putBoolean(KEY_OCR_MIGRATED, true)
                    .apply()
            }
        } catch (t: Throwable) {
            // 迁移失败不该拦住启动
        }
    }

    private const val KEY_OCR_MIGRATED = "ocr_default_off_v2"

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

    /** 超过这个长度就不走高精度：无 KV cache 的解码是 O(n²)，几千字符必然超时。 */
    var onnxMaxChars: Int
        get() = p.getInt("onnx_max_chars", 400)
        set(v) = p.edit().putInt("onnx_max_chars", v.coerceIn(64, 2000)).apply()

    /** 模型包下载任务 id（DownloadManager），-1 表示没有在跑的任务。 */
    var engineDlId: Long
        get() = p.getLong("engine_dl_id", -1L)
        set(v) = p.edit().putLong("engine_dl_id", v).apply()

    /** 只用 Wi-Fi 下载模型包（默认开，294MB 不该走流量）。 */
    var engineWifiOnly: Boolean
        get() = p.getBoolean("engine_wifi_only", true)
        set(v) = p.edit().putBoolean("engine_wifi_only", v).apply()

    /**
     * 上一次轮询看到的已下载字节。
     * 存盘是为了让 recreate/旋转/回前台之后仍知道"进度到哪了"，
     * 否则首帧看到的 downloaded 会被误判成"正在增长"。
     */
    var engineDlBytes: Long
        get() = p.getLong("engine_dl_bytes", 0L)
        set(v) = p.edit().putLong("engine_dl_bytes", v).apply()

    /** 是否曾经成功授权过屏幕捕获；用于区分"从未授权"和"授权失效"。 */
    var ocrEverGranted: Boolean
        get() = p.getBoolean("ocr_ever_granted", false)
        set(v) = p.edit().putBoolean("ocr_ever_granted", v).apply()

    /** OCR 兜底：默认**关**。没有截屏授权它根本跑不起来，开着只会让人以为能用。 */
    var ocrFallback: Boolean
        get() = p.getBoolean("ocr", false)
        set(v) = p.edit().putBoolean("ocr", v).apply()

    /** 控制条位置（旧字段，绝对像素）。新代码请用 barXF/barYF。 */
    var barX: Int
        get() = p.getInt("bar_x", -1)
        set(v) = p.edit().putInt("bar_x", v).apply()

    var barY: Int
        get() = p.getInt("bar_y", -1)
        set(v) = p.edit().putInt("bar_y", v).apply()

    /**
     * 控制条位置，存**比例**而不是像素。
     * 绝对像素在转横屏后会落到屏外（竖屏 1500px 的 y 在横屏 1080px 屏上就看不见了），
     * 而且没有任何入口能把它弄回来。<0 表示用户从没摆过，走默认位置。
     */
    var barXF: Float
        get() = p.getFloat("bar_xf", -1f)
        set(v) = p.edit().putFloat("bar_xf", v).apply()

    var barYF: Float
        get() = p.getFloat("bar_yf", -1f)
        set(v) = p.edit().putFloat("bar_yf", v).apply()
}
