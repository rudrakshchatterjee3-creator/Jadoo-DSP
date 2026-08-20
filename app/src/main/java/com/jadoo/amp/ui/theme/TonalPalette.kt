package com.jadoo.amp.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A minimal CIELAB / LCh colour engine, used to generate tonal palettes from a
 * seed colour.
 *
 * ## Why this is hand-rolled
 *
 * The obvious alternative is Google's Material Color Utilities, which
 * implements HCT and is unquestionably better at this. It is not used, for two
 * reasons that both come down to cost:
 *
 *  - The Android artifact lives inside `com.google.android.material`, which
 *    drags in AppCompat and the entire Views widget set — roughly a megabyte
 *    into an app that is pure Compose and has no Views in it — and forces the
 *    XML theme to be re-parented off raw framework `Theme.Material`.
 *  - The third-party Kotlin Multiplatform port avoids that but adds a new
 *    supply-chain dependency, in an app that already removed Shizuku on exactly
 *    that principle.
 *
 * A hundred and sixty lines of LCh is the cheaper side of that trade.
 *
 * ## Known limitation, stated rather than hidden
 *
 * LCh hue is less perceptually uniform than HCT's, most visibly in the blues:
 * a blue seed drifts slightly toward purple as tone rises, because CIELAB's
 * blue axis bends. This is acceptable here and nowhere near a reason to take
 * the dependency — the seed is a user-chosen accent rather than a brand
 * commitment, the brand scheme itself is hand-authored (see `BrandScheme`),
 * and Material You mode uses the platform's own dynamic scheme, which is real
 * HCT. The imprecision only ever affects the custom-seed path.
 */

// ── sRGB ⇄ linear ─────────────────────────────────────────────────────────

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float): Float =
    if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

// D65 reference white.
private const val XN = 0.95047f
private const val YN = 1.00000f
private const val ZN = 1.08883f

private fun labF(t: Float): Float =
    if (t > 0.008856f) cbrt(t.toDouble()).toFloat() else (7.787f * t) + (16f / 116f)

private fun labFInv(t: Float): Float {
    val t3 = t * t * t
    return if (t3 > 0.008856f) t3 else (t - 16f / 116f) / 7.787f
}

/** Perceptual lightness (L*), chroma (C*) and hue angle (h°) of [this]. */
fun Color.toLch(): Triple<Float, Float, Float> {
    val r = srgbToLinear(red)
    val g = srgbToLinear(green)
    val b = srgbToLinear(blue)

    val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / XN
    val y = (0.2126f * r + 0.7152f * g + 0.0722f * b) / YN
    val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / ZN

    val fx = labF(x)
    val fy = labF(y)
    val fz = labF(z)

    val l = 116f * fy - 16f
    val aStar = 500f * (fx - fy)
    val bStar = 200f * (fy - fz)

    val chroma = kotlin.math.sqrt(aStar * aStar + bStar * bStar)
    var hue = Math.toDegrees(atan2(bStar.toDouble(), aStar.toDouble())).toFloat()
    if (hue < 0f) hue += 360f

    return Triple(l, chroma, hue)
}

/**
 * Builds an sRGB colour from LCh.
 *
 * Out-of-gamut requests are resolved by reducing chroma until the colour fits,
 * rather than by clipping the channels. Clipping shifts hue — a deep saturated
 * red clipped channel-wise comes back orange — whereas chroma reduction keeps
 * the hue and lightness the caller actually asked for and gives up only the
 * saturation that could never have been displayed anyway.
 */
fun lchToColor(lightness: Float, chroma: Float, hue: Float): Color {
    var c = chroma.coerceAtLeast(0f)
    val l = lightness.coerceIn(0f, 100f)
    val hRad = Math.toRadians(hue.toDouble())

    repeat(48) {
        val aStar = (c * cos(hRad)).toFloat()
        val bStar = (c * sin(hRad)).toFloat()

        val fy = (l + 16f) / 116f
        val fx = fy + aStar / 500f
        val fz = fy - bStar / 200f

        val x = labFInv(fx) * XN
        val y = labFInv(fy) * YN
        val z = labFInv(fz) * ZN

        val rLin = 3.2406f * x - 1.5372f * y - 0.4986f * z
        val gLin = -0.9689f * x + 1.8758f * y + 0.0415f * z
        val bLin = 0.0557f * x - 0.2040f * y + 1.0570f * z

        val inGamut = rLin >= -0.001f && rLin <= 1.001f &&
            gLin >= -0.001f && gLin <= 1.001f &&
            bLin >= -0.001f && bLin <= 1.001f

        if (inGamut || c <= 0f) {
            return Color(
                linearToSrgb(rLin.coerceIn(0f, 1f)),
                linearToSrgb(gLin.coerceIn(0f, 1f)),
                linearToSrgb(bLin.coerceIn(0f, 1f))
            )
        }
        c -= chroma / 48f
    }
    // Chroma exhausted: the achromatic colour at this lightness always exists.
    val y = labFInv((l + 16f) / 116f)
    val v = linearToSrgb(y.coerceIn(0f, 1f))
    return Color(v, v, v)
}

/**
 * A fixed-hue, fixed-chroma ramp addressed by tone, where tone is L* on the
 * 0..100 scale. Material's tonal palettes are the same idea; the numbers used
 * for each role below match Material's conventional tone assignments so the
 * generated schemes land where a designer would expect.
 */
class TonalPalette(private val hue: Float, private val chroma: Float) {
    fun tone(t: Int): Color = lchToColor(t.toFloat(), chroma, hue)

    companion object {
        /** The accent ramp: the seed's own hue and chroma. */
        fun accent(seed: Color): TonalPalette {
            val (_, c, h) = seed.toLch()
            // Floor the chroma so a near-grey seed still produces a palette
            // with some identity rather than five shades of the same grey.
            return TonalPalette(h, c.coerceIn(16f, 100f))
        }

        /** A hue-rotated companion ramp, for secondary/tertiary roles. */
        fun accentRotated(seed: Color, degrees: Float, chromaScale: Float): TonalPalette {
            val (_, c, h) = seed.toLch()
            return TonalPalette(
                (h + degrees + 360f) % 360f,
                (c * chromaScale).coerceIn(8f, 100f)
            )
        }

        /**
         * Near-grey ramps carrying a trace of the seed's hue.
         *
         * This is the part that structurally kills the old green cast. The
         * previous scheme generator hardcoded green-tinted neutrals inside the
         * custom-seed branch — `Color(0xFFE8EDE5)`, `Color(0xFFC5CBC0)` and
         * friends — so picking a blue accent still produced a faintly green
         * app. Deriving the neutrals from the seed's OWN hue means there is no
         * literal left that could be wrong.
         */
        fun neutral(seed: Color): TonalPalette {
            val (_, _, h) = seed.toLch()
            return TonalPalette(h, 4f)
        }

        fun neutralVariant(seed: Color): TonalPalette {
            val (_, _, h) = seed.toLch()
            return TonalPalette(h, 8f)
        }
    }
}
