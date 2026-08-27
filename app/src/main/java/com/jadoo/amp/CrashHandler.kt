package com.jadoo.amp

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Writes any uncaught crash to a local file before handing off to the
 * platform's default handler (which still shows the normal "app has
 * stopped" dialog and kills the process — this never suppresses that).
 *
 * The problem this solves: OEM ROMs (MIUI, OriginOS, etc.) have caused
 * startup crashes in this app before that were impossible to diagnose
 * because the person hitting them has no idea what logcat is and can't
 * pull one. This writes a plain-text report to internal storage so
 * [MainActivity] can offer a one-tap "Share crash report" the next time
 * the app opens — same info a logcat pull would give, but reachable by
 * anyone who can tap a Share button.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val platformDefault = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            reportFile(context).writeText(buildReport(context, throwable))
        } catch (_: Throwable) {
            // Never let report-writing itself cause a secondary crash loop.
        }
        platformDefault?.uncaughtException(thread, throwable)
    }

    companion object {
        fun reportFile(context: Context): File = File(context.filesDir, "last_crash_report.txt")

        private fun buildReport(context: Context, throwable: Throwable): String {
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) { "unknown" }
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            return buildString {
                appendLine("JadOO DSP crash report")
                appendLine("App version: $versionName")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("ROM build: ${Build.DISPLAY}")
                appendLine("Time: ${Date()}")
                appendLine()
                append(sw.toString())
            }
        }

        /** Installs the handler as early as possible — see [JadooApplication]. */
        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext))
        }
    }
}

class JadooApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
