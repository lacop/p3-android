// Rear phone camera shown behind the thermal image: lens discovery, a camera2 preview session
// and a TextureView that rotates and zooms the preview in software.

package dev.p3view

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

private const val TAG = "p3view"
private const val WARMUP_FRAMES = 10

/** One selectable rear camera: a physical camera, opened through its logical camera if it has one. */
class Lens(val key: String, val label: String, val logicalId: String, val physicalId: String?)

/** Rear lenses sorted from widest to longest, labelled by field of view relative to the main one. */
fun findLenses(mgr: CameraManager): List<Lens> {
    class Candidate(val logical: String, val physical: String?, val equiv: Float)
    val back = mgr.cameraIdList.filter {
        mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }
    val grouped = back.associateWith { mgr.getCameraCharacteristics(it).physicalCameraIds }
    val physicals = grouped.values.filter { it.size > 1 }.flatten().toSet()

    fun equiv(id: String): Float {
        val c = mgr.getCameraCharacteristics(id)
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        return f * 43.27f / hypot(size.width, size.height) // 35 mm equivalent focal length
    }

    // A phone with a multi-camera exposes its real lenses as the physical cameras behind it. Any
    // other back ids are alternate entry points (crop zooms and the like), not extra lenses.
    val multi = grouped.filterValues { it.size > 1 }
    val found = mutableListOf<Candidate>()
    if (multi.isNotEmpty()) {
        val seen = mutableSetOf<String>()
        for ((id, phys) in multi) {
            for (p in phys) if (seen.add(p)) found += Candidate(id, p, runCatching { equiv(p) }.getOrDefault(0f))
        }
    } else {
        for (id in back) found += Candidate(id, null, equiv(id))
    }
    for ((id, phys) in grouped) Log.i(TAG, "back camera $id physical=$phys equiv=${runCatching { equiv(id) }.getOrNull()}")
    for (c in found) Log.i(TAG, "lens ${c.logical}/${c.physical} equiv=${c.equiv}mm")
    found.sortBy { it.equiv }
    val main = found.filter { it.equiv > 0 }.minByOrNull { abs(ln(it.equiv / 25f)) }
    return found.map {
        val ratio = if (main != null && it.equiv > 0) it.equiv / main.equiv else 1f
        Lens("${it.logical}/${it.physical}", zoomLabel(ratio), it.logical, it.physical)
    }
}

/** Snaps to the usual zoom stops when close (reported sensor sizes are only roughly right). */
private fun zoomLabel(ratio: Float): String {
    val stop = listOf(0.5f, 1f, 2f, 3f, 5f, 10f).minByOrNull { abs(ln(ratio / it)) }!!
    return if (abs(ln(ratio / stop)) < 0.2f) fmt("%s×", if (stop < 1f) fmt("%.1f", stop) else fmt("%.0f", stop))
    else fmt("%.1f×", ratio)
}

