package com.dcimgallery.raw.core.exif

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.dcimgallery.raw.core.cr3.Cr3Parser
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** 一张照片的常用拍摄参数，null 表示该字段缺失。 */
data class ExifInfo(
    val dateTimeText: String? = null,
    val camera: String? = null,
    val lens: String? = null,
    val exposure: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val focal: String? = null,
    val flash: String? = null,
    val whiteBalance: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)

/**
 * EXIF 读取器。
 * - 外接 CR3：解析 moov → uuid → CMT1/CMT2（小端 TIFF），权威来源
 *   （实测：内嵌预览不带 EXIF，XMP 盒可能为空）
 * - 本机照片（JPEG/HEIC）：直接用 androidx ExifInterface 读流
 */
object ExifReader {

    private const val TAG_IMAGE_WIDTH = 0x0100
    private const val TAG_IMAGE_LENGTH = 0x0101
    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_DATETIME = 0x0132
    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_FNUMBER = 0x829D
    private const val TAG_ISO = 0x8827
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_FOCAL_LENGTH = 0x920A
    private const val TAG_FLASH = 0x9209
    private const val TAG_WHITE_BALANCE = 0xA403
    private const val TAG_LENS_MODEL = 0xA434
    private const val TAG_FOCAL_35MM = 0xA405

    fun read(context: Context, uri: Uri, source: String): ExifInfo? {
        if (source != "external") return readLocal(context, uri)
        val jpeg = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.read() == 0xFF && it.read() == 0xD8 }
        }.getOrDefault(false) == true
        return if (jpeg) readLocal(context, uri) else readCr3(context, uri)
    }

    // ---- 外接 CR3：CMT 盒 TIFF ----

    private fun readCr3(context: Context, uri: Uri): ExifInfo? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val parser = Cr3Parser.open(pfd)
            val cmt1 = parser.readCmtBox("CMT1")?.let(TiffReader::parse).orEmpty()
            val cmt2 = parser.readCmtBox("CMT2")?.let(TiffReader::parse).orEmpty()
            ExifInfo(
                dateTimeText = formatDate(
                    (cmt2[TAG_DATETIME_ORIGINAL] as? String) ?: (cmt1[TAG_DATETIME] as? String),
                ),
                camera = listOfNotNull(cmt1[TAG_MAKE] as? String, cmt1[TAG_MODEL] as? String)
                    .joinToString(" ").trim().ifBlank { null },
                lens = cmt2[TAG_LENS_MODEL] as? String,
                exposure = (cmt2[TAG_EXPOSURE_TIME] as? Double)?.let(::formatShutter),
                aperture = (cmt2[TAG_FNUMBER] as? Double)
                    ?.let { "f/" + String.format(Locale.US, "%.1f", it) },
                iso = when (val v = cmt2[TAG_ISO]) {
                    is Long -> "ISO $v"
                    is LongArray -> v.firstOrNull()?.let { "ISO $it" }
                    else -> null
                },
                focal = formatFocal(
                    (cmt2[TAG_FOCAL_LENGTH] as? Double),
                    (cmt2[TAG_FOCAL_35MM] as? Long)?.toInt() ?: 0,
                ),
                flash = (cmt2[TAG_FLASH] as? Long)?.let { if (it and 1L == 1L) "闪光" else "未闪光" },
                whiteBalance = when ((cmt2[TAG_WHITE_BALANCE] as? Long)?.toInt()) {
                    0 -> "自动"
                    1 -> "手动"
                    else -> null
                },
                imageWidth = ((cmt1[TAG_IMAGE_WIDTH] as? Long) ?: 0L).toInt(),
                imageHeight = ((cmt1[TAG_IMAGE_LENGTH] as? Long) ?: 0L).toInt(),
            )
        }
    }.getOrNull()

    // ---- 本机照片：系统 EXIF ----

    private fun readLocal(context: Context, uri: Uri): ExifInfo? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            ExifInfo(
                dateTimeText = formatDate(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                ),
                camera = listOfNotNull(
                    exif.getAttribute(ExifInterface.TAG_MAKE),
                    exif.getAttribute(ExifInterface.TAG_MODEL),
                ).joinToString(" ").trim().ifBlank { null },
                lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
                exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
                    .takeIf { !it.isNaN() }?.let(::formatShutter),
                aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN)
                    .takeIf { !it.isNaN() }
                    ?.let { "f/" + String.format(Locale.US, "%.1f", it) },
                iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?.let { "ISO $it" },
                focal = formatFocal(
                    exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN)
                        .takeIf { !it.isNaN() },
                    exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0),
                ),
                flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
                    .takeIf { it >= 0 }
                    ?.let { if (it and 1 == 1) "闪光" else "未闪光" },
                whiteBalance = when (exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)) {
                    0 -> "自动"
                    1 -> "手动"
                    else -> null
                },
                imageWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                imageHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
            )
        }
    }.getOrNull()

    // ---- 格式化 ----

    private fun formatShutter(seconds: Double): String =
        if (seconds >= 1.0) {
            if (seconds % 1.0 == 0.0) "${seconds.toInt()}″"
            else String.format(Locale.US, "%.1f″", seconds)
        } else {
            "1/${(1.0 / seconds).roundToInt()}″"
        }

    private fun formatFocal(mm: Double?, eq35: Int): String? {
        mm ?: return null
        val base = "${mm.roundToInt()}mm"
        return if (eq35 > 0) "$base（等效 ${eq35}mm）" else base
    }

    private fun formatDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw.trim())
                ?: return null
            SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(parsed)
        }.getOrNull()
    }
}
