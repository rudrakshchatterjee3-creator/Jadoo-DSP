package com.jadoo.amp.ui

import android.graphics.Paint
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadoo.amp.audio.EqBands
import com.jadoo.amp.audio.LoudnessContour
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Animated diagrams for the help dialogs — one per DSP feature.
 *
 * These are explanatory, not decorative. Each one animates the actual
 * mechanism the feature implements: the exciter's sparkle sits in the real
 * 2-8 kHz band, the SBC diagram moves real bits between subbands, and the
 * Loudness Contour diagram draws the genuine ISO 226 curve by calling
 * [LoudnessContour.compensationDb] — the same function the DSP uses. A user
 * who watches the diagram learns what the toggle does; that's the bar.
 *
 * ## Motion rules
 *
 * Following Apple's fluid-interface guidance, adapted for a diagram that
 * nobody touches (so no springs or velocity handoff — there's no gesture):
 *
 *  - **Eased, never linear.** [FastOutSlowInEasing] on every value that
 *    represents a physical quantity; `LinearEasing` only for things that
 *    genuinely rotate or travel at constant speed (flow dots, sweeps).
 *  - **Slow cycles (2.4-5 s).** A help dialog is read, not watched. Fast
 *    loops pull the eye off the text they exist to explain.
 *  - **One idea per diagram.** Every diagram animates a single variable.
 *  - **Restraint in colour.** Theme roles only (primary / secondary /
 *    onSurfaceVariant), so all of it tracks Material You and both themes.
 *
 * ## Reduced motion
 *
 * When the system animator scale is 0 ("Remove animations" / Developer
 * options), [rememberMotionEnabled] returns false and every diagram renders a
 * single static frame at its most legible point rather than freezing at an
 * arbitrary one. The diagrams stay just as informative — they just stop
 * moving.
 */

// ── Shared scaffolding ────────────────────────────────────────────────────

/**
 * False when the user has animations switched off system-wide. Read once per
 * composition — this is a system setting, not something that changes while a
 * help dialog is open.
 */
@Composable
private fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }.getOrDefault(true)
    }
}

/**
 * A value cycling 0→1→0 over [periodMs], or pinned to [still] when motion is
 * off. [still] is chosen per diagram to be the frame that explains the
 * feature best, usually "effect fully applied".
 */
@Composable
private fun cycle(
    periodMs: Int,
    label: String,
    still: Float = 1f,
    easing: androidx.compose.animation.core.Easing = FastOutSlowInEasing
): Float {
    if (!rememberMotionEnabled()) return still
    val transition = rememberInfiniteTransition(label = label)
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = easing), RepeatMode.Reverse),
        label = label
    )
    return value
}

/**
 * A value ramping 0→1 and restarting, for things that travel in one direction
 * (flow dots, sweeps). Pinned mid-travel when motion is off.
 */
@Composable
private fun ramp(periodMs: Int, label: String, still: Float = 0.5f): Float {
    if (!rememberMotionEnabled()) return still
    val transition = rememberInfiniteTransition(label = label)
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = label
    )
    return value
}

/**
 * The frame every diagram sits in: a soft container with the same 24dp radius
 * used by the feature cards, so the dialog reads as one family. Inner padding
 * is generous on purpose — diagrams that touch their own edges look cramped
 * and make labels collide with the corner radius.
 */
@Composable
private fun IllustrationFrame(
    modifier: Modifier = Modifier,
    draw: DrawScope.(accent: Color, accentAlt: Color, muted: Color, surface: Color) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val accentAlt = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(surface)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) { draw(accent, accentAlt, muted, surface) }
    }
}

/**
 * Micro-label. Uppercase with a tracking bump reads as a diagram annotation,
 * not body copy. Pass [pillColor] (normally the frame's own [IllustrationFrame]
 * surface) to back the text with a solid pill sized to its measured width —
 * needed wherever a caption sits over a region the animation itself moves
 * through, so a bar or curve passing underneath never reads as mixed into the
 * label. Captions off to the side of the moving parts can skip it.
 */
