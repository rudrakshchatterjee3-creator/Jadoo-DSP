package com.jadoo.amp.audio

/**
 * Encoder-input conditioning for the Bluetooth SBC codec.
 *
 * ── Why this is not an equaliser preset ───────────────────────────────────
 *
 * Our DSP runs on the output mix, which is UPSTREAM of the SBC encoder. That
 * makes us the encoder's input conditioner, and it is the only leverage we
 * have: the encoder itself lives in the Bluetooth stack (hardware-offloaded
 * to the BT SoC on most modern phones) and is unreachable from an app. We
 * cannot replace it, and we cannot restore what it discards — a subband it
 * zeroes stays zeroed, and only the headphone could extend bandwidth back.
 *
 * What we CAN do is hand the encoder a signal it encodes well.
 *
 * ── How SBC actually allocates bits ───────────────────────────────────────
 *
 * SBC splits the band into 8 subbands of equal width fs/16 (2756.25 Hz at
 * 44.1 kHz, 3000 Hz at 48 kHz). For each frame it computes one scale factor
 * per subband from that subband's peak, then hands bits out in proportion to
 * those scale factors until the bitpool is exhausted.
 *
 * Two consequences drive everything below:
 *
 *   1. Bits are ZERO-SUM. Every bit spent on a subband is taken from another
 *      subband. Raising the level of a region does pull bits into it — out of
 *      whatever else was using them.
 *
 *   2. Allocation is recomputed EVERY FRAME. A frame is 16 blocks x 8
 *      subbands = 128 samples per channel = 2.9 ms at 44.1 kHz, so the
 *      allocation is redrawn ~344 times a second. When a high subband sits
 *      near the threshold where it receives zero bits, it flickers on and off
 *      at that rate. That flicker is the "watery", swishy character on
 *      cymbals, hats and sibilance. It is the single most recognisable SBC
 *      artifact and it is a LEVEL-STABILITY problem, not a tonal one.
 *
 * ── What the previous implementation did ──────────────────────────────────
 *
 * It applied +7 dB at 16 kHz and +4.5 dB at 10 kHz, described as
 * "pre-emphasis". Two things were wrong with that.
 *
 * First, pre-emphasis is only pre-emphasis if something de-emphasises at the
 * far end. Ours is a treble boost the headphone plays back verbatim — hence
 * the "artificial, EQ'd" character.
 *
 * Second, and worse, it inverted the bit economy. The 16 kHz band spans
 * roughly subbands 4-7, i.e. the top octave that most adults cannot hear and
 * that carries mostly cymbal hash and dither. Boosting it made SBC spend
 * scarce bitpool encoding exactly that, taken straight out of subbands 0-3
 * where the music lives. It also lifted the region's peaks, which raises the
 * per-frame scale factor and makes the flicker in (2) worse, not better.
 *
 * ── What this engine does instead ─────────────────────────────────────────
 *
 * [conditioningCurveDb] — cut the inaudible top octave to FREE bitpool, and
 * spend a small part of what is freed on the 8-12 kHz region, which is both
 * genuinely audible and cheap to encode. The presence scoop is reduced to a
 * fraction of what it was, and the bass lift is gone entirely: boosting
 * subband 0 inflates its scale factor, which coarsens quantisation across
 * 0-2.7 kHz — the most perceptually loaded part of the spectrum.
 *
 * [crestBandCutoffHz] and the CREST_* constants — a fast compressor across
 * the starved HF subbands. Lowering the peak-to-average ratio there lowers
 * the per-frame scale factor, which shrinks the quantisation step and keeps
 * the subband clear of the zero-bit threshold it was oscillating around.
 * This is the part that addresses the watery highs at the source rather than
 * masking them with tone controls.
 *
 * [HEADROOM_TRIM_DB] — SBC reconstruction can overshoot the original peak, so
 * material mastered near 0 dBFS clips in the receiver's DAC. A small trim
 * before the encoder costs almost nothing and removes that failure mode.
 *
 * ── What is deliberately not here ─────────────────────────────────────────
 *
 * HF stereo-width narrowing would make SBC's joint-stereo mid/side decision
 * far more stable and is a real win on paper, but it needs a sum/difference
 * matrix across L and R. DynamicsProcessing is strictly per-channel — EQ, MBC
 * and limiter, no cross-channel path — so it cannot be built on this engine.
 *
 * None of this makes SBC transparent. If the headphone supports AAC, aptX or
 * LDAC, selecting it beats everything in this file by a wide margin.
 */
object SbcEngine {

    /** SBC's subband count in the A2DP high-quality configuration. */
    const val SUBBAND_COUNT = 8

    /** Width of one SBC subband: fs / (2 * 8). */
    fun subbandWidthHz(sampleRateHz: Float): Float = sampleRateHz / (2f * SUBBAND_COUNT)

