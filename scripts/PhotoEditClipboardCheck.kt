package com.dcimgallery.raw.core.tone

fun main() {
    val clipboard = PhotoEditClipboard()
    check(!clipboard.hasValue && clipboard.paste() == null)
    val points = mutableListOf(MaskPoint(.2f, .4f), MaskPoint(.7f, .8f))
    val starts = mutableListOf(1)
    val hsl = MutableList(8) { HslAdjust(hue = .1f * it, sat = -.2f, lum = .3f) }
    val grading = MutableList(4) { ColorGrade(.1f * it, .3f, -.2f) }
    val bw = MutableList(8) { .15f }
    val curvePoints = mutableListOf(CurvePoint(0f, .1f), CurvePoint(.4f, .7f), CurvePoint(1f, 1f))
    val curves = MutableList(4) { ToneCurve(curvePoints) }
    val tone = ToneParams(exposure = .5f, highlights = -.4f, texture = .2f, dehaze = .3f,
        grain = .4f, sharpening = .5f, blackWhite = true, hsl = hsl, grading = grading, bwMix = bw, curves = curves)
    val parts = mutableListOf(MaskComponent(MaskTool.BRUSH, points, strokeStarts = starts, feather = .4f, opacity = .6f))
    val layer = MaskLayer(id = "source", name = "主体", tone = tone, components = parts, enabled = false, inverted = true)
    val layers = mutableListOf(layer)
    clipboard.copy(PhotoEdits(tone, layers))
    check(clipboard.hasValue)
    val first = clipboard.paste()!!
    check(first.tone == tone && first.masks.single().copy(id = layer.id) == layer)
    check(first.masks.single().id != layer.id)
    val second = clipboard.paste()!!
    check(second.masks.single().id != first.masks.single().id)
    // Mutating any source collection after copying must not modify the clipboard.
    points.clear(); starts.clear(); parts.clear(); layers.clear(); hsl.clear(); grading.clear(); bw.clear()
    curvePoints.clear(); curves.clear()
    val independent = clipboard.paste()!!
    check(independent.tone == first.tone)
    check(independent.masks.single().copy(id = first.masks.single().id) == first.masks.single())
    // Reset both scopes together; resetting a target does not erase the clipboard.
    val reset = PhotoEdits()
    check(reset.tone == ToneParams() && reset.masks.isEmpty())
    check(clipboard.paste()!!.masks.isNotEmpty())
    // A new copy replaces the old snapshot, including copying an unedited source.
    clipboard.copy(reset)
    check(clipboard.hasValue && clipboard.paste() == reset)
    println("PASS: complete global/local copy, detached geometry and collections, fresh mask IDs per paste, empty clipboard, neutral overwrite and reset of both scopes")
}
