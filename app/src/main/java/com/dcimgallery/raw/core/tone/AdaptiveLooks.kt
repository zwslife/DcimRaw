package com.dcimgallery.raw.core.tone

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

private val Quantiles = floatArrayOf(.02f, .08f, .2f, .35f, .5f, .65f, .8f, .92f, .98f)

/** Quantiles represent luminance/chroma histograms; the bands describe shadow, midtone and highlight colors. */
internal data class LookProfile(
    val light: FloatArray,
    val chroma: FloatArray,
    val bands: List<LookColor>,
    val hues: FloatArray? = null,
    val skin: LookColor? = null,
)

internal data class LookColor(val redBlue: Float, val green: Float)

internal data class AdaptiveLook(
    val label: String, val portrait: Boolean, val swatches: List<Long>, val target: LookProfile,
)

internal object AdaptiveLooks {
    val presets = listOf(
        look("青橙", false, 0xFF216579, 0xFFEFB17A, floatArrayOf(.06f,.12f,.23f,.36f,.49f,.63f,.76f,.88f,.96f),
            .36f, LookColor(-.14f,.04f), LookColor(.04f,-.01f), LookColor(.19f,-.03f),
            hues = floatArrayOf(.08f,.24f,.12f,.05f,.24f,.21f,.03f,.03f)),
        look("蓝调", false, 0xFF1A3158, 0xFF6D9CCA, floatArrayOf(.03f,.07f,.14f,.22f,.33f,.44f,.57f,.75f,.92f),
            .31f, LookColor(-.17f,.00f), LookColor(-.13f,-.01f), LookColor(.04f,.01f),
            hues = floatArrayOf(.04f,.11f,.07f,.03f,.16f,.48f,.08f,.03f)),
        look("黑金", false, 0xFF242126, 0xFFD9A34C, floatArrayOf(.02f,.04f,.10f,.18f,.29f,.43f,.63f,.82f,.95f),
            .27f, LookColor(.01f,.00f), LookColor(.14f,.03f), LookColor(.29f,.06f),
            hues = floatArrayOf(.12f,.37f,.36f,.05f,.025f,.035f,.02f,.02f)),
        look("复古胶片", false, 0xFF7B815D, 0xFFDBAD87, floatArrayOf(.12f,.17f,.27f,.37f,.48f,.59f,.69f,.81f,.91f),
            .22f, LookColor(.04f,.02f), LookColor(.11f,.01f), LookColor(.13f,.03f)),
        look("日系清透", true, 0xFFB0C8B2, 0xFFF3DFC8, floatArrayOf(.13f,.22f,.37f,.49f,.61f,.72f,.81f,.91f,.97f),
            .23f, LookColor(-.03f,.02f), LookColor(.06f,.00f), LookColor(.04f,.01f)),
        look("暖调人像", true, 0xFFB97954, 0xFFF2C99A, floatArrayOf(.08f,.16f,.29f,.41f,.53f,.65f,.76f,.87f,.96f),
            .29f, LookColor(.04f,.00f), LookColor(.15f,.00f), LookColor(.16f,.03f)),
        look("奶油柔光", true, 0xFFE8C2AA, 0xFFF6EADA, floatArrayOf(.17f,.26f,.40f,.52f,.63f,.73f,.83f,.92f,.97f),
            .19f, LookColor(.05f,.00f), LookColor(.09f,-.01f), LookColor(.09f,.01f)),
        look("清冷人像", true, 0xFF647D98, 0xFFD7DFE5, floatArrayOf(.07f,.14f,.26f,.39f,.51f,.64f,.77f,.89f,.96f),
            .20f, LookColor(-.09f,-.02f), LookColor(.02f,-.02f), LookColor(-.03f,.00f)),
    )

    private fun look(label: String, portrait: Boolean, dark: Long, light: Long, quantiles: FloatArray,
        saturation: Float, vararg bands: LookColor, hues: FloatArray? = null) =
        AdaptiveLook(label, portrait, listOf(dark, light),
            LookProfile(quantiles, FloatArray(Quantiles.size) { saturation * (.15f + 1.5f * Quantiles[it]) },
                bands.toList(), hues))

