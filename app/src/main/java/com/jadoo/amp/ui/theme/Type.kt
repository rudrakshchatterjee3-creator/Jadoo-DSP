package com.jadoo.amp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jadoo.amp.R

/**
 * Inter Tight (SIL OFL), four static weights subset to Latin plus common
 * punctuation (~43 KB each); any other character falls back to the system
 * font. The files sit a notch heavier than their names (Regular is wght 450,
 * Medium 560, SemiBold 660, Bold 760): at true 400-600 the face reads thin on
 * a dark screen. Chosen over SF Pro because it is a narrower face — rows, chips and
 * two-line subtitles fit — and because it can be bundled.
 *
 * To go back to the system font, set [JadooFont] to FontFamily.Default.
 */
val JadooFont = FontFamily(
    Font(R.font.inter_tight_regular, FontWeight.Normal),
    Font(R.font.inter_tight_medium, FontWeight.Medium),
    Font(R.font.inter_tight_semibold, FontWeight.SemiBold),
    Font(R.font.inter_tight_bold, FontWeight.Bold)
)

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: TextUnit = 0.sp
) = TextStyle(
    fontFamily = JadooFont,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking
)

/**
 * The type scale. One rule: every step is a visible step.
 *
 *   page title      displaySmall   30  SemiBold   "Settings", "Processing"
 *   sheet title     headlineSmall  22  SemiBold
 *   card title      titleLarge     19  SemiBold
 *   row title       bodyLarge      16  (rows add SemiBold)
 *   row subtitle    bodyMedium     13  grey — three sizes and two weights under the title
 *   caption         bodySmall      11
 *   button / chip   labelLarge     13  Medium
 *   small label     labelMedium    10  Medium, tracked
 *   micro label     labelSmall     10  Medium, tracked, grey
 *   group header    titleSmall     13  SemiBold (grey)
 *
 * Large steps are tracked in slightly, small ones out, which is what keeps
 * a dense settings screen from looking either shouty or mushy. Readouts use
 * these styles with `fontFeatureSettings = "tnum"` at the call site.
 */
val JadooTypography = Typography(
    displayLarge = style(44, 52, FontWeight.SemiBold, (-0.03).em),
    displayMedium = style(36, 44, FontWeight.SemiBold, (-0.025).em),
    displaySmall = style(30, 36, FontWeight.SemiBold, (-0.02).em),
    headlineLarge = style(28, 34, FontWeight.SemiBold, (-0.02).em),
    headlineMedium = style(25, 31, FontWeight.SemiBold, (-0.015).em),
    headlineSmall = style(22, 28, FontWeight.SemiBold, (-0.012).em),
    titleLarge = style(19, 24, FontWeight.SemiBold, (-0.01).em),
    titleMedium = style(15, 20, FontWeight.Medium),
    titleSmall = style(13, 18, FontWeight.SemiBold),
    bodyLarge = style(16, 22, FontWeight.Normal),
    bodyMedium = style(13, 18, FontWeight.Normal),
    bodySmall = style(11, 15, FontWeight.Normal, 0.01.em),
    labelLarge = style(13, 18, FontWeight.Medium),
    labelMedium = style(10, 14, FontWeight.Medium, 0.03.em),
    labelSmall = style(10, 14, FontWeight.Medium, 0.04.em)
)

@Immutable
data class JadooTextStyles(
    /**
     * 13sp body — same size as `bodyMedium` now that the scale is compact.
     * Kept as its own style because the Analog Bass control rows are laid
     * out against it; they must not move if `bodyMedium` is ever retuned.
     */
    val bodyCompact: TextStyle = TextStyle(
        fontFamily = JadooFont,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal
    )
)
