package com.dcimgallery.raw.core.cr3

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * CR3 (ISO Base Media File Format) 解析器。
 *
 * CR3 是类 MP4 的容器：ftyp(crx ) + moov(多轨) + uuid(CMT1..4 元数据) + PRVW/THMB + mdat。
 * 相机会在 trak 中写入一张全尺寸 JPEG 预览（与拍摄参数一致的机内解码结果），
 * 本解析器负责定位并抽出它，供网格/全屏浏览秒开使用。
 *
 * 通过 [ParcelFileDescriptor] + [FileChannel] 随机读取，适配 SAF DocumentFile，
 * 无需把整文件拷入内存。
 */
class Cr3Parser private constructor(private val ch: FileChannel) {

    data class Box(val type: String, val start: Long, val size: Long, val header: Int) {
        val payloadStart: Long get() = start + header
        val payloadSize: Long get() = size - header
    }

    data class TrackInfo(
        val width: Int = 0,
        val height: Int = 0,
        val handler: String = "",
        val sampleFormat: String = "",
        val sampleSizes: LongArray = longArrayOf(),
        val chunkOffsets: LongArray = longArrayOf(),
    ) {
        val pixels: Long get() = width.toLong() * height
    }

    companion object {
        private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "dinf")
        private const val MAX_LEAF_READ = 4L * 1024 * 1024 // 样本表最大读取 4MB

        /** 打开文件描述符并返回解析器；调用方负责关闭 pfd。 */
        fun open(pfd: ParcelFileDescriptor): Cr3Parser =
            Cr3Parser(FileInputStream(pfd.fileDescriptor).channel)

