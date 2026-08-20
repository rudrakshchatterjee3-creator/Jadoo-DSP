package com.jadoo.amp.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale.
 *
 * The pre-token UI used 38 distinct padding forms across the dashboard, almost
 * all of them one-off literals a few dp apart. They collapse to these twelve
 * without any visible change, because the differences were accidental rather
 * than designed.
 *
 * The scale is 4dp-based and deliberately sparse: if a value here doesn't fit,
 * the layout is usually the thing that's wrong, not the scale.
 */
@Immutable
data class JadooDimens(
    /** Hairline separations — icon-to-label, chip internals. */
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,

    /** Horizontal inset from the screen edge to card content. */
    val screenPadding: Dp = 16.dp,
    /** Inset from a card's edge to its content. */
    val cardPadding: Dp = 20.dp,

    /**
     * Gap between stacked cards.
     *
     * Replaces today's `spacedBy(6.dp)` plus an explicit `Spacer(6.dp)` between
     * every pair — which is a 12dp gap expressed twice, in two places, where
     * changing one silently halves it. This encodes the real number once.
     */
    val stackGap: Dp = 12.dp,

    /** Minimum interactive target. Below this, touch accuracy collapses. */
    val touchTarget: Dp = 48.dp
)
