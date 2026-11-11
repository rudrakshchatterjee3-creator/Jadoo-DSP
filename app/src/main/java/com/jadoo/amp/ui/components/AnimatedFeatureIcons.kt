package com.jadoo.amp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.jadoo.amp.ui.theme.JadooTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Feature icons that move while their feature is on.
 *
 * Two values drive every glyph:
 *  - `amp` (0..1) springs toward 1 when the feature turns on and back to 0
 *    when it turns off. Every movement is scaled by it, so at 0 each glyph
 *    is its plain resting icon and turning off eases back to that pose
 *    instead of cutting.
 *  - `phase` (0..1) loops slowly while `amp` is above zero. Every movement
 *    is periodic in it, so the loop has no seam. It keeps running until
 *    `amp` has settled, then stops: an off icon costs no frames.
 *
 * With reduced motion (animator scale 0) `amp` snaps and nothing loops.
 */
enum class FeatureGlyph(val periodMs: Int) {
    GraphicEq(3200),
    ParametricEq(4200),
    AnalogBass(4600),
    TubeWarmth(1800),
    Dbfb(2600),
    HiRes(3000),
    Exciter(2800),
    Hdr(3600),
    Surround(2400),
    Loudness(2200)
}

@Composable
fun AnimatedFeatureIcon(
    glyph: FeatureGlyph,
    on: Boolean,
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    val motion = JadooTheme.motion
    val amp = remember { Animatable(if (on) 1f else 0f) }
    val phase = remember { Animatable(0f) }

    LaunchedEffect(on, motion.enabled) {
        val target = if (on) 1f else 0f
        if (!motion.enabled) {
            amp.snapTo(target)
            return@LaunchedEffect
        }
        coroutineScope {
            launch { amp.animateTo(target, motion.gentle) }
            while (isActive && (on || amp.isRunning)) {
                // Continue from where the phase is, so a toggle mid-loop
                // never jumps the pose.
                val remaining = ((1f - phase.value) * glyph.periodMs).toInt()
                phase.animateTo(1f, tween(max(remaining, 1), easing = LinearEasing))
                phase.snapTo(0f)
            }
        }
    }

    Canvas(modifier) {
        val a = amp.value
        val p = phase.value
        scale(size.minDimension / 24f, Offset.Zero) {
            when (glyph) {
                FeatureGlyph.GraphicEq -> drawGraphicEq(tint, a, p)
                FeatureGlyph.ParametricEq -> drawParametricEq(tint, a, p)
                FeatureGlyph.AnalogBass -> drawAnalogBass(tint, a, p)
                FeatureGlyph.TubeWarmth -> drawTubeWarmth(tint, a, p)
                FeatureGlyph.Dbfb -> drawDbfb(tint, a, p)
                FeatureGlyph.HiRes -> drawHiRes(tint, a, p)
                FeatureGlyph.Exciter -> drawExciter(tint, a, p)
                FeatureGlyph.Hdr -> drawHdr(tint, a, p)
                FeatureGlyph.Surround -> drawSurround(tint, a, p)
                FeatureGlyph.Loudness -> drawLoudness(tint, a, p)
            }
        }
    }
}

private const val TAU = (2.0 * PI).toFloat()
private const val STROKE = 1.9f

private fun line(w: Float = STROKE) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

// ── Graphic EQ: seven bars swaying slowly out of step ───────────────────
private fun DrawScope.drawGraphicEq(c: Color, a: Float, p: Float) {
    val rest = floatArrayOf(5f, 9f, 14f, 18f, 12f, 8f, 4.5f)
    for (i in rest.indices) {
        val sway = 0.4f * sin(TAU * p + 1.3f * i) + 0.15f * sin(2f * TAU * p + 2.1f * i)
        val h = (rest[i] * (1f + a * sway)).coerceIn(2.5f, 21f)
        drawRoundRect(
            c, topLeft = Offset(3.5f + 3f * i - 1f, 12f - h / 2f),
            size = Size(2f, h), cornerRadius = CornerRadius(1f)
        )
    }
}

