package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jadoo.amp.audio.EqBands
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.eqPresetDataStore by preferencesDataStore(name = "eq_preset_preferences")

data class SavedEqPreset(
    val name: String,
    val gains: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SavedEqPreset
        if (name != other.name) return false
        return gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + gains.contentHashCode()
        return result
    }
}

class EqPresetPreferences(private val context: Context) {
    private object Keys {
        val savedPresets = stringPreferencesKey("saved_presets")
    }

    val presets: Flow<List<SavedEqPreset>> = context.eqPresetDataStore.data.map { preferences ->
        decodePresets(preferences[Keys.savedPresets].orEmpty())
    }

    suspend fun savePreset(name: String, gains: FloatArray) {
        val cleanName = name.trim().ifEmpty { "Custom Preset" }
        context.eqPresetDataStore.edit { preferences ->
            val currentPresets = decodePresets(preferences[Keys.savedPresets].orEmpty())
            val nextPresets = currentPresets
                .filterNot { it.name.equals(cleanName, ignoreCase = true) }
                .plus(
                    SavedEqPreset(
                        name = cleanName,
                        gains = FloatArray(EqBands.count) { index ->
                            gains.getOrNull(index)?.coerceIn(-15f, 15f) ?: 0f
                        }
                    )
                )
            preferences[Keys.savedPresets] = encodePresets(nextPresets)
        }
    }

    private fun encodePresets(presets: List<SavedEqPreset>): String {
        val root = JSONArray()
        presets.forEach { preset ->
            val gains = JSONArray()
            preset.gains.forEach { gain ->
                gains.put(gain.toDouble())
            }
            root.put(
                JSONObject()
                    .put("name", preset.name)
                    .put("gains", gains)
            )
        }
        return root.toString()
    }

    private fun decodePresets(raw: String): List<SavedEqPreset> {
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val root = JSONArray(raw)
            List(root.length()) { presetIndex ->
                val item = root.getJSONObject(presetIndex)
                val gains = item.getJSONArray("gains")
                SavedEqPreset(
                    name = item.getString("name"),
                    gains = FloatArray(EqBands.count) { index ->
                        gains.optDouble(index, 0.0).toFloat().coerceIn(-15f, 15f)
                    }
                )
            }
        }.getOrDefault(emptyList())
    }
}
