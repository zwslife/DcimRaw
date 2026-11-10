package com.dcimgallery.raw.core.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/** Geometry is applied after global/local color so existing masks retain original coordinates. */
object PhotoGeometryRenderer {
    /** The caller owns a new result; identity edits return source unchanged. */
    fun render(source: Bitmap, geometry: PhotoGeometry): Bitmap {
        val edit = geometry.normalized()
        if (edit.isIdentity) return source
        val frame = edit.frame(source.width, source.height)
        val (width, height) = edit.outputSize(source.width, source.height)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            setRotate(edit.rotation, source.width / 2f, source.height / 2f)
            postTranslate(-source.width / 2f - (edit.crop.left - .5f) * frame.width,
                -source.height / 2f - (edit.crop.top - .5f) * frame.height)
        }
        Canvas(output).drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }
}