// ── Parametric EQ: three sliders whose knobs are adjusted ───────────────
private fun DrawScope.drawParametricEq(c: Color, a: Float, p: Float) {
    val rest = floatArrayOf(0.70f, 0.30f, 0.55f)
    for (i in 0..2) {
        val y = 6f + 6f * i
        val pos = (rest[i] + 0.24f * a * sin(TAU * p + 2.1f * i)).coerceIn(0.08f, 0.92f)
        drawLine(c, Offset(3f, y), Offset(21f, y), strokeWidth = 1.8f, cap = StrokeCap.Round)
        drawCircle(c, radius = 2.6f, center = Offset(3f + 18f * pos, y))
    }
}

// ── Analog Bass: a radio whose telescopic antenna folds and extends ─────
private fun DrawScope.drawAnalogBass(c: Color, a: Float, p: Float) {
    drawRoundRect(c, topLeft = Offset(3f, 9f), size = Size(18f, 12f),
        cornerRadius = CornerRadius(2.5f), style = line())
    drawCircle(c, radius = 2.9f, center = Offset(8.5f, 15f), style = line(1.7f))
    drawLine(c, Offset(14.5f, 13f), Offset(18f, 13f), strokeWidth = 1.7f, cap = StrokeCap.Round)
    drawLine(c, Offset(14.5f, 17f), Offset(18f, 17f), strokeWidth = 1.7f, cap = StrokeCap.Round)

    // Extension: 1 = fully out. Starts out, folds to ~45%, comes back.
    val fold = 0.5f + 0.5f * sin(TAU * p - (PI / 2).toFloat())
    val ext = 1f - 0.55f * a * fold
    val tilt = a * 5f * sin(TAU * p)
    val base = Offset(16f, 9f)
    val full = 10f
    val ang = Math.toRadians((-139.0 + tilt)).toFloat()
    val dir = Offset(cos(ang), sin(ang))
    val total = full * ext
    // Three nested sections, thick to thin, as a real telescopic whip.
    val cuts = floatArrayOf(0f, 0.38f * full, 0.7f * full, total)
    val widths = floatArrayOf(2.3f, 1.8f, 1.3f)
    for (s in 0..2) {
        val from = cuts[s]
        val to = if (s == 2) total else minOf(cuts[s + 1], total)
        if (to <= from) continue
        drawLine(c, base + dir * from, base + dir * to, strokeWidth = widths[s], cap = StrokeCap.Round)
    }
    drawCircle(c, radius = 1.3f, center = base + dir * (total + 0.4f))
}

// ── Tube Warmth: a flickering flame ─────────────────────────────────────
private fun DrawScope.drawTubeWarmth(c: Color, a: Float, p: Float) {
    val sway = a * 1.3f * sin(TAU * p)
    val lift = a * 1.6f * (0.5f + 0.5f * sin(2f * TAU * p))
    val squash = 1f + a * 0.05f * sin(2f * TAU * p + 1f)
    val tipX = 12f + sway
    val tipY = 2f + lift
    val path = Path().apply {
        moveTo(tipX, tipY)
        cubicTo(15.5f + sway * 0.5f, 6.5f + lift * 0.5f, 19f, 10f, 19f, 14.5f)
        cubicTo(19f, 18.6f, 15.9f, 22f, 12f, 22f)
        cubicTo(8.1f, 22f, 5f, 18.6f, 5f, 14.5f)
        cubicTo(5f, 11.5f, 6.8f, 9.8f, 8.6f, 7.4f)
        cubicTo(9.2f, 9f, 10.2f, 10f, 11.3f, 10.2f)
        cubicTo(11.2f, 7f, 11.6f, 4.6f, tipX, tipY)
        close()
    }
    scale(1f, squash, pivot = Offset(12f, 22f)) { drawPath(path, c, style = Fill) }
}

// ── DBFB: a sun whose rays breathe in alternation ───────────────────────
private fun DrawScope.drawDbfb(c: Color, a: Float, p: Float) {
    val centre = Offset(12f, 12f)
    drawCircle(c, radius = 3.9f * (1f + 0.08f * a * sin(TAU * p)), center = centre)
    for (i in 0 until 8) {
        val ang = Math.toRadians(i * 45.0).toFloat()
        val d = Offset(cos(ang), sin(ang))
        val outer = 8.4f + 1.5f * a * sin(TAU * p + (i % 2) * PI.toFloat())
        drawLine(c, centre + d * 6.6f, centre + d * outer, strokeWidth = 1.9f, cap = StrokeCap.Round)
    }
}

