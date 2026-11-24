package com.jadoo.amp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jadoo.amp.R
import com.jadoo.amp.audio.EqBands
import com.jadoo.amp.audio.HearPrint
import com.jadoo.amp.audio.HearPrintTonePlayer
import com.jadoo.amp.audio.LevelTracker
import com.jadoo.amp.ui.components.GlassPinnedLayout
import com.jadoo.amp.ui.components.GlassSection
import com.jadoo.amp.ui.components.GlassTopBar
import com.jadoo.amp.ui.components.JadooAlertDialog
import com.jadoo.amp.ui.components.glassContentSource
import com.jadoo.amp.ui.theme.JadooTheme
import com.jadoo.amp.ui.theme.LocalUiStyle
import com.jadoo.amp.ui.theme.UiStyle
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Everything HearPrint's row and screen need from the service. */
class HearPrintUi(
    val enabled: Boolean,
    val strength: Float,
    val profile: HearPrint.Profile?,
    val curve: FloatArray,
    val onSpeaker: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    val onStrengthChange: (Float) -> Unit,
    val onSave: (HearPrint.Profile) -> Unit,
    val onDelete: () -> Unit,
    val beginTest: () -> Unit,
    val endTest: () -> Unit,
    val tasteLibrary: com.jadoo.amp.audio.Taste.Library = com.jadoo.amp.audio.Taste.Library(),
    val tasteCurve: FloatArray = FloatArray(com.jadoo.amp.audio.EqBands.count),
    val onTastePreview: (FloatArray?) -> Unit = {},
    /** (taste, name, index to replace or null for a new one) */
    val onSaveTaste: (com.jadoo.amp.audio.Taste.Profile, String, Int?) -> Unit = { _, _, _ -> },
    val onSelectTaste: (Int) -> Unit = {},
    val onRenameTaste: (Int, String) -> Unit = { _, _ -> },
    val onDeleteTaste: (Int) -> Unit = {},
    val isMusicPlaying: () -> Boolean = { false },
    /** The output the sound goes to; a test or A/B stops when it changes. */
    val outputKey: String = ""
) {
    val taste: com.jadoo.amp.audio.Taste.Profile? get() = tasteLibrary.current

    /** A hearing result but no taste saved yet: HearPrint is working on half its information. */
    val partial: Boolean get() = profile?.complete == true && tasteLibrary.items.isEmpty()
}

private enum class HpStep { Intro, Volume, Test, Result }

/** One measurement in the run: a test point (index into TEST_FREQS_HZ), or the 1 kHz repeat. */
private data class HpItem(val point: Int, val retest: Boolean = false)

private val RUN: List<HpItem> = HearPrint.ORDER.map { HpItem(it) } + HpItem(2, retest = true)

/** At most this many messy tones are re-run per test, each tone at most once. */
private const val MAX_REPEATS = 3

/** Presses in the silences before the result mentions them. */
private const val FALSE_PRESS_NOTE = 3

