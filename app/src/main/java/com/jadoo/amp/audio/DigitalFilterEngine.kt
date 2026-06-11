package com.jadoo.amp.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JadOO Digital Filter Engine
 *
 * Professional IIR biquad filter bank with full parametric control.
 * Implements the master difference equation:
 *
 *   y[n] = sum(b_k * x[n-k]) - sum(a_k * y[n-k])
 *
 * Each band provides:
 * - Center frequency (20Hz–20kHz)
 * - Gain (-15 to +15 dB)
 * - Q factor (0.1–18.0) — controls bandwidth
 * - Filter type (Peak, LowShelf, HighShelf, LowPass, HighPass, BandPass, Notch, AllPass)
 *
 * This gives surgical precision that DynamicsProcessing's fixed-bandwidth EQ cannot achieve.
 * A narrow Q (e.g., 12.0) creates a needle-thin notch to remove resonances.
 * A wide Q (e.g., 0.5) creates musical broad shelves.
 *
 * The engine cascades multiple biquad stages for steeper slopes when needed.
 *
 * Reference: Robert Bristow-Johnson's Audio EQ Cookbook
 * Reference: Julius O. Smith III — "Introduction to Digital Filters"
 */
class DigitalFilterEngine {

    companion object {
        private const val TAG = "DigitalFilterEngine"
        const val MAX_BANDS = 8

        fun evaluateMagnitudeResponseDb(
            b0: Float, b1: Float, b2: Float,
            a1: Float, a2: Float,
            frequencyHz: Float, sampleRateHz: Float
        ): Float {
            if (sampleRateHz <= 0f) return 0f
            val w = 2.0 * PI * frequencyHz / sampleRateHz
            val cW = cos(w); val sW = sin(w)
            val c2W = cos(2.0 * w); val s2W = sin(2.0 * w)
            val nr = b0.toDouble() + b1 * cW + b2 * c2W
            val ni = b1 * sW + b2 * s2W
            val dr = 1.0 + a1 * cW + a2 * c2W
            val di = a1 * sW + a2 * s2W
            val hPow2 = (nr * nr + ni * ni) / (dr * dr + di * di)
            return if (hPow2 > 0.0) (10.0 * log10(hPow2)).toFloat() else 0f
        }
    }

    /** Public state representation for UI */
    data class BiquadBandState(
        val index: Int,
        val type: FilterType,
        val frequency: Float,
        val gain: Float,
        val q: Float,
        val isEnabled: Boolean
    )

    /** Filter type for each band */
    enum class FilterType {
        Peak,       // Parametric bell (boost/cut at center frequency)
        LowShelf,   // Shelf below frequency
        HighShelf,  // Shelf above frequency
        LowPass,    // Remove everything above
        HighPass,   // Remove everything below
        BandPass,   // Pass only around center
        Notch,      // Remove only at center
        AllPass     // Phase shift only (useful for stereo widening)
    }

    /**
     * A single parametric filter band.
     * Fully describes one biquad in the cascade.
     */
    data class FilterBand(
        var enabled: Boolean = false,
        var type: FilterType = FilterType.Peak,
        var frequencyHz: Float = 1000f,
        var gainDb: Float = 0f,
        var q: Float = 1.0f
    )

    /** Internal biquad state for Direct Form II Transposed */
    private class BiquadProcessor {
        var b0 = 1f; var b1 = 0f; var b2 = 0f
        var a1 = 0f; var a2 = 0f
        var z1 = 0f; var z2 = 0f

        fun reset() { z1 = 0f; z2 = 0f }
    }

    // ── State ────────────────────────────────────────────────────────

    @Volatile var enabled = false
    private var sampleRate = 48000f
    val sampleRateHz: Float get() = sampleRate
    private val bands = Array(MAX_BANDS) { FilterBand() }
    private val processors = Array(MAX_BANDS) { BiquadProcessor() }
    private val lock = Any()
    
    // StateFlow for UI observation
    private val _bandStates = MutableStateFlow(List(MAX_BANDS) { index ->
        BiquadBandState(index, FilterType.Peak, 1000f, 0f, 1.0f, false)
    })
    val bandStates: StateFlow<List<BiquadBandState>> = _bandStates.asStateFlow()

    // ── Public API ───────────────────────────────────────────────────

    fun initialize(sampleRateHz: Float = 48000f) {
        sampleRate = sampleRateHz
        resetAllState()
        // Recalculate all enabled bands when sample rate changes
        synchronized(lock) {
            for (i in 0 until MAX_BANDS) {
                if (bands[i].enabled) calculateCoefficients(i)
            }
        }
        Log.d(TAG, "Initialized at ${sampleRateHz}Hz with $MAX_BANDS bands")
    }

    fun resetAllState() {
        synchronized(lock) {
            processors.forEach { it.reset() }
        }
    }

