package com.jadoo.amp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadoo.amp.audio.DpResponse
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jadoo.amp.ui.theme.JadooTheme
import com.jadoo.amp.ui.theme.LocalBackdrop
import com.jadoo.amp.ui.theme.Backdrop
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ln

/*
 * JadOO's design language, in one file.
 *
 * The app's signature is the live response curve at the top of the
 * dashboard: the tonal shape JadOO is actually writing to the audio, which
 * springs into a new shape whenever any feature changes. Everything below it
 * stays quiet on purpose — grouped lists on one surface level, sentence-case
 * headers in a neutral colour — so the accent colour only ever means "on".
 */

// ── Grouped lists ───────────────────────────────────────────────────────

/** Quiet sentence-case header above a group. Colour is saved for state. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One rounded container holding a group's rows, separated by inset hairlines. */
@Composable
fun FeatureGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** Hairline between rows, inset past the icon tile so rows read as one list. */
@Composable
fun GroupDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 68.dp else 20.dp, end = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
}

/** The rounded icon tile that leads each row; filled with the accent while its feature is on. */
@Composable
fun IconTile(icon: ImageVector, on: Boolean, enabled: Boolean = true) {
    val container by animateColorAsState(
        when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            on -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        }, animationSpec = JadooTheme.motion.fastSpec(), label = "tile")
    val content by animateColorAsState(
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            on -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }, animationSpec = JadooTheme.motion.fastSpec(), label = "tile_icon")
    val pop by animateFloatAsState(if (on) 1f else 0.92f, JadooTheme.motion.expressive, label = "tile_pop")
    Box(Modifier.size(36.dp).scale(pop).clip(RoundedCornerShape(11.dp)).background(container),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun jadooSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
)

/**
 * A feature's row: icon tile, name, one line of state, switch — and its
 * settings revealed underneath while it's on. The whole row toggles, not
 * just the 52dp switch, so the target is the full 64dp-high line.
 */
@Composable
fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onHelpClick: (() -> Unit)? = null,
    /** Full-width expanded content (graphs) instead of aligning under the title. */
    fullWidthContent: Boolean = false,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon, on = checked && enabled, enabled = enabled)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                         color = if (enabled) MaterialTheme.colorScheme.onSurface
                                 else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onHelpClick != null) {
                        IconButton(onClick = onHelpClick, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Info, "About $title", modifier = Modifier.size(16.dp),
                                 tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            // onCheckedChange = null: the row is the control, so TalkBack
            // announces one switch per feature instead of two.
            Switch(checked = checked, onCheckedChange = null, enabled = enabled,
                   colors = jadooSwitchColors())
        }
        if (expandedContent != null) {
            AnimatedVisibility(
                visible = checked && enabled,
                enter = expandVertically(JadooTheme.motion.standardSize) + fadeIn(JadooTheme.motion.fade),
                exit = shrinkVertically(JadooTheme.motion.standardSize) + fadeOut(JadooTheme.motion.fade)
            ) {
                Column(Modifier.fillMaxWidth().padding(start = if (fullWidthContent) 16.dp else 68.dp,
                                                       end = if (fullWidthContent) 16.dp else 20.dp, bottom = 16.dp),
                       verticalArrangement = Arrangement.spacedBy(12.dp), content = expandedContent)
            }
        }
    }
}

