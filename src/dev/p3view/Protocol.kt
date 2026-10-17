// P3 / P1 wire protocol. Pure Kotlin, no Android imports, so it can be unit tested on the JVM.
//
// Protocol facts from github.com/jvdillon/p3-ir-camera (P3_PROTOCOL.md) and
// github.com/xaionaro-go/thermalmaster (doc/reverse-engineering.md).

package dev.p3view

object P3 {
    const val VENDOR_ID = 0x3474
    const val MARKER_LEN = 12
    const val COMMAND_LEN = 18

    // Vendor control requests: bmRequestType 0x41 (out) / 0xC1 (in), wIndex 0.
    const val REQ_SEND_COMMAND = 0x20
    const val REQ_READ_RESPONSE = 0x21
    const val REQ_READ_STATUS = 0x22
    // Device-recipient stream trigger: bmRequestType 0x40, wIndex 1.
    const val REQ_STREAM_TRIGGER = 0xEE

    const val EP_STREAM_IN = 0x81

    // Status register: 1 = busy, 2 = write done, 3 = read done.
    const val STATUS_BUSY = 1
}

class Model(val name: String, val pid: Int, val width: Int, val height: Int) {
    /** IR rows + 2 metadata rows + thermal rows, 16 bits per pixel, markers excluded. */
    val frameSize: Int get() = 2 * (2 * height + 2) * width
    val pixels: Int get() = width * height

    companion object {
        val ALL = listOf(Model("P3", 0x45a2, 256, 192), Model("P1", 0x45c2, 160, 120))
        fun fromPid(pid: Int): Model? = ALL.firstOrNull { it.pid == pid }
    }
}

/** CRC-16/CCITT (XMODEM variant): poly 0x1021, init 0. The camera does not check it. */
fun crc16(data: ByteArray, len: Int = data.size): Int {
    var crc = 0
    for (i in 0 until len) {
        crc = crc xor ((data[i].toInt() and 0xff) shl 8)
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc = crc and 0xffff
    }
    return crc
}

/** 18-byte command. cmdType is big-endian on the wire, every other field little-endian. */
fun buildCommand(cmdType: Int, param: Int, register: Int, respLen: Int): ByteArray {
    val c = ByteArray(P3.COMMAND_LEN)
    c[0] = (cmdType shr 8).toByte(); c[1] = cmdType.toByte()
    c[2] = param.toByte(); c[3] = (param shr 8).toByte()
    c[4] = register.toByte(); c[5] = (register shr 8).toByte()
    c[12] = respLen.toByte(); c[13] = (respLen shr 8).toByte()
    val crc = crc16(c, 16)
    c[16] = crc.toByte(); c[17] = (crc shr 8).toByte()
    return c
}

object Commands {
    fun readRegister(register: Int, len: Int) = buildCommand(0x0101, 0x0081, register, len)
    val startStream get() = buildCommand(0x012f, 0x0081, 0, 1)
    fun gain(high: Boolean) = buildCommand(0x012f, 0x0041, if (high) 1 else 0, 0)
    val shutter get() = buildCommand(0x0136, 0x0043, 0, 0)
}

class InfoRegister(val label: String, val register: Int, val len: Int)

val INFO_REGISTERS = listOf(
    InfoRegister("Model", 0x01, 30),
    InfoRegister("Firmware", 0x02, 12),
    InfoRegister("Part number", 0x06, 64),
    InfoRegister("Serial", 0x07, 64),
    InfoRegister("Hardware", 0x0a, 64),
)

/** Register strings are NUL padded; unprogrammed flash reads as 0xFF. */
fun decodeText(b: ByteArray, len: Int = b.size): String {
    var end = (0 until len).firstOrNull { b[it].toInt() == 0 } ?: len
    while (end > 0 && b[end - 1] == 0xff.toByte()) end--
    return String(b, 0, end, Charsets.UTF_8).map { if (it.isISOControl()) '.' else it }.joinToString("")
}

fun rawToCelsius(raw: Int): Float = raw / 64f - 273.15f

class Marker(val sync: Int, val cnt1: Long, val cnt3: Int) {
    val isStart get() = sync == 0x8c || sync == 0x8d
    val isEnd get() = sync == 0x8e || sync == 0x8f

    companion object {
        fun parse(b: ByteArray, off: Int, len: Int): Marker? {
            if (len < P3.MARKER_LEN || b[off].toInt() != P3.MARKER_LEN) return null
            fun u8(i: Int) = b[off + i].toInt() and 0xff
            val m = Marker(
                sync = u8(1),
                cnt1 = (u8(2) or (u8(3) shl 8) or (u8(4) shl 16)).toLong() or (u8(5).toLong() shl 24),
                cnt3 = u8(10) or (u8(11) shl 8),
            )
            return if (m.isStart || m.isEnd) m else null
        }
    }
}