/** Runs a preview session for one lens at a time. Safe to call start/stop repeatedly from the UI thread. */
class OverlayCamera(
    context: Context,
    private val onError: (String) -> Unit,
    /** The session could not be set up with the edge output; the caller should retry without it. */
    private val onEdgesUnsupported: () -> Unit,
) {
    private val mgr = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("p3-cam").apply { start() }
    private val handler = Handler(thread.looper)
    private val exec = Executor { handler.post(it) }
    private var gen = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var lens: Lens? = null
    private var texture: SurfaceTexture? = null
    private var edges: EdgeProcessor? = null
    private var request: CaptureRequest? = null
    private var frames = 0

    /** Freezes the preview on its last frame: the repeating request stops, the view keeps the image. */
    @Volatile private var paused = false
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            synchronized(this@OverlayCamera) {
                // A session that starts while paused runs a few frames first, so exposure has settled.
                if (++frames >= WARMUP_FRAMES && paused && s === session) stopRepeating(s)
            }
        }
    }

    /** Starts (or switches to) a preview; with [edges], also feeds camera frames to the edge detector. */
    @Synchronized fun start(lens: Lens, view: CameraPreview, edges: EdgeProcessor?) {
        val tex = view.surfaceTexture ?: return
        if (lens === this.lens && tex === texture && edges === this.edges) return
        stop()
        val my = ++gen
        this.lens = lens
        texture = tex
        this.edges = edges
        try {
            val chars = mgr.getCameraCharacteristics(lens.physicalId ?: lens.logicalId)
            val size = previewSize(chars)
            tex.setDefaultBufferSize(size.width, size.height)
            view.configure(size.width, size.height, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, longSideFov(chars))
            val surface = Surface(tex).also { this.surface = it }
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val edgeSurface = edges?.let { e ->
                val s = edgeSize(chars, size)
                if (s == null) { Log.w(TAG, "no matching size for edges"); null } else e.open(s, sensor)
            }
            mgr.openCamera(lens.logicalId, exec, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    synchronized(this@OverlayCamera) {
                        if (my != gen) { d.close(); return }
                        device = d
                    }
                    createSession(d, surface, edgeSurface, lens, my)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); fail(my, "Phone camera disconnected") }
                override fun onError(d: CameraDevice, error: Int) { d.close(); fail(my, "Phone camera error $error") }
            })
        } catch (e: Exception) {
            fail(my, "Cannot open phone camera: ${e.message}")
        }
    }

    @Synchronized fun setPaused(p: Boolean) {
        paused = p
        val s = session ?: return
        if (p) {
            if (frames >= WARMUP_FRAMES) stopRepeating(s)
        } else {
            try {
                s.setRepeatingRequest(request ?: return, captureCallback, handler)
            } catch (e: Exception) {
                Log.w(TAG, "cannot resume phone camera", e)
            }
        }
    }

    private fun stopRepeating(s: CameraCaptureSession) {
        try { s.stopRepeating() } catch (e: Exception) { Log.w(TAG, "cannot pause phone camera", e) }
    }

    @Synchronized fun stop() {
        gen++
        request = null
        frames = 0
        session?.close()
        device?.close()
        edges?.close()
        edges = null
        surface?.release()
        session = null
        device = null
        surface = null
        lens = null
        texture = null
    }

    private fun createSession(d: CameraDevice, surface: Surface, edgeSurface: Surface?, lens: Lens, my: Int) {
        try {
            val outputs = listOfNotNull(surface, edgeSurface).map { s ->
                OutputConfiguration(s).also { o -> lens.physicalId?.let { o.setPhysicalCameraId(it) } }
            }
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        synchronized(this@OverlayCamera) {
                            if (my != gen) { s.close(); return }
                            session = s
                        }
                        try {
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                edgeSurface?.let { addTarget(it) }
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                            synchronized(this@OverlayCamera) {
                                request = req
                                frames = 0
                            }
                            s.setRepeatingRequest(req, captureCallback, null)
                        } catch (e: Exception) {
                            fail(my, "Phone camera preview failed: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (edgeSurface != null) {
                            Log.w(TAG, "cannot configure phone camera with edge output")
                            if (synchronized(this@OverlayCamera) { my == gen }) onEdgesUnsupported()
                        } else {
                            fail(my, "Cannot configure phone camera")
                        }
                    }
                })
            d.createCaptureSession(config)
        } catch (e: Exception) {
            fail(my, "Cannot start phone camera: ${e.message}")
        }
    }

    private fun fail(my: Int, msg: String) {
        Log.w(TAG, msg)
        synchronized(this) { if (my != gen) return }
        onError(msg)
    }

    /** Field of view along the sensor's long side in degrees, or 0 if the characteristics lack it. */
    private fun longSideFov(chars: CameraCharacteristics): Float {
        val f = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        return Math.toDegrees(2 * atan(max(size.width, size.height) / (2 * f)).toDouble()).toFloat()
    }

    /** A YUV size for edge detection: the same shape as the preview so both cover the same view, up to 800 px wide. */
    private fun edgeSize(chars: CameraCharacteristics, preview: Size): Size? =
        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width <= 800 && it.width * preview.height == it.height * preview.width }
            .maxByOrNull { it.width * it.height }

    /** Largest 4:3-ish size up to 1920x1440, which keeps the full sensor field of view. */
    private fun previewSize(chars: CameraCharacteristics): Size {
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(SurfaceTexture::class.java)
        return sizes.filter { it.width <= 1920 && it.height <= 1440 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }!!
    }
}

