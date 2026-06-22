package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
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
    // Tube Warmth
    val tubeWarmthEnabled: Boolean = false,
    val tubeWarmthIntensity: Float = 0.5f,
    // Mobile Bass
    val mobileBassEnabled: Boolean = false,
    val mobileBassIntensity: Float = 0.5f,
    // Parametric EQ (8 bands, serialised as "type,freq,gain,q,enabled" joined by "|")
    val peqEnabled: Boolean = false,
    val peqBands: String = ""       // "" means all-default (not yet configured)
)

/**
 * Persists DSP settings per output-device profile (e.g. "speaker", "wired",
 * "bt_WH-1000XM4"), mirroring Wavelet's per-output-device EQ profiles.
 *
 * Each setting is stored under a device-suffixed key ("master_enabled_wired").
 * If a device has never been seen before (no suffixed keys yet), [load] falls
 * back per-field to the original un-suffixed "legacy" keys (the app's
 * settings before this feature existed) so a brand-new device profile starts
 * from "whatever your last general settings were" instead of blank defaults.
 * Once [save] is called for that device, its own suffixed values take over.
 */
class SessionPreferences(private val context: Context) {

    private object KeyNames {
        const val MASTER_ENABLED   = "master_enabled"
        const val JADOO_ENABLED    = "jadoo_enabled"
        const val AUTO_EQ_MODE     = "auto_eq_mode"
        const val PRE_GAIN         = "pre_gain"
        const val POST_GAIN        = "post_gain"
        const val HI_RES_ENABLED   = "hi_res_enabled"
        const val DBFB_MODE        = "dbfb_mode"
        const val HDR_ENABLED      = "hdr_enabled"
        const val HDR_MODE         = "hdr_mode"
        const val SURROUND_MODE    = "surround_mode"
        const val BAND_GAINS       = "band_gains"
        const val ANALOG_BASS_ENABLED        = "analog_bass_enabled"
        const val ANALOG_BASS_DRIVE          = "analog_bass_drive"
        const val ANALOG_BASS_WARMTH         = "analog_bass_warmth"
        const val ANALOG_BASS_DRIFT          = "analog_bass_drift"
        const val ANALOG_BASS_PULTEC_BOOST   = "analog_bass_pultec_boost"
        const val ANALOG_BASS_PULTEC_CUT     = "analog_bass_pultec_cut"
        const val ANALOG_BASS_PULTEC_FREQ_IDX = "analog_bass_pultec_freq_idx"
        const val TUBE_WARMTH_ENABLED   = "tube_warmth_enabled"
        const val TUBE_WARMTH_INTENSITY = "tube_warmth_intensity"
        const val MOBILE_BASS_ENABLED   = "mobile_bass_enabled"
        const val MOBILE_BASS_INTENSITY = "mobile_bass_intensity"
        const val PEQ_ENABLED = "peq_enabled"
        const val PEQ_BANDS   = "peq_bands"
    }

    // Legacy (pre-per-device) un-suffixed keys, kept only as a one-time
    // migration/bootstrap source for devices with no profile of their own yet.
    private object LegacyKeys {
        val masterEnabled = booleanPreferencesKey(KeyNames.MASTER_ENABLED)
        val jadooEnabled  = booleanPreferencesKey(KeyNames.JADOO_ENABLED)
        val autoEqMode    = stringPreferencesKey(KeyNames.AUTO_EQ_MODE)
        val preGain       = floatPreferencesKey(KeyNames.PRE_GAIN)
        val postGain      = floatPreferencesKey(KeyNames.POST_GAIN)
        val hiResEnabled  = booleanPreferencesKey(KeyNames.HI_RES_ENABLED)
        val dbfbMode      = stringPreferencesKey(KeyNames.DBFB_MODE)
        val hdrEnabled    = booleanPreferencesKey(KeyNames.HDR_ENABLED)
        val hdrMode       = stringPreferencesKey(KeyNames.HDR_MODE)
        val surroundMode  = stringPreferencesKey(KeyNames.SURROUND_MODE)
        val bandGains     = stringPreferencesKey(KeyNames.BAND_GAINS)
        val analogBassEnabled       = booleanPreferencesKey(KeyNames.ANALOG_BASS_ENABLED)
        val analogBassDrive         = floatPreferencesKey(KeyNames.ANALOG_BASS_DRIVE)
        val analogBassWarmth        = floatPreferencesKey(KeyNames.ANALOG_BASS_WARMTH)
        val analogBassDrift         = floatPreferencesKey(KeyNames.ANALOG_BASS_DRIFT)
        val analogBassPultecBoost   = floatPreferencesKey(KeyNames.ANALOG_BASS_PULTEC_BOOST)
        val analogBassPultecCut     = floatPreferencesKey(KeyNames.ANALOG_BASS_PULTEC_CUT)
        val analogBassPultecFreqIdx = stringPreferencesKey(KeyNames.ANALOG_BASS_PULTEC_FREQ_IDX)
        val tubeWarmthEnabled   = booleanPreferencesKey(KeyNames.TUBE_WARMTH_ENABLED)
        val tubeWarmthIntensity = floatPreferencesKey(KeyNames.TUBE_WARMTH_INTENSITY)
        val mobileBassEnabled   = booleanPreferencesKey(KeyNames.MOBILE_BASS_ENABLED)
        val mobileBassIntensity = floatPreferencesKey(KeyNames.MOBILE_BASS_INTENSITY)
        val peqEnabled  = booleanPreferencesKey(KeyNames.PEQ_ENABLED)
        val peqBands    = stringPreferencesKey(KeyNames.PEQ_BANDS)
    }