private fun DrawScope.caption(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    align: Paint.Align = Paint.Align.LEFT,
    sizeSp: Float = 9.5f,
    alpha: Float = 0.75f,
    pillColor: Color? = null
) {
    drawIntoCanvas { canvas ->
        val upper = text.uppercase()
        val paint = Paint().apply {
            isAntiAlias = true
            this.color = color.copy(alpha = alpha).toArgb()
            textSize = sizeSp.sp.toPx()
            textAlign = align
            letterSpacing = 0.09f
            typeface = android.graphics.Typeface.DEFAULT
        }
        if (pillColor != null) {
            val textWidth = paint.measureText(upper)
            val padH = 6f
            val padV = 4f
            val left = when (align) {
                Paint.Align.LEFT -> x - padH
                Paint.Align.RIGHT -> x - textWidth - padH
                Paint.Align.CENTER -> x - textWidth / 2f - padH
            }
            val right = left + textWidth + padH * 2f
            val top = y - sizeSp.sp.toPx() - padV * 0.3f
            val bottom = y + padV
            canvas.nativeCanvas.drawRoundRect(
                left, top, right, bottom, 6f, 6f,
                Paint().apply { isAntiAlias = true; this.color = pillColor.toArgb() }
            )
        }
        canvas.nativeCanvas.drawText(upper, x, y, paint)
    }
}

/** Faint horizontal reference line — a datum, never a divider. */
private fun DrawScope.datum(y: Float, color: Color, dashed: Boolean = true) {
    drawLine(
        color = color.copy(alpha = 0.22f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.5f,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 8f)) else null
    )
}

/** Smooth 0..1 ramp between [edge0] and [edge1] — for masking a region of the spectrum. */
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Builds a smooth path through evenly spaced y samples across the full width. */
private fun DrawScope.curveThrough(points: FloatArray): Path = Path().apply {
    if (points.isEmpty()) return@apply
    val step = size.width / (points.size - 1)
    moveTo(0f, points[0])
    for (i in 1 until points.size) {
        val prevX = (i - 1) * step
        val x = i * step
        // Horizontal control points give a cosine-ish interpolation with no
        // overshoot — important for response curves, where an overshooting
        // spline would draw gain the filter doesn't actually have.
        cubicTo(prevX + step / 2f, points[i - 1], x - step / 2f, points[i], x, points[i])
    }
}

// ── 1. Hi-Res Upscaler ────────────────────────────────────────────────────

/** Spectrum whose top third lifts out of the noise floor as the effect engages. */
@Composable
internal fun HiResIllustration(modifier: Modifier = Modifier) {
    val lift = cycle(2800, "hires_lift")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val bars = 30
        val gap = size.width / bars
        val baseline = size.height * 0.88f
        val ceiling = size.height * 0.16f

        // The detail lossy compression discarded: a dashed ceiling the high
        // bands grow toward but never quite reach — this recovers presence,
        // it doesn't invent data.
        datum(ceiling, muted)
        caption("lost to compression", 0f, ceiling - 7f, muted, pillColor = surface)

        for (i in 0 until bars) {
            val t = i / (bars - 1f)
            // Typical program spectrum: energy falls with frequency.
            val base = (0.62f - 0.34f * t) * (0.86f + 0.14f * sin(i * 1.9f))
            val airMask = smoothstep(0.52f, 1f, t)
            val h = (base * (1f + lift * airMask * 1.5f)).coerceAtMost(0.78f)
            val top = baseline - h * (baseline - ceiling)
            val barColor = if (airMask > 0.01f) {
                Color(
                    red = lerp(muted.red, accent.red, airMask * lift),
                    green = lerp(muted.green, accent.green, airMask * lift),
                    blue = lerp(muted.blue, accent.blue, airMask * lift),
                    alpha = 0.45f + 0.55f * airMask * lift
                )
            } else muted.copy(alpha = 0.42f)
            drawLine(
                color = barColor,
                start = Offset(i * gap + gap * 0.5f, baseline),
                end = Offset(i * gap + gap * 0.5f, top),
                strokeWidth = gap * 0.42f
            )
        }
        // Air-band bracket, drawn last so it sits over the bars.
        val bracketStart = size.width * 0.55f
        drawLine(
            brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.0f), accentAlt)),
            start = Offset(bracketStart, baseline + 6f),
            end = Offset(size.width, baseline + 6f),
            strokeWidth = 2.5f
        )
        caption("air band", size.width, baseline + 20f, accentAlt, Paint.Align.RIGHT, alpha = 0.9f, pillColor = surface)
    }
}

// ── 2. DBFB ───────────────────────────────────────────────────────────────

