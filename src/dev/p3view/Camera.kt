// USB session on a worker thread: vendor commands, stream start, bulk reads, frame assembly,
// rendering to ARGB pixels.

package dev.p3view

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeoutException

private const val TAG = "p3view"
private const val TIMEOUT_MS = 1000
private const val TRANSFER_LEN = 64 * 1024
private const val TRANSFERS_IN_FLIGHT = 8

class DisplayFrame(
    /** Contrast-stretched level per pixel, 0..255; the view maps it through the palette. */
    val levels: IntArray,
    /** Raw thermal values in 1/64 K. */
    val thermal: IntArray,
    val width: Int,
    val height: Int,
    val hfovDeg: Float,
    /** Temperatures that levels 0 and 255 stand for, i.e. the range the colour scale covers. */
    val loC: Float,
    val hiC: Float,
    val minC: Float,
    val maxC: Float,
    val fps: Float,
) {
    fun celsiusAt(x: Int, y: Int): Float = rawToCelsius(thermal[y * width + x])
}

interface SessionListener {
    fun onStatus(text: String)
    fun onInfo(text: String)
    fun onFrame(frame: DisplayFrame)
    fun onError(text: String)
}

class CameraSession(
    private val conn: UsbDeviceConnection,
    private val device: UsbDevice,
    private val model: Model,
    private val listener: SessionListener,
) : Thread("p3-usb") {
    @Volatile private var running = true
    private val commands = LinkedBlockingQueue<ByteArray>()
    private val renderer = Renderer(model)

    fun shutter() { commands.offer(Commands.shutter) }

    fun shutdown() {
        running = false
        interrupt() // wakes the start sequence sleeps
        join(3000)
    }

    override fun run() {
        try {
            session()
        } catch (e: Exception) {
            Log.w(TAG, "session ended", e)
            if (running) listener.onError(e.message ?: e.toString())
        } finally {
            conn.close()
        }
    }

    private fun iface(id: Int, alt: Int): UsbInterface =
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == id && it.alternateSetting == alt }
            ?: throw IllegalStateException("interface $id alt $alt not found")

    private fun session() {
        val control = iface(0, 0)
        val streamIdle = iface(1, 0)
        val streamActive = iface(1, 1)
        val ep: UsbEndpoint = (0 until streamActive.endpointCount).map { streamActive.getEndpoint(it) }
            .firstOrNull { it.address == P3.EP_STREAM_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            ?: throw IllegalStateException("bulk endpoint 0x81 not found")

        // force = true detaches the kernel uvcvideo driver
        check(conn.claimInterface(control, true)) { "cannot claim interface 0" }
        check(conn.claimInterface(streamIdle, true)) { "cannot claim interface 1" }

        val info = INFO_REGISTERS.associate { it.label to runCatching { readRegister(it.register, it.len) }.getOrDefault("?") }
        listener.onInfo("${info["Model"]}  SN ${info["Serial"]}  FW ${info["Firmware"]}")
        Log.i(TAG, "device info: $info")

        listener.onStatus("Starting stream")
        startHandshake()
        sleep(1000)
        check(conn.setInterface(streamActive)) { "cannot select interface 1 alt 1" }
        check(conn.controlTransfer(0x40, P3.REQ_STREAM_TRIGGER, 0, 1, null, 0, TIMEOUT_MS) >= 0) { "stream trigger failed" }
        sleep(2000)
        val primeLen = (model.frameSize + ep.maxPacketSize - 1) / ep.maxPacketSize * ep.maxPacketSize
        conn.bulkTransfer(ep, ByteArray(primeLen), primeLen, 100) // priming read, result ignored
        startHandshake()
        listener.onStatus("")

        val requests = List(TRANSFERS_IN_FLIGHT) {
            UsbRequest().apply {
                check(initialize(conn, ep)) { "cannot initialize USB request" }
                clientData = ByteBuffer.allocateDirect(TRANSFER_LEN)
            }
        }
        try {
            for (r in requests) check(r.queue(r.clientData as ByteBuffer)) { "cannot queue USB request" }
            stream(requests)
        } finally {
            for (r in requests) r.cancel()
            val deadline = SystemClock.uptimeMillis() + 500
            var pending = requests.size
            while (pending > 0 && SystemClock.uptimeMillis() < deadline) {
                try { conn.requestWait(100) ?: break; pending-- } catch (_: TimeoutException) {} catch (_: Exception) { break }
            }
            requests.forEach { it.close() }
            conn.setInterface(streamIdle)
            conn.releaseInterface(streamIdle)
            conn.releaseInterface(control)
        }
    }

    private fun stream(requests: List<UsbRequest>) {
        val joiner = Joiner()
        val assembler = Assembler(model)
        val chunk = ByteArray(TRANSFER_LEN)
        var lastData = SystemClock.uptimeMillis()
        var frames = 0
        var fpsStart = SystemClock.uptimeMillis()
        var fps = 0f

        while (running) {
            commands.poll()?.let { write(it) }
            val r = try {
                conn.requestWait(200)
            } catch (_: TimeoutException) {
                if (SystemClock.uptimeMillis() - lastData > 3000) throw IllegalStateException("no data from the camera for 3 s")
                continue
            } ?: throw IllegalStateException("USB transfer failed; camera unplugged?")
            if (r !in requests) continue
            lastData = SystemClock.uptimeMillis()

            val buf = r.clientData as ByteBuffer
            val n = buf.position()
            buf.flip()
            buf.get(chunk, 0, n)
            buf.clear()
            check(r.queue(buf)) { "cannot requeue USB request" }

            if (!joiner.push(chunk, 0, n, TRANSFER_LEN)) continue
            when (val a = assembler.push(joiner.segment.data, joiner.segment.size)) {
                null -> {}
                is Assembled.Dropped -> Log.d(TAG, "dropped: ${a.reason}")
                is Assembled.Frame -> {
                    frames++
                    val now = SystemClock.uptimeMillis()
                    if (now - fpsStart >= 1000) {
                        fps = frames * 1000f / (now - fpsStart)
                        frames = 0
                        fpsStart = now
                    }
                    listener.onFrame(renderer.render(a.frame.thermal(model), fps))
                }
            }
        }
    }

    private fun out(request: Int, data: ByteArray) {
        val n = conn.controlTransfer(0x41, request, 0, 0, data, data.size, TIMEOUT_MS)
        check(n == data.size) { "control out 0x%02x failed (%d)".format(request, n) }
    }

    private fun input(request: Int, len: Int): ByteArray {
        val buf = ByteArray(len)
        val n = conn.controlTransfer(0xC1, request, 0, 0, buf, len, TIMEOUT_MS)
        check(n >= 0) { "control in 0x%02x failed (%d)".format(request, n) }
        return buf.copyOf(n)
    }

    private fun status(): Int {
        repeat(500) {
            val s = input(P3.REQ_READ_STATUS, 1)
            check(s.isNotEmpty()) { "empty status" }
            if (s[0].toInt() != P3.STATUS_BUSY) return s[0].toInt()
            sleep(1)
        }
        throw IllegalStateException("camera status stuck at busy")
    }

    private fun query(cmd: ByteArray, len: Int): ByteArray {
        out(P3.REQ_SEND_COMMAND, cmd)
        status()
        val data = input(P3.REQ_READ_RESPONSE, len)
        status()
        return data
    }

    private fun write(cmd: ByteArray) {
        out(P3.REQ_SEND_COMMAND, cmd)
        status()
    }

    private fun readRegister(register: Int, len: Int) = decodeText(query(Commands.readRegister(register, len), len))

    private fun startHandshake() {
        val r = query(Commands.startStream, 1)
        Log.i(TAG, "start-stream response ${r.joinToString { "%02x".format(it) }}")
    }
}

