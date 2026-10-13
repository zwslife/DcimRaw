package com.dcimgallery.raw.ui

import com.dcimgallery.raw.core.tone.HslAdjust
import com.dcimgallery.raw.core.tone.ColorGrade
import com.dcimgallery.raw.core.tone.ToneParams
import java.util.Locale
import kotlin.math.roundToInt

// ---- 参数定义 ----

internal data class ChipDef(
    val id: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val format: (Float) -> String,
    val get: (ToneParams) -> Float,
    val set: (ToneParams, Float) -> ToneParams,
    val default: Float = 0f,
)

internal fun toneControls(section: Int, hslIndex: Int, gradeIndex: Int, blackWhite: Boolean): List<ChipDef> {
    return when (section) {
        0 -> listOf(
            ChipDef("曝光", "曝光", -2f..2f, ::signed, { it.exposure }, { p, v -> p.copy(exposure = v) }),
            ChipDef("对比度", "对比度", -1f..1f, ::pct, { it.contrast }, { p, v -> p.copy(contrast = v) }),
            ChipDef("高光", "高光", -1f..1f, ::pct, { it.highlights }, { p, v -> p.copy(highlights = v) }),
            ChipDef("阴影", "阴影", -1f..1f, ::pct, { it.shadows }, { p, v -> p.copy(shadows = v) }),
            ChipDef("白色", "白色", -1f..1f, ::pct, { it.whites }, { p, v -> p.copy(whites = v) }),
            ChipDef("黑色", "黑色", -1f..1f, ::pct, { it.blacks }, { p, v -> p.copy(blacks = v) }),
        )
        1 -> listOf(
            ChipDef("色温", "色温", -1f..1f, ::pct, { it.temperature }, { p, v -> p.copy(temperature = v) }),
            ChipDef("色调", "色调", -1f..1f, ::pct, { it.tint }, { p, v -> p.copy(tint = v) }),
            ChipDef("饱和度", "饱和度", -1f..1f, ::pct, { it.saturation }, { p, v -> p.copy(saturation = v) }),
            ChipDef("自然饱和度", "自然饱和度", -1f..1f, ::pct, { it.vibrance }, { p, v -> p.copy(vibrance = v) }),
        )
        3 -> listOf(
            ChipDef("纹理", "纹理", -1f..1f, ::pct, { it.texture }, { p, v -> p.copy(texture = v) }),
            ChipDef("清晰度", "清晰度", -1f..1f, ::pct, { it.clarity }, { p, v -> p.copy(clarity = v) }),
            ChipDef("去朦胧", "去朦胧", -1f..1f, ::pct, { it.dehaze }, { p, v -> p.copy(dehaze = v) }),
            ChipDef("暗角", "暗角", -1f..1f, ::pct, { it.vignette }, { p, v -> p.copy(vignette = v) }),
            ChipDef("暗角中点", "暗角·中点", 0f..1f, ::unsignedPct, { it.vignetteMidpoint }, { p, v -> p.copy(vignetteMidpoint = v) }, 0.5f),
            ChipDef("暗角羽化", "暗角·羽化", 0f..1f, ::unsignedPct, { it.vignetteFeather }, { p, v -> p.copy(vignetteFeather = v) }, 0.5f),
            ChipDef("暗角圆度", "暗角·圆度", -1f..1f, ::pct, { it.vignetteRoundness }, { p, v -> p.copy(vignetteRoundness = v) }),
            ChipDef("暗角高光", "暗角·高光保护", 0f..1f, ::unsignedPct, { it.vignetteHighlights }, { p, v -> p.copy(vignetteHighlights = v) }),
            ChipDef("颗粒", "颗粒", 0f..1f, ::unsignedPct, { it.grain }, { p, v -> p.copy(grain = v) }),
            ChipDef("颗粒大小", "颗粒·大小", 0f..1f, ::unsignedPct, { it.grainSize }, { p, v -> p.copy(grainSize = v) }, 0.25f),
            ChipDef("颗粒粗糙度", "颗粒·粗糙度", 0f..1f, ::unsignedPct, { it.grainRoughness }, { p, v -> p.copy(grainRoughness = v) }, 0.5f),
        )
        4 -> {
            fun get(p: ToneParams) = p.grading.getOrElse(gradeIndex) { ColorGrade() }
            fun update(p: ToneParams, grade: ColorGrade) = p.copy(grading = List(4) {
                if (it == gradeIndex) grade else p.grading.getOrElse(it) { ColorGrade() }
            })
            listOf(
                ChipDef("grade_hue", "色相", 0f..1f, { "${(it * 360).roundToInt()}°" }, { get(it).hue },
                    { p, v -> update(p, get(p).copy(hue = v)) }),
                ChipDef("grade_sat", "饱和度", 0f..1f, ::unsignedPct, { get(it).saturation },
                    { p, v -> update(p, get(p).copy(saturation = v)) }),
                ChipDef("grade_lum", "明度", -1f..1f, ::pct, { get(it).luminance },
                    { p, v -> update(p, get(p).copy(luminance = v)) }),
                ChipDef("grade_balance", "平衡", -1f..1f, ::pct, { it.gradingBalance }, { p, v -> p.copy(gradingBalance = v) }),
                ChipDef("grade_blending", "混合", 0f..1f, ::unsignedPct, { it.gradingBlending }, { p, v -> p.copy(gradingBlending = v) }, 0.5f),
            )
        }
        5 -> listOf(
            ChipDef("锐化", "锐化·强度", 0f..1f, ::unsignedPct, { it.sharpening }, { p, v -> p.copy(sharpening = v) }),
            ChipDef("锐化半径", "锐化·半径", 0.5f..3f, { String.format(Locale.US, "%.1f px", it) }, { it.sharpeningRadius }, { p, v -> p.copy(sharpeningRadius = v) }, 1f),
            ChipDef("锐化细节", "锐化·细节", 0f..1f, ::unsignedPct, { it.sharpeningDetail }, { p, v -> p.copy(sharpeningDetail = v) }, 0.25f),
            ChipDef("锐化蒙版", "锐化·边缘蒙版", 0f..1f, ::unsignedPct, { it.sharpeningMasking }, { p, v -> p.copy(sharpeningMasking = v) }),
        )
        else -> {
            val name = ToneParams.HSL_NAMES[hslIndex]
            if (blackWhite) listOf(ChipDef("bw_mix", "黑白明度·$name", -1f..1f, ::pct,
                { it.bwMix.getOrElse(hslIndex) { 0f } }, { p, v -> p.copy(bwMix = List(ToneParams.HSL_COUNT) {
                    if (it == hslIndex) v else p.bwMix.getOrElse(it) { 0f }
                }) })) else listOf(
                ChipDef("hsl_h", "色相·$name", -1f..1f, ::pct,
                    { it.hsl.getOrElse(hslIndex) { HslAdjust() }.hue },
                    { p, v -> p.copy(hsl = p.hsl.replaced(hslIndex, p.hsl.getOrElse(hslIndex) { HslAdjust() }.copy(hue = v))) }),
                ChipDef("hsl_s", "饱和·$name", -1f..1f, ::pct,
                    { it.hsl.getOrElse(hslIndex) { HslAdjust() }.sat },
                    { p, v -> p.copy(hsl = p.hsl.replaced(hslIndex, p.hsl.getOrElse(hslIndex) { HslAdjust() }.copy(sat = v))) }),
                ChipDef("hsl_l", "明度·$name", -1f..1f, ::pct,
                    { it.hsl.getOrElse(hslIndex) { HslAdjust() }.lum },
                    { p, v -> p.copy(hsl = p.hsl.replaced(hslIndex, p.hsl.getOrElse(hslIndex) { HslAdjust() }.copy(lum = v))) }),
            )
        }
    }
}

private fun List<HslAdjust>.replaced(index: Int, value: HslAdjust): List<HslAdjust> =
    List(ToneParams.HSL_COUNT) { i -> if (i == index) value else getOrElse(i) { HslAdjust() } }

private fun signed(v: Float): String =
    String.format(Locale.US, if (v >= 0) "+%.2f" else "%.2f", v)

private fun pct(v: Float): String =
    (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }

private fun unsignedPct(v: Float): String = (v * 100).roundToInt().toString()
