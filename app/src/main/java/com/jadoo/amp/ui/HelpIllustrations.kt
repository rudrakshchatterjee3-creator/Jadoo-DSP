package com.jadoo.amp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.jadoo.amp.R
import com.jadoo.amp.audio.DigitalFilterEngine
import com.jadoo.amp.audio.EqBands
import com.jadoo.amp.audio.LoudnessContour
import com.jadoo.amp.ui.components.Cine
import com.jadoo.amp.ui.components.LabelAlign
import com.jadoo.amp.ui.components.areaUnder
import com.jadoo.amp.ui.components.breathe
import com.jadoo.amp.ui.components.frac
import com.jadoo.amp.ui.components.glowStroke
import com.jadoo.amp.ui.components.hash01
import com.jadoo.amp.ui.components.label
import com.jadoo.amp.ui.components.mix
import com.jadoo.amp.ui.components.pointAt
import com.jadoo.amp.ui.components.rememberSceneClock
import com.jadoo.amp.ui.components.seg
import com.jadoo.amp.ui.components.smoothPath
import com.jadoo.amp.ui.components.spark
import com.jadoo.amp.ui.components.trimmed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * The help sheets' diagrams, one per feature. Each is a short scene that
 * explains the real mechanism, choreographed like a title sequence:
 *
 *  1. **Reveal** (0-0.9 s): the stage and its grid fade up.
 *  2. **Draw** (0.2-1.5 s): the "as recorded" state draws itself on.
 *  3. **Act** (1.2-2.8 s): the feature switches on and the picture changes,
 *     eased, once.
 *  4. **Hold**: the "on" picture stays, with a slow, quiet life (a playhead,
 *     a ripple, a drifting spark) so the sheet never looks frozen, and never
 *     competes with the text below it. No replay is needed.
 *
 * Words live in the legend under the stage, in the app's own type, never
 * squeezed onto the drawing. Numbers (Hz, dBFS) are the only text on stage.
 *
 * Every picture must stay true to the DSP (CLAUDE.md rule 8): nothing here
 * saturates, generates harmonics or restores lost detail, because the engine
 * can't. Under reduced motion each scene shows its settled frame.
 */

// ── Stage ──────────────────────────────────────────────────────────────────

internal enum class Ink { Muted, Accent, Accent2 }

internal class Palette(
    val accent: Color,
    val accent2: Color,
    val muted: Color,
    val faint: Color,
    val surface: Color
) {
    fun of(ink: Ink) = when (ink) { Ink.Muted -> muted; Ink.Accent -> accent; Ink.Accent2 -> accent2 }
}

