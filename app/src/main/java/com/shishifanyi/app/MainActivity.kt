package com.shishifanyi.app

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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_OCR = 1001
        private const val REQ_EXPORT = 1002
        private const val REQ_IMPORT = 1003
        private const val REQ_ENGINE_IMPORT = 1004
        private const val REQ_ENGINE_EXPORT = 1005

        // 导入 zip 的上限：条目数或解压体积任意一项超限就停止，避免把内部存储写满
        private const val MAX_ENTRY_COUNT = 200
        private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

        /** 单条目上限：ML Kit 模型目录里最大的文件也就百兆出头，明显超过的多半是炸弹。 */
        private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
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
        root.addView(text("屏幕上的外文，就地变成中文或英文", 13f, R.color.fg_dim, false).apply {
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

        // 高精度引擎（可选第二引擎）
        root.addView(engineCard())

        // ADB 开启无障碍
        root.addView(adbCard())

        // 反馈
        root.addView(feedbackCard())

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
            // 重新打开时主动取一次词：屏幕不动就没有无障碍事件，否则打开后一直空白
            if (checked) ScreenReaderService.requestCapture()
        }
        card.addView(startSwitch)

        card.addView(toggleRow("OCR 兜底（读不到文字时截屏识别）", Prefs.ocrFallback) { on ->
            Prefs.ocrFallback = on
            if (on) ensureOcrPermission() else AppCore.syncOcrService()
        })
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

    /** 语言行状态的刷新代次与在途标记：防止 onResume 把 58 次查询排队复制。 */
    private var langRowGen = 0
    private var langRowBusy = false
    private var langRowAt = 0L
    private var langBox: LinearLayout? = null
    private var expandBtn: TextView? = null
    private var currentLangLabel: TextView? = null

    /** 目标语言的显示名。 */
    private fun targetName(code: String) =
        if (code == Languages.TARGET_EN) "英文" else "中文"

    /** 目标语言选择按钮。 */
    private val tgtChips = LinkedHashMap<String, TextView>()

    /** 选中「自动检测」时在卡片里常驻的说明（toast 单行放不下那么长的文案）。 */
    private var autoHint: TextView? = null

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
            "单语言模型约 35MB；非英语语言翻中文要经英语中转，需下两个模型（约 70MB）。",
            12f, R.color.fg_dim, false
        )
        d.setPadding(0, dp(6f), 0, dp(10f))
        card.addView(d)

        // 目标语言：中文 / 英文
        val tgtRow = LinearLayout(this)
        tgtRow.orientation = LinearLayout.HORIZONTAL
        tgtRow.gravity = Gravity.CENTER_VERTICAL
        tgtRow.setPadding(0, 0, 0, dp(10f))
        card.addView(tgtRow)
        tgtRow.addView(text("翻译成：", 12f, R.color.fg_dim, false))
        tgtChips.clear()
        for ((code, label) in listOf(
            Languages.TARGET_ZH to "中文",
            Languages.TARGET_EN to "英文"
        )) {
            val chip = TextView(this)
            chip.text = label
            chip.textSize = 12.5f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
            chip.isClickable = true
            chip.setOnClickListener { switchTarget(code) }
            val clp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            clp.rightMargin = dp(8f)
            tgtRow.addView(chip, clp)
            tgtChips[code] = chip
        }

        val curRow = LinearLayout(this)
        curRow.orientation = LinearLayout.HORIZONTAL
        curRow.gravity = Gravity.CENTER_VERTICAL
        curRow.setPadding(0, 0, 0, dp(6f))
        card.addView(curRow)

        currentLangLabel = text("当前：英语 → 中文", 12f, R.color.fg_dim, false)
        curRow.addView(currentLangLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 「自动检测」的说明用常驻文本，不用 toast——文案太长，toast 一定会被截断
        val hint = text("", 11f, R.color.fg_dim, false)
        hint.setPadding(0, 0, 0, dp(8f))
        hint.visibility = View.GONE
        card.addView(hint)
        autoHint = hint

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
            // 展开时才去查每个语言的状态
            if (show) refreshLangRows()
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
        box.removeAllViews()
        // 重建后旧一轮的「在途」标记没有意义，放掉
        langRowBusy = false
        val tgt = Prefs.tgtLang
        // 自动检测排第一，其余按 Languages 里的顺序（常用在前）
        val entries = ArrayList<Pair<String, String>>(Languages.ALL.size)
        entries.add("auto" to "自动检测")
        for (l in Languages.sourcesFor(tgt)) entries.add(l.code to l.name)

        for ((code, name) in entries) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(5f), 0, dp(5f))

            val title = TextView(this)
            title.text = name + " → " + targetName(tgt)
            title.textSize = 13f
            title.isClickable = true
            title.setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            title.setOnClickListener { pickSource(code, name) }
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

    /** 点语言名 = 切到它（模型没下就先提示下载）。 */
    private fun pickSource(code: String, name: String) {
        val tgt = Prefs.tgtLang
        if (code == "auto") {
            Prefs.srcLang = "auto"
            LanguagePack.clearReady(Prefs.srcLang, Prefs.tgtLang)
            AppCore.resetDedupe()
            refreshLangRows()
            refresh()
            showAutoHint(tgt)
            return
        }
        LanguagePack.isReady(code, tgt) { ready ->
            runOnUiThread {
                if (!ready) {
                    toast(name + " → " + targetName(tgt) + " 的模型还没下载，先点右侧「下载」")
                    return@runOnUiThread
                }
                Prefs.srcLang = code
                LanguagePack.clearReady(Prefs.srcLang, Prefs.tgtLang)
                AppCore.resetDedupe()
                refreshLangRows()
                refresh()
                toast("已切换：" + name + " → " + targetName(tgt))
            }
        }
    }

    /** 选了「自动检测」时显示常驻说明（toast 放不下完整文案）。 */
    private fun showAutoHint(tgt: String) {
        val h = autoHint ?: return
        h.text = buildString {
            append("· 在某些语言下翻译速度可能出现影响，具体影响速度受到mlkit中转次数而增加\n")
            append("· 自动检测目前还在测试阶段，可能出现翻译时间长，翻译不准确等问题\n")
            append("· 当前目标：" + targetName(tgt) + "，检测池 58 种语言（不含" + targetName(tgt) + "自身）。")
        }
        h.visibility = View.VISIBLE
    }

    /** 切换目标语言；源语言若与新目标撞车，自动换成另一个。 */
    private fun switchTarget(code: String) {
        if (Prefs.tgtLang == code) return
        Prefs.tgtLang = code
        LanguagePack.clearReady(Prefs.srcLang, Prefs.tgtLang)
        AppCore.resetDedupe()
        if (Prefs.srcLang == code) {
            Prefs.srcLang =
                if (code == Languages.TARGET_EN) Languages.TARGET_ZH else Languages.TARGET_EN
        }
        val box = langBox
        if (box != null) buildLangRows(box)
        refreshLangRows()
        refresh()
        toast("翻译目标已切换到" + targetName(code))
    }

    /** 右侧按钮：已就绪 → 删除；未就绪 → 下载（下完自动切过去）。 */
    private fun toggleLang(code: String, name: String, action: TextView) {
        val tgt = Prefs.tgtLang
        LanguagePack.isReady(code, tgt) { ready ->
            runOnUiThread {
                if (ready && code == Languages.TARGET_EN) {
                    toast("英语是枢纽语言，没有独立模型可删")
                    return@runOnUiThread
                }
                if (ready) {
                    LanguagePack.deleteSource(code) { ok ->
                        runOnUiThread {
                            toast(if (ok) "已删除 $name 模型" else "删除失败（英语模型不可删）")
                            refreshLangRows()
                            refresh()
                        }
                    }
                    return@runOnUiThread
                }
                packState?.text = "下载中"
                packState?.setTextColor(color(R.color.accent))
                val need = LanguagePack.neededModels(code, tgt).size
                toast(
                    if (need > 1) "需要下载 $need 个模型（约 ${need * 35}MB）"
                    else "开始下载模型（约 35MB）"
                )
                LanguagePack.download(code, tgt) { ok, msg ->
                    runOnUiThread {
                        packState?.text = msg
                        packState?.setTextColor(color(if (ok) R.color.ok else R.color.warn))
                        // 只有下载成功才切源语言；失败就留在原语言
                        if (ok) {
                            Prefs.srcLang = code
                            LanguagePack.clearReady(Prefs.srcLang, Prefs.tgtLang)
                            AppCore.resetDedupe()
                        }
                        refreshLangRows()
                        refresh()
                    }
                }
            }
        }
    }

    private fun refreshLangRows() {
        val cur = Prefs.srcLang
        val tgt = Prefs.tgtLang
        val curName = if (cur == "auto") "自动检测" else Languages.name(cur)
        currentLangLabel?.text = "当前：$curName → " + targetName(tgt)
        // 自动检测的说明跟着当前选择走；换成具体语言就收起来
        if (cur == "auto") showAutoHint(tgt) else autoHint?.visibility = View.GONE

        // 目标语言按钮高亮
        for ((code, chip) in tgtChips) {
            val on = code == tgt
            chip.setTextColor(color(if (on) R.color.bg else R.color.fg_dim))
            chip.background =
                if (on) rounded(R.color.accent, 10f) else rounded2(0x1AFFFFFF, 10f)
        }

        // 列表收起时不去查 58 个模型的状态，省掉上百次查询；展开时再刷新
        if (langBox?.visibility != View.VISIBLE) return

        // 「自动检测」那行没有模型，状态直接在这里写
        val autoPair = langRows["auto"]
        val autoOn = cur == "auto"
        autoPair?.first?.text = if (autoOn) "使用中 " else ""
        autoPair?.first?.setTextColor(color(if (autoOn) R.color.accent else R.color.fg_dim))

        // 上一轮还没回来就别再叠一轮：onResume 会反复触发，58 次 IPC 不该排队复制；
        // 超过 3 秒还没收尾就当作丢了，允许重来
        val now = System.currentTimeMillis()
        if (langRowBusy && now - langRowAt < 3000L) return
        langRowBusy = true
        langRowAt = now
        val gen = ++langRowGen

        val codes = langRows.keys.filter { it != "auto" }
        if (codes.isEmpty()) {
            langRowBusy = false
            return
        }
        var left = codes.size
        for (code in codes) {
            LanguagePack.isReady(code, tgt) { ready ->
                runOnUiThread {
                    // 代次不符说明目标语言已经换了，这一轮结果全部作废
                    if (gen == langRowGen) applyLangRow(code, tgt, ready)
                    left--
                    if (left == 0 && gen == langRowGen) langRowBusy = false
                }
            }
        }
    }

    /** 把某一行的「使用中」标记与右侧按钮画上去。 */
    private fun applyLangRow(code: String, tgt: String, ready: Boolean) {
        val pair = langRows[code] ?: return
        val state = pair.first
        val action = pair.second
        val isCur = code == Prefs.srcLang
        state.text = if (isCur) "使用中 " else ""
        state.setTextColor(color(if (isCur) R.color.accent else R.color.fg_dim))
        if (code == Languages.TARGET_EN) {
            // 英语是枢纽：它自己没有独立模型，这一行要的是「目标语言」模型。
            // 删它等于删掉整个目标语言包，deleteSource 也会直接拒绝，
            // 所以这里不提供「删除」，只给下载/已就绪。
            action.text = if (ready) "已就绪" else "下载"
            action.isClickable = !ready
            action.setTextColor(color(if (ready) R.color.ok else R.color.fg_dim))
            action.background = rounded2(0x1AFFFFFF, 8f)
            return
        }
        if (ready) {
            action.text = "删除"
            action.setTextColor(color(R.color.warn))
            action.background = rounded2(0x1AFFFFFF, 8f)
        } else {
            action.text = "下载"
            action.setTextColor(color(R.color.ok))
            action.background = rounded2(0x1AFFFFFF, 8f)
        }
    }

    private var engineState: TextView? = null
    private val engineChips = LinkedHashMap<String, TextView>()
    private var onnxTestIn: android.widget.EditText? = null
    private var onnxTestOut: TextView? = null

    private fun pairName(k: String): String = when (k) {
        "en_zh" -> "英→中"
        "zh_en" -> "中→英"
        else -> k
    }

    /**
     * 高精度引擎卡片：引擎切换、模型包导入导出、以及一个立刻能试出来的精翻输入框。
     * 模型不进 APK（单方向约 146MB），必须单独导入。
     */
    private fun engineCard(): LinearLayout {
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

        card.addView(text("高精度引擎（可选）", 15f, R.color.fg, true))
        card.addView(
            text(
                "用 OPUS-MT 的 int8 模型做英⇄中精翻，长句更通顺，但比 ML Kit 慢很多。" +
                    "模型不打进安装包：单方向约 146MB，需单独导入。实时覆盖默认仍走 ML Kit。",
                12f, R.color.fg_dim, false
            ).apply { setPadding(0, dp(6f), 0, dp(10f)) }
        )

        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.gravity = Gravity.CENTER_VERTICAL
        chipRow.setPadding(0, 0, 0, dp(8f))
        card.addView(chipRow)
        chipRow.addView(text("引擎：", 12f, R.color.fg_dim, false))
        engineChips.clear()
        for ((code, label) in listOf(Engines.ENGINE_MLKIT to "标准", Engines.ENGINE_ONNX to "高精度")) {
            val chip = TextView(this)
            chip.text = label
            chip.textSize = 12.5f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
            chip.isClickable = true
            chip.setOnClickListener { pickEngine(code) }
            val clp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            clp.rightMargin = dp(8f)
            chipRow.addView(chip, clp)
            engineChips[code] = chip
        }

        card.addView(toggleRow("仅长句用高精度（短文案仍走 ML Kit）", Prefs.onnxLongOnly) {
            Prefs.onnxLongOnly = it
            AppCore.resetDedupe()
        })

        val st = text("检查中…", 12f, R.color.fg_dim, false)
        st.setPadding(0, dp(4f), 0, dp(8f))
        card.addView(st)
        engineState = st

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        card.addView(row)
        val imp = smallBtn("导入模型包", R.color.accent) { importEnginePack() }
        val impLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        impLp.rightMargin = dp(10f)
        row.addView(imp, impLp)
        val exp = smallBtn("导出", R.color.fg_dim) { exportEnginePack() }
        val expLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        expLp.rightMargin = dp(10f)
        row.addView(exp, expLp)
        row.addView(smallBtn("删除", R.color.warn) { removeEnginePack() })

        // 精翻自测：直接翻一段，看效果也看耗时
        val input = android.widget.EditText(this)
        input.hint = "在这里输入一句原文，试试高精度引擎"
        input.textSize = 13f
        input.setTextColor(color(R.color.fg))
        input.setHintTextColor(color(R.color.fg_dim))
        input.setBackground(rounded2(0xFF0B0E12.toInt(), 10f))
        input.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        input.minLines = 2
        input.gravity = Gravity.TOP or Gravity.START
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val inputLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        inputLp.topMargin = dp(10f)
        card.addView(input, inputLp)
        onnxTestIn = input

        val out = text("", 12.5f, R.color.ok, false)
        out.setPadding(0, dp(8f), 0, 0)
        card.addView(out)
        onnxTestOut = out

        val testLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        testLp.topMargin = dp(8f)
        card.addView(smallBtn("精翻这段", R.color.ok) { runEngineTest() }, testLp)

        return card
    }

    private fun pickEngine(code: String) {
        if (Prefs.engine == code) return
        Prefs.engine = code
        AppCore.resetDedupe()
        refreshEngineCard()
        toast(
            if (code == Engines.ENGINE_ONNX) "已切到高精度（仅英⇄中，长句优先）"
            else "已切回标准引擎"
        )
    }

    private fun refreshEngineCard() {
        val on = Prefs.engine == Engines.ENGINE_ONNX
        for ((code, chip) in engineChips) {
            val sel = code == Prefs.engine
            chip.setTextColor(color(if (sel) R.color.bg else R.color.fg_dim))
            chip.background =
                if (sel) rounded(R.color.accent, 10f) else rounded2(0x1AFFFFFF, 10f)
        }
        val st = engineState ?: return
        Thread {
            val pairs = EnginePack.installed(this)
            var txt = if (pairs.isEmpty()) {
                "未安装模型包（英→中约 146MB，中→英约 146MB）"
            } else {
                "已安装：" + pairs.joinToString("、") { pairName(it.first) + " " + EnginePack.mb(it.second) } +
                    "，共 " + EnginePack.mb(pairs.sumOf { it.second })
            }
            if (on && pairs.isEmpty()) txt += "；当前选了高精度但没模型，实际仍走 ML Kit"
            val finalTxt = txt
            runOnUiThread { st.text = finalTxt }
        }.start()
    }

    private fun importEnginePack() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/octet-stream")
                )
            }
            startActivityForResult(i, REQ_ENGINE_IMPORT)
        } catch (t: Throwable) {
            toast("无法打开文件选择器")
        }
    }

    private fun doImportEnginePack(uri: android.net.Uri) {
        toast("正在导入模型包，请稍候…")
        Thread {
            val result = EnginePack.importZip(this) { contentResolver.openInputStream(uri) }
            runOnUiThread {
                toast(result.second)
                refreshEngineCard()
                refreshStorage()
            }
        }.start()
    }

    private fun exportEnginePack() {
        if (EnginePack.installed(this).isEmpty()) {
            toast("还没有可导出的模型包")
            return
        }
        try {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "live-translate-engine-opus-mt-int8.zip")
            }
            startActivityForResult(i, REQ_ENGINE_EXPORT)
        } catch (t: Throwable) {
            toast("无法打开保存界面")
        }
    }

    private fun doExportEnginePack(uri: android.net.Uri) {
        toast("正在打包模型…")
        Thread {
            val ok = EnginePack.exportZip({ contentResolver.openOutputStream(uri) }, this)
            runOnUiThread { toast(if (ok) "模型包已导出" else "导出失败") }
        }.start()
    }

    private fun removeEnginePack() {
        Thread {
            var n = 0
            for (k in listOf("en_zh", "zh_en")) {
                if (EnginePack.remove(this, k)) n++
            }
            val msg = if (n == 0) "没有可删除的模型" else "已删除模型包（$n 个方向）"
            runOnUiThread {
                toast(msg)
                refreshEngineCard()
                refreshStorage()
            }
        }.start()
    }

    /** 用高精度引擎翻一段，结果与耗时直接显示在卡片里。 */
    private fun runEngineTest() {
        val tgt = Prefs.tgtLang
        val src = if (tgt == Languages.TARGET_ZH) Languages.TARGET_EN else Languages.TARGET_ZH
        val key = EnginePack.pairKey(src, tgt)
        if (key == null || !EnginePack.isInstalled(this, key)) {
            toast("先导入 " + pairName(key ?: "en_zh") + " 的模型包")
            return
        }
        val input = onnxTestIn?.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            toast("先输入一句原文")
            return
        }
        onnxTestOut?.setTextColor(color(R.color.fg_dim))
        onnxTestOut?.text = "翻译中…"
        val t0 = System.currentTimeMillis()
        OnnxEngine.translate(src, tgt, input) { err, out ->
            runOnUiThread {
                val ms = System.currentTimeMillis() - t0
                onnxTestOut?.setTextColor(color(if (err != null) R.color.warn else R.color.ok))
                onnxTestOut?.text = if (err != null) err else "→ " + out + "（" + ms + "ms）"
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
            var truncated = false
            try {
                contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(BufferedInputStream(raw)).use { zis ->
                        var written = 0L
                        var e = zis.nextEntry
                        while (e != null) {
                            if (count >= MAX_ENTRY_COUNT || written >= MAX_TOTAL_BYTES) {
                                truncated = true
                                break
                            }
                            val target = resolveTarget(e.name)
                            if (target != null && !e.isDirectory) {
                                target.parentFile?.mkdirs()
                                // 边写边判：只在条目开始前看累计值挡不住单个超大条目，
                                // 一个几百兆的条目会被完整落盘之后才 break
                                var n = 0L
                                var over = false
                                FileOutputStream(target).use { out ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val r = zis.read(buf)
                                        if (r <= 0) break
                                        n += r
                                        if (n > MAX_ENTRY_BYTES || written + n > MAX_TOTAL_BYTES) {
                                            over = true
                                            break
                                        }
                                        out.write(buf, 0, r)
                                    }
                                }
                                if (over) {
                                    // 半个文件留着没用，纯占空间
                                    try {
                                        target.delete()
                                    } catch (ex: Throwable) {
                                        Log.w(TAG, "MainActivity@615: " + ex.message)
                                    }
                                    truncated = true
                                    break
                                }
                                written += n
                                count++
                            }
                            zis.closeEntry()
                            e = zis.nextEntry
                        }
                    }
                }
                // 写入完成后，为每个模型目录补写 ML Kit 登记
                var registered = 0
                for (dirName in ModelRegistry.localModelDirs(this)) {
                    // 结构不全的目录不登记：登记了 ML Kit 会认，但翻译时才失败，更难排查
                    if (!ModelRegistry.looksComplete(File(modelRoot(), dirName))) continue
                    val langs = ModelRegistry.langsOf(dirName)
                    if (langs != null) {
                        val pair = langs.first + "_" + langs.second
                        val dir = File(modelRoot(), dirName)
                        val hash = ModelRegistry.officialHash(this, pair, ModelRegistry.dirBytes(dir))
                        if (ModelRegistry.register(this, dirName, hash)) registered++
                    }
                }

                runOnUiThread {
                    storageSize?.postDelayed({ refreshStorage() }, 300L)
                    packState?.text = "校验中"
                    packState?.setTextColor(color(R.color.accent))
                    // auto 时没有具体源语言，用刚导入的目录推断一条实际路径来校验
                    val verifySrc = if (Prefs.srcLang == "auto") {
                        ModelRegistry.localModelDirs(this)
                            .mapNotNull { ModelRegistry.langsOf(it)?.first }
                            .firstOrNull { it != Prefs.tgtLang } ?: "en"
                    } else Prefs.srcLang
                    LanguagePack.verify(verifySrc, Prefs.tgtLang) { ok ->
                        runOnUiThread {
                            packState?.text = if (ok) "已就绪" else "导入未生效"
                            packState?.setTextColor(color(if (ok) R.color.ok else R.color.warn))
                            toast(
                                when {
                                    truncated -> "导入已中断：文件数或体积超出上限（已写入 $count 个）"
                                    ok -> "导入成功，共 $count 个文件"
                                    registered > 0 -> "已写入并登记 $count 个文件，若仍不可用请重启 App"
                                    else -> "写入了 $count 个文件，但缺少模型登记"
                                }
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
            // 只说人话：占地方的就是安装包和离线模型，其余没内容就不提
            runOnUiThread {
                val parts = ArrayList<String>(4)
                parts.add("安装包 " + mb(apk))
                parts.add("离线模型 " + mb(nob))
                if (files > 0) parts.add("应用数据 " + mb(files))
                if (cache > 0) parts.add("缓存 " + mb(cache))
                t.text = parts.joinToString(" · ")
            }
        }.start()
    }

    /** 存储占用只需一句总量，目录明细对用户没有意义，已移除。 */
    private fun dirSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) total += dirSize(f)
        return total
    }

    /** 小到不足 0.1MB 的东西也要看得出大小，不能一律显示 0.0MB。 */
    private fun mb(bytes: Long): String = when {
        bytes >= 1048576L -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0)
        bytes >= 1024L -> (bytes / 1024L).toString() + "KB"
        else -> bytes.toString() + "B"
    }

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
    /** 读回 Settings，确认写入真的生效（部分 ROM 会静默拒绝）。 */
    private fun readBackAccessibility(svc: String): Boolean {
        return try {
            val cur = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            cur.split(':').any { it.equals(svc, true) }
        } catch (e: Throwable) {
            false
        }
    }

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
                toast(
                    if (readBackAccessibility(svc)) "已关闭无障碍"
                    else "写入未生效，请在系统设置里手动关闭"
                )
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
                // 读回校验：部分 ROM 会拒绝或延后应用写入，乐观提示会让人以为已经开了
                toast(
                    if (readBackAccessibility(svc)) "已开启无障碍"
                    else "写入未生效，请在系统设置里手动开启"
                )
            }
        } catch (t: Throwable) {
            toast("写入失败：" + (t.message ?: "无权限"))
        }
        showBtnRef?.postDelayed({ refresh() }, 400L)
    }

    /** 反馈：GitHub Issue 与邮件两条路，正文自动带上版本、系统与机型。 */
    private fun feedbackCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(R.color.card, 16f)
        card.setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(12f)
        card.layoutParams = lp

        card.addView(text("反馈", 15f, R.color.fg, true))
        card.addView(
            text(
                "版本 " + versionLabel() + "。遇到问题请写清现象与复现步骤；版本、系统、机型会自动带上。",
                12f, R.color.fg_dim, false
            ).apply { setPadding(0, dp(6f), 0, dp(10f)) }
        )

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val issues = smallBtn("GitHub Issues", R.color.accent) { openIssues() }
        val issuesLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        issuesLp.rightMargin = dp(10f)
        row.addView(issues, issuesLp)
        row.addView(smallBtn("邮件反馈", R.color.fg_dim) { openMail() })
        card.addView(row)

        return card
    }

    /** 形如 4.9.3 (45)，反馈里要能一眼看出装的是哪个包。 */
    private fun versionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        (info.versionName ?: "?") + " (" + code + ")"
    } catch (e: Throwable) {
        "?"
    }

    /** 反馈正文的固定尾巴：这几项决定了能不能复现，尤其是 ROM 相关的毛病。 */
    private fun deviceInfo(): String {
        val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "?"
        return "版本：" + versionLabel() +
            "\n系统：Android " + android.os.Build.VERSION.RELEASE +
            " (API " + android.os.Build.VERSION.SDK_INT + ")" +
            "\n机型：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " / " + abi
    }

    private fun openIssues() {
        val body = "\n\n--- 以下信息请保留 ---\n" + deviceInfo()
        val url = "https://github.com/wAngdEfAo/live-translate/issues/new" +
            "?title=" + android.net.Uri.encode("反馈：") +
            "&body=" + android.net.Uri.encode(body)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Throwable) {
            toast("没有可用的浏览器，可直接访问 github.com/wAngdEfAo/live-translate/issues")
        }
    }

    private fun openMail() {
        val subject = "实时翻译 " + versionLabel() + " 反馈"
        val body = "\n\n--- 以下信息请保留 ---\n" + deviceInfo()
        val uri = android.net.Uri.parse(
            "mailto:qzwtml@outlook.com" +
                "?subject=" + android.net.Uri.encode(subject) +
                "&body=" + android.net.Uri.encode(body)
        )
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (e: Throwable) {
            toast("没有邮件客户端，可手动发到 qzwtml@outlook.com")
        }
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

        val src = Prefs.srcLang
        val tgt = Prefs.tgtLang
        if (src == "auto") {
            // 自动检测没有固定模型，要等检测出具体语言才知道下什么
            packState?.text = "自动检测"
            packState?.setTextColor(color(R.color.accent))
            return finishRefresh()
        }
        if (LanguagePack.isReadyCached(src, tgt)) {
            packState?.text = "已就绪"
            packState?.setTextColor(color(R.color.ok))
            return finishRefresh()
        }
        // 如实问 ML Kit：这条路径的模型齐了才算就绪
        LanguagePack.isReady(src, tgt) { downloaded ->
            runOnUiThread {
                when {
                    downloaded -> {
                        packState?.text = "已就绪"
                        packState?.setTextColor(color(R.color.ok))
                    }
                    LanguagePack.busy -> {
                        packState?.text = "下载中"
                        packState?.setTextColor(color(R.color.accent))
                    }
                    else -> {
                        packState?.text = "未下载"
                        packState?.setTextColor(color(R.color.warn))
                    }
                }
                finishRefresh()
            }
        }
        return
    }

    private fun finishRefresh() {
        refreshStorage()
        refreshLangRows()
        refreshEngineCard()
        hintOcrReauthIfNeeded()

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
            } catch (ex: Throwable) {
                Log.w(TAG, "MainActivity@917: " + ex.message)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OCR && resultCode == RESULT_OK && data != null) {
            OcrService.setPermission(data)
            Prefs.ocrEverGranted = true
            OcrService.start(this)
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQ_EXPORT -> doExport(data.data!!)
            REQ_IMPORT -> doImport(data.data!!)
            REQ_ENGINE_IMPORT -> doImportEnginePack(data.data!!)
            REQ_ENGINE_EXPORT -> doExportEnginePack(data.data!!)
        }
    }

    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
        } catch (ex: Throwable) {
            Log.w(TAG, "MainActivity@943: " + ex.message)
        }
    }

    private fun toggleOverlay() {
        if (OverlayService.isShowing()) {
            OverlayService.stop(this)
            // 关覆盖层的同时把 OCR 一起停掉，否则会一直挂着录屏权限
            AppCore.syncOcrService()
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

    private var ocrHintShown = false

    /** OCR 开关打开但授权已失效（服务被回收 / token 用掉）时，明确提示一次。 */
    private fun hintOcrReauthIfNeeded() {
        if (!Prefs.ocrFallback) return
        // 从未授权过的新用户不该看到"授权已失效"
        if (!Prefs.ocrEverGranted) return
        if (OcrService.hasPermission() || OcrService.isRunning()) return
        if (ocrHintShown) return
        ocrHintShown = true
        toast("OCR 授权已失效，把「OCR 兜底」关掉再打开即可重新授权")
    }

    private fun ensureOcrPermission() {
        // 没有悬浮窗就没有需要 OCR 的场合，别白挂一个前台服务 + 录屏指示器
        if (!OverlayService.isShowing()) {
            toast("先开启悬浮窗，OCR 才会跟着悬浮窗一起工作")
            return
        }
        if (OcrService.hasPermission() || OcrService.isRunning()) {
            OcrService.start(this)
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_OCR)
        } catch (ex: Throwable) {
            Log.w(TAG, "MainActivity@969: " + ex.message)
        }
    }

    private fun downloadPack() {
        if (LanguagePack.busy) return
        val src = Prefs.srcLang
        val tgt = Prefs.tgtLang
        if (src == "auto") {
            toast("「自动检测」不需要下载，请在列表里选一个具体语言")
            return
        }
        val need = LanguagePack.neededModels(src, tgt).size
        packState?.text = "下载中"
        packState?.setTextColor(color(R.color.accent))
        if (need > 1) toast("需要下载 $need 个模型（约 ${need * 35}MB，含英语中转）")
        LanguagePack.download(src, tgt) { ok, msg ->
            runOnUiThread {
                packState?.text = msg
                packState?.setTextColor(color(if (ok) R.color.ok else R.color.warn))
                refreshLangRows()
            }
        }
    }
}
