package com.jadoo.amp.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the active device profile's [SessionState] plus every saved
 * [SavedEqPreset] into a single JSON document, and back. Used by the
 * Settings dialog's Export/Import buttons so a user can back up or move
 * their tuning (including manual 15-band EQ values and saved presets)
 * across installs/devices as one file.
 */
object BackupCodec {
    private const val FORMAT_VERSION = 1

    fun encode(state: SessionState, presets: List<SavedEqPreset>): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)

        val s = JSONObject()
        s.put("masterEnabled", state.masterEnabled)
        s.put("preGain", state.preGain)
        s.put("postGain", state.postGain)
        s.put("hiResEnabled", state.hiResEnabled)
        s.put("dbfbMode", state.dbfbMode)
        s.put("hdrEnabled", state.hdrEnabled)
        s.put("hdrMode", state.hdrMode)
        s.put("surroundMode", state.surroundMode)
        s.put("bandGains", JSONArray().apply { state.bandGains.forEach { put(it.toDouble()) } })
        s.put("analogBassEnabled", state.analogBassEnabled)
        s.put("analogBassDrive", state.analogBassDrive)
        s.put("analogBassWarmth", state.analogBassWarmth)
        s.put("analogBassDrift", state.analogBassDrift)
        s.put("analogBassPultecBoost", state.analogBassPultecBoost)
        s.put("analogBassPultecCut", state.analogBassPultecCut)
        s.put("analogBassPultecFreqIndex", state.analogBassPultecFreqIndex)
        s.put("tubeWarmthEnabled", state.tubeWarmthEnabled)
        s.put("tubeWarmthIntensity", state.tubeWarmthIntensity)
        s.put("mobileBassEnabled", state.mobileBassEnabled)
        s.put("mobileBassIntensity", state.mobileBassIntensity)
        s.put("harmonicExciterEnabled", state.harmonicExciterEnabled)
        s.put("harmonicExciterIntensity", state.harmonicExciterIntensity)
        s.put("peqEnabled", state.peqEnabled)
        s.put("peqBands", state.peqBands)
        root.put("sessionState", s)

        val presetsArray = JSONArray()
        presets.forEach { preset ->
            val gains = JSONArray().apply { preset.gains.forEach { put(it.toDouble()) } }
            presetsArray.put(JSONObject().put("name", preset.name).put("gains", gains))
        }
        root.put("eqPresets", presetsArray)

        return root.toString(2)
    }

    data class DecodedBackup(
        val sessionState: SessionState,
        val eqPresets: List<SavedEqPreset>
    )

    /** Returns null if [raw] isn't a recognizable JadOO backup. */
    fun decode(raw: String): DecodedBackup? = runCatching {
        val root = JSONObject(raw)
        val s = root.getJSONObject("sessionState")

        fun bandGainsFrom(json: JSONObject): FloatArray {
            val arr = json.getJSONArray("bandGains")
            return FloatArray(15) { i -> arr.optDouble(i, 0.0).toFloat().coerceIn(-15f, 15f) }
        }

        val sessionState = SessionState(
            masterEnabled = s.optBoolean("masterEnabled", false),
            preGain = s.optDouble("preGain", 0.0).toFloat(),
            postGain = s.optDouble("postGain", 0.0).toFloat(),
            hiResEnabled = s.optBoolean("hiResEnabled", false),
            dbfbMode = s.optString("dbfbMode", "Off"),
            hdrEnabled = s.optBoolean("hdrEnabled", false),
            hdrMode = s.optString("hdrMode", "Restoration"),
            surroundMode = s.optString("surroundMode", "Off"),
            bandGains = bandGainsFrom(s),
            analogBassEnabled = s.optBoolean("analogBassEnabled", false),
            analogBassDrive = s.optDouble("analogBassDrive", 0.4).toFloat(),
            analogBassWarmth = s.optDouble("analogBassWarmth", 0.7).toFloat(),
            analogBassDrift = s.optDouble("analogBassDrift", 0.2).toFloat(),
            analogBassPultecBoost = s.optDouble("analogBassPultecBoost", 0.5).toFloat(),
            analogBassPultecCut = s.optDouble("analogBassPultecCut", 0.3).toFloat(),
            analogBassPultecFreqIndex = s.optInt("analogBassPultecFreqIndex", 2),
            tubeWarmthEnabled = s.optBoolean("tubeWarmthEnabled", false),
            tubeWarmthIntensity = s.optDouble("tubeWarmthIntensity", 0.5).toFloat(),
            mobileBassEnabled = s.optBoolean("mobileBassEnabled", false),
            mobileBassIntensity = s.optDouble("mobileBassIntensity", 0.5).toFloat(),
            harmonicExciterEnabled = s.optBoolean("harmonicExciterEnabled", false),
            harmonicExciterIntensity = s.optDouble("harmonicExciterIntensity", 0.5).toFloat(),
            peqEnabled = s.optBoolean("peqEnabled", false),
            peqBands = s.optString("peqBands", "")
        )

        val presetsArray = root.optJSONArray("eqPresets") ?: JSONArray()
        val eqPresets = List(presetsArray.length()) { i ->
            val item = presetsArray.getJSONObject(i)
            val gains = item.getJSONArray("gains")
            SavedEqPreset(
                name = item.getString("name"),
                gains = FloatArray(15) { b -> gains.optDouble(b, 0.0).toFloat().coerceIn(-15f, 15f) }
            )
        }

        DecodedBackup(sessionState, eqPresets)
    }.getOrNull()
}
