package dev.p3view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
    private val markerPath = Path()
    private val edgeMatrix = Matrix()
    private val edgePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Edge outlines of the camera view, drawn over the thermal image; both null when off. */
    var edgeBitmap: (() -> Bitmap?)? = null
    var edgePlacement: ((Matrix, Float, Float) -> Boolean)? = null
    // Frame minimum and maximum next to the colour scale, tinted like their markers.
    private val minText = Paint(text).apply { color = 0xFF82B1FF.toInt() }
    private val maxText = Paint(text).apply { color = 0xFFFF8A80.toInt() }
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

    /** Histogram of pixel temperatures under the colour scale bar. */
    var showHistogram = false
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

    private var notice = ""

    /** Short message over the top of the image that disappears by itself. */
    fun showNotice(text: String, ms: Long) {
        notice = text
        invalidate()
        postDelayed({ if (notice == text) { notice = ""; invalidate() } }, ms)
    }

    private var histFrame: DisplayFrame? = null
    private var histHeights = FloatArray(0)
    private val histPaint = Paint()

    /**
     * Histogram of the pixel temperatures: 80% of the view width, one column per screen pixel. The
     * range from the frame minimum to the maximum is split into that many equal temperature steps,
     * and each column grows up from the bottom of the box in proportion to the number of pixels in
     * its step (the tallest column fills the box). Independent of the colour range.
     */
    private fun drawHistogram(c: Canvas, f: DisplayFrame, top: Float, height: Float) {
        val n = (0.8f * width).toInt()
        if (n < 2) return
        if (f !== histFrame || n != histHeights.size) computeHistogram(f, n)
        val x0 = (width - n) / 2
        histPaint.color = Color.BLACK
        c.drawRect(x0.toFloat(), top, (x0 + n).toFloat(), top + height, histPaint)
        histPaint.color = Color.WHITE
        for (i in 0 until n) {
            val h = height * histHeights[i]
            if (h >= 0.5f) c.drawRect(x0 + i.toFloat(), top + height - h, x0 + i + 1f, top + height, histPaint)
        }
    }

    private fun computeHistogram(f: DisplayFrame, n: Int) {
        val minRaw = f.thermal[f.minIndex]
        val maxRaw = f.thermal[f.maxIndex]
        // Pixels per raw value (1/64 K). A raw value covers the span [r, r + 1), so when there are
        // more columns than raw values a value fills several columns with its count shared out
        // instead of leaving gaps between single-value columns.
        val size = maxRaw - minRaw + 1
        val counts = IntArray(size)
        for (r in f.thermal) counts[r - minRaw]++
        val cum = IntArray(size + 1)
        for (k in 0 until size) cum[k + 1] = cum[k] + counts[k]
        fun upTo(x: Float): Float {
            val k = Math.floor(x.toDouble()).toInt().coerceIn(0, size - 1)
            return cum[k] + counts[k] * (x.coerceAtMost(size.toFloat()) - k)
        }
        val step = size.toFloat() / n
        val columns = FloatArray(n) { i -> upTo((i + 1) * step) - upTo(i * step) }
        val tallest = maxOf(columns.max(), 1e-3f)
        histFrame = f
        histHeights = FloatArray(n) { columns[it] / tallest }
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
        // Only touches on the thermal image move the crosshair; a drag that leaves it keeps the last spot.
        if ((e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) &&
            displayedRect(f).contains(e.x, e.y)
        ) {
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

            val edges = if (overlay) edgeBitmap?.invoke() else null
            if (edges != null && edgePlacement?.invoke(edgeMatrix, edges.width.toFloat(), edges.height.toFloat()) == true) {
                // Outlines only on the thermal image, placed exactly like the camera view behind it.
                c.save()
                c.clipRect(displayedRect(f))
                c.drawBitmap(edges, edgeMatrix, edgePaint)
                c.restore()
            }

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
            val label = fmt("%.1f °C", f.celsiusAt(px, py))
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
                    // Red triangle pointing up for the hottest pixel, blue pointing down for the coldest;
                    // the pixel is at the triangle's centre, not its tip.
                    val r = 6.5f * dp
                    val tip = if (hot) -r else r
                    val baseY = if (hot) r / 2 else -r / 2
                    markerPath.rewind()
                    markerPath.moveTo(x, y + tip)
                    markerPath.lineTo(x - r * 0.866f, y + baseY)
                    markerPath.lineTo(x + r * 0.866f, y + baseY)
                    markerPath.close()
                    marker.color = if (hot) 0xFFFF3B30.toInt() else 0xFF2979FF.toInt()
                    c.drawPath(markerPath, marker)
                    c.drawPath(markerPath, markerRing)
                    val t = fmt("%.1f", if (hot) f.maxC else f.minC)
                    val tw = text.measureText(t)
                    c.drawText(t, if (x + 9 * dp + tw <= width) x + 9 * dp else x - 9 * dp - tw, y + text.textSize / 3, text)
                }
            }

            if (showScale || showHistogram) {
                // Just below the thermal image, centred:  min | scale low [colour bar] scale high | max
                // The scale ends are the 1st and 99th percentile the colours cover; min and max are the
                // real frame extremes. The histogram sits under the bar. When the image leaves no
                // room, the whole stack moves up to sit above the fps line.
                val minT = fmt("%.1f", f.minC)
                val maxT = fmt("%.1f", f.maxC)
                val lo = fmt("%.1f", f.loC)
                val hi = fmt("%.1f", f.hiC)
                val img = displayedRect(f)
                val slot = listOf(minT, maxT, lo, hi).maxOf { text.measureText(it) } + 6 * dp
                val edge = 10 * dp
                val x0 = img.left + edge + 2 * slot
                val x1 = img.right - edge - 2 * slot
                val histH = if (showHistogram) 72 * dp else 0f
                val histGap = if (showHistogram) 16 * dp else 0f
                val barBottom = minOf(img.bottom + 6 * dp + 12 * dp + histGap + histH, bottom - 22 * dp) - histGap - histH
                val barTop = barBottom - 12 * dp
                val base = barBottom - (12 * dp - text.textSize * 0.7f) / 2
                if (showScale && x1 > x0 + 30 * dp) {
                    val sb = scaleBitmap?.takeIf { shownPalette === palette } ?: Bitmap.createBitmap(palette.lut, 256, 1, Bitmap.Config.ARGB_8888)
                        .also { scaleBitmap = it; shownPalette = palette }
                    scaleRect.set(x0, barTop, x1, barBottom)
                    c.drawBitmap(sb, null, scaleRect, scalePaint)
                    c.drawRect(scaleRect, markerRing)
                    c.drawText(minT, img.left + edge, base, minText)
                    c.drawText(lo, x0 - 6 * dp - text.measureText(lo), base, text)
                    c.drawText(hi, x1 + 6 * dp, base, text)
                    c.drawText(maxT, img.right - edge - maxText.measureText(maxT), base, maxText)
                }
                if (showHistogram) drawHistogram(c, f, barBottom + histGap, histH)
            }

            c.drawText(fmt("%.1f fps", f.fps), left, bottom, text)

            if (notice.isNotEmpty()) {
                val img = displayedRect(f)
                c.drawText(notice, (width - big.measureText(notice)) / 2, img.top + 32 * dp, big)
            }
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