        /** Allows the same parser to be checked against local RAW fixtures. Caller owns the channel. */
        internal fun open(channel: FileChannel): Cr3Parser = Cr3Parser(channel)
    }

    private fun readBoxHeader(pos: Long): Box? {
        if (pos + 8 > ch.size()) return null
        val buf = ByteBuffer.allocate(16)
        ch.position(pos)
        if (ch.read(buf) < 8) return null
        buf.flip()
        var size = buf.int.toLong() and 0xFFFFFFFFL
        val tb = ByteArray(4).also { buf.get(it) }
        val type = String(tb, Charsets.ISO_8859_1)
        var header = 8
        when {
            size == 1L -> { size = buf.long; header = 16 }
            size == 0L -> size = ch.size() - pos
        }
        if (size < header || size <= 0) return null
        return Box(type, pos, size, header)
    }

    private fun childrenOf(parent: Box): List<Box> {
        val out = ArrayList<Box>()
        val end = parent.start + parent.size
        // uuid 盒的载荷以 16 字节 usertype 开头，子盒在其后
        var pos = parent.payloadStart + if (parent.type == "uuid") 16 else 0
        while (pos + 8 <= end) {
            val b = readBoxHeader(pos) ?: break
            out.add(b)
            pos = b.start + b.size
        }
        return out
    }

    private fun readPayload(b: Box, limit: Long = MAX_LEAF_READ): ByteBuffer {
        val n = minOf(b.payloadSize, limit).toInt()
        val buf = ByteBuffer.allocate(n)
        ch.position(b.payloadStart)
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) break
        }
        buf.flip()
        return buf
    }

    private fun parseTrack(trak: Box): TrackInfo {
        var info = TrackInfo()
        var mdia: Box? = null
        for (c in childrenOf(trak)) {
            when (c.type) {
                "tkhd" -> {
                    val d = readPayload(c, 256)
                    if (d.remaining() >= 8) {
                        d.position(d.limit() - 8)
                        info = info.copy(
                            width = d.int shr 16,
                            height = d.int shr 16,
                        )
                    }
                }
                "mdia" -> mdia = c
            }
        }
        if (mdia == null) return info
        findDeep(mdia, "hdlr")?.let { hdlr ->
            val d = readPayload(hdlr, 16)
            if (d.remaining() >= 12) {
                d.position(8)
                val hb = ByteArray(4).also { d.get(it) }
                info = info.copy(handler = String(hb, Charsets.ISO_8859_1))
            }
        }
        val stbl = findDeep(mdia, "stbl") ?: return info
        for (c in childrenOf(stbl)) {
            when (c.type) {
                "stsd" -> {
                    val d = readPayload(c, 32)
                    if (d.remaining() >= 16) {
                        d.position(12)
                        val fb = ByteArray(4).also { d.get(it) }
                        info = info.copy(sampleFormat = String(fb, Charsets.ISO_8859_1))
                    }
                }
                "stsz" -> {
                    val d = readPayload(c)
                    if (d.remaining() >= 12) {
                        d.position(4)
                        val uniform = d.int
                        val count = d.int
                        val sizes = LongArray(count)
                        if (uniform != 0) {
                            java.util.Arrays.fill(sizes, uniform.toLong() and 0xFFFFFFFFL)
                        } else {
                            for (i in 0 until count) {
                                if (d.remaining() < 4) break
                                sizes[i] = d.int.toLong() and 0xFFFFFFFFL
                            }
                        }
                        info = info.copy(sampleSizes = sizes)
                    }
                }
                "stco", "co64" -> {
                    if (info.chunkOffsets.isNotEmpty()) continue
                    val d = readPayload(c)
                    if (d.remaining() >= 8) {
                        d.position(4)
                        val count = d.int
                        val offs = LongArray(count)
                        for (i in 0 until count) {
                            offs[i] = if (c.type == "stco") {
                                if (d.remaining() < 4) break
                                d.int.toLong() and 0xFFFFFFFFL
                            } else {
                                if (d.remaining() < 8) break
                                d.long
                            }
                        }
                        info = info.copy(chunkOffsets = offs)
                    }
                }
            }
        }
        return info
    }

    private fun findDeep(parent: Box, type: String): Box? {
        for (c in childrenOf(parent)) {
            if (c.type == type) return c
            if (c.type in CONTAINERS) {
                findDeep(c, type)?.let { return it }
            }
        }
        return null
    }

    /** 解析 moov 下所有轨道；文件不是 CR3 时返回 null。 */
    fun parseTracks(): List<TrackInfo>? {
        val top = ArrayList<Box>()
        var pos = 0L
        val end = ch.size()
        while (pos + 8 <= end) {
            val b = readBoxHeader(pos) ?: break
            top.add(b)
            pos = b.start + b.size
        }
        val ftyp = top.firstOrNull { it.type == "ftyp" } ?: return null
        val brand = readPayload(ftyp, 16)
        if (brand.remaining() < 4) return null
        val bb = ByteArray(4).also { brand.get(it) }
        if (String(bb, Charsets.ISO_8859_1) != "crx ") return null

        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        return childrenOf(moov).filter { it.type == "trak" }.map { parseTrack(it) }
    }

    /**
     * 读取 moov → uuid(Canon 元数据容器) 内指定的 CMT 盒（TIFF 格式）。
     * CMT1 = IFD0（相机型号/时间/尺寸），CMT2 = ExifIFD（拍摄参数）。
     * 实测 R10：moov/uuid 内含 CNCV/CCTP/CTBO/CMT1..4/THMB。
     */
    fun readCmtBox(type: String): ByteArray? {
        var pos = 0L
        val end = ch.size()
        while (pos + 8 <= end) {
            val b = readBoxHeader(pos) ?: break
            if (b.type == "moov") {
                for (c in childrenOf(b)) {
                    if (c.type == "uuid") {
                        for (k in childrenOf(c)) {
                            if (k.type == type) {
                                val buf = readPayload(k)
                                val out = ByteArray(buf.remaining())
                                buf.get(out)
                                return out
                            }
                        }
                    }
                }
                return null
            }
            pos = b.start + b.size
        }
        return null
    }

    /** 读取指定偏移的少量字节（魔数探测用）。 */
    fun peekAt(offset: Long, count: Int): ByteArray {
        val buf = ByteBuffer.allocate(count)
        ch.position(offset)
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) break
        }
        return buf.array()
    }

    /** 读取指定偏移与长度的样本数据。 */
    fun readSample(offset: Long, size: Long): ByteArray {
        require(offset >= 0 && size > 0 && size <= Int.MAX_VALUE && offset <= ch.size() && size <= ch.size() - offset) {
            "RAW 内嵌样本范围无效或文件不完整"
        }
        val buf = ByteBuffer.allocate(size.toInt())
        ch.position(offset)
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw java.io.EOFException("RAW 内嵌样本不完整")
        }
        return buf.array()
    }
}

/**
 * 提取 CR3 内嵌的最大尺寸 JPEG 预览（trak 内样本，通常是全尺寸）。
 *
 * 注意：新固件机型（R10/R8 等）的视频轨 stsd 类型统一标记为 'CRAW'，
 * 不能据此区分 JPEG 轨；必须读取轨道样本首两字节，用 JPEG 魔数 FF D8 判定。
 * （R10 实测：预览轨 payload 为 FF D8…，RAW 轨为 FF 01… CRX 数据。）
 *
 * 返回原始 JPEG 字节；失败返回 null。
 */
fun Cr3Parser.extractFullJpegPreview(): ByteArray? {
    val tracks = parseTracks() ?: return null
    val jpeg = tracks
        .filter { it.sampleSizes.isNotEmpty() && it.chunkOffsets.isNotEmpty() }
        .filter { t ->
            val magic = peekAt(t.chunkOffsets[0], 2)
            magic.size >= 2 && magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()
        }
        .maxByOrNull { it.pixels } ?: return null
    return readSample(jpeg.chunkOffsets[0], jpeg.sampleSizes[0])
}
