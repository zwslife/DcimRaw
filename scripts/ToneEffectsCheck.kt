package com.dcimgallery.raw.core.tone

/** Host checks for edit persistence payload and native parameter layout. */
fun main() {
    val neutral = ToneParams()
    check(neutral.isNeutral)
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