/** A bass note whose weight is reinforced in proportion to how hard it hits. */
@Composable
internal fun DbfbIllustration(modifier: Modifier = Modifier) {
    val drive = cycle(2400, "dbfb_drive")
    IllustrationFrame(modifier) { accent, _, muted, surface ->
        val mid = size.height * 0.52f
        val n = 90
        val original = FloatArray(n + 1)
        val reinforced = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            // Envelope of a plucked bass note: fast attack, slow decay.
            val env = exp(-2.6f * t) * (1f - exp(-40f * t))
            val wave = sin(t * 13f)
            original[i] = mid - wave * env * size.height * 0.24f
            // Level-aware: the louder the note, the more weight is added.
            val boost = 1f + drive * 0.95f * env
            reinforced[i] = mid - wave * env * boost * size.height * 0.24f
        }

        datum(mid, muted, dashed = false)
        drawPath(curveThrough(original), muted.copy(alpha = 0.4f), style = Stroke(width = 2.5f))
        drawPath(
            curveThrough(reinforced),
            brush = Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.35f))),
            style = Stroke(width = 3.5f)
        )
        caption("input", 0f, size.height * 0.14f, muted)
    }
}

// ── 3. HDR Dynamics ───────────────────────────────────────────────────────

/** Brickwalled peaks stay put; the quiet material is pushed back down, restoring range. */
@Composable
internal fun HdrIllustration(modifier: Modifier = Modifier) {
    val expand = cycle(3000, "hdr_expand")
    IllustrationFrame(modifier) { accent, _, muted, surface ->
        val bars = 34
        val gap = size.width / bars
        val floorY = size.height * 0.84f
        val ceilY = size.height * 0.20f
        val threshold = floorY - (floorY - ceilY) * 0.42f

        datum(ceilY, muted)
        drawLine(
            color = accent.copy(alpha = 0.35f),
            start = Offset(0f, threshold),
            end = Offset(size.width, threshold),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f))
        )

        for (i in 0 until bars) {
            val seed = sin(i * 2.3f) * 0.5f + 0.5f
            // A brickwalled master: almost everything jammed near full scale.
            val loud = 0.72f + seed * 0.26f
            val level = if (seed < 0.34f) loud * 0.42f else loud
            val yFlat = floorY - level * (floorY - ceilY)
            // Downward expansion: only material BELOW the threshold moves, and
            // it moves down. Peaks are untouched — that's the whole point, and
            // it's why Restoration can't "squash" an already-loud master.
            val quiet = if (yFlat > threshold) (yFlat - threshold) else 0f
            val y = yFlat + quiet * expand * 0.55f
            val x = i * gap + gap * 0.5f
            drawLine(
                color = muted.copy(alpha = 0.22f),
                start = Offset(x, floorY), end = Offset(x, yFlat),
                strokeWidth = gap * 0.4f
            )
            drawLine(
                color = if (quiet > 0f) accent.copy(alpha = 0.55f + 0.35f * expand) else accent,
                start = Offset(x, floorY), end = Offset(x, y),
                strokeWidth = gap * 0.4f
            )
        }
        // Captions drawn last, on top of every bar, with a solid pill behind
        // them — the bar heights are randomized per-frame and would otherwise
        // paint straight over text placed earlier in z-order.
        caption("0 dBFS", 0f, ceilY - 7f, muted, pillColor = surface)
        caption("expander threshold", size.width, threshold - 7f, accent, Paint.Align.RIGHT, alpha = 0.85f, pillColor = surface)
    }
}

// ── 4. Analog Bass ────────────────────────────────────────────────────────

/** Input-vs-output transfer curve bending away from linear as drive rises. */
@Composable
internal fun AnalogBassIllustration(modifier: Modifier = Modifier) {
    val drive = cycle(3200, "analog_drive")
    val travel = ramp(2600, "analog_travel")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val pad = size.height * 0.1f
        val plot = Size(size.width, size.height - pad * 2f)
        fun px(t: Float) = t * plot.width
        fun py(v: Float) = pad + (1f - v) * plot.height

        // Linear reference — what a perfectly clean stage would do.
        drawLine(
            color = muted.copy(alpha = 0.3f),
            start = Offset(px(0f), py(0f)), end = Offset(px(1f), py(1f)),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 7f))
        )

        val k = 1.1f + drive * 2.9f
        val n = 60
        val pts = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            pts[i] = py(tanh(t * k) / tanh(k))
        }
        drawPath(
            curveThrough(pts),
            brush = Brush.horizontalGradient(listOf(accentAlt, accent)),
            style = Stroke(width = 3.5f)
        )

        // A signal travelling the curve: shows compression as the dot slows
        // vertically near the top while still moving at constant speed in x.
        val t = travel
        val dotX = px(t)
        val dotY = py(tanh(t * k) / tanh(k))
        drawCircle(accent.copy(alpha = 0.18f), radius = 11f, center = Offset(dotX, dotY))
        drawCircle(accent, radius = 4.5f, center = Offset(dotX, dotY))

        caption("linear", px(0.72f), py(0.9f), muted)
        caption("saturated", px(0.04f), py(0.86f), accent, alpha = 0.95f)
        caption("drive", px(0.04f), size.height, muted)
    }
}

