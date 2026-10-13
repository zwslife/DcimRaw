package com.dcimgallery.raw.core.tone

import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin

enum class MaskTool(val label: String) { BRUSH("画笔"), LINEAR("线性渐变"), RADIAL("径向渐变") }
data class MaskPoint(val x: Float, val y: Float)

/** 坐标相对照片本身归一化，独立于屏幕尺寸和照片周围的黑边。 */
data class MaskComponent(
    val tool: MaskTool,
    val points: List<MaskPoint>,
    val subtract: Boolean = false,
    val radius: Float = 0.06f,
    val feather: Float = 0.7f,
    val opacity: Float = 1f,
    val rotation: Float = 0f,
    val strokeStarts: List<Int> = emptyList(),
)

/** 在照片像素空间旋转，避免横竖照片的归一化坐标使角度变形。 */
fun rotateMaskPoint(point: MaskPoint, center: MaskPoint, degrees: Float, width: Float, height: Float): MaskPoint {
    val angle = Math.toRadians(degrees.toDouble())
    val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
    val x = (point.x - center.x) * width; val y = (point.y - center.y) * height
    return MaskPoint(center.x + (x * c - y * s) / width, center.y + (x * s + y * c) / height)
}

data class MaskLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val components: List<MaskComponent> = emptyList(),
    val tone: ToneParams = ToneParams(),
    val inverted: Boolean = false,
    val enabled: Boolean = true,
)

data class MaskViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float) = x >= left && x <= left + width && y >= top && y <= top + height
    fun normalize(x: Float, y: Float) = MaskPoint(
        ((x - left) / width).coerceIn(0f, 1f), ((y - top) / height).coerceIn(0f, 1f),
    )
    companion object {
        fun fit(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): MaskViewport {
            val scale = min(viewWidth / imageWidth.coerceAtLeast(1), viewHeight / imageHeight.coerceAtLeast(1))
            val w = (imageWidth * scale).coerceAtLeast(1f)
            val h = (imageHeight * scale).coerceAtLeast(1f)
            return MaskViewport((viewWidth - w) / 2f, (viewHeight - h) / 2f, w, h)
        }
    }
}

/** 预览、红色叠加和导出共用同一份覆盖率算法。 */
object MaskRasterizer {
    fun rasterize(layer: MaskLayer, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0)
        val result = FloatArray(width * height)
        val part = FloatArray(result.size)
        for (component in layer.components) {
            part.fill(0f)
            when (component.tool) {
                MaskTool.BRUSH -> brush(component, width, height, part)
                MaskTool.LINEAR -> gradient(component, width, height, part, false)
                MaskTool.RADIAL -> gradient(component, width, height, part, true)
            }
            val opacity = component.opacity.coerceIn(0f, 1f)
            for (i in result.indices) {
                val weight = part[i] * opacity
                result[i] = if (component.subtract) result[i] * (1f - weight) else max(result[i], weight)
            }
        }
        return ByteArray(result.size) { i ->
            val v = if (layer.inverted) 1f - result[i] else result[i]
            (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun gradient(c: MaskComponent, w: Int, h: Int, out: FloatArray, radial: Boolean) {
        if (c.points.size < 2) return
        val a = c.points.first()
        val b = c.points.last()
        val ax = a.x * w; val ay = a.y * h
        val dx = (b.x - a.x) * w; val dy = (b.y - a.y) * h
        val length2 = dx * dx + dy * dy
        if (length2 < 1f) return
        val rx = abs(dx).coerceAtLeast(1f); val ry = abs(dy).coerceAtLeast(1f)
        val feather = c.feather.coerceIn(0.001f, 1f)
        val angle = Math.toRadians(c.rotation.toDouble())
        val cosine = cos(angle).toFloat(); val sine = sin(angle).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            val px = x + 0.5f - ax; val py = y + 0.5f - ay
            val v = if (radial) {
                val localX = px * cosine + py * sine
                val localY = -px * sine + py * cosine
                val d = sqrt(localX * localX / (rx * rx) + localY * localY / (ry * ry))
                smooth((1f - d) / feather)
            } else {
                val t = (px * dx + py * dy) / length2
                1f - smooth((t - (1f - feather) * 0.5f) / feather)
            }
            out[y * w + x] = v
        }
    }

    private fun brush(c: MaskComponent, w: Int, h: Int, out: FloatArray) {
        if (c.points.isEmpty()) return
        val radius = (c.radius * min(w, h)).coerceAtLeast(0.5f)
        val feather = c.feather.coerceIn(0.001f, 1f)
        val points = c.points
        val breaks = c.strokeStarts.toHashSet()
        for (index in points.indices) {
            val a = points[index]
            val next = min(index + 1, points.lastIndex)
            val b = if (next in breaks) a else points[next]
            val ax = a.x * w; val ay = a.y * h; val bx = b.x * w; val by = b.y * h
            val dx = bx - ax; val dy = by - ay; val d2 = dx * dx + dy * dy
            val x0 = floor(min(ax, bx) - radius).toInt().coerceIn(0, w - 1)
            val x1 = ceil(max(ax, bx) + radius).toInt().coerceIn(0, w - 1)
            val y0 = floor(min(ay, by) - radius).toInt().coerceIn(0, h - 1)
            val y1 = ceil(max(ay, by) + radius).toInt().coerceIn(0, h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val px = x + 0.5f - ax; val py = y + 0.5f - ay
                val t = if (d2 > 0f) ((px * dx + py * dy) / d2).coerceIn(0f, 1f) else 0f
                val ex = px - t * dx; val ey = py - t * dy
                val v = smooth((1f - sqrt(ex * ex + ey * ey) / radius) / feather)
                val i = y * w + x
                out[i] = max(out[i], v)
            }
        }
    }
}
