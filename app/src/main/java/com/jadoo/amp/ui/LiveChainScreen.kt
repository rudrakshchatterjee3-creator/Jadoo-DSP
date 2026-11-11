package com.jadoo.amp.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jadoo.amp.audio.AudioChainProbe
import com.jadoo.amp.audio.AudioChainProbe.ChainSnapshot
import com.jadoo.amp.audio.SurroundMode
import com.jadoo.amp.ui.theme.JadooTheme
import com.jadoo.amp.ui.components.*
import com.jadoo.amp.ui.theme.LocalUiStyle
import com.jadoo.amp.ui.theme.UiStyle
import androidx.compose.foundation.layout.RowScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * What the inspector needs from the dashboard. Read-only on purpose: every
 * feature already has its own switch on the dashboard, so the inspector
 * only reports what the audio is going through.
 */
data class LiveChainState(
    val appLabel: String?,
    val appPackage: String?,
    val sessionId: Int?,
    val masterEnabled: Boolean,
    val dspBypassed: Boolean,
    val preGainDb: Float,
    val postGainDb: Float,
    val autoTrimDb: Float,
    /** Names of the JadOO features currently switched on, dashboard order. */
    val activeStages: List<String>,
    val outputLabel: String,
    val sbcModeEnabled: Boolean,
    val surroundMode: SurroundMode,
    val dumpPermissionEnabled: Boolean
)

private const val BT_CODEC_REFRESH_MS = 10_000L

/**
 * Polls [AudioChainProbe] while the host is at least STARTED — a composable
 * stays composed while the activity sits in the background, and dumpsys on
 * a loop there would be pure battery drain.
 */
@Composable
private fun rememberChainSnapshot(state: LiveChainState, intervalMs: Long, readCodec: Boolean): ChainSnapshot? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var snapshot by remember { mutableStateOf<ChainSnapshot?>(null) }
    val bluetooth = readCodec && state.outputLabel.startsWith("Bluetooth")
    LaunchedEffect(state.appPackage, state.sessionId, state.dumpPermissionEnabled, bluetooth, intervalMs) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var codec: AudioChainProbe.BluetoothCodecInfo? = null
            var codecReadAt = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (bluetooth && state.dumpPermissionEnabled && now - codecReadAt > BT_CODEC_REFRESH_MS) {
                    codec = AudioChainProbe.probeBluetoothCodec()
                    codecReadAt = now
                } else if (!bluetooth) {
                    codec = null
                }
                snapshot = try {
                    AudioChainProbe.probe(context, state.appPackage, state.sessionId,
                        state.dumpPermissionEnabled, codec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("LiveChain", "probe failed", e)
                    snapshot
                }
                delay(intervalMs)
            }
        }
    }
    return snapshot
}

// ── What the chain is doing, in words ───────────────────────────────────

private enum class Tone { Clean, Changed, Quiet }

private class Verdict(val text: String, val detail: String?, val tone: Tone)

private fun dspInPath(state: LiveChainState, snapshot: ChainSnapshot?): Boolean? {
    val thread = snapshot?.thread ?: return null
    if (!thread.hasDynamicsProcessing) return false
    val session = state.sessionId ?: return true
    return session == 0 || thread.effectSessions.isEmpty() || session in thread.effectSessions
}

private fun activeTrack(snapshot: ChainSnapshot?) = snapshot?.track?.takeIf { it.active }

/** The one-line answer to "what is happening to my audio?". */
private fun verdict(state: LiveChainState, snapshot: ChainSnapshot?): Verdict {
    val thread = snapshot?.thread
    val track = activeTrack(snapshot)
    return when {
        !state.dumpPermissionEnabled -> Verdict("Limited view",
            "Grant DUMP to trace the stream: adb shell pm grant com.jadoo.amp android.permission.DUMP", Tone.Quiet)
        snapshot == null -> Verdict("Tracing…", null, Tone.Quiet)
        track == null || thread == null -> Verdict("Nothing playing", "Start playback to trace the path.", Tone.Quiet)
        thread.bypassesSoftwareEffects -> Verdict("${AudioChainProbe.describePath(thread)} - JadOO may be skipped",
            "Turn off offload, exclusive or bit-perfect output in the player.", Tone.Changed)
        state.masterEnabled && dspInPath(state, snapshot) == false -> Verdict("JadOO isn't on this output",
            "Pause and resume playback so JadOO can re-attach.", Tone.Changed)
        snapshot.resampled -> Verdict(
            "Resampled once · ${AudioChainProbe.formatRate(track.sampleRate)} → ${AudioChainProbe.formatRate(thread.sampleRate!!)}",
            "Android converts the stream to the output's rate. Normal on phones; a USB DAC running at the source rate avoids it.",
            Tone.Changed)
        else -> Verdict("Rate matched end to end", null, Tone.Clean)
    }
}

