package com.jadoo.amp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jadoo.amp.ui.theme.BrandPalette

/**
 * The JadOO mark, drawn rather than imported.
 *
 * Geometry measured from the source PNGs and preserved exactly:
 *
 *     both circles      identical diameter (verified against the artwork)
 *     centre distance   0.582 · D
 *     overlap           41.8%
 *     centres           share one horizontal axis
 *
 * One composable serves the app header, the About screen, onboarding, and the
 * theme-change transition. That is the reason it is a Canvas and not a
 * `VectorPainter` over the drawable: [progress] drives a stroke trim, which a
 * static vector cannot do, and the transition needs the mark to draw itself on.
 *
 * @param progress 0..1 stroke trim. At 0 nothing is drawn; at 1 the mark is
 *   complete. The elements strike on in reading order — left ring, datum,
 *   right ring, curve — so the mark assembles as "reference, then processed".
 * @param datumCurve 0..1 blend of the right circle's bar from flat (0) to the
 *   EQ boost (1). Animating this is the cheapest possible way to say "the DSP
 *   is on" without adding an element.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    datumCurve: Float = 1f,
    referenceColor: Color = BrandPalette.White,
    processedColor: Color = BrandPalette.Gold,
    strokeRatio: Float = 0.125f
) {
    Canvas(modifier) {
        drawBrandMark(
            progress = progress,
            datumCurve = datumCurve,
            referenceColor = referenceColor,
            processedColor = processedColor,
            strokeRatio = strokeRatio
        )
    }
}

/**
 * Canvas-level draw, exposed separately so the theme transition can call it
 * from inside its own overlay's draw phase without a nested composable — and
 * therefore without recomposing once per animation frame.
 */
fun DrawScope.drawBrandMark(
    progress: Float,
    datumCurve: Float,
    referenceColor: Color,
    processedColor: Color,
    strokeRatio: Float = 0.125f
) {
    if (progress <= 0f) return

    // Fit the mark to the smaller axis, preserving the source aspect. Total
    // width is 2r + 1.164r + stroke; solving against the available box is what
    // keeps the mark from clipping at any size.
    val stroke = { r: Float -> r * 2f * strokeRatio }
    val rFromWidth = size.width / (2f + 1.164f + 2f * strokeRatio)
    val rFromHeight = (size.height - size.height * strokeRatio) / 2.2f
    val r = minOf(rFromWidth, rFromHeight)
    val sw = stroke(r)

    val totalWidth = 2f * r + 1.164f * r
    val leftCx = size.width / 2f - totalWidth / 2f + r
    val rightCx = leftCx + 1.164f * r
    val cy = size.height / 2f

    val stroke_ = Stroke(width = sw, cap = StrokeCap.Round)

    // Four elements striking on in sequence across the 0..1 window. The
    // overlap (each starts before the previous finishes) is what stops it
    // reading as four separate events.
    fun window(start: Float, end: Float): Float =
        ((progress - start) / (end - start)).coerceIn(0f, 1f)

    val pLeftRing = window(0f, 0.45f)
    val pDatum = window(0.25f, 0.60f)
    val pRightRing = window(0.40f, 0.85f)
    val pCurve = window(0.65f, 1f)

    // ── Left circle + flat datum: the reference channel ───────────────────
    if (pLeftRing > 0f) {
        drawTrimmedPath(
            Path().apply {
                addOval(Rect(Offset(leftCx - r, cy - r), Size(r * 2f, r * 2f)))
            },
            pLeftRing, referenceColor, stroke_
        )
    }
    if (pDatum > 0f) {
        val inset = r * 0.30f
        drawTrimmedPath(
            Path().apply {
                moveTo(leftCx - r + inset, cy)
                lineTo(leftCx + r - inset, cy)
            },
            pDatum, referenceColor, stroke_
        )
    }

    // ── Right circle + bent bar: the processed channel ────────────────────
    // Drawn second so it sits above the left, matching the source z-order.
    if (pRightRing > 0f) {
        drawTrimmedPath(
            Path().apply {
                addOval(Rect(Offset(rightCx - r, cy - r), Size(r * 2f, r * 2f)))
            },
            pRightRing, processedColor, stroke_
        )
    }
    if (pCurve > 0f) {
        val inset = r * 0.30f
        val x0 = rightCx - r + inset
        val x1 = rightCx + r - inset
        // Lift is expressed in stroke widths so the curve stays legible at
        // every size — a fixed fraction of r vanishes on a small mark.
        val lift = sw * 1.8f * datumCurve.coerceIn(0f, 1f)
        drawTrimmedPath(
            Path().apply {
                moveTo(x0, cy)
                cubicTo(
                    x0 + (rightCx - x0) * 0.55f, cy,
                    rightCx - (rightCx - x0) * 0.45f, cy - lift,
                    rightCx, cy - lift
                )
                cubicTo(
                    rightCx + (x1 - rightCx) * 0.45f, cy - lift,
                    x1 - (x1 - rightCx) * 0.55f, cy,
                    x1, cy
                )
            },
            pCurve, processedColor, stroke_
        )
    }
}

/**
 * Strokes the first [fraction] of [path].
 *
 * `PathMeasure.getSegment` is the only way to trim a path in Compose — there
 * is no `trimPathStart`/`trimPathEnd` equivalent outside of vector drawables.
 * Allocating a `PathMeasure` per element per frame is acceptable here because
 * this runs at most four times a frame and only during the transition; the
 * steady state is `progress == 1f`, which short-circuits.
 */
private fun DrawScope.drawTrimmedPath(
    path: Path,
    fraction: Float,
    color: Color,
    stroke: Stroke
) {
    if (fraction >= 1f) {
        drawPath(path, color, style = stroke)
        return
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val out = Path()
    measure.getSegment(0f, measure.length * fraction, out, true)
    drawPath(out, color, style = stroke)
}
