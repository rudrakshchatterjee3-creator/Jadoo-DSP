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