/** Everything a scene's draw block needs. */
internal class SceneScope(
    val t: Float,
    val p: Palette,
    val text: TextMeasurer,
    val micro: TextStyle,
    val small: TextStyle
) {
    val reveal get() = seg(t, 0f, 0.9f)
    val drawOn get() = seg(t, 0.2f, 1.3f, Cine.Draw)
    val on get() = seg(t, 1.25f, 1.5f, Cine.InOut)
    /** 0 until the act is over, then 1: gates the living hold. */
    val live get() = seg(t, 2.6f, 1.2f)
    /** Seconds into the living hold. */
    val hold get() = (t - 2.6f).coerceAtLeast(0f)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stage(
    modifier: Modifier,
    legend: List<Pair<Ink, String>>,
    draw: DrawScope.(SceneScope) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val palette = Palette(
        accent = cs.primary,
        accent2 = cs.tertiary,
        muted = cs.onSurfaceVariant,
        faint = cs.outlineVariant,
        surface = cs.surfaceContainerHigh
    )
    val clock = rememberSceneClock()
    val measurer = rememberTextMeasurer()
    val micro = MaterialTheme.typography.labelSmall
    val small = MaterialTheme.typography.labelMedium
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(cs.surfaceContainerHigh, cs.surfaceContainer)))
    ) {
        Canvas(
            Modifier.fillMaxWidth().weight(1f)
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp)
        ) {
            val scene = SceneScope(clock.value, palette, measurer, micro, small)
            // A soft key light behind the action, breathing very slowly.
            val glowAt = Offset(size.width * (0.55f + 0.08f * sin(scene.t * 0.35f)), size.height * 0.35f)
            drawCircle(
                Brush.radialGradient(listOf(palette.accent.copy(alpha = 0.10f * scene.reveal), Color.Transparent),
                    glowAt, size.maxDimension * 0.6f),
                size.maxDimension * 0.6f, glowAt
            )
            draw(scene)
        }
        if (legend.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                legend.forEachIndexed { i, (ink, text) ->
                    Row(
                        Modifier.graphicsLayer {
                            val a = seg(clock.value, 1.6f + i * 0.18f, 0.7f)
                            alpha = a
                            translationY = (1f - a) * 6.dp.toPx()
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(14.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.of(ink)))
                        Spacer(Modifier.size(8.dp))
                        Text(text, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── Shared geometry ────────────────────────────────────────────────────────

private const val F_LO = 20f
private const val F_HI = 20000f

/** x for [hz] on a log axis from [lo] to [hi]. */
private fun DrawScope.xHz(hz: Float, lo: Float = F_LO, hi: Float = F_HI) =
    size.width * (ln(hz / lo) / ln(hi / lo))

private fun DrawScope.hzAt(x: Float, lo: Float = F_LO, hi: Float = F_HI) =
    lo * exp(ln(hi / lo) * (x / size.width))

private fun smooth01(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun octaves(a: Float, b: Float) = ln(b / a) / ln(2f)

/** Samples [fn] across the width (log frequency) and returns a smooth path. */
private fun DrawScope.response(n: Int = 72, lo: Float = F_LO, hi: Float = F_HI, y: (Float) -> Float): Path {
    val xs = FloatArray(n + 1) { size.width * it / n }
    val ys = FloatArray(n + 1) { y(hzAt(xs[it], lo, hi)) }
    return smoothPath(xs, ys)
}

/** Faint frequency grid with Hz ticks, grown in from the centre line. */
private fun DrawScope.freqGrid(s: SceneScope, baseY: Float, ticks: List<Pair<Float, String>>, lo: Float = F_LO, hi: Float = F_HI) {
    val r = s.reveal
    ticks.forEach { (hz, name) ->
        val x = xHz(hz, lo, hi)
        val half = (baseY) * 0.5f * r
        drawLine(s.p.faint.copy(alpha = 0.45f), Offset(x, baseY / 2f - half), Offset(x, baseY / 2f + half), 1f)
        s.run { label(text, name, Offset(x, size.height), micro, p.muted, LabelAlign.Center, alpha = 0.7f * r) }
    }
}

private fun DrawScope.hline(y: Float, color: Color, alpha: Float, dashed: Boolean = false, width: Float = 1.2f) {
    drawLine(
        color.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), width,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5f, 7f)) else null
    )
}

/** Particles rising out of a band, for "air" and "presence". */
private fun DrawScope.rising(s: SceneScope, n: Int, xLo: Float, xHi: Float, yFrom: (Float) -> Float, top: Float, color: Color, intensity: Float, speed: Float = 0.28f) {
    if (intensity <= 0f) return
    for (i in 0 until n) {
        val x = mix(xLo, xHi, hash01(i, 1))
        val life = frac(s.t * speed * (0.7f + 0.6f * hash01(i, 2)) + hash01(i, 3))
        val y0 = yFrom(x)
        val y = mix(y0, top, life)
        val a = intensity * sin(life * PI.toFloat()) * 0.9f
        spark(Offset(x + 3f * sin(s.t * 1.7f + i), y), color, 1.6f + hash01(i, 4) * 1.2f, a)
    }
}

// ── 1. Hi-Res Upscaler ─────────────────────────────────────────────────────

/** The air above 5.2 kHz lifts in three steps; nothing below moves. */
@Composable
internal fun HiResIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Muted to stringResource(R.string.ill2_as_recorded), Ink.Accent to stringResource(R.string.ill2_air_lift))
) { s ->
    val base = size.height - 17.dp.toPx()
    freqGrid(s, base, listOf(100f to "100", 1000f to "1k", 10000f to "10k"))
    val top = 6f
    fun yDb(db: Float) = mix(top, base, (8f - db) / 40f)
    fun music(f: Float) = -4.4f * octaves(160f, f.coerceAtLeast(160f)) - 5f * octaves(f.coerceAtMost(45f), 45f)
    fun lift(f: Float) = (2.5f * smooth01(-0.25f, 0.25f, octaves(5200f, f)) +
        1.5f * smooth01(-0.25f, 0.25f, octaves(9600f, f)) +
        1.5f * smooth01(-0.25f, 0.25f, octaves(14500f, f))) * 1.8f
    val before = response { yDb(music(it)) }
    val after = response { yDb(music(it) + lift(it) * s.on) }
    drawPath(areaUnder(before, 0f, size.width, base),
        Brush.verticalGradient(listOf(s.p.muted.copy(alpha = 0.16f * s.reveal), Color.Transparent), top, base))
    val x52 = xHz(5200f)
    // The added air, as light between the two curves.
    if (s.on > 0f) {
        drawPath(areaUnder(after, 0f, size.width, base),
            Brush.horizontalGradient(listOf(Color.Transparent, s.p.accent.copy(alpha = 0.30f * s.on)), x52 - 60f, size.width))
    }
    glowStroke(before.trimmed(s.drawOn), s.p.muted.copy(alpha = 0.55f), 2f, glow = 0f)
    if (s.on > 0f) {
        glowStroke(after, Brush.horizontalGradient(listOf(s.p.muted.copy(alpha = 0f), s.p.accent), x52 - 80f, x52 + 40f), 2.6f, glow = s.on)
    }
    listOf(5200f, 9600f, 14500f).forEach { f ->
        val x = xHz(f)
        drawLine(s.p.accent.copy(alpha = 0.22f * s.on), Offset(x, top), Offset(x, base), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 6f)))
    }
    rising(s, 14, x52, size.width, { yDb(music(hzAt(it)) + lift(hzAt(it))) }, top, s.p.accent, s.live)
}

// ── 2. DBFB ────────────────────────────────────────────────────────────────

private fun bassEnvelope(x: Float, scroll: Float): Float {
    // Three notes per screen, repeating, so the conveyor never seams.
    val amps = floatArrayOf(0.62f, 1f, 0.78f)
    var e = 0f
    for (k in -1..3) {
        val idx = ((k % 3) + 3) % 3
        val start = (k + 0.08f) / 3f - scroll
        val d = x - start
        if (d >= 0f) e += amps[idx] * (1f - exp(-d * 120f)) * exp(-d * 9.5f)
    }
    return e.coerceAtMost(1f)
}

/** Draws a bass waveform inside [env] (0..1) around [mid]. */
private fun DrawScope.waveform(env: (Float) -> Float, mid: Float, amp: Float, cycles: Float, phase: Float): Path {
    val n = 260
    return Path().apply {
        for (i in 0..n) {
            val u = i / n.toFloat()
            val y = mid - env(u) * amp * sin((u * cycles + phase) * 2f * PI.toFloat())
            if (i == 0) moveTo(0f, y) else lineTo(size.width * u, y)
        }
    }
}

private fun DrawScope.envelopeArea(env: (Float) -> Float, mid: Float, amp: Float): Path {
    val n = 120
    return Path().apply {
        moveTo(0f, mid - env(0f) * amp)
        for (i in 1..n) { val u = i / n.toFloat(); lineTo(size.width * u, mid - env(u) * amp) }
        for (i in n downTo 0) { val u = i / n.toFloat(); lineTo(size.width * u, mid + env(u) * amp) }
        close()
    }
}