/** Secondary warnings that don't change the verdict. */
private fun extraNotes(state: LiveChainState, snapshot: ChainSnapshot?): List<String> {
    val notes = mutableListOf<String>()
    val codec = snapshot?.bluetoothCodec?.codec
    if (state.sbcModeEnabled && codec != null && !codec.equals("SBC", ignoreCase = true)) {
        notes += "SBC Enhancement is on but the codec is $codec. Turn it off - it rolls off treble $codec can keep."
    }
    if (snapshot?.spatialAudio?.enabled == true && state.surroundMode != SurroundMode.Off) {
        notes += "System spatial audio and Surround+ are both widening the sound. Pick one."
    }
    return notes
}

/**
 * "The sound was changed here" — amber, fixed rather than taken from the
 * seed palette: tertiary lands next to primary on some seeds, and this
 * signal has to read as different from JadOO's own accent. Two shades so
 * text keeps contrast: #8A5A00 on light surfaces (~5.6:1 on white),
 * #F2B84B on dark ones (~10:1 on near-black). Always paired with an icon
 * and words, never colour alone.
 */
@Composable
private fun changedColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(MaterialTheme.colorScheme.primary.toArgb(), hsv)
    // On the gold brand theme the accent IS amber, so the change signal
    // moves to coral (#FF8A70 dark / #A8391E light, same contrast targets).
    val accentIsAmber = hsv[1] > 0.25f && hsv[0] in 20f..70f
    return when {
        accentIsAmber && dark -> Color(0xFFFF8A70)
        accentIsAmber -> Color(0xFFA8391E)
        dark -> Color(0xFFF2B84B)
        else -> Color(0xFF8A5A00)
    }
}

/**
 * Position (0..1) of the signal pulse travelling down each wire segment while
 * audio plays; null when idle or under reduced motion. A single soft dot
 * moving source → output says "sound is flowing through here" — the dashed
 * line it replaces read as a decoration, not as signal.
 */
private val LocalWireFlow = androidx.compose.runtime.compositionLocalOf<Float?> { null }

@Composable
private fun rememberWireFlow(flowing: Boolean): Float? {
    if (!flowing || !JadooTheme.motion.enabled) return null
    val t = rememberInfiniteTransition(label = "wire_flow")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase").value
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.wire(
    color: Color, x: Float, from: Float, to: Float, width: Float, phase: Float?
) {
    drawLine(color, Offset(x, from), Offset(x, to), strokeWidth = width, cap = StrokeCap.Round)
    if (phase != null && to - from > 8.dp.toPx()) {
        val y = from + (to - from) * phase
        // Fade in and out at the segment ends so the pulse never pops.
        val edge = (minOf(phase, 1f - phase) * 6f).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = 0.25f * edge), radius = 7.dp.toPx(), center = Offset(x, y))
        drawCircle(color.copy(alpha = edge), radius = 3.dp.toPx(), center = Offset(x, y))
    }
}

// ── Logos ───────────────────────────────────────────────────────────────

