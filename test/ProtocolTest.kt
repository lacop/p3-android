// JVM tests for Protocol.kt. Run by build.sh before packaging.

package dev.p3view

private var failures = 0

private fun check(name: String, ok: Boolean) {
    if (!ok) { failures++; System.err.println("FAIL $name") }
}

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

private val P3_MODEL = Model.fromPid(0x45a2)!!

private fun marker(sync: Int, cnt1: Long, cnt3: Int) = byteArrayOf(
    12, sync.toByte(),
    cnt1.toByte(), (cnt1 shr 8).toByte(), (cnt1 shr 16).toByte(), (cnt1 shr 24).toByte(),
    0, 0, 0, 0,
    cnt3.toByte(), (cnt3 shr 8).toByte(),
)

private fun pixels(seed: Int) = ByteArray(P3_MODEL.frameSize) { (it * 7 + seed).toByte() }

private fun push(a: Assembler, vararg parts: ByteArray): Assembled? {
    val seg = parts.fold(ByteArray(0)) { acc, p -> acc + p }
    return a.push(seg, seg.size)
}

private fun frameData(r: Assembled?) = (r as? Assembled.Frame)?.frame?.data

fun main() {
    // Pre-computed commands from P3_PROTOCOL.md.
    val reference = listOf(
        "0101810001000000000000001e0000004f90" to Commands.readRegister(0x01, 30),
        "0101810002000000000000000c0000001f63" to Commands.readRegister(0x02, 12),
        "01018100070000000000000040000000104c" to Commands.readRegister(0x07, 64),
        "012f81000000000000000000010000004930" to Commands.startStream,
        "012f41000000000000000000000000003c3a" to Commands.gain(false),
        "012f41000100000000000000000000004939" to Commands.gain(true),
        "01364300000000000000000000000000cd0b" to Commands.shutter,
    )
    for ((want, got) in reference) check("command $want", got.contentEquals(hex(want)))
    check("crc check value", crc16("123456789".toByteArray()) == 0x31c3)
    check("decodeText", decodeText(byteArrayOf(0x50, 0x33, 0, 0)) == "P3" && decodeText(byteArrayOf(0x50, 0x33, -1, -1)) == "P3")

    // Joiner: 197,644-byte transfer in 64 KiB requests, then a 12-byte end marker.
    val j = Joiner()
    val req = 64 * 1024
    val big = ByteArray(12 + P3_MODEL.frameSize)
    val segs = mutableListOf<Int>()
    var off = 0
    while (off < big.size) {
        val n = minOf(req, big.size - off)
        if (j.push(big, off, n, req)) segs += j.segment.size
        off += n
    }
    check("joiner frame segment", segs == listOf(12 + P3_MODEL.frameSize))
    check("joiner marker segment", j.push(ByteArray(12), 0, 12, req) && j.segment.size == 12)
    check("joiner zero-length packet", !j.push(ByteArray(req), 0, req, req) && j.push(ByteArray(0), 0, 0, req) && j.segment.size == req)

    // Normal frame.
    var a = Assembler(P3_MODEL)
    var px = pixels(1)
    check("normal: no output on body", push(a, marker(0x8c, 7, 40), px) == null)
    check("normal: frame", frameData(push(a, marker(0x8e, 7, 80)))?.contentEquals(px) == true)

    // First frame after a manual shutter, as three segments and as a merged segment.
    val junk = ByteArray(36 * 256 - 12) { 0x55 }
    val split = 764 * 256
    px = pixels(2)
    push(a, marker(0x8d, 9, 40), junk, px.copyOfRange(0, split))
    push(a, marker(0x8d, 9, 80), px.copyOfRange(split, px.size))
    check("shutter: three segments", frameData(push(a, marker(0x8f, 9, 80)))?.contentEquals(px) == true)
    px = pixels(3)
    push(a, marker(0x8d, 9, 40), junk, px.copyOfRange(0, split), marker(0x8d, 9, 80), px.copyOfRange(split, px.size))
    check("shutter: merged segment", frameData(push(a, marker(0x8f, 9, 80)))?.contentEquals(px) == true)

    // Lost end marker: the next frame still comes through.
    a = Assembler(P3_MODEL)
    val p1 = pixels(4)
    val p2 = pixels(5)
    push(a, marker(0x8c, 1, 0), p1)
    push(a, marker(0x8d, 2, 40), p2)
    check("lost end marker", frameData(push(a, marker(0x8f, 2, 80)))?.contentEquals(p2) == true)

    // Drops and resync.
    a = Assembler(P3_MODEL)
    check("garbage ignored", push(a, byteArrayOf(1, 2, 3)) == null)
    check("unsynced end", push(a, marker(0x8e, 1, 0)) is Assembled.Dropped)
    push(a, marker(0x8c, 3, 0), pixels(6).copyOfRange(0, 1000))
    check("short frame", push(a, marker(0x8e, 3, 40)) is Assembled.Dropped)
    push(a, marker(0x8c, 4, 0), pixels(6))
    check("counter mismatch", push(a, marker(0x8e, 5, 40)) is Assembled.Dropped)
    px = pixels(7)
    push(a, marker(0x8c, 6, 0), px)
    check("resync", frameData(push(a, marker(0x8e, 6, 40)))?.contentEquals(px) == true)

    // Thermal plane and conversion.
    val raw = ((25.0 + 273.15) * 64).toInt()
    val data = ByteArray(P3_MODEL.frameSize)
    val base = (P3_MODEL.height + 2) * P3_MODEL.width * 2
    data[base] = raw.toByte(); data[base + 1] = (raw shr 8).toByte()
    val t = RawFrame(Marker(0x8c, 0, 0), Marker(0x8e, 0, 0), data, false).thermal(P3_MODEL)
    check("thermal plane", t[0] == raw && kotlin.math.abs(rawToCelsius(t[0]) - 25f) < 0.02f)

    if (failures > 0) { System.err.println("$failures test(s) failed"); kotlin.system.exitProcess(1) }
    println("protocol tests passed")
}
