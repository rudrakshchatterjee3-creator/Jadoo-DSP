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

    private val balancedTarget = floatArrayOf(
        0.8f,
        0.7f,
        0.45f,
        0.2f,
        0.0f,
        0.0f,
        0.0f,
        0.1f,
        0.15f,
        0.2f,
        0.25f,
        0.35f,
        0.45f,
        0.35f,
        0.1f
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
                            // Re-initialize filter engines if actual rate differs from default
                            service.updateEngineSampleRate(samplingRate.toFloat())
                            val mappedDb = mapFftToBands(
                                fft = fft,
                                samplingRateHz = samplingRate.toFloat(),
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
                Log.d(TAG, "Correction cycle: sampleCount=$sampleCount, " +
                        "firstBand=%.2f lastBand=%.2f".format(correction[0], correction[14]))
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
        val rel = normalizeAroundAverage(rawEstimate)

        // ── Spectral region energies (relative to average) ──────────
        // Bands: 0-3 sub/bass, 4-6 low-mid, 7-9 mid, 10-11 presence, 12-14 air
        val bassEnergy     = (rel[0] + rel[1] + rel[2] + rel[3]) / 4f
        val lowMidEnergy   = (rel[4] + rel[5] + rel[6]) / 3f
        val midEnergy      = (rel[7] + rel[8] + rel[9]) / 3f
        val presenceEnergy = (rel[10] + rel[11]) / 2f
        val airEnergy      = (rel[12] + rel[13] + rel[14]) / 3f

        // ── Intelligent per-song correction ─────────────────────────
        val correction = FloatArray(EqBands.count) { 0f }

        // Thin/weak bass → add warmth (most headphone deficiency is 63-100 Hz, not 40 Hz)
        // When DBFB is active, it handles bass reinforcement via MBC — don't fight it.
        if (bassEnergy < -1.5f && !dbfbOn) {
            val boost = (-bassEnergy - 1.5f).coerceAtMost(2.5f)
            correction[1] += boost * 0.4f  // 40 Hz
            correction[2] += boost * 0.8f  // 63 Hz — main thin-bass zone
            correction[3] += boost * 0.7f  // 100 Hz
            correction[4] += boost * 0.2f  // 160 Hz — slight body
        }
        // Excessive bass (boomy) → tame. Threshold raised to 2.5 so normal bass-heavy
        // tracks aren't falsely flagged. Cut is gentler to avoid over-correction.
        if (bassEnergy > 2.5f) {
            val aggressiveness = if (dbfbOn) 0.2f else 0.7f
            val cut = (bassEnergy - 2.5f).coerceAtMost(1.5f) * aggressiveness
            correction[0] -= cut * 0.15f
            correction[1] -= cut * 0.3f
            correction[2] -= cut * 0.4f
            correction[3] -= cut * 0.25f
        }

        // Boxy/muddy low-mids → cut
        if (lowMidEnergy > 1.5f) {
            val cut = (lowMidEnergy - 1.5f).coerceAtMost(2.0f)
            correction[4] -= cut * 0.3f
            correction[5] -= cut * 0.5f
            correction[6] -= cut * 0.4f
        }
        // Thin/recessed low-mids (brittle, hollow recordings) → gentle lift
        if (lowMidEnergy < -2.0f) {
            val boost = (-lowMidEnergy - 2.0f).coerceAtMost(1.5f)
            correction[4] += boost * 0.3f
            correction[5] += boost * 0.5f
            correction[6] += boost * 0.4f
        }

        // Muddy mids → cut; thin mids (nasal hollow sound) → gentle lift
        if (midEnergy > 2.0f) {
            val cut = (midEnergy - 2.0f).coerceAtMost(1.5f)
            correction[7] -= cut * 0.3f
            correction[8] -= cut * 0.4f
            correction[9] -= cut * 0.3f
        }
        if (midEnergy < -2.0f) {
            val boost = (-midEnergy - 2.0f).coerceAtMost(1.5f)
            correction[7] += boost * 0.3f
            correction[8] += boost * 0.4f
            correction[9] += boost * 0.3f
        }

        // Harsh upper-mids → gentle dip
        if (presenceEnergy > 2.0f) {
            val cut = (presenceEnergy - 2.0f).coerceAtMost(1.5f)
            correction[10] -= cut * 0.4f
            correction[11] -= cut * 0.3f
        }
        // Weak presence (muffled vocals) → lift
        if (presenceEnergy < -1.5f) {
            val boost = (-presenceEnergy - 1.5f).coerceAtMost(2.0f)
            correction[10] += boost * 0.5f
            correction[11] += boost * 0.4f
        }

        // Muffled air / old recording → open up treble.
        // When Hi-Res is already active, it handles 8–20 kHz via MBC expansion.
        // In that case we never push air bands upward to avoid double-boosting.
        if (!hiResOn) {
            if (airEnergy < -1.5f) {
                val boost = (-airEnergy - 1.5f).coerceAtMost(2.5f)
                correction[12] += boost * 0.5f
                correction[13] += boost * 0.6f
                correction[14] += boost * 0.5f
            }
        }

        // Hi-Res guard: clamp any residual upward correction on air bands to zero.
        if (hiResOn) {
            correction[12] = correction[12].coerceAtMost(0f)
            correction[13] = correction[13].coerceAtMost(0f)
            correction[14] = correction[14].coerceAtMost(0f)
        }

        // ── Gentle blend toward selected target curve ───────────────
        // This is a soft nudge (20%), not a hard template.
        // When Hi-Res is on, the target blend is also capped to 0 for air bands.
        val relTarget = normalizeAroundAverage(target)
        for (i in 0 until EqBands.count) {
            var targetNudge = (relTarget[i] - rel[i]) * TARGET_BLEND_STRENGTH
            if (hiResOn && i >= 12) targetNudge = targetNudge.coerceAtMost(0f)
            correction[i] = (correction[i] + targetNudge).coerceIn(-MAX_CORRECTION_DB, MAX_CORRECTION_DB)
        }

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
                val re = (fft[byteIndex].toInt() and 0xFF).toDouble()
                val im = (fft[byteIndex + 1].toInt() and 0xFF).toDouble()
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
        // Target curve blend reduced from 20% to 5% so spectral analysis dominates.
        // Auto-EQ should correct actual problems, not push every song toward a template.
        private const val TARGET_BLEND_STRENGTH = 0.05f
        private const val SILENCE_DB = -96.0f
    }
}
