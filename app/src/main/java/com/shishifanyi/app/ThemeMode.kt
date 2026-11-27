package com.shishifanyi.app

import android.content.Context
import android.content.res.Configuration

/**
 * 主题模式：深色 / 浅色 / 跟随系统。
 *
 * 实现方式刻意选「覆盖 uiMode + values-night」而不是引入 AppCompat：
 * 本项目是纯 Activity（连 AppCompat 都没依赖），界面全用代码搭、颜色都取 `R.color.*`。
 * 在 `attachBaseContext` 里把 Configuration 的 night 位改成目标值，
 * 之后所有 `R.color.bg` 之类会自动落到 `values/` 或 `values-night/`，
 * 一处都不用改。
 */
object ThemeMode {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    private const val PREF = "live_translate"
    private const val KEY = "theme_mode"

    /** 直接读原始偏好，不依赖 Prefs 初始化顺序（attachBaseContext 里要用）。 */
    fun current(ctx: Context): String {
        val v = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, SYSTEM)
        return when (v) {
            LIGHT, DARK -> v!!
            else -> SYSTEM
        }
    }

    fun save(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
    }

    /** 跟随系统时不改配置，交给系统；固定模式则强制 night 位。 */
    fun wrap(base: Context): Context {
        val mode = current(base)
        if (mode == SYSTEM) return base
        val cfg = Configuration(base.resources.configuration)
        val nightBit = if (mode == DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBit
        return try {
            base.createConfigurationContext(cfg)
        } catch (t: Throwable) {
            base
        }
    }

    fun label(mode: String): String = when (mode) {
        LIGHT -> "浅色"
        DARK -> "深色"
        else -> "跟随系统"
    }

    /** 当前实际是不是深色（跟随系统时看系统设置）。 */
    fun isDarkNow(ctx: Context): Boolean {
        val mode = current(ctx)
        if (mode == DARK) return true
        if (mode == LIGHT) return false
        val ui = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return ui == Configuration.UI_MODE_NIGHT_YES
    }
}