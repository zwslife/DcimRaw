package com.dcimgallery.raw.ui

/** Pure policy: never infer USB from SAF access or removable volume alone. */
internal object StorageOriginPolicy {
    fun isUsb(root: String, authority: String, sdk: Int, flags: Int, icon: String): Boolean {
        if (root.isBlank() || root == "primary" || root == "home" || root.startsWith("raw")) return false
        // ExternalStorageProvider's system root flags. Values changed after Oreo.
        // AOSP android14-release DocumentsContract.Root: FLAG_REMOVABLE_USB = 1 shl 19.
        val usbFlag = when (sdk) { in 26..27 -> 1 shl 20; in 29..36 -> 1 shl 19; else -> 0 }
        return icon.contains("usb", true) ||
            (authority == "com.android.externalstorage.documents" && usbFlag != 0 && flags and usbFlag != 0)
    }
}