    private fun deviceKeyName(base: String, deviceKey: String) = "${base}_$deviceKey"
    private fun boolKey(base: String, deviceKey: String) = booleanPreferencesKey(deviceKeyName(base, deviceKey))
    private fun floatKey(base: String, deviceKey: String) = floatPreferencesKey(deviceKeyName(base, deviceKey))
    private fun stringKey(base: String, deviceKey: String) = stringPreferencesKey(deviceKeyName(base, deviceKey))

    suspend fun save(state: SessionState, deviceKey: String) {
        context.sessionDataStore.edit { p ->
            p[boolKey(KeyNames.MASTER_ENABLED, deviceKey)]   = state.masterEnabled
            p[boolKey(KeyNames.JADOO_ENABLED, deviceKey)]    = state.jadooEnabled
            p[stringKey(KeyNames.AUTO_EQ_MODE, deviceKey)]   = state.autoEqMode
            p[floatKey(KeyNames.PRE_GAIN, deviceKey)]        = state.preGain
            p[floatKey(KeyNames.POST_GAIN, deviceKey)]       = state.postGain
            p[boolKey(KeyNames.HI_RES_ENABLED, deviceKey)]   = state.hiResEnabled
            p[stringKey(KeyNames.DBFB_MODE, deviceKey)]      = state.dbfbMode
            p[boolKey(KeyNames.HDR_ENABLED, deviceKey)]      = state.hdrEnabled
            p[stringKey(KeyNames.HDR_MODE, deviceKey)]       = state.hdrMode
            p[stringKey(KeyNames.SURROUND_MODE, deviceKey)]  = state.surroundMode
            p[stringKey(KeyNames.BAND_GAINS, deviceKey)]     = state.bandGains.joinToString(",")
            p[boolKey(KeyNames.ANALOG_BASS_ENABLED, deviceKey)]     = state.analogBassEnabled
            p[floatKey(KeyNames.ANALOG_BASS_DRIVE, deviceKey)]      = state.analogBassDrive
            p[floatKey(KeyNames.ANALOG_BASS_WARMTH, deviceKey)]     = state.analogBassWarmth
            p[floatKey(KeyNames.ANALOG_BASS_DRIFT, deviceKey)]      = state.analogBassDrift
            p[floatKey(KeyNames.ANALOG_BASS_PULTEC_BOOST, deviceKey)] = state.analogBassPultecBoost
            p[floatKey(KeyNames.ANALOG_BASS_PULTEC_CUT, deviceKey)]   = state.analogBassPultecCut
            p[stringKey(KeyNames.ANALOG_BASS_PULTEC_FREQ_IDX, deviceKey)] = state.analogBassPultecFreqIndex.toString()
            p[boolKey(KeyNames.TUBE_WARMTH_ENABLED, deviceKey)]   = state.tubeWarmthEnabled
            p[floatKey(KeyNames.TUBE_WARMTH_INTENSITY, deviceKey)] = state.tubeWarmthIntensity
            p[boolKey(KeyNames.MOBILE_BASS_ENABLED, deviceKey)]   = state.mobileBassEnabled
            p[floatKey(KeyNames.MOBILE_BASS_INTENSITY, deviceKey)] = state.mobileBassIntensity
            p[boolKey(KeyNames.PEQ_ENABLED, deviceKey)] = state.peqEnabled
            p[stringKey(KeyNames.PEQ_BANDS, deviceKey)] = state.peqBands
        }
    }

