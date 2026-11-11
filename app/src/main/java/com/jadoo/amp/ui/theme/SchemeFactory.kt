package com.jadoo.amp.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette as MkTonalPalette

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color

/** Where the app's colours come from. */
enum class ThemeMode {
    /** The hand-authored JadOO gold scheme. */
    Brand,

    /** The platform's wallpaper-derived scheme (API 31+). */
    MaterialYou,

    /** A scheme generated from a user-chosen seed colour. */
    CustomSeed
}

/**
 * What sits behind the content in dark mode.
 *
 * The default dark background (Material tone 6, ≈#0F1113) read as "too dark"
 * on a dark-room OLED: the grouped lists at tone 12 barely separated from it.
 * Dim and Tinted lift the whole surface ramp ~5 tones so lists separate
 * clearly while text contrast stays far above 4.5:1 (onSurface is tone 90).
 */
enum class Backdrop {
    /** True black for OLED — the old Pure Black switch. */
    Black,
    /** Lifted neutral charcoal, background tone 11. */
    Dim,
    /** Dim, but the neutrals carry the accent's hue (chroma 14). */
    Tinted,
    /** Dim plus a slow accent glow drawn behind the main screens. */
    Aurora
}

/** Light/dark selection. */
enum class ToneMode {
    /** Follow the system setting. The default, and hidden until expanded. */
    System,
    Light,
    Dark
}

/**
 * A complete description of what the app should look like.
 *
 * [amoled] only has meaning when the effective tone is dark; the factory and
 * the Appearance UI both gate on that, so a user who sets Pure Black and then
 * switches to light mode gets light mode, not black.
 */
data class ThemeSpec(
    val mode: ThemeMode = ThemeMode.Brand,
    val tone: ToneMode = ToneMode.System,
    val amoled: Boolean = false,
    val seed: Color = BrandPalette.Gold,
    val backdrop: Backdrop = Backdrop.Dim,
    val style: UiStyle = UiStyle.Standard
)

/**
 * Builds the [ColorScheme] for a [ThemeSpec].
 *
 * [systemDark] is the resolved system setting, passed in rather than read here
 * so this stays a pure function and can be unit-tested and previewed.
 *
 * One behaviour change worth a release note: on API 28–30, "Material You" has
 * no platform dynamic colour to fall back on. It previously fell back to a
 * static green scheme that matched nothing else in the app; it now falls back
 * to Brand, which is at least the app's own identity.
 */
fun buildColorScheme(
    spec: ThemeSpec,
    systemDark: Boolean,
    context: Context
): ColorScheme {
    val dark = when (spec.tone) {
        ToneMode.System -> systemDark
        ToneMode.Light -> false
        ToneMode.Dark -> true
    }

    val base = when (spec.mode) {
        ThemeMode.Brand ->
            if (dark) BrandScheme.Dark else BrandScheme.Light

        ThemeMode.MaterialYou ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (dark) BrandScheme.Dark else BrandScheme.Light
            }

        ThemeMode.CustomSeed -> seedScheme(spec.seed, dark)
    }

    return base.withBackdrop(spec.backdrop, dark)
}

/**
 * Re-seats the surface ramp for [backdrop], keeping every accent role as the
 * mode produced it. The neutrals are rebuilt from a MaterialKolor tonal
 * palette on the accent's hue, so Dim stays a quiet near-neutral and Tinted
 * visibly carries the accent — both at the same tones, same contrast.
 */
fun ColorScheme.withBackdrop(backdrop: Backdrop, dark: Boolean): ColorScheme {
    if (backdrop == Backdrop.Black) return if (dark) toAmoled() else this
    val hue = Hct.fromInt(primary.toArgb()).hue
    val baseChroma = Hct.fromInt(surfaceContainer.toArgb()).chroma
    val chroma = if (backdrop == Backdrop.Tinted) 14.0 else baseChroma.coerceIn(2.0, 6.0)
    val n = MkTonalPalette.fromHueAndChroma(hue, chroma)
    fun t(tone: Int) = Color(n.tone(tone))
    return if (dark) copy(
        background = t(11),
        surface = t(11),
        surfaceDim = t(9),
        surfaceBright = t(28),
        surfaceContainerLowest = t(8),
        surfaceContainerLow = t(14),
        surfaceContainer = t(17),
        surfaceContainerHigh = t(21),
        surfaceContainerHighest = t(25),
        onBackground = t(92),
        onSurface = t(92)
    ) else if (backdrop == Backdrop.Tinted) copy(
        background = t(96),
        surface = t(96),
        surfaceContainerLowest = t(99),
        surfaceContainerLow = t(94),
        surfaceContainer = t(92),
        surfaceContainerHigh = t(90),
        surfaceContainerHighest = t(87)
    ) else this
}

/** True when [spec] resolves to a dark scheme against [systemDark]. */
fun ThemeSpec.isDark(systemDark: Boolean): Boolean = when (tone) {
    ToneMode.System -> systemDark
    ToneMode.Light -> false
    ToneMode.Dark -> true
}

/**
 * Generates a full scheme from a seed with MaterialKolor — Google's own
 * material-color-utilities (HCT colour space, Tonal Spot style), so custom
 * seeds land exactly where Material You would put them, contrast guarantees
 * included.
 */
private fun seedScheme(seed: Color, dark: Boolean): ColorScheme =
    dynamicColorScheme(seedColor = seed, isDark = dark, style = PaletteStyle.TonalSpot)
