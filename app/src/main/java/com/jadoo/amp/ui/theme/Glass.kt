package com.jadoo.amp.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.material3.ColorScheme
import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * The app's overall visual style. Independent of colour mode, tone and backdrop.
 *
 * [LiquidGlass] is JadOO's take on Apple's Liquid Glass, built from Compose
 * GraphicsLayers (see `ui/components/LiquidGlass.kt`). Glass is the control
 * layer and cards; the rows inside cards stay plain:
 * - Each screen paints a backdrop of soft colour orbs into its own layer and,
 *   where content scrolls under a pinned bar, records that content into a
 *   second layer.
 * - Pinned bars and floating controls draw those layers back, offset to where
 *   they sit, through a blur (API 31+) and, on API 33+, an AGSL lens that bends
 *   what is behind their rim, splits colour slightly there and adds a specular
 *   edge.
 * - Dialogs and sheets are separate windows, so they can't sample the screen
 *   that way; on API 31+ they ask the window manager to blur what is behind
 *   them instead (when the device allows cross-window blur).
 * - Cards are lens glass over the backdrop (no blur); rows inside them stay plain.
 */
enum class UiStyle { Standard, LiquidGlass }

/** Persisted string ↔ [UiStyle]. Unknown/blank → Standard (the unchanged default). */
fun uiStyleFromKey(key: String?): UiStyle = when (key) {
    "LiquidGlass" -> UiStyle.LiquidGlass
    else -> UiStyle.Standard
}

fun UiStyle.key(): String = when (this) {
    UiStyle.Standard -> "Standard"
    UiStyle.LiquidGlass -> "LiquidGlass"
}

/**
 * How glass is rendered on this device with these accessibility settings.
 *
 * This is the ONE source of truth for the glass mode: [rememberGlassRendering]
 * reads the system, [resolveGlassRendering] decides, and the theme provides
 * the result as [LocalGlassRendering]. Every glass component reads that
 * local; none decides for itself.
 */
enum class GlassRendering {
    /** API 33+: blur plus the AGSL lens (edge refraction, colour split, specular rim). */
    Refraction,
    /** API 31–32: the content behind is blurred with a RenderEffect; no lens. */
    Blur,
    /** API < 31: no RenderEffect, so a thicker translucent fill over the backdrop. */
    Scrim,
    /**
     * Flat, near-opaque fill. No blur, no shader, no blurred shadow, no window
     * blur-behind, no press squeeze. Chosen for high-contrast text and for
     * reduced motion (see [resolveGlassRendering]).
     */
    Opaque
}

/**
 * The single glass-mode decision. Pure, so every branch is unit-tested even
 * where the phone can't run it.
 *
 * Opaque when either:
 * - [highContrastText]: Settings.Secure "high_text_contrast_enabled" = 1, or
 * - reduced motion: [transitionAnimationScale] == 0 (Settings.Global
 *   "transition_animation_scale"; "Remove animations" sets it to 0).
 *
 * Android has NO official "Reduce Transparency" setting (unlike iOS), so
 * there is no such signal to read and none is claimed. High-contrast text and
 * reduced motion are the nearest signals Android offers and stand in for it.
 */
fun resolveGlassRendering(
    sdkInt: Int,
    highContrastText: Boolean,
    transitionAnimationScale: Float = 1f
): GlassRendering = when {
    highContrastText -> GlassRendering.Opaque
    transitionAnimationScale == 0f -> GlassRendering.Opaque
    sdkInt >= 33 -> GlassRendering.Refraction
    sdkInt >= 31 -> GlassRendering.Blur
    else -> GlassRendering.Scrim
}

/** False in Opaque: then no blur, shader or blurred shadow is drawn at all. */
fun GlassRendering.drawsEffects(): Boolean = this != GlassRendering.Opaque

/** True when the tier samples what is behind the surface (and so needs a backdrop layer). */
fun GlassRendering.samplesBackdrop(): Boolean =
    this == GlassRendering.Refraction || this == GlassRendering.Blur

/** What a glass surface sits over; decides what it samples and how thick it must be. */
enum class GlassLevel {
    /** In-flow cards: lens glass over the backdrop, no blur. */
    Card,
    /** Pinned bars and floating controls: over the backdrop AND any content scrolling beneath. */
    Floating,
    /** Dialogs, sheets and menus: their own window, over whatever screen is behind. */
    Overlay,
    /** Glass inside glass (chips, tiles, buttons in a card): over another glass surface. */
    Nested
}

/**
 * The thinnest fill each tier/level is allowed to use, for the look. The fill
 * actually used is the larger of this and what WCAG AA needs ([glassFillAlpha]).
 */
