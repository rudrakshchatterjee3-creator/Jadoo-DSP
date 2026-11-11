package com.jadoo.amp

import android.content.Context
import android.content.Intent
import android.os.Build
import com.jadoo.amp.settings.BackupCodec
import com.jadoo.amp.settings.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/**
 * The opt-in "Send diagnostics" report. Nothing here runs unless the person
 * opens Settings → Diagnostics, and nothing leaves the phone unless they tap
 * Share in the preview: the report is built as plain text, shown in full, and
 * handed to the system share sheet. There is no server and no background upload.
 *
 * Contents: build and device facts, the current settings (the same fields as a
 * backup file, without preset names), the last crash report if one is waiting,
 * and this app's own recent log lines. Android only lets an app read its own
 * logcat entries, so no other app's output can end up in it. Audio, track
 * titles and account details are never read.
 */
object DiagnosticsReport {
    /** Keeps the share-sheet payload comfortably under the binder limit. */
    private const val MAX_CHARS = 150_000
    private const val LOG_LINES = 600
    private const val SUPPORT_EMAIL = "rudraksh.chatterjee3@gmail.com"

    suspend fun build(context: Context, state: SessionState?): String = withContext(Dispatchers.IO) {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val crash = runCatching {
            CrashHandler.reportFile(context).takeIf { it.exists() }?.readText()
        }.getOrNull()

        val text = buildString {
            appendLine("JadOO DSP diagnostics")
            appendLine("App version: $versionName")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ROM build: ${Build.DISPLAY}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("Time: ${Date()}")

            section("Settings")
            appendLine(
                state?.let { runCatching { BackupCodec.encode(it, emptyList()) }.getOrNull() }
                    ?: "(not available: audio service not running)"
            )

            if (crash != null) {
                section("Last crash")
                appendLine(crash.trim())
            }

            section("Recent app log")
            appendLine(recentLog())
        }
        // Keep the newest end: the log is chronological, so trim from the front
        // of the log section rather than losing the header and settings.
        if (text.length <= MAX_CHARS) text else text.take(4_000) + "\n[...trimmed...]\n" + text.takeLast(MAX_CHARS - 4_100)
    }

    fun share(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            // Pre-fills the recipient in email apps; other share targets ignore it.
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "JadOO DSP diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(
            Intent.createChooser(send, "Send diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("── $title ──")
    }

    private fun recentLog(): String = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString())
        )
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.destroy()
        out.trim().ifEmpty { "(log is empty)" }
    }.getOrElse { "(log unavailable: ${it.javaClass.simpleName})" }
}