    fun profile(pixels: IntArray): LookProfile? {
        val light = IntArray(256)
        val chroma = IntArray(256)
        val hues = FloatArray(ToneParams.HSL_COUNT)
        val redBlue = FloatArray(3)
        val green = FloatArray(3)
        val counts = FloatArray(3)
        var skinRedBlue = 0f
        var skinGreen = 0f
        var skinCount = 0
        var total = 0
        for (pixel in pixels) {
            if (pixel ushr 24 < 128) continue
            val r = (pixel ushr 16 and 255) / 255f
            val g = (pixel ushr 8 and 255) / 255f
            val b = (pixel and 255) / 255f
            val y = .2126f * r + .7152f * g + .0722f * b
            val c = maxOf(r, g, b) - minOf(r, g, b)
            light[(y * 255).toInt().coerceIn(0, 255)]++
            chroma[(c * 255).toInt().coerceIn(0, 255)]++
            for (band in 0..2) {
                val weight = (1f - abs(y - (.15f + .35f * band)) / .5f).coerceAtLeast(0f)
                counts[band] += weight
                redBlue[band] += weight * (r - b)
                green[band] += weight * (g - (r + b) / 2f)
            }
            if (c > .025f) {
                val hue = hue(r, g, b)
                val sector = ToneParams.HSL_CENTERS.indices.minBy { circularDistance(hue, ToneParams.HSL_CENTERS[it] / 360f) }
                hues[sector] += c
                // Warm midtone samples approximate skin-colored pixels without changing face regions.
                if (hue in .015f.. .135f && c in .055f.. .38f && y in .2f.. .88f) {
                    skinRedBlue += r - b
                    skinGreen += g - (r + b) / 2f
                    skinCount++
                }
            }
            total++
        }
        if (total == 0) return null
        val colorTotal = hues.sum()
        return LookProfile(quantiles(light, total), quantiles(chroma, total), List(3) {
            LookColor(redBlue[it] / counts[it].coerceAtLeast(1f), green[it] / counts[it].coerceAtLeast(1f))
        }, if (colorTotal > 0f) FloatArray(hues.size) { hues[it] / colorTotal } else hues,
            if (skinCount >= maxOf(8, total / 50)) LookColor(skinRedBlue / skinCount, skinGreen / skinCount) else null)
    }

    private fun quantiles(histogram: IntArray, total: Int): FloatArray {
        var bin = 0
        var count = histogram[0]
        return FloatArray(Quantiles.size) { index ->
            val threshold = Quantiles[index] * total
            while (count < threshold && bin < histogram.lastIndex) count += histogram[++bin]
            bin / 255f
        }
    }

    internal fun distance(profile: LookProfile, target: LookProfile, portrait: Boolean): Float {
        val light = profile.light.indices.sumOf { abs(profile.light[it] - target.light[it]).toDouble() }.toFloat() / Quantiles.size
        val chroma = profile.chroma.indices.sumOf { abs(profile.chroma[it] - target.chroma[it]).toDouble() }.toFloat() / Quantiles.size
        val color = profile.bands.indices.sumOf { i ->
            val a = profile.bands[i]
            val b = target.bands[i]
            ((abs(a.redBlue - b.redBlue) + abs(a.green - b.green)) * if (portrait && i == 1) .45f else 1f).toDouble()
        }.toFloat() / 3f
        val a = profile.hues
        val b = target.hues
        val hue = if (a == null || b == null) 0f else
            a.indices.sumOf { abs(a[it] - b[it]).toDouble() }.toFloat() / a.size
        return light * 2.4f + color * .95f + chroma * .62f + hue * .30f
    }