/** A row that opens something: icon tile, title, current value, chevron. */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    value: String?,
    onClick: () -> Unit,
    accent: Boolean = false,
    enabled: Boolean = true,
    onHelpClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, on = accent, enabled = enabled)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (onHelpClick != null) {
                    IconButton(onClick = onHelpClick, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Info, "About $title", modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            value?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
             tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A plain toggle row for settings — no icon tile, the label carries it. */
@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    onHelpClick: (() -> Unit)? = null
) {
    if (icon != null) {
        FeatureRow(icon, title, subtitle, checked, onCheckedChange, onHelpClick = onHelpClick)
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (onHelpClick != null) {
            IconButton(onClick = onHelpClick) {
                Icon(Icons.Default.Info, "About $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, colors = jadooSwitchColors())
    }
}

// ── Backdrop ────────────────────────────────────────────────────────────

/**
 * Paints a main screen's background for the chosen [Backdrop]. Aurora adds
 * two large, very soft glows — accent top-left, tertiary to the right — that
 * drift across the top third over 16 s and back. Slow enough to read as
 * light, not motion; still under reduced motion. Applied before the scroll
 * modifier, so it stays put while content scrolls over it.
 */
@Composable
fun Modifier.jadooBackdrop(): Modifier {
    val bg = MaterialTheme.colorScheme.background
    if (LocalBackdrop.current != Backdrop.Aurora) return this.background(bg)
    val a = MaterialTheme.colorScheme.primary
    val b = MaterialTheme.colorScheme.tertiary
    val dark = bg.luminance() < 0.5f
    val strength = if (dark) 1f else 0.6f
    val drift = if (JadooTheme.motion.enabled) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "aurora_drift").value
    } else 0.5f
    return this.drawBehind {
        drawRect(bg)
        val w = size.width
        val h = size.height
        val r1 = w * 1.05f
        val c1 = Offset(w * (0.1f + 0.35f * drift), h * 0.1f)
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.22f * strength), Color.Transparent),
                                        center = c1, radius = r1), radius = r1, center = c1)
        val r2 = w * 0.9f
        val c2 = Offset(w * (0.95f - 0.3f * drift), h * (0.3f + 0.06f * drift))
        drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.16f * strength), Color.Transparent),
                                        center = c2, radius = r2), radius = r2, center = c2)
    }
}

// ── Staggered entrance ──────────────────────────────────────────────────

/** When the app's first screen first composed this process; 0 until then. */
private var introStartedAt = 0L

/**
 * True only during the app's opening moment (the first 1.5 s after the first
 * screen composes). The launch stagger and the curve's draw-in belong to
 * opening the app; coming back from Settings shouldn't replay them — motion
 * on a frequent interaction is exactly what the HIG asks apps to avoid.
 */
@Composable
private fun rememberIntroMotion(): Boolean {
    val enabled = JadooTheme.motion.enabled
    return remember {
        val now = android.os.SystemClock.uptimeMillis()
        if (introStartedAt == 0L) introStartedAt = now
        enabled && now - introStartedAt < 1_500L
    }
}

/**
 * Fades and lifts [content] in [index] × 45 ms after first composition —
 * the dashboard's groups arrive in reading order once, at launch. Skipped
 * entirely when the system asks for reduced motion.
 */
@Composable
fun Entrance(index: Int, content: @Composable () -> Unit) {
    val play = rememberIntroMotion()
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!play) return@LaunchedEffect
        delay(60L + index * 45L)
        progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow))
    }
    Box(Modifier.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }) { content() }
}

// ── Signature: the live response curve ──────────────────────────────────

/**
 * What JadOO is actually doing to the sound right now, 20 Hz–20 kHz: the
 * engine's real static response, read back from DynamicsProcessing bin by
 * bin (see DspEngine.staticResponseDb) — every feature's EQ and band gains,
 * the input trim and the gain sliders, not just the graphic EQ.
 *
 * A new response pours into the curve on one spring, so a feature switching
 * on visibly reshapes it; that motion is the feedback for every toggle in the
 * app. The line draws itself in once at launch. With power off the curve
 * settles flat and greys out. Reduced motion: values jump, no draw-in.
 */