// ── 5. Surround+ ──────────────────────────────────────────────────────────

/** The stereo image widens outward while the centre image stays pinned. */
@Composable
internal fun SurroundIllustrationAnimated(modifier: Modifier = Modifier) {
    val width = cycle(3400, "surround_width")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val cx = size.width / 2f
        val cy = size.height * 0.82f
        val baseR = size.height * 0.42f

        // Widening wavefronts, drawn as arcs sweeping out from the listener.
        for (ring in 0..2) {
            val r = baseR * (0.55f + ring * 0.33f) * (1f + width * 0.42f)
            val alpha = (0.34f - ring * 0.09f) * (0.55f + width * 0.45f)
            drawArc(
                color = accentAlt.copy(alpha = alpha),
                startAngle = 200f - width * 22f,
                sweepAngle = 140f + width * 44f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = 2.5f)
            )
        }

        // Centre channel: locked. Vocals never move, in the diagram or in the DSP.
        drawLine(
            color = accent.copy(alpha = 0.85f),
            start = Offset(cx, cy - baseR * 1.28f), end = Offset(cx, cy - size.height * 0.06f),
            strokeWidth = 2.5f
        )
        drawCircle(accent, radius = 5f, center = Offset(cx, cy - baseR * 1.28f))
        caption("vocals locked", cx, cy - baseR * 1.28f - 12f, accent, Paint.Align.CENTER, alpha = 1f)

        // Listener.
        drawCircle(muted.copy(alpha = 0.5f), radius = 7f, center = Offset(cx, cy))
        caption("L", cx - baseR * (1f + width * 0.42f) - 6f, cy + 4f, accentAlt, Paint.Align.RIGHT)
        caption("R", cx + baseR * (1f + width * 0.42f) + 6f, cy + 4f, accentAlt, Paint.Align.LEFT)
    }
}

// ── 6. Tube Warmth ────────────────────────────────────────────────────────

/** Low-end bloom rises and the top rolls off — the two halves of valve tone. */
@Composable
internal fun TubeWarmthIllustration(modifier: Modifier = Modifier) {
    val glow = cycle(3000, "tube_glow")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val mid = size.height * 0.55f
        val n = 48
        val pts = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            // Bloom around 60-100Hz (left), roll-off above ~10kHz (right).
            val bloom = exp(-((t - 0.14f) * 4.4f).let { it * it }) * 0.5f
            val rolloff = -smoothstep(0.68f, 1f, t) * 0.62f
            pts[i] = mid - (bloom + rolloff) * glow * size.height * 0.42f
        }
        datum(mid, muted, dashed = false)
        caption("flat", size.width, mid - 7f, muted, Paint.Align.RIGHT, alpha = 0.55f)

        // Warm filament glow behind the bloom — the only purely atmospheric
        // element in the whole set, and it's anchored to the frequency the
        // bloom actually sits at.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentAlt.copy(alpha = 0.20f * glow), Color.Transparent),
                center = Offset(size.width * 0.14f, mid - size.height * 0.2f * glow),
                radius = size.height * 0.55f
            ),
            radius = size.height * 0.55f,
            center = Offset(size.width * 0.14f, mid - size.height * 0.2f * glow)
        )
        drawPath(
            curveThrough(pts),
            brush = Brush.horizontalGradient(listOf(accentAlt, accent)),
            style = Stroke(width = 3.5f)
        )
        caption("bloom", size.width * 0.06f, size.height, muted)
        caption("soft roll-off", size.width, size.height, muted, Paint.Align.RIGHT)
    }
}

// ── 7. Mobile Bass ────────────────────────────────────────────────────────