// ── Hi-Res: a clean wave in a frame that grows fine detail on top ───────
private fun DrawScope.drawHiRes(c: Color, a: Float, p: Float) {
    drawRoundRect(c, topLeft = Offset(2.5f, 5f), size = Size(19f, 14f),
        cornerRadius = CornerRadius(3f), style = line())
    val path = Path()
    val steps = 48
    for (s in 0..steps) {
        val u = s / steps.toFloat()
        val x = 6f + 12f * u
        val y = 12f - 3f * sin(TAU * u) - 1.3f * a * sin(TAU * (3f * u - p)) * sin(PI.toFloat() * u)
        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, c, style = line(1.7f))
}

// ── Harmonic Exciter: three sparkles twinkling out of step ──────────────
private fun DrawScope.drawExciter(c: Color, a: Float, p: Float) {
    fun star(cx: Float, cy: Float, r: Float, offset: Float) {
        val s = 1f + 0.26f * a * sin(TAU * p + offset)
        val rot = 14f * a * sin(TAU * p + offset)
        val rr = r * s
        val k = rr * 0.2f
        val path = Path().apply {
            moveTo(cx, cy - rr)
            quadraticTo(cx + k, cy - k, cx + rr, cy)
            quadraticTo(cx + k, cy + k, cx, cy + rr)
            quadraticTo(cx - k, cy + k, cx - rr, cy)
            quadraticTo(cx - k, cy - k, cx, cy - rr)
            close()
        }
        rotate(rot, pivot = Offset(cx, cy)) { drawPath(path, c, style = Fill) }
    }
    star(9f, 14f, 7.5f, 0f)
    star(18.5f, 6f, 3.8f, 2.1f)
    star(18.5f, 18.5f, 3f, 4.2f)
}

// ── HDR: the light/dark split rocks, opening the contrast ───────────────
private fun DrawScope.drawHdr(c: Color, a: Float, p: Float) {
    drawCircle(c, radius = 8.6f, center = Offset(12f, 12f), style = line())
    val rot = 35f * a * sin(TAU * p)
    drawArc(c, startAngle = -90f + rot, sweepAngle = 180f, useCenter = true,
        topLeft = Offset(5f, 5f), size = Size(14f, 14f))
}

// ── Surround: rings travelling out to both sides ────────────────────────
private fun DrawScope.drawSurround(c: Color, a: Float, p: Float) {
    val centre = Offset(12f, 12f)
    drawCircle(c, radius = 2.2f, center = centre)
    waves(c, centre, -45f, 90f, a, p, floatArrayOf(5.2f, 8.8f))
    waves(c, centre, 135f, 90f, a, p, floatArrayOf(5.2f, 8.8f))
}

// ── Loudness: a speaker pushing rings outward ───────────────────────────
private fun DrawScope.drawLoudness(c: Color, a: Float, p: Float) {
    val cone = Path().apply {
        moveTo(3f, 9.5f); lineTo(6.8f, 9.5f); lineTo(11f, 5.2f)
        lineTo(11f, 18.8f); lineTo(6.8f, 14.5f); lineTo(3f, 14.5f); close()
    }
    drawPath(cone, c, style = Fill)
    waves(c, Offset(11.5f, 12f), -45f, 90f, a, p, floatArrayOf(4.6f, 8f))
}

/** Two arcs at rest radii; with `a` they become rings that travel out and fade. */
private fun DrawScope.waves(
    c: Color, centre: Offset, startDeg: Float, sweepDeg: Float,
    a: Float, p: Float, restRadii: FloatArray
) {
    for (i in restRadii.indices) {
        val f = (p + i / restRadii.size.toFloat()) % 1f
        val moving = 3.5f + 6f * f
        val r = lerp(restRadii[i], moving, a)
        val alpha = lerp(1f, sin(PI.toFloat() * f), a).coerceIn(0f, 1f)
        if (alpha <= 0.01f) continue
        drawArc(
            c.copy(alpha = c.alpha * alpha), startAngle = startDeg, sweepAngle = sweepDeg,
            useCenter = false, topLeft = Offset(centre.x - r, centre.y - r),
            size = Size(2f * r, 2f * r), style = line(1.8f)
        )
    }
}
