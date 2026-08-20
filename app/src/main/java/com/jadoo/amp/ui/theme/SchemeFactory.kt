package com.jadoo.amp.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
    val seed: Color = BrandPalette.Gold
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

    return if (dark && spec.amoled) base.toAmoled() else base
}

/** True when [spec] resolves to a dark scheme against [systemDark]. */
fun ThemeSpec.isDark(systemDark: Boolean): Boolean = when (tone) {
    ToneMode.System -> systemDark
    ToneMode.Light -> false
    ToneMode.Dark -> true
}

/**
 * Generates a full scheme from a seed.
 *
 * Every role is derived from one of four tonal ramps built off the seed's own
 * hue — including the neutrals. That last part is the point: the old generator
 * hardcoded green-tinted neutral literals inside this branch, so a blue seed
 * produced a blue accent sitting on a faintly green background. There is now no
 * literal here that could be wrong.
 *
 * The tone numbers match Material's conventional role assignments, so the
 * result lands where a designer expects even though the colour space differs.
 */
private fun seedScheme(seed: Color, dark: Boolean): ColorScheme {
    val accent = TonalPalette.accent(seed)
    val secondary = TonalPalette.accentRotated(seed, 20f, 0.42f)
    val tertiary = TonalPalette.accentRotated(seed, -40f, 0.52f)
    val neutral = TonalPalette.neutral(seed)
    val neutralVariant = TonalPalette.neutralVariant(seed)

    return if (dark) {
        darkColorScheme(
            primary = accent.tone(80),
            onPrimary = accent.tone(20),
            primaryContainer = accent.tone(30),
            onPrimaryContainer = accent.tone(90),
            inversePrimary = accent.tone(40),

            secondary = secondary.tone(80),
            onSecondary = secondary.tone(20),
            secondaryContainer = secondary.tone(30),
            onSecondaryContainer = secondary.tone(90),

            tertiary = tertiary.tone(80),
            onTertiary = tertiary.tone(20),
            tertiaryContainer = tertiary.tone(30),
            onTertiaryContainer = tertiary.tone(90),

            background = neutral.tone(6),
            onBackground = neutral.tone(90),
            surface = neutral.tone(6),
            onSurface = neutral.tone(90),
            surfaceVariant = neutralVariant.tone(30),
            onSurfaceVariant = neutralVariant.tone(80),

            surfaceContainerLowest = neutral.tone(4),
            surfaceContainerLow = neutral.tone(10),
            surfaceContainer = neutral.tone(12),
            surfaceContainerHigh = neutral.tone(17),
            surfaceContainerHighest = neutral.tone(22),

            outline = neutralVariant.tone(60),
            outlineVariant = neutralVariant.tone(30),
            surfaceTint = accent.tone(80)
        )
    } else {
        lightColorScheme(
            primary = accent.tone(40),
            onPrimary = accent.tone(100),
            primaryContainer = accent.tone(90),
            onPrimaryContainer = accent.tone(10),
            inversePrimary = accent.tone(80),

            secondary = secondary.tone(40),
            onSecondary = secondary.tone(100),
            secondaryContainer = secondary.tone(90),
            onSecondaryContainer = secondary.tone(10),

            tertiary = tertiary.tone(40),
            onTertiary = tertiary.tone(100),
            tertiaryContainer = tertiary.tone(90),
            onTertiaryContainer = tertiary.tone(10),

            background = neutral.tone(98),
            onBackground = neutral.tone(10),
            surface = neutral.tone(98),
            onSurface = neutral.tone(10),
            surfaceVariant = neutralVariant.tone(90),
            onSurfaceVariant = neutralVariant.tone(30),

            surfaceContainerLowest = neutral.tone(100),
            surfaceContainerLow = neutral.tone(96),
            surfaceContainer = neutral.tone(94),
            surfaceContainerHigh = neutral.tone(92),
            surfaceContainerHighest = neutral.tone(90),

            outline = neutralVariant.tone(50),
            outlineVariant = neutralVariant.tone(80),
            surfaceTint = accent.tone(40)
        )
    }
}
