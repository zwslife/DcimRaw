package com.dcimgallery.raw.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Crop coordinates belong to the upright, corner-free frame after rotation. */
data class CropRect(val left: Float = 0f, val top: Float = 0f,
    val right: Float = 1f, val bottom: Float = 1f) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f

    fun normalized(): CropRect {
        if (listOf(left, top, right, bottom).any { !it.isFinite() }) return CropRect()
        val l = min(left, right).coerceIn(0f, 1f - MIN_SIZE)
        val t = min(top, bottom).coerceIn(0f, 1f - MIN_SIZE)
        return CropRect(l, t, max(left, right).coerceIn(l + MIN_SIZE, 1f),
            max(top, bottom).coerceIn(t + MIN_SIZE, 1f))
    }

    fun moved(dx: Float, dy: Float): CropRect {
        val x = dx.coerceIn(-left, 1f - right)
        val y = dy.coerceIn(-top, 1f - bottom)
        return CropRect(left + x, top + y, right + x, bottom + y)
    }

    /** ratio is width / height in normalized frame coordinates; zero means freeform. */
    fun withAspect(ratio: Float): CropRect {
        if (!ratio.isFinite() || ratio <= 0f) return this
        val w = min(width, height * ratio)
        val h = w / ratio
        return CropRect(centerX - w / 2, centerY - h / 2, centerX + w / 2, centerY + h / 2)
    }

    /** Handles: TL, TR, BR, BL, left, top, right, bottom, move. */
    fun dragged(handle: Int, dx: Float, dy: Float, ratio: Float): CropRect {
        if (handle == 8) return moved(dx, dy)
        if (handle !in 0..7) return this
        if (!ratio.isFinite() || ratio <= 0f) {
            val l = if (handle in listOf(0, 3, 4)) (left + dx).coerceIn(0f, right - MIN_SIZE) else left
            val r = if (handle in listOf(1, 2, 6)) (right + dx).coerceIn(left + MIN_SIZE, 1f) else right
            val t = if (handle in listOf(0, 1, 5)) (top + dy).coerceIn(0f, bottom - MIN_SIZE) else top
            val b = if (handle in listOf(2, 3, 7)) (bottom + dy).coerceIn(top + MIN_SIZE, 1f) else bottom
            return CropRect(l, t, r, b)
        }
        val minW = max(MIN_SIZE, MIN_SIZE * ratio)
        if (handle < 4) {
            val sx = if (handle == 1 || handle == 2) 1 else -1
            val sy = if (handle >= 2) 1 else -1
            val ax = if (sx > 0) left else right
            val ay = if (sy > 0) top else bottom
            val desired = if (abs(dx) >= abs(dy * ratio)) width + dx * sx else (height + dy * sy) * ratio
            val limit = min(if (sx > 0) 1f - ax else ax, (if (sy > 0) 1f - ay else ay) * ratio)
            val w = desired.coerceIn(min(minW, limit), limit)
            val x = ax + sx * w
            val y = ay + sy * w / ratio
            return CropRect(min(ax, x), min(ay, y), max(ax, x), max(ay, y))
        }
        if (handle == 4 || handle == 6) {
            val sx = if (handle == 6) 1 else -1
            val ax = if (sx > 0) left else right
            val limit = min(if (sx > 0) 1f - ax else ax, 2f * min(centerY, 1f - centerY) * ratio)
            val w = (width + dx * sx).coerceIn(min(minW, limit), limit)
            val x = ax + sx * w
            return CropRect(min(ax, x), centerY - w / ratio / 2f, max(ax, x), centerY + w / ratio / 2f)
        }
        val sy = if (handle == 7) 1 else -1
        val ay = if (sy > 0) top else bottom
        val limit = min(if (sy > 0) 1f - ay else ay, 2f * min(centerX, 1f - centerX) / ratio)
        val h = (height + dy * sy).coerceIn(min(max(MIN_SIZE, MIN_SIZE / ratio), limit), limit)
        val y = ay + sy * h
        return CropRect(centerX - h * ratio / 2f, min(ay, y), centerX + h * ratio / 2f, max(ay, y))
    }

    companion object { const val MIN_SIZE = .02f }
}

data class GeometryFrame(val width: Float, val height: Float)

data class PhotoGeometry(val quarterTurns: Int = 0, val straighten: Float = 0f,
    val crop: CropRect = CropRect()) {
    val rotation get() = quarterTurns * 90f + straighten
    val isIdentity get() = quarterTurns == 0 && straighten == 0f && crop == CropRect()

    fun normalized() = PhotoGeometry(((quarterTurns % 4) + 4) % 4,
        if (straighten.isFinite()) straighten.coerceIn(-45f, 45f) else 0f, crop.normalized())

    fun rotatedBy(turns: Int): PhotoGeometry {
        var rect = crop
        repeat(((turns % 4) + 4) % 4) {
            rect = CropRect(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
        }
        return copy(quarterTurns = ((quarterTurns + turns) % 4 + 4) % 4, crop = rect)
    }

    /** Inscribed frame keeps the original aspect and excludes transparent rotated corners. */
    fun frame(sourceWidth: Int, sourceHeight: Int): GeometryFrame {
        val w = (if (quarterTurns % 2 == 0) sourceWidth else sourceHeight).toFloat().coerceAtLeast(1f)
        val h = (if (quarterTurns % 2 == 0) sourceHeight else sourceWidth).toFloat().coerceAtLeast(1f)
        val angle = Math.toRadians(straighten.toDouble())
        val c = abs(cos(angle)).toFloat()
        val s = abs(sin(angle)).toFloat()
        val zoom = max(c + h / w * s, c + w / h * s)
        return GeometryFrame(w / zoom, h / zoom)
    }

    fun outputSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val frame = frame(sourceWidth, sourceHeight)
        return max(1, (frame.width * crop.width + .0001f).toInt()) to
            max(1, (frame.height * crop.height + .0001f).toInt())
    }
}
