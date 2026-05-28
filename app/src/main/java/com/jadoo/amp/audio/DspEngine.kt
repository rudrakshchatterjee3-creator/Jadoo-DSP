package com.jadoo.amp.audio

import android.media.audiofx.DynamicsProcessing
import android.util.Log

class DspEngine {
    var dynamicsProcessing: DynamicsProcessing? = null
        private set

    private var preGainDb = 0f
    private var postGainDb = 0f
    // Cached limiter reference so setPostGain can update postGain reliably
    // without a read-modify-write that may return stale state on some devices.
    private var currentLimiter: DynamicsProcessing.Limiter? = null

    fun attach(
        sessionId: Int,
        initialGains: FloatArray = FloatArray(EqBands.count) { 0f },
        initialPreGainDb: Float = preGainDb,
        initialPostGainDb: Float = postGainDb,
        hiResEnabled: Boolean = false,
        dbfbMode: DbfbMode = DbfbMode.Off,
        surroundPlusEnabled: Boolean = false,
        hdrDynamicsEnabled: Boolean = false,
        hdrMode: HdrMode = HdrMode.Restoration,
        analogBassEnabled: Boolean = false,
        analogBassDrive: Float = 0.4f,
        analogBassWarmth: Float = 0.7f,
        analogBassPultecBoost: Float = 0.5f,
        analogBassPultecCut: Float = 0.3f,
        analogBassPultecFreqIndex: Int = 2
    ): Boolean = synchronized(this) {
        var newDynamicsProcessing: DynamicsProcessing? = null
        try {
            preGainDb = initialPreGainDb.coerceIn(-12f, 12f)
            postGainDb = initialPostGainDb.coerceIn(-12f, 12f)

            // HiRes note: the air band (20kHz) uses getBand(index) without index++, then returns.
            // So the actual band count for HiRes paths is (configured indices) = mbcBandCount,
            // not mbcBandCount+1. All HiRes counts are 1 less than the sum of their parts.
            val mbcBandCount = when {
                hiResEnabled && dbfbMode != DbfbMode.Off && hdrDynamicsEnabled && analogBassEnabled -> 10 // AnalogBass(3)+DBFB(3)+HDR(1)+HiRes(3)
                hiResEnabled && dbfbMode != DbfbMode.Off && hdrDynamicsEnabled -> 7 // DBFB(3)+HDR(1)+HiRes(3)
                hiResEnabled && hdrDynamicsEnabled && analogBassEnabled -> 7 // AnalogBass(3)+HDR(1)+HiRes(3)
                hiResEnabled && dbfbMode != DbfbMode.Off && analogBassEnabled -> 9 // AnalogBass(3)+DBFB(3)+HiRes(3)
                hiResEnabled && hdrDynamicsEnabled -> 4           // HDR(1)+HiRes(3)
                hiResEnabled && dbfbMode != DbfbMode.Off -> 6    // DBFB(3)+HiRes(3)
                hiResEnabled && analogBassEnabled -> 7           // AnalogBass(3)+safety(1)+HiRes(3)
                hiResEnabled -> 4                                 // safety(1)+HiRes(3)
                dbfbMode != DbfbMode.Off && hdrDynamicsEnabled && analogBassEnabled -> 7 // AnalogBass(3)+DBFB(3)+HDR(1)
                dbfbMode != DbfbMode.Off && hdrDynamicsEnabled -> 4 // DBFB(3)+HDR(1)
                dbfbMode != DbfbMode.Off && analogBassEnabled -> 7 // AnalogBass(3)+DBFB(3)
                dbfbMode != DbfbMode.Off -> 4                     // DBFB(3)+safety(1)
                hdrDynamicsEnabled && analogBassEnabled -> 4      // AnalogBass(3)+HDR(1)
                hdrDynamicsEnabled -> 1                           // HDR(1)
                analogBassEnabled -> 4                             // AnalogBass(3)+safety(1)
                else -> 1
            }

            val postEqBandCount = if (analogBassEnabled) 4 else 0
            val configBuilder = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true,
                EqBands.count,
                true,
                mbcBandCount,
                analogBassEnabled,  // postEqInUse: enabled for Pultec-style EQ
                postEqBandCount,
                true
            )

            val preEq = DynamicsProcessing.Eq(true, true, EqBands.count)
            for (i in 0 until EqBands.count) {
                preEq.getBand(i).cutoffFrequency = EqBands.cutoffFrequencies[i]
                preEq.getBand(i).gain = initialGains.getOrNull(i)?.coerceIn(-15f, 15f) ?: 0f
            }
            configBuilder.setPreEqAllChannelsTo(preEq)

            val mbc = DynamicsProcessing.Mbc(true, true, mbcBandCount)
            configureMbc(mbc, hiResEnabled, dbfbMode, hdrDynamicsEnabled, hdrMode, analogBassEnabled, analogBassDrive, analogBassWarmth)
            configBuilder.setMbcAllChannelsTo(mbc)

            // ── Analog Bass PostEQ: Pultec-style boost/cut curves ────────
            if (analogBassEnabled) {
                val postEq = DynamicsProcessing.Eq(true, true, postEqBandCount)
                configureAnalogBassPostEq(postEq, analogBassPultecFreqIndex, analogBassPultecBoost, analogBassPultecCut, analogBassWarmth)
                configBuilder.setPostEqAllChannelsTo(postEq)
            }

            // ── Gain staging: calculate headroom offset ────────────────
            // When multiple features boost signal (HiRes, DBFB, HDR), the limiter
            // threshold must drop to prevent inter-modulation distortion.
            val headroomDb = calculateHeadroomOffset(hiResEnabled, dbfbMode, analogBassEnabled)

            // HDR mode: softer limiting lets transient peaks breathe.
            // Pure mode: acoustically transparent limiter (near-unity ratio, ceiling
            // just below 0 dBFS) — preserves the postGain path while never audibly
            // compressing. Previous design disabled the limiter entirely (inUse=false)
            // which also silently disabled postGain, breaking the post-gain slider.
            // Standard mode: tight brickwall limiting.
            val limiter = when {
                hdrDynamicsEnabled && hdrMode == HdrMode.Pure -> {
                    // Pure: transparent limiter — ratio ≈ 1 so it barely compresses,
                    // but the limiter stage remains active so postGain is applied.
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        1f,    // fast attack to catch rare overs cleanly
                        50f,   // quick release — imperceptible
                        1.001f, // near-unity ratio: acoustically transparent
                        -0.1f + headroomDb,
                        postGainDb
                    )
                }
                hdrDynamicsEnabled -> {
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        15f, 180f, 4f, -0.3f + headroomDb, postGainDb
                    )
                }
                else -> {
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        5f, 50f, 1.001f, 0f + headroomDb, postGainDb
                    )
                }
            }
            currentLimiter = limiter   // cache for reliable postGain updates
            configBuilder.setLimiterAllChannelsTo(limiter)

            // Build the new DP BEFORE releasing the old one.
            // If construction throws (device band-count limit, session conflict, etc.)
            // the old DP — which still has DBFB/HiRes/HDR configured — stays alive
            // and keeps processing. Previously the old was released first, so any
            // failure silently killed all DSP while the UI still showed features as on.
            newDynamicsProcessing = DynamicsProcessing(0, sessionId, configBuilder.build())

            // Success — swap and clean up old instance.
            // Android auto-releases the old DP when a new one of the same type is
            // created on the same session, so enabled=false may throw. Use a nested
            // try-catch so that cleanup errors don't cascade to the outer catch
            // (which would release the brand-new DP and break everything).
            val oldDynamicsProcessing = dynamicsProcessing
            dynamicsProcessing = newDynamicsProcessing
            try {
                oldDynamicsProcessing?.enabled = false
                oldDynamicsProcessing?.release()
            } catch (cleanupEx: Exception) {
                Log.w("DspEngine", "Old DP cleanup skipped (likely auto-released by Android): ${cleanupEx.message}")
            }

            newDynamicsProcessing.enabled = true
            setPreGain(preGainDb)
            setPostGain(postGainDb)
            Log.d("DspEngine", "Attached session=$sessionId hiRes=$hiResEnabled dbfb=$dbfbMode hdr=$hdrDynamicsEnabled surroundPlus=$surroundPlusEnabled mbcBands=$mbcBandCount")
            true
        } catch (e: Exception) {
            Log.e("DspEngine", "Failed to attach DynamicsProcessing — old DP preserved if present", e)
            newDynamicsProcessing?.release()
            currentLimiter = null
            false
        }
    }

    private fun configureMbc(
        mbc: DynamicsProcessing.Mbc,
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        hdrDynamicsEnabled: Boolean = false,
        hdrMode: HdrMode = HdrMode.Restoration,
        analogBassEnabled: Boolean = false,
        analogBassDrive: Float = 0.4f,
        analogBassWarmth: Float = 0.7f
    ) {
        var index = 0

        // ── Analog Bass Engine: Drive-controlled saturation simulation (20-300Hz) ──
        // Drive controls preGain (0→9dB drive), warmth controls postGain and ratio.
        // These bands produce clearly audible compression-saturation effects.
        if (analogBassEnabled) {
            val driveGain  = analogBassDrive  * 9f    // 0–9 dB input drive
            val warmthGain = analogBassWarmth * 3f    // 0–3 dB warmth output
            val compRatio  = 1.8f + analogBassDrive * 3.2f  // ratio 1.8–5.0

            // Sub-bass (20-60Hz): heavy saturation drive
            mbc.getBand(index++).apply {
                cutoffFrequency = 60f
                attackTime = 8f
                releaseTime = 200f
                ratio = compRatio
                threshold = -35f + analogBassDrive * 15f  // -35 to -20 dB
                kneeWidth = 12f
                noiseGateThreshold = -85f
                expanderRatio = 1f
                preGain  = driveGain                          // drive into compressor
                postGain = warmthGain - driveGain * 0.45f     // net: warmth minus drive bleed
            }
            // Low bass (60-120Hz): warmth body
            mbc.getBand(index++).apply {
                cutoffFrequency = 120f
                attackTime = 12f
                releaseTime = 180f
                ratio = 1.4f + analogBassWarmth * 1.2f
                threshold = -30f
                kneeWidth = 15f
                noiseGateThreshold = -88f
                expanderRatio = 1f
                preGain  = warmthGain * 0.8f
                postGain = warmthGain * 1.2f                  // warm body boost
            }
            // Upper bass (120-300Hz): mud control + Pultec dip character
            mbc.getBand(index++).apply {
                cutoffFrequency = 300f
                attackTime = 15f
                releaseTime = 160f
                ratio = 1.5f + analogBassDrive * 0.5f
                threshold = -24f
                kneeWidth = 10f
                noiseGateThreshold = -86f
                expanderRatio = 1.05f
                preGain  = -(analogBassWarmth * 1.5f)         // Pultec simultaneous dip
                postGain = 1.8f                               // body makeup gain
            }
        }

        // ── DBFB: Dynamic Bass Feedback (bands 0-260Hz) ──────────────
        if (dbfbMode != DbfbMode.Off) {
            val normal = dbfbMode == DbfbMode.Normal
            val subPostGain = if (normal) 2.2f else 4.0f
            val punchPostGain = if (normal) 1.2f else 2.2f
            val safetyThreshold = if (normal) -10.5f else -12.5f

            mbc.getBand(index++).apply {
                cutoffFrequency = 72f
                attackTime = 4f
                releaseTime = 115f
                ratio = 1.35f
                threshold = safetyThreshold
                kneeWidth = 7f
                noiseGateThreshold = -78f
                expanderRatio = 1.12f
                preGain = -0.4f
                postGain = subPostGain
            }
            mbc.getBand(index++).apply {
                cutoffFrequency = 145f
                attackTime = 5f
                releaseTime = 95f
                ratio = 1.5f
                threshold = safetyThreshold + 1.5f
                kneeWidth = 6f
                noiseGateThreshold = -82f
                expanderRatio = 1.06f
                preGain = -0.2f
                postGain = punchPostGain
            }
            mbc.getBand(index++).apply {
                cutoffFrequency = 260f
                attackTime = 8f
                releaseTime = 135f
                ratio = 1.8f
                threshold = -8.5f
                kneeWidth = 5f
                noiseGateThreshold = -90f
                expanderRatio = 1f
                preGain = 0f
                postGain = if (normal) -0.35f else -0.55f
            }
        }

        // ── HDR: Limiter-softening pass-through band ─────────────────
        //
        // The real HDR effect is the limiter (see attach() above): slower attack
        // and softer ratio let transient peaks breathe instead of hitting a brickwall.
        // This MBC band is kept for topology reasons but is fully transparent — no
        // compression, no expansion, no gain change. The signal passes through
        // unaffected. A single full-range band avoids unnecessary crossover filters.
        if (hdrDynamicsEnabled) {
            if (!hiResEnabled) {
                mbc.getBand(index).apply {
                    cutoffFrequency = 20000f
                    attackTime = 15f
                    releaseTime = 180f
                    ratio = 1.0f
                    threshold = 0f
                    kneeWidth = 0f
                    noiseGateThreshold = -90f
                    expanderRatio = 1.0f
                    preGain = 0f
                    postGain = 0f
                }
                return  // fully configured: [AnalogBass?] + [DBFB?] + HDR(1) = done
            }
            // With HiRes: single transparent band up to HiRes crossover at 5.2 kHz
            mbc.getBand(index++).apply {
                cutoffFrequency = 5200f
                attackTime = 15f
                releaseTime = 180f
                ratio = 1.0f
                threshold = 0f
                kneeWidth = 0f
                noiseGateThreshold = -90f
                expanderRatio = 1.0f
                preGain = 0f
                postGain = 0f
            }
        }

        // ── HiRes: Air-band expansion (5200Hz–20kHz) ─────────────────
        if (hiResEnabled) {
            // If neither DBFB nor HDR provided bands below, add a safety band
            if (!hdrDynamicsEnabled && dbfbMode == DbfbMode.Off) {
                mbc.getBand(index++).apply {
                    cutoffFrequency = 5200f
                    attackTime = 9f
                    releaseTime = 70f
                    ratio = 1f
                    threshold = 0f
                    kneeWidth = 0f
                    noiseGateThreshold = -90f
                    expanderRatio = 1f
                    preGain = 0f
                    postGain = 0f
                }
            }
            // 5.2–9.6 kHz: presence/clarity band
            mbc.getBand(index++).apply {
                cutoffFrequency = 9600f
                attackTime = 1.5f
                releaseTime = 34f
                ratio = 1.08f
                threshold = -14f
                kneeWidth = 9f
                noiseGateThreshold = -86f
                expanderRatio = 1.18f
                preGain = 0.5f
                postGain = 2.2f
            }
            // 9.6–14.5 kHz: the "silk" band
            mbc.getBand(index++).apply {
                cutoffFrequency = 14500f
                attackTime = 0.9f
                releaseTime = 24f
                ratio = 1.04f
                threshold = -18f
                kneeWidth = 10f
                noiseGateThreshold = -88f
                expanderRatio = 1.24f
                preGain = 0.6f
                postGain = 3.5f             // reduced from 4.2 to prevent harshness with HDR
            }
            // 14.5–20 kHz: pure air band
            mbc.getBand(index).apply {
                cutoffFrequency = 20000f
                attackTime = 0.6f
                releaseTime = 18f
                ratio = 1.02f
                threshold = -22f
                kneeWidth = 12f
                noiseGateThreshold = -90f
                expanderRatio = 1.28f
                preGain = 0.8f
                postGain = 4.5f             // reduced from 5.5 to prevent intermod with HDR
            }
            return  // fully configured: DBFB(opt) + HDR(opt) + HiRes(4) = done
        }

        // ── Fallback: no features enabled, single transparent passthrough ──
        if (dbfbMode == DbfbMode.Off && !hdrDynamicsEnabled) {
            mbc.getBand(index).apply {
                cutoffFrequency = 20000f
                attackTime = 5f
                releaseTime = 65f
                ratio = 1f
                threshold = 0f
                kneeWidth = 0f
                noiseGateThreshold = -90f
                expanderRatio = 1f
                preGain = 0f
                postGain = 0f
            }
        } else if (dbfbMode != DbfbMode.Off && !hdrDynamicsEnabled) {
            mbc.getBand(index).apply {
                cutoffFrequency = 20000f
                attackTime = 6f
                releaseTime = 80f
                ratio = 1f
                threshold = 0f
                kneeWidth = 0f
                noiseGateThreshold = -90f
                expanderRatio = 1f
                preGain = 0f
                postGain = 0f
            }
        }
    }

    /**
     * Configure PostEQ bands for Pultec-style boost/cut on the analog bass path.
     * Band 0: Low-shelf boost at/below pultec frequency (Pultec boost)
     * Band 1: Slight dip above pultec frequency (the Pultec simultaneous cut trick)
     * Band 2: Warmth zone body boost
     * Band 3: Full-spectrum endpoint (flat, required to cover Nyquist)
     */
    private fun configureAnalogBassPostEq(
        postEq: DynamicsProcessing.Eq,
        pultecFreqIndex: Int,
        pultecBoost: Float,
        pultecCut: Float,
        warmth: Float
    ) {
        val pultecFreqs = AnalogBassEngine.PULTEC_FREQUENCIES
        val pultecFreq = pultecFreqs[pultecFreqIndex.coerceIn(0, pultecFreqs.size - 1)]
        // Band 0: below/at pultec freq — the Pultec BOOST
        postEq.getBand(0).apply {
            cutoffFrequency = (pultecFreq * 1.5f).coerceIn(25f, 200f)
            gain = pultecBoost * 8f        // 0–8 dB boost
        }
        // Band 1: just above pultec freq — the Pultec simultaneous CUT (creates the resonant dip)
        postEq.getBand(1).apply {
            cutoffFrequency = (pultecFreq * 4f).coerceIn(80f, 500f)
            gain = -(pultecCut * 5f)       // 0–5 dB dip
        }
        // Band 2: warmth zone (mid-bass body)
        postEq.getBand(2).apply {
            cutoffFrequency = 500f
            gain = warmth * 2.5f           // 0–2.5 dB warmth
        }
        // Band 3: full-range endpoint (flat)
        postEq.getBand(3).apply {
            cutoffFrequency = 20000f
            gain = 0f
        }
    }

    /** Live-update the analog bass PostEQ bands without a full topology rebuild. */
    fun updateAnalogBassPostEq(
        pultecBoost: Float,
        pultecCut: Float,
        pultecFreqIndex: Int,
        warmth: Float
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            val pultecFreqs = AnalogBassEngine.PULTEC_FREQUENCIES
            val pultecFreq = pultecFreqs[pultecFreqIndex.coerceIn(0, pultecFreqs.size - 1)]
            val band0 = dp.getPostEqByChannelIndex(0).getBand(0).apply {
                cutoffFrequency = (pultecFreq * 1.5f).coerceIn(25f, 200f)
                gain = pultecBoost * 8f
            }
            val band1 = dp.getPostEqByChannelIndex(0).getBand(1).apply {
                cutoffFrequency = (pultecFreq * 4f).coerceIn(80f, 500f)
                gain = -(pultecCut * 5f)
            }
            val band2 = dp.getPostEqByChannelIndex(0).getBand(2).apply {
                cutoffFrequency = 500f
                gain = warmth * 2.5f
            }
            dp.setPostEqBandAllChannelsTo(0, band0)
            dp.setPostEqBandAllChannelsTo(1, band1)
            dp.setPostEqBandAllChannelsTo(2, band2)
            Log.d("DspEngine", "Analog bass PostEQ updated: boost=${pultecBoost * 8f}dB cut=${-(pultecCut * 5f)}dB warmth=${warmth * 2.5f}dB")
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating analog bass PostEQ", e)
        }
    }

    /** Live-update the analog bass MBC bands (drive + warmth) without full rebuild. */
    fun updateAnalogBassMbc(drive: Float, warmth: Float) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            val driveGain  = drive  * 9f
            val warmthGain = warmth * 3f
            val compRatio  = 1.8f + drive * 3.2f

            val band0 = dp.getMbcByChannelIndex(0).getBand(0).apply {
                ratio     = compRatio
                threshold = -35f + drive * 15f
                preGain   = driveGain
                postGain  = warmthGain - driveGain * 0.45f
            }
            val band1 = dp.getMbcByChannelIndex(0).getBand(1).apply {
                ratio    = 1.4f + warmth * 1.2f
                preGain  = warmthGain * 0.8f
                postGain = warmthGain * 1.2f
            }
            val band2 = dp.getMbcByChannelIndex(0).getBand(2).apply {
                ratio   = 1.5f + drive * 0.5f
                preGain = -(warmth * 1.5f)
            }
            dp.setMbcBandAllChannelsTo(0, band0)
            dp.setMbcBandAllChannelsTo(1, band1)
            dp.setMbcBandAllChannelsTo(2, band2)
            Log.d("DspEngine", "Analog bass MBC updated: drive=$drive warmth=$warmth")
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating analog bass MBC", e)
        }
    }

    /**
     * Calculate limiter headroom offset based on active features.
     * When multiple features add gain (HiRes boost, DBFB bass boost),
     * the limiter must have more headroom to prevent inter-modulation distortion.
     */
    private fun calculateHeadroomOffset(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean = false
    ): Float {
        var offset = 0f
        if (hiResEnabled) offset -= 0.6f
        if (dbfbMode == DbfbMode.High) offset -= 0.5f
        else if (dbfbMode == DbfbMode.Normal) offset -= 0.3f
        if (analogBassEnabled) offset -= 0.3f
        return offset
    }

    fun release() = synchronized(this) {
        dynamicsProcessing?.enabled = false
        dynamicsProcessing?.release()
        dynamicsProcessing = null
        currentLimiter = null
    }

    /**
     * Sets the same gain on ALL channels (left + right) for a PreEQ band.
     * WARNING: This DESTROYS any per-channel differential (e.g., surround widening).
     * Only use this when surround/spatial processing is OFF.
     * For surround-aware updates, use the caller's per-channel logic instead.
     */
    fun setPreEqBandGainAllChannels(bandIndex: Int, gainDb: Float) {
        synchronized(this) {
            if (bandIndex !in 0 until EqBands.count) return

            val dp = dynamicsProcessing ?: return
            try {
                val preEq = dp.getPreEqByChannelIndex(0)
                val band = preEq.getBand(bandIndex)
                band.gain = gainDb.coerceIn(-15f, 15f)
                dp.setPreEqBandAllChannelsTo(bandIndex, band)
            } catch (e: Exception) {
                Log.e("DspEngine", "Error setting EQ band", e)
            }
        }
    }

    fun getPreEqBandGain(bandIndex: Int): Float {
        synchronized(this) {
            if (bandIndex !in 0 until EqBands.count) return 0f

            val dp = dynamicsProcessing ?: return 0f
            return try {
                dp.getPreEqByChannelIndex(0).getBand(bandIndex).gain
            } catch (e: Exception) {
                0f
            }
        }
    }

    fun setPreGain(gainDb: Float) = synchronized(this) {
        preGainDb = gainDb.coerceIn(-12f, 12f)
        try {
            dynamicsProcessing?.setInputGainAllChannelsTo(preGainDb)
        } catch (e: Exception) {
            Log.e("DspEngine", "Error setting pre gain", e)
        }
    }

    fun setPostGain(gainDb: Float) {
        synchronized(this) {
            postGainDb = gainDb.coerceIn(-12f, 12f)
            val dp = dynamicsProcessing ?: return@synchronized
            val limiter = currentLimiter ?: return@synchronized
            try {
                // Update the cached limiter object and re-apply.
                // Avoids the fragile getLimiterByChannelIndex read-modify-write which
                // can return stale inUse/enabled state on some Android versions,
                // causing the postGain update to silently have no effect.
                limiter.postGain = postGainDb
                dp.setLimiterAllChannelsTo(limiter)
                Log.d("DspEngine", "Post gain set to ${postGainDb}dB")
            } catch (e: Exception) {
                Log.e("DspEngine", "Error setting post gain", e)
            }
        }
    }


}