    /** True if the parametric EQ is on AND at least one band is actually enabled. */
    fun hasActiveBand(): Boolean = synchronized(lock) {
        enabled && bands.any { it.enabled }
    }

    /** Configure a filter band and recalculate its coefficients */
    fun setBand(index: Int, band: FilterBand) {
        if (index !in 0 until MAX_BANDS) return
        synchronized(lock) {
            bands[index] = band.copy()
            if (band.enabled) {
                calculateCoefficients(index)
            }
            // Update StateFlow
            val currentState = _bandStates.value.toMutableList()
            currentState[index] = BiquadBandState(
                index = index,
                type = band.type,
                frequency = band.frequencyHz,
                gain = band.gainDb,
                q = band.q,
                isEnabled = band.enabled
            )
            _bandStates.value = currentState
        }
    }

    /** Get current band configuration */
    fun getBand(index: Int): FilterBand {
        if (index !in 0 until MAX_BANDS) return FilterBand()
        return synchronized(lock) { bands[index].copy() }
    }

    /** Update only the gain of a band (efficient for real-time control) */
    fun setBandGain(index: Int, gainDb: Float) {
        if (index !in 0 until MAX_BANDS) return
        synchronized(lock) {
            bands[index].gainDb = gainDb.coerceIn(-15f, 15f)
            if (bands[index].enabled) calculateCoefficients(index)
            // Update StateFlow
            updateBandStateFlow(index)
        }
    }

    /** Update only the Q factor (efficient for real-time control) */
    fun setBandQ(index: Int, q: Float) {
        if (index !in 0 until MAX_BANDS) return
        synchronized(lock) {
            bands[index].q = q.coerceIn(0.1f, 18f)
            if (bands[index].enabled) calculateCoefficients(index)
            // Update StateFlow
            updateBandStateFlow(index)
        }
    }

    /** Update only the frequency (efficient for real-time control) */
    fun setBandFrequency(index: Int, freqHz: Float) {
        if (index !in 0 until MAX_BANDS) return
        synchronized(lock) {
            bands[index].frequencyHz = freqHz.coerceIn(20f, 20000f)
            if (bands[index].enabled) calculateCoefficients(index)
            // Update StateFlow
            updateBandStateFlow(index)
        }
    }

    /**
     * Evaluate the combined magnitude response of all ENABLED bands at [freqHz].
     * Returns the total gain in dB at that frequency (sum of each band's dB contribution).
     *
     * This is used to map the biquad filter parameters onto the DynamicsProcessing PreEQ bands,
     * since JadOO intercepts audio at the OS session level and cannot call processSample() directly.
     *
     * Math: For each biquad H(z) = (b0 + b1*z⁻¹ + b2*z⁻²) / (1 + a1*z⁻¹ + a2*z⁻²),
     * evaluate at z = e^(jw), w = 2π·f/Fs:
     *   |H|² = (|numerator|²) / (|denominator|²)
     *   gain(dB) = 10·log10(|H|²) = 20·log10(|H|)
     * Bands are in series, so total(dB) = sum of individual gains(dB).
     *
     * Returns 0f when the filter is disabled (pass-through).
     */
    fun evaluateMagnitudeResponseDb(freqHz: Float): Float {
        if (!enabled) return 0f
        var totalDb = 0.0
        synchronized(lock) {
            for (i in 0 until MAX_BANDS) {
                if (!bands[i].enabled) continue
                val proc = processors[i]
                val w  = 2.0 * PI * freqHz / sampleRate
                val cW  = cos(w);  val sW  = sin(w)
                val c2W = cos(2.0 * w); val s2W = sin(2.0 * w)
                val nr = proc.b0.toDouble() + proc.b1 * cW + proc.b2 * c2W
                val ni = proc.b1 * sW + proc.b2 * s2W
                val dr = 1.0 + proc.a1 * cW + proc.a2 * c2W
                val di = proc.a1 * sW + proc.a2 * s2W
                val hPow2 = (nr * nr + ni * ni) / (dr * dr + di * di)
                if (hPow2 > 0.0) totalDb += 10.0 * log10(hPow2)
            }
        }
        return totalDb.toFloat()
    }

    /** Update only the filter type */
    fun setBandType(index: Int, type: FilterType) {
        if (index !in 0 until MAX_BANDS) return
        synchronized(lock) {
            bands[index].type = type
            if (bands[index].enabled) calculateCoefficients(index)
            // Update StateFlow
            updateBandStateFlow(index)
        }
    }

    /** Enable/disable a specific band */
    fun setBandEnabled(index: Int, enabled: Boolean) {
        if (index !in 0 until MAX_BANDS) return
        synchronized(lock) {
            bands[index].enabled = enabled
            if (enabled) calculateCoefficients(index)
            // Update StateFlow
            updateBandStateFlow(index)
        }
    }

