package com.dcimgallery.raw.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private fun close(a: Float, b: Float, tolerance: Float = .0002f) = check(abs(a - b) < tolerance) { "$a != $b" }
private fun valid(rect: CropRect) {
    check(rect.left >= -.0001f && rect.top >= -.0001f && rect.right <= 1.0001f && rect.bottom <= 1.0001f) { "$rect" }
    check(rect.width > 0f && rect.height > 0f) { "$rect" }
}

fun main() {
    check(PhotoGeometry().outputSize(6000, 4000) == 6000 to 4000)
    check(PhotoGeometry().rotatedBy(1).outputSize(6000, 4000) == 4000 to 6000)
    check(PhotoGeometry().rotatedBy(-1).rotatedBy(1) == PhotoGeometry())
    val crop = CropRect(.1f, .2f, .7f, .9f)
    var rotated = PhotoGeometry(crop = crop)
    repeat(4) { rotated = rotated.rotatedBy(1) }
    close(rotated.crop.left, crop.left); close(rotated.crop.top, crop.top)
    close(rotated.crop.right, crop.right); close(rotated.crop.bottom, crop.bottom)
    check(PhotoGeometry(-9, Float.NaN, CropRect(Float.NaN)).normalized() == PhotoGeometry(3))
    // Every corner of the displayed frame maps inside the original image at every supported angle.
    for ((w, h) in listOf(6000 to 4000, 4000 to 6000, 1000 to 1000, 8000 to 1000)) {
        for (turn in 0..3) for (angle in -45..45) {
            val geometry = PhotoGeometry(turn, angle.toFloat())
            val frame = geometry.frame(w, h)
            val radians = Math.toRadians(-geometry.rotation.toDouble())
            for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) {
                val x = sx * frame.width / 2f; val y = sy * frame.height / 2f
                val originalX = x * cos(radians) - y * sin(radians)
                val originalY = x * sin(radians) + y * cos(radians)
                check(abs(originalX) <= w / 2.0 + .002 && abs(originalY) <= h / 2.0 + .002)
            }
            val (outW, outH) = geometry.outputSize(w, h)
            check(outW > 0 && outH > 0)
        }
    }
    val random = Random(107)
    val ratios = listOf(1f, 4f / 3, 3f / 4, 3f / 2, 2f / 3, 16f / 9, 9f / 16, 4f / 5, 5f / 4)
    for (pixelRatio in ratios) {
        val ratio = pixelRatio / 1.5f
        val initial = CropRect().withAspect(ratio)
        valid(initial); close(initial.width / initial.height, ratio)
        for (handle in 0..8) repeat(150) {
            val result = initial.dragged(handle, random.nextFloat() * 4 - 2,
                random.nextFloat() * 4 - 2, ratio)
            valid(result); close(result.width / result.height, ratio)
        }
    }
    for (handle in 0..8) repeat(300) {
        valid(crop.dragged(handle, random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2, 0f))
    }
    println("PASS: quarter turns, crop roundtrip, invalid saved data, all 1,456 rotation frames without empty corners, preset ratios, 14,850 constrained/free drag cases")
}