/**
 * Bass notes, lifted: a static post-gain with gentle compression, so the
 * body of a note rises and its loudest hit rises least (DBFB is a leveler,
 * not an expander: a harder hit gets less, never more).
 */
@Composable
internal fun DbfbIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Muted to stringResource(R.string.ill2_as_recorded), Ink.Accent to stringResource(R.string.ill2_dbfb))
) { s ->
    val mid = size.height * 0.5f
    val amp = size.height * 0.40f
    val scroll = s.hold * 0.07f
    val orig = { u: Float -> bassEnvelope(u, scroll) * s.reveal }
    val proc = { u: Float -> val e = orig(u); (e * (1f + 0.55f * s.on * (1f - e * e))).coerceAtMost(1f) }
    hline(mid, s.p.faint, 0.6f * s.reveal)
    drawPath(envelopeArea(proc, mid, amp),
        Brush.verticalGradient(listOf(s.p.accent.copy(alpha = 0.20f * s.on), s.p.accent.copy(alpha = 0.05f * s.on), s.p.accent.copy(alpha = 0.20f * s.on)), mid - amp, mid + amp))
    glowStroke(waveform(orig, mid, amp, 22f, s.hold * 0.4f).trimmed(s.drawOn), s.p.muted.copy(alpha = 0.45f), 1.4f, glow = 0f)
    if (s.on > 0f) glowStroke(waveform(proc, mid, amp, 22f, s.hold * 0.4f), s.p.accent, 1.8f, glow = 0.6f * s.on, alpha = s.on)
    // The original's envelope, dashed, so the held-back peaks read.
    val n = 90
    val outline = Path().apply { for (i in 0..n) { val u = i / n.toFloat(); val y = mid - orig(u) * amp; if (i == 0) moveTo(0f, y) else lineTo(size.width * u, y) } }
    drawPath(outline.trimmed(s.drawOn), s.p.muted.copy(alpha = 0.5f), style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f))))
    // Playhead.
    if (s.live > 0f) {
        val u = frac(s.hold * 0.22f)
        val x = size.width * u
        drawLine(Brush.verticalGradient(listOf(Color.Transparent, s.p.accent.copy(alpha = 0.5f * s.live), Color.Transparent), mid - amp, mid + amp),
            Offset(x, mid - amp), Offset(x, mid + amp), 1.5f)
        spark(Offset(x, mid - proc(u) * amp), s.p.accent, 2.4f, s.live)
    }
}

// ── 3. HDR Dynamics ────────────────────────────────────────────────────────

/** A loud master scrolling by: everything under the threshold sinks, peaks stay. */
@Composable
internal fun HdrIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Accent2 to stringResource(R.string.ill2_hdr_peaks), Ink.Accent to stringResource(R.string.ill2_hdr_quiet))
) { s ->
    val floor = size.height - 4f
    val ceil = 14f
    val threshold = mix(floor, ceil, 0.46f)
    val bars = 32
    val step = size.width / bars
    val pos = s.hold * 2.2f
    val shift = frac(pos)
    val first = kotlin.math.floor(pos).toInt()
    for (j in -1..bars) {
        val i = first + j
        val r = hash01(i, 7)
        val breath = (i % 9 == 4) || (i % 13 == 7)
        val v = if (breath) 0.22f + r * 0.18f else 0.70f + r * 0.28f
        val x = (j - shift + 0.5f) * step
        val grow = seg(s.t, 0.15f + (j.coerceAtLeast(0) * 0.025f), 0.7f)
        val yFlat = mix(floor, ceil, v * grow)
        val below = (yFlat - threshold).coerceAtLeast(0f)
        val y = yFlat + below * 0.75f * s.on
        val w = step * 0.46f
        if (below > 0f) {
            drawRoundRect(s.p.muted.copy(alpha = 0.22f), Offset(x - w / 2f, yFlat), Size(w, floor - yFlat), CornerRadius(w / 2f),
                style = Stroke(1f))
        }
        val c = if (below > 0f) s.p.accent else s.p.accent2
        drawRoundRect(
            Brush.verticalGradient(listOf(c, c.copy(alpha = 0.25f)), y, floor),
            Offset(x - w / 2f, y), Size(w, (floor - y).coerceAtLeast(w)), CornerRadius(w / 2f)
        )
    }
    hline(ceil, s.p.muted, 0.35f * s.reveal, dashed = true)
    hline(threshold, s.p.accent, 0.55f * s.reveal, dashed = true)
    s.run {
        label(text, "0 dBFS", Offset(0f, ceil - 5f), micro, p.muted, alpha = 0.8f * reveal)
        label(text, "−38 dBFS", Offset(size.width, threshold - 6f), micro, p.accent, LabelAlign.End, alpha = reveal)
    }
}

// ── 4. Analog Bass ─────────────────────────────────────────────────────────

/**
 * Left: one bass note through the compressor and its makeup gain: the body
 * lifts, the attack peak is held, the long release blooms. Right: the
 * Pultec post-EQ, a low shelf boost with a dip just above it.
 */
