package com.jadoo.amp.audio

/**
 * Physical output device type. Scales how much bass/treble boost every
 * device-aware feature (Analog Bass, DBFB, Mobile Bass, HiRes Upscaler,
 * Harmonic Exciter) is allowed to request, so the DSP never asks a driver for
 * more than it can actually reproduce.
 *
 * The two axes that matter are what a driver can physically move, not its
 * price tag directly: diaphragm size/type and enclosure/seal. Magnet material
 * (ferrite vs neodymium) is deliberately NOT modeled here, it affects motor
 * efficiency and weight, not frequency response. Surround-sound channel
 * counts (2.1/5.1/7.1) are also not modeled: this effect chain processes the
 * app's stereo session before it ever reaches a receiver's channel splitter,
 * so it has no visibility into downstream LFE/surround channels, a "7.1"
 * category here would be a label with no real effect behind it.
 *
 * Each type carries a bass and treble RANGE (worst realistic unit at that
 * type to best realistic unit), picked from measured driver behavior in the
 * literature:
 *  - Olive & Welti, "The Relationship Between Perception and Measurement of
 *    Headphone Sound Quality" (AES, 2012) and their follow-up in-ear target
 *    curve work: sealed in-ears get MORE low-end reinforcement from the ear
 *    canal seal than open/on-ear designs, and budget single-dynamic-driver
 *    IEMs are usually treble-limited while multi-driver (BA/planar tweeter)
 *    designs extend cleanly to 20kHz.
 *  - Toole, "Sound Reproduction: Loudspeakers, Rooms, and the Listening
 *    Experience", Ch. 4 & 13: small-cabinet loudspeakers are bass-extension-
 *    limited by enclosure volume regardless of price; real low-end extension
 *    needs cabinet volume a "compact" speaker doesn't have.
 *  - Eargle, "Loudspeaker Handbook": driver diaphragm size and enclosure
 *    design, not motor magnet material, set the achievable frequency range.
 *
 * A per-type "quality" slider (0=budget, 1=flagship) picks where inside that
 * range a specific unit sits, rather than needing one named tier per price
 * point.
 */
enum class DeviceType(
    val displayName: String,
    val bassRange: ClosedFloatingPointRange<Float>,
    val trebleRange: ClosedFloatingPointRange<Float>
) {
    // No device-specific scaling: every feature behaves exactly as it did
    // before this system existed. The escape hatch for anyone who doesn't
    // want device-aware tuning at all.
    General("General", 1f..1f, 1f..1f),

    // Sealed in-ear: ear-canal seal reinforces bass even on budget dynamic
    // drivers (Olive & Welti); treble varies hugely, single-driver budget
    // units roll off early, multi-driver/planar flagships extend cleanly.
    Iem("In-Ear Monitor", 0.6f..0.95f, 0.35f..0.9f),

    // Small pad, light seal, modest driver, the least forgiving type on both axes.
    OnEar("On-Ear Headphones", 0.4f..0.7f, 0.4f..0.75f),

    // Larger driver and better seal (closed) or genuine open-back extension (flagship planar).
    OverEar("Over-Ear Headphones", 0.5f..0.9f, 0.45f..0.9f),

    // No cabinet volume to speak of; bass is the weak axis by physics, not price.
    CompactSpeaker("Compact Speaker", 0.2f..0.55f, 0.5f..0.8f),

    // Real cabinet volume, often ported, benefits from Toole's boundary/room bass reinforcement.
    HomeSpeaker("Home Speaker", 0.6f..1f, 0.6f..0.95f)
}

private fun ClosedFloatingPointRange<Float>.lerp(t: Float): Float =
    start + (endInclusive - start) * t.coerceIn(0f, 1f)

/** Where a specific unit of [this] type sits, given a 0=budget..1=flagship quality tier. */
fun DeviceType.bassExtension(qualityTier: Float): Float = bassRange.lerp(qualityTier)
fun DeviceType.trebleExtension(qualityTier: Float): Float = trebleRange.lerp(qualityTier)

/**
 * How much of the user's Crossfeed strength slider to actually apply, by
 * headphone type. Crossfeed only ever runs on Iem/OnEar/OverEar/General (see
 * JadooDspService.isHeadphoneRoute) — CompactSpeaker/HomeSpeaker never reach
 * this, they're gated out before it's read.
 *
 * The three real headphone types differ in how much of a problem Crossfeed is
 * actually solving:
 *  - **IEM**: a sealed ear-canal insert gives near-total isolation between
 *    channels — the hardest-panned, most "inside your head" presentation of
 *    any type, and the one Crossfeed helps most. Full strength.
 *  - **On-ear**: light seal, some but not much natural leakage. Slightly
 *    reduced, on the same reasoning as over-ear, just less of it.
 *  - **Over-ear**: often a looser seal, and many flagship designs are
 *    deliberately open-back, which lets a little of each channel reach the
 *    opposite ear acoustically already. Applying full Crossfeed on top of
 *    that natural leakage is what "flat/veiled" over-ear Crossfeed complaints
 *    trace back to — the fix is asking for less, not turning it off.
 *  - **General**: no device-specific information, so no scaling — exactly
 *    what the slider says.
 */
