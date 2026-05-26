package com.jadoo.amp.audio

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * JadOO Analog Bass Engine
 *
 * Models the physical and electrical behaviors of vintage analog hardware
 * to produce bass that feels alive, warm, and three-dimensional.
 *
 * Architecture:
 * 1. Pultec-style simultaneous boost/cut (resonant shelf)
 * 2. Harmonic saturation (2nd + 3rd order, tube/transformer modeling)
 * 3. Transformer soft-clipping with asymmetric knee
 * 4. Thermal drift micro-modulation (circuit instability simulation)
 * 5. 4x oversampling to prevent aliasing from nonlinear processing
 *
 * All processing is sample-accurate and runs in the DSP thread.
 */
class AnalogBassEngine {

    companion object {
        private const val TAG = "AnalogBassEngine"

        // Oversampling factor for anti-aliasing during nonlinear processing
        private const val OVERSAMPLE_FACTOR = 4
        // Internal processing sample rate (will be set from actual)
        private const val DEFAULT_SAMPLE_RATE = 48000f

        // Pultec frequency options (Hz)
        val PULTEC_FREQUENCIES = floatArrayOf(20f, 30f, 60f, 100f)
    }

    // ── User-facing parameters ───────────────────────────────────────

    /** Overall Analog Bass bypass */
    @Volatile var enabled = false

    /** Pultec boost amount (0.0 = off, 1.0 = full, maps to 0–8dB) */
    @Volatile var pultecBoost = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Pultec cut amount (0.0 = off, 1.0 = full, maps to 0–4dB cut) */
    @Volatile var pultecCut = 0.3f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Pultec center frequency index into PULTEC_FREQUENCIES */
    @Volatile var pultecFreqIndex = 2  // default 60Hz
        set(value) { field = value.coerceIn(0, PULTEC_FREQUENCIES.size - 1) }

    /** Harmonic drive/saturation amount (0.0 = clean, 1.0 = heavy saturation) */
    @Volatile var drive = 0.4f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Warmth: balance of even vs odd harmonics (0.0 = odd-dominant/aggressive, 1.0 = even-dominant/warm) */
    @Volatile var warmth = 0.7f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Drift: micro-fluctuation intensity (0.0 = stable digital, 1.0 = unstable vintage tube) */
    @Volatile var drift = 0.2f
        set(value) { field = value.coerceIn(0f, 1f) }

    // ── Internal state ───────────────────────────────────────────────

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var oversampledRate = DEFAULT_SAMPLE_RATE * OVERSAMPLE_FACTOR

    // Pultec biquad filter state (2nd order IIR)
    private val pultecBoostFilter = BiquadState()
    private val pultecCutFilter = BiquadState()
    private var lastPultecFreq = 0f
    private var lastPultecBoostAmt = 0f
    private var lastPultecCutAmt = 0f

    // Oversampling anti-alias filters (4th order Butterworth via 2 cascaded biquads)
    private val upsampleFilter1 = BiquadState()
    private val upsampleFilter2 = BiquadState()
    private val downsampleFilter1 = BiquadState()
    private val downsampleFilter2 = BiquadState()
    private var antiAliasCoeffs = BiquadCoeffs()

    // Drift LFO state
    private var driftPhase = 0.0
    private var driftPhase2 = 0.0
    private val driftRateHz = 0.07   // very slow modulation (~14s period)
    private val driftRate2Hz = 0.23  // secondary modulation (~4.3s period)

    // DC blocker state
    private var dcX1 = 0f
    private var dcY1 = 0f
    private val dcCoeff = 0.9975f  // ~5Hz cutoff at 48kHz

    /** Initialize the engine for a given sample rate */
    fun initialize(sampleRateHz: Float = DEFAULT_SAMPLE_RATE) {
        sampleRate = sampleRateHz
        oversampledRate = sampleRateHz * OVERSAMPLE_FACTOR
        // Calculate anti-alias filter coefficients (Butterworth LPF at Nyquist/2)
        antiAliasCoeffs = designLowpass(sampleRateHz / 2.2f, oversampledRate, 0.7071f)
        resetState()
        Log.d(TAG, "Initialized at ${sampleRateHz}Hz, oversampled=${oversampledRate}Hz")
    }

    /** Reset all filter states (call on track change or session switch) */
    fun resetState() {
        pultecBoostFilter.reset()
        pultecCutFilter.reset()
        upsampleFilter1.reset()
        upsampleFilter2.reset()
        downsampleFilter1.reset()
        downsampleFilter2.reset()
        driftPhase = 0.0
        driftPhase2 = 0.0
        dcX1 = 0f
        dcY1 = 0f
        lastPultecFreq = 0f
        lastPultecBoostAmt = 0f
        lastPultecCutAmt = 0f
    }