/** An app's real launcher icon, or a neutral glyph when it can't be resolved. */
@Composable
private fun AppLogo(packageName: String?, size: Dp, fallback: ImageVector) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(packageName, px) {
        packageName?.let {
            runCatching {
                context.packageManager.getApplicationIcon(it).toBitmap(px, px).asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        DeviceLogo(fallback, size)
    }
}

/** A round badge for things without an app icon: the mixer, the output device. */
@Composable
private fun DeviceLogo(icon: ImageVector, size: Dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

private fun outputIcon(label: String): ImageVector = when {
    label.startsWith("Bluetooth") -> Icons.Default.Headphones
    label.startsWith("USB") -> Icons.Default.Usb
    label == "Wired Headphones" -> Icons.Default.Headset
    else -> Icons.Default.Speaker
}

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Clean -> MaterialTheme.colorScheme.primary
    Tone.Changed -> changedColor()
    Tone.Quiet -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun toneIcon(tone: Tone): ImageVector = when (tone) {
    Tone.Clean -> Icons.Default.CheckCircle
    Tone.Changed -> Icons.Default.SwapVert
    Tone.Quiet -> Icons.Default.Info
}

private val tabular = TextStyle(fontFeatureSettings = "tnum")

private fun ms(value: Float) = String.format(Locale.US, "%.1f ms", value)

private fun streamFormat(snapshot: ChainSnapshot?): String? = activeTrack(snapshot)?.let {
    listOfNotNull(AudioChainProbe.formatRate(it.sampleRate),
        AudioChainProbe.describeBitDepth(it.formatCode) ?: AudioChainProbe.describeFormat(it.formatCode))
        .joinToString(" · ")
}

private fun outputFormat(snapshot: ChainSnapshot?): String? {
    val rate = snapshot?.thread?.sampleRate ?: snapshot?.platformSampleRate ?: return null
    return listOfNotNull(AudioChainProbe.formatRate(rate),
        snapshot?.thread?.halFormat?.let { AudioChainProbe.describeFormatName(it) }).joinToString(" · ")
}

private fun sourceName(state: LiveChainState, snapshot: ChainSnapshot?): String =
    snapshot?.sourceLabel ?: snapshot?.sourcePackage ?: state.appLabel ?: state.appPackage ?: "No app"

// ── Dashboard card ──────────────────────────────────────────────────────

/**
 * The path in one glance: app ─ JadOO ─ output, with the stream format on
 * the wire, and the verdict underneath. The whole card opens the inspector.
 */
@Composable
fun LiveChainCard(
    state: LiveChainState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot = rememberChainSnapshot(state, intervalMs = 3_000L, readCodec = false)
    val v = verdict(state, snapshot)
    val jadooOn = state.masterEnabled && !state.dspBypassed

    JadooSurface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.Top) {
                    Stop(sourceName(state, snapshot), Modifier.weight(1f)) {
                        AppLogo(snapshot?.sourcePackage ?: state.appPackage, 40.dp, Icons.Default.MusicNote)
                    }
                    StopArrow()
                    Stop(if (jadooOn) "JadOO" else "JadOO off", Modifier.weight(1f), accent = jadooOn) {
                        AppLogo(context.packageName, 40.dp, Icons.Default.GraphicEq)
                    }
                    StopArrow(changed = snapshot?.resampled == true)
                    Stop(state.outputLabel.removePrefix("Bluetooth: ").removePrefix("USB: "), Modifier.weight(1f)) {
                        DeviceLogo(outputIcon(state.outputLabel), 40.dp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(toneIcon(v.tone), null, tint = toneColor(v.tone), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        cardLine(v, snapshot),
                        style = MaterialTheme.typography.bodyMedium.merge(tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open signal path",
                 tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** "44.1 kHz · 32-bit float → 48 kHz" while playing, otherwise the verdict itself. */
private fun cardLine(v: Verdict, snapshot: ChainSnapshot?): String {
    val source = streamFormat(snapshot) ?: return v.text
    if (v.tone == Tone.Quiet) return v.text
    val out = snapshot?.thread?.sampleRate?.let { AudioChainProbe.formatRate(it) } ?: return source
    return if (snapshot?.resampled == true) "$source → $out, resampled" else "$source → $out"
}

/** One stop on the card's path: its logo, its name underneath. */
@Composable
private fun Stop(text: String, modifier: Modifier, accent: Boolean = false, logo: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        logo()
        Spacer(Modifier.height(6.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
             style = MaterialTheme.typography.labelLarge,
             color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** The arrow between two stops; amber when the stream is changed on the way. */
@Composable
private fun StopArrow(changed: Boolean = false) {
    Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
         tint = if (changed) changedColor() else MaterialTheme.colorScheme.outline,
         modifier = Modifier.padding(top = 10.dp).size(20.dp))
}

// ── Inspector screen ────────────────────────────────────────────────────

@Composable
fun LiveChainScreen(
    state: LiveChainState,
    onNavigateBack: () -> Unit
) {
    val snapshot = rememberChainSnapshot(state, intervalMs = 1_000L, readCodec = true)
    val thread = snapshot?.thread
    val track = activeTrack(snapshot)
    val v = verdict(state, snapshot)
    val notes = extraNotes(state, snapshot)
    val jadooOn = state.masterEnabled && !state.dspBypassed
    val inPath = dspInPath(state, snapshot)
    val bluetooth = state.outputLabel.startsWith("Bluetooth")

    // Liquid Glass: the title bar floats as glass over the scrolling page,
    // and the answer and the wire each sit on a glass card. Standard: as before.
    val glass = LocalUiStyle.current == UiStyle.LiquidGlass
    val titleRow: @Composable RowScope.() -> Unit = {
        IconButton(onClick = onNavigateBack) { Icon(Icons.Default.Close, "Close") }
        Text("Signal path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
    GlassPinnedLayout(Modifier.fillMaxSize(), header = { if (glass) GlassTopBar(content = titleRow) }) { headerTop ->
    Column(Modifier.fillMaxSize()) {
        if (!glass) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically, content = titleRow)
        }

        Column(
            Modifier
                .fillMaxSize()
                .glassContentSource()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (glass) JadooTheme.dimens.screenPadding else JadooTheme.dimens.screenPadding + 4.dp)
                .padding(top = headerTop, bottom = 32.dp)
        ) {
            GlassSection {
            // ── Answer first: what is playing, and what happens to it ──
            Spacer(Modifier.height(if (glass) 4.dp else 16.dp))
            Text(snapshot?.nowPlaying?.title ?: sourceName(state, snapshot),
                 style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                 maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (snapshot?.nowPlaying != null) {
                Text(listOfNotNull(snapshot.nowPlaying.artist, sourceName(state, snapshot)).joinToString(" · "),
                     style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(toneIcon(v.tone), null, tint = toneColor(v.tone),
                     modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(v.text, style = MaterialTheme.typography.titleMedium.merge(tabular),
                         color = if (v.tone == Tone.Quiet) MaterialTheme.colorScheme.onSurface else toneColor(v.tone))
                    v.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            notes.forEach { note ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, null, tint = changedColor(),
                         modifier = Modifier.padding(top = 2.dp).size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }

            }
            if (glass) {
                Spacer(Modifier.height(16.dp))
            } else {
                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(20.dp))
            }

            GlassSection {
            // ── The wire: one node per stage, changes written on the wire ──
            androidx.compose.runtime.CompositionLocalProvider(
                LocalWireFlow provides rememberWireFlow(flowing = track != null)
            ) {
            StageNode(
                logo = { AppLogo(snapshot?.sourcePackage ?: state.appPackage, LOGO, Icons.Default.MusicNote) },
                title = "Source",
                value = sourceName(state, snapshot),
                fact = streamFormat(snapshot) ?: if (state.dumpPermissionEnabled) "Not playing" else "Needs DUMP",
                details = buildList {
                    (snapshot?.sourcePackage ?: state.appPackage)?.let { add("Package" to it) }
                    track?.let {
                        add("Sample format" to AudioChainProbe.describeFormat(it.formatCode))
                        add("Channels" to AudioChainProbe.describeChannels(it.channelMask))
                        add("Audio session" to "${it.sessionId}")
                    }
                }
            )
            WireSegment(
                note = if (state.masterEnabled && inPath == false && track != null) "JadOO not attached to this output" else null
            )
            StageNode(
                logo = { AppLogo(LocalContext.current.packageName, LOGO, Icons.Default.GraphicEq) },
                title = "JadOO",
                value = when {
                    !state.masterEnabled -> "Off"
                    state.dspBypassed -> "Bypassed · nothing switched on"
                    else -> "${state.activeStages.size} feature${if (state.activeStages.size == 1) "" else "s"} on"
                },
                fact = if (jadooOn) state.activeStages.joinToString(", ") else null,
                accent = jadooOn,
                details = buildList {
                    add("In audio path" to when (inPath) {
                        true -> "Yes"
                        false -> "No"
                        null -> if (state.dumpPermissionEnabled) "Unknown" else "Needs DUMP"
                    })
                    add("Processing" to "32-bit float")
                    add("Input gain" to String.format(Locale.US, "%+.1f dB", state.preGainDb))
                    if (state.autoTrimDb < -0.05f) add("Headroom trim" to String.format(Locale.US, "%+.1f dB", state.autoTrimDb))
                    add("Output gain" to String.format(Locale.US, "%+.1f dB, safety limiter on", state.postGainDb))
                }
            )
            WireSegment(
                note = if (snapshot?.resampled == true && track != null && thread?.sampleRate != null)
                    "Resampled ${AudioChainProbe.formatRate(track.sampleRate)} → ${AudioChainProbe.formatRate(thread.sampleRate)}"
                else null
            )
            StageNode(
                logo = { DeviceLogo(Icons.Default.Tune, LOGO) },
                title = "Android mixer",
                value = thread?.let { AudioChainProbe.describePath(it) } ?: "System mixer",
                fact = outputFormat(snapshot),
                details = buildList {
                    thread?.processingFormat?.let { add("Mixing format" to AudioChainProbe.describeFormatName(it)) }
                    if (thread?.halFrameCount != null && thread.sampleRate != null) {
                        add("Hardware buffer" to "${thread.halFrameCount} frames · ${ms(thread.halFrameCount * 1000f / thread.sampleRate)}")
                    }
                    track?.latencyMs?.let { add("Track latency" to ms(it)) }
                    thread?.let { add("Output thread" to "${it.name} · ${it.type}") }
                }
            )
            WireSegment(note = null)
            val codec = snapshot?.bluetoothCodec
            StageNode(
                logo = { DeviceLogo(outputIcon(state.outputLabel), LOGO) },
                title = "Output",
                value = state.outputLabel,
                fact = when {
                    bluetooth && codec != null -> listOfNotNull(codec.codec,
                        codec.sampleRate?.let { AudioChainProbe.formatRate(it) },
                        codec.bitsPerSample?.let { "$it-bit" }, codec.ldacQuality).joinToString(" · ")
                    else -> outputFormat(snapshot)
                },
                last = true,
                details = buildList {
                    thread?.devices?.takeIf { it.isNotEmpty() }?.let { devices ->
                        add("Route" to devices.joinToString { AudioChainProbe.describeDevice(it) })
                    }
                    if (bluetooth && codec == null) add("Codec" to if (state.dumpPermissionEnabled) "Unknown" else "Needs DUMP")
                    codec?.channelMode?.let { add("Codec channels" to it) }
                    snapshot?.spatialAudio?.let { s ->
                        add("System spatial audio" to when {
                            !s.available -> "Not supported here"
                            s.enabled -> if (s.headTrackerAvailable) "On · head tracking available" else "On"
                            else -> "Off"
                        })
                    }
                }
            )
            }
            }
        }
        }
    }
}

private val GUTTER = 48.dp
private val LOGO = 32.dp

/**
 * One stage on the wire: a node in the gutter, the stage's name and its key
 * fact beside it. Tapping reveals the rest — the defaults stay scannable.
 */
@Composable
private fun StageNode(
    title: String,
    value: String,
    fact: String?,
    details: List<Pair<String, String>>,
    accent: Boolean = false,
    last: Boolean = false,
    logo: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val nodeColor = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "stage_chevron")
    val flow = LocalWireFlow.current

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(GUTTER).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val x = LOGO.toPx() / 2f
                if (!last) wire(lineColor, x, LOGO.toPx(), size.height, 2.dp.toPx(), flow)
            }
            logo()
        }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = details.isNotEmpty()) { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(bottom = 4.dp)
                .animateContentSize()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title.uppercase(Locale.US), style = MaterialTheme.typography.labelMedium,
                     letterSpacing = 1.sp, color = nodeColor, modifier = Modifier.weight(1f))
                if (details.isNotEmpty()) {
                    Icon(Icons.Default.ExpandMore, if (expanded) "Hide details" else "Show details",
                         tint = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.size(20.dp).rotate(chevron))
                }
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                 maxLines = 2, overflow = TextOverflow.Ellipsis)
            fact?.takeIf { it.isNotBlank() }?.let {
                // Liquid Glass: AA contrast tint under the "Graphic EQ" label (no-op in Standard).
                Text(it, style = MaterialTheme.typography.bodyMedium.merge(tabular),
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = if (it.contains("Graphic EQ"))
                         Modifier.glassLabelTint() else Modifier)
            }
            AnimatedVisibility(expanded,
                enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    details.forEach { (label, detail) -> DetailRow(label, detail) }
                }
            }
        }
    }
}

/** The stretch of wire between two stages; a change to the stream is written on it. */
@Composable
private fun WireSegment(note: String?) {
    val changed = changedColor()
    val lineColor = if (note != null) changed else MaterialTheme.colorScheme.outlineVariant
    val flow = LocalWireFlow.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(GUTTER).fillMaxHeight()) {
            wire(lineColor, LOGO.toPx() / 2f, 0f, size.height, (if (note != null) 3.dp else 2.dp).toPx(), flow)
        }
        if (note != null) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapVert, null, tint = changed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(note, style = MaterialTheme.typography.labelLarge.merge(tabular),
                     color = changed)
            }
        } else {
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(128.dp))
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp).merge(tabular),
             fontFamily = if (label == "Package" || label == "Output thread") FontFamily.Monospace else null,
             modifier = Modifier.weight(1f))
    }
}
