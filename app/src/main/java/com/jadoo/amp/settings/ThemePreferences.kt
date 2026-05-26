package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")

data class ThemeSettings(
    val useMaterialYou: Boolean = true,
    val customPrimaryColor: Int = 0xFF276A30.toInt()
)

class ThemePreferences(private val context: Context) {
    private object Keys {
        val useMaterialYou = booleanPreferencesKey("use_material_you")
        val customPrimaryColor = intPreferencesKey("custom_primary_color")
    }

    val settings: Flow<ThemeSettings> = context.themeDataStore.data.map { preferences ->
        ThemeSettings(
            useMaterialYou = preferences[Keys.useMaterialYou] ?: true,
            customPrimaryColor = preferences[Keys.customPrimaryColor] ?: 0xFF276A30.toInt()
        )
    }

    suspend fun setUseMaterialYou(enabled: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[Keys.useMaterialYou] = enabled
        }
    }

    suspend fun setCustomPrimaryColor(color: Int) {
        context.themeDataStore.edit { preferences ->
            preferences[Keys.customPrimaryColor] = color
        }
    }
}
