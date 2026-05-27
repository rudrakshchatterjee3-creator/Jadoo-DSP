package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding_prefs")

class OnboardingPreferences(private val context: Context) {
    private object Keys {
        val hasCompleted = booleanPreferencesKey("has_completed_onboarding")
    }

    val hasCompletedOnboarding: Flow<Boolean> = context.onboardingDataStore.data
        .map { preferences -> preferences[Keys.hasCompleted] ?: false }

    suspend fun markCompleted() {
        context.onboardingDataStore.edit { preferences ->
            preferences[Keys.hasCompleted] = true
        }
    }
}
