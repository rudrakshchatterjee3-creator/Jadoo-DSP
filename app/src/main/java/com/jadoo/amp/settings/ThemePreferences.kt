package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.preferencesDataStore
import com.jadoo.amp.ui.theme.ThemeMode
import com.jadoo.amp.ui.theme.ThemeSpec
import com.jadoo.amp.ui.theme.ToneMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")

/**
 * Persisted appearance settings.
 *
 * Four values where there used to be two, and the two legacy keys are still
 * read.
 */
data class ThemeSettings(
    /** "Brand" | "MaterialYou" | "CustomSeed" */
    val mode: String = "Brand",
    /** "System" | "Light" | "Dark" */
    val tone: String = "System",
    val amoled: Boolean = false,
    val seedColor: Int = 0xFFE1A730.toInt()
)

/**
 * Persisted strings to the typed [ThemeSpec] the theme layer consumes.
 *
 * Stored as strings rather than enum ordinals on purpose: an ordinal silently
 * changes meaning the moment someone reorders the enum, and this store outlives
 * any given version of it.
 */
fun ThemeSettings.toSpec(): ThemeSpec = ThemeSpec(
    mode = when (mode) {
        "MaterialYou" -> ThemeMode.MaterialYou
        "CustomSeed" -> ThemeMode.CustomSeed
        else -> ThemeMode.Brand
    },
    tone = when (tone) {
        "Light" -> ToneMode.Light
        "Dark" -> ToneMode.Dark
        else -> ToneMode.System
    },
    amoled = amoled,
    seed = Color(seedColor)
)

class ThemePreferences(private val context: Context) {
    private object Keys {
        val mode = stringPreferencesKey("theme_mode")
        val tone = stringPreferencesKey("tone_mode")
        val amoled = booleanPreferencesKey("amoled")
        val seedColor = intPreferencesKey("seed_color")
    }

    /**
     * The pre-v1.6 keys. Kept permanently, not deleted after a migration.
     */
    private object LegacyKeys {
        val useMaterialYou = booleanPreferencesKey("use_material_you")
        val customPrimaryColor = intPreferencesKey("custom_primary_color")
    }

    /**
     * Read-through migration: the new keys are consulted first, and the legacy
     * pair is the fallback when a new key is absent.
     *
     * Deliberately not the DataStore `Migration` API, and deliberately no
     * write-on-read. A migration that rewrites the store has a window in which
     * it is half-applied — and if it crashes inside that window, or the app is
     * killed mid-write, the user's appearance is left in a state neither
     * version understands. Falling back on read has no window at all: the old
     * values simply stay valid forever, and the first time the user touches an
     * appearance control the new keys are written and take over naturally.
     *
     * Costs four bytes of DataStore that will never be reclaimed. Worth it.
     *
     * [wasExistingUserAtLaunch] resolves the one real ambiguity left in that
     * migration: a user who has NEITHER a new-format key NOR a legacy
     * `useMaterialYou` value. That's every fresh install — but it's ALSO
     * every pre-v1.6 user who simply never opened theme settings, since the
     * old app never wrote a key unless the user touched the toggle. Without
     * this flag both cases fall into the same `null` branch and an existing
     * user silently wakes up on the new Brand (gold/black) look on their
     * first launch after updating, having never asked for it. It's a plain,
     * already-resolved Boolean (read once, synchronously, before Compose
     * starts — see MainActivity.onCreate) rather than a live/reactive
     * signal on purpose: this must be decided once, at cold start, not
     * re-evaluated later — a FRESH install's onboarding-completed flag also
     * flips to true partway through the same session (right after the
     * tutorial), and reacting to that live would retroactively switch a
     * brand-new user's theme out from under them mid-session.
     *
     * Not a byte-identical restoration of the old hardcoded green scheme —
     * that ColorScheme object no longer exists in this codebase to restore.
     * MaterialYou is the closest honest equivalent: it's this system's own
     * "don't impose a brand identity" mode, dynamic per-device rather than a
     * fixed opinionated palette, which is the actual thing an untouched
     * pre-rewrite install was doing (not deliberately choosing green, just
     * never being pushed toward any particular look).
     */
    fun settings(wasExistingUserAtLaunch: Boolean): Flow<ThemeSettings> = context.themeDataStore.data.map { p ->
        val legacyMaterialYou = p[LegacyKeys.useMaterialYou]
        val legacySeed = p[LegacyKeys.customPrimaryColor]

        ThemeSettings(
            mode = p[Keys.mode] ?: when (legacyMaterialYou) {
                // An existing user who had Material You on keeps it; one who
                // had turned it off was using a custom seed colour, so that is
                // what they get back.
                true -> "MaterialYou"
                false -> "CustomSeed"
                // Neither key exists. A fresh install lands on Brand — the
                // point of the whole redesign. An existing user who never
                // touched theme settings lands on MaterialYou instead, so
                // updating doesn't silently change their look.
                null -> if (wasExistingUserAtLaunch) "MaterialYou" else "Brand"
            },
            // No legacy equivalent — there was never a tone override.
            tone = p[Keys.tone] ?: "System",
            amoled = p[Keys.amoled] ?: false,
            seedColor = p[Keys.seedColor] ?: legacySeed ?: 0xFFE1A730.toInt()
        )
    }

    suspend fun setMode(mode: String) {
        context.themeDataStore.edit { it[Keys.mode] = mode }
    }

    suspend fun setTone(tone: String) {
        context.themeDataStore.edit { it[Keys.tone] = tone }
    }

    suspend fun setAmoled(enabled: Boolean) {
        context.themeDataStore.edit { it[Keys.amoled] = enabled }
    }

    suspend fun setSeedColor(color: Int) {
        context.themeDataStore.edit { it[Keys.seedColor] = color }
    }
}
