package com.dcimgallery.raw.core.tone

import com.dcimgallery.raw.core.geometry.PhotoGeometry

/** Lift more in darker faces and use the bright tail as headroom. */
internal fun faceLightTone(median: Float, upper: Float): ToneParams? {
    if (!median.isFinite() || !upper.isFinite()) return null
    val room = ((.99f - upper) / .30f).coerceIn(0f, 1f)
    val exposure = ((.74f - median) * .88f).coerceIn(0f, .42f) * room
    if (exposure < .025f) return null
    return ToneParams(exposure = exposure, shadows = exposure * .14f,
        highlights = if (upper > .70f) -exposure * .28f else 0f)
}

/** Convert the crop's pixel coordinates back into the original photo's mask space. */
internal fun faceSourcePoint(x: Float, y: Float, width: Int, height: Int, geometry: PhotoGeometry): MaskPoint {
    val upright = geometry.frame(width, height)
    val rotated = MaskPoint(.5f + (x + (geometry.crop.left - .5f) * upright.width) / width,
        .5f + (y + (geometry.crop.top - .5f) * upright.height) / height)
    val source = rotateMaskPoint(rotated, MaskPoint(.5f, .5f), -geometry.rotation, width.toFloat(), height.toFloat())
    return MaskPoint(source.x.coerceIn(0f, 1f), source.y.coerceIn(0f, 1f))
}
