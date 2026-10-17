package dev.p3view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.WindowInsets
import java.util.concurrent.atomic.AtomicReference

/** Draws the latest frame scaled to fit, a centre crosshair and a few lines of text. */
class ThermalView(context: Context) : View(context) {
    private val latest = AtomicReference<DisplayFrame?>()
    private var shown: DisplayFrame? = null
    private var bitmap: Bitmap? = null
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

    @Volatile var status: String = ""
        set(v) { field = v; postInvalidate() }
    @Volatile var info: String = ""
        set(v) { field = v; postInvalidate() }

    fun submit(frame: DisplayFrame) {
        latest.set(frame)
        postInvalidateOnAnimation()
    }

    fun clearFrame() {
        latest.set(null)
        postInvalidate()
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
                ?: Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            if (f !== shown) {
                bmp.setPixels(f.pixels, 0, f.width, 0, 0, f.width, f.height)
                shown = f
            }
            val s = minOf(width / f.width.toFloat(), height / f.height.toFloat())
            val w = f.width * s
            val h = f.height * s
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawBitmap(bmp, null, dst, imagePaint)

            val cx = dst.centerX()
            val cy = dst.centerY()
            val a = 6 * dp
            val b = 16 * dp
            c.drawLine(cx - b, cy, cx - a, cy, cross)
            c.drawLine(cx + a, cy, cx + b, cy, cross)
            c.drawLine(cx, cy - b, cx, cy - a, cross)
            c.drawLine(cx, cy + a, cx, cy + b, cross)
            c.drawText("%.1f °C".format(f.centreC), cx + b + 4 * dp, cy - b, big)

            c.drawText("min %.1f °C   max %.1f °C".format(f.minC, f.maxC), left, bottom, text)
            val fps = "%.1f fps".format(f.fps)
            c.drawText(fps, right - text.measureText(fps), bottom, text)
        }
        if (info.isNotEmpty()) c.drawText(info, left, top + text.textSize, text)
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
