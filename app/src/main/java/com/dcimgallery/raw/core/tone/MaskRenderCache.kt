package com.dcimgallery.raw.core.tone

import android.graphics.Bitmap
import com.dcimgallery.raw.core.raw.RawDecoder
import kotlin.math.roundToInt

/** 调色拖动只更新矩阵，选区未改变时复用已栅格化的覆盖率。仅在单一渲染协程内使用。 */
class MaskRenderCache {
    private data class Key(val parts: List<MaskComponent>, val inverted: Boolean, val w: Int, val h: Int)
    private val coverage = LinkedHashMap<Key, ByteArray>()
    private var packedKeys = emptyList<Key>()
    private var packedCoverage = ByteArray(0)

    fun apply(bitmap: Bitmap, layers: List<MaskLayer>, previewHandle: Long = 0L): Boolean {
        val active = layers.filter { it.enabled && !it.tone.isNeutral && (it.components.isNotEmpty() || it.inverted) }
        if (active.isEmpty()) return true
        val scale = (1024f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val keys = active.map { Key(it.components, it.inverted, w, h) }
        if (keys != packedKeys) {
            packedCoverage = ByteArray(w * h * active.size)
            active.forEachIndexed { i, layer ->
                val pixels = coverage.getOrPut(keys[i]) { MaskRasterizer.rasterize(layer, w, h) }
                pixels.copyInto(packedCoverage, i * w * h)
            }
            coverage.keys.retainAll(keys.toSet())
            packedKeys = keys
        }
        val matrices = FloatArray(20 * active.size)
        val luts = FloatArray(256 * active.size)
        val hsls = FloatArray(24 * active.size)
        val effects = FloatArray(ToneParams.EFFECT_COUNT * active.size)
        active.forEachIndexed { i, layer ->
            val data = layer.tone.toRenderData()
            data.matrix.copyInto(matrices, i * 20)
            data.lut.copyInto(luts, i * 256)
            data.hsl.copyInto(hsls, i * 24)
            data.effects.copyInto(effects, i * ToneParams.EFFECT_COUNT)
        }
        return RawDecoder.applyMasks(bitmap, matrices, luts, hsls, effects, packedCoverage, w, h, previewHandle)
    }

    fun clear() {
        coverage.clear()
        packedKeys = emptyList()
        packedCoverage = ByteArray(0)
    }
}
