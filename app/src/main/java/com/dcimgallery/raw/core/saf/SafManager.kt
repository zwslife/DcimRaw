package com.dcimgallery.raw.core.saf

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * SAF 授权管理：持久化用户授权的外接盘目录树 URI，重启后自动恢复。
 */
object SafManager {

    private const val PREFS = "saf_prefs"
    private const val KEY_TREE_URI = "tree_uri"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 已授权且当前仍然可读（外接盘已挂载）时返回 URI，否则 null。 */
    fun grantedUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        val uri = Uri.parse(raw)
        return if (isReadable(context, uri)) uri else null
    }

    /** 已持久化的 URI（不做可读性检查，用于提示"盘未挂载"）。 */
    fun persistedUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun persist(context: Context, uri: Uri) {
        val flags = try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (se: SecurityException) {
            // 部分卷不支持写权限，降级为只读
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clear(context: Context) {
        persistedUri(context)?.let { uri ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
        }
        prefs(context).edit().remove(KEY_TREE_URI).apply()
    }

    fun isReadable(context: Context, uri: Uri): Boolean = try {
        DocumentFile.fromTreeUri(context, uri)?.let { it.isDirectory && it.canRead() } == true
    } catch (_: Exception) {
        false
    }
}