@Composable
internal fun AnalogBassIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Accent to stringResource(R.string.ill2_analog_body), Ink.Accent2 to stringResource(R.string.ill2_pultec))
) { s ->
    val split = size.width * 0.58f
    val mid = size.height * 0.5f
    val amp = size.height * 0.42f
    val cycle = 2.4f
    val noteT = if (s.live > 0f) frac(s.hold / cycle) else 0f
    fun env(u: Float): Float {
        val d = u - 0.06f
        return if (d < 0f) 0f else (1f - exp(-d * 60f)) * exp(-d * 3.1f)
    }
    fun processed(u: Float): Float {
        val e = env(u)
        // Above the knee the compressor takes gain off; the makeup (Warmth)
        // then lifts everything by the same amount, so the body comes up
        // about 2.5 dB and the attack peak lands where it was.
        val comp = if (e > 0.55f) 0.55f + (e - 0.55f) * 0.45f else e
        return mix(e, (comp * 1.33f).coerceAtMost(1f), s.on)
    }
    val reveal = s.reveal
    fun panel(fn: (Float) -> Float): (Float) -> Float = { x -> fn(x) * reveal }
    // Left panel, drawn into its own width.
    drawContext.canvas.save()
    drawContext.canvas.clipRect(0f, 0f, split - 12f, size.height)
    val w = split - 12f
    fun area(fn: (Float) -> Float): Path {
        val n = 100
        return Path().apply {
            moveTo(0f, mid)
            for (i in 0..n) { val u = i / n.toFloat(); lineTo(w * u, mid - fn(u) * amp) }
            for (i in n downTo 0) { val u = i / n.toFloat(); lineTo(w * u, mid + fn(u) * amp) }
            close()
        }
    }
    hline(mid, s.p.faint, 0.6f * reveal)
    drawPath(area(panel(::env)), s.p.muted.copy(alpha = 0.10f))
    drawPath(area(panel(::env)), s.p.muted.copy(alpha = 0.5f), style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f))))
    drawPath(area(panel(::processed)),
        Brush.horizontalGradient(listOf(s.p.accent.copy(alpha = 0.55f * s.on), s.p.accent.copy(alpha = 0.12f * s.on)), 0f, w))
    if (s.live > 0f) {
        val x = w * noteT
        spark(Offset(x, mid - processed(noteT) * reveal * amp), s.p.accent, 2.4f, s.live * (1f - noteT * 0.6f))
    }
    drawContext.canvas.restore()
    drawLine(s.p.faint.copy(alpha = 0.5f * reveal), Offset(split, 10f), Offset(split, size.height - 10f), 1f)

    // Right panel: Pultec response, log 20 Hz - 1 kHz.
    val left = split + 14f
    val pw = size.width - left
    val zero = mid
    fun yDb(db: Float) = zero - db * (size.height * 0.055f)
    fun pultec(f: Float): Float {
        val boost = 6f / (1f + (f / 90f).let { it * it })
        val dip = -3.5f * exp(-0.5f * (octaves(240f, f) / 0.55f).let { it * it })
        val warmth = 1.2f * exp(-0.5f * (octaves(420f, f) / 0.6f).let { it * it })
        return boost + dip + warmth
    }
    val n = 60
    val xs = FloatArray(n + 1) { left + pw * it / n }
    val f = FloatArray(n + 1) { 20f * exp(ln(1000f / 20f) * it / n) }
    val flat = smoothPath(xs, FloatArray(n + 1) { yDb(0f) })
    val curve = smoothPath(xs, FloatArray(n + 1) { yDb(pultec(f[it]) * s.on) })
    drawLine(s.p.faint.copy(alpha = 0.6f * reveal), Offset(left, zero), Offset(size.width, zero), 1f)
    glowStroke(flat.trimmed(s.drawOn), s.p.muted.copy(alpha = 0.4f), 1.4f, glow = 0f)
    if (s.on > 0f) glowStroke(curve, Brush.horizontalGradient(listOf(s.p.accent2, s.p.accent), left, size.width), 2.4f, glow = s.on)
    s.run {
        label(text, "60 Hz", Offset(left + pw * (ln(60f / 20f) / ln(50f)), size.height), micro, p.muted, LabelAlign.Center, alpha = 0.7f * reveal)
    }
}

// ── 5. Surround+ ───────────────────────────────────────────────────────────

