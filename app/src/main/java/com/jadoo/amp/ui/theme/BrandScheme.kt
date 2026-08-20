package com.jadoo.amp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The JadOO brand colour schemes.
 *
 * These are **hand-authored**, not generated. The generator in `TonalPalette`
 * was used to propose starting values, but every role below is a signed-off
 * number. That is what "brand" has to mean: a runtime derivation from a seed
 * would drift the moment the generator's maths changed, and a brand that moves
 * when you refactor a colour space isn't a brand.
 *
 * The custom-seed path is where generation belongs, and that is where it lives.
 */
object BrandScheme {

    /**
     * Dark is the primary expression. The mark is gold on black; this is the
     * scheme that actually looks like the logo.
     */
    val Dark: ColorScheme = darkColorScheme(
        primary = BrandPalette.Gold,
        onPrimary = Color(0xFF3D2A00),
        primaryContainer = Color(0xFF5A3D00),
        onPrimaryContainer = Color(0xFFFFDDA6),
        inversePrimary = BrandPalette.GoldDeep,

        // Secondary is the same gold pulled toward neutral — a warm sand.
        // Rotating hue here would introduce a second colour the brand doesn't
        // have; desaturating keeps the palette honest to a one-colour mark.
        secondary = Color(0xFFD8C3A0),
        onSecondary = Color(0xFF3A2F1B),
        secondaryContainer = Color(0xFF52462F),
        onSecondaryContainer = Color(0xFFF5DFBB),

        // Tertiary is the single permitted departure: a cool counterweight so
        // selected/active states can be distinguished from the gold accent
        // without both fighting for the same warm register.
        tertiary = Color(0xFFA6CFC4),
        onTertiary = Color(0xFF0B3730),
        tertiaryContainer = Color(0xFF264E46),
        onTertiaryContainer = Color(0xFFC2EBE0),

        background = Color(0xFF16130E),
        onBackground = Color(0xFFEAE1D5),
        surface = Color(0xFF16130E),
        onSurface = Color(0xFFEAE1D5),
        surfaceVariant = Color(0xFF4C4539),
        onSurfaceVariant = Color(0xFFCFC5B4),

        // The container ramp. These five steps are what give cards their
        // separation from the background; AMOLED mode compresses them but
        // must not flatten them (see toAmoled).
        surfaceContainerLowest = Color(0xFF100E09),
        surfaceContainerLow = Color(0xFF1E1A14),
        surfaceContainer = Color(0xFF221E17),
        surfaceContainerHigh = Color(0xFF2D2821),
        surfaceContainerHighest = Color(0xFF38332B),

        outline = Color(0xFF988F7F),
        outlineVariant = Color(0xFF4C4539),
        surfaceTint = BrandPalette.Gold,

        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6)
    )

    /**
     * Light mode. Note `primary` is [BrandPalette.GoldDeep], not the brand
     * gold — see that property for the contrast arithmetic. The true gold
     * appears as `primaryContainer` and `surfaceTint`, where it belongs.
     */
    val Light: ColorScheme = lightColorScheme(
        primary = BrandPalette.GoldDeep,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDDA6),
        onPrimaryContainer = Color(0xFF271900),
        inversePrimary = BrandPalette.Gold,

        secondary = Color(0xFF6B5D45),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF5E0C1),
        onSecondaryContainer = Color(0xFF241A08),

        tertiary = Color(0xFF3E665D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC0ECE0),
        onTertiaryContainer = Color(0xFF00201A),

        background = Color(0xFFFFF8EF),
        onBackground = Color(0xFF1E1B16),
        surface = Color(0xFFFFF8EF),
        onSurface = Color(0xFF1E1B16),
        surfaceVariant = Color(0xFFEDE1CF),
        onSurfaceVariant = Color(0xFF4D4639),

        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFCF2E7),
        surfaceContainer = Color(0xFFF7ECE0),
        surfaceContainerHigh = Color(0xFFF1E6DA),
        surfaceContainerHighest = Color(0xFFEBE0D5),

        outline = Color(0xFF7F7667),
        outlineVariant = Color(0xFFD0C5B4),
        surfaceTint = BrandPalette.GoldDeep,

        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002)
    )
}

/**
 * Pure-black (AMOLED) transform.
 *
 * This is a **post-transform applied to a finished scheme**, not a fourth
 * palette. That composes: it works identically on the brand scheme, a
 * custom-seed scheme and the platform's Material You scheme, and adding a
 * fifth theme mode later gets AMOLED support for free. A separate hand-authored
 * AMOLED palette would need maintaining three times over and would silently
 * not apply to Material You at all.
 *
 * Two rules make it safe:
 *
 * 1. **The container ramp survives.** Only `background` and `surface` go to
 *    true black; the five `surfaceContainer*` steps are re-seated on a
 *    compressed but still-monotonic ramp. Flattening everything to `#000000`
 *    — which is what most "AMOLED mode" implementations do — makes all 23
 *    `surfaceContainerHigh` cards in this app dissolve into an unnavigable
 *    void, and no amount of outline work brings them back.
 *
 * 2. **No `on*` role is touched.** Those are the guaranteed-contrast pairings.
 *    Material You computes them against the wallpaper and its accessibility
 *    guarantees ride on them; rewriting them here would break exactly the
 *    thing dynamic colour is trusted for.
 */
fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    // Nudged up to stay visible against true black — an outline tuned for a
    // #16130E background disappears entirely at #000000.
    outlineVariant = outlineVariant.copy(alpha = 1f).let {
        Color(
            red = (it.red + 0.06f).coerceAtMost(1f),
            green = (it.green + 0.06f).coerceAtMost(1f),
            blue = (it.blue + 0.06f).coerceAtMost(1f)
        )
    }
)
