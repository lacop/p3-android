package dev.p3view

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

private const val ACTION_USB_PERMISSION = "dev.p3view.USB_PERMISSION"

class MainActivity : Activity(), SessionListener {
    private lateinit var usb: UsbManager
    private lateinit var view: ThermalView
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
        view = ThermalView(this)
        setContentView(view)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view.setOnClickListener {
            val s = session
            if (s != null && s.isAlive) {
                s.shutter()
                view.status = ""
            } else {
                connect(fromUser = true)
            }
        }
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