/** Seen from above: the sides move out, the voice stays dead ahead. */
@Composable
internal fun SurroundIllustrationAnimated(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Accent to stringResource(R.string.ill2_vocals_centre), Ink.Accent2 to stringResource(R.string.ill2_wider))
) { s ->
    val cx = size.width / 2f
    val head = Offset(cx, size.height * 0.84f)
    val voice = Offset(cx, size.height * 0.16f)
    val spread = mix(size.width * 0.17f, size.width * 0.42f, s.on)
    val sideY = size.height * 0.34f + 14f * s.on
    val l = Offset(cx - spread, sideY)
    val r = Offset(cx + spread, sideY)
    // The stage arc through the three sources.
    val arc = Path().apply { moveTo(l.x, l.y); quadraticTo(cx, voice.y - 18f, r.x, r.y) }
    drawPath(arc.trimmed(s.drawOn), s.p.faint.copy(alpha = 0.8f), style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
    fun ripples(c: Offset, color: Color, phase: Float, reach: Float) {
        for (k in 0..2) {
            val life = frac(s.t * 0.42f + phase + k / 3f)
            val a = (1f - life) * 0.45f * s.reveal
            drawCircle(color.copy(alpha = a), 6f + life * reach, c, style = Stroke(1.4f))
        }
    }
    ripples(voice, s.p.accent, 0f, size.height * 0.5f)
    ripples(l, s.p.accent2, 0.33f, size.height * 0.45f)
    ripples(r, s.p.accent2, 0.66f, size.height * 0.45f)
    // Lines of sound to the listener.
    listOf(l to s.p.accent2, voice to s.p.accent, r to s.p.accent2).forEach { (c, col) ->
        drawLine(Brush.linearGradient(listOf(col.copy(alpha = 0.35f * s.reveal), Color.Transparent), c, head), c, head, 1.2f)
    }
    spark(voice, s.p.accent, 4f, s.reveal)
    spark(l, s.p.accent2, 3.4f, s.reveal)
    spark(r, s.p.accent2, 3.4f, s.reveal)
    // Listener, seen from above.
    drawCircle(s.p.muted.copy(alpha = 0.55f * s.reveal), 11f, head)
    drawRoundRect(s.p.muted.copy(alpha = 0.55f * s.reveal), Offset(head.x - 16f, head.y - 5f), Size(5f, 10f), CornerRadius(3f))
    drawRoundRect(s.p.muted.copy(alpha = 0.55f * s.reveal), Offset(head.x + 11f, head.y - 5f), Size(5f, 10f), CornerRadius(3f))
}

// ── 6. Tube Warmth ─────────────────────────────────────────────────────────

/** A bloom at 63-100 Hz and a softer top: tone only, no saturation. */
@Composable
internal fun TubeWarmthIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Accent2 to stringResource(R.string.ill2_bloom), Ink.Accent to stringResource(R.string.ill2_soft_top))
) { s ->
    val base = size.height - 17.dp.toPx()
    freqGrid(s, base, listOf(100f to "100", 1000f to "1k", 10000f to "10k"))
    val zero = base * 0.52f
    fun shape(f: Float) = 3.4f * exp(-0.5f * (octaves(80f, f) / 0.9f).let { it * it }) - 5f * smooth01(0f, 1.2f, octaves(8000f, f))
    val pxDb = base * 0.07f
    val flat = response { zero }
    val warm = response { zero - shape(it) * pxDb * s.on }
    val bloomAt = Offset(xHz(80f), zero - 3.4f * pxDb * s.on)
    drawCircle(Brush.radialGradient(listOf(s.p.accent2.copy(alpha = 0.32f * s.on), Color.Transparent), bloomAt, size.height * 0.7f),
        size.height * 0.7f, bloomAt)
    glowStroke(flat.trimmed(s.drawOn), s.p.muted.copy(alpha = 0.5f), 1.6f, glow = 0f)
    drawPath(areaUnder(warm, 0f, size.width, zero), Brush.verticalGradient(listOf(s.p.accent2.copy(alpha = 0.22f * s.on), Color.Transparent), zero - 3.4f * pxDb, zero))
    if (s.on > 0f) glowStroke(warm, Brush.horizontalGradient(listOf(s.p.accent2, s.p.accent)), 2.6f, glow = s.on)
    // Embers drifting up off the bloom.
    rising(s, 9, xHz(40f), xHz(180f), { zero - shape(hzAt(it)) * pxDb }, 4f, s.p.accent2, s.live, speed = 0.18f)
}

// ── 7. Mobile Bass ─────────────────────────────────────────────────────────

/**
 * A phone speaker can't move air below ~90 Hz. Mobile Bass lifts 90-300 Hz,
 * where the note's own overtones already are; the ear fills in the low note
 * from them. Nothing is generated.
 */
@Composable
internal fun MobileBassIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Muted to stringResource(R.string.ill2_too_low), Ink.Accent to stringResource(R.string.ill2_overtones))
) { s ->
    val lo = 30f; val hi = 600f
    val base = size.height - 17.dp.toPx()
    val top = 8f
    val wall = xHz(90f, lo, hi)
    // The speaker's floor: hatched, out of reach.
    drawContext.canvas.save()
    drawContext.canvas.clipRect(0f, top, wall, base)
    var hx = -size.height
    while (hx < wall) {
        drawLine(s.p.muted.copy(alpha = 0.10f * s.reveal), Offset(hx, base), Offset(hx + size.height, top), 1f)
        hx += 9f
    }
    drawContext.canvas.restore()
    drawLine(s.p.muted.copy(alpha = 0.4f * s.reveal), Offset(wall, top), Offset(wall, base), 1.2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f)))
    hline(base, s.p.faint, 0.7f * s.reveal)
    val fund = 55f
    val fx = xHz(fund, lo, hi)
    val fh = (base - top) * 0.78f * seg(s.t, 0.3f, 0.8f)
    drawRoundRect(s.p.muted.copy(alpha = 0.45f), Offset(fx - 6f, base - fh), Size(12f, fh), CornerRadius(6f),
        style = Stroke(1.3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
    val harmonics = listOf(110f, 165f, 220f, 275f)
    harmonics.forEachIndexed { i, f ->
        val x = xHz(f, lo, hi)
        val h0 = (base - top) * (0.40f - i * 0.07f) * seg(s.t, 0.45f + i * 0.08f, 0.8f)
        val h = h0 * (1f + 0.6f * s.on) * (1f + 0.04f * s.live * breathe(s.t, 1.6f, i * 0.2f))
        drawRoundRect(Brush.verticalGradient(listOf(s.p.accent, s.p.accent.copy(alpha = 0.3f)), base - h, base),
            Offset(x - 6f, base - h), Size(12f, h), CornerRadius(6f))
        // The ear's inference: light travels from each overtone to the low note.
        if (s.live > 0f) {
            val arc = Path().apply { moveTo(x, base - h); quadraticTo((x + fx) / 2f, base - h - 40f - i * 8f, fx, base - fh) }
            drawPath(arc, s.p.accent.copy(alpha = 0.12f * s.live), style = Stroke(1f))
            val u = frac(s.hold * 0.35f + i * 0.25f)
            spark(arc.pointAt(u), s.p.accent, 1.8f, s.live * sin(u * PI.toFloat()))
        }
    }
    // The low note the ear rebuilds, glowing in time with its overtones.
    spark(Offset(fx, base - fh), s.p.accent, 3f, 0.4f * s.on + 0.4f * s.live * breathe(s.t, 1.6f))
    s.run {
        label(text, "55", Offset(fx, size.height), micro, p.muted, LabelAlign.Center, alpha = 0.7f * reveal)
        label(text, "90", Offset(wall, size.height), micro, p.muted, LabelAlign.Center, alpha = 0.7f * reveal)
        label(text, "300 Hz", Offset(xHz(300f, lo, hi), size.height), micro, p.accent, LabelAlign.Center, alpha = 0.8f * reveal)
    }
}

// ── 8. Harmonic Exciter ────────────────────────────────────────────────────

/** A lift on 2-8 kHz, everything else left alone. A band boost, no harmonics made. */
@Composable
internal fun ExciterIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Accent to stringResource(R.string.ill2_presence))
) { s ->
    val base = size.height - 17.dp.toPx()
    freqGrid(s, base, listOf(100f to "100", 2000f to "2k", 8000f to "8k"))
    val zero = base * 0.66f
    val x2 = xHz(2000f); val x8 = xHz(8000f)
    drawRect(Brush.verticalGradient(listOf(s.p.accent.copy(alpha = 0.10f * s.on), Color.Transparent), 0f, base),
        Offset(x2, 0f), Size(x8 - x2, base))
    fun shape(f: Float) = 5f * smooth01(-0.25f, 0.25f, octaves(2000f, f)) * (1f - smooth01(-0.25f, 0.25f, octaves(8000f, f)))
    val pxDb = base * 0.08f
    val flat = response { zero }
    val lifted = response { zero - shape(it) * pxDb * s.on }
    glowStroke(flat.trimmed(s.drawOn), s.p.muted.copy(alpha = 0.5f), 1.6f, glow = 0f)
    drawPath(areaUnder(lifted, 0f, size.width, zero), Brush.verticalGradient(listOf(s.p.accent.copy(alpha = 0.3f * s.on), Color.Transparent), zero - 5f * pxDb, zero))
    if (s.on > 0f) glowStroke(lifted, s.p.accent, 2.6f, glow = s.on)
    rising(s, 12, x2, x8, { zero - shape(hzAt(it)) * pxDb }, 4f, s.p.accent, s.live)
}

