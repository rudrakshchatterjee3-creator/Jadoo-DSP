package com.jadoo.amp.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.9 shipped letter spacing in `em`, and Live Chain's
 * `Text(style = labelMedium, letterSpacing = 1.sp)` crashed with
 * "Cannot perform operation for Sp and Em". Every style must use `sp`.
 */
class TypographyTest {

    private val styles: Map<String, TextStyle> = JadooTypography.let {
        mapOf(
            "displayLarge" to it.displayLarge, "displayMedium" to it.displayMedium,
            "displaySmall" to it.displaySmall, "headlineLarge" to it.headlineLarge,
            "headlineMedium" to it.headlineMedium, "headlineSmall" to it.headlineSmall,
            "titleLarge" to it.titleLarge, "titleMedium" to it.titleMedium,
            "titleSmall" to it.titleSmall, "bodyLarge" to it.bodyLarge,
            "bodyMedium" to it.bodyMedium, "bodySmall" to it.bodySmall,
            "labelLarge" to it.labelLarge, "labelMedium" to it.labelMedium,
            "labelSmall" to it.labelSmall,
            "bodyCompact" to JadooTextStyles().bodyCompact
        )
    }

    @Test
    fun letterSpacingAndLineHeightAreInSp() {
        for ((name, style) in styles) {
            assertTrue("$name letterSpacing", style.letterSpacing.type != TextUnitType.Em)
            assertTrue("$name lineHeight", style.lineHeight.type == TextUnitType.Sp)
            assertTrue("$name fontSize", style.fontSize.type == TextUnitType.Sp)
        }
    }

    @Test
    fun mergingAnSpLetterSpacingDoesNotThrow() {
        for ((_, style) in styles) {
            style.merge(letterSpacing = 1.sp, lineHeight = 20.sp, fontSize = 12.sp)
        }
    }
}
