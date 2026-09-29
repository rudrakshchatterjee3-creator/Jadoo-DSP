package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.updateDataStore by preferencesDataStore(name = "update_prefs")

/**
 * Tracks the "What's New" dialog's snooze state for a release tag.
 *
 * There is no permanent "seen, never show again" — only "Remind me later"
 * (snoozeUntilMillis) postpones the popup for a while. Opening "Download
 * Update" and then backing out of the browser without actually installing
 * must NOT suppress the popup forever, so it isn't recorded as "seen"
 * at all — only the explicit snooze action delays it.
 */
class UpdatePreferences(private val context: Context) {
    private object Keys {
        val snoozedTag = stringPreferencesKey("snoozed_release_tag")
        val snoozeUntilMillis = longPreferencesKey("snooze_until_millis")
    }

    companion object {
        const val SNOOZE_DURATION_MILLIS = 24L * 60 * 60 * 1000 // 24 hours
    }

    /** True if [tag] is currently snoozed (i.e. the popup shouldn't show yet). */
    suspend fun isSnoozed(tag: String): Boolean {
        val data = context.updateDataStore.data.first()
        if (data[Keys.snoozedTag] != tag) return false
        val until = data[Keys.snoozeUntilMillis] ?: return false
        return System.currentTimeMillis() < until
    }

    suspend fun snooze(tag: String) {
        context.updateDataStore.edit {
            it[Keys.snoozedTag] = tag
            it[Keys.snoozeUntilMillis] = System.currentTimeMillis() + SNOOZE_DURATION_MILLIS
        }
    }
}
