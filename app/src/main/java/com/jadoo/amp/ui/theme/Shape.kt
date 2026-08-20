package com.jadoo.amp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * Fourteen distinct radii existed before this — 4, 8, 10, 12, 14, 16, 18, 20,
 * 22, 24, 28, 32, 50% and a couple of one-offs. Six carry the whole design, and
 * the difference between the ones that merged (18 vs 20, 22 vs 24) was not
 * perceptible at any size the app actually renders them.
 *
 * These feed [MaterialTheme.shapes] so stock M3 components inherit them without
 * any per-call-site work.
 */
@Immutable
data class JadooShapes(
    /** Chips, small badges, inline pills. */
    val chip: RoundedCornerShape = RoundedCornerShape(8.dp),
    /** Inputs, small buttons, list rows that need definition. */
    val control: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** Nested surfaces inside a card. */
    val inner: RoundedCornerShape = RoundedCornerShape(16.dp),
    /** The dominant feature-card radius. */
    val card: RoundedCornerShape = RoundedCornerShape(24.dp),
    /** Dialogs, sheets, full-width overlays. */
    val sheet: RoundedCornerShape = RoundedCornerShape(32.dp),
    /** Fully round — swatches, avatars, the brand mark's bounding box. */
    val round: RoundedCornerShape = RoundedCornerShape(percent = 50)
)

/** The M3 [Shapes] mapping, so stock components pick these up for free. */
fun JadooShapes.toMaterialShapes(): Shapes = Shapes(
    extraSmall = chip,
    small = control,
    medium = inner,
    large = card,
    extraLarge = sheet
)
