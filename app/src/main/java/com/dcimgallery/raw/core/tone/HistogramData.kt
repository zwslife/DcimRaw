package com.dcimgallery.raw.core.tone

/** 256 档 RGB / 亮度直方图。限制统计点数，避免逐像素扫描全尺寸原片。 */
data class HistogramData(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val luminance: IntArray,
    val samples: Int,
) {
    val peak: Int = maxOf(red.maxOrNull() ?: 0, green.maxOrNull() ?: 0,
        blue.maxOrNull() ?: 0, luminance.maxOrNull() ?: 0).coerceAtLeast(1)

    companion object {
        /** Small Gaussian kernel for display only; original bin counts remain exact. */
        fun smooth(bins: IntArray): FloatArray {
            val weights = intArrayOf(1, 4, 6, 4, 1)
            return FloatArray(bins.size) { index ->
                var sum = 0f
                var weight = 0
                for (k in weights.indices) {
                    val at = index + k - 2
                    if (at in bins.indices) { sum += bins[at] * weights[k].toFloat(); weight += weights[k] }
                }
                if (weight == 0) 0f else sum / weight
            }
        }

        /** 按网格中心采样整张照片；透明像素不计入，不受缩放或蒙版叠加色影响。 */
        fun sample(width: Int, height: Int, readRow: (Int, IntArray) -> Unit): HistogramData {
            require(width > 0 && height > 0)
            val red = IntArray(256)
            val green = IntArray(256)
            val blue = IntArray(256)
            val luminance = IntArray(256)
            val columns = minOf(width, 128)
            val rows = minOf(height, 128)
            val row = IntArray(width)
            var count = 0
            repeat(rows) { gy ->
                val y = ((gy + 0.5) * height / rows).toInt().coerceAtMost(height - 1)
                readRow(y, row)
                repeat(columns) { gx ->
                    val x = ((gx + 0.5) * width / columns).toInt().coerceAtMost(width - 1)
                    val pixel = row[x]
                    if (pixel ushr 24 != 0) {
                        val r = pixel ushr 16 and 255
                        val g = pixel ushr 8 and 255
                        val b = pixel and 255
                        red[r]++; green[g]++; blue[b]++
                        luminance[(77 * r + 150 * g + 29 * b + 128) ushr 8]++
                        count++
                    }
                }
            }
            return HistogramData(red, green, blue, luminance, count)
        }
    }
}
