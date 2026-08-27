package com.jadoo.amp.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore(name = "session_state")

data class SessionState(
    val masterEnabled: Boolean = false,
    val preGain: Float = 0f,
    val postGain: Float = 0f,
    val hiResEnabled: Boolean = false,
    val dbfbMode: String = "Off",
    val hdrEnabled: Boolean = false,
    val hdrMode: String = "Restoration",
    val surroundMode: String = "Off",
    val bandGains: FloatArray = FloatArray(15),
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
    // Harmonic Exciter
    val harmonicExciterEnabled: Boolean = false,
    val harmonicExciterIntensity: Float = 0.5f,
    // Parametric EQ (8 bands, serialised as "type,freq,gain,q,enabled" joined by "|")
    val peqEnabled: Boolean = false,
    val peqBands: String = "",       // "" means all-default (not yet configured)
    // SBC Enhancement: pre-emphasis for Bluetooth SBC codec devices (not for LDAC/LHDC)
    val sbcModeEnabled: Boolean = false,
    // Device type (see DeviceType) and budget-to-flagship quality tier for this output
    // device profile. "General" = no device-aware scaling, matches pre-existing behavior.
    val deviceType: String = "General",
    val deviceQualityTier: Float = 0.5f,
    // Crossfeed: headphone-only tonal-EQ curve (BETA — see
    // JadooDspService.crossfeedShape)
    val crossfeedEnabled: Boolean = false,
    val crossfeedStrength: Float = 0.5f,
    // Loudness Contour (ISO 226): level-tracking tonal compensation. The
    // reference level is genuinely per-output-device — the SPL your IEMs hit
    // at max volume is nothing like your phone speaker's — so it lives in the
    // per-device profile alongside everything else rather than as a global.
    val loudnessEnabled: Boolean = false,
    val loudnessAmount: Float = 0.7f,
    val loudnessReferencePhon: Float = 80f,
    // Gain staging. ON pays the feature stack's gain budget across the limiter
    // threshold AND a transparent input trim; OFF dumps the whole budget on
    // the limiter threshold, which is what the app did before v1.6 and which
    // could reach -19 dBFS on a 10:1 brickwall. See DspEngine.splitGainBudget.
    val preciseGainStaging: Boolean = true,
    // Name of the built-in or custom EQ preset last explicitly selected for
    // this device profile, so the highlighted chip / "Overwrite" target
    // survives a relaunch instead of resetting to none every cold start.
    // "" means no preset is currently tracked as selected (matches peqBands'
    // own empty-string-means-unset convention). The actual band GAINS this
    // preset produced already persisted before this field existed — this
    // only restores which NAME the UI should show as selected.
    val selectedPresetName: String = "",
    // Name of the content-channel device profile applied for this output, or
    // "" for none. Only the NAME is stored — the correction curve itself is
    // resolved from live content on load, so a later content update that
    // improves a curve reaches devices already using it. See
    // JadooDspService.applyDeviceProfileCurve.
    val deviceProfileName: String = ""
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
        const val HARMONIC_EXCITER_ENABLED   = "harmonic_exciter_enabled"
        const val HARMONIC_EXCITER_INTENSITY = "harmonic_exciter_intensity"
        const val PEQ_ENABLED = "peq_enabled"
        const val PEQ_BANDS   = "peq_bands"
        const val SBC_MODE_ENABLED = "sbc_mode_enabled"
        const val DEVICE_TYPE = "device_type"
        const val DEVICE_QUALITY_TIER = "device_quality_tier"
        const val CROSSFEED_ENABLED = "crossfeed_enabled"
        const val CROSSFEED_STRENGTH = "crossfeed_strength"
        const val LOUDNESS_ENABLED = "loudness_enabled"
        const val LOUDNESS_AMOUNT = "loudness_amount"
        const val LOUDNESS_REFERENCE_PHON = "loudness_reference_phon"
        const val PRECISE_GAIN_STAGING = "precise_gain_staging"
        const val SELECTED_PRESET_NAME = "selected_preset_name"
        const val DEVICE_PROFILE_NAME = "device_profile_name"
    }

    // Legacy (pre-per-device) un-suffixed keys, kept only as a one-time
    // migration/bootstrap source for devices with no profile of their own yet.
    private object LegacyKeys {
        val masterEnabled = booleanPreferencesKey(KeyNames.MASTER_ENABLED)
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
        val analogBassPultecFreqIdx = intPreferencesKey(KeyNames.ANALOG_BASS_PULTEC_FREQ_IDX)
        val tubeWarmthEnabled   = booleanPreferencesKey(KeyNames.TUBE_WARMTH_ENABLED)
        val tubeWarmthIntensity = floatPreferencesKey(KeyNames.TUBE_WARMTH_INTENSITY)
        val mobileBassEnabled   = booleanPreferencesKey(KeyNames.MOBILE_BASS_ENABLED)
        val mobileBassIntensity = floatPreferencesKey(KeyNames.MOBILE_BASS_INTENSITY)
        val harmonicExciterEnabled   = booleanPreferencesKey(KeyNames.HARMONIC_EXCITER_ENABLED)
        val harmonicExciterIntensity = floatPreferencesKey(KeyNames.HARMONIC_EXCITER_INTENSITY)
        val peqEnabled      = booleanPreferencesKey(KeyNames.PEQ_ENABLED)
        val peqBands        = stringPreferencesKey(KeyNames.PEQ_BANDS)
        val sbcModeEnabled  = booleanPreferencesKey(KeyNames.SBC_MODE_ENABLED)
        val deviceType         = stringPreferencesKey(KeyNames.DEVICE_TYPE)
        val deviceQualityTier  = floatPreferencesKey(KeyNames.DEVICE_QUALITY_TIER)
        val crossfeedEnabled   = booleanPreferencesKey(KeyNames.CROSSFEED_ENABLED)
        val crossfeedStrength  = floatPreferencesKey(KeyNames.CROSSFEED_STRENGTH)
        val loudnessEnabled       = booleanPreferencesKey(KeyNames.LOUDNESS_ENABLED)
        val loudnessAmount        = floatPreferencesKey(KeyNames.LOUDNESS_AMOUNT)
        val loudnessReferencePhon = floatPreferencesKey(KeyNames.LOUDNESS_REFERENCE_PHON)
        val preciseGainStaging    = booleanPreferencesKey(KeyNames.PRECISE_GAIN_STAGING)
        val selectedPresetName    = stringPreferencesKey(KeyNames.SELECTED_PRESET_NAME)
        val deviceProfileName     = stringPreferencesKey(KeyNames.DEVICE_PROFILE_NAME)
    }

    private val savedProfileNamesKey = stringPreferencesKey("saved_profile_names")

    /**
     * Packages the user has opted in to per-app profiles for. Stored globally
     * rather than per output device: "YouTube should have its own settings" is
     * a statement about the app, not about which headphones are plugged in.
     * The profile itself is still keyed per device AND per app (see
     * [perAppProfileKey]), so the same app can hold different tuning on
     * different outputs.
     */
    private val perAppPackagesKey = stringPreferencesKey("per_app_profile_packages")

    private fun deviceKeyName(base: String, deviceKey: String) = "${base}_$deviceKey"
    private fun boolKey(base: String, deviceKey: String) = booleanPreferencesKey(deviceKeyName(base, deviceKey))
    private fun floatKey(base: String, deviceKey: String) = floatPreferencesKey(deviceKeyName(base, deviceKey))
    private fun intKey(base: String, deviceKey: String) = intPreferencesKey(deviceKeyName(base, deviceKey))
    private fun stringKey(base: String, deviceKey: String) = stringPreferencesKey(deviceKeyName(base, deviceKey))

    suspend fun save(state: SessionState, deviceKey: String) {
        context.sessionDataStore.edit { p ->
            p[boolKey(KeyNames.MASTER_ENABLED, deviceKey)]   = state.masterEnabled
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
            p[intKey(KeyNames.ANALOG_BASS_PULTEC_FREQ_IDX, deviceKey)] = state.analogBassPultecFreqIndex
            p[boolKey(KeyNames.TUBE_WARMTH_ENABLED, deviceKey)]   = state.tubeWarmthEnabled
            p[floatKey(KeyNames.TUBE_WARMTH_INTENSITY, deviceKey)] = state.tubeWarmthIntensity
            p[boolKey(KeyNames.MOBILE_BASS_ENABLED, deviceKey)]   = state.mobileBassEnabled
            p[floatKey(KeyNames.MOBILE_BASS_INTENSITY, deviceKey)] = state.mobileBassIntensity
            p[boolKey(KeyNames.HARMONIC_EXCITER_ENABLED, deviceKey)]   = state.harmonicExciterEnabled
            p[floatKey(KeyNames.HARMONIC_EXCITER_INTENSITY, deviceKey)] = state.harmonicExciterIntensity
            p[boolKey(KeyNames.PEQ_ENABLED, deviceKey)] = state.peqEnabled
            p[stringKey(KeyNames.PEQ_BANDS, deviceKey)] = state.peqBands
            p[boolKey(KeyNames.SBC_MODE_ENABLED, deviceKey)] = state.sbcModeEnabled
            p[stringKey(KeyNames.DEVICE_TYPE, deviceKey)] = state.deviceType
            p[floatKey(KeyNames.DEVICE_QUALITY_TIER, deviceKey)] = state.deviceQualityTier
            p[boolKey(KeyNames.CROSSFEED_ENABLED, deviceKey)] = state.crossfeedEnabled
            p[floatKey(KeyNames.CROSSFEED_STRENGTH, deviceKey)] = state.crossfeedStrength
            p[boolKey(KeyNames.LOUDNESS_ENABLED, deviceKey)] = state.loudnessEnabled
            p[floatKey(KeyNames.LOUDNESS_AMOUNT, deviceKey)] = state.loudnessAmount
            p[floatKey(KeyNames.LOUDNESS_REFERENCE_PHON, deviceKey)] = state.loudnessReferencePhon
            p[boolKey(KeyNames.PRECISE_GAIN_STAGING, deviceKey)] = state.preciseGainStaging
            p[stringKey(KeyNames.SELECTED_PRESET_NAME, deviceKey)] = state.selectedPresetName
            p[stringKey(KeyNames.DEVICE_PROFILE_NAME, deviceKey)] = state.deviceProfileName
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
        fun int(base: String, legacy: Preferences.Key<Int>, default: Int) =
            p[intKey(base, deviceKey)] ?: p[legacy] ?: default
        fun string(base: String, legacy: Preferences.Key<String>, default: String) =
            p[stringKey(base, deviceKey)] ?: p[legacy] ?: default

        return SessionState(
            masterEnabled   = bool(KeyNames.MASTER_ENABLED, LegacyKeys.masterEnabled, false),
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
                ?: FloatArray(15),
            analogBassEnabled     = bool(KeyNames.ANALOG_BASS_ENABLED, LegacyKeys.analogBassEnabled, false),
            analogBassDrive       = float(KeyNames.ANALOG_BASS_DRIVE, LegacyKeys.analogBassDrive, 0.4f),
            analogBassWarmth      = float(KeyNames.ANALOG_BASS_WARMTH, LegacyKeys.analogBassWarmth, 0.7f),
            analogBassDrift       = float(KeyNames.ANALOG_BASS_DRIFT, LegacyKeys.analogBassDrift, 0.2f),
            analogBassPultecBoost = float(KeyNames.ANALOG_BASS_PULTEC_BOOST, LegacyKeys.analogBassPultecBoost, 0.5f),
            analogBassPultecCut   = float(KeyNames.ANALOG_BASS_PULTEC_CUT, LegacyKeys.analogBassPultecCut, 0.3f),
            analogBassPultecFreqIndex = int(KeyNames.ANALOG_BASS_PULTEC_FREQ_IDX, LegacyKeys.analogBassPultecFreqIdx, 2),
            tubeWarmthEnabled   = bool(KeyNames.TUBE_WARMTH_ENABLED, LegacyKeys.tubeWarmthEnabled, false),
            tubeWarmthIntensity = float(KeyNames.TUBE_WARMTH_INTENSITY, LegacyKeys.tubeWarmthIntensity, 0.5f),
            mobileBassEnabled   = bool(KeyNames.MOBILE_BASS_ENABLED, LegacyKeys.mobileBassEnabled, false),
            mobileBassIntensity = float(KeyNames.MOBILE_BASS_INTENSITY, LegacyKeys.mobileBassIntensity, 0.5f),
            harmonicExciterEnabled   = bool(KeyNames.HARMONIC_EXCITER_ENABLED, LegacyKeys.harmonicExciterEnabled, false),
            harmonicExciterIntensity = float(KeyNames.HARMONIC_EXCITER_INTENSITY, LegacyKeys.harmonicExciterIntensity, 0.5f),
            peqEnabled     = bool(KeyNames.PEQ_ENABLED, LegacyKeys.peqEnabled, false),
            peqBands       = string(KeyNames.PEQ_BANDS, LegacyKeys.peqBands, ""),
            sbcModeEnabled = bool(KeyNames.SBC_MODE_ENABLED, LegacyKeys.sbcModeEnabled, false),
            deviceType = string(KeyNames.DEVICE_TYPE, LegacyKeys.deviceType, "General"),
            deviceQualityTier = float(KeyNames.DEVICE_QUALITY_TIER, LegacyKeys.deviceQualityTier, 0.5f),
            crossfeedEnabled = bool(KeyNames.CROSSFEED_ENABLED, LegacyKeys.crossfeedEnabled, false),
            crossfeedStrength = float(KeyNames.CROSSFEED_STRENGTH, LegacyKeys.crossfeedStrength, 0.5f),
            loudnessEnabled = bool(KeyNames.LOUDNESS_ENABLED, LegacyKeys.loudnessEnabled, false),
            loudnessAmount = float(KeyNames.LOUDNESS_AMOUNT, LegacyKeys.loudnessAmount, 0.7f),
            loudnessReferencePhon = float(
                KeyNames.LOUDNESS_REFERENCE_PHON, LegacyKeys.loudnessReferencePhon, 80f
            ),
            // Defaults true for existing profiles as well as new ones. The old
            // behaviour it replaces is the bug it was written to fix, so
            // inheriting it would be the wrong kind of backwards compatibility.
            preciseGainStaging = bool(
                KeyNames.PRECISE_GAIN_STAGING, LegacyKeys.preciseGainStaging, true
            ),
            deviceProfileName = string(
                KeyNames.DEVICE_PROFILE_NAME, LegacyKeys.deviceProfileName, ""
            ),
            selectedPresetName = string(
                KeyNames.SELECTED_PRESET_NAME, LegacyKeys.selectedPresetName, ""
            )
        )
    }

    // ── Per-app profiles ──────────────────────────────────────────────────

    /**
     * Profile key for [packageName] on [deviceKey]. The "@" separator can't
     * collide with anything: device keys are built from `[a-zA-Z0-9_-]` only
     * (see JadooDspService.computeOutputDeviceKey) and Android package names
     * can't contain "@" either.
     */
    fun perAppProfileKey(deviceKey: String, packageName: String) = "$deviceKey@$packageName"

    /**
     * True if a profile has ever been saved under [key]. Distinct from
     * `load(key) != null`, which also succeeds via the legacy un-suffixed
     * bootstrap path — that fallback is right for a brand-new OUTPUT DEVICE
     * but wrong for a brand-new per-app profile, which should fork from the
     * device profile the user already tuned rather than from pre-per-device
     * legacy settings.
     */
    suspend fun hasProfile(key: String): Boolean {
        val p = context.sessionDataStore.data.first()
        return p[boolKey(KeyNames.MASTER_ENABLED, key)] != null
    }

    val perAppProfilePackages: Flow<Set<String>> = context.sessionDataStore.data.map { p ->
        p[perAppPackagesKey]?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    suspend fun isPerAppProfileEnabled(packageName: String): Boolean {
        val p = context.sessionDataStore.data.first()
        val set = p[perAppPackagesKey]?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        return packageName in set
    }

    suspend fun setPerAppProfileEnabled(packageName: String, enabled: Boolean) {
        context.sessionDataStore.edit { p ->
            val current = p[perAppPackagesKey]?.split("|")
                ?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            if (enabled) current.add(packageName) else current.remove(packageName)
            p[perAppPackagesKey] = current.joinToString("|")
        }
    }

    // ── Custom (imported) profile management ──────────────────────────────

    val customProfileNames: Flow<List<String>> = context.sessionDataStore.data.map { p ->
        p[savedProfileNamesKey]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun profileExists(name: String): Boolean {
        val p = context.sessionDataStore.data.first()
        val existing = p[savedProfileNamesKey] ?: ""
        return name.trim() in existing.split("|")
    }

    suspend fun saveAsCustomProfile(name: String, state: SessionState) {
        val key = "custom_${name.trim()}"
        save(state, key)
        context.sessionDataStore.edit { p ->
            val existing = p[savedProfileNamesKey] ?: ""
            val names = if (existing.isBlank()) mutableListOf() else existing.split("|").toMutableList()
            if (name.trim() !in names) {
                names.add(name.trim())
                p[savedProfileNamesKey] = names.joinToString("|")
            }
        }
    }

    suspend fun loadCustomProfile(name: String): SessionState? = load("custom_${name.trim()}")

    suspend fun deleteCustomProfile(name: String) {
        context.sessionDataStore.edit { p ->
            val existing = p[savedProfileNamesKey] ?: ""
            val names = existing.split("|").toMutableList()
            names.remove(name.trim())
            p[savedProfileNamesKey] = names.filter { it.isNotBlank() }.joinToString("|")
        }
    }
}
