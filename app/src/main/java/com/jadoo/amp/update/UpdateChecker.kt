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
    val htmlUrl: String
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
            ReleaseInfo(
                tagName = json.getString("tag_name"),
                name = json.optString("name", json.getString("tag_name")),
                body = json.optString("body", ""),
                htmlUrl = json.optString("html_url", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "GitHub releases check failed: ${e.message}")
            null
        }
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

    /** Splits a release body into display lines — bullet markers stripped, blank lines dropped. */
    fun changelogLines(body: String): List<String> =
        body.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() && it != "#" }
}
