package com.jadoo.amp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jadoo.amp.audio.AutoEqTargetMode
import com.jadoo.amp.audio.DbfbMode
import com.jadoo.amp.audio.DigitalFilterEngine
import com.jadoo.amp.audio.HdrMode
import com.jadoo.amp.audio.EqBands
import com.jadoo.amp.audio.SurroundMode
import com.jadoo.amp.settings.SavedEqPreset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt

private val Presets = mapOf(
    "Acoustic" to floatArrayOf(3f, 2.5f, 2f, 1.2f, 0.5f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2.4f, 2.2f, 1.5f, 0.8f),
    "Bass" to floatArrayOf(6f, 5.5f, 4.5f, 3f, 1.8f, 0.5f, 0f, -0.5f, -0.5f, 0f, 0.5f, 1f, 0.5f, 0f, 0f),
    "Beats" to floatArrayOf(5f, 4.5f, 4f, 2.5f, 1f, -0.5f, -1f, -0.5f, 0.5f, 1.5f, 3f, 4f, 3f, 2f, 1f),
    "Classic" to floatArrayOf(2f, 2f, 1.5f, 0.5f, 0f, 0f, -0.5f, -0.5f, 0f, 0.5f, 1f, 2f, 2.5f, 2f, 1f),
    "Clear" to floatArrayOf(-1f, -0.5f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2f, 2.5f, 3f, 3f, 2.5f, 2f, 1.5f)
)

private val ColorPalette = listOf(
    Color(0xFF276A30),
    Color(0xFF006B5E),
    Color(0xFF005FAF),
    Color(0xFF6750A4),
    Color(0xFF9A452F),
    Color(0xFFB3261E),
    Color(0xFF6D5E00),
    Color(0xFF3F5F32)
)

private sealed class HelpContent(
    val title: String,
    val body: String
) {
    data object AutoEqPower : HelpContent(
        title = "JadOO Psychoacoustics Engine",
        body = "Continuously analyses the audio spectrum and glides the EQ toward a target curve every 5 seconds. Balanced mode makes small, natural moves."
    )

    data object HiResUpscaler : HelpContent(
        title = "Hi-Res Upscaler",
        body = "Recovers treble detail lost in lossy compression using fast air-band MBC expansion and transient preservation. Does not move the visible EQ graph."
    )

    data object Dbfb : HelpContent(
        title = "JadOO DBFB",
        body = "Dynamic Bass Feedback adds level-aware sub-bass weight. Normal is rounded and full; High is deeper and more assertive."
    )

    data object HdrDynamics : HelpContent(
        title = "HDR Dynamics Restorer",
        body = "Restores punch to loudness-war audio by softening the brickwall limiter.\n\nPure mode removes the second-stage limiter entirely for the cleanest path. May clip on heavily mastered tracks.\n\nRestoration mode uses a soft 6:1 ceiling plus subtle air-band EQ (+1 dB at 16 kHz) to recover detail lost during hyper-compression."
    )

    data object AnalogBass : HelpContent(
        title = "Analog Bass",
        body = "Vintage hardware circuit modeling.\n\nDrive: Harmonic saturation intensity. More drive = thicker bass.\n\nWarmth: Even-order (smooth tube) vs odd-order (punchy transformer) balance.\n\nDrift: Microscopic gain/phase variation for analog unpredictability.\n\nPultec EQ: Legendary simultaneous boost/cut trick — sub-bass rises, mud dips.\n\nUses 4x oversampling to prevent aliasing."
    )

    data object SpatialSurround : HelpContent(
        title = "JadOO Surround+",
        body = "Frequency-dependent stereo widening via per-channel EQ differential.\n\nBass stays mono for punch. Mids get gentle spread. Treble gets full width.\n\nTraditional: natural width. Front Stage: speaker-like imaging. Ultra Wide: 180° immersion.\n\nPure EQ-based — no phase-smearing virtualizer."
    )

    data object NotificationAccess : HelpContent(
        title = "Notification session access",
        body = "Android lets notification listeners read active media sessions. JadOO uses this to detect playback when normal media-control permissions are unavailable."
    )

    data object DumpPermission : HelpContent(
        title = "DUMP permission",
        body = "Helps the fallback session scanner read system audio state. Grant via: adb shell pm grant com.jadoo.amp android.permission.DUMP"
    )

    data object DigitalFilters : HelpContent(
        title = "Parametric EQ",
        body = "8-band parametric EQ with biquad IIR filters.\n\nTypes: Peak, Low/High Shelf, Low/High Pass, Band Pass, Notch, All Pass.\n\nFrequency: 20Hz–20kHz. Gain: +/-15dB. Q: 0.1–18.0.\n\nUse cases:\n• Q=12.0 at 4.3kHz to remove harsh vocal sibilance\n• Low Shelf at 80Hz to add sub-bass warmth\n• Notch at 60Hz to eliminate electrical hum\n• All Pass for phase correction in complex setups\n\nBased on Robert Bristow-Johnson's Audio EQ Cookbook coefficients."
    )

}

