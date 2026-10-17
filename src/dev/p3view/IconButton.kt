package dev.p3view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Round translucent button with a canvas-drawn icon, so the app needs no icon resources. */
class IconButton(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { SHUTTER, ROTATE, PALETTE }

    private val dp = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val swatch = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var colors = IntArray(0)

    init {
        isClickable = true
        contentDescription = when (kind) {
            Kind.SHUTTER -> "Shutter"
            Kind.ROTATE -> "Rotate"
            Kind.PALETTE -> "Colour scheme"
        }
    }

    /** The palette button shows the current palette as a gradient. */
    fun showPalette(p: Palette) {
        colors = IntArray(9) { p.lut[it * 255 / 8] }
        swatch.shader = null
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = (44 * dp).toInt()
        setMeasuredDimension(size, size)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        bg.color = if (isPressed) 0xB0606060.toInt() else 0x80000000.toInt()
        c.drawCircle(cx, cy, width / 2f, bg)
        when (kind) {
            Kind.SHUTTER -> {
                c.drawCircle(cx, cy, 10 * dp, line)
                c.drawCircle(cx, cy, 5 * dp, fill)
            }
            Kind.ROTATE -> {
                val r = 8 * dp
                box.set(cx - r, cy - r, cx + r, cy + r)
                val start = -60f
                val sweep = 290f
                c.drawArc(box, start, sweep, false, line)
                // Arrowhead at the clockwise end of the arc.
                val end = Math.toRadians((start + sweep).toDouble())
                val px = cx + r * cos(end).toFloat()
                val py = cy + r * sin(end).toFloat()
                val rx = cos(end).toFloat()
                val ry = sin(end).toFloat()
                val head = Path().apply {
                    moveTo(px - ry * 5 * dp, py + rx * 5 * dp)
                    lineTo(px + rx * 4.5f * dp, py + ry * 4.5f * dp)
                    lineTo(px - rx * 4.5f * dp, py - ry * 4.5f * dp)
                    close()
                }
                c.drawPath(head, fill)
            }
            Kind.PALETTE -> {
                val hw = 11 * dp
                val hh = 7 * dp
                box.set(cx - hw, cy - hh, cx + hw, cy + hh)
                if (colors.isNotEmpty()) {
                    if (swatch.shader == null) swatch.shader = LinearGradient(box.left, 0f, box.right, 0f, colors, null, Shader.TileMode.CLAMP)
                    c.drawRoundRect(box, 3 * dp, 3 * dp, swatch)
                }
                c.drawRoundRect(box, 3 * dp, 3 * dp, line.apply { strokeWidth = 1.5f * dp })
                line.strokeWidth = 2 * dp
            }
        }
    }
}