@Composable
fun HearPrintScreen(ui: HearPrintUi, onNavigateBack: () -> Unit, onOpenTaste: () -> Unit = {}) {
    var step by remember { mutableStateOf(HpStep.Intro) }
    var index by remember { mutableIntStateOf(0) }
    var holding by remember { mutableStateOf(false) }
    var askStop by remember { mutableStateOf(false) }
    val thresholds = remember { FloatArray(HearPrint.TEST_FREQS_HZ.size) { Float.NaN } }
    var retestDb by remember { mutableStateOf(Float.NaN) }
    var belowRangeCount by remember { mutableIntStateOf(0) }
    var notHeardCount by remember { mutableIntStateOf(0) }
    var falsePresses by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var repeats by remember { mutableIntStateOf(0) }
    var lastRepeated by remember { mutableIntStateOf(-1) }
    var result by remember { mutableStateOf<HearPrint.Profile?>(null) }
    val player = remember { HearPrintTonePlayer() }
    var testing by remember { mutableStateOf(false) }

    fun stopAudio() {
        player.sounding = false
        player.stop()
        if (testing) { testing = false; ui.endTest() }
    }

    fun startAudio(): Boolean {
        if (!testing) { ui.beginTest(); testing = true }
        return player.start()
    }

    // Leaving the screen or the app always ends the test and gives the sound back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && (step == HpStep.Volume || step == HpStep.Test)) {
                stopAudio()
                holding = false
                step = HpStep.Intro
                index = 0
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stopAudio() }
    }

    val inTest = step == HpStep.Volume || step == HpStep.Test
    val close: () -> Unit = { if (inTest) askStop = true else onNavigateBack() }
    BackHandler(onBack = close)

    // A result belongs to the output it was measured on. If the sound moves
    // (headphones out, Bluetooth drops or connects) mid-test or before
    // saving, the run stops and nothing is saved.
    var outputChanged by remember { mutableStateOf(false) }
    val startOutput = remember { ui.outputKey }
    LaunchedEffect(ui.outputKey) {
        if (ui.outputKey != startOutput && step != HpStep.Intro && !outputChanged) {
            stopAudio()
            holding = false
            result = null
            outputChanged = true
        }
    }
    if (outputChanged) OutputChangedDialog(onDismiss = { outputChanged = false; onNavigateBack() })
    if (askStop) {
        JadooAlertDialog(
            onDismissRequest = { askStop = false },
            title = { Text(stringResource(R.string.hp_stop_title)) },
            text = { Text(stringResource(R.string.hp_stop_body)) },
            confirmButton = { TextButton(onClick = { askStop = false; stopAudio(); onNavigateBack() }) { Text(stringResource(R.string.hp_stop_confirm)) } },
            dismissButton = { TextButton(onClick = { askStop = false }) { Text(stringResource(R.string.hp_keep_going)) } }
        )
    }

    // Volume step: steady pulses at the reference level.
    LaunchedEffect(step) {
        if (step == HpStep.Volume) {
            player.frequencyHz = 1000f
            player.levelDb = HearPrint.VOLUME_TONE_DB
            player.sounding = true
        }
    }

    // The measurement loop: one tracker per item, stepped every 20 ms.
    LaunchedEffect(step, index, attempt) {
        if (step != HpStep.Test) return@LaunchedEffect
        val item = RUN[index]
        val tracker = LevelTracker(startDb = HearPrint.startLevelDb(item.point, thresholds))
        player.sounding = false
        // A silence of random length, so the next tone can't be anticipated by
        // rhythm. A new press in it is a press with nothing playing.
        val gapMs = 400 + Random.nextInt(500)
        var waited = 0
        var wasHolding = holding
        while (waited < gapMs) {
            delay(20); waited += 20
            if (holding && !wasHolding) falsePresses++
            wasHolding = holding
        }
        player.frequencyHz = HearPrint.TEST_FREQS_HZ[item.point]
        player.levelDb = tracker.levelDb
        player.sounding = true
        var last = System.nanoTime()
        while (!tracker.isDone) {
            delay(20)
            val now = System.nanoTime()
            tracker.step(holding, (now - last) / 1e9f)
            last = now
            player.levelDb = tracker.levelDb
        }
        player.sounding = false
        // Answers all over the place on this tone: run it once more.
        if (tracker.spreadDb > HearPrint.REPEAT_SPREAD_DB && repeats < MAX_REPEATS && lastRepeated != index) {
            repeats++; lastRepeated = index; attempt++
            return@LaunchedEffect
        }
        val t = tracker.thresholdDb()
        if (tracker.belowRange) belowRangeCount++
        if (tracker.notHeard) notHeardCount++
        if (item.retest) retestDb = HearPrint.retestDifference(thresholds[2], t) else thresholds[item.point] = t
        val next = index + 1
        if (next >= RUN.size) {
            stopAudio()
            holding = false
            result = HearPrint.Profile(thresholds.copyOf(), retestDb, System.currentTimeMillis())
            step = HpStep.Result
        } else index = next
    }

    val glass = LocalUiStyle.current == UiStyle.LiquidGlass
    val titleRow: @Composable RowScope.() -> Unit = {
        IconButton(onClick = close, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.Close, stringResource(if (inTest) R.string.hp_cd_stop else R.string.scr_cd_close))
        }
        Text(stringResource(R.string.hp_title), style = MaterialTheme.typography.titleLarge,
             fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    }
    GlassPinnedLayout(Modifier.fillMaxSize(), header = { if (glass) GlassTopBar(content = titleRow) }) { headerTop ->
        Column(Modifier.fillMaxSize()) {
            if (!glass) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, content = titleRow)
            }
            Box(
                Modifier.fillMaxSize().glassContentSource()
                    .padding(horizontal = JadooTheme.dimens.screenPadding)
                    .padding(top = if (glass) headerTop + 8.dp else 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                val fade = JadooTheme.motion.fade
                AnimatedContent(
                    step,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxSize(),
                    transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                    label = "hp_step"
                ) { s ->
                    when (s) {
                        HpStep.Intro -> IntroStep(ui.onSpeaker) {
                            index = 0; belowRangeCount = 0; notHeardCount = 0; retestDb = Float.NaN
                            falsePresses = 0; repeats = 0; lastRepeated = -1
                            thresholds.fill(Float.NaN)
                            if (startAudio()) step = HpStep.Volume
                        }
                        HpStep.Volume -> VolumeStep { step = HpStep.Test }
                        HpStep.Test -> TestStep(index, RUN.size, holding) { holding = it }
                        HpStep.Result -> result?.let { r ->
                            ResultStep(r, belowRangeCount, notHeardCount, falsePresses,
                                onSave = { ui.onSave(r); onNavigateBack() },
                                onSaveAndTaste = { ui.onSave(r); onNavigateBack(); onOpenTaste() },
                                onRetake = { result = null; step = HpStep.Intro })
                        }
                    }
                }
            }
        }
    }
}