    /** Scores candidates using the actual rendering pipeline so brightness and color changes cannot blindly stack. */
    fun fit(base: ToneParams, target: LookProfile, portrait: Boolean,
        evaluate: (ToneParams) -> LookProfile): ToneParams {
        var best = base
        var measured = evaluate(base)
        val original = measured
        var score = distance(measured, target, portrait)
        fun tryParams(candidate: ToneParams) {
            if (candidate == best) return
            val profile = evaluate(candidate)
            val skinChange = if (portrait && original.skin != null) {
                val skin = profile.skin
                if (skin == null) .10f else abs(original.skin.redBlue - skin.redBlue) + abs(original.skin.green - skin.green)
            } else 0f
            val error = distance(profile, target, portrait) + skinChange * .45f
            if (error + .00015f < score) {
                best = candidate
                measured = profile
                score = error
            }
        }
        for (strength in listOf(.42f, .72f, 1f)) {
            tryParams(withCurve(base, original.light, target.light, strength))
        }
        if (best.blackWhite && target.chroma[5] > .06f) tryParams(best.copy(blackWhite = false))
        repeat(2) { pass ->
            val step = if (pass == 0) .30f else .13f
            val start = best
            for (direction in listOf(-1f, 1f)) {
                tryParams(start.copy(temperature = (start.temperature + direction * step).coerceIn(-1f, 1f)))
            }
            val tintBase = best
            for (direction in listOf(-1f, 1f)) {
                tryParams(tintBase.copy(tint = (tintBase.tint + direction * step).coerceIn(-1f, 1f)))
            }
            val saturationBase = best
            val delta = ((target.chroma[5] - measured.chroma[5]) * 2.2f).coerceIn(-.45f, .45f)
            tryParams(saturationBase.copy(saturation = (saturationBase.saturation + delta).coerceIn(-1f, 1f)))
            for (i in 0..2) {
                val x = target.bands[i].redBlue - measured.bands[i].redBlue
                val y = target.bands[i].green - measured.bands[i].green
                val residual = sqrt(x * x + y * y)
                val ceiling = if (portrait && i == 1) .11f else .28f
                val grade = ColorGrade(hue(x / 2f, y, -x / 2f), (residual * 1.6f).coerceAtMost(ceiling))
                val before = best
                for (factor in listOf(.55f, 1f)) {
                    val grades = List(4) { before.grading.getOrElse(it) { ColorGrade() } }.toMutableList()
                    grades[i] = grade.copy(saturation = grade.saturation * factor, luminance = grades[i].luminance)
                    tryParams(before.copy(grading = grades))
                }
            }
            val actualHues = measured.hues
            val targetHues = target.hues
            if (actualHues != null && targetHues != null) {
                val changes = List(ToneParams.HSL_COUNT) { i ->
                    val existing = best.hsl.getOrElse(i) { HslAdjust() }
                    val weight = if (portrait && i in 0..2) .35f else 1f
                    val diff = ((targetHues[i] - actualHues[i]) * 1.4f * weight).coerceIn(-.22f, .22f)
                    existing.copy(sat = (existing.sat + diff).coerceIn(-1f, 1f))
                }
                tryParams(best.copy(hsl = changes))
            }
        }
        val refined = best
        for (ev in listOf(-.14f, .14f)) {
            tryParams(refined.copy(exposure = (refined.exposure + ev).coerceIn(-2f, 2f)))
        }
        return best
    }

    private fun withCurve(base: ToneParams, source: FloatArray, target: FloatArray, amount: Float): ToneParams {
        val existing = base.curves.firstOrNull()?.lut() ?: ToneCurve().lut()
        val controls = ArrayList<CurvePoint>(12)
        var previous = 0f
        for (i in 0..10) {
            val x = i / 10f
            val y = existing[(x * 255f).toInt()] / 255f
            val delta = (quantileMap(y, source, target) - y).coerceIn(-.32f, .32f)
            val output = (y + amount * delta).coerceIn(previous, 1f)
            controls += CurvePoint(x, output)
            previous = output
        }
        return base.copy(curves = List(4) { if (it == 0) ToneCurve(controls) else base.curves.getOrElse(it) { ToneCurve() } })
    }

    private fun quantileMap(value: Float, source: FloatArray, target: FloatArray): Float {
        // Keep the black/white endpoints anchored, including for photos without very dark or bright samples.
        if (value <= 0f || value >= 1f) return value
        var leftX = 0f
        var leftY = 0f
        for (i in 0..source.size) {
            val rightX = if (i == source.size) 1f else source[i]
            val rightY = if (i == target.size) 1f else target[i]
            if (value <= rightX && rightX > leftX) return leftY + (rightY - leftY) * (value - leftX) / (rightX - leftX)
            leftX = rightX
            leftY = rightY
        }
        return value
    }

    private fun circularDistance(a: Float, b: Float): Float = abs(a - b).let { minOf(it, 1f - it) }

    private fun hue(r: Float, g: Float, b: Float): Float {
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta < .0001f) return 0f
        val sector = when (max) {
            r -> (g - b) / delta
            g -> 2f + (b - r) / delta
            else -> 4f + (r - g) / delta
        } / 6f
        return sector - floor(sector)
    }
}
