package com.dcimgallery.raw

import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

/** A single local crash report; never uploaded automatically. */
object CrashRecord {
    private const val FILE = "last-crash.txt"
    private var installed = false
    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.filesDir, FILE)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText("DcimRaw $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
                    "${Instant.now()} · ${thread.name}\n\n" + error.stackTraceToString().take(48_000))
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()
    fun clear(context: Context) { File(context.filesDir, FILE).delete() }
}