/** The speaker can't move the fundamental, so its harmonics carry it instead. */
@Composable
internal fun MobileBassIllustration(modifier: Modifier = Modifier) {
    val pulse = cycle(2200, "mobile_pulse")
    IllustrationFrame(modifier) { accent, _, muted, surface ->
        val baseline = size.height * 0.82f
        val slots = listOf(0.16f, 0.42f, 0.62f, 0.80f)
        val labels = listOf("40 Hz", "80", "120", "160")

        // The fundamental: present in the recording, not reproducible by a
        // phone driver. Drawn hollow because the speaker never emits it.
        val fx = size.width * slots[0]
        val fh = size.height * 0.5f
        drawLine(
            color = muted.copy(alpha = 0.3f),
            start = Offset(fx, baseline), end = Offset(fx, baseline - fh),
            strokeWidth = 13f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 6f))
        )

        // Harmonics — small, cheap to reproduce, and enough for the ear to
        // reconstruct the missing fundamental.
        for (i in 1 until slots.size) {
            val x = size.width * slots[i]
            val h = size.height * (0.34f - (i - 1) * 0.07f) * (1f + pulse * 0.55f)
            drawLine(
                color = accent.copy(alpha = 0.55f + 0.4f * pulse),
                start = Offset(x, baseline), end = Offset(x, baseline - h),
                strokeWidth = 13f
            )
        }

        // The phantom fundamental the ear infers, pulsing in time with the
        // harmonics that create it.
        drawCircle(
            color = accent.copy(alpha = 0.10f + 0.16f * pulse),
            radius = size.height * (0.16f + 0.09f * pulse),
            center = Offset(fx, baseline - fh)
        )
        caption("phantom", fx, baseline - fh - size.height * 0.2f, accent, Paint.Align.CENTER, alpha = 0.9f)

        drawLine(muted.copy(alpha = 0.3f), Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.5f)
        labels.forEachIndexed { i, text ->
            caption(text, size.width * slots[i], baseline + 16f, muted, Paint.Align.CENTER)
        }
    }
}

// ── 8. Crossfeed ──────────────────────────────────────────────────────────

/** Each channel bleeds a little into the other, the way speakers reach both ears. */
@Composable
internal fun CrossfeedIllustration(modifier: Modifier = Modifier) {
    val flow = ramp(2800, "crossfeed_flow")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val leftX = size.width * 0.16f
        val rightX = size.width * 0.84f
        val topY = size.height * 0.26f
        val botY = size.height * 0.74f
        val r = size.height * 0.14f

        fun bleed(fromX: Float, fromY: Float, toX: Float, toY: Float): Path = Path().apply {
            moveTo(fromX, fromY)
            cubicTo(
                (fromX + toX) / 2f, fromY,
                (fromX + toX) / 2f, toY,
                toX, toY
            )
        }

        val lToR = bleed(leftX, topY, rightX, botY)
        val rToL = bleed(rightX, botY, leftX, topY)
        drawPath(lToR, accentAlt.copy(alpha = 0.35f), style = Stroke(width = 2f))
        drawPath(rToL, accentAlt.copy(alpha = 0.35f), style = Stroke(width = 2f))

        // Travelling dots make the direction of the bleed unambiguous — a
        // static curve between two circles reads as a connector, not a flow.
        for (k in 0..2) {
            val t = ((flow + k / 3f) % 1f)
            val e = FastOutSlowInEasing.transform(t)
            val bx = lerp(leftX, rightX, e)
            val by = lerp(topY, botY, e * e * (3f - 2f * e))
            drawCircle(accentAlt.copy(alpha = 0.75f * (1f - abs(t - 0.5f) * 1.2f)), 3.5f, Offset(bx, by))
            val bx2 = lerp(rightX, leftX, e)
            val by2 = lerp(botY, topY, e * e * (3f - 2f * e))
            drawCircle(accentAlt.copy(alpha = 0.75f * (1f - abs(t - 0.5f) * 1.2f)), 3.5f, Offset(bx2, by2))
        }

        drawCircle(accent.copy(alpha = 0.16f), r * 1.35f, Offset(leftX, topY))
        drawCircle(accent, r * 0.5f, Offset(leftX, topY))
        drawCircle(accent.copy(alpha = 0.16f), r * 1.35f, Offset(rightX, botY))
        drawCircle(accent, r * 0.5f, Offset(rightX, botY))
        caption("L", leftX, topY - r * 1.7f, accent, Paint.Align.CENTER, alpha = 1f)
        caption("R", rightX, botY + r * 2.2f, accent, Paint.Align.CENTER, alpha = 1f)
    }
}