    /** Lower edge of subband [index] (0-based), in Hz. */
    fun subbandEdgeHz(index: Int, sampleRateHz: Float): Float =
        subbandWidthHz(sampleRateHz) * index

    /**
     * Where the HF crest-control band starts: the subband 3 / subband 4
     * boundary, which is exactly fs/4 (11025 Hz at 44.1 kHz, 12000 Hz at
     * 48 kHz). Subbands 4-7 are the ones that get starved and flicker, and
     * putting the boundary ON a subband edge rather than across one matters:
     * a band edge that straddles a subband raises BOTH subbands' scale
     * factors for one audible result, doubling the bit cost, and breaks the
     * PQMF's aliasing cancellation between the two (SBC's filterbank is only
     * near-perfect-reconstruction, so cancellation holds only while adjacent
     * subbands are quantised alike).
     *
     * Clamped because OEM HALs sometimes report a hi-res rate on a Bluetooth
     * route. SBC never runs above 48 kHz, and an MBC cutoff at or above the
     * 20 kHz closing band would break the array's required ascending order.
     */
    fun crestBandCutoffHz(sampleRateHz: Float): Float {
        val edge = if (sampleRateHz.isFinite() && sampleRateHz > 0f) sampleRateHz / 4f else 11025f
        return edge.coerceIn(9_000f, 16_000f)
    }

    /**
     * PreEQ conditioning curve, one entry per [EqBands] band.
     *
     * Read against the 44.1 kHz subband edges (2756 Hz apart):
     *
     *   idx 10  2000-3162 Hz   SB0/SB1    -0.4  trim only, this is where the
     *                                           bits are and we want them
     *   idx 11  3162-5020 Hz   SB1        -0.8  was -2.5; that deep a scoop is
     *                                           what read as "hollow and fake"
     *   idx 12  5020-7937 Hz   SB1/SB2    +0.5  cymbal body, still cheap
     *   idx 13  7937-12649 Hz  SB2-SB4    +2.0  audible air, affordable bits —
     *                                           this is where the perceived
     *                                           treble is actually bought back
     *   idx 14  12649-20000 Hz SB4-SB7    -6.5  the inversion: inaudible to
     *                                           most adults, hash and dither,
     *                                           and its scale factor was
     *                                           stealing the whole budget
     *
     * Everything from 25 Hz to 1.6 kHz is left alone on purpose. The old
     * curve's +1.2/+0.8 dB bass lift raised subband 0's scale factor, which
     * coarsens the quantiser across the busiest part of the spectrum — it
     * bought "body" with midrange resolution.
     *
     * Overridable over the content channel (RemoteTuning.sbcPreEmphasis),
     * already clamped to +/-10 dB by RemoteContent.parseTuning.
     */
    val conditioningCurveDb: FloatArray = floatArrayOf(
        0f,    // 25 Hz
        0f,    // 40 Hz
        0f,    // 63 Hz
        0f,    // 100 Hz
        0f,    // 160 Hz
        0f,    // 250 Hz
        0f,    // 400 Hz
        0f,    // 630 Hz
        0f,    // 1 kHz
        0f,    // 1.6 kHz
        -0.4f, // 2.5 kHz
        -0.8f, // 4 kHz
        0.5f,  // 6.3 kHz
        2.0f,  // 10 kHz
        -6.5f  // 16 kHz
    )

    // ── HF crest control (MBC) ────────────────────────────────────────────
    // Fast enough to catch an HF transient before it sets the frame's scale
    // factor, slow enough on release not to pump. The threshold is band-local
    // (DynamicsProcessing measures level per band), so -26 dBFS engages on
    // genuinely hot treble and leaves ordinary material alone.
    //
    // No makeup gain: pulling this region's peaks DOWN is the entire point.
    // Adding the level back would restore the scale factor we just lowered.
    const val CREST_ATTACK_MS = 1.5f
    const val CREST_RELEASE_MS = 60f
    const val CREST_RATIO = 3.2f
    const val CREST_THRESHOLD_DB = -26f
    const val CREST_KNEE_DB = 6f

    /**
     * Applied to HiRes's own air bands when SBC conditioning is also active.
     * HiRes boosts 9.6-14.5 kHz by +4 dB and 14.5-20 kHz by +5.5 dB, which is
     * the same bit-stealing move this engine exists to undo. Scaled rather
     * than zeroed — the user asked for air and should still get some — with
     * the top band cut harder because it buys the least and costs the most.
     */
    const val AIR_SCALE_SILK = 0.7f
    const val AIR_SCALE_TOP = 0.4f

    /**
     * Pre-encoder trim guarding against decoder overshoot. SBC reconstruction
     * is not peak-preserving; hot masters come out of the decoder above the
     * level that went in and clip at the receiver's DAC.
     */
    const val HEADROOM_TRIM_DB = -1.0f
}
