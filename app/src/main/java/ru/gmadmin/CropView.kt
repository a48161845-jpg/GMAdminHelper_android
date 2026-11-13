package ru.gmadmin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Рамка области чтения поверх игры. Тянешь за угол или край — меняешь размер, берёшь за середину — двигаешь,
 * касаешься вне рамки и ведёшь пальцем — рисуешь новую. Всё за пределами рамки затемнено.
 */
class CropView(c: Context, private val accent: Int) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val r = RectF()
    var onChange: (() -> Unit)? = null

    private val dim = Paint().apply { color = 0xAA05070B.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0xFFFFFF) or 0x1E000000 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val knobEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE60F131B.toInt() }
    private val tagTx = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD }

    private val minS = 48f * d
    private var mode = 0           // 0 — ничего, 1 — двигаем, 2 — тянем края, 3 — рисуем новую
    private var flags = 0          // 1 — левый, 2 — верхний, 4 — правый, 8 — нижний край
    private var ax = 0f; private var ay = 0f
    private var ox = 0f; private var oy = 0f; private var sl = 0f; private var st = 0f

    fun setRect(l: Float, t: Float, rr: Float, b: Float) { r.set(l, t, rr, b); invalidate() }
    fun rect(): RectF = RectF(r)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        cv.drawRect(0f, 0f, w, r.top, dim)
        cv.drawRect(0f, r.bottom, w, h, dim)
        cv.drawRect(0f, r.top, r.left, r.bottom, dim)
        cv.drawRect(r.right, r.top, w, r.bottom, dim)
        cv.drawRect(r, fill)
        val p = Path()
        for (k in 1..2) {
            p.moveTo(r.left + r.width() * k / 3f, r.top); p.lineTo(r.left + r.width() * k / 3f, r.bottom)
            p.moveTo(r.left, r.top + r.height() * k / 3f); p.lineTo(r.right, r.top + r.height() * k / 3f)
        }
        cv.drawPath(p, grid)
        cv.drawRoundRect(r, 3f * d, 3f * d, line)
        val cx = r.centerX(); val cy = r.centerY()
        val kr = 9f * d
        for (pt in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            cv.drawCircle(pt.first, pt.second, kr, knob); cv.drawCircle(pt.first, pt.second, kr, knobEdge)
        }
        val bw = 22f * d; val bh = 6f * d
        for (pt in listOf(cx to r.top, cx to r.bottom)) cv.drawRoundRect(pt.first - bw / 2, pt.second - bh / 2, pt.first + bw / 2, pt.second + bh / 2, bh, bh, knob)
        for (pt in listOf(r.left to cy, r.right to cy)) cv.drawRoundRect(pt.first - bh / 2, pt.second - bw / 2, pt.first + bh / 2, pt.second + bw / 2, bh, bh, knob)
        val tag = (100f * r.width() / max(1f, w)).toInt().toString() + "% × " + (100f * r.height() / max(1f, h)).toInt() + "%"
        val tw = tagTx.measureText(tag)
        val tx = (r.left + 8 * d).coerceAtMost(w - tw - 20 * d)
        val ty = if (r.top > 34 * d) r.top - 10 * d else r.top + 30 * d
        cv.drawRoundRect(tx - 7 * d, ty - 15 * d, tx + tw + 7 * d, ty + 6 * d, 9 * d, 9 * d, tagBg)
        cv.drawText(tag, tx, ty, tagTx)
    }

    private fun edges(x: Float, y: Float): Int {
        val slop = min(30f * d, max(16f * d, min(r.width(), r.height()) / 3f))
        val inY = y > r.top - slop && y < r.bottom + slop
        val inX = x > r.left - slop && x < r.right + slop
        var f = 0
        if (inY && abs(x - r.left) < slop) f = f or 1
        if (inX && abs(y - r.top) < slop) f = f or 2
        if (inY && abs(x - r.right) < slop) f = f or 4
        if (inX && abs(y - r.bottom) < slop) f = f or 8
        if (f and 1 != 0 && f and 4 != 0) f = if (abs(x - r.left) < abs(x - r.right)) f and 4.inv() else f and 1.inv()
        if (f and 2 != 0 && f and 8 != 0) f = if (abs(y - r.top) < abs(y - r.bottom)) f and 8.inv() else f and 2.inv()
        return f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        val x = e.x.coerceIn(0f, w); val y = e.y.coerceIn(0f, h)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                flags = edges(x, y)
                if (flags != 0) mode = 2
                else if (r.contains(x, y)) { mode = 1; ox = x; oy = y; sl = r.left; st = r.top }
                else { mode = 3; ax = x; ay = y; r.set(x, y, x, y) }
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    1 -> {
                        val nl = (sl + x - ox).coerceIn(0f, w - r.width()); val nt = (st + y - oy).coerceIn(0f, h - r.height())
                        r.offsetTo(nl, nt)
                    }
                    2 -> {
                        if (flags and 1 != 0) r.left = min(x, r.right - minS)
                        if (flags and 4 != 0) r.right = max(x, r.left + minS)
                        if (flags and 2 != 0) r.top = min(y, r.bottom - minS)
                        if (flags and 8 != 0) r.bottom = max(y, r.top + minS)
                        r.left = r.left.coerceAtLeast(0f); r.top = r.top.coerceAtLeast(0f)
                        r.right = r.right.coerceAtMost(w); r.bottom = r.bottom.coerceAtMost(h)
                    }
                    3 -> r.set(min(ax, x), min(ay, y), max(ax, x), max(ay, y))
                }
                invalidate(); onChange?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 3) {            // слишком маленькая новая рамка — растягиваем до минимума
                    if (r.width() < minS) { val nr = min(w, r.left + minS); r.right = nr; r.left = max(0f, nr - minS) }
                    if (r.height() < minS) { val nb = min(h, r.top + minS); r.bottom = nb; r.top = max(0f, nb - minS) }
                }
                mode = 0; invalidate(); onChange?.invoke()
            }
        }
        return true
    }
}
