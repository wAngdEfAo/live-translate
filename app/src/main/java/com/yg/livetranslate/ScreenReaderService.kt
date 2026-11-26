package com.yg.livetranslate

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 通过无障碍服务读取当前屏幕的文字与位置。
 * 事件密集，统一做 300ms 防抖：屏幕静止后才抓一次。
 */
class ScreenReaderService : AccessibilityService() {

    companion object {
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
        if (buf.isEmpty()) {
            AppCore.onBlocks(emptyList())
            return
        }
        val copy = ArrayList<TextBlock>(buf)
        AppCore.onBlocks(copy)
    }

    private fun collect(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 40) return
        nodeBudget++
        if (nodeBudget > 800) return

        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            addBlock(node, t)
        } else {
            val c = node.contentDescription?.toString()?.trim()
            if (!c.isNullOrEmpty()) addBlock(node, c)
        }

        val n = node.childCount
        for (i in 0 until n) {
            val child = try {
                node.getChild(i)
            } catch (t2: Throwable) {
                null
            } ?: continue
            collect(child, depth + 1)
        }
    }

    private fun addBlock(node: AccessibilityNodeInfo, text: String) {
        node.getBoundsInScreen(tmpRect)
        if (tmpRect.width() <= 0 || tmpRect.height() <= 0) return
        buf.add(TextBlock(text, Rect(tmpRect)))
    }
}
