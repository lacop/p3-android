// Edge detection on the phone camera's brightness plane. The result is a translucent white
// outline bitmap, already turned upright, that the thermal view draws over the thermal image.

package dev.p3view

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlin.math.abs

class EdgeProcessor(private val onUpdate: () -> Unit) {
    /** 0..1: how faint an edge still shows. */
    @Volatile var sensitivity = 0.5f

    /** Latest outline image, upright in the phone's natural orientation; null while off. */
    @Volatile var bitmap: Bitmap? = null
        private set

    private val thread = HandlerThread("p3-edges").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var next = 0
    private var gray = ByteArray(0)
    private var out = IntArray(0)

    /** Creates the reader for camera frames of this size; the camera session writes into the returned surface. */
    @Synchronized fun open(size: Size, sensorOrientation: Int): Surface {
        close()
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ process(it, sensorOrientation) }, handler)
        reader = r
        return r.surface
    }

    @Synchronized fun close() {
        reader?.close()
        reader = null
        bitmap = null
        buffers.fill(null)
        onUpdate()
    }

    @Synchronized private fun process(rd: ImageReader, sensorOrientation: Int) {
        if (rd !== reader) return
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            if (gray.size != w * h) gray = ByteArray(w * h)
            for (y in 0 until h) {
                buf.position(y * plane.rowStride)
                buf.get(gray, y * w, w)
            }

            // Sobel gradient size |gx| + |gy|, written turned by the sensor orientation so the
            // outline is upright like the preview. Ramp from the threshold to full opacity.
            val turned = sensorOrientation == 90 || sensorOrientation == 270
            val ow = if (turned) h else w
            val oh = if (turned) w else h
            if (out.size != ow * oh) out = IntArray(ow * oh) else out.fill(0)
            val threshold = 40f + 360f * (1f - sensitivity)
            val gain = 255f / threshold
            fun g(i: Int) = gray[i].toInt() and 0xff
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val gx = g(i - w + 1) + 2 * g(i + 1) + g(i + w + 1) - g(i - w - 1) - 2 * g(i - 1) - g(i + w - 1)
                    val gy = g(i + w - 1) + 2 * g(i + w) + g(i + w + 1) - g(i - w - 1) - 2 * g(i - w) - g(i - w + 1)
                    val alpha = ((abs(gx) + abs(gy) - threshold) * gain).toInt().coerceIn(0, 255)
                    if (alpha == 0) continue
                    val idx = when (sensorOrientation) {
                        90 -> x * ow + (h - 1 - y)
                        180 -> (h - 1 - y) * ow + (w - 1 - x)
                        270 -> (w - 1 - x) * ow + y
                        else -> y * ow + x
                    }
                    out[idx] = (alpha shl 24) or 0xFFFFFF
                }
            }
            val bmp = buffers[next]?.takeIf { it.width == ow && it.height == oh }
                ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { buffers[next] = it }
            bmp.setPixels(out, 0, ow, 0, 0, ow, oh)
            bitmap = bmp
            next = 1 - next
        } finally {
            img.close()
        }
        onUpdate()
    }
}
