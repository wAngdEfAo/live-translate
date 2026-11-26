package com.shishifanyi.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView

/**
 * 译文块：先把手里的矩形反复涂满不透明底色，再画文字。
 * 多次涂色是为了防止任何一层半透明（drawable alpha / 窗口合成）
 * 让底下的英文透出来。
 */
// 项目没有引入 appcompat，用原生 TextView 是刻意的，换 AppCompatTextView 会平白多一个依赖
@SuppressLint("AppCompatCustomView")
class CoverView(context: Context) : TextView(context) {

    var coverColor: Int = 0xFF10131A.toInt()
        set(v) {
            field = v or (0xFF shl 24)
            invalidate()
        }

    /** 调试用描边，null 表示不画。 */
    var outlineColor: Int? = null

    private val paint = Paint().apply { isAntiAlias = false }
    private val strokePaint = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = coverColor
        // 涂三遍：任何 0.7 的透传都会变成 0.03
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.drawRect(0f, 0f, w, h, paint)

        val oc = outlineColor
        if (oc != null) {
            strokePaint.color = oc
            strokePaint.strokeWidth = 1.5f
            canvas.drawRect(0f, 0f, w, h, strokePaint)
        }
        super.draw(canvas)
    }
}