/**
 * TextureView that shows the camera preview upright. With the thermal image's size and field of
 * view known, zoom 1 scales the preview so both show the same angle per pixel; [zoom] and the
 * pan offsets then fine-tune the alignment in software.
 */
class CameraPreview(context: Context) : TextureView(context), DisplayManager.DisplayListener {
    private var bufW = 0
    private var bufH = 0
    private var sensorOrientation = 90
    private var camFov = 0f
    private var irLong = 0f
    private var irFov = 0f
    private val matrix = Matrix()

    var zoom = 1f
        set(v) { field = v; applyTransform() }

    /** Offsets as a fraction of the thermal image's long side, in screen directions. */
    var panX = 0f
        set(v) { field = v; applyTransform() }
    var panY = 0f
        set(v) { field = v; applyTransform() }

    fun configure(w: Int, h: Int, sensorOrientation: Int, longSideFovDeg: Float) {
        bufW = w
        bufH = h
        this.sensorOrientation = sensorOrientation
        camFov = longSideFovDeg
        applyTransform()
    }

    fun matchTo(longSidePx: Float, hfovDeg: Float) {
        irLong = longSidePx
        irFov = hfovDeg
        applyTransform()
    }

    /** Called after the preview's placement changed, so other layers drawn with it can follow. */
    var onTransform: (() -> Unit)? = null

    fun applyTransform() {
        if (!contentMatrix(matrix, width.toFloat(), height.toFloat())) return
        setTransform(matrix)
        onTransform?.invoke()
    }

    /**
     * Matrix that places an image of the camera's shape on screen the way the preview is placed.
     * The image is [srcW] x [srcH] in whatever resolution, upright for a natural-portrait display;
     * the preview view itself shows the camera stretched over the view, so it passes its own size.
     * Returns false while there is nothing to place yet.
     */
    fun contentMatrix(out: Matrix, srcW: Float, srcH: Float): Boolean {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (bufW == 0 || vw == 0f) return false
        // The system already rotates the buffer to the device's natural orientation and stretches
        // it over the view. Here the content is therefore (cw x ch), upright for a natural-portrait
        // display; map the source to content pixels, counter-rotate for the current display
        // rotation, scale, recentre.
        val turned = sensorOrientation % 180 == 90
        val cw = if (turned) bufH else bufW
        val ch = if (turned) bufW else bufH
        val rot = (360 - (display?.rotation ?: 0) * 90) % 360
        val sideways = rot % 180 != 0
        val dw = if (sideways) ch else cw
        val dh = if (sideways) cw else ch
        val base = if (camFov > 0 && irFov > 0 && irLong > 0) {
            // Same pixels per tan(angle) as the thermal image, along the long side.
            irLong * tan(Math.toRadians(camFov / 2.0)).toFloat() / tan(Math.toRadians(irFov / 2.0)).toFloat() / max(cw, ch)
        } else {
            max(vw / dw, vh / dh) // unknown fields of view: centre-crop to fill
        }
        val k = base * zoom
        out.setScale(cw / srcW, ch / srcH)
        out.postTranslate(-cw / 2f, -ch / 2f)
        out.postRotate(rot.toFloat())
        out.postScale(k, k)
        out.postTranslate(vw / 2 + panX * irLong, vh / 2 + panY * irLong)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(this, null)
    }

    override fun onDetachedFromWindow() {
        context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        super.onDetachedFromWindow()
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) = applyTransform()
}
