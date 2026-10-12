package com.dcimgallery.raw.core.exif

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 最小 TIFF/IFD 解析器，用于读取 CR3 CMT 盒（小端 TIFF）。
 * 仅覆盖需要的类型：ASCII(2)、SHORT(3)、LONG(4)、RATIONAL(5)、SRATIONAL(10)。
 * 返回值形态：String / Long / LongArray / Double（有理数已求值）。
 */
object TiffReader {

    private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

    fun parse(data: ByteArray): Map<Int, Any> {
        if (data.size < 8) return emptyMap()
        val littleEndian = data[0] == 'I'.code.toByte()
        val buf = ByteBuffer.wrap(data).order(
            if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN,
        )
        buf.position(2)
        if ((buf.short.toInt() and 0xFFFF) != 42) return emptyMap()
        val out = HashMap<Int, Any>()
        readIfd(buf, data, buf.int, out)
        return out
    }

    private fun readIfd(buf: ByteBuffer, data: ByteArray, offset: Int, out: MutableMap<Int, Any>) {
        if (offset < 0 || offset + 2 > data.size) return
        buf.position(offset)
        val count = buf.short.toInt() and 0xFFFF
        var p = offset + 2
        repeat(count) {
            if (p + 12 > data.size) return
            buf.position(p)
            val tag = buf.short.toInt() and 0xFFFF
            val type = buf.short.toInt() and 0xFFFF
            val cnt = buf.int
            val unit = if (type in 1 until TYPE_SIZE.size) TYPE_SIZE[type] else 1
            val total = unit * cnt
            val valueOffset = if (total <= 4) {
                p + 8
            } else {
                buf.position(p + 8)
                buf.int
            }
            if (valueOffset >= 0 && valueOffset + total <= data.size) {
                decode(buf, data, type, cnt, valueOffset, total)?.let { out[tag] = it }
            }
            p += 12
        }
    }

    private fun decode(
        buf: ByteBuffer,
        data: ByteArray,
        type: Int,
        cnt: Int,
        offset: Int,
        total: Int,
    ): Any? = runCatching {
        when (type) {
            2 -> String(data, offset, total)
                .trim { it.code == 0 || it == ' ' || it == '\n' || it == '\t' }
                .ifBlank { null }

            3, 4 -> {
                buf.position(offset)
                if (cnt == 1) {
                    readUint(buf, type)
                } else {
                    LongArray(cnt) { readUint(buf, type) }
                }
            }

            5, 10 -> {
                buf.position(offset)
                val num = if (type == 5) (buf.int.toLong() and 0xFFFFFFFFL) else buf.int.toLong()
                val den = if (type == 5) (buf.int.toLong() and 0xFFFFFFFFL) else buf.int.toLong()
                if (den == 0L) return null
                num.toDouble() / den.toDouble()
            }

            else -> null
        }
    }.getOrNull()

    private fun readUint(buf: ByteBuffer, type: Int): Long =
        if (type == 3) (buf.short.toInt() and 0xFFFF).toLong()
        else buf.int.toLong() and 0xFFFFFFFFL
}
