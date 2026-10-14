package com.dcimgallery.raw.core.raw

/** File names and provider MIME types are hints; badges follow the actual file signature. */
enum class PhotoFileType {
    JPEG, CR3, UNKNOWN;

    companion object {
        fun fromHeader(bytes: ByteArray): PhotoFileType = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
            bytes.size >= 12 && String(bytes, 4, 8, Charsets.ISO_8859_1) == "ftypcrx " -> CR3
            else -> UNKNOWN
        }
    }
}
