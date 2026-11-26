package com.yg.livetranslate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQ_OCR = 1001
        private const val REQ_EXPORT = 1002
        private const val REQ_IMPORT = 1003
    }

    private var accState: TextView? = null
    private var ovState: TextView? = null
    private var packState: TextView? = null
    private var startSwitch: Switch? = null
    private var showBtnRef: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        AppCore.init(this)
        setContentView(buildUi())
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- 界面 ----------

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(color(R.color.bg))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20f), dp(28f), dp(20f), dp(28f))
        scroll.addView(root)

        root.addView(text("实时翻译", 26f, R.color.fg, true))
        root.addView(text("屏幕上的英文，浮窗里变成中文", 13f, R.color.fg_dim, false).apply {
            setPadding(0, dp(6f), 0, dp(20f))
        })

        // 三步引导
        val step1 = stepCard(
            "1. 无障碍权限",
            "用来读取屏幕上的文字",
            "去开启",
            View.OnClickListener { openAccessibility() }
        )
        accState = step1.second
        root.addView(step1.first)

        val step2 = stepCard(
            "2. 悬浮窗权限",
            "译文需要一个悬浮小窗来显示",
            "去开启",
            View.OnClickListener { openOverlayPermission() }
        )
        ovState = step2.second
        root.addView(step2.first)

        val step3 = languageCard()
        packState = step3.second
        root.addView(step3.first)

        // 存储占用
        root.addView(storageCard())

        // ADB 开启无障碍
        root.addView(adbCard())

        // 悬浮窗开关
        val showBtn = TextView(this)
        showBtn.text = "显示悬浮窗"
        showBtn.textSize = 15f
        showBtn.gravity = Gravity.CENTER
        showBtn.setTextColor(color(R.color.bg))
        showBtn.background = rounded(R.color.accent, 12f)
        showBtn.setPadding(dp(18f), dp(13f), dp(18f), dp(13f))
        showBtn.isClickable = true
        showBtn.setOnClickListener { toggleOverlay() }
        showBtnRef = showBtn
        val sbLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        sbLp.topMargin = dp(6f)
        root.addView(showBtn, sbLp)

        // 开关区
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(R.color.card, 16f)
        card.setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(18f)
        root.addView(card, lp)

        startSwitch = Switch(this)
        startSwitch!!.text = "开始翻译"
        startSwitch!!.setTextColor(color(R.color.fg))
        startSwitch!!.textSize = 15f
        startSwitch!!.setPadding(0, dp(10f), 0, dp(10f))
        startSwitch!!.setOnCheckedChangeListener { _, checked ->
            Prefs.translating = checked
            AppCore.resetDedupe()
            AppCore.syncOcrService()
            OverlayService.refreshForeground()
        }
        card.addView(startSwitch)

        card.addView(toggleRow("OCR 兜底（读不到文字时截屏识别）", Prefs.ocrFallback) { on ->
            Prefs.ocrFallback = on
            if (on) ensureOcrPermission() else AppCore.syncOcrService()
        })
        card.addView(toggleRow("后台常驻（防止被系统回收）", Prefs.resident) { Prefs.resident = it })
        card.addView(toggleRow("精准模式（每次截图识别，更准但更费电）", Prefs.accurateMode) { Prefs.accurateMode = it })

        val note = text(
            "提示：译文只在内存里保留最近 3 条，不写入磁盘、不上传。",
            12f, R.color.fg_dim, false
        )
        note.setPadding(0, dp(18f), 0, 0)
        root.addView(note)

        return scroll
    }

    private var storageSize: TextView? = null
    private val langRows = LinkedHashMap<String, Pair<TextView, TextView>>()
    private var langBox: LinearLayout? = null
    private var expandBtn: TextView? = null
    private var currentLangLabel: TextView? = null

    private val LANGS = listOf(
        "auto" to "自动检测", "en" to "英语", "ja" to "日语", "ko" to "韩语",
        "fr" to "法语", "de" to "德语", "es" to "西班牙语",
        "ru" to "俄语", "pt" to "葡萄牙语", "it" to "意大利语",
        "th" to "泰语", "vi" to "越南语", "ar" to "阿拉伯语"
    )
    private var storageDirs: TextView? = null

    /** 语言包：既能在线下载，也能直接导入 zip。 */
    private fun languageCard(): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(R.color.card, 16f)
        card.setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12f)
        card.layoutParams = lp

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        card.addView(head)

        head.addView(text("3. 离线语言包", 15f, R.color.fg, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val st = text("检查中", 12f, R.color.fg_dim, false)
        head.addView(st)

        val d = text(
            "英译中模型约 30MB。可在线下载，也可以直接导入之前导出的 zip。",
            12f, R.color.fg_dim, false
        )
        d.setPadding(0, dp(6f), 0, dp(12f))
        card.addView(d)

        val curRow = LinearLayout(this)
        curRow.orientation = LinearLayout.HORIZONTAL
        curRow.gravity = Gravity.CENTER_VERTICAL
        curRow.setPadding(0, 0, 0, dp(6f))
        card.addView(curRow)

        currentLangLabel = text("当前：英语 → 中文", 12f, R.color.fg_dim, false)
        curRow.addView(currentLangLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val exp = TextView(this)
        exp.text = "展开 ▾"
        exp.textSize = 12f
        exp.setTextColor(color(R.color.accent))
        exp.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        exp.isClickable = true
        expandBtn = exp
        curRow.addView(exp)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(2f), 0, dp(10f))
        box.visibility = View.GONE
        card.addView(box)
        langBox = box
        buildLangRows(box)

        exp.setOnClickListener {
            if (langBox == null) return@setOnClickListener
            val show = langBox!!.visibility != View.VISIBLE
            langBox!!.visibility = if (show) View.VISIBLE else View.GONE
            expandBtn?.text = if (show) "收起 ▴" else "展开 ▾"
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        card.addView(row)

        val btn = TextView(this)
        btn.text = "下载"
        btn.textSize = 13f
        btn.setTextColor(color(R.color.bg))
        btn.gravity = Gravity.CENTER
        btn.background = rounded(R.color.accent, 10f)
        btn.setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
        btn.isClickable = true
        btn.setOnClickListener { downloadPack() }
        row.addView(btn)

        val sp = View(this)
        row.addView(sp, LinearLayout.LayoutParams(dp(10f), 1))

        val imp = TextView(this)
        imp.text = "导入 zip"
        imp.textSize = 13f
        imp.setTextColor(color(R.color.ok))
        imp.gravity = Gravity.CENTER
        imp.background = rounded2(0x1AFFFFFF, 10f)
        imp.setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
        imp.isClickable = true
        imp.setOnClickListener { importPack() }
        row.addView(imp)

        return Pair(card, st)
    }

    private fun buildLangRows(box: LinearLayout) {
        langRows.clear()
        for ((code, name) in LANGS) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(5f), 0, dp(5f))

            val title = TextView(this)
            title.text = if (code == "auto") "自动检测 → 中文" else name + " → 中文"
            title.textSize = 13f
            title.isClickable = true
            title.setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            title.setOnClickListener {
                Prefs.srcLang = code
                AppCore.resetDedupe()
                refreshLangRows()
                refresh()
                toast("已切换源语言：" + name)
            }
            row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val state = TextView(this)
            state.text = ""
            state.textSize = 11f
            state.setTextColor(color(R.color.fg_dim))
            row.addView(state)

            val action = TextView(this)
            action.textSize = 11.5f
            action.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            if (code == "auto") {
                action.text = "自带"
                action.setTextColor(color(R.color.fg_dim))
            } else {
                action.isClickable = true
                action.setOnClickListener { toggleLang(code, name, action) }
            }
            row.addView(action)

            langRows[code] = Pair(state, action)
            box.addView(row)
        }
    }

    private fun toggleLang(code: String, name: String, action: TextView) {
        LanguagePack.isDownloaded(code) { downloaded ->
            runOnUiThread {
                if (downloaded) {
                    LanguagePack.delete(code) { ok ->
                        runOnUiThread {
                            toast(if (ok) "已删除 $name 语言包" else "删除失败")
                            refreshLangRows()
                            refresh()
                        }
                    }
                } else {
                    Prefs.srcLang = code
                    packState?.text = "下载中"
                    packState?.setTextColor(color(R.color.accent))
                    LanguagePack.download(code, Prefs.tgtLang) { ok, msg ->
                        runOnUiThread {
                            packState?.text = msg
                            packState?.setTextColor(color(if (ok) R.color.ok else R.color.warn))
                            refreshLangRows()
                        }
                    }
                }
            }
        }
    }

    private fun refreshLangRows() {
        val cur = Prefs.srcLang
        val curName = LANGS.firstOrNull { it.first == cur }?.second ?: "英语"
        currentLangLabel?.text = "当前：" + curName + " → 中文"

        for ((code, name) in LANGS) {
            val pair = langRows[code] ?: continue
            val state = pair.first
            val action = pair.second
            val isCur = code == cur
            state.text = if (isCur) "使用中 " else ""
            state.setTextColor(color(if (isCur) R.color.accent else R.color.fg_dim))
            if (code == "auto") continue
            LanguagePack.isDownloaded(code) { downloaded ->
                runOnUiThread {
                    if (downloaded) {
                        action.text = "删除"
                        action.setTextColor(color(R.color.warn))
                        action.background = rounded2(0x1AFFFFFF, 8f)
                    } else {
                        action.text = "下载"
                        action.setTextColor(color(R.color.ok))
                        action.background = rounded2(0x1AFFFFFF, 8f)
                    }
                }
            }
        }
    }

    private fun storageCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(R.color.card, 16f)
        card.setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12f)
        card.layoutParams = lp

        card.addView(text("存储", 15f, R.color.fg, true))

        val sz = text("计算中…", 12f, R.color.fg_dim, false)
        sz.setPadding(0, dp(6f), 0, dp(10f))
        card.addView(sz)
        storageSize = sz

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(4f), 0, 0)
        card.addView(row)

        row.addView(smallBtn("导出语言包为 zip", R.color.accent) { exportPack() })

        val tip = text("换机或重装前先导出，之后在第 3 步用「导入 zip」还原，免下载。", 11f, R.color.fg_dim, false)
        tip.setPadding(0, dp(8f), 0, 0)
        card.addView(tip)

        return card
    }

    private fun exportPack() {
        try {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "实时翻译-语言包-英中.zip")
            }
            startActivityForResult(i, REQ_EXPORT)
        } catch (t: Throwable) {
            toast("无法打开保存界面")
        }
    }

    private fun importPack() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
            }
            startActivityForResult(i, REQ_IMPORT)
        } catch (t: Throwable) {
            toast("无法打开文件选择器")
        }
    }

    private fun doExport(uri: android.net.Uri) {
        toast("正在打包…")
        Thread {
            try {
                contentResolver.openOutputStream(uri)?.use { raw ->
                    ZipOutputStream(BufferedOutputStream(raw)).use { zos ->
                        addDirToZip(zos, filesDir, "files")
                        addDirToZip(zos, noBackupFilesDir, "nobackup")
                        cacheDir?.let { addDirToZip(zos, it, "cache") }
                    }
                }
                runOnUiThread { toast("语言包已导出") }
            } catch (t: Throwable) {
                runOnUiThread { toast("导出失败：" + (t.message ?: "未知错误")) }
            }
        }.start()
    }

    private fun addDirToZip(zos: ZipOutputStream, dir: File, prefix: String) {
        val root = dir.absolutePath
        val all = dir.walkTopDown().filter { it.isFile }.toList()
        for (f in all) {
            val rel = f.absolutePath.removePrefix(root).trimStart('/')
            if (rel.isEmpty()) continue
            zos.putNextEntry(ZipEntry(prefix + "/" + rel))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun doImport(uri: android.net.Uri) {
        toast("正在导入…")
        Thread {
            var count = 0
            try {
                contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(BufferedInputStream(raw)).use { zis ->
                        var e = zis.nextEntry
                        while (e != null) {
                            val target = resolveTarget(e.name)
                            if (target != null && !e.isDirectory) {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { zis.copyTo(it) }
                                count++
                            }
                            zis.closeEntry()
                            e = zis.nextEntry
                        }
                    }
                }
                runOnUiThread {
                    storageDirs?.postDelayed({ refreshStorage() }, 300L)
                    packState?.text = "校验中"
                    packState?.setTextColor(color(R.color.accent))
                    LanguagePack.verify(Prefs.srcLang, Prefs.tgtLang) { ok ->
                        runOnUiThread {
                            packState?.text = if (ok) "已就绪" else "导入未生效"
                            packState?.setTextColor(color(if (ok) R.color.ok else R.color.warn))
                            toast(
                                if (ok) "导入成功，共 $count 个文件"
                                else "写入了 $count 个文件，但语言包仍未识别"
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("导入失败：" + (t.message ?: "未知错误")) }
            }
        }.start()
    }

    /**
     * ML Kit 的真实模型目录：私有目录/com.google.mlkit.translate.models/<dict.xxx>/
     */
    private fun modelRoot(): File =
        File(noBackupFilesDir, "com.google.mlkit.translate.models")

    private fun resolveTarget(name: String): File? {
        val clean = name.replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return null
        val parts = clean.split('/')
        if (parts.contains("..")) return null

        val base: File
        val rest: List<String>
        when {
            parts[0] == "files" -> {
                base = filesDir
                rest = parts.drop(1)
            }
            parts[0] == "nobackup" -> {
                base = noBackupFilesDir
                rest = parts.drop(1)
            }
            parts[0] == "cache" -> {
                base = cacheDir
                rest = parts.drop(1)
            }
            parts[0].startsWith("dict.") -> {
                // 官方语言包：直接落到 ML Kit 的模型根目录
                base = modelRoot()
                rest = parts
            }
            else -> {
                base = filesDir
                rest = parts
            }
        }
        if (rest.isEmpty()) return null
        return File(base, rest.joinToString("/"))
    }

    private fun refreshStorage() {
        val t = storageSize ?: return
        Thread {
            val cache = dirSize(cacheDir) + (externalCacheDir?.let { dirSize(it) } ?: 0L)
            val apk = try {
                packageManager.getPackageInfo(packageName, 0).let { info ->
                    val ai = info.applicationInfo
                    if (ai == null) 0L else java.io.File(ai.sourceDir).length()
                }
            } catch (e: Throwable) {
                0L
            }
            val files = dirSize(filesDir)
            val nob = dirSize(noBackupFilesDir)
            val listing = dirListing(filesDir, "files") + "\n" + dirListing(cacheDir, "cache")
            runOnUiThread {
                t.text = "APK " + mb(apk) +
                    "\n数据 " + mb(files) +
                    " · 私有 " + mb(nob) +
                    " · 缓存 " + mb(cache)
                storageDirs?.text = listing
            }
        }.start()
    }

    private fun clearCaches() {
        Thread {
            try {
                cacheDir.deleteRecursively()
            } catch (t: Throwable) {
            }
            try {
                externalCacheDir?.deleteRecursively()
            } catch (t: Throwable) {
            }
            runOnUiThread {
                refreshStorage()
        refreshLangRows()
                toast("缓存已清理（语言包若被清掉，下次翻译需重新下载）")
            }
        }.start()
    }

    private fun dirListing(dir: java.io.File, label: String): String {
        val kids = dir?.listFiles()
        if (kids == null || kids.isEmpty()) return label + ": （空）"
        val rows = kids.map { it.name to dirSize(it) }
            .sortedByDescending { it.second }
            .take(5)
        return label + ":\n" + rows.joinToString("\n") { "  " + it.first + "  " + mb(it.second) }
    }

    private fun dirSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) total += dirSize(f)
        return total
    }

    private fun mb(bytes: Long): String = String.format("%.1fMB", bytes / 1048576.0)

    /** 一条 pm grant 授权后，App 自己开关无障碍，不需要在终端写脚本。 */
    private fun adbCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(R.color.card, 16f)
        card.setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12f)
        card.layoutParams = lp

        card.addView(text("4. 一键开启无障碍", 15f, R.color.fg, true))

        val desc = text(
            "在电脑 cmd 里执行一次下面这行授权，之后本页就能直接开关无障碍，" +
                "不用再进系统设置，也不会动到别的无障碍服务。",
            12f, R.color.fg_dim, false
        )
        desc.setPadding(0, dp(6f), 0, dp(10f))
        card.addView(desc)

        val cmd = "adb shell pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS"
        val code = TextView(this)
        code.text = cmd
        code.textSize = 10.5f
        code.setTextColor(0xFF9AA4B2.toInt())
        code.setTypeface(android.graphics.Typeface.MONOSPACE)
        code.setTextIsSelectable(true)
        code.background = rounded2(0xFF0B0E12.toInt(), 10f)
        code.setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
        card.addView(code)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(10f), 0, 0)
        card.addView(row)

        row.addView(smallBtn("复制命令", R.color.accent) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                toast("已复制，粘到 cmd 里执行")
            } catch (t: Throwable) {
                toast("复制失败")
            }
        })

        val sp = View(this)
        row.addView(sp, LinearLayout.LayoutParams(dp(10f), 1))

        row.addView(smallBtn("开启无障碍", R.color.ok) { toggleAccessibilityBySelf() })

        val tip = text(
            "没有授权时点「开启无障碍」会提示去授权；也可以继续用第 1 张卡片手动开。",
            11f, R.color.fg_dim, false
        )
        tip.setPadding(0, dp(8f), 0, 0)
        card.addView(tip)

        return card
    }

    private fun hasWriteSecure(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 已授权时直接改系统设置：只追加自己这一条，保留其它无障碍服务。 */
    private fun toggleAccessibilityBySelf() {
        if (!hasWriteSecure()) {
            toast("尚未授权，请先复制上面的命令在电脑 cmd 里执行一次")
            return
        }
        val svc = packageName + "/" + ScreenReaderService::class.java.name
        try {
            if (isAccessibilityOn()) {
                val cur = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val kept = cur.split(':')
                    .filter { it.isNotBlank() && !it.equals(svc, true) }
                    .joinToString(":")
                Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    kept
                )
                toast("已关闭无障碍")
            } else {
                val cur = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val merged = when {
                    cur.isBlank() || cur == "null" -> svc
                    cur.split(':').any { it.equals(svc, true) } -> cur
                    else -> cur + ":" + svc
                }
                Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    merged
                )
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                toast("已开启无障碍")
            }
        } catch (t: Throwable) {
            toast("写入失败：" + (t.message ?: "无权限"))
        }
        showBtnRef?.postDelayed({ refresh() }, 400L)
    }

    private fun smallBtn(label: String, colorRes: Int, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.textSize = 12.5f
        b.gravity = Gravity.CENTER
        b.setTextColor(color(colorRes))
        b.background = rounded2(0x1AFFFFFF, 10f)
        b.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun rounded2(colorValue: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(colorValue)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 有 root 时开启无障碍：先读现有值，只追加自己这一条，不覆盖别人。 */
    private fun stepCard(
        title: String,
        desc: String,
        action: String,
        onClick: View.OnClickListener
    ): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(R.color.card, 16f)
        card.setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12f)
        card.layoutParams = lp

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        card.addView(head)

        val t = text(title, 15f, R.color.fg, true)
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(t, tlp)

        val st = text("检查中", 12f, R.color.fg_dim, false)
        head.addView(st)

        val d = text(desc, 12f, R.color.fg_dim, false)
        d.setPadding(0, dp(6f), 0, dp(12f))
        card.addView(d)

        val btn = TextView(this)
        btn.text = action
        btn.textSize = 13f
        btn.setTextColor(color(R.color.bg))
        btn.gravity = Gravity.CENTER
        btn.background = rounded(R.color.accent, 10f)
        btn.setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
        btn.isClickable = true
        btn.setOnClickListener(onClick)
        card.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return Pair(card, st)
    }

    private fun toggleRow(label: String, init: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6f), 0, dp(6f))

        val sw = Switch(this)
        sw.isChecked = init
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }

        val t = text(label, 13f, R.color.fg, false)
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tlp.rightMargin = dp(10f)
        row.addView(t, tlp)
        row.addView(sw)
        return row
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

    private fun color(res: Int): Int = if (android.os.Build.VERSION.SDK_INT >= 23) getColor(res) else resources.getColor(res)

    private fun dp(v: Float): Int = (resources.displayMetrics.density * v).toInt()

    // ---------- 状态与跳转 ----------

    private fun refresh() {
        val accOn = isAccessibilityOn()
        accState?.text = if (accOn) "已开启" else "未开启"
        accState?.setTextColor(color(if (accOn) R.color.ok else R.color.warn))

        val ovOn = Settings.canDrawOverlays(this)
        ovState?.text = if (ovOn) "已开启" else "未开启"
        ovState?.setTextColor(color(if (ovOn) R.color.ok else R.color.warn))

        if (LanguagePack.ready) {
            packState?.text = "已就绪"
            packState?.setTextColor(color(R.color.ok))
        } else if (LanguagePack.busy) {
            packState?.text = "下载中"
            packState?.setTextColor(color(R.color.accent))
        } else {
            packState?.text = "未下载"
            packState?.setTextColor(color(R.color.warn))
        }

        refreshStorage()
        refreshLangRows()

        startSwitch?.isChecked = Prefs.translating

        val showing = OverlayService.isShowing()
        showBtnRef?.text = if (showing) "关闭悬浮窗" else "显示悬浮窗"
        showBtnRef?.background = rounded(if (showing) R.color.card else R.color.accent, 12f)
        showBtnRef?.setTextColor(color(if (showing) R.color.fg else R.color.bg))
    }

    private fun isAccessibilityOn(): Boolean {
        val expected = "$packageName/${ScreenReaderService::class.java.name}"
        val short = "$packageName/.ScreenReaderService"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, true) || it.equals(short, true) }
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            // 无法跳转时留给用户手动找
        }
    }

    private fun openOverlayPermission() {
        try {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (t2: Throwable) {
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OCR && resultCode == RESULT_OK && data != null) {
            OcrService.setPermission(data)
            OcrService.start(this)
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQ_EXPORT -> doExport(data.data!!)
            REQ_IMPORT -> doImport(data.data!!)
        }
    }

    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
        } catch (t: Throwable) {
        }
    }

    private fun toggleOverlay() {
        if (OverlayService.isShowing()) {
            OverlayService.stop(this)
            showBtnRef?.postDelayed({ refresh() }, 200L)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        OverlayService.start(this)
        showBtnRef?.postDelayed({ refresh() }, 300L)
    }

    private fun ensureOcrPermission() {
        if (OcrService.hasPermission() || OcrService.isRunning()) {
            OcrService.start(this)
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_OCR)
        } catch (t: Throwable) {
        }
    }

    private fun downloadPack() {
        if (LanguagePack.busy) return
        packState?.text = "下载中"
        packState?.setTextColor(color(R.color.accent))
        LanguagePack.download(Prefs.srcLang, Prefs.tgtLang) { ok, msg ->
            runOnUiThread {
                packState?.text = msg
                packState?.setTextColor(color(if (ok) R.color.ok else R.color.warn))
            }
        }
    }
}
