package com.dcimgallery.raw.core.tone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.media.FaceDetector
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import com.dcimgallery.raw.core.geometry.PhotoGeometryRenderer
import com.dcimgallery.raw.core.raw.RawDecoder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun fitPhotoLook(source: Bitmap, geometry: PhotoGeometry, base: ToneParams,
    target: LookProfile, portrait: Boolean, checkActive: () -> Unit): ToneParams {
    return withPhotoSample(source, geometry, 112) { sample, _, _ ->
        val output = Bitmap.createBitmap(sample.width, sample.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(sample.width * sample.height)
        try {
            AdaptiveLooks.fit(base, target, portrait) { params ->
                checkActive()
                val data = params.toRenderData()
                check(RawDecoder.renderToneFull(sample, data.matrix, data.lut, data.hsl, data.effects, output))
                output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
                checkNotNull(AdaptiveLooks.profile(pixels))
            }
        } finally {
            output.recycle()
        }
    }
}

internal fun bitmapLookProfile(bitmap: Bitmap): LookProfile? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return AdaptiveLooks.profile(pixels)
}

private const val FaceLightPrefix = "face-light:"

internal data class FaceLightResult(val detected: Int, val layers: List<MaskLayer>)

internal fun findFaceLightMasks(frame: Bitmap, geometry: PhotoGeometry, existing: List<MaskLayer>): FaceLightResult =
    withPhotoSample(frame, geometry, 1200) { photo, sourceWidth, sourceHeight ->
        if (photo.width < 32 || photo.height < 32) return@withPhotoSample FaceLightResult(0, emptyList())
        val width = photo.width and 1.inv()
        val height = photo.height
        val faces = arrayOfNulls<FaceDetector.Face>(8)
        val detectorInput = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val found = try {
            Canvas(detectorInput).drawBitmap(photo, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            FaceDetector(width, height, faces.size).findFaces(detectorInput, faces)
        } finally {
            detectorInput.recycle()
        }
        val faceScaleX = photo.width.toFloat() / width
        val pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        val layers = ArrayList<MaskLayer>()
        val autoCenters = existing.filter { it.id.startsWith(FaceLightPrefix) }
            .mapNotNull { it.components.firstOrNull()?.points?.firstOrNull() }.toMutableList()
        var detected = 0
        for (face in faces.take(found).filterNotNull().sortedByDescending { it.eyesDistance() }) {
            val midpoint = PointF()
            face.getMidPoint(midpoint)
            val eyes = face.eyesDistance()
            if (face.confidence() < FaceDetector.Face.CONFIDENCE_THRESHOLD || eyes < 8f ||
                !eyes.isFinite() || !midpoint.x.isFinite() || !midpoint.y.isFinite()) continue
            val x = midpoint.x * faceScaleX
            val y = midpoint.y + eyes * .43f
            if (x !in 0f..photo.width.toFloat() || y !in 0f..photo.height.toFloat()) continue
            detected++
            val center = faceSourcePoint(x, y, sourceWidth, sourceHeight, geometry)
            val radiusX = eyes * 1.18f * faceScaleX / sourceWidth
            val radiusY = eyes * 1.52f / sourceHeight
            if (autoCenters.any { abs(it.x - center.x) < radiusX * .60f &&
                    abs(it.y - center.y) < radiusY * .60f }) continue
            val tone = faceExposure(pixels, photo.width, photo.height, x, y, eyes) ?: continue
            val roll = face.pose(FaceDetector.Face.EULER_Z).takeIf { it.isFinite() }?.coerceIn(-35f, 35f) ?: 0f
            val rotation = ((roll - geometry.rotation + 540f) % 360f) - 180f
            val mask = MaskComponent(MaskTool.RADIAL,
                listOf(center, MaskPoint(center.x + radiusX, center.y + radiusY)),
                feather = .68f, rotation = rotation)
            autoCenters += center
            layers += MaskLayer(id = FaceLightPrefix + UUID.randomUUID(), name = "面部提亮 ${autoCenters.size}",
                components = listOf(mask), tone = tone)
        }
        FaceLightResult(detected, layers)
    }

private fun faceExposure(pixels: IntArray, width: Int, height: Int, x: Float, y: Float, eyes: Float): ToneParams? {
    val histogram = IntArray(256)
    var count = 0
    val rx = eyes * .64f
    val ry = eyes * .77f
    val step = (eyes / 24f).roundToInt().coerceAtLeast(1)
    val left = (x - rx).toInt().coerceIn(0, width - 1)
    val right = (x + rx).toInt().coerceIn(0, width - 1)
    val top = (y - ry).toInt().coerceIn(0, height - 1)
    val bottom = (y + ry).toInt().coerceIn(0, height - 1)
    for (py in top..bottom step step) for (px in left..right step step) {
        val dx = (px - x) / rx
        val dy = (py - y) / ry
        if (dx * dx + dy * dy > 1f) continue
        val pixel = pixels[py * width + px]
        if (pixel ushr 24 < 128) continue
        val light = (.2126f * (pixel ushr 16 and 255) + .7152f * (pixel ushr 8 and 255) +
            .0722f * (pixel and 255)).toInt().coerceIn(0, 255)
        histogram[light]++
        count++
    }
    if (count == 0) return null
    fun percentile(q: Float): Float {
        var total = 0
        for (i in histogram.indices) {
            total += histogram[i]
            if (total >= q * count) return i / 255f
        }
        return 1f
    }
    return faceLightTone(percentile(.50f), percentile(.94f))
}

private inline fun <T> withPhotoSample(bitmap: Bitmap, geometry: PhotoGeometry, edge: Int,
    operation: (Bitmap, Int, Int) -> T): T {
    val scale = (edge.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    // The JNI renderer needs a software RGBA_8888 source.
    val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    var cropped = source
    return try {
        Canvas(source).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        cropped = PhotoGeometryRenderer.render(source, geometry.normalized())
        operation(cropped, width, height)
    } finally {
        if (cropped !== source) cropped.recycle()
        source.recycle()
    }
}