// ── 9. Harmonic Exciter ───────────────────────────────────────────────────

/** Presence band lifts and sparkles; everything outside it is left alone. */
@Composable
internal fun ExciterIllustration(modifier: Modifier = Modifier) {
    val lift = cycle(2600, "exciter_lift")
    val rise = ramp(3000, "exciter_rise")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val baseline = size.height * 0.82f
        val bandStart = size.width * 0.44f
        val bandEnd = size.width * 0.78f

        drawRect(
            color = accent.copy(alpha = 0.07f + 0.05f * lift),
            topLeft = Offset(bandStart, 0f),
            size = Size(bandEnd - bandStart, baseline)
        )

        val bars = 30
        val gap = size.width / bars
        for (i in 0 until bars) {
            val x = i * gap + gap * 0.5f
            val inBand = x in bandStart..bandEnd
            val base = (0.5f - 0.2f * (i / bars.toFloat())) * (0.85f + 0.15f * sin(i * 2.1f))
            val h = base * (if (inBand) 1f + lift * 0.6f else 1f)
            drawLine(
                color = if (inBand) accent.copy(alpha = 0.5f + 0.45f * lift) else muted.copy(alpha = 0.32f),
                start = Offset(x, baseline), end = Offset(x, baseline - h * baseline),
                strokeWidth = gap * 0.42f
            )
        }

        // Sparkle: three particles rising inside the band on offset phases.
        for (k in 0..2) {
            val t = (rise + k / 3f) % 1f
            val sx = bandStart + (bandEnd - bandStart) * (0.22f + 0.28f * k)
            val sy = baseline - t * baseline * 0.85f
            drawCircle(accentAlt.copy(alpha = (1f - t) * 0.85f), 2.5f + (1f - t) * 1.5f, Offset(sx, sy))
        }

        drawLine(muted.copy(alpha = 0.3f), Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.5f)
        caption("2 kHz", bandStart, baseline + 16f, accent, Paint.Align.CENTER, alpha = 0.9f)
        caption("8 kHz", bandEnd, baseline + 16f, accent, Paint.Align.CENTER, alpha = 0.9f)
        caption("presence", (bandStart + bandEnd) / 2f, size.height, accent, Paint.Align.CENTER, alpha = 0.95f)
    }
}

// ── 10. Parametric EQ ─────────────────────────────────────────────────────

/** One band morphing through peak → high shelf → notch, the way the type picker does. */
@Composable
internal fun ParametricEqIllustration(modifier: Modifier = Modifier) {
    val morph = ramp(5200, "peq_morph", still = 0.0f)
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val mid = size.height * 0.55f
        val n = 64
        // Three shapes, crossfaded round-robin. Each is the real response
        // family it names, not a decorative squiggle.
        fun peak(t: Float) = exp(-((t - 0.5f) * 6.5f).let { it * it })
        fun shelf(t: Float) = smoothstep(0.35f, 0.78f, t)
        fun notch(t: Float) = -exp(-((t - 0.5f) * 11f).let { it * it })

        val phase = morph * 3f
        val idx = phase.toInt() % 3
        val frac = FastOutSlowInEasing.transform(phase - phase.toInt())
        val pts = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            val a = when (idx) { 0 -> peak(t); 1 -> shelf(t); else -> notch(t) }
            val b = when (idx) { 0 -> shelf(t); 1 -> notch(t); else -> peak(t) }
            pts[i] = mid - lerp(a, b, frac) * size.height * 0.34f
        }

        datum(mid, muted, dashed = false)
        drawPath(
            curveThrough(pts),
            brush = Brush.horizontalGradient(listOf(accentAlt, accent)),
            style = Stroke(width = 3.5f)
        )
        // The draggable node, sitting on the curve at the band's centre.
        val nodeY = pts[n / 2]
        drawCircle(accent.copy(alpha = 0.18f), 12f, Offset(size.width * 0.5f, nodeY))
        drawCircle(accent, 5f, Offset(size.width * 0.5f, nodeY))

        val name = when (idx) { 0 -> "peak → shelf"; 1 -> "shelf → notch"; else -> "notch → peak" }
        caption(name, 0f, size.height, muted)
        caption("8 bands", size.width, size.height, muted, Paint.Align.RIGHT)
    }
}

// ── 11. SBC Enhancement ───────────────────────────────────────────────────

