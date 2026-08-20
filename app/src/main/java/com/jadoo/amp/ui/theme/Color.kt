package com.jadoo.amp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The JadOO brand palette, sampled directly from `JadOO Base logo 1.png` and
 * `JadOO Base logo 2.png`. Both files agree exactly.
 *
 * Three colours. That is the whole brand.
 */
object BrandPalette {
    /** The mark's gold. L*≈73, hue≈78°. */
    val Gold = Color(0xFFE1A730)

    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)

    /**
     * Gold is unusable as a light-mode `primary`.
     *
     * At L*≈73 it contrasts 1.9:1 against white — nowhere near the 4.5:1 that
     * text needs, and not even the 3:1 a large control needs. Light mode
     * therefore uses this darkened amber-bronze for `primary` and reserves the
     * true brand gold for `primaryContainer` and accent fills, where it sits
     * on its own dark-on-light pairing and reads correctly.
     *
     * Dark mode has no such problem and uses the real gold directly.
     */
    val GoldDeep = Color(0xFF7A5510)

    /**
     * The adaptive icon's background plate.
     *
     * Not pure black on purpose: against a dark launcher wallpaper a
     * `#000000` plate has no edge at all and the mark appears to float in a
     * void. A few points of lift keeps the silhouette legible without reading
     * as grey.
     */
    val IconPlate = Color(0xFF0B0B0B)
}

/**
 * The swatches offered in Appearance → Custom.
 *
 * Gold leads, because it is the brand. The rest are spaced roughly evenly
 * around the hue circle so the generated schemes are visibly different from
 * each other rather than five variations on blue.
 */
val SeedSwatches = listOf(
    BrandPalette.Gold,
    Color(0xFF4F8CFF), // blue
    Color(0xFF39B58A), // green
    Color(0xFFE0605B), // red
    Color(0xFFA97BE8), // violet
    Color(0xFF3FB6C8)  // cyan
)
