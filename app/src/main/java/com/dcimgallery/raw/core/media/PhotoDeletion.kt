package com.dcimgallery.raw.core.media

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.dcimgallery.raw.data.db.PhotoEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Physical deletion and platform consent. Never treats a denied/unreadable URI as deleted. */
class PhotoDeletion(private val context: Context) {
    suspend fun delete(photos: List<PhotoEntity>, authorize: suspend (IntentSender) -> Boolean,
        onDeleted: suspend (Set<String>) -> Unit): DeleteReport {
        val byUri = photos.associateBy { it.uri }
        val plan = deletionPlan(photos.map { DeleteTarget(it.uri, it.source == "local" && Build.VERSION.SDK_INT >= 30) })
        return executeDeletion(plan, execute = { batch ->
            try {
                if (batch.systemManaged && Build.VERSION.SDK_INT >= 30) {
                    val sender = withContext(Dispatchers.IO) {
                        MediaStore.createDeleteRequest(context.contentResolver, batch.uris.map(Uri::parse)).intentSender
                    }
                    if (!authorize(sender)) DeleteAttempt(emptySet(), cancelled = true)
                    else DeleteAttempt(withContext(Dispatchers.IO) { batch.uris.filter(::isMissing).toSet() })
                } else {
                    val photo = byUri.getValue(batch.uris.single())
                    val uri = Uri.parse(photo.uri)
                    try {
                        val removed = withContext(Dispatchers.IO) { deleteDirect(photo, uri) }
                        DeleteAttempt(if (removed) setOf(photo.uri) else emptySet())
                    } catch (error: SecurityException) {
                        if (Build.VERSION.SDK_INT == 29 && photo.source == "local" && error is RecoverableSecurityException) {
                            if (!authorize(error.userAction.actionIntent.intentSender)) DeleteAttempt(emptySet(), cancelled = true)
                            else {
                                val removed = withContext(Dispatchers.IO) { deleteDirect(photo, uri) }
                                DeleteAttempt(if (removed) setOf(photo.uri) else emptySet())
                            }
                        } else DeleteAttempt(emptySet())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DeleteAttempt(emptySet()) }
        }, onDeleted = onDeleted)
    }

    private fun deleteDirect(photo: PhotoEntity, uri: Uri): Boolean =
        if (photo.source == "local") context.contentResolver.delete(uri, null, null) > 0 || isMissing(photo.uri)
        else DocumentsContract.deleteDocument(context.contentResolver, uri) || isMissing(photo.uri)

    private fun isMissing(raw: String): Boolean = try {
        context.contentResolver.query(Uri.parse(raw), null, null, null, null)?.use { !it.moveToFirst() } ?: false
    } catch (_: FileNotFoundException) { true }
    catch (_: Exception) { false }
}
