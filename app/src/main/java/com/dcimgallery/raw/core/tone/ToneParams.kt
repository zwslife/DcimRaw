package com.dcimgallery.raw.core.tone

import kotlin.math.exp
import kotlin.math.pow

data class CurvePoint(val x: Float, val y: Float)

/** Normalized point curve. Harmonic tangents avoid overshoot between control points. */
data class ToneCurve(val points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))) {
    val isNeutral: Boolean get() = normalized().points.let {
        it.first() == CurvePoint(0f, 0f) && it.last() == CurvePoint(1f, 1f) && it.all { point -> point.x == point.y }
    }

    fun normalized(): ToneCurve {
        val valid = points.filter { it.x.isFinite() && it.y.isFinite() }
            .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.x }.distinctBy { it.x }.take(16)
        if (valid.size < 2) return ToneCurve()
        val spaced = valid
            .fold(emptyList<CurvePoint>()) { kept, point ->
                if (kept.isEmpty() || point.x - kept.last().x >= .004f) kept + point else kept
            }
        return if (spaced.size < 2) ToneCurve() else ToneCurve(spaced)
    }

    fun lut(): FloatArray {
        val p = normalized().points
        val h = FloatArray(p.size - 1) { p[it + 1].x - p[it].x }
        val d = FloatArray(h.size) { (p[it + 1].y - p[it].y) / h[it] }
        val m = FloatArray(p.size)
        m[0] = d.first(); m[m.lastIndex] = d.last()
        for (i in 1 until p.lastIndex) {
            if (d[i - 1] * d[i] > 0f) {
                val w1 = 2f * h[i] + h[i - 1]; val w2 = h[i] + 2f * h[i - 1]
                m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }
        var segment = 0
        return FloatArray(256) { index ->
            val x = index / 255f
            while (segment < p.size - 2 && x > p[segment + 1].x) segment++
            val t = ((x - p[segment].x) / h[segment]).coerceIn(0f, 1f)
            val t2 = t * t; val t3 = t2 * t
            ((2*t3 - 3*t2 + 1)*p[segment].y + (t3 - 2*t2 + t)*h[segment]*m[segment] +
                (-2*t3 + 3*t2)*p[segment + 1].y + (t3 - t2)*h[segment]*m[segment + 1])
                .coerceIn(0f, 1f) * 255f
        }
    }
}

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

/** 色彩分级：色相 0..1 为一周，饱和度 0..1，明度 -1..1。 */
data class ColorGrade(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    val isNeutral: Boolean get() = saturation == 0f && luminance == 0f
}