// ── 9. Parametric EQ ───────────────────────────────────────────────────────

/** One band moving through its shapes: peak, high shelf, notch. */
@Composable
internal fun ParametricEqIllustration(modifier: Modifier = Modifier) {
    val names = listOf(
        stringResource(R.string.scr_filter_peak),
        stringResource(R.string.scr_filter_high_shelf),
        stringResource(R.string.scr_filter_notch)
    )
    Stage(
        modifier,
        listOf(Ink.Accent to stringResource(R.string.ill2_any_shape),
               Ink.Muted to stringResource(R.string.scr_ill_bands, DigitalFilterEngine.MAX_BANDS))
    ) { s ->
        val base = size.height - 17.dp.toPx()
        freqGrid(s, base, listOf(100f to "100", 1000f to "1k", 10000f to "10k"))
        val zero = base * 0.55f
        val pxDb = base * 0.045f
        fun peak(f: Float) = 7f * exp(-0.5f * (octaves(1000f, f) / 0.55f).let { it * it })
        fun shelf(f: Float) = 6f * smooth01(-0.9f, 0.9f, octaves(3000f, f))
        fun notch(f: Float) = -8f * exp(-0.5f * (octaves(1000f, f) / 0.16f).let { it * it })
        val shapes = listOf(::peak, ::shelf, ::notch)
        // Hold each shape, then morph to the next: 2.4 s per shape.
        val clock = (s.t - 1.2f).coerceAtLeast(0f) / 2.4f
        val idx = kotlin.math.floor(clock).toInt()
        val within = frac(clock)
        val m = seg(within, 0.62f, 0.38f, Cine.InOut)
        val a = shapes[idx % 3]; val b = shapes[(idx + 1) % 3]
        val grow = seg(s.t, 0.4f, 1.0f)
        fun db(f: Float) = mix(a(f), b(f), m) * grow
        hline(zero, s.p.faint, 0.7f * s.reveal)
        val curve = response { zero - db(it) * pxDb }
        drawPath(areaUnder(curve, 0f, size.width, zero), Brush.verticalGradient(listOf(s.p.accent.copy(alpha = 0.18f), Color.Transparent), zero - 7f * pxDb, zero))
        glowStroke(curve.trimmed(s.drawOn), Brush.horizontalGradient(listOf(s.p.accent2, s.p.accent)), 2.6f, glow = 1f)
        val nodeHz = mix(if (idx % 3 == 1) 3000f else 1000f, if ((idx + 1) % 3 == 1) 3000f else 1000f, m)
        val node = Offset(xHz(nodeHz), zero - db(nodeHz) * pxDb)
        drawCircle(s.p.accent.copy(alpha = 0.18f * s.reveal), 14f + 2f * breathe(s.t, 2f), node)
        spark(node, s.p.accent, 4.5f, s.reveal)
        s.run {
            val nameA = names[idx % 3]; val nameB = names[(idx + 1) % 3]
            label(text, nameA, Offset(0f, small.fontSize.toPx() + 2f), small, p.accent, alpha = (1f - m) * reveal, rise = 6f)
            label(text, nameB, Offset(0f, small.fontSize.toPx() + 2f), small, p.accent, alpha = m * reveal, rise = 6f)
        }
    }
}

// ── 10. SBC Enhancement ────────────────────────────────────────────────────

/**
 * SBC's 8 subbands. As recorded, the top subbands sit at the zero-bit edge
 * and flicker, which is the watery sound. Conditioning lowers the top octave
 * and steadies it, and the bits it frees go where the music is.
 */
