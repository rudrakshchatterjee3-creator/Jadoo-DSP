package com.jadoo.amp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Everything Material 3 has no slot for.
 *
 * The ownership split is deliberate:
 *
 *  - `colorScheme`, `typography` and `shapes` stay on [MaterialTheme], so every
 *    stock M3 widget in the app inherits them without a single call-site
 *    change. Duplicating them here would mean maintaining two sources of truth
 *    and would leave stock components on the defaults.
 *  - Spacing, alpha, motion and the extra text styles have no M3 home, so they
 *    ride here — reached through [JadooTheme], which mirrors `MaterialTheme`'s
 *    shape so the two read the same at the call site.
 *
 * `staticCompositionLocalOf`, not `compositionLocalOf`, and that is a real
 * decision rather than a default. Static locals invalidate the ENTIRE subtree
 * when their value changes, rather than only the composables that read them.
 * For spacing and radii that costs nothing, because they never change at
 * runtime. For motion and — via the scheme — colour, the whole-tree
 * invalidation is exactly what we want: a theme change genuinely does affect
 * everything, and the recomposition happens underneath the transition curtain
 * where it cannot be seen. Paying reader-tracking overhead on every read to
 * optimise an event that is hidden anyway would be the wrong trade.
 */
@Immutable
data class JadooTokens(
    val dimens: JadooDimens = JadooDimens(),
    val alpha: JadooAlpha = JadooAlpha(),
    val shapes: JadooShapes = JadooShapes(),
    val text: JadooTextStyles = JadooTextStyles(),
    val motion: JadooMotion = JadooMotion()
)

val LocalJadooTokens = staticCompositionLocalOf { JadooTokens() }

/**
 * Accessor mirroring [MaterialTheme]. `JadooTheme.dimens.lg` reads alongside
 * `MaterialTheme.colorScheme.primary` without a mental gear change.
 */
object JadooTheme {
    val dimens: JadooDimens
        @Composable @ReadOnlyComposable get() = LocalJadooTokens.current.dimens

    val alpha: JadooAlpha
        @Composable @ReadOnlyComposable get() = LocalJadooTokens.current.alpha

    val shapes: JadooShapes
        @Composable @ReadOnlyComposable get() = LocalJadooTokens.current.shapes

    val text: JadooTextStyles
        @Composable @ReadOnlyComposable get() = LocalJadooTokens.current.text

    val motion: JadooMotion
        @Composable @ReadOnlyComposable get() = LocalJadooTokens.current.motion
}
