package com.jadoo.amp.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize

/**
 * Motion specs.
 *
 * Two rules the whole app follows:
 *
 * 1. **Springs, not durations, for anything the user can interrupt.** A toggle
 *    or an expanding card can be hit again mid-flight, and a spring picks the
 *    new target up from wherever it actually is, at whatever velocity it
 *    actually has. A `tween` restarts from a fixed curve and visibly stutters.
 *    Durations are reserved for things that genuinely cannot be interrupted —
 *    the theme transition's one-shot curtain, for instance.
 *
 * 2. **Reduced motion is handled once, here.** When [enabled] is false every
 *    spec below is `snap()`, so the setting is honoured at all ~130 animation
 *    call sites without any of them knowing it exists. The alternative — a
 *    conditional at each site — guarantees some get missed.
 *
 * Note what is deliberately NOT routed through this: the help illustrations in
 * `HelpIllustrations.kt` keep their own `still`-frame behaviour. Snapping them
 * would pin each diagram to an arbitrary animation frame; they instead hold
 * the single most *explanatory* frame, which is a different and better answer
 * for a diagram whose whole job is to teach a mechanism.
 */
@Immutable
data class JadooMotion(
    val enabled: Boolean = true
) {
    /** Toggle knobs, chip selection, small state flips. */
    val fast: FiniteAnimationSpec<Float>
        get() = if (!enabled) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )

    /** The default for most visible movement — card expansion, reveals. */
    val standard: FiniteAnimationSpec<Float>
        get() = if (!enabled) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )

    /** Large surfaces settling — sheets, section reflow. */
    val gentle: FiniteAnimationSpec<Float>
        get() = if (!enabled) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        )

    /** A small overshoot, for things that should feel physical (the power toggle). */
    val expressive: FiniteAnimationSpec<Float>
        get() = if (!enabled) snap() else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )

    /** Size-typed variant of [standard], for `AnimatedVisibility` expand/shrink. */
    val standardSize: FiniteAnimationSpec<IntSize>
        get() = if (!enabled) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )

    /** Cross-fades and opacity-only changes. */
    val fade: FiniteAnimationSpec<Float>
        get() = if (!enabled) snap() else tween(durationMillis = 180)

    /**
     * Same spring as [fast], generically typed for `animateColorAsState`/
     * `animateDpAsState`/etc — those need `AnimationSpec<Color>`/`<Dp>`, not
     * `<Float>`, so [fast]'s own `FiniteAnimationSpec<Float>` type doesn't
     * fit them directly. Kept as one function sharing [fast]'s exact numbers
     * rather than a second literal spring, so the two can't drift apart.
     */
    fun <T> fastSpec(): AnimationSpec<T> =
        if (!enabled) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )

    /** Generically typed variant of [expressive] — see [fastSpec]. */
    fun <T> expressiveSpec(): AnimationSpec<T> =
        if (!enabled) snap() else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )

    /**
     * Generically typed variant of [standard], returned as the more specific
     * [FiniteAnimationSpec] (spring/snap both satisfy it) so it can feed
     * APIs like `slideInHorizontally` that require that exact type rather
     * than the looser [AnimationSpec].
     */
    fun <T> standardSpec(): FiniteAnimationSpec<T> =
        if (!enabled) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )

    companion object {
        /**
         * The theme transition, which is a one-shot the user cannot interrupt
         * (the whole screen is covered for its duration) and therefore the one
         * place a fixed duration is correct. See the transition overlay for
         * the phase breakdown.
         */
        const val THEME_TRANSITION_MS = 620
    }
}

/**
 * True when the system's animator scale is non-zero.
 *
 * `ANIMATOR_DURATION_SCALE == 0` is what Android's "Remove animations"
 * accessibility setting and every battery saver actually set. There is no
 * higher-level Compose API for it, so it is read directly.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }.getOrDefault(true)
    }
}
