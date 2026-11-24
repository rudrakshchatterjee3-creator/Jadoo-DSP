package com.jadoo.amp.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.jadoo.amp.ui.theme.JadooTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Motion toolkit for the app's drawn scenes (help diagrams, onboarding).
 *
 * The scenes are choreographed, not looped tweens: one clock, and every
 * element reads its own window of it ([seg]) so entrances overlap and
 * settle in sequence like a cut, then the scene keeps a slow, quiet life.
 * The clock is read inside draw lambdas only, so a running scene redraws its
 * canvas and never recomposes.
 *
 * Reduced motion (system "Remove animations"): the clock stands still at
 * [SETTLED_S], which every scene treats as its finished, most legible frame.
 */
object Cine {
    /** Expo-like ease out: fast start, long soft landing. The house curve for entrances. */
    val Out: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Symmetric ease for morphs between two states. */
    val InOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    /** Gentle ease out for long draws. */
    val Draw: Easing = CubicBezierEasing(0.33f, 0f, 0.15f, 1f)

    /** Where a still (reduced-motion) scene sits: every entrance done. */
    const val SETTLED_S = 6f
}

/**
 * Seconds since this scene appeared, from the frame clock. Stands at
 * [Cine.SETTLED_S] when motion is off.
 */
@Composable
fun rememberSceneClock(): State<Float> {
    val enabled = JadooTheme.motion.enabled
    val time = remember { mutableFloatStateOf(if (enabled) 0f else Cine.SETTLED_S) }
    LaunchedEffect(enabled) {
        if (!enabled) { time.floatValue = Cine.SETTLED_S; return@LaunchedEffect }
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> time.floatValue = (now - start) / 1e9f }
    }
    return time
}

/** Eased progress 0..1 of the window [start, start + dur] seconds on [t]. */
fun seg(t: Float, start: Float, dur: Float, easing: Easing = Cine.Out): Float =
    easing.transform(((t - start) / dur).coerceIn(0f, 1f))

/** A slow 0..1 swell with period [periodS], for living holds. */
fun breathe(t: Float, periodS: Float, phase: Float = 0f): Float =
    0.5f + 0.5f * sin((t / periodS + phase) * 2f * PI.toFloat())

/** Fractional part, always positive. */
fun frac(x: Float): Float = x - kotlin.math.floor(x)

fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

fun mix(a: Color, b: Color, t: Float) = Color(
    mix(a.red, b.red, t), mix(a.green, b.green, t), mix(a.blue, b.blue, t), mix(a.alpha, b.alpha, t)
)

/** Deterministic pseudo-random 0..1 for particle [i] and stream [k]. */
fun hash01(i: Int, k: Int = 0): Float {
    var h = i * 374761393 + k * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0x7fffffff) / 2147483647f
}

/** The first [fraction] of this path's length. */
fun Path.trimmed(fraction: Float): Path {
    if (fraction >= 1f) return this
    val out = Path()
    if (fraction <= 0f) return out
    val pm = PathMeasure()
    pm.setPath(this, false)
    pm.getSegment(0f, pm.length * fraction, out, true)
    return out
}

/** The point [fraction] of the way along this path. */
fun Path.pointAt(fraction: Float): Offset {
    val pm = PathMeasure()
    pm.setPath(this, false)
    return pm.getPosition(pm.length * fraction.coerceIn(0f, 1f))
}

/**
 * A stroke with light: a wide soft bloom, a tighter halo, then the crisp
 * line. Reads as lit on dark and light themes alike.
 */
fun DrawScope.glowStroke(path: Path, brush: Brush, width0: Float, glow: Float = 1f, alpha: Float = 1f) {
    // Widths are designed at 2.6 px per dp; scale so lines keep their weight on any screen.
    val width = width0 * density / 2.6f
    if (glow > 0f) {
        drawPath(path, brush, alpha = 0.07f * glow * alpha,
            style = Stroke(width * 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, brush, alpha = 0.18f * glow * alpha,
            style = Stroke(width * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(path, brush, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

fun DrawScope.glowStroke(path: Path, color: Color, width: Float, glow: Float = 1f, alpha: Float = 1f) =
    glowStroke(path, androidx.compose.ui.graphics.SolidColor(color), width, glow, alpha)

/** A point of light: core, halo, bloom. */
fun DrawScope.spark(center: Offset, color: Color, radius0: Float, intensity: Float = 1f) {
    if (intensity <= 0f) return
    val radius = radius0 * density / 2.6f
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.35f * intensity), Color.Transparent), center, radius * 5f),
        radius * 5f, center
    )
    drawCircle(color.copy(alpha = 0.55f * intensity), radius * 1.8f, center)
    drawCircle(color.copy(alpha = intensity), radius, center)
}

/**
 * A smooth path through the samples (Catmull-Rom as cubics). Tangents follow
 * the neighbours, so a slope stays a clean slope; flat per-sample tangents
 * drew a fine staircase on every sloped curve.
 */
fun smoothPath(xs: FloatArray, ys: FloatArray): Path = Path().apply {
    if (xs.isEmpty()) return@apply
    moveTo(xs[0], ys[0])
    val n = xs.size
    for (i in 1 until n) {
        val i0 = (i - 2).coerceAtLeast(0); val i3 = (i + 1).coerceAtMost(n - 1)
        val c1x = xs[i - 1] + (xs[i] - xs[i0]) / 6f
        val c1y = ys[i - 1] + (ys[i] - ys[i0]) / 6f
        val c2x = xs[i] - (xs[i3] - xs[i - 1]) / 6f
        val c2y = ys[i] - (ys[i3] - ys[i - 1]) / 6f
        cubicTo(c1x, c1y, c2x, c2y, xs[i], ys[i])
    }
}

/** Closes [top] down to [baseY] for an area fill. */
fun areaUnder(top: Path, x0: Float, x1: Float, baseY: Float): Path = Path().apply {
    addPath(top)
    lineTo(x1, baseY)
    lineTo(x0, baseY)
    close()
}

enum class LabelAlign { Start, Center, End }

/**
 * Text in the app's own type, drawn into a scene. [anchor] is the baseline
 * start/centre/end per [align]; [rise] slides it up into place as it fades in.
 */
fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    anchor: Offset,
    style: TextStyle,
    color: Color,
    align: LabelAlign = LabelAlign.Start,
    alpha: Float = 1f,
    rise: Float = 0f
) {
    if (alpha <= 0.01f || text.isEmpty()) return
    val layout = measurer.measure(text, style.copy(color = color))
    val x = when (align) {
        LabelAlign.Start -> anchor.x
        LabelAlign.Center -> anchor.x - layout.size.width / 2f
        LabelAlign.End -> anchor.x - layout.size.width
    }
    val y = anchor.y - layout.firstBaseline + rise * (1f - alpha)
    drawText(layout, topLeft = Offset(x, y), alpha = alpha.coerceIn(0f, 1f))
}
