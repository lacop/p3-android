package dev.p3view

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.widget.PopupMenu

private const val ACTION_USB_PERMISSION = "dev.p3view.USB_PERMISSION"
private const val PREF_ROTATION = "rotation"
private const val PREF_PALETTE = "palette"
private const val PREF_OVERLAY = "overlay"
private const val PREF_LENS = "lens"
private const val PREF_OPACITY = "opacity"
private const val PREF_MARKERS = "markers"
private const val PREF_SCALE = "scale"
private const val PREF_EDGES = "edges"
private const val PREF_EDGE_SENS = "edge_sensitivity"
private const val PREF_ZOOM = "fovzoom_" // + lens key; zoom 1 = same field of view as the thermal image
private const val PREF_PAN_X = "panx_"
private const val PREF_PAN_Y = "pany_"
private const val ZOOM_MIN = 0.5f
private const val ZOOM_MAX = 2f
private const val LIVE_COLOR = 0xE61565C0.toInt()   // pause button while live
private const val PAUSED_COLOR = 0xE6EF6C00.toInt() // and while paused
private const val PAN_MAX = 0.3f

class MainActivity : Activity(), SessionListener {
    private lateinit var usb: UsbManager
    private lateinit var view: ThermalView
    private lateinit var prefs: SharedPreferences
    private lateinit var preview: CameraPreview
    private lateinit var overlayCamera: OverlayCamera
    private lateinit var overlayButton: IconButton
    private lateinit var tuneButton: IconButton
    private lateinit var buttons: LinearLayout
    private var lenses = emptyList<Lens>()
    private var lens: Lens? = null
    private var overlayOn = false
    private var started = false
    private lateinit var snapshots: SnapshotWriter
    private lateinit var snapshotCount: TextView
    private lateinit var pauseButton: IconButton
    private var paused = false
    private lateinit var edgeProcessor: EdgeProcessor
    private var edgesOn = false
    // Model, serial and firmware of the connected camera; written from the USB thread.
    @Volatile private var deviceInfo = ""
    private var refreshPanel: (() -> Unit)? = null
    // Raw (lo, hi) the colour scale is locked to; not saved, as a range means little next launch.
    private var lockedRange: FloatArray? = null
    private var session: CameraSession? = null
    private var sessionDevice: String? = null
    private var permissionPending = false
    // Automatic permission prompts happen once; after a denial only a tap asks again.
    private var cameraAsked = false
    private var usbAsked = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    permissionPending = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else view.status = "USB permission denied\nTap to ask again"
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Android shows its own "open with" prompt for the attached camera; accepting
                    // it grants access and arrives in onNewIntent. Do not stack a second prompt.
                    usbAsked = true
                    connect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    usbAsked = false
                    val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (dev != null && dev.deviceName == sessionDevice) {
                        stopSession()
                        view.clearFrame()
                        deviceInfo = ""
                        view.status = "Camera unplugged"
                        setPaused(false)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(UsbManager::class.java)
        prefs = getSharedPreferences("p3view", MODE_PRIVATE)
        view = ThermalView(this).apply {
            quarterTurns = prefs.getInt(PREF_ROTATION, 0)
            palette = Palette.byName(prefs.getString(PREF_PALETTE, null))
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Touches on the image move the crosshair; with no frame (or an error overlay) they retry.
        view.setOnClickListener { connect(fromUser = true) }

        val dp = resources.displayMetrics.density
        val paletteButton = IconButton(this, IconButton.Kind.PALETTE).apply {
            showPalette(view.palette)
            setOnClickListener { showPaletteMenu(this) }
        }
        val rotateButton = IconButton(this, IconButton.Kind.ROTATE).apply {
            setOnClickListener {
                view.quarterTurns += 1
                prefs.edit().putInt(PREF_ROTATION, view.quarterTurns).apply()
            }
        }
        val displayButton = IconButton(this, IconButton.Kind.DISPLAY).apply {
            setOnClickListener { showReadoutPanel(this) }
        }
        val shutterButton = IconButton(this, IconButton.Kind.SHUTTER).apply {
            setOnClickListener {
                val s = session
                val running = s != null && s.isAlive
                if (running) {
                    s!!.shutter()
                    view.showNotice("Calibrating…", 1200)
                }
                // White flash when sent, red when there is no camera to calibrate.
                flashColor = if (running) Color.WHITE else 0xFFFF1744.toInt()
                flash()
            }
        }

        lenses = runCatching { findLenses(getSystemService(CameraManager::class.java)) }.getOrDefault(emptyList())
        lens = lenses.firstOrNull { it.key == prefs.getString(PREF_LENS, null) }
            ?: lenses.firstOrNull { it.label == "1×" } ?: lenses.firstOrNull()
        overlayOn = prefs.getBoolean(PREF_OVERLAY, false) && lens != null
        edgesOn = prefs.getBoolean(PREF_EDGES, false)
        edgeProcessor = EdgeProcessor { view.postInvalidateOnAnimation() }.apply {
            sensitivity = prefs.getFloat(PREF_EDGE_SENS, 0.5f)
        }
        view.edgeBitmap = { edgeProcessor.bitmap }
        view.edgePlacement = { m, w, h -> preview.contentMatrix(m, w, h) }
        overlayCamera = OverlayCamera(
            this,
            onError = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } },
            onEdgesUnsupported = {
                runOnUiThread {
                    edgesOn = false
                    prefs.edit().putBoolean(PREF_EDGES, false).apply()
                    Toast.makeText(this, "The phone cannot run the edge overlay with this camera", Toast.LENGTH_LONG).show()
                    updateOverlay()
                }
            },
        )
        preview = CameraPreview(this).apply {
            onTransform = { view.invalidate() }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = updateOverlay()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    overlayCamera.stop()
                    return true
                }
            }
        }
        lens?.let(::applyLensState)
        view.imageListener = { longPx, fov -> preview.matchTo(longPx, fov) }
        view.opacity = prefs.getFloat(PREF_OPACITY, 0.5f)
        view.showMarkers = prefs.getBoolean(PREF_MARKERS, false)
        view.showScale = prefs.getBoolean(PREF_SCALE, true)
        overlayButton = IconButton(this, IconButton.Kind.OVERLAY).apply {
            setOnClickListener {
                if (lens == null) {
                    Toast.makeText(this@MainActivity, "No rear camera found", Toast.LENGTH_SHORT).show()
                } else {
                    overlayOn = !overlayOn
                    prefs.edit().putBoolean(PREF_OVERLAY, overlayOn).apply()
                    if (overlayOn && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                    }
                    applyOverlayState()
                }
            }
        }
        tuneButton = IconButton(this, IconButton.Kind.TUNE).apply {
            setOnClickListener { showOverlayPanel() }
        }
        val infoButton = IconButton(this, IconButton.Kind.INFO).apply {
            setOnClickListener { showInfo(this) }
        }
        snapshots = SnapshotWriter(
            contentResolver,
            onChange = { runOnUiThread { showSnapshotCount() } },
            onError = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } },
        )
        snapshotCount = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
            textSize = 20f
            visibility = View.GONE
        }
        val snapshotButton = IconButton(this, IconButton.Kind.SNAPSHOT, 72f).apply {
            color = 0xE62E7D32.toInt()
            pressedColor = 0xFF66BB6A.toInt()
            setOnClickListener {
                // White flash when queued, red when there was nothing to save.
                flashColor = if (takeSnapshot()) Color.WHITE else 0xFFFF1744.toInt()
                flash()
            }
        }
        pauseButton = IconButton(this, IconButton.Kind.PAUSE, 72f).apply {
            color = LIVE_COLOR
            pressedColor = 0xFF64B5F6.toInt()
            setOnClickListener {
                setPaused(!paused)
                flash()
            }
        }

        fun corner(vararg children: View, top: Boolean, vertical: Boolean = false) = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.END else Gravity.CENTER_VERTICAL
            for (b in children) {
                addView(b, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (vertical) topMargin = (8 * dp).toInt() else marginStart = (8 * dp).toInt()
                })
            }
            setOnApplyWindowInsetsListener { v, insets ->
                val safe = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val m = (8 * dp).toInt()
                v.setPadding(safe.left + m, if (top) safe.top + m else 0, safe.right + m, if (top) 0 else safe.bottom + m)
                insets
            }
        }
        buttons = corner(tuneButton, overlayButton, paletteButton, rotateButton, displayButton, shutterButton, top = true)
        val galleryButton = IconButton(this, IconButton.Kind.GALLERY).apply {
            setOnClickListener { openSnapshotFolder() }
        }
        val infoCorner = corner(infoButton, galleryButton, top = true)
        // The pause button sits above the snapshot button; the queue length is left of the latter.
        val snapshotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(snapshotCount)
            addView(snapshotButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = (8 * dp).toInt()
            })
        }
        val snapshotCorner = corner(pauseButton, snapshotRow, top = false, vertical = true)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(buttons, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            addView(infoCorner, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(snapshotCorner, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        })
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        applyOverlayState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        connect()
    }

    override fun onResume() {
        super.onResume()
        started = true
        updateOverlay()
        connect()
    }

    override fun onStop() {
        setPaused(false) // the preview surface goes away, so a frozen camera view could not be kept
        started = false
        updateOverlay()
        super.onStop()
        stopSession()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        overlayCamera.stop()
        stopSession()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            connect()
            updateOverlay()
        }
    }

    private fun connect(fromUser: Boolean = false) {
        if (session?.isAlive == true) return
        // Android grants USB access to video-class devices only to apps holding CAMERA.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraAsked || fromUser) {
                cameraAsked = true
                view.status = "Camera permission needed"
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            } else {
                view.status = "Camera permission denied\nAndroid requires it for USB cameras\nTap to ask again, or allow\nCamera in the app settings"
            }
            return
        }
        val device = usb.deviceList.values.firstOrNull {
            it.vendorId == P3.VENDOR_ID && Model.fromPid(it.productId) != null
        }
        if (device == null) {
            view.status = "Plug in the camera"
            return
        }
        if (!usb.hasPermission(device)) {
            if (permissionPending) return
            if (usbAsked && !fromUser) {
                view.status = "Camera detected\nTap to connect"
                return
            }
            view.status = "Waiting for USB permission"
            usbAsked = true
            permissionPending = true
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(device, pi)
            return
        }
        val conn = usb.openDevice(device)
        if (conn == null) {
            view.status = "Cannot open the camera\nTap to retry"
            return
        }
        view.status = "Connecting"
        sessionDevice = device.deviceName
        session = CameraSession(conn, device, Model.fromPid(device.productId)!!, this).also {
            it.lockRange(lockedRange)
            it.start()
        }
    }

    private fun zoomFor(l: Lens) = prefs.getFloat(PREF_ZOOM + l.key, 1f).coerceIn(ZOOM_MIN, ZOOM_MAX)

    /** Shows or hides everything that depends on the overlay toggle. */
    private fun applyOverlayState() {
        overlayButton.active = overlayOn
        tuneButton.visibility = if (overlayOn) View.VISIBLE else View.GONE
        preview.visibility = if (overlayOn) View.VISIBLE else View.GONE
        view.overlay = overlayOn
        updateOverlay()
    }

    private fun updateOverlay() {
        val l = lens
        if (overlayOn && started && l != null && preview.isAvailable &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) overlayCamera.start(l, preview, if (edgesOn) edgeProcessor else null) else overlayCamera.stop()
    }

    /** Loads the saved zoom and pan of a lens into the preview. */
    private fun applyLensState(l: Lens) {
        preview.zoom = prefs.getFloat(PREF_ZOOM + l.key, 1f).coerceIn(ZOOM_MIN, ZOOM_MAX)
        preview.panX = prefs.getFloat(PREF_PAN_X + l.key, 0f).coerceIn(-PAN_MAX, PAN_MAX)
        preview.panY = prefs.getFloat(PREF_PAN_Y + l.key, 0f).coerceIn(-PAN_MAX, PAN_MAX)
    }

    private fun saveLensState() {
        val l = lens ?: return
        prefs.edit().putFloat(PREF_ZOOM + l.key, preview.zoom)
            .putFloat(PREF_PAN_X + l.key, preview.panX).putFloat(PREF_PAN_Y + l.key, preview.panY).apply()
    }

    /** Lens, zoom, pan and opacity controls; every change applies live so alignment is easy to tweak. */
    private fun showOverlayPanel() {
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xE6202020.toInt())
        }
        fun label() = TextView(this).apply { setTextColor(Color.WHITE) }

        /** Adds a labelled slider; returns a function that sets its value without firing [live]. */
        fun slider(name: String, min: Float, max: Float, format: (Float) -> String, value: Float,
                   live: (Float) -> Unit, done: () -> Unit): (Float) -> Unit {
            val text = label()
            val bar = SeekBar(this).apply { this.max = Math.round((max - min) * 1000) }
            fun show(v: Float) {
                text.text = "$name  ${format(v)}"
                bar.progress = Math.round((v - min) * 1000)
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + progress / 1000f
                    text.text = "$name  ${format(v)}"
                    live(v)
                }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) = done()
            })
            col.addView(text)
            col.addView(bar)
            show(value)
            return ::show
        }

        if (lenses.size > 1) {
            col.addView(label().apply { text = "Camera" })
            val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            lenses.forEachIndexed { i, l ->
                group.addView(RadioButton(this).apply { id = i; text = l.label; isChecked = l === lens })
            }
            col.addView(group)
            group.setOnCheckedChangeListener { _, id ->
                val l = lenses[id]
                lens = l
                prefs.edit().putString(PREF_LENS, l.key).apply()
                applyLensState(l)
                refresh()
                updateOverlay()
            }
        }

        val pct = { v: Float -> fmt("%+.1f%%", v * 100) }
        val setZoom = slider("Zoom", ZOOM_MIN, ZOOM_MAX, { fmt("%.3f×", it) }, preview.zoom,
            { preview.zoom = it }, ::saveLensState)
        val setPanX = slider("Shift right", -PAN_MAX, PAN_MAX, pct, preview.panX, { preview.panX = it }, ::saveLensState)
        val setPanY = slider("Shift down", -PAN_MAX, PAN_MAX, pct, preview.panY, { preview.panY = it }, ::saveLensState)
        slider("Thermal opacity", 0f, 1f, { "${Math.round(it * 100)}%" }, view.opacity,
            { view.opacity = it }, { prefs.edit().putFloat(PREF_OPACITY, view.opacity).apply() })
        col.addView(CheckBox(this).apply {
            text = "Edge overlay"
            setTextColor(Color.WHITE)
            isChecked = edgesOn
            setOnCheckedChangeListener { _, on ->
                edgesOn = on
                prefs.edit().putBoolean(PREF_EDGES, on).apply()
                updateOverlay() // the edge output is part of the camera session, so it restarts
            }
        })
        slider("Edge sensitivity", 0f, 1f, { "${Math.round(it * 100)}%" }, edgeProcessor.sensitivity,
            { edgeProcessor.sensitivity = it }, { prefs.edit().putFloat(PREF_EDGE_SENS, edgeProcessor.sensitivity).apply() })
        refreshPanel = {
            setZoom(preview.zoom)
            setPanX(preview.panX)
            setPanY(preview.panY)
        }
        col.addView(Button(this).apply {
            text = "Reset zoom and shift"
            setOnClickListener {
                preview.zoom = 1f
                preview.panX = 0f
                preview.panY = 0f
                saveLensState()
                refresh()
            }
        })

        val width = minOf((320 * dp).toInt(), resources.displayMetrics.widthPixels - 2 * pad)
        PopupWindow(col, width, LayoutParams.WRAP_CONTENT, true).apply {
            setOnDismissListener { refreshPanel = null }
        }.showAtLocation(
            buttons, Gravity.TOP or Gravity.END, buttons.paddingRight, buttons.bottom + (4 * dp).toInt(),
        )
    }

    private fun refresh() { refreshPanel?.invoke() }

    /** Freezes or resumes both the thermal image and the phone camera preview. */
    private fun setPaused(p: Boolean) {
        paused = p
        view.paused = p
        overlayCamera.setPaused(p)
        pauseButton.showPlay = p
        pauseButton.color = if (p) PAUSED_COLOR else LIVE_COLOR
    }

    private fun showSnapshotCount() {
        val n = snapshots.count
        snapshotCount.text = n.toString()
        snapshotCount.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    /** Queues a snapshot. Only references and one bitmap copy are taken here; the worker does the rest. */
    private fun takeSnapshot(): Boolean {
        val f = view.currentFrame()
        if (f == null) {
            Toast.makeText(this, "No thermal image yet", Toast.LENGTH_SHORT).show()
            return false
        }
        val stem = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val (cx, cy) = view.crosshairPixel(f)
        val visible = if (overlayOn && preview.isAvailable) preview.bitmap else null
        snapshots.submit(
            SnapshotJob(stem, System.currentTimeMillis(), f, view.palette.lut, view.quarterTurns, cx, cy, visible, visible?.let { view.displayedRect(f) }, view.showMarkers)
        )
        return true
    }

    /** Opens the snapshot folder in the system file browser. */
    private fun openSnapshotFolder() {
        val dir = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$SNAPSHOT_DIR")
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(dir, DocumentsContract.Document.MIME_TYPE_DIR))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No file browser found. Snapshots are in $SNAPSHOT_DIR", Toast.LENGTH_LONG).show()
        }
    }

    private fun showInfo(anchor: View) {
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setBackgroundColor(0xE6202020.toInt())
            this.text = deviceInfo.ifEmpty { "No camera connected" }.replace("  ", "\n")
        }
        PopupWindow(text, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true).showAsDropDown(anchor)
    }

    /** Toggles for the on-screen readouts. */
    private fun showReadoutPanel(anchor: View) {
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(0xE6202020.toInt())
        }
        fun toggle(title: String, checked: Boolean, change: (CheckBox, Boolean) -> Unit) {
            col.addView(CheckBox(this).apply {
                text = title
                setTextColor(Color.WHITE)
                isChecked = checked
                setOnCheckedChangeListener { box, on -> change(box as CheckBox, on) }
            })
        }
        toggle("Hottest / coldest markers", view.showMarkers) { _, on ->
            view.showMarkers = on
            prefs.edit().putBoolean(PREF_MARKERS, on).apply()
        }
        toggle("Lock colour scale", lockedRange != null) { box, on ->
            val frame = view.currentFrame()
            if (on && frame == null) {
                Toast.makeText(this, "No thermal image yet", Toast.LENGTH_SHORT).show()
                box.isChecked = false
            } else {
                // Freeze the range in use right now; the session applies it to every later frame.
                lockedRange = if (on) floatArrayOf(frame!!.loRaw, frame.hiRaw) else null
                session?.lockRange(lockedRange)
            }
        }
        toggle("Colour scale bar", view.showScale) { _, on ->
            view.showScale = on
            prefs.edit().putBoolean(PREF_SCALE, on).apply()
        }
        PopupWindow(col, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true).showAtLocation(
            buttons, Gravity.TOP or Gravity.END, buttons.paddingRight, buttons.bottom + (4 * dp).toInt(),
        )
    }

    private fun showPaletteMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        Palette.ALL.forEachIndexed { i, p ->
            menu.menu.add(0, i, i, p.name).apply { isCheckable = true; isChecked = p === view.palette }
        }
        menu.menu.setGroupCheckable(0, true, true)
        menu.setOnMenuItemClickListener { item ->
            val p = Palette.ALL[item.itemId]
            view.palette = p
            (anchor as IconButton).showPalette(p)
            prefs.edit().putString(PREF_PALETTE, p.name).apply()
            true
        }
        menu.show()
    }

    private fun stopSession() {
        session?.shutdown()
        session = null
        sessionDevice = null
    }

    // SessionListener, called on the USB thread
    override fun onStatus(text: String) { view.status = text }
    override fun onInfo(text: String) { deviceInfo = text }
    override fun onFrame(frame: DisplayFrame) { view.submit(frame) }
    override fun onError(text: String) { view.status = "$text\nTap to retry" }
}
