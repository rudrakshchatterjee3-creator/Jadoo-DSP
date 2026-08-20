package com.jadoo.amp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The app's type scale is stock Material 3 — there is no custom font, and the
 * platform's default already reads well at every size the app uses.
 *
 * What this file adds is the three styles M3 has no slot for. Thirteen raw
 * `.sp` literals were sitting alongside `MaterialTheme.typography` before;
 * ten of them were just `bodyMedium`/`labelSmall` restated, and the remaining
 * three are genuinely different things that deserve names.
 */
val JadooTypography = Typography()

@Immutable
data class JadooTextStyles(
    /**
     * 13sp body — one notch below `bodyMedium`'s 14sp.
     *
     * Deliberately kept rather than rounded up. The Analog Bass control rows
     * are laid out against this size and reflow to two lines at 14sp, which
     * changes the card's height and breaks its rhythm against its neighbours.
     * A real 1sp distinction, not drift.
     */
    val bodyCompact: TextStyle = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal
    ),

    /** The all-caps run-in label above each section. */
    val sectionLabel: TextStyle = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.1.sp,
        fontWeight = FontWeight.SemiBold
    ),

    /**
     * Numeric readouts — dB, Hz, percentages.
     *
     * `tnum` forces tabular (fixed-width) figures. Without it the digits are
     * proportionally spaced, so a value ticking 9.8 → 10.1 → 9.9 during a
     * slider drag makes the whole readout jitter sideways. This is the single
     * highest-value line in the type file.
     */
    val readout: TextStyle = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.End
    )
)
