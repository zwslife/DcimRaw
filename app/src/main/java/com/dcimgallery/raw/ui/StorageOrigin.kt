package com.dcimgallery.raw.ui

import android.content.Context
import android.os.Build
import android.net.Uri
import android.provider.DocumentsContract
import com.dcimgallery.raw.data.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object StorageOrigin {
    private fun volume(uri: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringBefore(':').lowercase()
    }.getOrNull()

    suspend fun usbPhotos(context: Context, photos: List<PhotoEntity>): Set<String> = withContext(Dispatchers.IO) {
        val candidates = photos.filter { it.source == "external" }.groupBy { Uri.parse(it.uri).authority }
        val result = mutableSetOf<String>()
        candidates.forEach { (authority, entries) ->
            if (authority == null) return@forEach
            val usbRoots = mutableSetOf<String>()
            runCatching {
                val provider = context.packageManager.resolveContentProvider(authority, 0) ?: return@runCatching
                val resources = context.packageManager.getResourcesForApplication(provider.applicationInfo)
                context.contentResolver.query(DocumentsContract.buildRootsUri(authority), arrayOf(
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_ICON, DocumentsContract.Root.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val root = cursor.getString(0)?.substringBefore(':')?.lowercase() ?: continue
                        val iconName = runCatching { resources.getResourceEntryName(cursor.getInt(1)) }.getOrDefault("")
                        // A removable volume can also be an SD card. Require a USB-specific provider icon.
                        if (StorageOriginPolicy.isUsb(root, authority, Build.VERSION.SDK_INT, cursor.getInt(2), iconName)) usbRoots += root
                    }
                }
            }
            entries.filter { volume(Uri.parse(it.uri)) in usbRoots }.forEach { result += it.uri }
        }
        result
    }
}
