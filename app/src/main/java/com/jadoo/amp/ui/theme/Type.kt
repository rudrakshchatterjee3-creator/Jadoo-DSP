package com.jadoo.amp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's type scale is stock Material 3 — there is no custom font, and the
 * platform's default already reads well at every size the app uses.
 *
 * What this file adds is the one style M3 has no slot for. Readouts use
 * the M3 styles with `fontFeatureSettings = "tnum"` at the call site.
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
    )
)