@Composable
internal fun SbcIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Muted to stringResource(R.string.ill2_flicker), Ink.Accent to stringResource(R.string.ill2_bits))
) { s ->
    val bands = 8
    val base = size.height - 17.dp.toPx()
    val slot = size.width / bands
    val bh = (base - 6f) / 7.2f
    val bw = slot * 0.56f
    val steady = intArrayOf(5, 5, 4, 4, 3, 2, 1, 1)
    fun block(b: Int, k: Int) = Offset(b * slot + (slot - bw) / 2f, base - (k + 1) * bh + bh * 0.14f)
    for (b in 0 until bands) {
        for (k in 0 until steady[b]) {
            val appear = seg(s.t, 0.1f + b * 0.05f + k * 0.04f, 0.5f)
            val o = block(b, k)
            drawRoundRect(s.p.accent.copy(alpha = 0.5f * appear), o + Offset(0f, (1f - appear) * 8f), Size(bw, bh * 0.72f), CornerRadius(4f))
        }
        s.run { label(text, "${b + 1}", Offset(b * slot + slot / 2f, size.height), micro, p.muted, LabelAlign.Center, alpha = 0.7f * reveal) }
    }
    // The flickering top blocks of subbands 7 and 8, then their flight down.
    listOf(6 to 2, 7 to 3).forEachIndexed { i, (from, to) ->
        val fromO = block(from, steady[from])
        val toO = block(to, steady[to])
        val fly = seg(s.t, 1.3f + i * 0.28f, 1.0f, Cine.InOut)
        if (fly <= 0f) {
            val flicker = if (sin(s.t * 23f + i * 2.1f) > 0.1f) 0.8f else 0.12f
            drawRoundRect(s.p.muted.copy(alpha = flicker * seg(s.t, 0.5f, 0.4f)), fromO, Size(bw, bh * 0.72f), CornerRadius(4f))
        } else {
            val ctrl = Offset((fromO.x + toO.x) / 2f, minOf(fromO.y, toO.y) - bh * 2.2f)
            val path = Path().apply { moveTo(fromO.x, fromO.y); quadraticTo(ctrl.x, ctrl.y, toO.x, toO.y) }
            if (fly < 1f) drawPath(path.trimmed(fly), s.p.accent.copy(alpha = 0.25f), style = Stroke(1.5f, cap = StrokeCap.Round))
            val at = path.pointAt(fly)
            drawRoundRect(mix(s.p.muted, s.p.accent, fly).copy(alpha = 0.9f), at, Size(bw, bh * 0.72f), CornerRadius(4f))
            if (fly < 1f) spark(at + Offset(bw / 2f, bh * 0.36f), s.p.accent, 2f, 1f - fly)
        }
    }
    // Once settled, a slow shimmer runs across the now-steady bands.
    if (s.live > 0f) {
        val sx = frac(s.hold * 0.18f) * (size.width + 80f) - 40f
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, s.p.accent.copy(alpha = 0.10f * s.live), Color.Transparent), sx - 40f, sx + 40f),
            Offset(sx - 40f, 0f), Size(80f, base))
    }
    hline(base, s.p.faint, 0.8f * s.reveal)
}

// ── 11. Loudness Contour ───────────────────────────────────────────────────

/**
 * The real ISO 226 correction, from the same function the DSP runs, as the
 * volume comes down. 1 kHz never moves.
 */
@Composable
internal fun LoudnessIllustration(modifier: Modifier = Modifier) {
    val unity = stringResource(R.string.scr_ill_unity)
    Stage(modifier, listOf(Ink.Accent to stringResource(R.string.ill2_loudness))) { s ->
        val reference = 80f
        val level = mix(reference, 50f, s.on) - 5f * s.live * breathe(s.t, 7f)
        val comp = LoudnessContour.compensationDb(reference, level, 1f)
        val track = size.height - 6f
        val zero = size.height * 0.66f
        val top = 10f
        val maxDb = 14f
        val xs = FloatArray(EqBands.count) { size.width * it / (EqBands.count - 1) }
        val ys = FloatArray(EqBands.count) { zero - (comp[it] / maxDb).coerceIn(0f, 1f) * (zero - top) }
        val curve = smoothPath(xs, ys)
        hline(zero, s.p.faint, 0.8f * s.reveal)
        drawPath(areaUnder(curve, 0f, size.width, zero), Brush.verticalGradient(listOf(s.p.accent.copy(alpha = 0.28f), Color.Transparent), top, zero))
        glowStroke(curve.trimmed(s.drawOn), Brush.horizontalGradient(listOf(s.p.accent, s.p.accent2, s.p.accent)), 2.6f, glow = 1f)
        val k1 = EqBands.frequencies.indexOfFirst { it == 1000f }
        val unityAt = Offset(xs[k1], zero)
        drawCircle(s.p.accent2.copy(alpha = s.reveal), 5f, unityAt, style = Stroke(1.6f))
        s.run {
            label(text, unity, Offset(unityAt.x, zero + small.fontSize.toPx() + 8f), micro, p.muted, LabelAlign.Center, alpha = 0.85f * reveal)
            label(text, "0 dB", Offset(size.width, zero - 6f), micro, p.muted, LabelAlign.End, alpha = 0.7f * reveal)
        }
        // The volume, sliding down as the curve rises.
        val knobX = mix(size.width, size.width * 0.18f, (reference - level) / 40f)
        drawLine(s.p.faint.copy(alpha = 0.8f * s.reveal), Offset(0f, track), Offset(size.width, track), 3f, cap = StrokeCap.Round)
        drawLine(Brush.horizontalGradient(listOf(s.p.accent.copy(alpha = 0.4f), s.p.accent)), Offset(0f, track), Offset(knobX, track), 3f, cap = StrokeCap.Round)
        spark(Offset(knobX, track), s.p.accent, 4.5f, s.reveal)
    }
}

// ── 12. Per-app profiles ───────────────────────────────────────────────────

