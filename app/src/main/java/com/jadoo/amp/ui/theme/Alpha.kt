package com.jadoo.amp.ui.theme

import androidx.compose.runtime.Immutable

/**
 * Emphasis levels.
 *
 * The pre-token UI carried 35 distinct alpha values across 127 call sites, every
 * one an inline literal — 0.4f, 0.42f, 0.45f and 0.46f all appeared, all meaning
 * "dimmed". Five levels cover every real case.
 *
 * Alpha is for EMPHASIS, not for colour. If a value here is being used to make
 * something a different colour, the right answer is a different colour role.
 */
@Immutable
data class JadooAlpha(
    /** Full-strength content. */
    val full: Float = 1f,
    /** Secondary text, inactive-but-legible labels. */
    val muted: Float = 0.72f,
    /** Disabled content — must still be readable, just clearly inert. */
    val disabled: Float = 0.42f,
    /** Icon and container tints that sit behind content. */
    val subtle: Float = 0.24f,
    /** Hairlines, dividers, the faintest structural marks. */
    val hairline: Float = 0.12f
)
