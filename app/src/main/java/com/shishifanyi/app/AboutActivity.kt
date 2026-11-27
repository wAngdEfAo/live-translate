package com.shishifanyi.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest

/**
 * 「关于」页：版本、签名、引擎与许可、反馈入口。
 *
 * 单独一个 Activity 而不是塞进首页卡片里 —— 首页已经够长了，
 * 而这些东西一年看一两次。
 */
class AboutActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeMode.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        AppCore.init(this)
        setContentView(buildUi())
        applyBarAppearance()
    }

    /** targetSdk 36 强制 edge-to-edge：不处理 insets 内容会顶到状态栏下面。 */
    private fun applySystemBarPadding(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()
                )
                view.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }
        v.requestApplyInsets()
    }

    private fun applyBarAppearance() {
        val light = !ThemeMode.isDarkNow(this)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val c = window.insetsController ?: return
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                c.setSystemBarsAppearance(if (light) mask else 0, mask)
            } else if (android.os.Build.VERSION.SDK_INT >= 23) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                flags = if (light) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
        } catch (t: Throwable) {
            // 状态栏外观失败不影响内容
        }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(color(R.color.bg))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20f), dp(28f), dp(20f), dp(28f))
        scroll.addView(root)

        root.addView(text("关于", 24f, R.color.fg, true))
        root.addView(text("实时翻译 · 屏幕上的外文就地换成中文或英文", 12.5f, R.color.fg_dim, false)
            .apply { setPadding(0, dp(6f), 0, dp(18f)) })

        // 应用信息
        card(root, "应用") {
            kv(it, "名称", getString(R.string.app_name))
            kv(it, "版本", versionLabel())
            kv(it, "包名", packageName)
            kv(it, "运行环境", "Android " + android.os.Build.VERSION.RELEASE +
                " (API " + android.os.Build.VERSION.SDK_INT + ")")
            kv(it, "机型", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " / " + (android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "?"))
        }

        // 签名：升级能不能覆盖，全看这个指纹对不对
        card(root, "签名证书（SHA-256）") {
            val tv = text(certSha256(), 11f, R.color.fg_dim, false)
            tv.setTypeface(Typeface.MONOSPACE)
            tv.setTextIsSelectable(true)
            tv.background = rounded2(inputBg(), 8f)
            tv.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            it.addView(tv)
            it.addView(text(
                "只有指纹与旧版本一致，新包才能直接覆盖安装；不一致说明签名换了，需要卸载重装。",
                11.5f, R.color.fg_dim, false
            ).apply { setPadding(0, dp(8f), 0, 0) })
        }

        // 引擎
        card(root, "翻译引擎") {
            kv(it, "默认引擎", "Google ML Kit（离线，59 种语言，以英语为枢纽）")
            kv(it, "ML Kit 翻译", "com.google.mlkit:translate 17.0.3")
            kv(it, "ML Kit OCR", "com.google.mlkit:text-recognition 16.0.1")
            kv(it, "语言识别", "com.google.mlkit:language-id 17.0.6")
            kv(it, "高精度引擎（可选）", "OPUS-MT Marian int8 · ONNX Runtime 1.20.0")
            kv(it, "高精度模型", "Helsinki-NLP/opus-mt-en-zh、opus-mt-zh-en（CC-BY 4.0）")
        }

        // 许可
        card(root, "许可") {
            it.addView(text(
                "本应用代码采用 MIT 许可，可自由使用、修改、分发与商用，保留版权声明即可。\n" +
                    "APK 内打包的 Google ML Kit 组件受 Google 自身条款约束；" +
                    "高精度模型为 Helsinki-NLP 的 OPUS-MT，许可 CC-BY 4.0，分发需保留署名。",
                12f, R.color.fg_dim, false
            ))
        }

        // 链接
        card(root, "反馈与链接") {
            val row = LinearLayout(this@AboutActivity)
            row.orientation = LinearLayout.HORIZONTAL
            it.addView(row)
            row.addView(smallBtn("GitHub 仓库") { open("https://github.com/wAngdEfAo/live-translate") },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8f) })
            row.addView(smallBtn("Issues") { open("https://github.com/wAngdEfAo/live-translate/issues") })

            val row2 = LinearLayout(this@AboutActivity)
            row2.orientation = LinearLayout.HORIZONTAL
            row2.setPadding(0, dp(8f), 0, 0)
            it.addView(row2)
            row2.addView(smallBtn("Releases") {
                open("https://github.com/wAngdEfAo/live-translate/releases")
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8f) })
            row2.addView(smallBtn("复制诊断信息") { copyDiagnostics() })
        }

        val foot = text("本软件的代码编写与发布均由 AI 完成。", 11f, R.color.fg_dim, false)
        foot.setPadding(0, dp(16f), 0, 0)
        root.addView(foot)

        applySystemBarPadding(scroll)
        return scroll
    }

    // ---------- 小工具 ----------

    private fun card(root: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(R.color.card, 16f)
        c.setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12f)
        root.addView(c, lp)
        c.addView(text(title, 14f, R.color.fg, true))
        build(c)
    }

    /** 一行「键  值」，值靠右。 */
    private fun kv(parent: LinearLayout, k: String, v: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(7f), 0, 0)
        parent.addView(row)
        row.addView(text(k, 12f, R.color.fg_dim, false))
        val value = text(v, 12f, R.color.fg, false)
        value.gravity = Gravity.END
        value.setTextIsSelectable(true)
        row.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun smallBtn(label: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.textSize = 12.5f
        b.gravity = Gravity.CENTER
        b.setTextColor(color(R.color.accent))
        b.background = rounded2(tintBg(), 10f)
        b.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun text(s: String, sp: Float, colorRes: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = s
        v.textSize = sp
        v.setTextColor(color(colorRes))
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD)
        return v
    }

    private fun rounded(colorRes: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color(colorRes))
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun rounded2(colorValue: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(colorValue)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun color(res: Int): Int =
        if (android.os.Build.VERSION.SDK_INT >= 23) getColor(res) else resources.getColor(res)

    private fun tintBg(): Int = if (ThemeMode.isDarkNow(this)) 0x1AFFFFFF else 0x12000000

    private fun inputBg(): Int = if (ThemeMode.isDarkNow(this)) 0xFF0B0E12.toInt() else 0xFFEDEFF3.toInt()

    private fun dp(v: Float): Int = (resources.displayMetrics.density * v).toInt()

    // ---------- 数据 ----------

    private fun versionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        (info.versionName ?: "?") + " (" + code + ")"
    } catch (t: Throwable) {
        "?"
    }

    /** APK 签名证书的 SHA-256，用于核对「能不能覆盖升级」。 */
    private fun certSha256(): String = try {
        val pm = packageManager
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val bytes = sigs?.firstOrNull()?.toByteArray()
        if (bytes == null || bytes.isEmpty()) {
            "读不到签名信息"
        } else {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(bytes).joinToString(":") { String.format("%02X", it) }
        }
    } catch (t: Throwable) {
        "读不到签名信息：" + (t.message ?: "")
    }

    private fun copyDiagnostics() {
        val info = buildString {
            append("版本：").append(versionLabel()).append('\n')
            append("包名：").append(packageName).append('\n')
            append("系统：Android ").append(android.os.Build.VERSION.RELEASE)
            append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("机型：").append(android.os.Build.MANUFACTURER).append(' ')
            append(android.os.Build.MODEL).append(" / ")
            append(android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "?").append('\n')
            append("证书：").append(certSha256())
            append('\n').append(Latency.summary())
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("diagnostics", info))
            toast("诊断信息已复制")
        } catch (t: Throwable) {
            toast("复制失败")
        }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            toast("没有可用的浏览器，地址：" + url)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}