/** Percentile contrast stretch (1st to 99th, smoothed, 2 K minimum span) to 0..255 levels. */
class Renderer(private val model: Model) {
    private var lo = 0f
    private var hi = 0f
    private var init = false
    private val sorted = IntArray(model.pixels)

    fun render(thermal: IntArray, fps: Float): DisplayFrame {
        System.arraycopy(thermal, 0, sorted, 0, thermal.size)
        sorted.sort()
        val n = sorted.size
        val p1 = sorted[n / 100].toFloat()
        val p99 = sorted[n - 1 - n / 100].toFloat()
        if (init) { lo += (p1 - lo) * 0.2f; hi += (p99 - hi) * 0.2f } else { lo = p1; hi = p99; init = true }
        var a = lo
        var b = hi
        val minSpan = 2 * 64f
        if (b - a < minSpan) { val mid = (a + b) / 2; a = mid - minSpan / 2; b = mid + minSpan / 2 }
        val scale = 255f / (b - a)
        val levels = IntArray(n) { i -> ((thermal[i] - a) * scale).toInt().coerceIn(0, 255) }
        return DisplayFrame(
            levels, thermal, model.width, model.height, model.hfov,
            loC = rawToCelsius(a.toInt()), hiC = rawToCelsius(b.toInt()),
            minC = rawToCelsius(sorted[0]),
            maxC = rawToCelsius(sorted[n - 1]),
            fps = fps,
        )
    }
}