/**
 * One step: content centred in the space above, the action pinned below.
 * [scroll] is off for the test, where a drag across the hold button must
 * never turn into a scroll that cancels the hold.
 */
@Composable
internal fun StepLayout(
    scroll: Boolean = true,
    centered: Boolean = true,
    footer: @Composable ColumnScope.() -> Unit = {},
    body: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val h = maxHeight
            val scrollState = rememberScrollState()
            Column(
                Modifier.fillMaxWidth()
                    .then(if (scroll) Modifier.verticalScroll(scrollState) else Modifier)
                    .heightIn(min = h)
                    .padding(top = if (centered) 0.dp else 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top,
                content = body
            )
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = footer
        )
    }
}

/**
 * An icon in a lit disc. On entrance the disc scales in and a ring of light
 * draws itself around it; while [pulsing], soft sound rings drift outward and
 * a slow highlight orbits the ring.
 */
@Composable
internal fun Hero(icon: ImageVector, pulsing: Boolean) {
    val ring = MaterialTheme.colorScheme.primary
    val glowTint = MaterialTheme.colorScheme.tertiary
    val clock = com.jadoo.amp.ui.components.rememberSceneClock()
    Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val enter = com.jadoo.amp.ui.components.seg(t, 0f, 0.9f)
            val draw = com.jadoo.amp.ui.components.seg(t, 0.15f, 1.2f, com.jadoo.amp.ui.components.Cine.Draw)
            val inner = 48.dp.toPx()
            val outer = size.minDimension / 2
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(glowTint.copy(alpha = 0.22f * enter), androidx.compose.ui.graphics.Color.Transparent), center, outer),
                outer, center)
            if (pulsing) repeat(3) { i ->
                val life = com.jadoo.amp.ui.components.frac(t * 0.36f + i / 3f)
                drawCircle(ring.copy(alpha = (1f - life) * 0.28f * enter), radius = inner + (outer - inner) * life,
                    style = Stroke(1.4.dp.toPx()))
            }
            val r = inner + 6.dp.toPx()
            drawArc(
                androidx.compose.ui.graphics.Brush.sweepGradient(listOf(ring.copy(alpha = 0.15f), ring, ring.copy(alpha = 0.15f)), center),
                startAngle = -90f + t * 24f, sweepAngle = 360f * draw, useCenter = false,
                topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            )
            val a = (-90f + t * 24f + 360f * draw) * (Math.PI / 180f).toFloat()
            if (draw > 0.02f) {
                val p = Offset(center.x + r * kotlin.math.cos(a), center.y + r * kotlin.math.sin(a))
                drawCircle(ring.copy(alpha = 0.35f), 6.dp.toPx(), p)
                drawCircle(ring, 2.5.dp.toPx(), p)
            }
        }
        Box(Modifier.size(92.dp)
            .graphicsLayer {
                val e = com.jadoo.amp.ui.components.seg(clock.value, 0f, 0.8f)
                scaleX = 0.82f + 0.18f * e; scaleY = scaleX; alpha = e
            }
            .clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
internal fun Headline(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
         textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp).semantics { heading() })
}

