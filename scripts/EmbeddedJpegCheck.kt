package com.dcimgallery.raw.core.cr3

import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO
import com.dcimgallery.raw.core.raw.PhotoFileType

fun main(args: Array<String>) {
    check(args.isNotEmpty())
    args.forEach { name ->
        val file = File(name)
        RandomAccessFile(file, "r").use { input ->
            val parser = Cr3Parser.open(input.channel)
            val bytes = parser.extractFullJpegPreview() ?: error("Missing embedded JPEG: $name")
            val image = ImageIO.read(bytes.inputStream()) ?: error("Invalid JPEG: $name")
            val header = ByteArray(12).also { input.seek(0); input.readFully(it) }
            val type = PhotoFileType.fromHeader(header)
            val original = if (parser.isStandaloneJpeg()) {
                check(type == PhotoFileType.JPEG) { "JPEG content must never receive a RAW badge" }
                check(parser.parseTracks() == null)
                file.readBytes()
            } else {
                check(type == PhotoFileType.CR3) { "Actual CR3 must keep its RAW badge" }
                val tracks = parser.parseTracks()!!.filter { it.sampleSizes.isNotEmpty() && it.chunkOffsets.isNotEmpty() }
                val jpegTracks = tracks.filter { track -> input.seek(track.chunkOffsets[0]); input.readUnsignedShort() == 0xffd8 }
                val largest = jpegTracks.maxBy { it.pixels }
                check(image.width == largest.width && image.height == largest.height)
                ByteArray(largest.sampleSizes[0].toInt()).also { input.seek(largest.chunkOffsets[0]); input.readFully(it) }
            }
            check(bytes.contentEquals(original)) { "Extraction changed original JPEG bytes" }
            check(runCatching { parser.readSample(file.length() - 1, 100) }.isFailure)
            check(runCatching { parser.readSample(-1, 10) }.isFailure)
            println("PASS: ${file.name}, type=$type, ${image.width}x${image.height}, ${bytes.size} exact JPEG bytes; truncated samples rejected")
        }
    }
    val empty = File.createTempFile("no-jpeg-", ".cr3", File(".gradle"))
    try {
        empty.writeBytes(byteArrayOf(0, 0, 0, 8, 102, 116, 121, 112))
        RandomAccessFile(empty, "r").use { check(Cr3Parser.open(it.channel).extractFullJpegPreview() == null) }
    } finally { empty.delete() }
    println("PASS: container without an embedded JPEG returns no export data")
    check(PhotoFileType.fromHeader(byteArrayOf()) == PhotoFileType.UNKNOWN)
    check(PhotoFileType.fromHeader(byteArrayOf(-1, -40)) == PhotoFileType.UNKNOWN)
}