fun glassBaseAlpha(rendering: GlassRendering, level: GlassLevel, dark: Boolean): Float {
    if (rendering == GlassRendering.Opaque) return 0.97f
    val light = if (dark) 0f else 0.04f
    return when (rendering) {
        GlassRendering.Scrim -> when (level) {
            GlassLevel.Card -> 0.76f
            GlassLevel.Floating -> 0.86f
            GlassLevel.Overlay -> 0.92f
            GlassLevel.Nested -> 0.40f
        }
        else -> when (level) {
            GlassLevel.Card -> 0.44f
            GlassLevel.Floating -> 0.56f
            GlassLevel.Overlay -> 0.66f
            GlassLevel.Nested -> 0.34f
        } + light
    }
}

/**
 * The fill a LIVE Refraction surface uses: thin, like the real material. Text
 * legibility there does not come from the fill; the lens shader adds an
 * adaptive tint per pixel ([GLASS_ADAPT]) where the blurred backdrop is
 * dark (light scheme) or bright (dark scheme), the way vibrancy does. Every
 * other tier keeps the WCAG-driven [glassFillAlpha].
 */
fun glassLiveAlpha(level: GlassLevel, dark: Boolean): Float = when (level) {
    GlassLevel.Card -> if (dark) 0.58f else 0.34f
    GlassLevel.Floating -> if (dark) 0.22f else 0.34f
    GlassLevel.Overlay -> if (dark) 0.42f else 0.56f
    GlassLevel.Nested -> if (dark) 0.14f else 0.22f
}

/** Extra tint (0..1) the lens may add per pixel where the backdrop fights the text. */
const val GLASS_ADAPT = 0.34f
/** Saturation multiplier applied to what shows through live glass (iOS-style vibrancy). */
const val GLASS_SATURATION = 1.45f

/** Accent orbs the Liquid Glass backdrop paints. Dark scheme; light uses [GLASS_LIGHT_STRENGTH]×. */
const val GLASS_GLOW_PRIMARY_ALPHA = 0.34f
const val GLASS_GLOW_TERTIARY_ALPHA = 0.26f
const val GLASS_GLOW_SECONDARY_ALPHA = 0.20f
const val GLASS_LIGHT_STRENGTH = 0.90f

/** Blur applied to what scrolls under a pinned glass bar (API 31+). */
const val GLASS_BLUR_RADIUS_DP = 12f
/** Overlays get a heavier window blur-behind (API 31+, when the device allows it). */
const val GLASS_WINDOW_BLUR_DP = 48f

/** Window dim behind a glass dialog: lighter when the window behind is blurred. */
fun glassDialogDim(windowBlur: Boolean): Float = if (windowBlur) 0.55f else 0.60f

/** WCAG 2.x contrast ratio between two opaque colours. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** The colour a glass surface actually shows over [behind]. */
fun glassComposite(surface: Color, alpha: Float, behind: Color): Color =
    surface.copy(alpha = alpha).compositeOver(behind)

/**
 * Smallest fill alpha (to 1/1024) at which [text] on [surface] over [behind]
 * reaches [target]. 1 when even an opaque fill can't.
 */
fun minAlphaForContrast(surface: Color, text: Color, behind: Color, target: Float = 4.5f): Float {
    if (contrastRatio(text, glassComposite(surface, 1f, behind)) < target) return 1f
    if (contrastRatio(text, glassComposite(surface, 0f, behind)) >= target) return 0f
    var lo = 0f; var hi = 1f
    repeat(10) {
        val mid = (lo + hi) / 2f
        if (contrastRatio(text, glassComposite(surface, mid, behind)) >= target) hi = mid else lo = mid
    }
    return hi
}

/**
 * The orb backdrop's extreme colours: the background alone, each orb at full
 * strength, and the orbs stacked. Blur only averages these, so nothing the
 * backdrop shows is further from the background than one of them.
 */
fun glassBackdropExtremes(scheme: ColorScheme, dark: Boolean): List<Color> {
    val k = if (dark) 1f else GLASS_LIGHT_STRENGTH
    val bg = scheme.background
    val p = scheme.primary.copy(alpha = GLASS_GLOW_PRIMARY_ALPHA * k)
    val t = scheme.tertiary.copy(alpha = GLASS_GLOW_TERTIARY_ALPHA * k)
    val s = scheme.secondary.copy(alpha = GLASS_GLOW_SECONDARY_ALPHA * k)
    return listOf(
        bg,
        p.compositeOver(bg), t.compositeOver(bg), s.compositeOver(bg),
        t.compositeOver(p.compositeOver(bg)),
        s.compositeOver(t.compositeOver(p.compositeOver(bg)))
    )
}

