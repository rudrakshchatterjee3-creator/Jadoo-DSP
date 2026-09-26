package com.jadoo.amp.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
        const val MAX_BANDS = 16
        const val MIN_FREQUENCY_HZ = 20f
        const val MAX_FREQUENCY_HZ = 20_000f
        const val MIN_GAIN_DB = -15f
        const val MAX_GAIN_DB = 15f
        const val MIN_Q = 0.1f
        const val MAX_Q = 18f
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000f

        /**
         * Useful starting points spread logarithmically over the audible range.
         * Existing 8-band profiles retain their first eight serialized bands;
         * the additional bands migrate as disabled defaults at indices 9-16.
         */
        val DEFAULT_FREQUENCIES_HZ = floatArrayOf(
            25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f,
            1_000f, 1_600f, 2_500f, 4_000f, 6_300f, 10_000f, 16_000f, 20_000f
        )

        fun defaultBand(index: Int): FilterBand = FilterBand(
            enabled = false,
            type = FilterType.Peak,
            frequencyHz = DEFAULT_FREQUENCIES_HZ.getOrElse(index) { 1_000f },
            gainDb = 0f,
            q = 1f
        )

        /** Sanitizes imported and UI-provided values before they reach filter math. */
        fun sanitizeBand(band: FilterBand, index: Int = 0): FilterBand = band.copy(
            frequencyHz = band.frequencyHz.takeIf(Float::isFinite)
                ?.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
                ?: DEFAULT_FREQUENCIES_HZ.getOrElse(index) { 1_000f },
            gainDb = band.gainDb.takeIf(Float::isFinite)
                ?.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) ?: 0f,
            q = band.q.takeIf(Float::isFinite)?.coerceIn(MIN_Q, MAX_Q) ?: 1f
        )

        fun evaluateMagnitudeResponseDb(
            b0: Float, b1: Float, b2: Float,
            a1: Float, a2: Float,
            frequencyHz: Float, sampleRateHz: Float
        ): Float {
            if (!sampleRateHz.isFinite() || sampleRateHz <= 0f || !frequencyHz.isFinite()) return 0f
            val safeFrequency = frequencyHz.coerceIn(1f, sampleRateHz * 0.499f)
            val w = 2.0 * PI * safeFrequency / sampleRateHz
            val cW = cos(w); val sW = sin(w)
            val c2W = cos(2.0 * w); val s2W = sin(2.0 * w)
            val nr = b0.toDouble() + b1 * cW + b2 * c2W
            val ni = b1 * sW + b2 * s2W
            val dr = 1.0 + a1 * cW + a2 * c2W
            val di = a1 * sW + a2 * s2W
            val denominatorPower = dr * dr + di * di
            val hPow2 = if (denominatorPower.isFinite() && denominatorPower > 1e-20) {
                (nr * nr + ni * ni) / denominatorPower
            } else Double.NaN
            return if (hPow2.isFinite() && hPow2 >= 0.0) {
                (10.0 * log10(hPow2.coerceAtLeast(1e-12))).toFloat()
                    .takeIf(Float::isFinite) ?: 0f
            } else 0f
        }

        /**
         * Exact response used by both the engine and the UI graph. Keeping one
         * implementation prevents the graph from promising a shape that the
         * live PEQ mapper does not produce (previously Notch/BandPass/AllPass
         * all drew as flat even though the engine evaluated them differently).
         */
        fun evaluateBandMagnitudeResponseDb(
            band: BiquadBandState,
            frequencyHz: Float,
            sampleRateHz: Float = DEFAULT_SAMPLE_RATE_HZ
        ): Float {
            if (!band.isEnabled) return 0f
            val sanitized = sanitizeBand(
                FilterBand(true, band.type, band.frequency, band.gain, band.q),
                band.index
            )
            val coefficients = coefficientsFor(sanitized, sanitizeSampleRate(sampleRateHz))
            return evaluateMagnitudeResponseDb(
                coefficients.b0, coefficients.b1, coefficients.b2,
                coefficients.a1, coefficients.a2,
                frequencyHz, sanitizeSampleRate(sampleRateHz)
            )
        }

        fun evaluateCombinedMagnitudeResponseDb(
            bands: List<BiquadBandState>,
            frequencyHz: Float,
            sampleRateHz: Float = DEFAULT_SAMPLE_RATE_HZ
        ): Float = bands.sumOf {
            evaluateBandMagnitudeResponseDb(it, frequencyHz, sampleRateHz).toDouble()
        }.toFloat().takeIf(Float::isFinite) ?: 0f

        private data class Coefficients(
            val b0: Float,
            val b1: Float,
            val b2: Float,
            val a1: Float,
            val a2: Float
        )

        private fun sanitizeSampleRate(value: Float): Float =
            value.takeIf { it.isFinite() && it in 8_000f..384_000f } ?: DEFAULT_SAMPLE_RATE_HZ

        private fun coefficientsFor(rawBand: FilterBand, rawSampleRate: Float): Coefficients {
            val sampleRate = sanitizeSampleRate(rawSampleRate)
            val band = sanitizeBand(rawBand).let {
                it.copy(frequencyHz = it.frequencyHz.coerceAtMost(sampleRate * 0.475f))
            }
            val w0 = (2.0 * PI * band.frequencyHz / sampleRate).toFloat()
            val cosW0 = cos(w0.toDouble()).toFloat()
            val sinW0 = sin(w0.toDouble()).toFloat()
            val alpha = sinW0 / (2f * band.q)
            val a = 10f.pow(band.gainDb / 40f)

            val b0: Float
            val b1: Float
            val b2: Float
            val a0: Float
            val a1: Float
            val a2: Float

            when (band.type) {
                FilterType.Peak -> {
                    b0 = 1f + alpha * a; b1 = -2f * cosW0; b2 = 1f - alpha * a
                    a0 = 1f + alpha / a; a1 = -2f * cosW0; a2 = 1f - alpha / a
                }
                FilterType.LowShelf -> {
                    val sqrtA = sqrt(a); val twoSqrtAAlpha = 2f * sqrtA * alpha
                    b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
                    b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
                    b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
                    a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
                    a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
                    a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha
                }
                FilterType.HighShelf -> {
                    val sqrtA = sqrt(a); val twoSqrtAAlpha = 2f * sqrtA * alpha
                    b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)
                    b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
                    b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)
                    a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
                    a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
                    a2 = (a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha
                }
                FilterType.LowPass -> {
                    b0 = (1f - cosW0) / 2f; b1 = 1f - cosW0; b2 = b0
                    a0 = 1f + alpha; a1 = -2f * cosW0; a2 = 1f - alpha
                }
                FilterType.HighPass -> {
                    b0 = (1f + cosW0) / 2f; b1 = -(1f + cosW0); b2 = b0
                    a0 = 1f + alpha; a1 = -2f * cosW0; a2 = 1f - alpha
                }
                FilterType.BandPass -> {
                    b0 = alpha; b1 = 0f; b2 = -alpha
                    a0 = 1f + alpha; a1 = -2f * cosW0; a2 = 1f - alpha
                }
                FilterType.Notch -> {
                    b0 = 1f; b1 = -2f * cosW0; b2 = 1f
                    a0 = 1f + alpha; a1 = -2f * cosW0; a2 = 1f - alpha
                }
                FilterType.AllPass -> {
                    b0 = 1f - alpha; b1 = -2f * cosW0; b2 = 1f + alpha
                    a0 = 1f + alpha; a1 = -2f * cosW0; a2 = 1f - alpha
                }
            }

            if (!a0.isFinite() || kotlin.math.abs(a0) < 1e-9f) {
                return Coefficients(1f, 0f, 0f, 0f, 0f)
            }
            val result = Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            return if (listOf(result.b0, result.b1, result.b2, result.a1, result.a2).all(Float::isFinite)) {
                result
            } else Coefficients(1f, 0f, 0f, 0f, 0f)
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
    private var sampleRate = DEFAULT_SAMPLE_RATE_HZ
    val sampleRateHz: Float get() = sampleRate
    private val bands = Array(MAX_BANDS) { defaultBand(it) }
    private val processors = Array(MAX_BANDS) { BiquadProcessor() }
    private val lock = Any()
    
    // StateFlow for UI observation
    private val _bandStates = MutableStateFlow(List(MAX_BANDS) { index ->
        defaultBand(index).toState(index)
    })
    val bandStates: StateFlow<List<BiquadBandState>> = _bandStates.asStateFlow()

    // ── Public API ───────────────────────────────────────────────────

    fun initialize(sampleRateHz: Float = 48000f) {
        sampleRate = sanitizeSampleRate(sampleRateHz)
        resetAllState()
        // Recalculate all enabled bands when sample rate changes
        synchronized(lock) {
            for (i in 0 until MAX_BANDS) {
                if (bands[i].enabled) calculateCoefficients(i)
            }
        }
        Log.d(TAG, "Initialized at ${sampleRate}Hz with $MAX_BANDS bands")
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
            val safeBand = sanitizeBand(band, index)
            bands[index] = safeBand
            if (safeBand.enabled) {
                calculateCoefficients(index)
            }
            // Update StateFlow
            val currentState = _bandStates.value.toMutableList()
            currentState[index] = BiquadBandState(
                index = index,
                type = safeBand.type,
                frequency = safeBand.frequencyHz,
                gain = safeBand.gainDb,
                q = safeBand.q,
                isEnabled = safeBand.enabled
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
            bands[index].gainDb = gainDb.takeIf(Float::isFinite)?.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) ?: 0f
            if (bands[index].enabled) calculateCoefficients(index)
            // Update StateFlow
            updateBandStateFlow(index)
        }
    }

    /** Update only the Q factor (efficient for real-time control) */
    fun setBandQ(index: Int, q: Float) {
        if (index !in 0 until MAX_BANDS) return
        synchronized(lock) {
            bands[index].q = q.takeIf(Float::isFinite)?.coerceIn(MIN_Q, MAX_Q) ?: 1f
            if (bands[index].enabled) calculateCoefficients(index)
            // Update StateFlow
            updateBandStateFlow(index)
        }
    }

    /** Update only the frequency (efficient for real-time control) */
    fun setBandFrequency(index: Int, freqHz: Float) {
        if (index !in 0 until MAX_BANDS) return
        synchronized(lock) {
            bands[index].frequencyHz = freqHz.takeIf(Float::isFinite)
                ?.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
                ?: DEFAULT_FREQUENCIES_HZ[index]
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
                totalDb += evaluateMagnitudeResponseDb(
                    proc.b0, proc.b1, proc.b2, proc.a1, proc.a2,
                    freqHz, sampleRate
                )
            }
        }
        return totalDb.toFloat()
    }

    /**
     * Combined magnitude response for a PreEQ band spanning [loHz, hiHz]:
     * the single most extreme (largest |dB|) sample found anywhere in that
     * span, not the response at one fixed point.
     *
     * A DynamicsProcessing.Eq PreEQ band applies ONE flat gain across its
     * whole cutoff span (see EqBands.cutoffFrequencies) — [evaluateMagnitudeResponseDb]
     * sampled only at the band's centre frequency missed any narrow (high-Q)
     * filter whose target frequency fell inside the band's span but away
     * from that one sampled point: a Q=12 notch at 3200Hz (inside band 11's
     * 3162-5020Hz span, whose centre is 4000Hz) measured as -0.48dB instead
     * of the requested -12dB — effectively invisible.
     *
     * An arithmetic dB average across the span was tried first and rejected:
     * it fixed the off-centre case (-0.48 to -2.25dB) but broke the far more
     * common on-centre one — a Q=12 notch placed exactly ON 4000Hz dropped
     * from -12.00dB (the old single-point sample, correct there by luck) to
     * -2.85dB, gutting the PEQ's main real use case, surgically pulling a
     * known resonance.
     *
     * Taking the peak instead of the mean fixes the miss without the
     * regression: sampling N log-spaced points across the span and keeping
     * the one with the largest |dB| finds a narrow filter wherever it falls
     * in the span (off-centre case: -10.80dB, essentially full depth) while
     * an on-centre filter's own peak IS one of the sample points, so its
     * depth is preserved exactly as before (-11.84 to -11.98dB vs the true
     * -12dB, both cases). Broad filters (Q<=2) are already close to flat
     * across the span, so peak and single-point agree there too — verified
     * by hand, no regression for shelves/bells. Does not fix the bandwidth
     * mismatch itself (a flat-gain band can't reproduce a narrow notch's
     * shape) — that needs raw PCM access this app doesn't have.
     */
    fun evaluateBandPeakDb(loHz: Float, hiHz: Float, samples: Int = 13): Float {
        if (!enabled) return 0f
        val lo = loHz.coerceAtLeast(1f)
        val hi = hiHz.coerceAtLeast(lo + 1f)
        val logLo = ln(lo.toDouble())
        val logHi = ln(hi.toDouble())
        var best = 0f
        for (s in 0 until samples) {
            val t = if (samples <= 1) 0.5 else s.toDouble() / (samples - 1)
            val f = exp(logLo + (logHi - logLo) * t).toFloat()
            val v = evaluateMagnitudeResponseDb(f)
            if (kotlin.math.abs(v) > kotlin.math.abs(best)) best = v
        }
        return best
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

    private fun FilterBand.toState(index: Int) = BiquadBandState(
        index = index,
        type = type,
        frequency = frequencyHz,
        gain = gainDb,
        q = q,
        isEnabled = enabled
    )

    // ══════════════════════════════════════════════════════════════════
    // COEFFICIENT CALCULATION (Audio EQ Cookbook)
    // ══════════════════════════════════════════════════════════════════

    private fun calculateCoefficients(index: Int) {
        val proc = processors[index]
        val coefficients = coefficientsFor(bands[index], sampleRate)
        proc.b0 = coefficients.b0
        proc.b1 = coefficients.b1
        proc.b2 = coefficients.b2
        proc.a1 = coefficients.a1
        proc.a2 = coefficients.a2
    }
}