@Composable
internal fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
         textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp))
}

@Composable
private fun CheckLine(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape,
           modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun IntroStep(onSpeaker: Boolean, onStart: () -> Unit) {
    StepLayout(footer = {
        if (onSpeaker) {
            Text(stringResource(R.string.hp_need_headphones), color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                 modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        PrimaryButton(stringResource(R.string.hp_start), enabled = !onSpeaker, onClick = onStart)
        Text(stringResource(R.string.hp_not_medical), style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
             modifier = Modifier.padding(top = 12.dp))
    }) {
        Hero(Icons.Default.Hearing, pulsing = false)
        Headline(stringResource(R.string.hp_intro_headline))
        Body(stringResource(R.string.hp_intro_body))
        Spacer(Modifier.height(24.dp))
        GlassSection {
            CheckLine(Icons.Default.Headphones, stringResource(R.string.hp_check_headphones))
            CheckLine(Icons.Default.NightsStay, stringResource(R.string.hp_check_quiet))
            CheckLine(Icons.Default.MusicOff, stringResource(R.string.hp_check_music))
        }
    }
}

@Composable
private fun VolumeStep(onDone: () -> Unit) {
    StepLayout(footer = { PrimaryButton(stringResource(R.string.hp_volume_done), onClick = onDone) }) {
        Hero(Icons.AutoMirrored.Filled.VolumeUp, pulsing = true)
        Headline(stringResource(R.string.hp_volume_headline))
        Body(stringResource(R.string.hp_volume_body))
    }
}

/**
 * The hold button inside a ring of one segment per tone. The only things that
 * move with the user's finger are the button and its ripples: nothing on
 * screen ever follows the tone itself, or the eyes would answer for the ears.
 */
@Composable
private fun TestStep(index: Int, total: Int, holding: Boolean, onHold: (Boolean) -> Unit) {
    val motion = JadooTheme.motion
    val haptics = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val press by animateFloatAsState(if (holding) 0.94f else 1f, motion.expressive, label = "hp_press")
    val held by animateFloatAsState(if (holding) 1f else 0f, motion.standard, label = "hp_held")
    val fill by animateColorAsState(
        if (holding) primary else MaterialTheme.colorScheme.primaryContainer,
        motion.fastSpec(), label = "hp_fill")
    val transition = rememberInfiniteTransition(label = "hp_ring")
    val drift by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ripple")
    val beat by transition.animateFloat(0.45f, 1f,
        infiniteRepeatable(tween(900), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse), label = "beat")
    val label = stringResource(if (holding) R.string.hp_hearing else R.string.hp_hold)
    val stateText = stringResource(if (holding) R.string.hp_state_pressed else R.string.hp_state_released)
    val counter = stringResource(R.string.hp_progress, index + 1, total)

    StepLayout(scroll = false) {
        Text(counter, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; heading() })
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                val ringR = size.minDimension / 2 - stroke
                val gap = 7f
                val sweep = 360f / total - gap
                for (i in 0 until total) {
                    val color = when {
                        i < index -> primary
                        i == index -> primary.copy(alpha = if (motion.enabled) beat else 1f)
                        else -> track
                    }
                    drawArc(color, startAngle = -90f + i * 360f / total + gap / 2, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(center.x - ringR, center.y - ringR), size = Size(ringR * 2, ringR * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                if (held > 0.01f && motion.enabled) {
                    val from = 100.dp.toPx()
                    val to = ringR - 14.dp.toPx()
                    repeat(2) { i ->
                        val t = (drift + i * 0.5f) % 1f
                        drawCircle(primary.copy(alpha = (1f - t) * 0.40f * held), radius = from + (to - from) * t,
                            style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Box(
                Modifier
                    .size(200.dp)
                    .scale(press)
                    .clip(CircleShape)
                    .background(fill)
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                        stateDescription = stateText
                        // TalkBack users double-tap-and-hold; a plain double tap toggles.
                        onClick { onHold(!holding); true }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onHold(true)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            waitForUpOrCancellation()
                            onHold(false)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Hearing, null, modifier = Modifier.size(44.dp),
                         tint = if (holding) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                         textAlign = TextAlign.Center,
                         color = if (holding) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                         modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.hp_test_body), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
             modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
internal fun Note(icon: ImageVector, text: String, warn: Boolean) {
    val tint = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ResultStep(
    profile: HearPrint.Profile,
    belowRange: Int,
    notHeard: Int,
    falsePresses: Int,
    onSave: () -> Unit,
    onSaveAndTaste: () -> Unit,
    onRetake: () -> Unit
) {
    val curve = HearPrint.bandCurve(profile, HearPrint.DEFAULT_STRENGTH)
    StepLayout(footer = {
        if (profile.complete) {
            PrimaryButton(stringResource(R.string.tp_save_and_taste), onClick = onSaveAndTaste)
            TextButton(onClick = onSave, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tp_save_hearing_only))
            }
        }
        TextButton(onClick = onRetake, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.hp_retake))
        }
    }) {
        Headline(stringResource(R.string.hp_result_headline))
        Body(stringResource(R.string.hp_result_body))
        Spacer(Modifier.height(24.dp))
        GlassSection { HearPrintChart(curve, Modifier.fillMaxWidth().height(200.dp)) }
        Spacer(Modifier.height(8.dp))
        if (!profile.complete) Note(Icons.Default.Info, stringResource(R.string.hp_note_incomplete), warn = true)
        if (belowRange >= 2) Note(Icons.Default.Info, stringResource(R.string.hp_note_volume_high), warn = true)
        if (notHeard > 0) Note(Icons.Default.Info, stringResource(R.string.hp_note_not_heard), warn = true)
        if (profile.complete && !profile.reliable) Note(Icons.Default.Info, stringResource(R.string.hp_note_unsteady), warn = true)
        if (falsePresses >= FALSE_PRESS_NOTE) Note(Icons.Default.Info, stringResource(R.string.hp_note_false_presses), warn = true)
        if (profile.complete && profile.reliable && belowRange < 2 && notHeard == 0 && falsePresses < FALSE_PRESS_NOTE) {
            Note(Icons.Default.CheckCircle, stringResource(R.string.hp_note_steady), warn = false)
        }
    }
}

/**
 * The curve JadOO applies to both ears, on a log-frequency axis: a smooth
 * line over a soft fill, the zero line, four frequency marks and a dot on the
 * biggest lift. It morphs when the curve changes and draws in left to right
 * the first time it appears (reduced motion: neither).
 */
@Composable
fun HearPrintChart(curve: FloatArray, modifier: Modifier = Modifier) {
    val motion = JadooTheme.motion
    val freqs = EqBands.frequencies
    val n = freqs.size
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = faint)
    val peakStyle = MaterialTheme.typography.labelMedium.copy(color = line, fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()

    val target = if (curve.size == n) curve else FloatArray(n)
    var from by remember { mutableStateOf(FloatArray(n)) }
    var to by remember { mutableStateOf(FloatArray(n)) }
    val morph = remember { Animatable(1f) }
    val reveal = remember { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(target.contentHashCode()) {
        val p = morph.value
        from = FloatArray(n) { from[it] + (to[it] - from[it]) * p }
        to = target
        if (motion.enabled) {
            morph.snapTo(0f)
            morph.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 160f))
        } else morph.snapTo(1f)
    }
    LaunchedEffect(Unit) { if (motion.enabled) reveal.animateTo(1f, tween(900)) }

    val maxLift = target.maxOrNull() ?: 0f
    val description = stringResource(R.string.hp_chart_cd, String.format(java.util.Locale.US, "%.1f", maxLift))

    Canvas(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        val left = 4.dp.toPx(); val right = size.width - 4.dp.toPx()
        val topPad = 22.dp.toPx(); val bottomPad = 22.dp.toPx()
        val plotTop = topPad; val plotBottom = size.height - bottomPad
        val lo = ln(100f); val hi = ln(20000f)
        val top = 7f; val bottom = -4f
        fun x(f: Float) = left + (right - left) * ((ln(f) - lo) / (hi - lo))
        fun y(db: Float) = plotTop + (plotBottom - plotTop) * ((top - db.coerceIn(bottom, top)) / (top - bottom))
        val value = { i: Int -> from[i] + (to[i] - from[i]) * morph.value }

        listOf(250f to "250", 1000f to "1k", 4000f to "4k", 12000f to "12k").forEach { (f, name) ->
            drawLine(grid.copy(alpha = 0.35f), Offset(x(f), plotTop), Offset(x(f), plotBottom), 1.dp.toPx())
            val m = measurer.measure(name, labelStyle)
            drawText(m, topLeft = Offset(x(f) - m.size.width / 2f, plotBottom + 6.dp.toPx()))
        }
        drawLine(grid, Offset(left, y(0f)), Offset(right, y(0f)), 1.dp.toPx())

        val points = (0 until n).filter { freqs[it] >= 100f }.map { Offset(x(freqs[it]), y(value(it))) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 0 until points.size - 1) {
                val p0 = points[max(i - 1, 0)]; val p1 = points[i]
                val p2 = points[i + 1]; val p3 = points[min(i + 2, points.size - 1)]
                cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                    p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                    p2.x, p2.y)
            }
        }
        val fillPath = Path().apply {
            addPath(path)
            lineTo(points.last().x, y(0f)); lineTo(points.first().x, y(0f)); close()
        }
        clipRect(left = 0f, top = 0f, right = size.width * reveal.value, bottom = size.height) {
            drawPath(fillPath, Brush.verticalGradient(
                listOf(line.copy(alpha = 0.32f), line.copy(alpha = 0.0f)), startY = plotTop, endY = y(0f)))
            drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        val peak = (0 until n).filter { freqs[it] >= 100f }.maxByOrNull { value(it) }
        if (peak != null && value(peak) >= 0.5f && reveal.value >= 1f) {
            val c = Offset(x(freqs[peak]), y(value(peak)))
            drawCircle(surface, 7.dp.toPx(), c)
            drawCircle(line, 4.5.dp.toPx(), c)
            val m = measurer.measure("+" + String.format(java.util.Locale.US, "%.1f", value(peak)) + " dB", peakStyle)
            drawText(m, topLeft = Offset((c.x - m.size.width / 2f).coerceIn(0f, size.width - m.size.width),
                (c.y - m.size.height - 8.dp.toPx()).coerceAtLeast(0f)))
        }
    }
}

/** The sound moved to another output during a measurement; nothing was saved. */
@Composable
internal fun OutputChangedDialog(onDismiss: () -> Unit) {
    JadooAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hp_output_changed_title)) },
        text = { Text(stringResource(R.string.hp_output_changed_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hp_output_changed_ok)) } }
    )
}
