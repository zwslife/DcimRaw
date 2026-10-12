package com.dcimgallery.raw.core.tone

import kotlin.math.exp
import kotlin.math.pow

/** 单个 HSL 颜色分区的调整量。 */
data class HslAdjust(
    /** 色相偏移（-1..+1，映射 ±60°） */
    val hue: Float = 0f,
    /** 饱和度（-1..+1，1 = 加倍） */
    val sat: Float = 0f,
    /** 明亮度（-1..+1） */
    val lum: Float = 0f,
) {
    val isNeutral: Boolean get() = hue == 0f && sat == 0f && lum == 0f
}

/**
 * Lr 风格调色参数（全量版）。
 * 全部以 0 为中性点；拖动预览与导出共用同一份渲染数据（toRenderData），
 * 由 native 渲染管线逐像素应用：矩阵 → 亮度曲线 → HSL。
 */
data class ToneParams(
    /** 曝光（EV 档，-2..+2） */
    val exposure: Float = 0f,
    /** 对比度（-1..+1） */
    val contrast: Float = 0f,
    /** 色温（-1 冷 .. +1 暖） */
    val temperature: Float = 0f,
    /** 色调（-1 绿 .. +1 品红） */
    val tint: Float = 0f,
    /** 饱和度（-1..+1） */
    val saturation: Float = 0f,
    /** 高光（-1..+1，作用于 0.75 亮度带） */
    val highlights: Float = 0f,
    /** 阴影（-1..+1，作用于 0.25 亮度带） */
    val shadows: Float = 0f,
    /** 白色（-1..+1，作用于 0.82+ 亮度带） */
    val whites: Float = 0f,
    /** 黑色（-1..+1，作用于 0.18- 亮度带） */
    val blacks: Float = 0f,
    /** HSL 八分区：红/橙/黄/绿/青/蓝/紫/品红 */
    val hsl: List<HslAdjust> = List(HSL_COUNT) { HslAdjust() },
) {
    val isNeutral: Boolean
        get() = exposure == 0f && contrast == 0f && temperature == 0f &&
            tint == 0f && saturation == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f && hsl.all { it.isNeutral }

    /** 基础五项的 4x5 颜色矩阵（与预览 GPU/导出共用） */
    fun toMatrix(): FloatArray {
        val ex = 2.0.pow(exposure.toDouble()).toFloat()
        val k = 1f + contrast * 0.9f
        val rs = 1f + temperature * 0.15f
        val gs = 1f + tint * 0.10f
        val bs = 1f - temperature * 0.15f
        val s = 1f + saturation
        val wr = 0.299f
        val wg = 0.587f
        val wb = 0.114f
        val iw = 1f - s
        val dr = ex * k * rs
        val dg = ex * k * gs
        val db = ex * k * bs
        val off = 128f * (1f - k)
        return floatArrayOf(
            (s + iw * wr) * dr, (iw * wg) * dg, (iw * wb) * db, 0f, off,
            (iw * wr) * dr, (s + iw * wg) * dg, (iw * wb) * db, 0f, off,
            (iw * wr) * dr, (iw * wg) * dg, (s + iw * wb) * db, 0f, off,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** 高光/阴影/白色/黑色合成的亮度曲线（256 级，0..255 → 0..255）。 */
    fun toneLut(): FloatArray {
        val lut = FloatArray(256)
        for (i in 0..255) {
            val t = i / 255f
            val adj =
                shadows * gauss(t, 0.25f, 0.16f) * 0.35f +
                    highlights * gauss(t, 0.75f, 0.16f) * 0.35f +
                    whites * smoothstep(0.82f, 1f, t) * 0.30f +
                    blacks * (1f - smoothstep(0f, 0.18f, t)) * 0.30f
            lut[i] = ((t + adj) * 255f).coerceIn(0f, 255f)
        }
        return lut
    }

    /** HSL 调整打包（8 区 × [色相°, 饱和度Δ, 亮度Δ]）。 */
    fun hslArray(): FloatArray {
        val out = FloatArray(HSL_COUNT * 3)
        for (i in 0 until HSL_COUNT) {
            val a = hsl.getOrElse(i) { HslAdjust() }
            out[i * 3] = a.hue * 60f      // ±60°
            out[i * 3 + 1] = a.sat        // 倍率增量
            out[i * 3 + 2] = a.lum * 0.35f
        }
        return out
    }

    /** 传给 native 渲染管线的全部数据。 */
    data class RenderData(
        val matrix: FloatArray,
        val lut: FloatArray,
        val hsl: FloatArray,
    )

    fun toRenderData(): RenderData = RenderData(toMatrix(), toneLut(), hslArray())

    companion object {
        const val HSL_COUNT = 8

        /** 八分区中心色相（红橙黄绿青蓝紫品红） */
        val HSL_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 315f)
        val HSL_NAMES = listOf("红", "橙", "黄", "绿", "青", "蓝", "紫", "品红")
        val HSL_COLORS = longArrayOf(
            0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50,
            0xFF00BCD4, 0xFF2196F3, 0xFF9C27B0, 0xFFE91E63,
        )

        private fun gauss(x: Float, mu: Float, sigma: Float): Float {
            val d = (x - mu) / sigma
            return exp(-d * d / 2f)
        }

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