/** Bits migrating out of the bass subbands and into the starved top two. */
@Composable
internal fun SbcIllustration(modifier: Modifier = Modifier) {
    val shift = cycle(2800, "sbc_shift")
    IllustrationFrame(modifier) { accent, _, muted, surface ->
        val bands = 8
        val baseline = size.height * 0.80f
        val slotW = size.width / bands
        val blockH = size.height * 0.085f

        // SBC's allocator spends bits where the energy is — which starves the
        // top subbands. Pre-emphasis biases the input so it spends more there.
        val natural = intArrayOf(6, 6, 5, 4, 3, 2, 1, 1)
        val boosted = intArrayOf(5, 5, 4, 3, 3, 3, 3, 3)

        for (b in 0 until bands) {
            val count = lerp(natural[b].toFloat(), boosted[b].toFloat(), shift)
            val full = count.toInt()
            val partial = count - full
            for (k in 0 until full) {
                drawRoundRect(
                    color = if (b >= 6) accent.copy(alpha = 0.55f + 0.4f * shift) else accent.copy(alpha = 0.42f),
                    topLeft = Offset(b * slotW + slotW * 0.22f, baseline - (k + 1) * blockH + blockH * 0.16f),
                    size = Size(slotW * 0.56f, blockH * 0.7f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                )
            }
            if (partial > 0.02f) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.42f * partial),
                    topLeft = Offset(b * slotW + slotW * 0.22f, baseline - (full + 1) * blockH + blockH * 0.16f),
                    size = Size(slotW * 0.56f, blockH * 0.7f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                )
            }
        }

        drawLine(muted.copy(alpha = 0.3f), Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.5f)
        caption("subband 1", 0f, baseline + 16f, muted)
        caption("8", size.width, baseline + 16f, muted, Paint.Align.RIGHT)
    }
}

// ── 12. Loudness Contour ──────────────────────────────────────────────────

/**
 * The genuine ISO 226 correction, computed by the same [LoudnessContour]
 * function the DSP runs. As the volume marker slides down, the curve is
 * recomputed at that level — what's drawn is exactly what would be applied.
 */
@Composable
internal fun LoudnessIllustration(modifier: Modifier = Modifier) {
    val drop = cycle(4000, "loudness_drop")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val reference = 80f
        val current = lerp(reference, 42f, drop)
        val comp = LoudnessContour.compensationDb(reference, current, 1f)

        val plotTop = size.height * 0.10f
        val plotBottom = size.height * 0.72f
        val zeroY = plotBottom
        val maxDb = 14f

        datum(zeroY, muted, dashed = false)

        val pts = FloatArray(EqBands.count)
        for (i in 0 until EqBands.count) {
            pts[i] = zeroY - (comp[i] / maxDb).coerceIn(0f, 1f) * (zeroY - plotTop)
        }
        val path = curveThrough(pts)
        // Fill under the curve so "how much is being added" reads at a glance.
        val filled = Path().apply {
            addPath(path)
            lineTo(size.width, zeroY)
            lineTo(0f, zeroY)
            close()
        }
        drawPath(
            filled,
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.02f)),
                startY = plotTop, endY = zeroY
            )
        )
        drawPath(
            path,
            brush = Brush.horizontalGradient(listOf(accent, accentAlt, accent)),
            style = Stroke(width = 3.5f)
        )
        // Drawn after the curve, with a pill behind it — the treble end of
        // the curve sits right on the zero line and would otherwise touch
        // the text.
        caption("0 dB", size.width, zeroY - 7f, muted, Paint.Align.RIGHT, alpha = 0.6f, pillColor = surface)

        // Volume marker, moving in lockstep with the curve it produces.
        val trackY = size.height * 0.92f
        drawLine(muted.copy(alpha = 0.25f), Offset(0f, trackY), Offset(size.width, trackY), strokeWidth = 3f)
        val knobX = lerp(size.width, size.width * 0.12f, drop)
        drawLine(
            brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.5f), accent)),
            start = Offset(0f, trackY), end = Offset(knobX, trackY), strokeWidth = 3f
        )
        drawCircle(accent, 5.5f, Offset(knobX, trackY))
        caption("25 Hz", 0f, plotBottom + 16f, muted, pillColor = surface)
        caption("1 kHz · 0 dB always", size.width * 0.5f, plotBottom + 16f, muted, Paint.Align.CENTER, pillColor = surface)
        caption("16 kHz", size.width, plotBottom + 16f, muted, Paint.Align.RIGHT, pillColor = surface)
    }
}

