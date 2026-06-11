package com.jadoo.amp.audio

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.log10

class PsychoacousticsBrain(
    private val service: JadooDspService
) {
    // SupervisorJob: one coroutine crashing (e.g. during topology rebuild) won't cancel the scope
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val bufferLock = Any()
    private val gainsMutex = Mutex()
    private var correctionJob: Job? = null
    private var visualizer: Visualizer? = null
    private var lastCaptureMs = 0L
    private var writeIndex = 0
    private var sampleCount = 0
    private val rollingWindow = Array(WINDOW_SIZE) { FloatArray(EqBands.count) { SILENCE_DB } }
    @Volatile private var currentGains = FloatArray(EqBands.count) { 0f }
    // Slow-moving spectral reference (EMA), seeded on the first correction cycle
    // after start(). Each cycle measures how far the *current* spectrum sits from
    // this trailing average — a new song/passage shows up as a deviation that
    // drives an immediate correction shift, then decays back toward the mode's
    // target shape as the baseline catches up to the new material.
    @Volatile private var referenceBaseline: FloatArray? = null

    private val harmanTarget = floatArrayOf(
        3.5f,
        3.0f,
        2.5f,
        1.0f,
        0.0f,
        -0.5f,
        -0.5f,
        0.0f,
        0.5f,
        1.5f,
        2.5f,
        3.0f,
        2.0f,
        1.0f,
        0.0f
    )

    // A gentle "fun" curve: bass, vocal/mid presence and air all lifted, with a
    // small dip through the boxy 160-400 Hz region to keep it from sounding muddy.
    private val balancedTarget = floatArrayOf(
        1.2f,
        1.0f,
        0.8f,
        0.5f,
        -0.3f,
        -0.4f,
        -0.2f,
        0.4f,
        0.6f,
        0.5f,
        0.5f,
        0.6f,
        0.6f,
        0.7f,
        0.5f
    )

    private val exquisiteMidsTarget = floatArrayOf(
        -0.5f,
        -0.3f,
        0.0f,
        0.5f,
        1.0f,
        1.5f,
        1.8f,
        2.0f,
        2.0f,
        1.8f,
        1.3f,
        0.8f,
        0.4f,
        0.0f,
        -0.7f
    )

    fun getTargetForMode(mode: AutoEqTargetMode): FloatArray = when (mode) {
        AutoEqTargetMode.Balanced -> balancedTarget.copyOf()
        AutoEqTargetMode.HarmanCurve -> harmanTarget.copyOf()
        AutoEqTargetMode.ExquisiteMids -> exquisiteMidsTarget.copyOf()
    }

    fun start(sessionId: Int?) {
        stop()
        val resolvedSessionId = sessionId ?: return

        // Restore currentGains from whatever is currently applied to the engine so
        // the first rawEstimate = trackAverage - currentGains is accurate.
        // Use a fresh copy to avoid torn reads from the volatile reference.
        val existing = service.bandGains.value
        val restored = FloatArray(EqBands.count) { i -> existing.getOrElse(i) { 0f } }
        currentGains = restored
        referenceBaseline = null

        startVisualizer(resolvedSessionId)
        startCorrectionLoop()
        Log.d(TAG, "Auto-EQ started on session $resolvedSessionId, target=${service.autoEqTargetMode.value}")
    }

    fun stop() {
        // Cancel the correction job and wait for the glide to actually finish
        // so we never write to a released DynamicsProcessing instance.
        val job = correctionJob
        correctionJob = null
        job?.cancel()

        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        lastCaptureMs = 0L
        synchronized(bufferLock) {
            writeIndex = 0
            sampleCount = 0
            rollingWindow.forEach { sample ->
                sample.fill(SILENCE_DB)
            }
        }
        Log.d(TAG, "Dynamic Harman Auto-EQ stopped")
    }

    private fun startVisualizer(sessionId: Int) {
        try {
            val captureSizeRange = Visualizer.getCaptureSizeRange()
            val captureSize = captureSizeRange[1]
            Log.d(TAG, "Starting Visualizer on session $sessionId, captureSize=$captureSize")
            val nextVisualizer = Visualizer(sessionId).apply {
                enabled = false
                this.captureSize = captureSize
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer,
                            waveform: ByteArray,
                            samplingRate: Int
                        ) = Unit

                        override fun onFftDataCapture(
                            visualizer: Visualizer,
                            fft: ByteArray,
                            samplingRate: Int
                        ) {
                            val now = System.currentTimeMillis()
                            if (now - lastCaptureMs < CAPTURE_INTERVAL_MS) return
                            lastCaptureMs = now
                            // Visualizer reports samplingRate in milliHertz, not Hz.
                            val samplingRateHz = samplingRate / 1000f
                            // Re-initialize filter engines if actual rate differs from default
                            service.updateEngineSampleRate(samplingRateHz)
                            val mappedDb = mapFftToBands(
                                fft = fft,
                                samplingRateHz = samplingRateHz,
                                captureSize = visualizer.captureSize
                            )
                            recordCapture(mappedDb)
                        }
                    },
                    CAPTURE_RATE_MILLIHERTZ,
                    false,
                    true
                )
                enabled = true
            }
            visualizer = nextVisualizer
            Log.d(TAG, "Visualizer started successfully on session $sessionId")
        } catch (exception: Exception) {
            Log.e(TAG, "VISUALIZER FAILED on session $sessionId — dynamic Auto-EQ will not run. " +
                    "Check that RECORD_AUDIO permission is granted.", exception)
        }
    }

    private fun recordCapture(mappedDb: FloatArray) {
        // Skip silence frames — they skew the rolling average and corrupt corrections.
        // Use max instead of average: bass-heavy tracks can have low average but
        // still be playing music. Only skip if ALL bands are near silence.
        if (mappedDb.max() < -50.0) return
        synchronized(bufferLock) {
            rollingWindow[writeIndex] = mappedDb
            writeIndex = (writeIndex + 1) % WINDOW_SIZE
            sampleCount = (sampleCount + 1).coerceAtMost(WINDOW_SIZE)
        }
        if (sampleCount <= 3) {
            Log.d(TAG, "FFT sample #$sampleCount recorded, bass=%.1fdB mid=%.1fdB treble=%.1fdB"
                .format(mappedDb[3], mappedDb[8], mappedDb[13]))
        }
    }

    private fun startCorrectionLoop() {
        correctionJob = scope.launch {
            while (isActive) {
                delay(CORRECTION_INTERVAL_MS)
                if (sampleCount < MIN_SAMPLES_BEFORE_CORRECTION) {
                    Log.w(TAG, "Not enough FFT samples ($sampleCount < $MIN_SAMPLES_BEFORE_CORRECTION); skipping correction cycle.")
                    continue
                }
                val trackAverage = averageRollingWindow()
                val correction = calculateCorrection(trackAverage)
                Log.d(TAG, "Correction cycle: sampleCount=$sampleCount, correction=[%s]"
                    .format(correction.joinToString(", ") { "%.2f".format(it) }))
                glideTo(smoothTargetGains(correction))
            }
        }
    }

    private fun averageRollingWindow(): FloatArray {
        return synchronized(bufferLock) {
            if (sampleCount == 0) {
                FloatArray(EqBands.count) { SILENCE_DB }
            } else {
                FloatArray(EqBands.count) { band ->
                    var total = 0f
                    for (sample in 0 until sampleCount) {
                        total += rollingWindow[sample][band]
                    }
                    total / sampleCount
                }
            }
        }
    }

    private fun calculateCorrection(trackAverage: FloatArray): FloatArray {
        val target = when (service.autoEqTargetMode.value) {
            AutoEqTargetMode.Balanced -> balancedTarget
            AutoEqTargetMode.HarmanCurve -> harmanTarget
            AutoEqTargetMode.ExquisiteMids -> exquisiteMidsTarget
        }
        val hiResOn = service.hiResUpscalerEnabled.value
        val dbfbOn = service.dbfbMode.value != DbfbMode.Off

        // Estimate the raw (pre-EQ) spectrum by subtracting our own current gains.
        // Use the volatile snapshot for thread safety.
        val gainsSnapshot = currentGains
        val rawEstimate = FloatArray(EqBands.count) { i ->
            trackAverage[i] - gainsSnapshot[i]
        }

        // Compare against the trailing baseline, then slide the baseline a step
        // toward this cycle's reading. A new song/passage initially looks like a
        // big deviation from the old baseline (immediate, visible reaction), which
        // decays back toward zero over the following cycles as the baseline catches
        // up — "real-time scanning" instead of a one-shot snapshot.
        val baseline = referenceBaseline ?: rawEstimate.copyOf()
        val deviation = normalizeAroundAverage(FloatArray(EqBands.count) { i -> rawEstimate[i] - baseline[i] })
        referenceBaseline = FloatArray(EqBands.count) { i ->
            baseline[i] + (rawEstimate[i] - baseline[i]) * BASELINE_EMA_ALPHA
        }

        // The selected curve's shape (mean removed) is the dominant, always-on
        // correction — applied at full strength from the very first cycle, so each
        // mode has its own immediate, audible character instead of waiting for
        // region thresholds to slowly trip.
        val relTarget = normalizeAroundAverage(target)

        // Small per-song nudge on top of the target shape: if this passage is
        // brighter/darker/bassier than the frozen baseline, lean gently the other
        // way so the result still converges toward the target instead of just
        // inheriting whatever tonal quirks this particular track has.
        val adaptive = FloatArray(EqBands.count) { i -> -deviation[i] * ADAPTIVE_STRENGTH }

        // DBFB already reinforces bass via MBC — don't also push bass up adaptively.
        if (dbfbOn) {
            for (i in 0..3) adaptive[i] = adaptive[i].coerceAtMost(0f)
        }
        // Hi-Res already expands 8-20 kHz — don't also push air bands up adaptively.
        if (hiResOn) {
            for (i in 12..14) adaptive[i] = adaptive[i].coerceAtMost(0f)
        }

        val correction = FloatArray(EqBands.count) { i ->
            (relTarget[i] + adaptive[i]).coerceIn(-MAX_CORRECTION_DB, MAX_CORRECTION_DB)
        }

        Log.d(TAG, "Correction calc [${service.autoEqTargetMode.value}]: relTarget=[%s] deviation=[%s] adaptive=[%s] -> correction=[%s]"
            .format(
                relTarget.joinToString(", ") { "%.2f".format(it) },
                deviation.joinToString(", ") { "%.2f".format(it) },
                adaptive.joinToString(", ") { "%.2f".format(it) },
                correction.joinToString(", ") { "%.2f".format(it) }
            ))

        return correction
    }

    private fun normalizeAroundAverage(values: FloatArray): FloatArray {
        val average = values.sum() / values.size.coerceAtLeast(1)
        return FloatArray(EqBands.count) { index ->
            values[index] - average
        }
    }

    private fun smoothTargetGains(targetGains: FloatArray): FloatArray {
        return FloatArray(EqBands.count) { index ->
            val previous = targetGains.getOrNull(index - 1) ?: targetGains[index]
            val current = targetGains[index]
            val next = targetGains.getOrNull(index + 1) ?: targetGains[index]
            (previous + current + next) / 3f
        }
    }

    private suspend fun glideTo(targetGains: FloatArray) {
        val startGains = currentGains.copyOf()
        val steps = GLIDE_DURATION_MS / GLIDE_STEP_MS
        for (step in 1..steps) {
            if (!currentCoroutineContext().isActive) return  // bail if job cancelled (e.g. DBFB toggle)
            val progress = step.toFloat() / steps.toFloat()
            val frame = FloatArray(EqBands.count) { index ->
                startGains[index] + ((targetGains[index] - startGains[index]) * progress)
            }
            // Atomically swap the gains reference so readers always see a consistent snapshot
            gainsMutex.withLock {
                currentGains = frame.copyOf()
            }
            try {
                service.applyBandsToEngine(frame)
                service.updateBandGains(frame)
            } catch (e: CancellationException) {
                throw e  // always re-throw cancellation
            } catch (e: Exception) {
                // DSP instance was released mid-glide (topology rebuild) — skip this frame
                Log.w(TAG, "glide frame skipped during DSP rebuild: ${e.message}")
            }
            delay(GLIDE_STEP_MS)
        }
        // Clear rolling window so the next correction cycle's trackAverage
        // only contains samples captured with the new EQ applied. Without this,
        // rawEstimate = trackAverage - currentGains is contaminated by samples
        // that were captured before the glide finished, causing corrections to
        // converge to zero (flat) over multiple cycles.
        synchronized(bufferLock) {
            writeIndex = 0
            sampleCount = 0
            rollingWindow.forEach { it.fill(SILENCE_DB) }
        }
    }

    private fun mapFftToBands(
        fft: ByteArray,
        samplingRateHz: Float,
        captureSize: Int
    ): FloatArray {
        if (fft.isEmpty() || samplingRateHz <= 0f || captureSize <= 0) {
            return FloatArray(EqBands.count) { SILENCE_DB }
        }
        val maxBin = (fft.size / 2) - 1

        return FloatArray(EqBands.count) { band ->
            // Use the band's full frequency span (geometric mean ±half-octave) to
            // average multiple FFT bins per band.  This gives far more stable and
            // accurate readings than picking a single nearest bin.
            val centerFreq = EqBands.frequencies[band]
            val lowerFreq  = centerFreq / 1.41f   // ~half-octave below centre
            val upperFreq  = centerFreq * 1.41f   // ~half-octave above centre

            val binLow  = ((lowerFreq  * captureSize) / samplingRateHz).toInt().coerceIn(1, maxBin)
            val binHigh = ((upperFreq  * captureSize) / samplingRateHz).toInt().coerceIn(1, maxBin)

            var powerSum = 0.0
            var count = 0
            for (bin in binLow..binHigh) {
                val byteIndex = bin * 2
                if (byteIndex + 1 >= fft.size) break
                // FFT bytes are signed Re/Im components (can be negative); sign-extend, don't mask.
                val re = fft[byteIndex].toInt().toDouble()
                val im = fft[byteIndex + 1].toInt().toDouble()
                powerSum += re * re + im * im
                count++
            }
            if (count == 0 || powerSum == 0.0) return@FloatArray SILENCE_DB
            val rms = kotlin.math.sqrt(powerSum / count)
            val db = (20.0 * log10(rms)).toFloat()
            if (db.isFinite()) db else SILENCE_DB
        }
    }

    companion object {
        private const val TAG = "PsychoacousticsBrain"
        private const val WINDOW_SIZE = 12
        private const val MIN_SAMPLES_BEFORE_CORRECTION = 4
        private const val CAPTURE_INTERVAL_MS = 500L
        private const val CAPTURE_RATE_MILLIHERTZ = 2_000
        private const val CORRECTION_INTERVAL_MS = 3_000L
        private const val GLIDE_DURATION_MS = 2_000
        private const val GLIDE_STEP_MS = 100L
        private const val MAX_CORRECTION_DB = 4.5f
        private const val ADAPTIVE_STRENGTH = 0.6f
        private const val BASELINE_EMA_ALPHA = 0.3f
        private const val SILENCE_DB = -96.0f
    }
}