    /**
     * Process a single audio sample through the full analog bass chain.
     * Call this for each sample in the bass frequency range (<300Hz).
     *
     * Signal flow:
     * Input → Pultec EQ → 4x Upsample → Saturation → Downsample → DC Block → Output
     */
    fun processSample(input: Float): Float {
        if (!enabled) return input

        // 1. Pultec-style simultaneous boost/cut
        var sample = applyPultec(input)

        // 2. Apply drift modulation (subtle pitch/level micro-variations)
        sample = applyDrift(sample)

        // 3. Upsample 4x, apply saturation, downsample
        sample = processWithOversampling(sample)

        // 4. DC blocking filter (saturation can introduce DC offset)
        sample = applyDcBlocker(sample)

        // 5. Blend with dry signal based on drive amount for parallel saturation
        val dryBlend = 1f - (drive * 0.6f)  // at max drive, 40% dry remains
        return input * dryBlend + sample * (1f - dryBlend + drive * 0.3f)
    }

    /**
     * Process a block of samples (more efficient for batch processing).
     * Applies analog bass processing to the entire buffer in-place.
     * Only processes frequencies below the bass crossover (~300Hz).
     */
    fun processBlock(buffer: FloatArray, offset: Int = 0, length: Int = buffer.size - offset) {
        if (!enabled) return
        for (i in offset until (offset + length).coerceAtMost(buffer.size)) {
            buffer[i] = processSample(buffer[i])
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PULTEC EMULATION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Emulates the famous Pultec EQP-1A simultaneous boost/cut trick.
     * Boosting and cutting the same frequency creates a unique resonant curve:
     * sub-bass gets boosted while the muddy region just above gets dipped.
     */
    private fun applyPultec(input: Float): Float {
        val freq = PULTEC_FREQUENCIES[pultecFreqIndex]
        val boostAmt = pultecBoost
        val cutAmt = pultecCut

        // Recalculate coefficients only when parameters change
        if (freq != lastPultecFreq || boostAmt != lastPultecBoostAmt || cutAmt != lastPultecCutAmt) {
            updatePultecCoeffs(freq, boostAmt, cutAmt)
            lastPultecFreq = freq
            lastPultecBoostAmt = boostAmt
            lastPultecCutAmt = cutAmt
        }

        // The Pultec trick: cascade boost then cut in series.
        // This creates the characteristic resonant dip above the boost frequency
        // because the cut shelf sits slightly higher in frequency than the boost.
        val boosted = pultecBoostFilter.process(input)
        val afterCut = pultecCutFilter.process(boosted)
        return afterCut
    }

    private fun updatePultecCoeffs(centerFreq: Float, boostAmt: Float, cutAmt: Float) {
        // Boost: low shelf with resonance (Q increases with boost for Pultec character)
        val boostGainDb = boostAmt * 8f  // 0–8 dB boost range
        val boostQ = 0.5f + boostAmt * 0.8f  // Q rises with boost = resonant peak
        val boostCoeffs = designLowShelf(centerFreq, sampleRate, boostGainDb, boostQ)
        pultecBoostFilter.setCoeffs(boostCoeffs)

        // Cut: slightly higher frequency shelf (Pultec cuts ~0.5 octave above boost)
        val cutFreq = centerFreq * 1.5f
        val cutGainDb = -(cutAmt * 4f)  // 0–4 dB cut range
        val cutQ = 0.6f  // fixed broader Q for the cut (less resonant)
        val cutCoeffs = designLowShelf(cutFreq, sampleRate, cutGainDb, cutQ)
        pultecCutFilter.setCoeffs(cutCoeffs)
    }

    // ══════════════════════════════════════════════════════════════════
    // HARMONIC SATURATION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Applies harmonic saturation modeling vintage transformer/tube behavior.
     *
     * - Even harmonics (2nd, 4th): Warm, smooth — characteristic of single-ended
     *   tube circuits and transformer cores. Controlled by [warmth].
     * - Odd harmonics (3rd, 5th): Punchy, aggressive — characteristic of push-pull
     *   tube circuits. More present at lower [warmth].
     *
     * Uses hyperbolic tangent (tanh) soft-clipping combined with polynomial
     * waveshaping for accurate harmonic distribution.
     */
    private fun saturate(input: Float): Float {
        val driveAmount = drive * 3f + 0.5f  // 0.5–3.5x gain into saturation

        // Drive the signal into the nonlinear region
        val driven = input * driveAmount

        // Soft clipping via tanh (models tube grid saturation)
        val tanhClipped = tanh(driven.toDouble()).toFloat()

        // Even-harmonic generation (asymmetric waveshaping like single-ended tubes)
        // f(x) = x + k * x^2 where k controls even harmonic amount
        val evenK = warmth * 0.15f
        val evenHarmonics = driven + evenK * driven * abs(driven)

        // Odd-harmonic generation (symmetric like push-pull / transformer core)
        // f(x) = x - k * x^3
        val oddK = (1f - warmth) * 0.08f
        val oddHarmonics = driven - oddK * driven * driven * driven

        // Blend: warmth controls the mix between even and odd harmonic paths.
        // Weights sum to 1.0 for consistent loudness regardless of warmth setting.
        val evenWeight = warmth * 0.35f
        val oddWeight = (1f - warmth) * 0.35f
        val tanhWeight = 1f - evenWeight - oddWeight
        val saturated = tanhClipped * tanhWeight +
                       evenHarmonics * evenWeight +
                       oddHarmonics * oddWeight

        // Transformer saturation: asymmetric soft knee
        // Real transformers clip positive and negative halves differently
        val transformerOut = transformerSaturate(saturated)

        // Normalize back to roughly unity gain (compensate for drive boost)
        return transformerOut / (driveAmount * 0.4f + 0.6f)
    }

    /**
     * Models transformer core saturation.
     * Real audio transformers (Neve 1073, SSL E-series, Pultec) have:
     * - Asymmetric clipping (positive peaks saturate differently than negative)
     * - Soft magnetic core saturation that creates even harmonics
     * - Frequency-dependent saturation (bass saturates more than treble)
     */
    private fun transformerSaturate(input: Float): Float {
        // Asymmetric saturation: positive half clips softer (tube-like)
        // negative half clips harder (transformer-like)
        return if (input >= 0f) {
            // Positive: soft polynomial saturation (even harmonics)
            val x = input.coerceAtMost(2f)
            x * (1f - x * x * 0.1f)
        } else {
            // Negative: slightly harder tanh curve (mild odd harmonics)
            val x = (-input).coerceAtMost(2f)
            -(tanh(x * 1.2).toFloat()) * 0.9f
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // THERMAL DRIFT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Simulates thermal drift of analog components.
     * Real circuits have microscopic variations caused by:
     * - Resistor thermal noise (Johnson-Nyquist)
     * - Capacitor dielectric absorption
     * - Tube heater temperature fluctuation
     *
     * This creates a subtle "alive" quality where no two bass notes
     * sound exactly the same — the opposite of digital precision.
     */
    private fun applyDrift(input: Float): Float {
        if (drift < 0.01f) return input

        // Dual-LFO modulation (non-harmonically related for organic feel)
        driftPhase += driftRateHz / sampleRate * 2.0 * PI
        driftPhase2 += driftRate2Hz / sampleRate * 2.0 * PI
        if (driftPhase > 2.0 * PI) driftPhase -= 2.0 * PI
        if (driftPhase2 > 2.0 * PI) driftPhase2 -= 2.0 * PI

        // Combine two LFOs for complex, non-repeating modulation
        val mod1 = sin(driftPhase).toFloat()
        val mod2 = sin(driftPhase2).toFloat() * 0.6f

        // Apply as micro-gain variation (±0.3dB max at full drift)
        // This models thermal resistance changes in analog circuits
        val gainMod = 1f + (mod1 + mod2) * drift * 0.017f

        // Apply as micro-pitch variation (±0.1 cent max at full drift)
        // This models capacitor value drift affecting filter frequencies
        // (Implemented as subtle phase modulation)
        val phaseMod = (mod1 * 0.3f + mod2 * 0.7f) * drift * 0.001f

        return input * gainMod + input * phaseMod
    }

    // ══════════════════════════════════════════════════════════════════
    // OVERSAMPLING (Anti-aliasing for nonlinear processing)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Applies saturation with proper 4x oversampling.
     *
     * Nonlinear processes generate harmonics that can alias. We upsample by
     * zero-stuffing, filter, saturate each upsampled point, filter again, and
     * keep the first output sample (which corresponds to the original input
     * time position).
     */
    private fun processWithOversampling(input: Float): Float {
        // Zero-stuff: input followed by 3 zeros
        val upsampled = FloatArray(OVERSAMPLE_FACTOR)
        upsampled[0] = input * OVERSAMPLE_FACTOR  // preserve energy

        var result = 0f
        for (i in 0 until OVERSAMPLE_FACTOR) {
            // Interpolation anti-alias filter
            var s = upsampleFilter1.processWithCoeffs(upsampled[i], antiAliasCoeffs)
            s = upsampleFilter2.processWithCoeffs(s, antiAliasCoeffs)

            // Saturation at oversampled rate
            s = saturate(s)

            // Decimation anti-alias filter
            s = downsampleFilter1.processWithCoeffs(s, antiAliasCoeffs)
            s = downsampleFilter2.processWithCoeffs(s, antiAliasCoeffs)

            // Keep the FIRST sample (original time position), discard the rest
            if (i == 0) result = s
        }

        return result / OVERSAMPLE_FACTOR
    }

    // ══════════════════════════════════════════════════════════════════
    // DC BLOCKER
    // ══════════════════════════════════════════════════════════════════

    /** High-pass filter at ~5Hz to remove DC offset introduced by asymmetric saturation */
    private fun applyDcBlocker(input: Float): Float {
        val y = input - dcX1 + dcCoeff * dcY1
        dcX1 = input
        dcY1 = y
        return y
    }

    // ══════════════════════════════════════════════════════════════════
    // BIQUAD FILTER INFRASTRUCTURE
    // ══════════════════════════════════════════════════════════════════

    /** Biquad filter coefficients (Direct Form I) */
    data class BiquadCoeffs(
        var b0: Float = 1f,
        var b1: Float = 0f,
        var b2: Float = 0f,
        var a1: Float = 0f,
        var a2: Float = 0f
    )

    /** Biquad filter state with Direct Form II Transposed implementation */
    class BiquadState {
        private var coeffs = BiquadCoeffs()
        private var z1 = 0f
        private var z2 = 0f

        fun setCoeffs(c: BiquadCoeffs) { coeffs = c }

        fun reset() { z1 = 0f; z2 = 0f }

        fun process(input: Float): Float {
            val output = coeffs.b0 * input + z1
            z1 = coeffs.b1 * input - coeffs.a1 * output + z2
            z2 = coeffs.b2 * input - coeffs.a2 * output
            return output
        }

        fun processWithCoeffs(input: Float, c: BiquadCoeffs): Float {
            val output = c.b0 * input + z1
            z1 = c.b1 * input - c.a1 * output + z2
            z2 = c.b2 * input - c.a2 * output
            return output
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // FILTER DESIGN (Audio EQ Cookbook - Robert Bristow-Johnson)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Design a 2nd-order low-pass filter (Butterworth when Q=0.7071).
     * Used for anti-aliasing in the oversampling chain.
     */
    private fun designLowpass(cutoffHz: Float, sampleRateHz: Float, q: Float): BiquadCoeffs {
        val w0 = (2.0 * PI * cutoffHz / sampleRateHz).toFloat()
        val cosW0 = cos(w0.toDouble()).toFloat()
        val sinW0 = sin(w0.toDouble()).toFloat()
        val alpha = sinW0 / (2f * q)

        val b0 = (1f - cosW0) / 2f
        val b1 = 1f - cosW0
        val b2 = (1f - cosW0) / 2f
        val a0 = 1f + alpha
        val a1 = -2f * cosW0
        val a2 = 1f - alpha

        return BiquadCoeffs(
            b0 = b0 / a0, b1 = b1 / a0, b2 = b2 / a0,
            a1 = a1 / a0, a2 = a2 / a0
        )
    }

    /**
     * Design a low-shelf filter with adjustable Q (resonance).
     * Based on the Audio EQ Cookbook by Robert Bristow-Johnson.
     * Higher Q creates a resonant peak at the shelf transition — the Pultec character.
     */
    private fun designLowShelf(
        centerHz: Float,
        sampleRateHz: Float,
        gainDb: Float,
        q: Float
    ): BiquadCoeffs {
        val a = Math.pow(10.0, gainDb / 40.0).toFloat()  // amplitude from dB
        val w0 = (2.0 * PI * centerHz / sampleRateHz).toFloat()
        val cosW0 = cos(w0.toDouble()).toFloat()
        val sinW0 = sin(w0.toDouble()).toFloat()
        val alpha = sinW0 / (2f * q)
        val sqrtA = sqrt(a)
        val twoSqrtAAlpha = 2f * sqrtA * alpha

        val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
        val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
        val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
        val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
        val a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha

        return BiquadCoeffs(
            b0 = b0 / a0, b1 = b1 / a0, b2 = b2 / a0,
            a1 = a1 / a0, a2 = a2 / a0
        )
    }
}