/** Back-compat helper: the stacked-orb extreme (kept for the existing test). */
fun glassWorstBackdrop(background: Color, primary: Color, tertiary: Color, dark: Boolean): Color {
    val k = if (dark) 1f else GLASS_LIGHT_STRENGTH
    return tertiary.copy(alpha = GLASS_GLOW_TERTIARY_ALPHA * k)
        .compositeOver(primary.copy(alpha = GLASS_GLOW_PRIMARY_ALPHA * k).compositeOver(background))
}

/**
 * Everything a glass surface at [level] can end up over, worst cases included.
 * Floating bars and overlays can sit over any content colour the app draws
 * (text, accent tiles, containers), taken at full strength as if the blur
 * didn't soften it. Overlays add those colours under the dialog dim.
 * Nested glass sits over a card's glass, so its backdrop is that composite.
 */
fun glassBackdropCandidates(scheme: ColorScheme, dark: Boolean, level: GlassLevel,
                            rendering: GlassRendering): List<Color> {
    val orbs = glassBackdropExtremes(scheme, dark)
    val content = listOf(
        scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.onPrimary,
        scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer,
        scheme.surfaceContainerHighest, scheme.outline, scheme.surface
    )
    return when (level) {
        GlassLevel.Card -> orbs
        GlassLevel.Floating -> orbs + content
        GlassLevel.Overlay -> {
            val all = orbs + content
            all + all.map { Color.Black.copy(alpha = glassDialogDim(windowBlur = false)).compositeOver(it) }
        }
        GlassLevel.Nested -> {
            val cardAlpha = glassFillAlpha(scheme, dark, GlassLevel.Card, rendering, scheme.surfaceContainer,
                                           listOf(scheme.onSurface, scheme.onSurfaceVariant))
            orbs.map { glassComposite(scheme.surfaceContainer, cardAlpha, it) }
        }
    }
}

/**
 * The fill alpha a glass surface of colour [tint] uses: at least the look's
 * [glassBaseAlpha], raised until every colour in [texts] reaches WCAG AA
 * (4.5:1) over every backdrop the surface can sit on. Pure; unit-tested.
 */
fun glassFillAlpha(scheme: ColorScheme, dark: Boolean, level: GlassLevel, rendering: GlassRendering,
                   tint: Color, texts: List<Color>): Float {
    val base = glassBaseAlpha(rendering, level, dark)
    if (rendering == GlassRendering.Opaque) return base
    val solid = tint.copy(alpha = 1f)
    var need = base
    for (behind in glassBackdropCandidates(scheme, dark, level, rendering)) {
        for (text in texts) {
            val a = minAlphaForContrast(solid, text.copy(alpha = 1f), behind)
            if (a > need) need = a
        }
    }
    return need.coerceIn(0f, 1f)
}

/** The style in effect. Standard unless the user picked Liquid Glass. */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.Standard }

/** How glass renders right now; only meaningful when [LocalUiStyle] is LiquidGlass. */
val LocalGlassRendering = staticCompositionLocalOf { GlassRendering.Blur }

/**
 * Reads the system signals [resolveGlassRendering] needs and keeps them live
 * (a ContentObserver re-reads them when the user flips a setting). Called once,
 * in the theme; everything else reads [LocalGlassRendering].
 * Android has no Reduce Transparency setting; see [resolveGlassRendering].
 */
@Composable
fun rememberGlassRendering(): GlassRendering {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val state = remember(context) { mutableStateOf(readGlassRendering(resolver)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state.value = readGlassRendering(resolver) }
        }
        runCatching {
            resolver.registerContentObserver(Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer)
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer)
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return state.value
}

private fun readGlassRendering(resolver: ContentResolver): GlassRendering {
    val highContrast = runCatching {
        Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) == 1
    }.getOrDefault(false)
    val transition = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
    }.getOrDefault(1f)
    return resolveGlassRendering(Build.VERSION.SDK_INT, highContrast, transition)
}

// ── Contrast tint under labels on glass ─────────────────────────────────

/**
 * Alpha of a [tint] layer drawn directly beneath a label so every colour in
 * [texts] reaches WCAG AA (4.5:1) over the worst case the dynamic glass
 * backdrop can show: every orb extreme plus every content colour the app
 * draws, at full strength (the Floating candidate set). The card's own fill
 * under the label is ignored, so this holds even where a lens shows the
 * backdrop unfiltered. Pure; unit-tested.
 */
fun glassLabelTintAlpha(scheme: ColorScheme, dark: Boolean, tint: Color, texts: List<Color>): Float {
    val solid = tint.copy(alpha = 1f)
    var need = 0f
    for (behind in glassBackdropCandidates(scheme, dark, GlassLevel.Floating, GlassRendering.Refraction)) {
        for (text in texts) {
            val a = minAlphaForContrast(solid, text.copy(alpha = 1f), behind)
            if (a > need) need = a
        }
    }
    return need.coerceIn(0f, 1f)
}