fun DeviceType.crossfeedFactor(): Float = when (this) {
    DeviceType.Iem -> 1.0f
    DeviceType.OnEar -> 0.85f
    DeviceType.OverEar -> 0.7f
    else -> 1.0f
}

/**
 * Average tonal correction for a device class, in dB per [EqBands] band.
 *
 * Until now DeviceType had no sound of its own: it only scaled OTHER
 * features' boosts down (see bassExtension/trebleExtension), so with no bass
 * or treble feature enabled, choosing your actual device changed nothing at
 * all — and when it did do something, it only ever made things quieter than
 * leaving it on General. That is a restraint mechanism, not a device profile.
 *
 * These curves are the missing half: a correction toward neutral for what the
 * AVERAGE device of each class measurably does wrong. Summed into the PreEQ
 * alongside every other tonal shape, so it applies on its own, independent of
 * which features are on.
 *
 * What each curve corrects, and why:
 *
 *  - **IEM** — the canal seal already delivers low bass, so it needs no sub
 *    lift beyond a touch of extension; what budget single-dynamic units share
 *    is a mid-bass bloat around 160-250 Hz, the canal resonance near 2.5-4 kHz,
 *    a glare peak around 6.3 kHz, and an early top-octave rolloff.
 *  - **On-Ear** — the weakest seal of any type, so bass leaks out; the
 *    classic complaint is thin lows plus a 2.5 kHz honk from the small pad.
 *  - **Over-Ear** — closest to neutral already. Open-back designs give up
 *    sub-bass for their soundstage, which is the main thing worth restoring.
 *  - **Compact Speaker** — no sub-bass to ask for at all (see bassRange), a
 *    boxy 250-400 Hz cabinet signature, and it benefits from a little
 *    presence for intelligibility at low level.
 *  - **Home Speaker** — real cabinet plus room boundary reinforcement piles
 *    up around 63-160 Hz (Toole ch. 13); trimming that is what actually
 *    cleans up a room-placed speaker.
 *  - **General** — flat, by definition. The escape hatch for anyone who
 *    wants no device-aware behaviour of any kind stays exactly that.
 *
 * Deliberately modest — nothing here exceeds ±1.8 dB. This is a correction
 * for a class average, not a measured profile of one specific model (that is
 * what the headphone tuning in the content channel is for), and the spread
 * inside every class is wide enough that a bolder curve would be wrong more
 * often than right.
 *
 * [qualityTier] tapers the correction: a flagship unit is closer to neutral
 * out of the box AND more likely to be deliberately voiced, so it gets 60% of
 * the budget unit's correction rather than the full amount.
 */
fun DeviceType.correctionDb(bandIndex: Int, qualityTier: Float): Float {
    val curve = when (this) {
        DeviceType.General -> return 0f
        //                 25    40    63   100   160   250   400   630    1k  1.6k  2.5k    4k  6.3k   10k   16k
        DeviceType.Iem -> floatArrayOf(
            1.0f, 0.8f, 0.3f, 0.0f, -0.5f, -0.5f, -0.3f, 0.0f, 0.0f, 0.0f, -0.5f, -0.8f, -1.0f, 0.5f, 1.0f
        )
        DeviceType.OnEar -> floatArrayOf(
            1.5f, 1.8f, 1.5f, 1.0f, 0.5f, 0.0f, -0.5f, -0.5f, 0.0f, -0.5f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f
        )
        DeviceType.OverEar -> floatArrayOf(
            1.2f, 1.0f, 0.6f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.5f, -0.5f, -0.3f, 0.3f, 0.8f
        )
        DeviceType.CompactSpeaker -> floatArrayOf(
            0.0f, 0.0f, 0.5f, 1.0f, 0.5f, -1.0f, -1.5f, -0.5f, 0.0f, 0.5f, 0.5f, 0.0f, -0.5f, 0.0f, 0.0f
        )
        DeviceType.HomeSpeaker -> floatArrayOf(
            0.0f, -0.3f, -0.8f, -1.0f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.3f, 0.5f
        )
    }
    val raw = curve.getOrElse(bandIndex) { 0f }
    return raw * (1f - 0.4f * qualityTier.coerceIn(0f, 1f))
}
