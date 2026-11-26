package com.shishifanyi.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 通过无障碍服务读取当前屏幕的文字与位置。
 * 事件密集，统一做 300ms 防抖：屏幕静止后才抓一次。
 */
class ScreenReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenReader"

        /** 单次抓取的节点上限；超过就只取前这么多，避免主线程被超长列表拖死。
         *  开了 flagIncludeNotImportantViews 后节点会变多，这里相应放宽。 */
        private const val MAX_NODES = 1200

        @Volatile
        var instance: ScreenReaderService? = null
            private set

        fun requestCapture() {
            instance?.captureSoon(0L)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val captureTask = Runnable { captureNow() }
    private var nodeBudget = 0

    private val buf = ArrayList<TextBlock>(64)
    private val tmpRect = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        AppCore.init(this)
        instance = this
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(captureTask)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!Prefs.translating) return
        if (e.packageName == packageName) return
        if (!screenOn()) return
        val delay = when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> 300L
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> 600L
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> 400L
            else -> return
        }
        captureSoon(delay)
    }

    private fun screenOn(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (t: Throwable) {
            true
        }
    }

    private fun captureSoon(delay: Long) {
        handler.removeCallbacks(captureTask)
        if (delay <= 0L) handler.post(captureTask) else handler.postDelayed(captureTask, delay)
    }

    private fun captureNow() {
        if (!Prefs.translating) return
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return

        nodeBudget = 0
        buf.clear()
        collect(root, 0)
        // 触顶时明确记一笔：否则长列表页下半屏一直不翻，却查不出原因
        if (nodeBudget > MAX_NODES) {
            Log.w(TAG, "节点数超过 $MAX_NODES，本次只处理前 $MAX_NODES 个，靠后的内容可能漏译")
        }
        if (buf.isEmpty()) {
            AppCore.onBlocks(emptyList())
            return
        }
        val copy = ArrayList<TextBlock>(buf)
        AppCore.onBlocks(copy)
    }

    private fun collect(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return
        try {
            if (depth > 40) return
            nodeBudget++
            if (nodeBudget > MAX_NODES) return

            // 密码 / 验证码输入框：整棵子树都不读，内容不该被翻译，更不该进翻译引擎
            if (node.isPassword) return

            // 只取可见文字；contentDescription 是读屏描述（图标名、按钮说明），
            // 屏幕上并不显示，翻它会凭空盖住图标
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) addBlock(node, t)

            val n = node.childCount
            for (i in 0 until n) {
                val child = try {
                    node.getChild(i)
                } catch (t2: Throwable) {
                    null
                } ?: continue
                collect(child, depth + 1)
            }
        } finally {
            // API 33 起节点由系统自动回收；更早的版本必须手动回收，否则每抓一屏都留一批引用
            if (android.os.Build.VERSION.SDK_INT < 33) {
                try {
                    node.recycle()
                } catch (t: Throwable) {
                    // 已经回收过或已被系统处理，忽略
                }
            }
        }
    }

    private fun addBlock(node: AccessibilityNodeInfo, text: String) {
        node.getBoundsInScreen(tmpRect)
        if (tmpRect.width() <= 0 || tmpRect.height() <= 0) return
        buf.add(TextBlock(text, Rect(tmpRect)))
    }
}
