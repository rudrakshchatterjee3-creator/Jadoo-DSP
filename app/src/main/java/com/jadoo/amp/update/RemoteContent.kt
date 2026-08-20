package com.jadoo.amp.update

import android.util.Log
import com.jadoo.amp.audio.EqBands
import org.json.JSONObject

/**
 * Lane A of the update system: content that ships WITHOUT reinstalling the app.
 *
 * Android has no legitimate way to swap compiled code at runtime — downloading
 * and class-loading a dex is a Play policy violation and exactly the kind of
 * thing Play Protect flags (this project already dropped Shizuku over Play
 * Protect). So Lane A is strictly data: no code, no reflection, no eval. What
 * it does carry is most of what actually changes between releases of a DSP app
 * — preset curves, per-headphone corrections, and the tuning constants that get
 * retuned by ear every version.
 *
 * ## Trust model
 *
 * This payload sets DSP gain values. A truncated, corrupt, or hostile document
 * must not be able to write +40 dB into the EQ, so EVERY numeric field is
 * clamped to the same bounds the equivalent hardcoded constant obeys, and any
 * field that fails to parse falls back to the built-in default rather than to
 * zero or to whatever partial value was read. Parsing never throws: a bad
 * document degrades to the bundled fallback, it doesn't break playback.
 *
 * ## Versioning
 *
 * [schemaVersion] is the document's shape; [SUPPORTED_SCHEMA] is what this APK
 * understands. A document declaring a NEWER schema is rejected outright rather
 * than partially parsed — an old APK guessing at fields it doesn't know is how
 * a "safe" content channel turns into a broken one. [contentVersion] is the
 * payload's own revision, used only to decide whether a fetch brought anything
 * new.
 */
data class RemoteContent(
    val schemaVersion: Int,
    val contentVersion: Int,
    val presets: List<RemoteEqPreset>,
    val headphoneProfiles: List<RemoteHeadphoneProfile>,
    val tuning: RemoteTuning
) {
    companion object {
        const val SUPPORTED_SCHEMA = 1

        /** Used before anything has been fetched or when a document is unusable. */
        val EMPTY = RemoteContent(
            schemaVersion = SUPPORTED_SCHEMA,
            contentVersion = 0,
            presets = emptyList(),
            headphoneProfiles = emptyList(),
            tuning = RemoteTuning()
        )

        /**
         * Parses [raw], returning null if it is not a usable document for this
         * APK. Null means "keep whatever you already had" — never "wipe it".
         */
        fun parse(raw: String): RemoteContent? {
            return try {
                val root = JSONObject(raw)
                val schema = root.optInt("schemaVersion", 0)
                if (schema > SUPPORTED_SCHEMA) {
                    Log.w(TAG, "Ignoring content: schema $schema is newer than supported $SUPPORTED_SCHEMA")
                    return null
                }
                if (schema < 1) {
                    Log.w(TAG, "Ignoring content: missing/invalid schemaVersion")
                    return null
                }
                RemoteContent(
                    schemaVersion = schema,
                    contentVersion = root.optInt("contentVersion", 0),
                    presets = parsePresets(root),
                    headphoneProfiles = parseHeadphoneProfiles(root),
                    tuning = parseTuning(root)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Content parse failed: ${e.message}")
                null
            }
        }

        private const val TAG = "RemoteContent"

        /** Same bound the manual EQ and PreEQ writes use throughout the app. */
        private const val MAX_BAND_GAIN = 15f

        private fun parsePresets(root: JSONObject): List<RemoteEqPreset> {
            val array = root.optJSONArray("presets") ?: return emptyList()
            val out = mutableListOf<RemoteEqPreset>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val gainsArray = obj.optJSONArray("gains") ?: continue
                // A preset with the wrong band count can't be mapped onto the
                // 15-band graphic EQ at all — skip rather than pad, which would
                // silently produce a curve nobody authored.
                if (gainsArray.length() != EqBands.count) continue
                val gains = FloatArray(EqBands.count) { band ->
                    gainsArray.optDouble(band, 0.0).toFloat()
                        .takeIf { it.isFinite() }
                        ?.coerceIn(-MAX_BAND_GAIN, MAX_BAND_GAIN) ?: 0f
                }
                out.add(RemoteEqPreset(name, gains))
            }
            return out
        }

        private fun parseHeadphoneProfiles(root: JSONObject): List<RemoteHeadphoneProfile> {
            val array = root.optJSONArray("headphoneProfiles") ?: return emptyList()
            val out = mutableListOf<RemoteHeadphoneProfile>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                val match = obj.optString("match").trim()
                if (name.isEmpty() || match.isEmpty()) continue
                out.add(
                    RemoteHeadphoneProfile(
                        name = name,
                        match = match,
                        deviceType = obj.optString("deviceType", "General").trim(),
                        qualityTier = obj.optDouble("qualityTier", 0.5).toFloat()
                            .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
                    )
                )
            }
            return out
        }

        private fun parseTuning(root: JSONObject): RemoteTuning {
            val obj = root.optJSONObject("tuning") ?: return RemoteTuning()
            val defaults = RemoteTuning()

            fun clampedFloat(key: String, min: Float, max: Float, fallback: Float): Float {
                if (!obj.has(key)) return fallback
                val v = obj.optDouble(key, Double.NaN).toFloat()
                return if (v.isFinite()) v.coerceIn(min, max) else fallback
            }

            val sbc = obj.optJSONArray("sbcPreEmphasis")?.let { arr ->
                if (arr.length() != EqBands.count) null
                else FloatArray(EqBands.count) { band ->
                    arr.optDouble(band, 0.0).toFloat()
                        .takeIf { it.isFinite() }
                        // Tighter than the general EQ bound: this curve is
                        // summed on TOP of the user's own EQ and the surround
                        // smile, and it currently peaks at +7dB. ±10 leaves
                        // room to retune without room to blow up the mix.
                        ?.coerceIn(-10f, 10f) ?: 0f
                }
            }

            return RemoteTuning(
                // Bounds mirror what these values can sanely be in
                // DspEngine.configureMbc — see the Restoration block there.
                hdrRestorationThreshold = clampedFloat(
                    "hdrRestorationThreshold", -60f, -20f, defaults.hdrRestorationThreshold
                ),
                hdrRestorationKnee = clampedFloat(
                    "hdrRestorationKnee", 0f, 30f, defaults.hdrRestorationKnee
                ),
                hdrRestorationExpanderRatio = clampedFloat(
                    "hdrRestorationExpanderRatio", 1f, 1.5f, defaults.hdrRestorationExpanderRatio
                ),
                sbcPreEmphasis = sbc
            )
        }
    }
}