// ── 13. Per-app profiles ──────────────────────────────────────────────────

/** Two apps, two curves — the DSP follows whichever one is playing. */
@Composable
internal fun PerAppIllustration(modifier: Modifier = Modifier) {
    val swap = cycle(3600, "perapp_swap")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val tileH = size.height * 0.26f
        val tileW = size.width * 0.4f
        val gapX = size.width * 0.08f
        val leftX = size.width * 0.5f - tileW - gapX / 2f
        val rightX = size.width * 0.5f + gapX / 2f

        fun tile(x: Float, active: Float, tint: Color, name: String) {
            drawRoundRect(
                color = tint.copy(alpha = 0.10f + 0.22f * active),
                topLeft = Offset(x, 0f),
                size = Size(tileW, tileH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tileH * 0.34f, tileH * 0.34f)
            )
            drawRoundRect(
                color = tint.copy(alpha = 0.25f + 0.6f * active),
                topLeft = Offset(x, 0f),
                size = Size(tileW, tileH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tileH * 0.34f, tileH * 0.34f),
                style = Stroke(width = 1.5f)
            )
            caption(name, x + tileW / 2f, tileH * 0.63f, tint, Paint.Align.CENTER, alpha = 0.6f + 0.4f * active)
        }
        tile(leftX, 1f - swap, accent, "music")
        tile(rightX, swap, accentAlt, "podcast")

        // The active tile's curve. Music keeps its full smile; speech tilts
        // toward the midrange — the actual reason to want this feature.
        val mid = size.height * 0.74f
        val n = 48
        val pts = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            val music = (cos(t * 6.28f) * 0.5f + 0.1f)
            val speech = exp(-((t - 0.52f) * 3.6f).let { it * it }) - 0.42f
            pts[i] = mid - lerp(music, speech, swap) * size.height * 0.2f
        }
        datum(mid, muted, dashed = false)
        drawPath(
            curveThrough(pts),
            brush = Brush.horizontalGradient(
                listOf(
                    Color(
                        lerp(accent.red, accentAlt.red, swap),
                        lerp(accent.green, accentAlt.green, swap),
                        lerp(accent.blue, accentAlt.blue, swap)
                    ),
                    accent.copy(alpha = 0.4f)
                )
            ),
            style = Stroke(width = 3.5f)
        )
    }
}

// ── 14. Device profiles / content channel ─────────────────────────────────

/** Tuning arriving over the air and landing in the app, without a reinstall. */
@Composable
internal fun ContentChannelIllustration(modifier: Modifier = Modifier) {
    val flow = ramp(3000, "content_flow")
    IllustrationFrame(modifier) { accent, accentAlt, muted, surface ->
        val cloudY = size.height * 0.2f
        val deviceY = size.height * 0.78f
        val cx = size.width * 0.5f

        // Source.
        drawCircle(accentAlt.copy(alpha = 0.14f), size.height * 0.16f, Offset(cx, cloudY))
        drawCircle(accentAlt, 5f, Offset(cx, cloudY))
        caption("tuning · presets · devices", cx, cloudY - size.height * 0.2f, muted, Paint.Align.CENTER)

        // Packets descending one after another.
        for (k in 0..2) {
            val t = (flow + k / 3f) % 1f
            val y = lerp(cloudY + size.height * 0.1f, deviceY - size.height * 0.14f, t)
            val a = (1f - abs(t - 0.5f) * 1.3f).coerceIn(0f, 1f)
            drawRoundRect(
                color = accent.copy(alpha = 0.75f * a),
                topLeft = Offset(cx - size.width * 0.035f, y),
                size = Size(size.width * 0.07f, size.height * 0.05f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
        }

        // Destination — the app, unchanged. No install, no restart.
        drawRoundRect(
            color = accent.copy(alpha = 0.12f),
            topLeft = Offset(cx - size.width * 0.16f, deviceY - size.height * 0.1f),
            size = Size(size.width * 0.32f, size.height * 0.2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.07f, size.height * 0.07f)
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.6f),
            topLeft = Offset(cx - size.width * 0.16f, deviceY - size.height * 0.1f),
            size = Size(size.width * 0.32f, size.height * 0.2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.07f, size.height * 0.07f),
            style = Stroke(width = 1.5f)
        )
        caption("no reinstall", cx, size.height, accent, Paint.Align.CENTER, alpha = 0.95f)
    }
}
