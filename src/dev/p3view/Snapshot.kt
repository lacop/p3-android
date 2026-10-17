// Snapshots land in the shared Pictures/p3view folder through MediaStore, so the gallery, the
// Files app and share sheets see them. The UI thread only queues a job holding references to immutable frame data (and a
// copy of the camera view); a worker thread renders and writes the files.

package dev.p3view

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "p3view"

/** Annotated image: the thermal image is enlarged by this factor so text and crosshair stay legible. */
private const val ANNOTATED_SCALE = 4
private const val BAR_HEIGHT = 48

/** Folder below external storage root where snapshots are saved. */
const val SNAPSHOT_DIR = "Pictures/p3view"

class SnapshotJob(
    /** File name stem, `YYYYMMDD-HHMMSS-SSS`, from the moment of the button press. */
    val stem: String,
    /** Wall clock time of the press, used as the picture's date taken. */
    val takenMs: Long,
    val frame: DisplayFrame,
    val lut: IntArray,
    val quarterTurns: Int,
    /** Crosshair pixel in the unrotated frame. */
    val crossX: Int,
    val crossY: Int,
    /** Copy of the camera view as shown (null when the camera overlay is off). */
    val visible: Bitmap?,
    /** Where the thermal image sits on [visible]. */
    val thermalRect: RectF?,
)

class SnapshotWriter(private val resolver: ContentResolver, private val onChange: () -> Unit, private val onError: (String) -> Unit) {
    private val queue = LinkedBlockingQueue<SnapshotJob>()
    private val pending = AtomicInteger()

    /** Jobs queued or being written. */
    val count: Int get() = pending.get()

    init {
        Thread({
            while (true) {
                val job = queue.take()
                try {
                    write(job)
                } catch (e: Exception) {
                    Log.w(TAG, "snapshot ${job.stem} failed", e)
                    onError("Snapshot failed: ${e.message}")
                } finally {
                    job.visible?.recycle()
                    pending.decrementAndGet()
                    onChange()
                }
            }
        }, "p3-snapshot").apply { isDaemon = true; start() }
    }

    fun submit(job: SnapshotJob) {
        pending.incrementAndGet()
        queue.put(job)
        onChange()
    }

    private fun write(job: SnapshotJob) {
        val f = job.frame
        var pixels = IntArray(f.levels.size) { job.lut[f.levels[it]] }
        var w = f.width
        var h = f.height
        var cx = job.crossX
        var cy = job.crossY
        repeat(job.quarterTurns) {
            // Quarter turn clockwise: (x, y) -> (h - 1 - y, x), width and height swap.
            val turned = IntArray(pixels.size)
            for (y in 0 until h) for (x in 0 until w) turned[x * h + (h - 1 - y)] = pixels[y * w + x]
            pixels = turned
            val nx = h - 1 - cy
            cy = cx
            cx = nx
            val t = w; w = h; h = t
        }
        val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        save(job, "${job.stem}-raw.png", raw)

        val annotated = annotate(raw, job, cx, cy)
        save(job, "${job.stem}-annotated.png", annotated)
        raw.recycle()
        annotated.recycle()

        val visible = job.visible
        val rect = job.thermalRect
        if (visible != null && rect != null) {
            val paint = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = maxOf(2f, visible.width / 250f)
            }
            Canvas(visible).drawRect(rect, paint)
            save(job, "${job.stem}-visible.jpg", visible)
        }
    }

    private fun save(job: SnapshotJob, name: String, bmp: Bitmap) {
        val jpeg = name.endsWith(".jpg")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, SNAPSHOT_DIR)
            put(MediaStore.Images.Media.DATE_TAKEN, job.takenMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "cannot create $name" }
        try {
            val out = checkNotNull(resolver.openOutputStream(uri)) { "cannot write $name" }
            out.use { check(bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it)) { "cannot encode $name" } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Enlarged image with crosshair and temperature, and a bar below showing the colour scale. */
    private fun annotate(raw: Bitmap, job: SnapshotJob, px: Int, py: Int): Bitmap {
        val k = ANNOTATED_SCALE
        val w = raw.width * k
        val h = raw.height * k
        val out = Bitmap.createBitmap(w, h + BAR_HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(Bitmap.createScaledBitmap(raw, w, h, true), 0f, 0f, null)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 3f
            setShadowLayer(2f, 0f, 0f, Color.BLACK)
        }
        val x = (px + 0.5f) * k
        val y = (py + 0.5f) * k
        val a = 8f
        val b = 22f
        c.drawLine(x - b, y, x - a, y, line)
        c.drawLine(x + a, y, x + b, y, line)
        c.drawLine(x, y - b, x, y - a, line)
        c.drawLine(x, y + a, x, y + b, line)
        val f = job.frame
        val label = "%.1f °C".format(f.celsiusAt(job.crossX, job.crossY))
        val lw = text.measureText(label)
        val lx = if (x + b + 6 + lw <= w) x + b + 6 else x - b - 6 - lw
        val ly = if (y - b - text.textSize >= 0) y - b else y + b + text.textSize
        c.drawText(label, lx, ly, text)

        // Bar: low end temperature, the palette from cold to hot, high end temperature. The ends are
        // what levels 0 and 255 stand for, so a pixel's temperature can be read off its colour.
        val lo = "%.1f °C".format(f.loC)
        val hi = "%.1f °C".format(f.hiC)
        val stripLeft = 12f + text.measureText(lo) + 12f
        val stripRight = w - 12f - text.measureText(hi) - 12f
        val mid = h + BAR_HEIGHT / 2f
        val cell = Paint()
        val step = (stripRight - stripLeft) / 256
        for (i in 0 until 256) {
            cell.color = job.lut[i]
            c.drawRect(stripLeft + i * step, mid - 9, stripLeft + (i + 1) * step + 0.5f, mid + 9, cell)
        }
        val base = mid - (text.ascent() + text.descent()) / 2
        c.drawText(lo, 12f, base, text)
        c.drawText(hi, w - 12f - text.measureText(hi), base, text)
        return out
    }
}
