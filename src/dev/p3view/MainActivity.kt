package dev.p3view

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu

private const val ACTION_USB_PERMISSION = "dev.p3view.USB_PERMISSION"
private const val PREF_ROTATION = "rotation"
private const val PREF_PALETTE = "palette"

class MainActivity : Activity(), SessionListener {
    private lateinit var usb: UsbManager
    private lateinit var view: ThermalView
    private lateinit var prefs: SharedPreferences
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
                        view.info = ""
                        view.status = "Camera unplugged"
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
            topReserve = 36 * resources.displayMetrics.density
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
        val shutterButton = IconButton(this, IconButton.Kind.SHUTTER).apply {
            setOnClickListener {
                val s = session
                if (s != null && s.isAlive) s.shutter()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in listOf(paletteButton, rotateButton, shutterButton)) {
                addView(b, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (8 * dp).toInt()
                })
            }
            setOnApplyWindowInsetsListener { v, insets ->
                val safe = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(safe.left, safe.top + (8 * dp).toInt(), safe.right + (8 * dp).toInt(), 0)
                insets
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(buttons, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        })
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        connect()
    }

    override fun onResume() {
        super.onResume()
        connect()
    }

    override fun onStop() {
        super.onStop()
        stopSession()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        stopSession()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) connect()
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
        session = CameraSession(conn, device, Model.fromPid(device.productId)!!, this).also { it.start() }
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
    override fun onInfo(text: String) { view.info = text }
    override fun onFrame(frame: DisplayFrame) { view.submit(frame) }
    override fun onError(text: String) { view.status = "$text\nTap to retry" }
}
