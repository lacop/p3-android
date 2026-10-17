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
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Round translucent button with a canvas-drawn icon, so the app needs no icon resources. */
class IconButton(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { SHUTTER, ROTATE, PALETTE, OVERLAY, TUNE, INFO, SNAPSHOT, GALLERY, DISPLAY }

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
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val box = RectF()
    private var colors = IntArray(0)

    /** Highlighted toggle state. */
    var active = false
        set(v) { field = v; invalidate() }

    init {
        isClickable = true
        contentDescription = when (kind) {
            Kind.SHUTTER -> "Shutter"
            Kind.ROTATE -> "Rotate"
            Kind.PALETTE -> "Colour scheme"
            Kind.OVERLAY -> "Phone camera overlay"
            Kind.TUNE -> "Overlay settings"
            Kind.INFO -> "Camera info"
            Kind.SNAPSHOT -> "Save snapshot"
            Kind.GALLERY -> "Open snapshot folder"
            Kind.DISPLAY -> "Readout options"
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
        bg.color = when {
            isPressed -> 0xB0606060.toInt()
            active -> 0xCC2979FF.toInt()
            else -> 0x80000000.toInt()
        }
        c.drawCircle(cx, cy, width / 2f, bg)
        when (kind) {
            Kind.SHUTTER -> {
                // Aperture iris: a ring, a small hexagon and each hexagon edge extended to the ring.
                val outer = 10 * dp
                val inner = 4 * dp
                c.drawCircle(cx, cy, outer, line)
                line.strokeWidth = 1.5f * dp
                val hex = Path()
                for (j in 0 until 6) {
                    val a0 = Math.toRadians(60.0 * j)
                    val a1 = Math.toRadians(60.0 * (j + 1))
                    val ax = inner * cos(a0).toFloat()
                    val ay = inner * sin(a0).toFloat()
                    val bx = inner * cos(a1).toFloat()
                    val by = inner * sin(a1).toFloat()
                    if (j == 0) hex.moveTo(cx + ax, cy + ay)
                    hex.lineTo(cx + bx, cy + by)
                    // Extend a -> b past b until it meets the ring.
                    val len = hypot(bx - ax, by - ay)
                    val dx = (bx - ax) / len
                    val dy = (by - ay) / len
                    val ad = bx * dx + by * dy
                    val t = -ad + sqrt(ad * ad - (bx * bx + by * by) + outer * outer)
                    c.drawLine(cx + bx, cy + by, cx + bx + t * dx, cy + by + t * dy, line)
                }
                c.drawPath(hex, line)
                line.strokeWidth = 2 * dp
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
            Kind.OVERLAY -> {
                // Two stacked squares: the thermal layer over the camera layer.
                val s = 10 * dp
                val o = 3.5f * dp
                box.set(cx - s / 2 - o, cy - s / 2 - o, cx + s / 2 - o, cy + s / 2 - o)
                c.drawRoundRect(box, 2 * dp, 2 * dp, line.apply { strokeWidth = 1.75f * dp })
                box.offset(2 * o, 2 * o)
                c.drawRoundRect(box, 2 * dp, 2 * dp, fill)
                line.strokeWidth = 2 * dp
            }
            Kind.GALLERY -> {
                // Picture frame with a sun and two hills.
                val hw = 11 * dp
                val hh = 8.5f * dp
                box.set(cx - hw, cy - hh, cx + hw, cy + hh)
                c.drawRoundRect(box, 3 * dp, 3 * dp, line.apply { strokeWidth = 1.75f * dp })
                line.strokeWidth = 2 * dp
                c.drawCircle(cx + 4.5f * dp, cy - 2.5f * dp, 1.8f * dp, fill)
                val hills = Path().apply {
                    moveTo(cx - hw + 1.5f * dp, cy + hh - 1.5f * dp)
                    lineTo(cx - 3.5f * dp, cy - 0.5f * dp)
                    lineTo(cx + 0.5f * dp, cy + 4 * dp)
                    lineTo(cx + 3 * dp, cy + 1.5f * dp)
                    lineTo(cx + hw - 1.5f * dp, cy + hh - 1.5f * dp)
                    close()
                }
                c.drawPath(hills, fill)
            }
            Kind.DISPLAY -> {
                // Thermometer: outlined stem, filled bulb and mercury.
                val stem = 2.75f * dp
                box.set(cx - stem, cy - 10 * dp, cx + stem, cy + 3 * dp)
                c.drawRoundRect(box, stem, stem, line.apply { strokeWidth = 1.75f * dp })
                line.strokeWidth = 2 * dp
                c.drawCircle(cx, cy + 6 * dp, 4.5f * dp, fill)
                c.drawRect(cx - 0.9f * dp, cy - 4 * dp, cx + 0.9f * dp, cy + 6 * dp, fill)
            }
            Kind.INFO -> {
                glyph.textSize = 24 * dp
                c.drawText("?", cx, cy - (glyph.ascent() + glyph.descent()) / 2, glyph)
            }
            Kind.SNAPSHOT -> {
                val hw = 11 * dp
                val hh = 7.5f * dp
                box.set(cx - hw, cy - hh + 1.5f * dp, cx + hw, cy + hh + 1.5f * dp)
                c.drawRoundRect(box, 3 * dp, 3 * dp, line.apply { strokeWidth = 1.75f * dp })
                line.strokeWidth = 2 * dp
                c.drawCircle(cx, cy + 1.5f * dp, 3.5f * dp, line)
                c.drawRect(cx - 4 * dp, cy - hh - 1 * dp, cx + 4 * dp, cy - hh + 2 * dp, fill)
            }
            Kind.TUNE -> {
                // Three sliders with knobs at different positions.
                val half = 10 * dp
                for ((i, k) in listOf(-0.4f, 0.5f, -0.1f).withIndex()) {
                    val y = cy + (i - 1) * 7 * dp
                    c.drawLine(cx - half, y, cx + half, y, line)
                    c.drawCircle(cx + k * half, y, 3 * dp, fill)
                }
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
