package com.dcimgallery.raw.ui

internal fun editorSliderValue(x: Float, width: Float, inset: Float, range: ClosedFloatingPointRange<Float>): Float {
    val fraction = ((x - inset) / (width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return range.start + fraction * (range.endInclusive - range.start)
}
