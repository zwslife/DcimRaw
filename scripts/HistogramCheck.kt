package com.dcimgallery.raw.core.tone

fun main() {
    fun colors(vararg pixels: Int) = HistogramData.sample(pixels.size, 1) { _, row -> pixels.copyInto(row) }
    val endpoints = colors(0xff000000.toInt(), 0xffffffff.toInt())
    check(endpoints.samples == 2 && endpoints.luminance[0] == 1 && endpoints.luminance[255] == 1)
    listOf(endpoints.red, endpoints.green, endpoints.blue).forEach { check(it[0] == 1 && it[255] == 1) }
    val rgb = colors(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
    check(rgb.red[255] == 1 && rgb.green[255] == 1 && rgb.blue[255] == 1)
    check(rgb.luminance[77] == 1 && rgb.luminance[149] == 1 && rgb.luminance[29] == 1)
    val transparent = colors(0x00ffffff, 0xff808080.toInt())
    check(transparent.samples == 1 && transparent.luminance[128] == 1)
    val empty = colors(0)
    check(empty.samples == 0 && empty.peak == 1)
    val white = colors(0xffffffff.toInt())
    check(white.samples == 1 && white.peak == 1)
    var rowsRead = 0
    val large = HistogramData.sample(6000, 4000) { y, row ->
        check(y in 0 until 4000)
        rowsRead++
        row.fill(if (y < 2000) 0xff000000.toInt() else 0xffffffff.toInt())
    }
    check(rowsRead == 128 && large.samples == 128 * 128)
    check(large.luminance[0] == 8192 && large.luminance[255] == 8192)
    listOf(large.red, large.green, large.blue, large.luminance).forEach { check(it.sum() == large.samples) }
    // Recompute from a changed displayed frame, retaining both clipping endpoints.
    val before = colors(0xff404040.toInt())
    val after = colors(0xffc0c0c0.toInt())
    check(before.luminance[64] == 1 && after.luminance[192] == 1 && after.luminance[64] == 0)
    val flat = IntArray(256) { 40 }
    check(HistogramData.smooth(flat).all { it == 40f }) { "Smoothing must preserve a flat histogram, including endpoints" }
    check(HistogramData.smooth(IntArray(256)).all { it == 0f })
    val impulse = IntArray(256).also { it[128] = 160 }
    val curve = HistogramData.smooth(impulse)
    check(curve[128] > curve[127] && curve[127] > curve[126] && curve[126] > 0f)
    check(curve[126] == curve[130] && curve[127] == curve[129])
    check(kotlin.math.abs(curve.sum() - 160f) < .001f)
    check(impulse[128] == 160 && impulse.sum() == 160) { "Display smoothing must not alter source counts" }
    for (edge in listOf(0, 255)) {
        val values = HistogramData.smooth(IntArray(256).also { it[edge] = 100 })
        check(values.all { it.isFinite() && it >= 0f } && values[edge] > 0f)
    }
    println("PASS: RGB/luminance bins, black/white endpoints, transparency, edited frames, 16K-sample bound and non-destructive Gaussian display smoothing")
}