/** A 15-band graphic-EQ curve delivered over the content channel. */
data class RemoteEqPreset(val name: String, val gains: FloatArray) {
    // FloatArray needs explicit equals/hashCode for the data class to behave
    // sensibly in the lists this ends up in.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteEqPreset) return false
        return name == other.name && gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + gains.contentHashCode()
}

/**
 * A known headphone/DAC, matched against the output device's reported product
 * name (see JadooDspService.computeOutputDeviceKey). Deliberately only carries
 * a DeviceType + quality tier suggestion rather than a full EQ correction: the
 * app's device-aware scaling already consumes exactly those two values, so this
 * plugs into an existing mechanism instead of inventing a parallel one.
 */
data class RemoteHeadphoneProfile(
    val name: String,
    val match: String,
    val deviceType: String,
    val qualityTier: Float
) {
    /** Case-insensitive substring match against a device label like "Bluetooth: WH-1000XM4". */
    fun matches(deviceLabel: String): Boolean =
        deviceLabel.contains(match, ignoreCase = true)
}

/**
 * Remotely adjustable DSP constants. Every field defaults to the value
 * currently hardcoded in the engine, so an absent or unparseable entry
 * reproduces exactly the built-in behaviour.
 */
data class RemoteTuning(
    val hdrRestorationThreshold: Float = -38f,
    val hdrRestorationKnee: Float = 14f,
    val hdrRestorationExpanderRatio: Float = 1.12f,
    /** Null = use the built-in curve in JadooDspService.writeCombinedBand. */
    val sbcPreEmphasis: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteTuning) return false
        return hdrRestorationThreshold == other.hdrRestorationThreshold &&
            hdrRestorationKnee == other.hdrRestorationKnee &&
            hdrRestorationExpanderRatio == other.hdrRestorationExpanderRatio &&
            (sbcPreEmphasis?.contentEquals(other.sbcPreEmphasis) ?: (other.sbcPreEmphasis == null))
    }

    override fun hashCode(): Int {
        var result = hdrRestorationThreshold.hashCode()
        result = 31 * result + hdrRestorationKnee.hashCode()
        result = 31 * result + hdrRestorationExpanderRatio.hashCode()
        result = 31 * result + (sbcPreEmphasis?.contentHashCode() ?: 0)
        return result
    }
}