/** Two apps, two sounds: the curve follows whichever one is playing. */
@Composable
internal fun PerAppIllustration(modifier: Modifier = Modifier) {
    val music = stringResource(R.string.scr_ill_music).replaceFirstChar { it.titlecase() }
    val podcast = stringResource(R.string.scr_ill_podcast).replaceFirstChar { it.titlecase() }
    Stage(modifier, listOf(Ink.Accent to stringResource(R.string.ill2_per_app))) { s ->
        val tileH = size.height * 0.24f
        val tileW = size.width * 0.38f
        val gap = size.width * 0.06f
        val lx = size.width / 2f - tileW - gap / 2f
        val rx = size.width / 2f + gap / 2f
        // 0 = music playing, 1 = podcast; swaps every 2.6 s once settled.
        val cycle = if (s.t < 1.2f) 0f else {
            val c = (s.t - 1.2f) / 2.6f
            val i = kotlin.math.floor(c).toInt()
            val m = seg(frac(c), 0.7f, 0.3f, Cine.InOut)
            if (i % 2 == 0) m else 1f - m
        }
        fun tile(x: Float, active: Float, name: String, color: Color) {
            val r = CornerRadius(tileH / 2f)
            drawRoundRect(color.copy(alpha = (0.06f + 0.16f * active) * s.reveal), Offset(x, 0f), Size(tileW, tileH), r)
            drawRoundRect(color.copy(alpha = (0.2f + 0.6f * active) * s.reveal), Offset(x, 0f), Size(tileW, tileH), r, style = Stroke(1.4f))
            if (active > 0.5f) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.18f * active), Color.Transparent), Offset(x + tileW / 2f, tileH / 2f), tileW * 0.7f),
                tileW * 0.7f, Offset(x + tileW / 2f, tileH / 2f))
            s.run { label(text, name, Offset(x + tileW / 2f, tileH / 2f + small.fontSize.toPx() * 0.36f), small, mix(p.muted, color, active), LabelAlign.Center, alpha = reveal) }
        }
        tile(lx, 1f - cycle, music, s.p.accent)
        tile(rx, cycle, podcast, s.p.accent2)
        val mid = size.height * 0.68f
        val n = 48
        val xs = FloatArray(n + 1) { size.width * it / n }
        val ys = FloatArray(n + 1) {
            val u = it / n.toFloat()
            val smile = (cos(u * 2f * PI.toFloat()) * 0.5f + 0.1f)
            val speech = exp(-((u - 0.55f) * 3.6f).let { v -> v * v }) - 0.42f
            mid - mix(smile, speech, cycle) * size.height * 0.22f
        }
        hline(mid, s.p.faint, 0.7f * s.reveal)
        glowStroke(smoothPath(xs, ys).trimmed(s.drawOn), mix(s.p.accent, s.p.accent2, cycle), 2.6f, glow = 1f)
    }
}

private fun cos(x: Float) = kotlin.math.cos(x)

// ── 13. HearPrint ──────────────────────────────────────────────────────────

/**
 * The tone test, in miniature: each pitch finds its quietest heard level,
 * then about half the gap to typical hearing is lifted, smoothed.
 */
@Composable
internal fun HearPrintIllustration(modifier: Modifier = Modifier) = Stage(
    modifier,
    listOf(Ink.Muted to stringResource(R.string.ill2_hp_tones), Ink.Accent to stringResource(R.string.ill2_hp_lift))
) { s ->
    val lo = 200f; val hi = 14000f
    val freqs = floatArrayOf(250f, 500f, 1000f, 2000f, 3000f, 4000f, 5000f, 6000f, 8000f, 10000f, 12000f)
    // An example ear: fine to 2 kHz, a dip around 6-10 kHz.
    val loss = floatArrayOf(0f, 0f, 0f, 0.3f, 1f, 2.6f, 5.2f, 8f, 9f, 7.4f, 6.2f)
    val ref = size.height * 0.30f
    val pxDb = size.height * 0.045f
    hline(ref, s.p.faint, 0.8f * s.reveal, dashed = true)
    freqs.forEachIndexed { i, f ->
        val x = xHz(f, lo, hi)
        val start = 0.3f + i * 0.11f
        val beep = seg(s.t, start, 0.5f)
        val drop = seg(s.t, start + 0.1f, 0.6f)
        // The beep: a ripple at the pitch.
        if (beep in 0.01f..0.99f) drawCircle(s.p.muted.copy(alpha = (1f - beep) * 0.6f), 4f + beep * 16f, Offset(x, ref), style = Stroke(1.2f))
        val y = ref + loss[i] * pxDb * drop
        drawLine(s.p.muted.copy(alpha = 0.3f * drop), Offset(x, ref), Offset(x, y), 1f)
        drawCircle(s.p.muted.copy(alpha = 0.8f * beep), 3.2f, Offset(x, y))
        if (s.live > 0f) {
            val ping = frac(s.hold * 0.25f - i / 11f)
            if (ping < 0.12f) drawCircle(s.p.accent.copy(alpha = (0.12f - ping) * 4f * s.live), 4f + ping * 90f, Offset(x, y), style = Stroke(1f))
        }
    }
    val lift = seg(s.t, 1.9f, 1.3f, Cine.Draw)
    val xs = FloatArray(freqs.size) { xHz(freqs[it], lo, hi) }
    val ys = FloatArray(freqs.size) { ref - loss[it] * 0.5f * pxDb }
    val curve = smoothPath(xs, ys)
    drawPath(areaUnder(curve, xs.first(), xs.last(), ref), Brush.verticalGradient(listOf(s.p.accent.copy(alpha = 0.25f * lift), Color.Transparent), ref - 5f * pxDb, ref))
    glowStroke(curve.trimmed(lift), s.p.accent, 2.6f, glow = lift)
    s.run {
        label(text, "250", Offset(xs.first(), size.height), micro, p.muted, LabelAlign.Center, alpha = 0.7f * reveal)
        label(text, "1k", Offset(xs[2], size.height), micro, p.muted, LabelAlign.Center, alpha = 0.7f * reveal)
        label(text, "12k", Offset(xs.last(), size.height), micro, p.muted, LabelAlign.Center, alpha = 0.7f * reveal)
    }
}