    suspend fun load(deviceKey: String): SessionState? {
        val p = context.sessionDataStore.data.first()

        val hasSuffixed = p[boolKey(KeyNames.MASTER_ENABLED, deviceKey)] != null
        val hasLegacy = p[LegacyKeys.masterEnabled] != null
        if (!hasSuffixed && !hasLegacy) return null

        // Per-field fallback: this device's own saved value, else the legacy
        // un-suffixed value (one-time bootstrap for a brand-new device), else default.
        fun bool(base: String, legacy: Preferences.Key<Boolean>, default: Boolean) =
            p[boolKey(base, deviceKey)] ?: p[legacy] ?: default
        fun float(base: String, legacy: Preferences.Key<Float>, default: Float) =
            p[floatKey(base, deviceKey)] ?: p[legacy] ?: default
        fun string(base: String, legacy: Preferences.Key<String>, default: String) =
            p[stringKey(base, deviceKey)] ?: p[legacy] ?: default

        return SessionState(
            masterEnabled   = bool(KeyNames.MASTER_ENABLED, LegacyKeys.masterEnabled, false),
            jadooEnabled    = bool(KeyNames.JADOO_ENABLED, LegacyKeys.jadooEnabled, false),
            autoEqMode      = string(KeyNames.AUTO_EQ_MODE, LegacyKeys.autoEqMode, "HarmanCurve"),
            preGain         = float(KeyNames.PRE_GAIN, LegacyKeys.preGain, 0f),
            postGain        = float(KeyNames.POST_GAIN, LegacyKeys.postGain, 0f),
            hiResEnabled    = bool(KeyNames.HI_RES_ENABLED, LegacyKeys.hiResEnabled, false),
            dbfbMode        = string(KeyNames.DBFB_MODE, LegacyKeys.dbfbMode, "Off"),
            hdrEnabled      = bool(KeyNames.HDR_ENABLED, LegacyKeys.hdrEnabled, false),
            hdrMode         = string(KeyNames.HDR_MODE, LegacyKeys.hdrMode, "Restoration"),
            surroundMode    = string(KeyNames.SURROUND_MODE, LegacyKeys.surroundMode, "Off"),
            bandGains       = (p[stringKey(KeyNames.BAND_GAINS, deviceKey)] ?: p[LegacyKeys.bandGains])
                ?.split(",")
                ?.mapNotNull { it.toFloatOrNull() }
                ?.toFloatArray()
                ?.takeIf { it.size == 15 }
                ?: FloatArray(15) { 0f },
            analogBassEnabled     = bool(KeyNames.ANALOG_BASS_ENABLED, LegacyKeys.analogBassEnabled, false),
            analogBassDrive       = float(KeyNames.ANALOG_BASS_DRIVE, LegacyKeys.analogBassDrive, 0.4f),
            analogBassWarmth      = float(KeyNames.ANALOG_BASS_WARMTH, LegacyKeys.analogBassWarmth, 0.7f),
            analogBassDrift       = float(KeyNames.ANALOG_BASS_DRIFT, LegacyKeys.analogBassDrift, 0.2f),
            analogBassPultecBoost = float(KeyNames.ANALOG_BASS_PULTEC_BOOST, LegacyKeys.analogBassPultecBoost, 0.5f),
            analogBassPultecCut   = float(KeyNames.ANALOG_BASS_PULTEC_CUT, LegacyKeys.analogBassPultecCut, 0.3f),
            analogBassPultecFreqIndex = (p[stringKey(KeyNames.ANALOG_BASS_PULTEC_FREQ_IDX, deviceKey)]
                ?: p[LegacyKeys.analogBassPultecFreqIdx])?.toIntOrNull() ?: 2,
            tubeWarmthEnabled   = bool(KeyNames.TUBE_WARMTH_ENABLED, LegacyKeys.tubeWarmthEnabled, false),
            tubeWarmthIntensity = float(KeyNames.TUBE_WARMTH_INTENSITY, LegacyKeys.tubeWarmthIntensity, 0.5f),
            mobileBassEnabled   = bool(KeyNames.MOBILE_BASS_ENABLED, LegacyKeys.mobileBassEnabled, false),
            mobileBassIntensity = float(KeyNames.MOBILE_BASS_INTENSITY, LegacyKeys.mobileBassIntensity, 0.5f),
            peqEnabled = bool(KeyNames.PEQ_ENABLED, LegacyKeys.peqEnabled, false),
            peqBands   = string(KeyNames.PEQ_BANDS, LegacyKeys.peqBands, "")
        )
    }
}
