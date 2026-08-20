package com.jadoo.amp.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    /**
     * Direct download URL for the release's APK asset, when the release has
     * one. Null means Lane B (in-app install) isn't available for this release
     * and the UI falls back to opening the release page in a browser.
     */
    val apkAssetUrl: String? = null,
    val apkAssetName: String? = null,
    val apkAssetSizeBytes: Long = 0L,
    /**
     * versionCode of the release, parsed from a `versionCode: N` line in the
     * release body. String versionName comparison ([isNewer]) is fine for a
     * "there's an update" banner, but too loose for a flow that installs an
     * APK automatically — "1.5.2" vs "1.5.2-hotfix" compares equal there.
     * Null when the release body doesn't declare one.
     */
    val versionCode: Int? = null
)

/**
 * Checks GitHub Releases for a newer version than what's installed. Plain
 * HttpURLConnection + org.json — both built into Android, so this needs no
 * extra networking dependency for a single GET-and-parse.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val OWNER = "rudrakshchatterjee3-creator"
    private const val REPO = "Jadoo-DSP"

    /** Returns the latest GitHub release, or null on any failure (offline, rate-limited, etc). */
    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GitHub releases check failed: HTTP ${connection.responseCode}")
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val releaseBody = json.optString("body", "")
            val apkAsset = findApkAsset(json)
            ReleaseInfo(
                tagName = json.getString("tag_name"),
                name = json.optString("name", json.getString("tag_name")),
                body = releaseBody,
                htmlUrl = json.optString("html_url", ""),
                apkAssetUrl = apkAsset?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                apkAssetName = apkAsset?.optString("name")?.takeIf { it.isNotBlank() },
                apkAssetSizeBytes = apkAsset?.optLong("size", 0L) ?: 0L,
                versionCode = parseVersionCode(releaseBody)
            )
        } catch (e: Exception) {
            Log.w(TAG, "GitHub releases check failed: ${e.message}")
            null
        }
    }

    /**
     * Picks the release's APK asset. GitHub releases routinely carry mapping
     * files, checksums and source archives alongside the build, so this
     * matches on the .apk extension rather than taking assets[0].
     */
    private fun findApkAsset(release: JSONObject): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) return asset
        }
        return null
    }

    /**
     * Reads a `versionCode: N` line out of the release body — the only place
     * GitHub's release API can carry it, since versionCode lives in the APK's
     * manifest and isn't exposed as release metadata.
     */
    fun parseVersionCode(body: String): Int? {
        val match = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE).find(body)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** True if [remoteTag] (e.g. "v1.2" or "1.2") is a strictly newer version than [installedVersionName] (e.g. "1.0"). */
    fun isNewer(remoteTag: String, installedVersionName: String): Boolean {
        val remote = parseVersion(remoteTag) ?: return false
        val installed = parseVersion(installedVersionName) ?: return false
        for (i in 0 until maxOf(remote.size, installed.size)) {
            val r = remote.getOrElse(i) { 0 }
            val v = installed.getOrElse(i) { 0 }
            if (r != v) return r > v
        }
        return false
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split(".").map { it.takeWhile { c -> c.isDigit() } }
        if (parts.any { it.isEmpty() }) return null
        return parts.map { it.toInt() }
    }

    /**
     * Splits a release body into clean display lines for the in-app dialog:
     * stops at the second markdown header (e.g. an "## Installing" section,
     * which isn't changelog content), drops code fences, strips bullet
     * markers and bold/code asterisks/backticks. GitHub's release page
     * still renders the original markdown — this is only for the
     * plain-text in-app view.
     */
    fun changelogLines(body: String): List<String> {
        val result = mutableListOf<String>()
        var seenFirstHeader = false
        for (rawLine in body.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#")) {
                if (seenFirstHeader) break
                seenFirstHeader = true
                continue
            }
            if (trimmed.startsWith("```")) continue
            val cleaned = trimmed.removePrefix("-").removePrefix("*").trim()
                .replace("**", "").replace("`", "")
            if (cleaned.isNotEmpty()) result.add(cleaned)
        }
        return result
    }
}