@Composable
fun DashboardScreen(
    sessionId: Int?,
    isAttached: Boolean,
    activePackageName: String?,
    masterEnabled: Boolean,
    jadooEnabled: Boolean,
    autoEqTargetMode: AutoEqTargetMode,
    preGainDb: Float,
    postGainDb: Float,
    headroomDb: Float,
    hiResUpscalerEnabled: Boolean,
    hdrDynamicsEnabled: Boolean,
    hdrMode: HdrMode,
    dbfbMode: DbfbMode,
    surroundMode: SurroundMode,
    bandGains: FloatArray,
    // Analog Bass
    analogBassEnabled: Boolean,
    analogBassDrive: Float,
    analogBassWarmth: Float,
    analogBassDrift: Float,
    analogBassPultecBoost: Float,
    analogBassPultecCut: Float,
    analogBassPultecFreqIndex: Int,
    // Digital Filters
    digitalFilterEnabled: Boolean,
    digitalFilterBandStates: List<com.jadoo.amp.audio.DigitalFilterEngine.BiquadBandState>,
    savedPresets: List<SavedEqPreset>,
    useMaterialYou: Boolean,
    customPrimaryColor: Color,
    notificationListenerEnabled: Boolean,
    dumpPermissionEnabled: Boolean,
    onMasterPowerToggled: (Boolean) -> Unit,
    onJadooToggled: (Boolean) -> Unit,
    onAutoEqTargetModeChanged: (AutoEqTargetMode) -> Unit,
    onPreGainChanged: (Float) -> Unit,
    onPostGainChanged: (Float) -> Unit,
    onHeadroomChanged: (Float) -> Unit,
    onHiResUpscalerToggled: (Boolean) -> Unit,
    onHdrDynamicsToggled: (Boolean) -> Unit,
    onHdrModeChanged: (HdrMode) -> Unit,
    onDbfbModeChanged: (DbfbMode) -> Unit,
    onSurroundModeChanged: (SurroundMode) -> Unit,
    onBandLevelChanged: (Int, Float) -> Unit,
    onPresetSelected: (FloatArray) -> Unit,
    onSavePreset: (String, FloatArray) -> Unit,
    onDeletePreset: (String) -> Unit,
    // Analog Bass callbacks
    onAnalogBassEnabledChanged: (Boolean) -> Unit,
    onAnalogBassDriveChanged: (Float) -> Unit,
    onAnalogBassWarmthChanged: (Float) -> Unit,
    onAnalogBassDriftChanged: (Float) -> Unit,
    onAnalogBassPultecBoostChanged: (Float) -> Unit,
    onAnalogBassPultecCutChanged: (Float) -> Unit,
    onAnalogBassPultecFreqIndexChanged: (Int) -> Unit,
    onDigitalFilterEnabledChanged: (Boolean) -> Unit,
    onDigitalFilterBandEnabledChanged: (Int, Boolean) -> Unit,
    onDigitalFilterBandTypeChanged: (Int, DigitalFilterEngine.FilterType) -> Unit,
    onDigitalFilterBandFrequencyChanged: (Int, Float) -> Unit,
    onDigitalFilterBandGainChanged: (Int, Float) -> Unit,
    onDigitalFilterBandQChanged: (Int, Float) -> Unit,
    onUseMaterialYouChanged: (Boolean) -> Unit,
    onCustomPrimaryColorChanged: (Color) -> Unit,
    onOpenNotificationListenerSettings: () -> Unit
) {
    var showExpandedEq by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showGainControls by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf<HelpContent?>(null) }
    var showSurroundPicker by remember { mutableStateOf(false) }
    var showParametricEq by remember { mutableStateOf(false) }
    val manualControlsEnabled = masterEnabled && !jadooEnabled
    var graphicEqEnabled by remember { mutableStateOf(false) }
    var showGraphicEq by remember { mutableStateOf(false) }
    var savedBandGainsBeforeDisable by remember { mutableStateOf<FloatArray?>(null) }
    var showAutoEqPreview by remember { mutableStateOf(false) }
    val statusText = when {
        isAttached -> "DSP active · ${activePackageName ?: "Session ${sessionId ?: 0}"}"
        masterEnabled -> "Waiting for playback"
        else -> "Engine off"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── MASTER POWER ────────────────────────────────────────────
        val powerBg by animateColorAsState(
            targetValue = if (masterEnabled) MaterialTheme.colorScheme.primaryContainer
                          else MaterialTheme.colorScheme.surfaceContainerHigh,
            label = "power_bg"
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = powerBg,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                   verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f)
                        .clickable { showGainControls = !showGainControls },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("JadOO DSP",
                                 fontWeight = FontWeight.Bold, fontSize = 22.sp,
                                 color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(statusText,
                                 color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                 fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, "Settings",
                                 tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Switch(checked = masterEnabled, onCheckedChange = onMasterPowerToggled,
                               colors = SwitchDefaults.colors(
                                   checkedThumbColor = MaterialTheme.colorScheme.primary,
                                   checkedTrackColor = MaterialTheme.colorScheme.surface,
                                   uncheckedThumbColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                   uncheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)))
                    }
                }
                AnimatedVisibility(visible = showGainControls,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
                    GainControlDeck(
                        preGainDb, postGainDb, headroomDb,
                        onPreGainChanged, onPostGainChanged, onHeadroomChanged,
                        onResetAll = {
                            onPreGainChanged(0f)
                            onPostGainChanged(0f)
                            onHeadroomChanged(0f)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── AUTO EQ ─────────────────────────────────────────────────
        SectionLabel("AUTO EQ")
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                CompactToggleRow(
                    title = "JadOO Auto-EQ",
                    subtitle = if (jadooEnabled) "Analysing · ${autoEqTargetMode.displayName}"
                               else "Per-song analysis",
                    checked = jadooEnabled, enabled = masterEnabled,
                    leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = if (masterEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) },
                    onCheckedChange = { enabled -> onJadooToggled(enabled); if (enabled) showAutoEqPreview = true },
                    onHelpClick = { helpDialog = HelpContent.AutoEqPower }
                )
                AnimatedVisibility(visible = jadooEnabled,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
                    Column(modifier = Modifier.padding(bottom = 14.dp),
                           verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AutoEqTargetMode.entries.forEach { mode ->
                                val selected = autoEqTargetMode == mode
                                val chipBg by animateColorAsState(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant, label = "chip_$mode")
                                Surface(onClick = { onAutoEqTargetModeChanged(mode) },
                                        shape = RoundedCornerShape(50.dp), color = chipBg) {
                                    Text(mode.displayName,
                                         modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                         fontSize = 13.sp,
                                         fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                         color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                 else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        AnimatedVisibility(visible = showAutoEqPreview,
                            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                EqGraphWithStickyLabels(bandGains = bandGains, enabled = false,
                                    expanded = false, onTap = { showExpandedEq = true },
                                    onBandLevelChanged = { _, _ -> },
                                    modifier = Modifier.fillMaxWidth().height(180.dp))
                                Text("Live correction curve · tap to enlarge",
                                     color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = { showAutoEqPreview = !showAutoEqPreview },
                                   modifier = Modifier.align(Alignment.End)) {
                            Text(if (showAutoEqPreview) "Hide curve" else "Show curve", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── ENHANCEMENT ─────────────────────────────────────────────
        SectionLabel("ENHANCEMENT")
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                CompactToggleRow(
                    title = "Hi-Res Upscaler",
                    subtitle = if (hiResUpscalerEnabled) "Air · Silk · Presence"
                               else "High-frequency recovery",
                    checked = hiResUpscalerEnabled, enabled = masterEnabled,
                    leadingIcon = { Icon(Icons.Default.HighQuality, null, tint = if (masterEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) },
                    onCheckedChange = onHiResUpscalerToggled,
                    onHelpClick = { helpDialog = HelpContent.HiResUpscaler })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                CompactToggleRow(
                    title = "HDR Dynamics",
                    subtitle = if (hdrDynamicsEnabled) when (hdrMode) {
                        HdrMode.Pure -> "Pure · limiter bypass"
                        HdrMode.Restoration -> "Restoration · soft ceiling"
                    } else "Opens brickwall audio",
                    checked = hdrDynamicsEnabled, enabled = masterEnabled,
                    leadingIcon = { Icon(Icons.Default.WbSunny, null, tint = if (masterEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) },
                    onCheckedChange = onHdrDynamicsToggled,
                    onHelpClick = { helpDialog = HelpContent.HdrDynamics })
                if (hdrDynamicsEnabled && masterEnabled) {
                    HdrModeChips(hdrMode, masterEnabled, onHdrModeChanged)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("JadOO DBFB", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                 color = if (masterEnabled) MaterialTheme.colorScheme.onSurface
                                         else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(2.dp))
                            IconButton(onClick = { helpDialog = HelpContent.Dbfb },
                                       modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Info, null,
                                     tint = MaterialTheme.colorScheme.primary,
                                     modifier = Modifier.size(14.dp))
                            }
                        }
                        Text("Dynamic bass · ${dbfbMode.displayName}", fontSize = 12.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DbfbModeChips(dbfbMode, masterEnabled, onDbfbModeChanged)
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── ANALOG BASS ──────────────────────────────────────────────
        SectionLabel("ANALOG BASS")
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                CompactToggleRow(
                    title = "Analog Bass",
                    subtitle = if (analogBassEnabled) "Tube warmth · Pultec EQ"
                               else "Vintage analog emulation",
                    checked = analogBassEnabled, enabled = masterEnabled,
                    leadingIcon = { Icon(Icons.Default.Radio, null, tint = if (masterEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) },
                    onCheckedChange = onAnalogBassEnabledChanged,
                    onHelpClick = { helpDialog = HelpContent.AnalogBass })
                AnimatedVisibility(visible = analogBassEnabled && masterEnabled,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
                    Column(modifier = Modifier.padding(bottom = 14.dp),
                           verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        // Drive (Saturation)
                        LabeledSlider(
                            label = "Drive",
                            value = analogBassDrive,
                            valueLabel = "${String.format("%.1f", analogBassDrive * 100)}%",
                            onValueChange = onAnalogBassDriveChanged,
                            steps = 100)
                        // Warmth (Even/Odd harmonic balance)
                        LabeledSlider(
                            label = "Warmth",
                            value = analogBassWarmth,
                            valueLabel = "${String.format("%.1f", analogBassWarmth * 100)}%",
                            onValueChange = onAnalogBassWarmthChanged,
                            steps = 100)
                        // Drift (Thermal micro-modulation)
                        LabeledSlider(
                            label = "Drift",
                            value = analogBassDrift,
                            valueLabel = "${String.format("%.1f", analogBassDrift * 100)}%",
                            onValueChange = onAnalogBassDriftChanged,
                            steps = 100)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        // Pultec section
                        Text("Pultec EQ", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                             color = MaterialTheme.colorScheme.onSurface)
                        // Pultec frequency selector
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val pultecFreqs = listOf("20 Hz", "30 Hz", "60 Hz", "100 Hz")
                            pultecFreqs.forEachIndexed { idx, label ->
                                val selected = analogBassPultecFreqIndex == idx
                                val chipBg by animateColorAsState(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant, label = "pultec_$idx")
                                Surface(onClick = { onAnalogBassPultecFreqIndexChanged(idx) },
                                        shape = RoundedCornerShape(50.dp), color = chipBg) {
                                    Text(label,
                                         modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                         fontSize = 12.sp,
                                         fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                         color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                 else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        // Pultec Boost
                        LabeledSlider(
                            label = "Boost",
                            value = analogBassPultecBoost,
                            valueLabel = "+${String.format("%.2f", analogBassPultecBoost * 8)} dB",
                            onValueChange = onAnalogBassPultecBoostChanged,
                            steps = 80)
                        // Pultec Cut
                        LabeledSlider(
                            label = "Cut",
                            value = analogBassPultecCut,
                            valueLabel = "-${String.format("%.2f", analogBassPultecCut * 4)} dB",
                            onValueChange = onAnalogBassPultecCutChanged,
                            steps = 40)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        // Reset button — restores all Analog Bass sliders to default values
                        OutlinedButton(
                            onClick = {
                                onAnalogBassDriveChanged(0.4f)
                                onAnalogBassWarmthChanged(0.7f)
                                onAnalogBassDriftChanged(0.2f)
                                onAnalogBassPultecBoostChanged(0.5f)
                                onAnalogBassPultecCutChanged(0.3f)
                                onAnalogBassPultecFreqIndexChanged(2)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset to defaults")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── SPATIAL ─────────────────────────────────────────────────
        SectionLabel("SPATIAL")
        Surface(modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = masterEnabled) { showSurroundPicker = true },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.SurroundSound,
                        contentDescription = null,
                        tint = if (masterEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("JadOO Surround+", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                 color = if (masterEnabled) MaterialTheme.colorScheme.onSurface
                                         else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(2.dp))
                            IconButton(onClick = { helpDialog = HelpContent.SpatialSurround },
                                       modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Info, null,
                                     tint = MaterialTheme.colorScheme.primary,
                                     modifier = Modifier.size(14.dp))
                            }
                        }
                        Text(if (surroundMode != SurroundMode.Off)
                                 "${surroundMode.displayName} · ${surroundMode.tagline}"
                             else "Tap to choose a mode",
                             fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val badgeColor by animateColorAsState(
                    if (surroundMode != SurroundMode.Off) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant, label = "surround_badge")
                Surface(shape = RoundedCornerShape(10.dp), color = badgeColor) {
                    Text(surroundMode.displayName,
                         modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                         fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                         color = if (surroundMode != SurroundMode.Off) MaterialTheme.colorScheme.onPrimaryContainer
                                 else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── PARAMETRIC EQ ────────────────────────────────────────────
        SectionLabel("PARAMETRIC EQ")
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                CompactToggleRow(
                    title = "Parametric EQ",
                    subtitle = "8-band · Q control · Filter types",
                    checked = digitalFilterEnabled, enabled = masterEnabled,
                    leadingIcon = { Icon(Icons.Default.Tune, null, tint = if (masterEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) },
                    onCheckedChange = onDigitalFilterEnabledChanged,
                    onHelpClick = { helpDialog = HelpContent.DigitalFilters }
                )
                if (digitalFilterEnabled && masterEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showParametricEq = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Configure 8 Bands")
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── EQUALIZER ───────────────────────────────────────────────
        SectionLabel("EQUALIZER")
        Surface(modifier = Modifier.fillMaxWidth().alpha(if (jadooEnabled) 0.45f else 1f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                CompactToggleRow(
                    title = "Graphic EQ",
                    subtitle = when {
                        jadooEnabled -> "Disabled — Auto-EQ active"
                        graphicEqEnabled -> "15 bands · tap graph to expand"
                        else -> "Manual 15-band equalizer"
                    },
                    checked = graphicEqEnabled, enabled = manualControlsEnabled,
                    leadingIcon = { Icon(Icons.Default.GraphicEq, null, tint = if (manualControlsEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) },
                    onCheckedChange = { enabled ->
                        graphicEqEnabled = enabled
                        if (enabled) {
                            showGraphicEq = true
                            savedBandGainsBeforeDisable?.let { onPresetSelected(it) }
                        } else {
                            showGraphicEq = false
                            savedBandGainsBeforeDisable = bandGains.copyOf()
                            onPresetSelected(FloatArray(EqBands.count) { 0f })
                        }
                    })
                AnimatedVisibility(visible = showGraphicEq && graphicEqEnabled,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
                    Column(modifier = Modifier.padding(bottom = 14.dp),
                           verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        EqGraphWithStickyLabels(bandGains = bandGains, enabled = manualControlsEnabled,
                            expanded = false, onTap = { showExpandedEq = true },
                            onBandLevelChanged = onBandLevelChanged,
                            modifier = Modifier.fillMaxWidth().height(220.dp))
                        Text(if (manualControlsEnabled) "Tap to expand · drag nodes to tune"
                             else "Enable master power for manual tuning",
                             color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showExpandedEq) {
        ExpandedEqDialog(
            bandGains = bandGains,
            savedPresets = savedPresets,
            enabled = manualControlsEnabled,
            onDismiss = { showExpandedEq = false },
            onBandLevelChanged = onBandLevelChanged,
            onPresetSelected = onPresetSelected,
            onSavePreset = onSavePreset,
            onDeletePreset = onDeletePreset
        )
    }

    if (showSettings) {
        SettingsDialog(
            useMaterialYou = useMaterialYou,
            customPrimaryColor = customPrimaryColor,
            notificationListenerEnabled = notificationListenerEnabled,
            dumpPermissionEnabled = dumpPermissionEnabled,
            onUseMaterialYouChanged = onUseMaterialYouChanged,
            onCustomPrimaryColorChanged = onCustomPrimaryColorChanged,
            onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
            onHelpRequested = { helpDialog = it },
            onDismiss = { showSettings = false }
        )
    }

    if (showSurroundPicker) {
        SurroundModePickerDialog(
            currentMode = surroundMode,
            onModeSelected = { mode ->
                onSurroundModeChanged(mode)
                showSurroundPicker = false
            },
            onDismiss = { showSurroundPicker = false }
        )
    }

    // Parametric EQ Dialog
    if (showParametricEq) {
        Dialog(onDismissRequest = { showParametricEq = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                ParametricEqScreen(
                    bandStates = digitalFilterBandStates,
                    preampDb = preGainDb,
                    onBandTypeChanged = { index, type ->
                        onDigitalFilterBandTypeChanged(index, type)
                    },
                    onBandFrequencyChanged = { index, freq ->
                        onDigitalFilterBandFrequencyChanged(index, freq)
                    },
                    onBandGainChanged = { index, gain ->
                        onDigitalFilterBandGainChanged(index, gain)
                    },
                    onBandQChanged = { index, q ->
                        onDigitalFilterBandQChanged(index, q)
                    },
                    onBandEnabledChanged = { index, enabled ->
                        onDigitalFilterBandEnabledChanged(index, enabled)
                    },
                    onPreampChanged = onPreGainChanged,
                    onResetAllBands = {
                        for (i in 0 until 8) {
                            onDigitalFilterBandEnabledChanged(i, false)
                            onDigitalFilterBandGainChanged(i, 0f)
                            onDigitalFilterBandFrequencyChanged(i, 1000f)
                            onDigitalFilterBandQChanged(i, 1.0f)
                            onDigitalFilterBandTypeChanged(i, DigitalFilterEngine.FilterType.Peak)
                        }
                    },
                    onNavigateBack = { showParametricEq = false }
                )
            }
        }
    }

    helpDialog?.let { content ->
        HelpDialog(
            content = content,
            onDismiss = { helpDialog = null }
        )
    }
}

@Composable
private fun PsychoacousticTargetDropdown(
    selectedMode: AutoEqTargetMode,
    enabled: Boolean,
    onModeSelected: (AutoEqTargetMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Psychoacoustic Target",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = selectedMode.displayName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = enabled
            ) {
                Text(selectedMode.displayName)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                AutoEqTargetMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName) },
                        onClick = {
                            expanded = false
                            onModeSelected(mode)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(
    sessionId: Int?,
    isAttached: Boolean,
    activePackageName: String?,
    masterEnabled: Boolean
) {
    val targetColor = if (isAttached) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val containerColor by animateColorAsState(
        targetValue = targetColor,
        label = "status_container"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = when {
                    isAttached -> "DSP attached"
                    masterEnabled -> "Waiting for playback"
                    else -> "DSP powered off"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = activePackageName ?: "Global session ${sessionId ?: 0}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun HdrModeChips(
    selectedMode: HdrMode,
    enabled: Boolean,
    onModeSelected: (HdrMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HdrMode.values().forEach { mode ->
            val selected = mode == selectedMode
            AssistChip(
                onClick = { onModeSelected(mode) },
                label = { Text(mode.displayName) },
                enabled = enabled,
                leadingIcon = if (selected) {
                    {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun DbfbModeChips(
    selectedMode: DbfbMode,
    enabled: Boolean,
    onModeSelected: (DbfbMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DbfbMode.values().forEach { mode ->
            val selected = mode == selectedMode
            AssistChip(
                onClick = { onModeSelected(mode) },
                label = { Text(mode.displayName) },
                enabled = enabled,
                leadingIcon = if (selected) {
                    {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    onTitleClick: (() -> Unit)? = null,
    onHelpClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onTitleClick != null) {
                        Modifier.clickable { onTitleClick() }
                    } else {
                        Modifier
                    }
                )
                if (onHelpClick != null) {
                    IconButton(
                        onClick = onHelpClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "$title help",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
private fun GainControlDeck(
    preGainDb: Float,
    postGainDb: Float,
    headroomDb: Float,
    onPreGainChanged: (Float) -> Unit,
    onPostGainChanged: (Float) -> Unit,
    onHeadroomChanged: (Float) -> Unit,
    onResetAll: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Gain Staging",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onResetAll,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        "Reset all",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            GainSlider(
                label = "Pre gain",
                value = preGainDb,
                onValueChange = onPreGainChanged
            )
            GainSlider(
                label = "Post gain",
                value = postGainDb,
                onValueChange = onPostGainChanged
            )
            GainSlider(
                label = "Headroom",
                value = headroomDb,
                onValueChange = onHeadroomChanged,
                valueRange = -6f..0f
            )
        }
    }
}

@Composable
private fun GainSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = -12f..12f
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                text = String.format("%.2f dB", value),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) * 10).toInt(),
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
                thumbColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    subtitle: String? = null,
    value: Float,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 100
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                     color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 11.sp,
                         color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
            Text(valueLabel, fontSize = 14.sp,
                 color = MaterialTheme.colorScheme.primary,
                 fontWeight = FontWeight.Medium)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
                thumbColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun EqGraphWithStickyLabels(
    bandGains: FloatArray,
    enabled: Boolean,
    expanded: Boolean,
    onTap: () -> Unit,
    onBandLevelChanged: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val density = LocalDensity.current
    val labelTextSize = with(density) { if (expanded) 12.sp.toPx() else 10.sp.toPx() }
    val labelHeight = with(density) { 34.dp.toPx() }
    val graphPaddingTop = with(density) { 14.dp.toPx() }
    val dbLabelWidthDp = if (expanded) 46.dp else 40.dp

    // Full range: -15 to +15, every 1 dB step
    val dbSteps = (-15..15).toList()

    Box(modifier = modifier.clip(RoundedCornerShape(14.dp))) {
        // Scrollable EQ graph
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = dbLabelWidthDp)
                .horizontalScroll(rememberScrollState())
        ) {
            InteractiveEqGraph(
                bandGains = bandGains,
                enabled = enabled,
                expanded = expanded,
                onTap = onTap,
                onBandLevelChanged = onBandLevelChanged,
                modifier = Modifier
                    .width(if (expanded) 680.dp else 400.dp)
                    .fillMaxHeight()
            )
        }

        // Sticky dB labels overlay on left — drawn in a Canvas so they align exactly
        Canvas(
            modifier = Modifier
                .width(dbLabelWidthDp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                .align(Alignment.CenterStart)
        ) {
            val w = size.width
            val plotBottom = size.height - labelHeight
            val plotHeight = plotBottom - graphPaddingTop

            // Minor grid reference line
            drawLine(
                color = gridColor,
                start = Offset(w, graphPaddingTop),
                end = Offset(w, plotBottom),
                strokeWidth = 1f
            )

            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = labelTextSize
                    textAlign = android.graphics.Paint.Align.RIGHT
                }

                dbSteps.forEach { db ->
                    val y = plotBottom - ((db + 15f) / 30f) * plotHeight
                    val isZero = db == 0
                    val isMajor = db % 5 == 0

                    // Color labels: highlight 0dB line
                    paint.color = when {
                        isZero -> labelColor.copy(alpha = 0.9f).toArgb()
                        isMajor -> labelColor.copy(alpha = 0.7f).toArgb()
                        else -> labelColor.copy(alpha = 0.35f).toArgb()
                    }
                    paint.typeface = if (isMajor) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

                    // Only draw text for every 3dB step (but all grid lines)
                    if (db % 3 == 0) {
                        val label = when {
                            db > 0 -> "+${db}"
                            else -> "$db"
                        }
                        canvas.nativeCanvas.drawText(label, w - 4f, y + labelTextSize / 3f, paint)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollableEqGraph(
    bandGains: FloatArray,
    enabled: Boolean,
    expanded: Boolean,
    onTap: () -> Unit,
    onBandLevelChanged: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        InteractiveEqGraph(
            bandGains = bandGains,
            enabled = enabled,
            expanded = expanded,
            onTap = onTap,
            onBandLevelChanged = onBandLevelChanged,
            modifier = Modifier
                .width(if (expanded) 680.dp else 400.dp)
                .fillMaxHeight()
        )
    }
}

@Composable
fun InteractiveEqGraph(
    bandGains: FloatArray,
    enabled: Boolean,
    expanded: Boolean,
    onTap: () -> Unit,
    onBandLevelChanged: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val localGains = remember {
        mutableStateListOf<Float>().apply {
            repeat(EqBands.count) { add(0f) }
        }
    }
    var activeDragIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(bandGains) {
        if (activeDragIndex == null) {
            for (index in 0 until EqBands.count) {
                localGains[index] = bandGains.getOrNull(index) ?: 0f
            }
        }
    }

    val activeRadius = with(density) { if (expanded) 10.dp.toPx() else 8.dp.toPx() }
    val normalRadius = with(density) { if (expanded) 6.dp.toPx() else 4.dp.toPx() }
    val strokeWidthPx = with(density) { if (expanded) 5.dp.toPx() else 4.dp.toPx() }
    val labelTextSize = with(density) { if (expanded) 12.sp.toPx() else 10.sp.toPx() }
    val labelHeight = with(density) { 34.dp.toPx() }
    val graphPaddingTop = with(density) { 14.dp.toPx() }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(if (expanded) 16.dp else 14.dp))
            .background(surfaceColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    if (!expanded) {
                        onTap()
                    } else if (enabled) {
                        val plotWidth = size.width
                        val closestIndex = (offset.x / (plotWidth / (EqBands.count - 1))).roundToInt().coerceIn(0, EqBands.count - 1)
                        val plotHeight = size.height - labelHeight - graphPaddingTop
                        val yRatio = 1f - ((offset.y - graphPaddingTop) / plotHeight)
                        val newDb = (yRatio * 30f - 15f).coerceIn(-15f, 15f)
                        localGains[closestIndex] = newDb
                        onBandLevelChanged(closestIndex, newDb)
                    }
                })
            }
            .pointerInput(enabled && expanded) {
                if (!enabled || !expanded) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y
                    var directionKnown = false
                    var isVertical = false
                    var dragIdx: Int? = null

                    // Phase 1: determine drag direction before committing
                    while (!directionKnown) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: return@awaitEachGesture
                        if (!change.pressed) return@awaitEachGesture
                        val dx = kotlin.math.abs(change.position.x - startX)
                        val dy = kotlin.math.abs(change.position.y - startY)
                        if (dx > touchSlop || dy > touchSlop) {
                            isVertical = dy >= dx
                            directionKnown = true
                            if (isVertical) {
                                dragIdx = (startX / (size.width.toFloat() / (EqBands.count - 1)))
                                    .roundToInt().coerceIn(0, EqBands.count - 1)
                                activeDragIndex = dragIdx
                            }
                        }
                    }

                    if (!isVertical) return@awaitEachGesture  // let parent horizontalScroll handle it

                    // Phase 2: consume and handle vertical band adjustment
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            dragIdx?.let { idx ->
                                val plotHeight = size.height - labelHeight - graphPaddingTop
                                val yRatio = 1f - ((change.position.y - graphPaddingTop) / plotHeight)
                                localGains[idx] = (yRatio * 30f - 15f).coerceIn(-15f, 15f)
                            }
                        }
                    } finally {
                        dragIdx?.let { idx -> onBandLevelChanged(idx, localGains[idx]) }
                        activeDragIndex = null
                    }
                }
            }
    ) {
        val w = size.width
        val plotTop = graphPaddingTop
        val plotBottom = size.height - labelHeight
        val plotHeight = plotBottom - plotTop

        for (db in -15..15 step 3) {
            val y = plotBottom - ((db + 15f) / 30f) * plotHeight
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        for (index in 0 until EqBands.count) {
            val x = (index / (EqBands.count - 1f)) * w
            drawLine(
                color = gridColor,
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 1f
            )
        }

        val points = mutableListOf<Offset>()
        for (index in 0 until EqBands.count) {
            val x = (index / (EqBands.count - 1f)) * w
            val db = localGains[index].coerceIn(-15f, 15f)
            val y = plotBottom - ((db + 15f) / 30f) * plotHeight
            points.add(Offset(x, y))
        }

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (index in 0 until points.size - 1) {
                val p0 = points[index]
                val p1 = points[index + 1]
                val cpX = (p0.x + p1.x) / 2f
                cubicTo(cpX, p0.y, cpX, p1.y, p1.x, p1.y)
            }
        }

        val fillPath = Path().apply {
            addPath(path)
            lineTo(w, plotBottom)
            lineTo(0f, plotBottom)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(primaryColor.copy(alpha = 0.38f), Color.Transparent),
                startY = plotTop,
                endY = plotBottom
            ),
            style = Fill
        )

        drawPath(
            path = path,
            color = primaryColor,
            style = Stroke(width = strokeWidthPx)
        )

        points.forEachIndexed { index, point ->
            drawCircle(
                color = if (activeDragIndex == index) secondaryColor else primaryColor,
                radius = if (activeDragIndex == index) activeRadius else normalRadius,
                center = point
            )
        }

        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = labelColor.copy(alpha = 0.92f).toArgb()
                textSize = labelTextSize
                textAlign = android.graphics.Paint.Align.CENTER
            }
            EqBands.labels.forEachIndexed { index, label ->
                val x = (index / (EqBands.count - 1f)) * w
                canvas.nativeCanvas.drawText(label, x, plotBottom + labelHeight * 0.62f, paint)
            }
        }
    }
}

@Composable
private fun ExpandedEqDialog(
    bandGains: FloatArray,
    savedPresets: List<SavedEqPreset>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onBandLevelChanged: (Int, Float) -> Unit,
    onPresetSelected: (FloatArray) -> Unit,
    onSavePreset: (String, FloatArray) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    // Preset being targeted by the context menu (long-press)
    var contextMenuPreset by remember { mutableStateOf<SavedEqPreset?>(null) }
    val navigationPadding = WindowInsets.navigationBars.asPaddingValues()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 40.dp + navigationPadding.calculateBottomPadding()
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "15-band EQ",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Save and recall custom curves",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close EQ editor"
                        )
                    }
                }

                EqGraphWithStickyLabels(
                    bandGains = bandGains,
                    enabled = enabled,
                    expanded = true,
                    onTap = {},
                    onBandLevelChanged = onBandLevelChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Presets.forEach { (name, gains) ->
                        AssistChip(
                            onClick = { onPresetSelected(gains) },
                            label = { Text(name) },
                            enabled = enabled
                        )
                    }
                    savedPresets.forEach { preset ->
                        // Box wraps the chip so we can anchor a DropdownMenu to it
                        Box {
                            AssistChip(
                                onClick = { onPresetSelected(preset.gains) },
                                label = { Text(preset.name) },
                                enabled = enabled,
                                modifier = Modifier.pointerInput(preset.name) {
                                    detectTapGestures(
                                        onLongPress = { contextMenuPreset = preset }
                                    )
                                }
                            )
                            DropdownMenu(
                                expanded = contextMenuPreset?.name == preset.name,
                                onDismissRequest = { contextMenuPreset = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Overwrite with current EQ") },
                                    onClick = {
                                        onSavePreset(preset.name, bandGains.copyOf())
                                        contextMenuPreset = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Delete",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        onDeletePreset(preset.name)
                                        contextMenuPreset = null
                                    }
                                )
                            }
                        }
                    }
                    AssistChip(
                        onClick = { showSaveDialog = true },
                        label = { Text("Save current") }
                    )
                    AssistChip(
                        onClick = { onPresetSelected(FloatArray(EqBands.count) { 0f }) },
                        label = { Text("Reset flat") },
                        enabled = enabled
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                showSaveDialog = false
                onSavePreset(name, bandGains.copyOf())
            }
        )
    }
}

@Composable
private fun SavePresetDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var presetName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = "Save preset",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    singleLine = true,
                    label = { Text("Preset name") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { onSave(presetName) },
                        enabled = presetName.isNotBlank()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    useMaterialYou: Boolean,
    customPrimaryColor: Color,
    notificationListenerEnabled: Boolean,
    dumpPermissionEnabled: Boolean,
    onUseMaterialYouChanged: (Boolean) -> Unit,
    onCustomPrimaryColorChanged: (Color) -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onHelpRequested: (HelpContent) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close settings"
                        )
                    }
                }

                PermissionStatusRow(
                    title = "Notification sessions",
                    description = "Detects active playback through Android media notifications.",
                    enabled = notificationListenerEnabled,
                    actionLabel = "Open",
                    onAction = onOpenNotificationListenerSettings,
                    onHelp = { onHelpRequested(HelpContent.NotificationAccess) }
                )

                PermissionStatusRow(
                    title = "DUMP fallback",
                    description = "Optional ADB grant for deeper system audio scanning.",
                    enabled = dumpPermissionEnabled,
                    actionLabel = "Command",
                    onAction = { onHelpRequested(HelpContent.DumpPermission) },
                    onHelp = { onHelpRequested(HelpContent.DumpPermission) }
                )

                ToggleRow(
                    title = "Material You",
                    subtitle = "Match system wallpaper colors",
                    checked = useMaterialYou,
                    onCheckedChange = onUseMaterialYouChanged
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "UI Color Override",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (useMaterialYou) "Disable Material You to choose a custom primary color." else "Choose a primary color.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ColorPalette.forEach { color ->
                            val selected = color == customPrimaryColor
                            Box(
                                modifier = Modifier
                                    .size(if (selected) 42.dp else 36.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable(enabled = !useMaterialYou) {
                                        onCustomPrimaryColorChanged(color)
                                    }
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    description: String,
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onHelp: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(
                        onClick = onHelp,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "$title help",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (enabled) "Enabled" else "Disabled",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun HelpDialog(
    content: HelpContent,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.78f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Clean header ──────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = content.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // ── Scrollable body ──────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (content == HelpContent.HiResUpscaler) {
                        HiResUpscalerIllustration(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                    HelpBodyText(body = content.body)
                }
            }
        }
    }
}

@Composable
private fun HelpBodyText(body: String) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val lines = body.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            // Empty line → small spacer
            line.isBlank() -> {
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Bullet line
            line.trimStart().startsWith("•") -> {
                val bulletText = line.trimStart().removePrefix("•").trim()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp, end = 10.dp)
                            .size(6.dp)
                            .background(primary, shape = RoundedCornerShape(50))
                    )
                    Text(
                        text = bulletText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurface,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 20.sp
                    )
                }
            }
            // Regular text
            else -> {
                Text(
                    text = line.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
        i++
    }
}

@Composable
private fun HiResUpscalerIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val label = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(surface)
            .padding(8.dp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h * 0.55f
        val compressed = Path().apply {
            moveTo(0f, mid)
            for (i in 0..24) {
                val x = i / 24f * w
                val y = mid + kotlin.math.sin(i * 0.9f).toFloat() * h * 0.09f
                lineTo(x, y)
            }
        }
        val recovered = Path().apply {
            moveTo(0f, mid)
            for (i in 0..48) {
                val x = i / 48f * w
                val shimmer = kotlin.math.sin(i * 1.55f).toFloat() * h * 0.045f
                val body = kotlin.math.sin(i * 0.45f).toFloat() * h * 0.12f
                lineTo(x, mid + body + shimmer)
            }
        }
        drawPath(
            path = compressed,
            color = label.copy(alpha = 0.55f),
            style = Stroke(width = 4f)
        )
        drawPath(
            path = recovered,
            brush = Brush.horizontalGradient(listOf(primary, secondary)),
            style = Stroke(width = 6f)
        )
        drawCircle(
            color = primary.copy(alpha = 0.16f),
            radius = h * 0.35f,
            center = Offset(w * 0.78f, h * 0.42f)
        )
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = label.toArgb()
                textSize = 13.sp.toPx()
            }
            canvas.nativeCanvas.drawText("compressed", w * 0.08f, h * 0.18f, paint)
            canvas.nativeCanvas.drawText("natural detail recovery", w * 0.45f, h * 0.86f, paint)
        }
    }
}

@Composable
private fun SurroundModePickerDialog(
    currentMode: SurroundMode,
    onModeSelected: (SurroundMode) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Surround Mode",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "EQ-based widening, no resampling",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SurroundMode.Off.also { mode ->
                        SurroundModeCard(
                            mode = mode,
                            selected = currentMode == mode,
                            onClick = { onModeSelected(mode) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SurroundMode.Traditional.also { mode ->
                        SurroundModeCard(
                            mode = mode,
                            selected = currentMode == mode,
                            onClick = { onModeSelected(mode) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SurroundMode.Front.also { mode ->
                        SurroundModeCard(
                            mode = mode,
                            selected = currentMode == mode,
                            onClick = { onModeSelected(mode) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SurroundMode.Wide.also { mode ->
                        SurroundModeCard(
                            mode = mode,
                            selected = currentMode == mode,
                            onClick = { onModeSelected(mode) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SurroundModeCard(
    mode: SurroundMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                         else MaterialTheme.colorScheme.surfaceContainer
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = if (selected) 3.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (mode != SurroundMode.Off) {
                SurroundIllustration(
                    mode = mode,
                    modifier = Modifier
                        .size(64.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Off",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = mode.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = mode.tagline,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun SurroundIllustration(
    mode: SurroundMode,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Canvas(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.75f

        when (mode) {
            SurroundMode.Traditional -> {
                listOf(h * 0.28f, h * 0.50f, h * 0.72f).forEachIndexed { i, r ->
                    drawArc(
                        color = primary.copy(alpha = 0.15f + i * 0.14f),
                        startAngle = 195f, sweepAngle = 150f, useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = 3f + i * 1.5f)
                    )
                }
                drawCircle(color = primary, radius = 5f, center = Offset(cx, cy))
                listOf(w * 0.10f, w * 0.90f).forEach { x ->
                    drawCircle(color = secondary.copy(alpha = 0.55f), radius = 4f, center = Offset(x, h * 0.28f))
                }
            }
            SurroundMode.Front -> {
                listOf(h * 0.30f, h * 0.55f, h * 0.80f).forEachIndexed { i, r ->
                    drawArc(
                        color = primary.copy(alpha = 0.18f + i * 0.14f),
                        startAngle = 210f, sweepAngle = 120f, useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = 4f + i * 1.5f)
                    )
                }
                val spY = h * 0.30f
                val spSize = h * 0.16f
                val lX = w * 0.08f
                val rX = w - w * 0.08f
                drawPath(Path().apply {
                    moveTo(lX, spY - spSize / 2); lineTo(lX + spSize, spY)
                    lineTo(lX, spY + spSize / 2); close()
                }, color = secondary.copy(alpha = 0.70f))
                drawPath(Path().apply {
                    moveTo(rX, spY - spSize / 2); lineTo(rX - spSize, spY)
                    lineTo(rX, spY + spSize / 2); close()
                }, color = secondary.copy(alpha = 0.70f))
                drawCircle(color = primary, radius = 5f, center = Offset(cx, cy))
            }
            SurroundMode.Wide -> {
                listOf(h * 0.22f, h * 0.40f, h * 0.60f, h * 0.80f).forEachIndexed { i, r ->
                    drawArc(
                        color = primary.copy(alpha = 0.10f + i * 0.10f),
                        startAngle = 180f, sweepAngle = 180f, useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = 3f + i.toFloat())
                    )
                }
                listOf(180f, 210f, 240f, 270f, 300f, 330f, 360f).forEach { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    val len = h * 0.72f
                    drawLine(
                        color = secondary.copy(alpha = 0.18f),
                        start = Offset(cx, cy),
                        end = Offset(
                            cx + (kotlin.math.cos(rad) * len).toFloat(),
                            cy + (kotlin.math.sin(rad) * len).toFloat()
                        ),
                        strokeWidth = 2f
                    )
                }
                drawCircle(color = primary, radius = 6f, center = Offset(cx, cy))
                listOf(w * 0.12f to h * 0.22f, w * 0.88f to h * 0.22f,
                       w * 0.03f to h * 0.52f, w * 0.97f to h * 0.52f).forEach { (x, y) ->
                    drawCircle(color = secondary.copy(alpha = 0.55f), radius = 3.5f, center = Offset(x, y))
                }
            }
            else -> {}
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 1.dp),
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun CompactToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
    onHelpClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            leadingIcon?.invoke()
            if (leadingIcon != null) Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onHelpClick != null) {
                        Spacer(Modifier.width(2.dp))
                        IconButton(onClick = onHelpClick, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}
