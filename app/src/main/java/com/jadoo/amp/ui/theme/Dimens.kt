package com.jadoo.amp.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale.
 *
 * The pre-token UI used 38 distinct padding forms across the dashboard, almost
 * all of them one-off literals a few dp apart. They collapse to this handful
 * without any visible change, because the differences were accidental rather
 * than designed.
 *
 * The scale is 4dp-based and deliberately sparse: if a value here doesn't fit,
 * the layout is usually the thing that's wrong, not the scale.
 */
@Immutable
data class JadooDimens(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xxxl: Dp = 32.dp,

    /** Horizontal inset from the screen edge to card content. */
    val screenPadding: Dp = 16.dp,

)