@Composable
fun ResponseCurveHero(
    gainsDb: FloatArray,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val motion = JadooTheme.motion
    val freqs = DpResponse.DISPLAY_FREQUENCIES_HZ
    val n = freqs.size
    val target = if (active && gainsDb.size == n) gainsDb else FloatArray(n)
    var from by remember { mutableStateOf(FloatArray(n)) }
    var to by remember { mutableStateOf(FloatArray(n)) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target.contentHashCode()) {
        val p = progress.value
        from = FloatArray(n) { from[it] + (to[it] - from[it]) * p }
        to = target
        if (motion.enabled) {
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 140f))
        } else progress.snapTo(1f)
    }
    fun value(i: Int) = from[i] + (to[i] - from[i]) * progress.value

    val playIntro = rememberIntroMotion()
    val drawIn = remember { Animatable(if (playIntro) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (playIntro) drawIn.animateTo(1f, tween(1100, easing = LinearEasing))
    }
    val accent by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        tween(400), label = "curve_accent")
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                               fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()

    val peak = (0 until n).maxByOrNull { abs(to[it]) } ?: 0
    val peakDb = to[peak]
    val peakHz = freqs[peak]
    val peakLabel = if (peakHz >= 1000f) "%.1f kilohertz".format(peakHz / 1000f) else "%.0f hertz".format(peakHz)
    val description = if (!active) "Response curve, flat: JadOO off"
        else if (abs(peakDb) < 0.3f) "Response curve, flat"
        else "Response curve, largest change ${"%+.1f".format(peakDb)} dB at $peakLabel"

    Canvas(modifier.semantics { contentDescription = description }) {
        val labelBand = 18.dp.toPx()
        val h = size.height - labelBand
        val w = size.width
        val range = 12f
        val logLo = ln(20f); val logHi = ln(20_000f)
        fun x(f: Float) = w * (ln(f) - logLo) / (logHi - logLo)
        fun y(db: Float) = h / 2f - (db.coerceIn(-range, range) / range) * (h / 2f - 6.dp.toPx())

        // Decade grid with labels, and a stronger 0 dB line.
        for ((f, label) in listOf(100f to "100", 1_000f to "1k", 10_000f to "10k")) {
            drawLine(grid, Offset(x(f), 0f), Offset(x(f), h), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(label, labelStyle)
            drawText(text, topLeft = Offset(x(f) - text.size.width / 2f, h + 4.dp.toPx()))
        }
        drawLine(grid, Offset(0f, y(0f)), Offset(w, y(0f)), strokeWidth = 1.dp.toPx())

        // Catmull-Rom → cubic Bézier through the measured points.
        val xs = FloatArray(n) { x(freqs[it]) }
        val ys = FloatArray(n) { y(value(it)) }
        val path = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 0 until n - 1) {
                val i0 = maxOf(i - 1, 0); val i3 = minOf(i + 2, n - 1)
                cubicTo(xs[i] + (xs[i + 1] - xs[i0]) / 6f, ys[i] + (ys[i + 1] - ys[i0]) / 6f,
                        xs[i + 1] - (xs[i3] - xs[i]) / 6f, ys[i + 1] - (ys[i3] - ys[i]) / 6f,
                        xs[i + 1], ys[i + 1])
            }
        }
        val visible = if (drawIn.value >= 1f) path else Path().also { out ->
            val measure = PathMeasure().apply { setPath(path, false) }
            measure.getSegment(0f, measure.length * drawIn.value, out, true)
        }
        // Soft fill between curve and 0 dB, fading out away from the line.
        val area = Path().apply {
            addPath(visible)
            lineTo(xs[n - 1] * drawIn.value.coerceAtLeast(0.001f), y(0f))
            lineTo(0f, y(0f))
            close()
        }
        drawPath(area, Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.02f), accent.copy(alpha = 0.28f)),
            startY = 0f, endY = h))
        // Glow under the line, then the line.
        drawPath(visible, accent.copy(alpha = 0.25f), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(visible, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * The power control: a 64dp round button that glows while JadOO processes.
 * The glow breathes slowly (4 s) — the only ambient motion in the app, and it
 * means exactly one thing: audio is being processed. Static under reduced motion.
 */
@Composable
fun PowerButton(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val motion = JadooTheme.motion
    val accent = MaterialTheme.colorScheme.primary
    val breath = if (on && motion.enabled) {
        val t = rememberInfiniteTransition(label = "power_breath")
        t.animateFloat(0.18f, 0.42f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "breath").value
    } else if (on) 0.3f else 0f
    val container by animateColorAsState(
        if (on) accent else MaterialTheme.colorScheme.surfaceContainerHighest, tween(300), label = "power_bg")
    val iconColor by animateColorAsState(
        if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(300), label = "power_fg")
    val press by animateFloatAsState(if (on) 1f else 0.94f, JadooTheme.motion.expressive, label = "power_pop")
    Box(
        modifier
            .size(72.dp)
            .drawBehind {
                if (breath > 0f) drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = breath), Color.Transparent),
                                         radius = size.minDimension * 0.75f),
                    radius = size.minDimension * 0.75f)
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = { onToggle(!on) },
            shape = CircleShape,
            color = container,
            modifier = Modifier.size(60.dp).scale(press)
                .semantics { contentDescription = if (on) "Turn JadOO off" else "Turn JadOO on" }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PowerSettingsNew, null, tint = iconColor, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** A small rounded status chip, e.g. the current output device. */
@Composable
fun StatusChip(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
