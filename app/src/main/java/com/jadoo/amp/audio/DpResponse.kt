package com.jadoo.amp.audio

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * DynamicsProcessing's static frequency response, worked out the way the
 * engine itself applies gain (AOSP DPFrequency.cpp).
 *
 * DP is an FFT engine: every stage multiplies whole FFT bins. A band covers
 * the bins from just after the previous band's cutoff up to
 * `round(cutoff * block / sampleRate)`, so its gain is a step over those
 * bins. A band whose cutoff rounds to a bin the previous band already
 * took gets NO bins at all and does nothing. Drawing smooth bumps at the
 * nominal band centres hides that; this reproduces it.
 */
object DpResponse {

    /**
     * Frame length requested from DynamicsProcessing. The engine rounds
     * `duration * sampleRate` up to a power of two: 2048 samples at both
     * 44.1 and 48 kHz, ~21 Hz per bin. Android's 10 ms default (512
     * samples, ~94 Hz bins) left 25 Hz on the DC bin and gave the 63 Hz and
     * 100 Hz bands no bins at all. Costs ~43 ms of latency.
     */
    const val FRAME_DURATION_MS = 42f

    /** Points on the dashboard's response curve, log-spaced 20 Hz – 20 kHz. */
    val DISPLAY_FREQUENCIES_HZ = FloatArray(96) { 20f * 1000f.pow(it / 95f) }

    /** The FFT block DP actually uses — EffectDynamicsProcessing.cpp's rounding. */
    fun blockSize(sampleRateHz: Int): Int {
        val desired = (FRAME_DURATION_MS * sampleRateHz / 1000f).toInt()
        var block = 8
        while (block < desired) block = block shl 1
        return block
    }

    /**
     * Adds one stage's band gains onto [binsDb] (block / 2 + 1 bins), each
     * band as a step over the bins DP assigns it.
     */
    fun addBandSteps(
        binsDb: FloatArray,
        bandCount: Int,
        cutoffHz: (Int) -> Float,
        gainDb: (Int) -> Float,
        enabled: (Int) -> Boolean,
        blockSize: Int,
        sampleRateHz: Int
    ) {
        var next = 0
        for (b in 0 until bandCount) {
            val stop = (0.5f + cutoffHz(b) * blockSize / sampleRateHz).toInt()
            if (enabled(b)) {
                val g = gainDb(b)
                for (k in next..minOf(stop, binsDb.size - 1)) binsDb[k] += g
            }
            // AOSP sets binNext = binStop + 1 even when that moves BACKWARDS
            // (a cutoff below the previous one): the next band then re-processes
            // bins an earlier band already took. Reproduced, not corrected.
            next = stop + 1
        }
    }

    /**
     * The bins read at display frequencies. Linear in dB between bin centres:
     * DP's analysis window smears each bin's gain into its neighbours, so the
     * real response ramps between them rather than stepping.
     */
    fun sample(binsDb: FloatArray, freqsHz: FloatArray, blockSize: Int, sampleRateHz: Int): FloatArray =
        FloatArray(freqsHz.size) { i ->
            val pos = (freqsHz[i] * blockSize / sampleRateHz).coerceIn(0f, (binsDb.size - 1).toFloat())
            val lo = floor(pos).toInt()
            val hi = minOf(lo + 1, binsDb.size - 1)
            val t = pos - lo
            binsDb[lo] + (binsDb[hi] - binsDb[lo]) * t
        }

    /** Bin index DP gives [frequencyHz] — for tests and diagnostics. */
    fun binOf(frequencyHz: Float, blockSize: Int, sampleRateHz: Int): Int =
        (frequencyHz * blockSize / sampleRateHz).roundToInt()
}
