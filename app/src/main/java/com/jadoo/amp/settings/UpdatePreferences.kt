package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.updateDataStore by preferencesDataStore(name = "update_prefs")

/**
 * Remembers which release tag the "What's New" dialog has already been
 * shown for, so the update check can run on every launch (as intended)
 * without re-showing the same changelog every single time.
 */
class UpdatePreferences(private val context: Context) {
    private object Keys {
        val lastSeenTag = stringPreferencesKey("last_seen_release_tag")
    }

    suspend fun lastSeenTag(): String? =
        context.updateDataStore.data.first()[Keys.lastSeenTag]

    suspend fun markSeen(tag: String) {
        context.updateDataStore.edit { it[Keys.lastSeenTag] = tag }
    }
}
