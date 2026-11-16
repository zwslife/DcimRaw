package com.dcimgallery.raw.core.tone

import com.dcimgallery.raw.core.geometry.CropRect
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import kotlin.math.abs
import kotlin.math.roundToInt

private fun color(r: Int, g: Int, b: Int): Int =
    (255 shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

// Host reference for the matrix and point-curve portion of the renderer.
private fun render(pixels: IntArray, params: ToneParams): IntArray {
    val matrix = params.toMatrix()
    val lut = params.renderLut()
    return IntArray(pixels.size) { i ->
        val r = (pixels[i] ushr 16 and 255).toFloat()
        val g = (pixels[i] ushr 8 and 255).toFloat()
        val b = (pixels[i] and 255).toFloat()
        fun channel(index: Int): Int {
            val row = index * 5
            val value = (matrix[row] * r + matrix[row + 1] * g + matrix[row + 2] * b + matrix[row + 4])
                .roundToInt().coerceIn(0, 255)
            return lut[index * 256 + value].roundToInt()
        }
        color(channel(0), channel(1), channel(2))
    }
}

fun main() {
    check(AdaptiveLooks.presets.count { it.portrait } == 4 && AdaptiveLooks.presets.count { !it.portrait } == 4)
    check(AdaptiveLooks.profile(intArrayOf(0, 0x10FFFFFF)) == null)
    val reference = IntArray(1024) { index ->
        val gray = 40 + index % 170
        color(gray + 14, gray + 5, gray - 11)
    }
    val target = checkNotNull(AdaptiveLooks.profile(reference))
    val neutral = ToneParams()
    val same = AdaptiveLooks.fit(neutral, target, false) { checkNotNull(AdaptiveLooks.profile(render(reference, it))) }
    check(same == neutral)
    val results = listOf(-36, 40).map { offset ->
        val source = IntArray(1024) { index ->
            val gray = 40 + index % 170 + offset
            color(gray - 9, gray + 2, gray + 10)
        }
        fun profile(tone: ToneParams) = checkNotNull(AdaptiveLooks.profile(render(source, tone)))
        val before = profile(neutral)
        val fit = AdaptiveLooks.fit(neutral, target, false, ::profile)
        val after = profile(fit)
        check(AdaptiveLooks.distance(after, target, false) < AdaptiveLooks.distance(before, target, false))
        check(kotlin.math.abs(after.light[4] - target.light[4]) < kotlin.math.abs(before.light[4] - target.light[4]))
        check(kotlin.math.abs(after.bands[1].redBlue - target.bands[1].redBlue) <
            kotlin.math.abs(before.bands[1].redBlue - target.bands[1].redBlue))
        fit
    }
    check(results[0] != results[1])
    val dark = checkNotNull(faceLightTone(.25f, .55f))
    val moderate = checkNotNull(faceLightTone(.55f, .80f))
    check(dark.exposure > moderate.exposure && moderate.highlights < 0f)
    check(faceLightTone(.82f, .98f) == null)
    check(faceLightTone(.30f, 1f) == null)
    val rotatedCrop = PhotoGeometry(quarterTurns = 1, crop = CropRect(.25f, .10f, .75f, .9f))
    val mapped = faceSourcePoint(60f, 80f, 400, 240, rotatedCrop)
    check(abs(mapped.x - .3f) < .001f && abs(mapped.y - .5f) < .001f)
    println("PASS: eight adaptive targets, source-dependent luminance and color fitting, face highlight headroom and rotated crop coordinates")
}