/**
 * Lr 风格调色参数（全量版）。
 * 全局与蒙版共用参数、保存格式和 native 管线；辅助项使用各自的默认值。
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
    val vibrance: Float = 0f,
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignette: Float = 0f,
    val vignetteMidpoint: Float = 0.5f,
    val vignetteFeather: Float = 0.5f,
    val vignetteRoundness: Float = 0f,
    val vignetteHighlights: Float = 0f,
    val grain: Float = 0f,
    val grainSize: Float = 0.25f,
    val grainRoughness: Float = 0.5f,
    val blackWhite: Boolean = false,
    val bwMix: List<Float> = List(HSL_COUNT) { 0f },
    val sharpening: Float = 0f,
    /** 原图像素半径，预览按缩放比例换算。 */
    val sharpeningRadius: Float = 1f,
    val sharpeningDetail: Float = 0.25f,
    val sharpeningMasking: Float = 0f,
    /** 阴影、中间调、高光、全局。 */
    val grading: List<ColorGrade> = List(4) { ColorGrade() },
    val gradingBalance: Float = 0f,
    val gradingBlending: Float = 0.5f,
    /** Master RGB, red, green, blue point curves. */
    val curves: List<ToneCurve> = List(4) { ToneCurve() },
) {
    val isNeutral: Boolean
        get() = exposure == 0f && contrast == 0f && temperature == 0f &&
            tint == 0f && saturation == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f && hsl.all { it.isNeutral } &&
            vibrance == 0f && texture == 0f && clarity == 0f && dehaze == 0f &&
            vignette == 0f && grain == 0f && !blackWhite && sharpening == 0f && grading.all { it.isNeutral } &&
            curves.all { it.isNeutral }

    /** 基础五项的 4x5 颜色矩阵（与预览 GPU/导出共用） */
    fun toMatrix(): FloatArray {
        val ex = 2.0.pow(exposure.toDouble()).toFloat()
        val k = 1f + contrast * 0.9f
        val rs = 1f + temperature * 0.15f
        val gs = 1f + tint * 0.10f
        val bs = 1f - temperature * 0.15f
        val s = if (blackWhite) 1f else 1f + saturation
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
        val effects: FloatArray,
    )

    /** 与 tone_engine.h 的 EffectIndex 一一对应，预览/定稿/蒙版使用同一协议。 */
    fun effectsArray(): FloatArray = FloatArray(EFFECT_COUNT).also { out ->
        floatArrayOf(vibrance, texture, clarity, dehaze, vignette, vignetteMidpoint,
            vignetteFeather, vignetteRoundness, vignetteHighlights, grain, grainSize,
            grainRoughness, if (blackWhite) 1f else 0f, sharpening, sharpeningRadius,
            sharpeningDetail, sharpeningMasking, gradingBalance, gradingBlending).copyInto(out)
        repeat(4) { i ->
            val grade = grading.getOrElse(i) { ColorGrade() }
            out[19 + i * 3] = grade.hue
            out[20 + i * 3] = grade.saturation
            out[21 + i * 3] = grade.luminance
        }
        repeat(HSL_COUNT) { out[31 + it] = bwMix.getOrElse(it) { 0f } }
    }

    /** Compose base tone + master + channel curves once per edit, never per pixel. */
    fun renderLut(): FloatArray {
        val base = toneLut()
        val tables = List(4) { curves.getOrElse(it) { ToneCurve() }.lut() }
        fun sample(table: FloatArray, value: Float): Float {
            val x = value.coerceIn(0f, 255f); val i = x.toInt()
            return table[i] + (table[(i + 1).coerceAtMost(255)] - table[i]) * (x - i)
        }
        return FloatArray(LUT_SIZE) { i -> sample(tables[i / 256 + 1], sample(tables[0], base[i % 256])) }
    }

    fun toRenderData(): RenderData = RenderData(toMatrix(), renderLut(), hslArray(), effectsArray())

    /** 老照片缺失的项采用默认值；防止损坏的存档把 NaN 或越界值传入 native。 */
    fun withEffects(values: FloatArray): ToneParams {
        val defaults = ToneParams().effectsArray()
        fun v(i: Int, min: Float = 0f, max: Float = 1f): Float =
            (values.getOrNull(i)?.takeIf { it.isFinite() } ?: defaults[i]).coerceIn(min, max)
        return copy(vibrance = v(0, -1f), texture = v(1, -1f), clarity = v(2, -1f),
            dehaze = v(3, -1f), vignette = v(4, -1f), vignetteMidpoint = v(5),
            vignetteFeather = v(6), vignetteRoundness = v(7, -1f), vignetteHighlights = v(8),
            grain = v(9), grainSize = v(10), grainRoughness = v(11), blackWhite = v(12) >= 0.5f,
            sharpening = v(13), sharpeningRadius = v(14, 0.5f, 3f), sharpeningDetail = v(15),
            sharpeningMasking = v(16), gradingBalance = v(17, -1f), gradingBlending = v(18),
            grading = List(4) { ColorGrade(v(19 + it * 3), v(20 + it * 3), v(21 + it * 3, -1f)) },
            bwMix = List(HSL_COUNT) { v(31 + it, -1f) })
    }

    companion object {
        const val HSL_COUNT = 8
        const val EFFECT_COUNT = 39
        const val LUT_SIZE = 768

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
