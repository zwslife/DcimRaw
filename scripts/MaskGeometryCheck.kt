package com.dcimgallery.raw.core.tone

import kotlin.math.abs

fun main() {
    fun alpha(map: ByteArray, w: Int, x: Int, y: Int) = map[y * w + x].toInt() and 255
    fun raster(vararg parts: MaskComponent, inverted: Boolean = false, w: Int = 100, h: Int = 100) =
        MaskRasterizer.rasterize(MaskLayer(name = "test", components = parts.toList(), inverted = inverted), w, h)
    val radial = MaskComponent(MaskTool.RADIAL, listOf(MaskPoint(.5f, .5f), MaskPoint(.85f, .85f)), feather = .5f)
    val radialMap = raster(radial)
    val ellipse = radial.copy(points = listOf(MaskPoint(.5f, .5f), MaskPoint(.85f, .6f)), feather = .2f)
    val horizontal = raster(ellipse)
    val vertical = raster(ellipse.copy(rotation = 90f))
    check(alpha(horizontal, 100, 75, 50) > 200 && alpha(horizontal, 100, 50, 75) == 0)
    check(alpha(vertical, 100, 75, 50) == 0 && alpha(vertical, 100, 50, 75) > 200) { "Rotation does not rotate ellipse coverage" }
    val diagonal = raster(ellipse.copy(rotation = 45f))
    check(alpha(diagonal, 100, 65, 65) > 200 && alpha(diagonal, 100, 65, 35) == 0)
    check(raster(ellipse.copy(rotation = 360f)).contentEquals(horizontal))
    val rotatedInverse = raster(ellipse.copy(rotation = 45f), inverted = true)
    for (i in diagonal.indices) check((diagonal[i].toInt() and 255) + (rotatedInverse[i].toInt() and 255) == 255)
    val pivot = MaskPoint(.5f, .5f)
    val corner = MaskPoint(.8f, .6f)
    val turned = rotateMaskPoint(corner, pivot, 90f, 200f, 100f)
    check(abs(turned.x - .45f) < .0001f && abs(turned.y - 1.1f) < .0001f) { "Rotation must use pixel aspect ratio" }
    val restored = rotateMaskPoint(turned, pivot, -90f, 200f, 100f)
    check(abs(restored.x - corner.x) < .0001f && abs(restored.y - corner.y) < .0001f) { "Resizing rotated ellipse uses wrong inverse" }
    check(alpha(radialMap, 100, 50, 50) == 255)
    check(alpha(radialMap, 100, 5, 5) == 0)
    check(alpha(radialMap, 100, 75, 50) in 1..254) { "Radial feather missing" }
    val linear = MaskComponent(MaskTool.LINEAR, listOf(MaskPoint(.25f, .5f), MaskPoint(.75f, .5f)), feather = 1f)
    val linearMap = raster(linear)
    check(alpha(linearMap, 100, 0, 50) == 255 && alpha(linearMap, 100, 99, 50) == 0)
    check(alpha(linearMap, 100, 49, 50) in 120..140)
    for (x in 1..99) check(alpha(linearMap, 100, x, 50) <= alpha(linearMap, 100, x - 1, 50))
    val brush = MaskComponent(MaskTool.BRUSH, listOf(MaskPoint(.2f, .5f), MaskPoint(.8f, .5f)), radius = .08f, feather = .5f)
    val brushMap = raster(brush)
    for (x in 20..79) check(alpha(brushMap, 100, x, 50) == 255) { "Gap in continuous stroke at $x" }
    check(alpha(brushMap, 100, 50, 10) == 0)
    val dot = brush.copy(points = listOf(MaskPoint(.5f, .5f)), radius = .12f)
    check(alpha(raster(dot), 100, 50, 50) == 255) { "Single tap should paint a dot" }
    val subtracted = raster(radial, dot.copy(subtract = true))
    check(alpha(subtracted, 100, 50, 50) == 0) { "Subtract must remove coverage" }
    check(alpha(subtracted, 100, 70, 50) > 0) { "Subtract removed unrelated coverage" }
    val added = raster(radial, dot.copy(points = listOf(MaskPoint(.1f, .1f))))
    check(alpha(added, 100, 10, 10) == 255) { "Add must expand coverage" }
    val inverse = raster(radial, inverted = true)
    for (i in inverse.indices) check((inverse[i].toInt() and 255) + (radialMap[i].toInt() and 255) == 255)
    check(alpha(raster(dot.copy(opacity = .3f)), 100, 50, 50) in 76..77)
    val portrait = raster(dot.copy(radius = .1f), w = 100, h = 200)
    check(abs(alpha(portrait, 100, 57, 100) - alpha(portrait, 100, 50, 107)) <= 1) { "Brush becomes oval on portrait photos" }
    val viewport = MaskViewport.fit(400f, 800f, 6000, 4000)
    check(!viewport.contains(200f, 100f)) { "Letterbox accepts painting" }
    val center = viewport.normalize(200f, 400f)
    check(abs(center.x - .5f) < .0001f && abs(center.y - .5f) < .0001f)
    val edge = viewport.normalize(viewport.left + viewport.width, viewport.top + viewport.height)
    check(abs(edge.x - 1f) < .0001f && abs(edge.y - 1f) < .0001f)
    check(raster(linear.copy(points = listOf(MaskPoint(.5f, .5f), MaskPoint(.5f, .5f)))).all { it == 0.toByte() })
    println("PASS: radial rotation 45/90/360 degrees, inverse handles, aspect ratio, brush continuity, gradients, feather, add/subtract, inversion, opacity and letterbox coordinates")
}
