package com.jadoo.amp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.jadoo.amp.settings.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTest {

    @Test
    fun uiStyleKeysMapAndRoundTrip() {
        assertEquals(UiStyle.LiquidGlass, uiStyleFromKey("LiquidGlass"))
        assertEquals(UiStyle.Standard, uiStyleFromKey(null))
        assertEquals(UiStyle.Standard, uiStyleFromKey(""))
        assertEquals(UiStyle.Standard, uiStyleFromKey("   "))
        assertEquals(UiStyle.Standard, uiStyleFromKey("Frosted"))
        UiStyle.values().forEach { assertEquals(it, uiStyleFromKey(it.key())) }
    }

    @Test
    fun defaultStyleIsStandard() {
        assertEquals("Standard", ThemeSettings().uiStyle)
        assertEquals(UiStyle.Standard, ThemeSpec().style)
    }

    // ── Tier selection: every branch, including the ones the test phone can't run ──

    @Test
    fun belowApi31UsesScrim() {
        for (sdk in 28..30) assertEquals(GlassRendering.Scrim, resolveGlassRendering(sdk, false))
    }

    @Test
    fun api31And32UseBlur() {
        assertEquals(GlassRendering.Blur, resolveGlassRendering(31, false))
        assertEquals(GlassRendering.Blur, resolveGlassRendering(32, false))
    }

    @Test
    fun api33AndUpUseRefraction() {
        for (sdk in 33..36) assertEquals(GlassRendering.Refraction, resolveGlassRendering(sdk, false))
    }

    @Test
    fun highContrastTextForcesOpaqueOnEveryApi() {
        for (sdk in 28..36) assertEquals(GlassRendering.Opaque, resolveGlassRendering(sdk, true))
    }

    @Test
    fun onlyBlurTiersSampleTheBackdrop() {
        assertTrue(GlassRendering.Refraction.samplesBackdrop())
        assertTrue(GlassRendering.Blur.samplesBackdrop())
        assertFalse(GlassRendering.Scrim.samplesBackdrop())
        assertFalse(GlassRendering.Opaque.samplesBackdrop())
    }

    @Test
    fun baseAlphaInRangeOpaqueHighestAndThickerOverMoreContent() {
        for (dark in listOf(true, false)) for (r in GlassRendering.values()) {
            for (l in GlassLevel.values()) {
                val a = glassBaseAlpha(r, l, dark)
                assertTrue("alpha $a", a in 0f..1f)
                assertTrue(a <= glassBaseAlpha(GlassRendering.Opaque, l, dark))
            }
            assertTrue(glassBaseAlpha(r, GlassLevel.Floating, dark) >= glassBaseAlpha(r, GlassLevel.Card, dark))
            assertTrue(glassBaseAlpha(r, GlassLevel.Overlay, dark) >= glassBaseAlpha(r, GlassLevel.Floating, dark))
        }
    }

    @Test
    fun noBlurMeansThickerFill() {
        for (dark in listOf(true, false)) for (l in GlassLevel.values()) {
            assertTrue(glassBaseAlpha(GlassRendering.Scrim, l, dark) >= glassBaseAlpha(GlassRendering.Blur, l, dark))
        }
    }

    @Test
    fun dialogDimIsLighterWhenTheWindowBehindIsBlurred() {
        assertTrue(glassDialogDim(true) < glassDialogDim(false))
    }

    // ── Contrast maths ──

    @Test
    fun blackOnWhiteIs21() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.05f)
    }

    @Test
    fun minAlphaReachesTargetAndIsTight() {
        val surface = Color(0xFF211F26); val text = Color(0xFFE6E1E5); val behind = Color.White
        val a = minAlphaForContrast(surface, text, behind)
        assertTrue(a in 0f..1f)
        assertTrue(contrastRatio(text, glassComposite(surface, a, behind)) >= 4.5f)
        assertTrue(contrastRatio(text, glassComposite(surface, (a - 0.01f).coerceAtLeast(0f), behind)) < 4.5f || a == 0f)
    }

    @Test
    fun minAlphaIsOneWhenEvenOpaqueFails() {
        assertEquals(1f, minAlphaForContrast(Color.Gray, Color.Gray, Color.White))
    }

    // ── WCAG AA on every glass surface, every scheme, tier and level ──

    private fun schemes(): List<Pair<String, Pair<ColorScheme, Boolean>>> = listOf(
        "dark" to (BrandScheme.Dark to true),
        "light" to (BrandScheme.Light to false),
        "amoled" to (BrandScheme.Dark.toAmoled() to true),
        "dark tinted" to (BrandScheme.Dark.withBackdrop(Backdrop.Tinted, true) to true),
        "light tinted" to (BrandScheme.Light.withBackdrop(Backdrop.Tinted, false) to false),
        "dark dim" to (BrandScheme.Dark.withBackdrop(Backdrop.Dim, true) to true),
        "light dim" to (BrandScheme.Light.withBackdrop(Backdrop.Dim, false) to false)
    )

    /** Every tinted surface the app draws as glass, with the text colours that sit on it. */
    private fun surfaces(s: ColorScheme): List<Pair<Color, List<Color>>> = listOf(
        s.surfaceContainer to listOf(s.onSurface, s.onSurfaceVariant),
        s.surfaceContainerHigh to listOf(s.onSurface, s.onSurfaceVariant),
        s.surfaceContainerLow to listOf(s.onSurface, s.onSurfaceVariant),
        s.surfaceContainerHighest to listOf(s.onSurface, s.onSurfaceVariant),
        s.primaryContainer to listOf(s.onPrimaryContainer),
        s.secondaryContainer to listOf(s.onSecondaryContainer),
        s.errorContainer to listOf(s.onErrorContainer)
    )

    @Test
    fun bodyTextOnEveryGlassSurfaceMeetsAaOverItsWorstBackdrop() {
        val failures = mutableListOf<String>()
        for ((name, pair) in schemes()) {
            val (scheme, dark) = pair
            for (rendering in GlassRendering.values()) for (level in GlassLevel.values()) {
                for ((tint, texts) in surfaces(scheme)) {
                    val alpha = glassFillAlpha(scheme, dark, level, rendering, tint, texts)
                    for (behind in glassBackdropCandidates(scheme, dark, level, rendering)) for (text in texts) {
                        val c = contrastRatio(text, glassComposite(tint, alpha, behind))
                        if (c < 4.5f) failures += "$name $rendering $level tint=$tint text=$text alpha=$alpha c=$c"
                    }
                }
            }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun cardsStayClearlyTranslucentWhereBlurIsAvailable() {
        // The point of the material: an in-flow card must not end up a flat,
        // near-opaque slab just to pass contrast.
        for ((name, pair) in schemes()) {
            val (s, dark) = pair
            val a = glassFillAlpha(s, dark, GlassLevel.Card, GlassRendering.Refraction, s.surfaceContainer,
                                   listOf(s.onSurface, s.onSurfaceVariant))
            assertTrue("$name card alpha $a", a <= 0.75f)
        }
    }

    @Test
    fun worstBackdropContainsTheStackedOrbs() {
        val s = BrandScheme.Dark
        val stacked = glassWorstBackdrop(s.background, s.primary, s.tertiary, true)
        assertTrue(glassBackdropExtremes(s, true).contains(stacked))
    }

    @Test
    fun floatingAndOverlayConsiderContentColours() {
        val s = BrandScheme.Light
        val card = glassBackdropCandidates(s, false, GlassLevel.Card, GlassRendering.Refraction)
        val floating = glassBackdropCandidates(s, false, GlassLevel.Floating, GlassRendering.Refraction)
        val overlay = glassBackdropCandidates(s, false, GlassLevel.Overlay, GlassRendering.Refraction)
        assertTrue(floating.containsAll(card))
        assertTrue(floating.contains(s.onSurface))
        assertTrue(overlay.size > floating.size)
    }

    // ── Accessibility resolver (the one source of the glass mode) ──

    @Test
    fun reducedMotionForcesOpaqueOnEveryApi() {
        for (sdk in 28..36) {
            assertEquals(GlassRendering.Opaque, resolveGlassRendering(sdk, false, transitionAnimationScale = 0f))
            assertEquals(GlassRendering.Opaque, resolveGlassRendering(sdk, true, transitionAnimationScale = 0f))
        }
    }

    @Test
    fun nonZeroAnimationScaleKeepsTheApiTier() {
        for (scale in listOf(0.5f, 1f, 10f)) {
            assertEquals(GlassRendering.Refraction, resolveGlassRendering(33, false, scale))
            assertEquals(GlassRendering.Blur, resolveGlassRendering(31, false, scale))
            assertEquals(GlassRendering.Scrim, resolveGlassRendering(30, false, scale))
        }
    }

    @Test
    fun onlyOpaqueSuppressesEffects() {
        assertFalse(GlassRendering.Opaque.drawsEffects())
        assertFalse(GlassRendering.Opaque.samplesBackdrop())
        for (r in GlassRendering.values()) if (r != GlassRendering.Opaque) assertTrue(r.drawsEffects())
    }

    // ── Contrast tint under "Graphic EQ" labels ──

    @Test
    fun graphicEqLabelTintReachesAaOverWorstBackdrop() {
        val failures = mutableListOf<String>()
        for ((name, pair) in schemes()) {
            val (s, dark) = pair
            val texts = listOf(s.onSurface, s.onSurfaceVariant)
            val a = glassLabelTintAlpha(s, dark, s.surfaceContainer, texts)
            assertTrue("$name alpha $a", a in 0f..1f)
            for (behind in glassBackdropCandidates(s, dark, GlassLevel.Floating, GlassRendering.Refraction))
                for (t in texts) {
                    val cr = contrastRatio(t, glassComposite(s.surfaceContainer, a, behind))
                    if (cr < 4.5f) failures += "$name text=$t behind=$behind a=$a c=$cr"
                }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun graphicEqLabelTintCoversLightAndDark() {
        val darks: Set<Boolean> = schemes().map { it.second.second }.toSet()
        assertTrue(true in darks && false in darks)
    }
}
