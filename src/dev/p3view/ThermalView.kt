package dev.p3view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Draws the latest frame scaled to fit and rotated, a movable crosshair with its temperature,
 * and a few lines of text. Touching the image moves the crosshair.
 */
class ThermalView(context: Context) : View(context) {
    private val latest = AtomicReference<DisplayFrame?>()
    private var shown: DisplayFrame? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private val dst = RectF()
    private val dp = resources.displayMetrics.density
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16 * dp
        setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val big = Paint(text).apply { textSize = 22 * dp }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2 * dp
        setShadowLayer(2 * dp, 0f, 0f, Color.BLACK)
    }

    // Crosshair position as a fraction of the unrotated frame.
    private var crossX = 0.5f
    private var crossY = 0.5f

    @Volatile var status: String = ""
        set(v) { field = v; postInvalidate() }
    @Volatile var info: String = ""
        set(v) { field = v; postInvalidate() }

    /** Clockwise quarter turns, 0..3. */
    var quarterTurns = 0
        set(v) { field = ((v % 4) + 4) % 4; invalidate() }

    var palette: Palette = Palette.ALL[0]
        set(v) { field = v; shown = null; invalidate() }

    /** Space kept free at the top for the button row. */
    var topReserve = 0f

    fun submit(frame: DisplayFrame) {
        latest.set(frame)
        postInvalidateOnAnimation()
    }

    fun clearFrame() {
        latest.set(null)
        postInvalidate()
    }

    /** Scale that fits the rotated frame on screen. */
    private fun scaleFor(f: DisplayFrame): Float {
        val dw = if (quarterTurns % 2 == 1) f.height else f.width
        val dh = if (quarterTurns % 2 == 1) f.width else f.height
        return minOf(width / dw.toFloat(), height / dh.toFloat())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = latest.get()
        // With a status overlay ("Tap to retry") a tap is a click for the activity instead.
        if (f == null || status.isNotEmpty()) return super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            // Undo the rotation, then convert to a pixel and back to the pixel's centre.
            var u = e.x - width / 2f
            var v = e.y - height / 2f
            repeat(quarterTurns) { val t = u; u = v; v = -t }
            val s = scaleFor(f)
            val px = (0.5f + u / (f.width * s)).times(f.width).toInt().coerceIn(0, f.width - 1)
            val py = (0.5f + v / (f.height * s)).times(f.height).toInt().coerceIn(0, f.height - 1)
            crossX = (px + 0.5f) / f.width
            crossY = (py + 0.5f) / f.height
            invalidate()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val safe = rootWindowInsets?.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val left = (safe?.left ?: 0) + 16 * dp
        val right = width - (safe?.right ?: 0) - 16 * dp
        val top = (safe?.top ?: 0) + 16 * dp
        val bottom = height - (safe?.bottom ?: 0) - 16 * dp

        val f = latest.get()
        if (f != null) {
            val bmp = bitmap?.takeIf { it.width == f.width && it.height == f.height }
                ?: Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888).also {
                    bitmap = it
                    pixels = IntArray(f.width * f.height)
                    shown = null
                }
            if (f !== shown) {
                val lut = palette.lut
                for (i in pixels.indices) pixels[i] = lut[f.levels[i]]
                bmp.setPixels(pixels, 0, f.width, 0, 0, f.width, f.height)
                shown = f
            }
            val s = scaleFor(f)
            val w = f.width * s
            val h = f.height * s
            val mx = width / 2f
            val my = height / 2f
            c.save()
            c.translate(mx, my)
            c.rotate(90f * quarterTurns)
            dst.set(-w / 2, -h / 2, w / 2, h / 2)
            c.drawBitmap(bmp, null, dst, imagePaint)
            c.restore()

            // Crosshair: offset from the frame centre, rotated clockwise like the image.
            var u = (crossX - 0.5f) * w
            var v = (crossY - 0.5f) * h
            repeat(quarterTurns) { val t = u; u = -v; v = t }
            val cx = mx + u
            val cy = my + v
            val a = 6 * dp
            val b = 16 * dp
            c.drawLine(cx - b, cy, cx - a, cy, cross)
            c.drawLine(cx + a, cy, cx + b, cy, cross)
            c.drawLine(cx, cy - b, cx, cy - a, cross)
            c.drawLine(cx, cy + a, cx, cy + b, cross)
            val px = (crossX * f.width).toInt().coerceIn(0, f.width - 1)
            val py = (crossY * f.height).toInt().coerceIn(0, f.height - 1)
            val label = "%.1f °C".format(f.celsiusAt(px, py))
            val lw = big.measureText(label)
            val lx = if (cx + b + 4 * dp + lw <= width) cx + b + 4 * dp else cx - b - 4 * dp - lw
            val ly = if (cy - b - big.textSize >= 0) cy - b else cy + b + big.textSize
            c.drawText(label, lx, ly, big)

            c.drawText("min %.1f °C   max %.1f °C".format(f.minC, f.maxC), left, bottom, text)
            val fps = "%.1f fps".format(f.fps)
            c.drawText(fps, right - text.measureText(fps), bottom, text)
        }
        if (info.isNotEmpty()) c.drawText(info, left, top + topReserve + text.textSize, text)
        if (status.isNotEmpty()) {
            val lines = status.split('\n')
            var y = height / 2f - (lines.size - 1) * big.textSize * 0.7f
            for (line in lines) {
                c.drawText(line, (width - big.measureText(line)) / 2, y, big)
                y += big.textSize * 1.4f
            }
        }
    }
}