    /** Helper to update StateFlow for a single band */
    private fun updateBandStateFlow(index: Int) {
        val band = bands[index]
        val currentState = _bandStates.value.toMutableList()
        currentState[index] = BiquadBandState(
            index = index,
            type = band.type,
            frequency = band.frequencyHz,
            gain = band.gainDb,
            q = band.q,
            isEnabled = band.enabled
        )
        _bandStates.value = currentState
    }

    // ══════════════════════════════════════════════════════════════════
    // COEFFICIENT CALCULATION (Audio EQ Cookbook)
    // ══════════════════════════════════════════════════════════════════

    private fun calculateCoefficients(index: Int) {
        val band = bands[index]
        val proc = processors[index]
        val w0 = (2.0 * PI * band.frequencyHz / sampleRate).toFloat()
        val cosW0 = cos(w0.toDouble()).toFloat()
        val sinW0 = sin(w0.toDouble()).toFloat()
        val alpha = sinW0 / (2f * band.q)
        val a = 10f.pow(band.gainDb / 40f)  // linear amplitude

        var b0: Float; var b1: Float; var b2: Float
        var a0: Float; var a1: Float; var a2: Float

        when (band.type) {
            FilterType.Peak -> {
                b0 = 1f + alpha * a
                b1 = -2f * cosW0
                b2 = 1f - alpha * a
                a0 = 1f + alpha / a
                a1 = -2f * cosW0
                a2 = 1f - alpha / a
            }
            FilterType.LowShelf -> {
                val sqrtA = sqrt(a)
                val twoSqrtAAlpha = 2f * sqrtA * alpha
                b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
                b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
                b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
                a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
                a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
                a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha
            }
            FilterType.HighShelf -> {
                val sqrtA = sqrt(a)
                val twoSqrtAAlpha = 2f * sqrtA * alpha
                b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)
                b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
                b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)
                a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
                a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
                a2 = (a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha
            }
            FilterType.LowPass -> {
                b0 = (1f - cosW0) / 2f
                b1 = 1f - cosW0
                b2 = (1f - cosW0) / 2f
                a0 = 1f + alpha
                a1 = -2f * cosW0
                a2 = 1f - alpha
            }
            FilterType.HighPass -> {
                b0 = (1f + cosW0) / 2f
                b1 = -(1f + cosW0)
                b2 = (1f + cosW0) / 2f
                a0 = 1f + alpha
                a1 = -2f * cosW0
                a2 = 1f - alpha
            }
            FilterType.BandPass -> {
                b0 = alpha
                b1 = 0f
                b2 = -alpha
                a0 = 1f + alpha
                a1 = -2f * cosW0
                a2 = 1f - alpha
            }
            FilterType.Notch -> {
                b0 = 1f
                b1 = -2f * cosW0
                b2 = 1f
                a0 = 1f + alpha
                a1 = -2f * cosW0
                a2 = 1f - alpha
            }
            FilterType.AllPass -> {
                b0 = 1f - alpha
                b1 = -2f * cosW0
                b2 = 1f + alpha
                a0 = 1f + alpha
                a1 = -2f * cosW0
                a2 = 1f - alpha
            }
        }

        // Normalize by a0
        proc.b0 = b0 / a0
        proc.b1 = b1 / a0
        proc.b2 = b2 / a0
        proc.a1 = a1 / a0
        proc.a2 = a2 / a0
    }

    /**
     * Compute the frequency response magnitude at a given frequency.
     * Useful for displaying the filter curve in the UI.
     * Returns gain in dB.
     */
    fun getResponseAtFrequency(freqHz: Float): Float {
        if (!enabled) return 0f
        var totalGainDb = 0f
        synchronized(lock) {
            for (i in 0 until MAX_BANDS) {
                if (!bands[i].enabled) continue
                val proc = processors[i]
                val w = (2.0 * PI * freqHz / sampleRate)
                val cosW = cos(w)
                val cos2W = cos(2.0 * w)
                val sinW = sin(w)
                val sin2W = sin(2.0 * w)

                // H(z) = (b0 + b1*z^-1 + b2*z^-2) / (1 + a1*z^-1 + a2*z^-2)
                // Evaluate at z = e^(jw)
                val numReal = proc.b0 + proc.b1 * cosW.toFloat() + proc.b2 * cos2W.toFloat()
                val numImag = -(proc.b1 * sinW.toFloat() + proc.b2 * sin2W.toFloat())
                val denReal = 1f + proc.a1 * cosW.toFloat() + proc.a2 * cos2W.toFloat()
                val denImag = -(proc.a1 * sinW.toFloat() + proc.a2 * sin2W.toFloat())

                val numMagSq = numReal * numReal + numImag * numImag
                val denMagSq = denReal * denReal + denImag * denImag

                if (denMagSq > 0f) {
                    val magSq = numMagSq / denMagSq
                    totalGainDb += (10f * kotlin.math.log10(magSq.coerceAtLeast(1e-10f)))
                }
            }
        }
        return totalGainDb
    }
}
