package com.dcimgallery.raw.ui

fun main() {
    val provider = "com.android.externalstorage.documents"
    check(!StorageOriginPolicy.isUsb("primary", provider, 34, 1 shl 19, "ic_usb"))
    check(!StorageOriginPolicy.isUsb("home", provider, 34, 1 shl 19, "ic_usb"))
    check(!StorageOriginPolicy.isUsb("1234-5678", provider, 34, 1 shl 18, "ic_sd"))
    check(!StorageOriginPolicy.isUsb("1234-5678", provider, 34, 0, ""))
    check(StorageOriginPolicy.isUsb("1234-5678", provider, 34, 1 shl 19, ""))
    check(StorageOriginPolicy.isUsb("1234-5678", provider, 26, 1 shl 20, ""))
    check(!StorageOriginPolicy.isUsb("1234-5678", "other.provider", 34, 1 shl 19, ""))
    check(StorageOriginPolicy.isUsb("1234-5678", "other.provider", 34, 0, "ic_usb_storage"))
    check(!StorageOriginPolicy.isUsb("1234-5678", provider, 37, 1 shl 19, ""))
    println("Storage origin checks passed: internal, SD, USB, unknown provider and SDK")
}
