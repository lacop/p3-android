package dev.p3view

/** A named 256-entry ARGB lookup table from contrast-stretched level (0 cold, 255 hot) to colour. */
class Palette(val name: String, val lut: IntArray) {
    companion object {
        val ALL = listOf(
            gradient("Ironbow",
                0.00f to 0x000010, 0.12f to 0x1b0c41, 0.28f to 0x4a0c6b, 0.42f to 0x8f1f74, 0.55f to 0xc8364f,
                0.68f to 0xee6a24, 0.80f to 0xf9a81a, 0.91f to 0xfbe07b, 1.00f to 0xfffbe6),
            gradient("White hot", 0f to 0x000000, 1f to 0xffffff),
            gradient("Black hot", 0f to 0xffffff, 1f to 0x000000),
            gradient("Rainbow",
                0.00f to 0x000080, 0.25f to 0x0060ff, 0.45f to 0x00e0c0, 0.60f to 0x40e040,
                0.78f to 0xffe000, 1.00f to 0xff2000),
            gradient("Lava",
                0.00f to 0x000000, 0.35f to 0x8b0000, 0.65f to 0xff6a00, 0.85f to 0xffd000, 1.00f to 0xffffff),
            // FLIR-style "Arctic": deep blue through white to orange and red.
            gradient("Arctic",
                0.00f to 0x00002e, 0.20f to 0x0b3d91, 0.40f to 0x3fa0e8, 0.55f to 0xcfeeff, 0.70f to 0xffe9a8,
                0.85f to 0xff9a3c, 1.00f to 0xd7261e),
            // Rainbow with more bands, so small steps stand out.
            gradient("Rainbow HC",
                0.00f to 0x000000, 0.10f to 0x2a0080, 0.20f to 0x0040ff, 0.30f to 0x00d0ff, 0.40f to 0x00e060,
                0.50f to 0xa0e000, 0.60f to 0xffe000, 0.70f to 0xff8000, 0.80f to 0xff0000, 0.90f to 0xff00a0,
                1.00f to 0xffffff),
            gradient("Jet",
                0.000f to 0x000080, 0.125f to 0x0000ff, 0.375f to 0x00ffff, 0.625f to 0xffff00, 0.875f to 0xff0000,
                1.000f to 0x800000),
            gradient("Blue-white-red", 0f to 0x1a1aff, 0.5f to 0xffffff, 1f to 0xff1a1a),
            gradient("Night green", 0f to 0x000000, 0.6f to 0x00a040, 1f to 0xc0ffc0),
            gradient("Viridis",
                0.00f to 0x440154, 0.25f to 0x3b528b, 0.50f to 0x21918c, 0.75f to 0x5ec962, 1.00f to 0xfde725),
        )

        fun byName(name: String?): Palette = ALL.firstOrNull { it.name == name } ?: ALL[0]

        private fun gradient(name: String, vararg stops: Pair<Float, Int>) = Palette(name, IntArray(256) { i ->
            val t = i / 255f
            val k = stops.indices.firstOrNull { it > 0 && t <= stops[it].first }?.minus(1) ?: (stops.size - 2)
            val (t0, c0) = stops[k]
            val (t1, c1) = stops[k + 1]
            val f = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
            fun ch(shift: Int): Int {
                val x = (c0 shr shift) and 0xff
                val y = (c1 shr shift) and 0xff
                return Math.round(x + (y - x) * f) shl shift
            }
            (0xff shl 24) or ch(16) or ch(8) or ch(0)
        })
    }
}
