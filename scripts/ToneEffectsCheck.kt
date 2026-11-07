package com.dcimgallery.raw.core.tone

/** Host checks for edit persistence payload and native parameter layout. */
fun main() {
    val neutral = ToneParams()
    check(neutral.isNeutral)
    check(neutral.renderLut().size == 768)
    neutral.renderLut().forEachIndexed { i, v -> check(kotlin.math.abs(v - i % 256) < .001f) }
    val lift = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(.5f, .75f), CurvePoint(1f, 1f)))
    val raised = neutral.copy(curves = listOf(lift, ToneCurve(), ToneCurve(), ToneCurve()))
    check(!raised.isNeutral && raised.renderLut()[128] > 190f)
    val redOnly = neutral.copy(curves = listOf(ToneCurve(), lift, ToneCurve(), ToneCurve())).renderLut()
    check(redOnly[128] > 190f && kotlin.math.abs(redOnly[384] - 128f) < .001f && kotlin.math.abs(redOnly[640] - 128f) < .001f)
    val sampled = lift.lut()
    check(sampled.asList().zipWithNext().all { (a, b) -> a <= b })
    val malformed = ToneCurve(listOf(CurvePoint(Float.NaN, 0f), CurvePoint(1f, Float.POSITIVE_INFINITY)))
    check(malformed.normalized() == ToneCurve())
    val inverse = ToneCurve(listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f))).lut()
    check(inverse.first() == 255f && inverse.last() == 0f)
    check(inverse.asList().zipWithNext().all { (a, b) -> a >= b })
    val close = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1e-30f, 1f), CurvePoint(1f, 1f)))
    check(close.lut().all { it.isFinite() && it in 0f..255f })
    val clipped = ToneCurve(listOf(CurvePoint(.2f, 0f), CurvePoint(.8f, 1f)))
    check(clipped.normalized() == clipped) { "Endpoint x coordinates must survive saving" }
    check(clipped.normalized().normalized() == clipped)
    check(clipped.lut()[20] == 0f && clipped.lut()[235] == 255f)
    check(clipped.lut()[64] < 40f && clipped.lut()[192] > 230f)
    val trimmedIdentity = ToneCurve(listOf(CurvePoint(.2f, .2f), CurvePoint(.8f, .8f)))
    check(!trimmedIdentity.isNeutral) { "Clipped diagonal endpoints are not a neutral curve" }
    check(neutral.effectsArray().size == ToneParams.EFFECT_COUNT)
    check(neutral.withEffects(neutral.effectsArray()) == neutral)
    val rich = neutral.copy(vibrance = .31f, texture = -.42f, clarity = .53f, dehaze = .64f,
        vignette = -.75f, vignetteMidpoint = .22f, vignetteFeather = .33f,
        vignetteRoundness = -.44f, vignetteHighlights = .55f, grain = .66f,
        grainSize = .77f, grainRoughness = .88f, blackWhite = true,
        bwMix = List(8) { (it - 4) / 5f }, sharpening = .91f, sharpeningRadius = 2.3f,
        sharpeningDetail = .47f, sharpeningMasking = .58f,
        grading = List(4) { ColorGrade(it / 4f, .36f, -.24f) }, gradingBalance = -.65f, gradingBlending = .76f)
    check(!rich.isNeutral)
    check(neutral.withEffects(rich.effectsArray()) == rich) { "Saved effects failed to round-trip" }
    check(neutral.withEffects(floatArrayOf()) == neutral) { "Old edits need default auxiliary values" }
    check(neutral.withEffects(floatArrayOf(Float.NaN)).vibrance == 0f)
    check(neutral.withEffects(floatArrayOf(999f)).vibrance == 1f)
    check(neutral.copy(grainSize = 1f, sharpeningRadius = 3f,
        grading = List(4) { ColorGrade(hue = .5f) }, bwMix = List(8) { 1f }).isNeutral)
    listOf(neutral.copy(vibrance = .1f), neutral.copy(texture = .1f), neutral.copy(clarity = .1f),
        neutral.copy(dehaze = .1f), neutral.copy(vignette = .1f), neutral.copy(grain = .1f),
        neutral.copy(blackWhite = true), neutral.copy(sharpening = .1f),
        neutral.copy(grading = listOf(ColorGrade(luminance = .1f)))).forEach { check(!it.isNeutral) }
    // Black/white conversion must retain color information for the eight mixer channels.
    check(neutral.copy(blackWhite = true, saturation = -1f).toMatrix()
        .contentEquals(neutral.copy(blackWhite = true).toMatrix()))
    check(rich.effectsArray()[14] == 2.3f && rich.effectsArray()[34] == rich.bwMix[3])
    println("PASS: all new parameters round-trip; v1 defaults, neutral detection and native layout preserved")
}
