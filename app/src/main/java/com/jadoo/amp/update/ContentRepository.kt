package com.jadoo.amp.update

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jadoo.amp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Context.contentDataStore by preferencesDataStore(name = "remote_content")

/**
 * Fetches, caches and serves the Lane A content document (see [RemoteContent]).
 *
 * Three-tier resolution, applied in order at startup:
 *
 *  1. **Bundled** (`res/raw/jadoo_content.json`) — always present, so a cold
 *     first launch with no network still has a complete, valid document. This
 *     is the floor: the app is never in a state where content is "missing".
 *  2. **Cached** — the last document successfully fetched, stored raw so a
 *     later APK with a newer parser can re-read it without a refetch.
 *  3. **Network** — refreshed in the background; replaces the cache only if it
 *     parses cleanly AND is not older than what's already cached.
 *
 * A failed fetch is a no-op, not an error state. The app keeps running on
 * whatever tier it last resolved.
 */
class ContentRepository(private val context: Context) {

    companion object {
        private const val TAG = "ContentRepository"
        private const val OWNER = "rudrakshchatterjee3-creator"
        private const val REPO = "Jadoo-DSP"

        /**
         * Served from the repo's default branch rather than as a release asset
         * so publishing new content is a plain commit — the entire point of
         * Lane A is that shipping a preset pack or a retuned SBC curve doesn't
         * require cutting a release.
         */
        private const val CONTENT_URL =
            "https://raw.githubusercontent.com/$OWNER/$REPO/master/content/jadoo-content.json"

        /** Don't refetch more than once every 6 hours on app launches. */
        private const val REFRESH_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    }

    private object Keys {
        val cachedJson = stringPreferencesKey("cached_content_json")
        val lastFetchMillis = longPreferencesKey("last_content_fetch_millis")
    }

    private val _content = MutableStateFlow(RemoteContent.EMPTY)
    val content: StateFlow<RemoteContent> = _content.asStateFlow()

    /**
     * Resolves bundled → cached, then refreshes from the network if due.
     * Safe to call on every app launch.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadBundled()?.let { _content.value = it }

        val prefs = context.contentDataStore.data.first()
        prefs[Keys.cachedJson]?.let { raw ->
            RemoteContent.parse(raw)?.let { cached ->
                // Guard against a cache written by a newer APK whose content
                // has since been rolled back — keep whichever is newer.
                if (cached.contentVersion >= _content.value.contentVersion) {
                    _content.value = cached
                }
            }
        }

        val lastFetch = prefs[Keys.lastFetchMillis] ?: 0L
        if (System.currentTimeMillis() - lastFetch >= REFRESH_INTERVAL_MILLIS) {
            refresh()
        }
    }

    /**
     * Force a fetch regardless of the refresh interval. Returns true only if a
     * genuinely newer document was adopted.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val raw = fetchRaw() ?: return@withContext false
        val parsed = RemoteContent.parse(raw) ?: run {
            Log.w(TAG, "Fetched content rejected by parser — keeping current")
            // Still record the attempt so a permanently-malformed document
            // doesn't cause a refetch on every single launch.
            markFetched()
            return@withContext false
        }
        if (parsed.contentVersion < _content.value.contentVersion) {
            Log.w(TAG, "Fetched content v${parsed.contentVersion} is older than " +
                "current v${_content.value.contentVersion} — ignoring")
            markFetched()
            return@withContext false
        }
        val isNew = parsed.contentVersion > _content.value.contentVersion
        _content.value = parsed
        context.contentDataStore.edit { p ->
            p[Keys.cachedJson] = raw
            p[Keys.lastFetchMillis] = System.currentTimeMillis()
        }
        Log.i(TAG, "Content updated to v${parsed.contentVersion} " +
            "(${parsed.presets.size} presets, ${parsed.headphoneProfiles.size} device profiles)")
        isNew
    }

    private suspend fun markFetched() {
        context.contentDataStore.edit { it[Keys.lastFetchMillis] = System.currentTimeMillis() }
    }

    /**
     * Plain HttpURLConnection, matching UpdateChecker — a single GET needs no
     * networking dependency. HTTPS only; a redirect to plain HTTP is refused
     * rather than followed, since this payload drives DSP gain values.
     */
    private fun fetchRaw(): String? {
        return try {
            val url = URL(CONTENT_URL)
            if (!url.protocol.equals("https", ignoreCase = true)) {
                Log.w(TAG, "Refusing non-HTTPS content URL")
                return null
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Content fetch failed: HTTP ${connection.responseCode}")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Content fetch failed: ${e.message}")
            null
        }
    }

    private fun loadBundled(): RemoteContent? = try {
        context.resources.openRawResource(R.raw.jadoo_content)
            .bufferedReader().use { it.readText() }
            .let { RemoteContent.parse(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Bundled content unreadable: ${e.message}")
        null
    }
}