/** Growable byte buffer. */
class Bytes(capacity: Int) {
    var data = ByteArray(capacity)
        private set
    var size = 0
        private set

    fun append(src: ByteArray, off: Int, len: Int) {
        if (size + len > data.size) data = data.copyOf(maxOf(size + len, data.size * 2))
        System.arraycopy(src, off, data, size, len)
        size += len
    }

    fun clear() { size = 0 }

    fun remove(at: Int, len: Int) {
        System.arraycopy(data, at + len, data, at, size - at - len)
        size -= len
    }
}

/**
 * Joins bulk IN completions into device-side transfers. A device transfer ends with a packet
 * shorter than the max packet size, which completes the host request early
 * (actual < requested), including a zero-length packet after an exact multiple.
 */
class Joiner(private val limit: Int = 4 shl 20) {
    val segment = Bytes(512 * 1024)
    private var done = false

    /** Returns true when this completion finished a segment, available in [segment]. */
    fun push(src: ByteArray, off: Int, len: Int, requested: Int): Boolean {
        if (done) { segment.clear(); done = false }
        segment.append(src, off, len)
        if (len < requested || segment.size >= limit) { done = true; return true }
        return false
    }

    fun discard() { segment.clear(); done = false }
}

class RawFrame(val start: Marker, val end: Marker, val data: ByteArray, val repaired: Boolean) {
    /** Thermal plane, raw values in 1/64 K. */
    fun thermal(m: Model): IntArray {
        val base = (m.height + 2) * m.width * 2
        return IntArray(m.pixels) { i ->
            (data[base + 2 * i].toInt() and 0xff) or ((data[base + 2 * i + 1].toInt() and 0xff) shl 8)
        }
    }
}

sealed class Assembled {
    class Frame(val frame: RawFrame) : Assembled()
    class Dropped(val reason: String) : Assembled()
}

/**
 * Turns device-side transfers into frames. Normal frame: [start marker + pixels], [end marker].
 * First frame after a manual shutter: [start + junk + pixels part 1], [start + pixels part 2],
 * [end marker]. The junk duplicates the image top and precedes the real data, so a frame is
 * always the last frameSize payload bytes.
 */
class Assembler(model: Model) {
    private val frameSize = model.frameSize
    private val body = Bytes(frameSize + 16 * 1024)
    private var first: Marker? = null
    private var latest: Marker? = null
    private var latestOff = 0

    fun push(seg: ByteArray, len: Int): Assembled? {
        val marker = Marker.parse(seg, 0, len)
        if (len == P3.MARKER_LEN && marker != null && marker.isEnd) return finish(marker)
        if (marker != null && marker.isStart) {
            if (first == null) { first = marker; body.clear() }
            latest = marker
            latestOff = body.size
            body.append(seg, P3.MARKER_LEN, len - P3.MARKER_LEN)
        } else if (first != null) {
            body.append(seg, 0, len)
        } else {
            return null // not synced yet, wait for a start marker
        }
        if (body.size > 3 * frameSize) {
            reset()
            return Assembled.Dropped("no end marker within 3 frames")
        }
        return null
    }

    private fun reset() { first = null; latest = null; body.clear() }

    private fun finish(end: Marker): Assembled {
        val f = first
        val l = latest
        if (f == null || l == null) { reset(); return Assembled.Dropped("end marker before start marker") }
        var repaired = false
        val start: Marker? = when {
            f.cnt1 == end.cnt1 -> {
                if (body.size != frameSize) { repaired = true; removeEmbeddedMarker(end.cnt3) }
                if (body.size >= frameSize) f else null
            }
            // An end marker was lost; the newest start marker begins this frame.
            l.cnt1 == end.cnt1 && body.size - latestOff >= frameSize -> l
            else -> null
        }
        val result = if (start == null) {
            Assembled.Dropped("frame ${end.cnt1}: ${body.size} bytes, start counter ${f.cnt1}")
        } else {
            Assembled.Frame(RawFrame(start, end, body.data.copyOfRange(body.size - frameSize, body.size), repaired))
        }
        reset()
        return result
    }

    /** A merged post-shutter segment carries the second start marker inside the payload. */
    private fun removeEmbeddedMarker(endCnt3: Int) {
        if (body.size < frameSize + P3.MARKER_LEN) return
        val from = maxOf(0, body.size - 64 * 1024)
        for (i in body.size - P3.MARKER_LEN downTo from) {
            val m = Marker.parse(body.data, i, P3.MARKER_LEN)
            if (m != null && m.isStart && m.cnt3 == endCnt3) { body.remove(i, P3.MARKER_LEN); return }
        }
    }
}
