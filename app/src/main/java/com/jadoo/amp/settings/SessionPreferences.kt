package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.sessionDataStore by preferencesDataStore(name = "session_state")

data class SessionState(
    val masterEnabled: Boolean = false,
    val jadooEnabled: Boolean = false,
    val autoEqMode: String = "HarmanCurve",
    val preGain: Float = 0f,
    val postGain: Float = 0f,
    val headroomDb: Float = 0f,
    val hiResEnabled: Boolean = false,
    val dbfbMode: String = "Off",
    val hdrEnabled: Boolean = false,
    val hdrMode: String = "Restoration",
    val surroundMode: String = "Off",
    val bandGains: FloatArray = FloatArray(15) { 0f },
    // Analog Bass
    val analogBassEnabled: Boolean = false,
    val analogBassDrive: Float = 0.4f,
    val analogBassWarmth: Float = 0.7f,
    val analogBassDrift: Float = 0.2f,
    val analogBassPultecBoost: Float = 0.5f,
    val analogBassPultecCut: Float = 0.3f,
    val analogBassPultecFreqIndex: Int = 2,
    // Parametric EQ (8 bands, serialised as "type,freq,gain,q,enabled" joined by "|")
    val peqEnabled: Boolean = false,
    val peqBands: String = ""       // "" means all-default (not yet configured)
)

class SessionPreferences(private val context: Context) {
    private object Keys {
        val masterEnabled     = booleanPreferencesKey("master_enabled")
        val jadooEnabled      = booleanPreferencesKey("jadoo_enabled")
        val autoEqMode        = stringPreferencesKey("auto_eq_mode")
        val preGain           = floatPreferencesKey("pre_gain")
        val postGain          = floatPreferencesKey("post_gain")
        val headroomDb        = floatPreferencesKey("headroom_db")
        val hiResEnabled      = booleanPreferencesKey("hi_res_enabled")
        val dbfbMode          = stringPreferencesKey("dbfb_mode")
        val hdrEnabled        = booleanPreferencesKey("hdr_enabled")
        val hdrMode           = stringPreferencesKey("hdr_mode")
        val surroundMode      = stringPreferencesKey("surround_mode")
        val bandGains         = stringPreferencesKey("band_gains")
        // Analog Bass
        val analogBassEnabled       = booleanPreferencesKey("analog_bass_enabled")
        val analogBassDrive         = floatPreferencesKey("analog_bass_drive")
        val analogBassWarmth        = floatPreferencesKey("analog_bass_warmth")
        val analogBassDrift         = floatPreferencesKey("analog_bass_drift")
        val analogBassPultecBoost   = floatPreferencesKey("analog_bass_pultec_boost")
        val analogBassPultecCut     = floatPreferencesKey("analog_bass_pultec_cut")
        val analogBassPultecFreqIdx = stringPreferencesKey("analog_bass_pultec_freq_idx")
        // Parametric EQ
        val peqEnabled  = booleanPreferencesKey("peq_enabled")
        val peqBands    = stringPreferencesKey("peq_bands")
    }

    suspend fun save(state: SessionState) {
        context.sessionDataStore.edit { p ->
            p[Keys.masterEnabled]   = state.masterEnabled
            p[Keys.jadooEnabled]    = state.jadooEnabled
            p[Keys.autoEqMode]      = state.autoEqMode
            p[Keys.preGain]         = state.preGain
            p[Keys.postGain]        = state.postGain
            p[Keys.headroomDb]      = state.headroomDb
            p[Keys.hiResEnabled]    = state.hiResEnabled
            p[Keys.dbfbMode]        = state.dbfbMode
            p[Keys.hdrEnabled]      = state.hdrEnabled
            p[Keys.hdrMode]         = state.hdrMode
            p[Keys.surroundMode]    = state.surroundMode
            p[Keys.bandGains]       = state.bandGains.joinToString(",")
            // Analog Bass
            p[Keys.analogBassEnabled]     = state.analogBassEnabled
            p[Keys.analogBassDrive]       = state.analogBassDrive
            p[Keys.analogBassWarmth]      = state.analogBassWarmth
            p[Keys.analogBassDrift]       = state.analogBassDrift
            p[Keys.analogBassPultecBoost] = state.analogBassPultecBoost
            p[Keys.analogBassPultecCut]   = state.analogBassPultecCut
            p[Keys.analogBassPultecFreqIdx] = state.analogBassPultecFreqIndex.toString()
            // Parametric EQ
            p[Keys.peqEnabled] = state.peqEnabled
            p[Keys.peqBands]   = state.peqBands
        }
    }

    suspend fun load(): SessionState? {
        val p = context.sessionDataStore.data.first()
        // Return null if never saved (first launch)
        p[Keys.masterEnabled] ?: return null
        return SessionState(
            masterEnabled   = p[Keys.masterEnabled]   ?: false,
            jadooEnabled    = p[Keys.jadooEnabled]    ?: false,
            autoEqMode      = p[Keys.autoEqMode]      ?: "HarmanCurve",
            preGain         = p[Keys.preGain]         ?: 0f,
            postGain        = p[Keys.postGain]        ?: 0f,
            headroomDb      = p[Keys.headroomDb]      ?: 0f,
            hiResEnabled    = p[Keys.hiResEnabled]    ?: false,
            dbfbMode        = p[Keys.dbfbMode]        ?: "Off",
            hdrEnabled      = p[Keys.hdrEnabled]      ?: false,
            hdrMode         = p[Keys.hdrMode]         ?: "Restoration",
            surroundMode    = p[Keys.surroundMode]    ?: "Off",
            bandGains       = p[Keys.bandGains]
                ?.split(",")
                ?.mapNotNull { it.toFloatOrNull() }
                ?.toFloatArray()
                ?.takeIf { it.size == 15 }
                ?: FloatArray(15) { 0f },
            // Analog Bass
            analogBassEnabled     = p[Keys.analogBassEnabled] ?: false,
            analogBassDrive       = p[Keys.analogBassDrive] ?: 0.4f,
            analogBassWarmth      = p[Keys.analogBassWarmth] ?: 0.7f,
            analogBassDrift       = p[Keys.analogBassDrift] ?: 0.2f,
            analogBassPultecBoost = p[Keys.analogBassPultecBoost] ?: 0.5f,
            analogBassPultecCut   = p[Keys.analogBassPultecCut] ?: 0.3f,
            analogBassPultecFreqIndex = p[Keys.analogBassPultecFreqIdx]?.toIntOrNull() ?: 2,
            // Parametric EQ
            peqEnabled = p[Keys.peqEnabled] ?: false,
            peqBands   = p[Keys.peqBands]   ?: ""
        )
    }
}
