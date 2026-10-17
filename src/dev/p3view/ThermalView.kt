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
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val scaleRect = RectF()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scaleBitmap: Bitmap? = null
    private var shownPalette: Palette? = null
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

    /** Clockwise quarter turns, 0..3. */
    var quarterTurns = 0
        set(v) { field = ((v % 4) + 4) % 4; invalidate() }

    var palette: Palette = Palette.ALL[0]
        set(v) { field = v; shown = null; invalidate() }

    /** With the phone camera behind, the background is transparent and the image is blended. */
    var overlay = false
        set(v) { field = v; invalidate() }

    /** Tags on the hottest and coldest pixel. */
    var showMarkers = false
        set(v) { field = v; invalidate() }

    /** Colour scale bar with its end temperatures. */
    var showScale = false
        set(v) { field = v; invalidate() }

    var opacity = 0.5f
        set(v) { field = v; invalidate() }

    /** While set, new frames are dropped and the last one stays on screen. */
    @Volatile var paused = false

    fun submit(frame: DisplayFrame) {
        if (paused) return
        latest.set(frame)
        postInvalidateOnAnimation()
    }

    /** The frame currently on screen, if any. */
    fun currentFrame(): DisplayFrame? = latest.get()

    /** Crosshair position as a pixel of the unrotated frame. */
    fun crosshairPixel(f: DisplayFrame): Pair<Int, Int> =
        Pair((crossX * f.width).toInt().coerceIn(0, f.width - 1), (crossY * f.height).toInt().coerceIn(0, f.height - 1))

    /** Where the thermal image is drawn, in view coordinates. */
    fun displayedRect(f: DisplayFrame): RectF {
        val s = scaleFor(f.width, f.height)
        val odd = quarterTurns % 2 == 1
        val w = (if (odd) f.height else f.width) * s
        val h = (if (odd) f.width else f.height) * s
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    fun clearFrame() {
        latest.set(null)
        postInvalidate()
    }

    /**
     * Told (on the UI thread) when the drawn image's long side in pixels or its lens field of
     * view changes, so the camera overlay can match its scale to the thermal image.
     */
    var imageListener: ((longSidePx: Float, hfovDeg: Float) -> Unit)? = null
    private var reportedLong = 0f
    private var reportedFov = 0f

    /** Scale that fits the rotated frame on screen. */
    private fun scaleFor(fw: Int, fh: Int): Float {
        val dw = if (quarterTurns % 2 == 1) fh else fw
        val dh = if (quarterTurns % 2 == 1) fw else fh
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
            val s = scaleFor(f.width, f.height)
            val px = (0.5f + u / (f.width * s)).times(f.width).toInt().coerceIn(0, f.width - 1)
            val py = (0.5f + v / (f.height * s)).times(f.height).toInt().coerceIn(0, f.height - 1)
            crossX = (px + 0.5f) / f.width
            crossY = (py + 0.5f) / f.height
            invalidate()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (!overlay) c.drawColor(Color.BLACK)
        imagePaint.alpha = if (overlay) (opacity * 255).toInt() else 255
        // Before the first frame assume the P3's shape so the overlay still has a size to match.
        val frameNow = latest.get()
        val fw = frameNow?.width ?: 4
        val fh = frameNow?.height ?: 3
        val long = maxOf(fw, fh) * scaleFor(fw, fh)
        val fov = frameNow?.hfovDeg ?: Model.ALL[0].hfov
        if (long != reportedLong || fov != reportedFov) {
            reportedLong = long
            reportedFov = fov
            post { imageListener?.invoke(long, fov) }
        }
        val safe = rootWindowInsets?.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val left = (safe?.left ?: 0) + 16 * dp
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
            val s = scaleFor(f.width, f.height)
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
            val (px, py) = crosshairPixel(f)
            val label = "%.1f °C".format(f.celsiusAt(px, py))
            val lw = big.measureText(label)
            val lx = if (cx + b + 4 * dp + lw <= width) cx + b + 4 * dp else cx - b - 4 * dp - lw
            val ly = if (cy - b - big.textSize >= 0) cy - b else cy + b + big.textSize
            c.drawText(label, lx, ly, big)

            if (showMarkers) {
                for ((index, hot) in listOf(f.maxIndex to true, f.minIndex to false)) {
                    // Same mapping as the crosshair: pixel centre as an offset from the frame centre, rotated.
                    var mu = ((index % f.width + 0.5f) / f.width - 0.5f) * w
                    var mv = ((index / f.width + 0.5f) / f.height - 0.5f) * h
                    repeat(quarterTurns) { val t = mu; mu = -mv; mv = t }
                    val x = mx + mu
                    val y = my + mv
                    marker.color = if (hot) 0xFFFF3B30.toInt() else 0xFF2979FF.toInt()
                    c.drawCircle(x, y, 5 * dp, marker)
                    c.drawCircle(x, y, 5 * dp, markerRing)
                    val t = "%.1f".format(if (hot) f.maxC else f.minC)
                    val tw = text.measureText(t)
                    c.drawText(t, if (x + 9 * dp + tw <= width) x + 9 * dp else x - 9 * dp - tw, y + text.textSize / 3, text)
                }
            }

            if (showScale) {
                // Colour scale just below the thermal image, centred, with the end
                // temperatures beside it. When the image leaves no room, it sits above the min/max line.
                val lo = "%.1f °C".format(f.loC)
                val hi = "%.1f °C".format(f.hiC)
                val img = displayedRect(f)
                val pad = maxOf(text.measureText(lo), text.measureText(hi)) + 8 * dp
                val x0 = img.left + 10 * dp + pad
                val x1 = img.right - 10 * dp - pad
                val barBottom = minOf(img.bottom + 6 * dp + 12 * dp, bottom - 22 * dp)
                val barTop = barBottom - 12 * dp
                val base = barBottom - (12 * dp - text.textSize * 0.7f) / 2
                if (x1 > x0 + 20 * dp) {
                    val sb = scaleBitmap?.takeIf { shownPalette === palette } ?: Bitmap.createBitmap(palette.lut, 256, 1, Bitmap.Config.ARGB_8888)
                        .also { scaleBitmap = it; shownPalette = palette }
                    scaleRect.set(x0, barTop, x1, barBottom)
                    c.drawBitmap(sb, null, scaleRect, scalePaint)
                    c.drawRect(scaleRect, markerRing)
                    c.drawText(lo, x0 - 8 * dp - text.measureText(lo), base, text)
                    c.drawText(hi, x1 + 8 * dp, base, text)
                }
            }

            c.drawText("min %.1f °C   max %.1f °C   %.1f fps".format(f.minC, f.maxC, f.fps), left, bottom, text)
        }
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
