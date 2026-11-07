package com.jadoo.amp.ui

import java.util.Locale

import android.graphics.Rect
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.draw.scale
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Replay
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import com.jadoo.amp.update.UpdateCheckControls
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jadoo.amp.audio.DbfbMode
import com.jadoo.amp.audio.DigitalFilterEngine
import com.jadoo.amp.audio.HdrMode
import com.jadoo.amp.audio.EqBands
import com.jadoo.amp.audio.SurroundMode
import com.jadoo.amp.settings.SavedEqPreset
import com.jadoo.amp.ui.components.AppearanceSection
import com.jadoo.amp.update.AUTOEQ_ENABLED
import com.jadoo.amp.ui.components.Entrance
import com.jadoo.amp.ui.components.FeatureGroup
import com.jadoo.amp.ui.components.FeatureRow
import com.jadoo.amp.ui.components.GroupDivider
import com.jadoo.amp.ui.components.GroupHeader
import com.jadoo.amp.ui.components.NavRow
import com.jadoo.amp.ui.components.PowerButton
import com.jadoo.amp.ui.components.ResponseCurveHero
import com.jadoo.amp.ui.components.StatusChip
import com.jadoo.amp.ui.components.jadooBackdrop
import com.jadoo.amp.ui.components.SettingToggleRow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.jadoo.amp.ui.theme.JadooTheme
import androidx.compose.ui.geometry.Size
import kotlin.math.roundToInt

private val Presets = mapOf(
    "Acoustic" to floatArrayOf(3f, 2.5f, 2f, 1.2f, 0.5f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2.4f, 2.2f, 1.5f, 0.8f),
    "Bass" to floatArrayOf(6f, 5.5f, 4.5f, 3f, 1.8f, 0.5f, 0f, -0.5f, -0.5f, 0f, 0.5f, 1f, 0.5f, 0f, 0f),
    "Beats" to floatArrayOf(5f, 4.5f, 4f, 2.5f, 1f, -0.5f, -1f, -0.5f, 0.5f, 1.5f, 3f, 4f, 3f, 2f, 1f),
    "Classic" to floatArrayOf(2f, 2f, 1.5f, 0.5f, 0f, 0f, -0.5f, -0.5f, 0f, 0.5f, 1f, 2f, 2.5f, 2f, 1f),
    "Clear" to floatArrayOf(-1f, -0.5f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2f, 2.5f, 3f, 3f, 2.5f, 2f, 1.5f),
    // Soulful Mids: vocal intimacy preset. Warmth body (160-400Hz) + deep vocal peak (1-2.5kHz)
    // + boxiness cut at 630Hz so warmth breathes. Bass slightly pulled back to keep mids
    // the center of gravity. Gentle air above 4kHz — present but never fatiguing.
    "Soulful Mids" to floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1.5f, 2f, 1.2f, -0.8f, 3f, 3.5f, 3f, 2f, 1f, 0.5f, 0f)
)

// The seed swatches moved to ui/theme/Color.kt as SeedSwatches, so the brand
// gold leads the list and the set is shared with the Appearance section.

private sealed class HelpContent(
    val title: String,
    val body: String
) {
    data object HiResUpscaler : HelpContent(
        title = "Hi-Res Upscaler",
        body = "Lifts the top octaves for more air and sparkle: +2.5 dB from 5 kHz, rising to +5.5 dB above 14.5 kHz.\n\nIt brightens what's there - it can't bring back detail a compressed file has already lost."
    )

    data object Dbfb : HelpContent(
        title = "JadOO DBFB",
        body = "Adds level-aware sub-bass weight.\n\n• **Normal** - rounded, subtle lift\n• **High** - deeper and more assertive"
    )

    data object HdrDynamics : HelpContent(
        title = "HDR Dynamics Restorer",
        body = "Restores punch to loudness-war audio.\n\n• **Pure** - near-transparent\n• **Restoration** - widens quiet-to-loud range, adds a touch of air"
    )

    data object AnalogBass : HelpContent(
        title = "Analog Bass",
        body = "Vintage hardware-style bass coloring, built from bass compression and EQ.\n\n• **Drive** - compresses the bass harder, for a denser low end\n• **Warmth** - adds bass level\n• **Drift** - softens how the compression eases in\n• **Pultec EQ** - shapes the low end"
    )

    data object SpatialSurround : HelpContent(
        title = "JadOO Surround+",
        body = "Widens the sound through EQ shaping alone - vocals stay centered.\n\n• **Traditional** - natural width\n• **Front Stage** - pushes vocals forward\n• **Ultra Wide** - the most spacious"
    )

    data object DumpPermission : HelpContent(
        title = "DUMP permission",
        body = "Optional - helps detect the active audio session.\n\nGrant via:\n\n**adb shell pm grant com.jadoo.amp android.permission.DUMP**"
    )

    data object TubeWarmth : HelpContent(
        title = "Tube Warmth",
        body = "Valve-amp tonal character - gentle low-end bloom and a soft high-end roll-off. Good for thin or clinical-sounding tracks."
    )

    data object MobileBass : HelpContent(
        title = "JadOO Mobile Bass",
        body = "Adds fuller bass on your phone's own speaker. Won't go as deep as a real subwoofer, but noticeably fuller than stock.\n\nOnly shown when playing through the speaker."
    )

    data object HarmonicExciter : HelpContent(
        title = "Harmonic Exciter",
        body = "Adds presence and sparkle to the 2-8kHz range. Kept gentle on purpose - push it too far on bright tracks and it can edge toward harsh.\n\nWorks on every output."
    )

    data object DigitalFilters : HelpContent(
        title = "Parametric EQ",
        body = "Up to 16 filters - peaks, shelves and passes - on top of the graphic EQ.\n\nThe engine applies them through the same 15 bands as the graphic EQ, so very narrow filters come out broader than set. The curve on the home screen shows what's really applied."
    )

    data object SbcEnhancement : HelpContent(
        title = "SBC Enhancement",
        body = "Two versions - switch by ear.\n\n• **Classic** - the original: lifts 10-16 kHz to push SBC's bits into the treble. Brighter, more open.\n• **New** - conditions the signal for the encoder, described below. Smoother, fewer artifacts.\n\nNew conditions the signal before it reaches the SBC encoder, so the codec has an easier job.\n\nSBC hands its bits out in proportion to how loud each frequency region is, and it re-decides every 3 milliseconds. The top octave is the most expensive region to encode and the least audible, so it is rolled off - that frees bits for the range you actually hear. The 8-12kHz air is lifted slightly to buy the treble back where bits are cheap.\n\nOn top of that the highs are gently peak-controlled. That steadies SBC's bit allocation, which is what stops cymbals and sibilance breaking up into the swirling, watery sound SBC is known for.\n\nEnable only for **SBC** devices - not LDAC, LHDC, or aptX HD. If your headphones support one of those, switching to it beats anything this feature can do."
    )

    data object LoudnessContour : HelpContent(
        title = "Loudness Contour",
        body = "Bass falls away faster than mids at low volume. This tilts the EQ back to compensate as you turn down, so the balance stays put.\n\n## Reference Level\nHow loud your gear actually gets at full volume - set it by ear against a track you know.\n\n## Amount\nScales the whole correction if full strength feels like too much."
    )


    data object PerAppProfiles : HelpContent(
        title = "Per-app profiles",
        body = "Gives each app its own DSP settings, switched automatically when it starts playing - e.g. a speech tilt for podcasts, full curve for music.\n\nStored per app and per output device."
    )

    data object GainStaging : HelpContent(
        title = "Precise Gain Staging",
        body = "Every boost (bass, treble, Loudness, Surround, EQ) needs room above a loud song, or its peaks clip.\n\n## On\nOn wired and speaker, the input is trimmed just enough to keep the mix as loud as with the effects off. Below full volume the phone's own volume already makes that room.\n\nBluetooth always arrives at full level, so there boosts are level-aware instead: whole at normal levels, fading out on a song's hottest moments. Nothing is turned down to make room, and turning a feature on doesn't cost volume.\n\n## Off\nNo protection. Loud songs can clip when several boosts are on.\n\nSafe to leave on. Turn off only to compare against how the app used to sound."
    )

}

/**
 * Bluetooth on absolute volume hands JadOO full scale at every slider
 * position, so the boosts only fit after the input trim. With absolute
 * volume off the phone turns the level down BEFORE JadOO, which gives the
 * boosts their room back below full volume — the same as a wired route.
 * The setting lives in Developer options, which may still be locked.
 */
@Composable
private fun BluetoothHeadroomTip(trimDb: Float, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(14.dp))
                Text("Give Bluetooth more headroom",
                     style = MaterialTheme.typography.titleSmall,
                     color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                String.format(Locale.US,
                    "Your earbuds get full volume all the time, so JadOO lowers its input by %.1f dB " +
                    "to keep your boosts from clipping. Turn on Disable absolute volume in Developer " +
                    "options and reconnect: the phone's volume then makes that room instead.", -trimDb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = { openDeveloperOptions(context) }) { Text("Open settings") }
            }
        }
    }
}

private fun openDeveloperOptions(context: android.content.Context) {
    val unlocked = android.provider.Settings.Global.getInt(context.contentResolver,
        android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    val action = if (unlocked) android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                 else android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS
    if (!unlocked) {
        android.widget.Toast.makeText(context,
            "Tap Build number 7 times to unlock Developer options, then come back",
            android.widget.Toast.LENGTH_LONG).show()
    }
    try {
        context.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }
}

/** Picks a representative icon for the current output device label (see JadooDspService.computeOutputDeviceKey). */
private fun outputDeviceIcon(deviceLabel: String): ImageVector = when {
    deviceLabel.startsWith("Bluetooth") -> Icons.Default.Bluetooth
    deviceLabel.startsWith("USB") -> Icons.Default.Usb
    deviceLabel == "Wired Headphones" -> Icons.Default.Headset
    else -> Icons.AutoMirrored.Filled.VolumeUp
}

@Composable
fun DashboardScreen(
    sessionId: Int?,
    isAttached: Boolean,
    activePackageName: String?,
    currentOutputDevice: String,
    masterEnabled: Boolean,
    dspBypassed: Boolean,
    preGainDb: Float,
    postGainDb: Float,
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
    // Tube Warmth
    tubeWarmthEnabled: Boolean,
    tubeWarmthIntensity: Float,
    // Mobile Bass
    mobileBassEnabled: Boolean,
    mobileBassIntensity: Float,
    // Harmonic Exciter
    harmonicExciterEnabled: Boolean,
    harmonicExciterIntensity: Float,
    // Digital Filters
    digitalFilterEnabled: Boolean,
    digitalFilterBandStates: List<com.jadoo.amp.audio.DigitalFilterEngine.BiquadBandState>,
    savedPresets: List<SavedEqPreset>,
    themeSettings: com.jadoo.amp.settings.ThemeSettings,
    dumpPermissionEnabled: Boolean,
    deviceType: com.jadoo.amp.audio.DeviceType,
    // Loudness Contour (ISO 226)
    loudnessEnabled: Boolean,
    loudnessAmount: Float,
    loudnessReferencePhon: Float,
    loudnessCurrentPhon: Float,
    // Gain staging
    preciseGainStaging: Boolean,
    gainBudgetDb: Float,
    autoTrimDb: Float,
    // Bluetooth on absolute volume while boosts are being trimmed — see BluetoothHeadroomTip
    showBluetoothHeadroomTip: Boolean,
    onDismissBluetoothHeadroomTip: () -> Unit,
    // Selected EQ preset name — persisted, see JadooDspService.selectedPresetName
    selectedPresetName: String,
    // Live 15-band tonal response the chain is writing — the hero curve
    responseCurveDb: FloatArray,
    // AutoEQ correction applied to this output's PEQ, "Model · lab", or ""
    autoEqProfileName: String,
    // Per-app profiles
    activeAppPackage: String?,
    perAppProfileActive: Boolean,
    perAppProfilePackages: Set<String>,
    // Content channel (Lane A)
    // Manually selectable content-channel device tunings — see the picker in
    // SettingsScreen for why auto-matching alone is not enough.
    onMasterPowerToggled: (Boolean) -> Unit,
    onPreGainChanged: (Float) -> Unit,
    onPostGainChanged: (Float) -> Unit,
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
    // Tube Warmth callbacks
    onTubeWarmthEnabledChanged: (Boolean) -> Unit,
    onTubeWarmthIntensityChanged: (Float) -> Unit,
    // Mobile Bass callbacks
    onMobileBassEnabledChanged: (Boolean) -> Unit,
    onMobileBassIntensityChanged: (Float) -> Unit,
    // Harmonic Exciter callbacks
    onHarmonicExciterEnabledChanged: (Boolean) -> Unit,
    onHarmonicExciterIntensityChanged: (Float) -> Unit,
    onDigitalFilterEnabledChanged: (Boolean) -> Unit,
    onDigitalFilterBandEnabledChanged: (Int, Boolean) -> Unit,
    onDigitalFilterBandTypeChanged: (Int, DigitalFilterEngine.FilterType) -> Unit,
    onDigitalFilterBandFrequencyChanged: (Int, Float) -> Unit,
    onDigitalFilterBandGainChanged: (Int, Float) -> Unit,
    onDigitalFilterBandQChanged: (Int, Float) -> Unit,
    onThemeModeChanged: (Offset, String) -> Unit,
    onToneModeChanged: (Offset, String) -> Unit,
    onBackdropChanged: (Offset, String) -> Unit,
    onSeedColorChanged: (Offset, Color) -> Unit,
    onDeviceTypeChanged: (com.jadoo.amp.audio.DeviceType) -> Unit,
    onResetDigitalFilterBands: () -> Unit,
    // AutoEQ headphone database → Parametric EQ
    onApplyAutoEq: (com.jadoo.amp.audio.AutoEq.Profile, String) -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    // SBC Enhancement (Bluetooth-only)
    sbcModeEnabled: Boolean,
    onSbcModeEnabledChanged: (Boolean) -> Unit,
    sbcStyle: com.jadoo.amp.audio.SbcStyle,
    onSbcStyleChanged: (com.jadoo.amp.audio.SbcStyle) -> Unit,
    // Custom/imported profiles
    savedProfileNames: List<String>,
    onLoadProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    // Loudness Contour callbacks
    onLoudnessEnabledChanged: (Boolean) -> Unit,
    onLoudnessAmountChanged: (Float) -> Unit,
    onLoudnessReferencePhonChanged: (Float) -> Unit,
    // Gain staging callbacks
    onPreciseGainStagingChanged: (Boolean) -> Unit,
    onSelectedPresetNameChanged: (String?) -> Unit,
    // Per-app profile callbacks
    onPerAppProfileToggled: (String, Boolean) -> Unit,
    // Content channel callbacks
) {
    var showExpandedEq by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // Live progress (0..1) of an in-flight back gesture on the Settings
    // screen — drives its slide/scale/fade below so the screen visibly
    // tracks the finger instead of only animating once the gesture
    // completes. Driven from two sources: PredictiveBackHandler below (the
    // system API — confirmed it dispatches the back press itself correctly
    // on-device, but this ROM's SystemUI never delivers the per-frame
    // progress events, only silence then a completion) and settingsDragProgress,
    // written synchronously by a hand-rolled edge-swipe gesture on the
    // Settings Box further down, the same way Telegram's own swipe-back
    // works rather than relying on the OS preview. The two never move in
    // the same gesture, so taking their max is a safe way to read "whichever
    // is currently live" without extra coordination.
    val settingsBackProgress = remember { Animatable(0f) }
    val settingsDragProgress = remember { mutableFloatStateOf(0f) }
    val settingsBackSpec = JadooTheme.motion.standardSpec<Float>()
    val settingsBackScope = rememberCoroutineScope()
    // A touch starting from the true screen edge never reaches the app on
    // this device — confirmed with all three of the standard Android
    // techniques: PredictiveBackHandler (dispatches the completed back
    // press, but this ROM never forwards per-frame progress), a hand-rolled
    // pointerInput gesture, and systemGestureExclusionRects below (normally
    // how Telegram's own edge swipe-back claims those touches back from the
    // system). All three work correctly the moment a touch can reach the
    // app at all — confirmed via a mid-screen swipe — so this is the OS/ROM
    // not honouring third-party gesture exclusion at the true edge, not a
    // bug here. A swipe starting at the literal edge pixel still closes
    // Settings correctly (the OS back dispatch works); it just can't
    // preview live on this specific device. Kept anyway since it costs
    // nothing and may work on other OEMs/ROMs.
    val settingsBackView = LocalView.current
    val settingsBackDensity = LocalDensity.current
    DisposableEffect(showSettings) {
        if (showSettings && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val exclusionWidthPx = with(settingsBackDensity) { 32.dp.roundToPx() }
            settingsBackView.systemGestureExclusionRects = listOf(Rect(0, 0, exclusionWidthPx, 10000))
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settingsBackView.systemGestureExclusionRects = emptyList()
            }
        }
    }
    PredictiveBackHandler(enabled = showSettings) { events ->
        try {
            events.collect { event -> settingsBackProgress.snapTo(event.progress) }
            settingsBackProgress.snapTo(0f)
            showSettings = false
        } catch (cancelled: CancellationException) {
            settingsBackProgress.animateTo(0f, settingsBackSpec)
        }
    }
    var showGainControls by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf<HelpContent?>(null) }
    var showSurroundPicker by remember { mutableStateOf(false) }
    var showParametricEq by remember { mutableStateOf(false) }
    val manualControlsEnabled = masterEnabled
    // Derive initial EQ-enabled state from the restored band gains so that after
    // orientation change or process death the toggle correctly reflects what the
    // service is actually applying (instead of always resetting to "off").
    var graphicEqEnabled by remember { mutableStateOf(bandGains.any { it != 0f }) }
    var showGraphicEq by remember { mutableStateOf(bandGains.any { it != 0f }) }
    // Promote to "enabled" whenever the service reports non-zero gains (rebind,
    // restore, import). Never demote — the user controls the "off" direction
    // via the toggle. FloatArray uses reference equality so this fires on every
    // new array from the service, but the promotion-only guard makes that safe.
    val hasNonZeroBands = bandGains.any { it != 0f }
    LaunchedEffect(hasNonZeroBands) {
        if (hasNonZeroBands && !graphicEqEnabled) {
            graphicEqEnabled = true
            showGraphicEq = true
        }
    }
    var savedBandGainsBeforeDisable by remember { mutableStateOf<FloatArray?>(null) }
    var showLiveChain by remember { mutableStateOf(false) }
    var showAutoEq by remember { mutableStateOf(false) }
    var autoEqQuery by remember { mutableStateOf("") }
    // Name of the preset (built-in or custom) the user last selected — now a
    // persisted prop (see JadooDspService.selectedPresetName), not local
    // remember state, so it survives a relaunch instead of resetting to
    // none every cold start. "" (unset) is normalised to null here so the
    // rest of this function can keep using the nullable convention
    // ExpandedEqDialog already expects.
    val selectedPresetName = selectedPresetName.ifBlank { null }
    val statusText = when {
        isAttached && dspBypassed -> "Bypass · ${activePackageName ?: "Session ${sessionId ?: 0}"} (no effects active)"
        isAttached -> "DSP active · ${activePackageName ?: "Session ${sessionId ?: 0}"}"
        masterEnabled -> "Waiting for playback"
        else -> "Engine off"
    }

    // Read-only summary for the pipeline inspector — the switches stay on
    // the cards below; the inspector only names what is switched on.
    val liveChainState = LiveChainState(
        appLabel = activePackageName,
        appPackage = activeAppPackage,
        sessionId = sessionId,
        masterEnabled = masterEnabled,
        dspBypassed = dspBypassed,
        preGainDb = preGainDb,
        postGainDb = postGainDb,
        autoTrimDb = autoTrimDb,
        activeStages = buildList {
            if (graphicEqEnabled) add(selectedPresetName?.let { "Graphic EQ ($it)" } ?: "Graphic EQ")
            if (digitalFilterEnabled) add("Parametric EQ")
            if (loudnessEnabled) add("Loudness Contour")
            if (surroundMode != SurroundMode.Off) add("Surround+ ${surroundMode.displayName}")
            if (tubeWarmthEnabled) add("Tube Warmth")
            if (dbfbMode != DbfbMode.Off) add("DBFB ${dbfbMode.displayName}")
            if (analogBassEnabled && surroundMode != SurroundMode.Front) add("Analog Bass")
            if (hdrDynamicsEnabled) add("HDR ${hdrMode.displayName}")
            if (hiResUpscalerEnabled) add("Hi-Res Upscaler")
            if (harmonicExciterEnabled) add("Harmonic Exciter")
            if (mobileBassEnabled && currentOutputDevice == "Phone Speaker") add("Mobile Bass")
            if (sbcModeEnabled && currentOutputDevice.startsWith("Bluetooth")) add("SBC Enhancement")
        },
        outputLabel = currentOutputDevice,
        sbcModeEnabled = sbcModeEnabled,
        surroundMode = surroundMode,
        dumpPermissionEnabled = dumpPermissionEnabled
    )

    // Rendered BEFORE the showSettings branch/return below, not after it —
    // it used to sit at the very end of this function, past the early
    // `return` the Settings branch takes. Settings' own (i) help buttons set
    // `helpDialog` correctly, but the recomposition that followed took the
    // SAME early-return branch, so this block — HelpDialog's only render
    // site — was structurally unreachable the entire time Settings was open.
    // Tapping a help button looked like it did nothing; navigating back to
    // the dashboard (showSettings=false, falls through past the return) was
    // the first time this code could run at all, by which point helpDialog
    // was still set from the earlier tap — "doesn't open until you go back."
    helpDialog?.let { content ->
        HelpDialog(
            content = content,
            onDismiss = { helpDialog = null }
        )
    }

    // ── Layout ───────────────────────────────────────────────────────
    // Single scrolling page, sections in signal-chain order (EQ shapes it,
    // Tone colours it, Level controls how loud it is, Space places it, Out
    // adapts it to what it's coming out of). A 5-tab bottom-nav split was
    // built and tried; it made every screen feel sparse and lost the
    // at-a-glance overview the single page gives you scrolling past a
    // section you're not touching. Reverted deliberately, back to one page.
    //
    // Settings is a full-screen swap, not a Dialog — a Dialog is a genuinely
    // separate floating window with a dim scrim around a card that doesn't
    // fill the height, which reads as "popup," not "screen." Swapping the
    // whole root content (this if/else) instead of overlaying a Dialog gives
    // Settings the same real-screen feel ParametricEqScreen was always meant
    // to have, and matches this app's own no-navigation-library architecture
    // (see the plan file) — no back stack needed for one destination.
    AnimatedVisibility(
        visible = showSettings,
        enter = slideInHorizontally(JadooTheme.motion.standardSpec()) { it } + fadeIn(JadooTheme.motion.fade),
        exit = slideOutHorizontally(JadooTheme.motion.standardSpec()) { it } + fadeOut(JadooTheme.motion.fade)
    ) {
        Box(
            modifier = Modifier.pointerInput(Unit) {
                // Hand-rolled edge-swipe-to-go-back, independent of whether
                // this ROM's SystemUI actually delivers predictive-back
                // preview frames (confirmed on-device it doesn't, though the
                // completed gesture does still close the screen via
                // PredictiveBackHandler above).
                //
                // A THIN edge-hugging activation zone (originally 24dp) never
                // received any touches at all — that strip is exactly where
                // gesture-nav Android reserves the system's own back-swipe
                // detection, so those touches are consumed before they ever
                // reach the app's pointerInput, same failure mode as the
                // predictive-back API itself. Apps that build their own
                // swipe-back (Telegram included) sidestep this by starting
                // the recognizer well clear of that reserved sliver — a wide
                // activation zone, not a thin one. Direction disambiguation
                // (dx>8dp before consuming) is what keeps this from stealing
                // the Settings list's own scroll/tap gestures, not the zone.
                val activationZonePx = size.width * 0.5f
                val commitThreshold = size.width * 0.35f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > activationZonePx) return@awaitEachGesture
                    var directionKnown = false
                    var dragging = false
                    var lastDx = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!directionKnown) {
                            if (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f) {
                                directionKnown = true
                                // Rightward and horizontal-dominant only —
                                // anything else (a scroll, a leftward
                                // swipe) is left untouched for the
                                // Settings list's own gestures.
                                dragging = dx > 0f && dx > kotlin.math.abs(dy)
                                if (!dragging) break
                            }
                        }
                        if (dragging) {
                            change.consume()
                            lastDx = dx
                            // Plain state write, not the Animatable — this
                            // loop runs on awaitPointerEventScope's
                            // restricted coroutine, which cannot call
                            // suspend functions outside it (Animatable.snapTo
                            // included).
                            settingsDragProgress.floatValue = (dx / size.width).coerceIn(0f, 1f)
                        }
                    }
                    if (dragging) {
                        val settledProgress = lastDx / size.width
                        settingsBackScope.launch {
                            // Hand off to the Animatable at the exact value
                            // the drag left off at, then release the plain
                            // state — no visible jump, since both agree at
                            // this instant.
                            settingsBackProgress.snapTo(settledProgress.coerceIn(0f, 1f))
                            settingsDragProgress.floatValue = 0f
                            if (lastDx > commitThreshold) {
                                showSettings = false
                                settingsBackProgress.snapTo(0f)
                            } else {
                                settingsBackProgress.animateTo(0f, settingsBackSpec)
                            }
                        }
                    }
                }
            }
        ) {
        SettingsScreen(
            modifier = Modifier.graphicsLayer {
                val progress = maxOf(settingsBackProgress.value, settingsDragProgress.floatValue)
                translationX = progress * size.width
                scaleX = 1f - progress * 0.06f
                scaleY = 1f - progress * 0.06f
                alpha = 1f - progress * 0.25f
            },
            themeSettings = themeSettings,
            onThemeModeChanged = onThemeModeChanged,
            onToneModeChanged = onToneModeChanged,
            onBackdropChanged = onBackdropChanged,
            onSeedColorChanged = onSeedColorChanged,
            dumpPermissionEnabled = dumpPermissionEnabled,
            currentOutputDevice = currentOutputDevice,
            deviceType = deviceType,
            onDeviceTypeChanged = onDeviceTypeChanged,
            onHelpRequested = { helpDialog = it },
            onExportSettings = onExportSettings,
            onImportSettings = onImportSettings,
            savedProfileNames = savedProfileNames,
            onLoadProfile = { name -> onLoadProfile(name); showSettings = false },
            onDeleteProfile = onDeleteProfile,
            activeAppPackage = activeAppPackage,
            activeAppLabel = activePackageName,
            perAppProfilePackages = perAppProfilePackages,
            onPerAppProfileToggled = onPerAppProfileToggled,
            preciseGainStaging = preciseGainStaging,
            gainBudgetDb = gainBudgetDb,
            autoTrimDb = autoTrimDb,
            onPreciseGainStagingChanged = onPreciseGainStagingChanged,
            autoEqProfileName = autoEqProfileName,
            onOpenAutoEq = { autoEqQuery = ""; showAutoEq = true },
            onRemoveAutoEq = {
                onResetDigitalFilterBands()
                onPreGainChanged(0f)
            },
            onNavigateBack = { showSettings = false }
        )
        }
    }
    AnimatedVisibility(
        visible = !showSettings,
        enter = slideInHorizontally(JadooTheme.motion.standardSpec()) { -it / 4 } + fadeIn(JadooTheme.motion.fade),
        exit = slideOutHorizontally(JadooTheme.motion.standardSpec()) { -it / 4 } + fadeOut(JadooTheme.motion.fade)
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .jadooBackdrop()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JadooTheme.dimens.screenPadding)
            .padding(top = 4.dp, bottom = 40.dp)
    ) {
            // ── Top bar: wordmark, where the sound is going, settings ──────
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("JadOO", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                     modifier = Modifier.weight(1f))
                StatusChip(
                    icon = outputDeviceIcon(currentOutputDevice),
                    text = currentOutputDevice.removePrefix("Bluetooth: ").removePrefix("USB: ")
                        .let { if (perAppProfileActive) "$it · app" else it },
                    onClick = { showLiveChain = true }
                )
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "Settings")
                }
            }

            // ── Hero: state, power, and the live response curve ───────────
            Entrance(0) {
                Column(Modifier.padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    !masterEnabled -> "Off"
                                    dspBypassed || !isAttached -> "Standing by"
                                    else -> "Processing"
                                },
                                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                when {
                                    !masterEnabled -> "Tap power to process ${currentOutputDevice.lowercase().let { if (it == "phone speaker") "the speaker" else currentOutputDevice }}"
                                    !isAttached -> "Waiting for playback"
                                    dspBypassed -> "Nothing switched on"
                                    else -> listOfNotNull(activePackageName,
                                        "${liveChainState.activeStages.size} feature" +
                                            if (liveChainState.activeStages.size == 1) "" else "s").joinToString(" · ")
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PowerButton(on = masterEnabled, onToggle = onMasterPowerToggled)
                    }
                    ResponseCurveHero(
                        gainsDb = responseCurveDb,
                        active = masterEnabled && !dspBypassed,
                        modifier = Modifier.fillMaxWidth().height(176.dp).padding(top = 16.dp)
                    )
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { showGainControls = !showGainControls }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Tonal response", style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            String.format(Locale.US, "In %+.1f · Out %+.1f dB", preGainDb, postGainDb),
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(Icons.Default.ExpandMore, if (showGainControls) "Hide gain" else "Show gain",
                             tint = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.size(20.dp).graphicsLayer {
                                 rotationZ = if (showGainControls) 180f else 0f
                             })
                    }
                    AnimatedVisibility(visible = showGainControls,
                        enter = expandVertically(JadooTheme.motion.standardSize) + fadeIn(JadooTheme.motion.fade),
                        exit = shrinkVertically(JadooTheme.motion.standardSize) + fadeOut(JadooTheme.motion.fade)) {
                        GainControlDeck(
                            preGainDb, postGainDb,
                            onPreGainChanged, onPostGainChanged,
                            onResetAll = {
                                onPreGainChanged(0f)
                                onPostGainChanged(0f)
                            }
                        )
                    }
                }
            }

            // ── Signal path ───────────────────────────────────────────────
            Entrance(1) {
                Column {
                    GroupHeader("Signal path")
                    LiveChainCard(state = liveChainState, onOpen = { showLiveChain = true })
                }
            }

            // ── Headphone correction suggestion ──────────────────────────
            // Offered (never applied) when a named headphone is connected and
            // this output has no AutoEQ correction yet.
            val connectedModel = currentOutputDevice.takeIf {
                it.startsWith("Bluetooth: ") || it.startsWith("USB: ")
            }?.substringAfter(": ")
            AnimatedVisibility(
                visible = AUTOEQ_ENABLED && masterEnabled && connectedModel != null && autoEqProfileName.isBlank(),
                enter = expandVertically(JadooTheme.motion.standardSize) + fadeIn(JadooTheme.motion.fade),
                exit = shrinkVertically(JadooTheme.motion.standardSize) + fadeOut(JadooTheme.motion.fade)
            ) {
                Surface(
                    onClick = { autoEqQuery = connectedModel ?: ""; showAutoEq = true },
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Correct ${connectedModel ?: "your headphones"}",
                                 style = MaterialTheme.typography.titleSmall,
                                 color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Find its measured EQ in the AutoEQ database",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                             tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            AnimatedVisibility(
                visible = masterEnabled && showBluetoothHeadroomTip && autoTrimDb <= -1f,
                enter = expandVertically(JadooTheme.motion.standardSize) + fadeIn(JadooTheme.motion.fade),
                exit = shrinkVertically(JadooTheme.motion.standardSize) + fadeOut(JadooTheme.motion.fade)
            ) {
                BluetoothHeadroomTip(trimDb = autoTrimDb, onDismiss = onDismissBluetoothHeadroomTip)
            }

            // ── Tone ──────────────────────────────────────────────────────
            Entrance(2) {
                Column {
                    GroupHeader("Tone", trailing = selectedPresetName?.takeIf { graphicEqEnabled })
                    FeatureGroup {
                        FeatureRow(
                            icon = Icons.Default.GraphicEq,
                            title = "Graphic EQ",
                            subtitle = if (graphicEqEnabled) "15 bands · tap the graph to expand" else "15-band equalizer",
                            checked = graphicEqEnabled,
                            enabled = manualControlsEnabled,
                            fullWidthContent = true,
                            onCheckedChange = { enabled ->
                                graphicEqEnabled = enabled
                                if (enabled) {
                                    showGraphicEq = true
                                    savedBandGainsBeforeDisable?.let { onPresetSelected(it) }
                                } else {
                                    showGraphicEq = false
                                    savedBandGainsBeforeDisable = bandGains.copyOf()
                                    onPresetSelected(FloatArray(EqBands.count))
                                }
                            }
                        ) {
                            EqGraphWithStickyLabels(bandGains = bandGains, enabled = manualControlsEnabled,
                                expanded = false, onTap = { showExpandedEq = true },
                                onBandLevelChanged = onBandLevelChanged,
                                modifier = Modifier.fillMaxWidth().height(220.dp))
                        }
                        GroupDivider()
                        FeatureRow(
                            icon = Icons.Default.Tune,
                            title = "Parametric EQ",
                            subtitle = autoEqProfileName.takeIf { it.isNotBlank() }?.let { "AutoEQ · $it" }
                                ?: "16 filters with Q and filter types",
                            checked = digitalFilterEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = onDigitalFilterEnabledChanged,
                            onHelpClick = { helpDialog = HelpContent.DigitalFilters }
                        ) {
                            FilledTonalButton(onClick = { showParametricEq = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Edit filters")
                            }
                        }
                        if (AUTOEQ_ENABLED) {
                            GroupDivider()
                            NavRow(
                                icon = Icons.Default.Headphones,
                                title = "Headphone correction",
                                value = autoEqProfileName.ifBlank { "Search 8,000+ measured headphones" },
                                accent = autoEqProfileName.isNotBlank() && digitalFilterEnabled,
                                enabled = masterEnabled,
                                onClick = { autoEqQuery = ""; showAutoEq = true }
                            )
                        }
                    }
                }
            }

            // ── Character ─────────────────────────────────────────────────
            val analogBassBlockedByFront = surroundMode == SurroundMode.Front
            Entrance(3) {
                Column {
                    GroupHeader("Character")
                    FeatureGroup {
                        FeatureRow(
                            icon = Icons.Default.Radio,
                            title = "Analog Bass",
                            subtitle = when {
                                analogBassBlockedByFront -> "Not available with Front Stage"
                                analogBassEnabled -> "Drive, warmth and Pultec low end"
                                else -> "Vintage hardware bass"
                            },
                            checked = analogBassEnabled && !analogBassBlockedByFront,
                            enabled = masterEnabled && !analogBassBlockedByFront,
                            onCheckedChange = onAnalogBassEnabledChanged,
                            onHelpClick = { helpDialog = HelpContent.AnalogBass }
                        ) {
                            LabeledSlider(label = "Drive", value = analogBassDrive,
                                valueLabel = "${String.format(Locale.US, "%.0f", analogBassDrive * 100)}%",
                                onValueChange = onAnalogBassDriveChanged, steps = 100)
                            LabeledSlider(label = "Warmth", value = analogBassWarmth,
                                valueLabel = "${String.format(Locale.US, "%.0f", analogBassWarmth * 100)}%",
                                onValueChange = onAnalogBassWarmthChanged, steps = 100)
                            LabeledSlider(label = "Drift", value = analogBassDrift,
                                valueLabel = "${String.format(Locale.US, "%.0f", analogBassDrift * 100)}%",
                                onValueChange = onAnalogBassDriftChanged, steps = 100)
                            Text(
                                if (mobileBassEnabled) "Pultec EQ · inactive while Mobile Bass is on" else "Pultec EQ",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (mobileBassEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                listOf("20 Hz", "30 Hz", "60 Hz", "100 Hz").forEachIndexed { idx, label ->
                                    FilterChip(selected = analogBassPultecFreqIndex == idx,
                                               onClick = { onAnalogBassPultecFreqIndexChanged(idx) },
                                               label = { Text(label) })
                                }
                            }
                            LabeledSlider(label = "Boost", value = analogBassPultecBoost,
                                valueLabel = "+${String.format(Locale.US, "%.1f", analogBassPultecBoost * 8)} dB",
                                onValueChange = onAnalogBassPultecBoostChanged, steps = 80)
                            LabeledSlider(label = "Cut", value = analogBassPultecCut,
                                valueLabel = "-${String.format(Locale.US, "%.1f", analogBassPultecCut * 5)} dB",
                                onValueChange = onAnalogBassPultecCutChanged, steps = 40)
                            TextButton(onClick = {
                                onAnalogBassDriveChanged(0.4f)
                                onAnalogBassWarmthChanged(0.7f)
                                onAnalogBassDriftChanged(0.2f)
                                onAnalogBassPultecBoostChanged(0.5f)
                                onAnalogBassPultecCutChanged(0.3f)
                                onAnalogBassPultecFreqIndexChanged(2)
                            }) { Text("Reset to defaults") }
                        }
                        GroupDivider()
                        FeatureRow(
                            icon = Icons.Default.Whatshot,
                            title = "Tube Warmth",
                            subtitle = if (tubeWarmthEnabled) "Warm bloom, softened highs" else "Valve-amp tone",
                            checked = tubeWarmthEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = onTubeWarmthEnabledChanged,
                            onHelpClick = { helpDialog = HelpContent.TubeWarmth }
                        ) {
                            LabeledSlider(label = "Intensity", value = tubeWarmthIntensity,
                                valueLabel = "${String.format(Locale.US, "%.0f", tubeWarmthIntensity * 100)}%",
                                onValueChange = onTubeWarmthIntensityChanged, steps = 100)
                        }
                        GroupDivider()
                        FeatureRow(
                            icon = Icons.Default.WbSunny,
                            title = "JadOO DBFB",
                            subtitle = if (dbfbMode == DbfbMode.Off) "Level-aware sub-bass" else "Dynamic bass · ${dbfbMode.displayName}",
                            checked = dbfbMode != DbfbMode.Off,
                            enabled = masterEnabled,
                            onCheckedChange = { on -> onDbfbModeChanged(if (on) DbfbMode.Normal else DbfbMode.Off) },
                            onHelpClick = { helpDialog = HelpContent.Dbfb }
                        ) {
                            DbfbModeChips(dbfbMode, masterEnabled, onDbfbModeChanged)
                        }
                        GroupDivider()
                        FeatureRow(
                            icon = Icons.Default.HighQuality,
                            title = "Hi-Res Upscaler",
                            subtitle = if (hiResUpscalerEnabled) "Air · silk · presence" else "More air and sparkle up top",
                            checked = hiResUpscalerEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = onHiResUpscalerToggled,
                            onHelpClick = { helpDialog = HelpContent.HiResUpscaler }
                        )
                        GroupDivider()
                        FeatureRow(
                            icon = Icons.Default.AutoAwesome,
                            title = "Harmonic Exciter",
                            subtitle = if (harmonicExciterEnabled) "Adding presence and sparkle" else "Clarity in the 2-8 kHz range",
                            checked = harmonicExciterEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = onHarmonicExciterEnabledChanged,
                            onHelpClick = { helpDialog = HelpContent.HarmonicExciter }
                        ) {
                            LabeledSlider(label = "Intensity", value = harmonicExciterIntensity,
                                valueLabel = "${String.format(Locale.US, "%.0f", harmonicExciterIntensity * 100)}%",
                                onValueChange = onHarmonicExciterIntensityChanged, steps = 100)
                        }
                        GroupDivider()
                        FeatureRow(
                            icon = Icons.Default.Contrast,
                            title = "HDR Dynamics",
                            subtitle = if (hdrDynamicsEnabled) "${hdrMode.displayName} · restores punch" else "Opens up brickwalled masters",
                            checked = hdrDynamicsEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = onHdrDynamicsToggled,
                            onHelpClick = { helpDialog = HelpContent.HdrDynamics }
                        ) {
                            HdrModeChips(hdrMode, masterEnabled, onHdrModeChanged)
                        }
                    }
                }
            }

            // ── Space ─────────────────────────────────────────────────────
            Entrance(4) {
                Column {
                    GroupHeader("Space")
                    FeatureGroup {
                        NavRow(
                            icon = Icons.Default.SurroundSound,
                            title = "JadOO Surround+",
                            value = if (surroundMode != SurroundMode.Off)
                                "${surroundMode.displayName} · ${surroundMode.tagline}" else "Off · choose a stage",
                            accent = surroundMode != SurroundMode.Off,
                            enabled = masterEnabled,
                            onClick = { showSurroundPicker = true },
                            onHelpClick = { helpDialog = HelpContent.SpatialSurround }
                        )
                    }
                }
            }

            // ── Level ─────────────────────────────────────────────────────
            Entrance(5) {
                Column {
                    GroupHeader("Level")
                    FeatureGroup {
                        val belowReference = (loudnessReferencePhon - loudnessCurrentPhon).coerceAtLeast(0f)
                        FeatureRow(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            title = "Loudness Contour",
                            subtitle = when {
                                !loudnessEnabled -> "Keeps the balance at low volume"
                                belowReference < 1f -> "At reference level · no correction"
                                else -> "${String.format(Locale.US, "%.0f", belowReference)} dB below reference · compensating"
                            },
                            checked = loudnessEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = onLoudnessEnabledChanged,
                            onHelpClick = { helpDialog = HelpContent.LoudnessContour }
                        ) {
                            LabeledSlider(label = "Amount", value = loudnessAmount,
                                valueLabel = "${String.format(Locale.US, "%.0f", loudnessAmount * 100)}%",
                                onValueChange = onLoudnessAmountChanged, steps = 100)
                            LabeledSlider(label = "Reference level",
                                value = (loudnessReferencePhon - 60f) / 30f,
                                valueLabel = "${String.format(Locale.US, "%.0f", loudnessReferencePhon)} dB SPL",
                                onValueChange = { onLoudnessReferencePhonChanged(60f + it * 30f) }, steps = 30)
                            Text("Set the reference to how loud your gear is at full volume. Saved per output; " +
                                 "never boosts above the reference.",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ── Output-specific ───────────────────────────────────────────
            val onSpeaker = currentOutputDevice == "Phone Speaker"
            val onBt = currentOutputDevice.startsWith("Bluetooth")
            AnimatedVisibility(visible = onSpeaker || onBt,
                enter = expandVertically(JadooTheme.motion.standardSize) + fadeIn(JadooTheme.motion.fade),
                exit = shrinkVertically(JadooTheme.motion.standardSize) + fadeOut(JadooTheme.motion.fade)) {
                Entrance(6) {
                    Column {
                        GroupHeader(if (onSpeaker) "Phone speaker" else "Bluetooth")
                        FeatureGroup {
                            if (onSpeaker) {
                                FeatureRow(
                                    icon = Icons.Default.Speaker,
                                    title = "JadOO Mobile Bass",
                                    subtitle = if (mobileBassEnabled) "Restoring missing low end" else "Fuller bass from the built-in speaker",
                                    checked = mobileBassEnabled,
                                    enabled = masterEnabled,
                                    onCheckedChange = onMobileBassEnabledChanged,
                                    onHelpClick = { helpDialog = HelpContent.MobileBass }
                                ) {
                                    LabeledSlider(label = "Amount", value = mobileBassIntensity,
                                        valueLabel = "${String.format(Locale.US, "%.0f", mobileBassIntensity * 100)}%",
                                        onValueChange = onMobileBassIntensityChanged, steps = 100)
                                }
                            }
                            if (onBt) {
                                FeatureRow(
                                    icon = Icons.Default.Bluetooth,
                                    title = "SBC Enhancement",
                                    subtitle = if (sbcModeEnabled) "${sbcStyle.displayName} · for the SBC encoder" else "For SBC headphones only",
                                    checked = sbcModeEnabled,
                                    enabled = masterEnabled,
                                    onCheckedChange = onSbcModeEnabledChanged,
                                    onHelpClick = { helpDialog = HelpContent.SbcEnhancement }
                                ) {
                                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                        val styles = com.jadoo.amp.audio.SbcStyle.entries
                                        styles.forEachIndexed { i, style ->
                                            SegmentedButton(
                                                selected = sbcStyle == style,
                                                onClick = { onSbcStyleChanged(style) },
                                                shape = SegmentedButtonDefaults.itemShape(i, styles.size)
                                            ) { Text(style.displayName) }
                                        }
                                    }
                                }
                            }
                        }
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
            selectedPresetName = selectedPresetName,
            onPresetNameChanged = onSelectedPresetNameChanged,
            onDismiss = { showExpandedEq = false },
            onBandLevelChanged = onBandLevelChanged,
            onPresetSelected = onPresetSelected,
            onSavePreset = onSavePreset,
            onDeletePreset = onDeletePreset
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

    if (showAutoEq) {
        Dialog(
            onDismissRequest = { showAutoEq = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                AutoEqScreen(
                    currentOutputDevice = currentOutputDevice,
                    initialQuery = autoEqQuery,
                    onApply = onApplyAutoEq,
                    onNavigateBack = { showAutoEq = false }
                )
            }
        }
    }

    if (showLiveChain) {
        Dialog(
            onDismissRequest = { showLiveChain = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                LiveChainScreen(
                    state = liveChainState,
                    onNavigateBack = { showLiveChain = false }
                )
            }
        }
    }

    // Parametric EQ Dialog
    if (showParametricEq) {
        Dialog(
            onDismissRequest = { showParametricEq = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(0.dp),
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
                    onResetAllBands = onResetDigitalFilterBands,
                    autoEqProfileName = autoEqProfileName,
                    onOpenAutoEq = if (AUTOEQ_ENABLED) ({ autoEqQuery = ""; showAutoEq = true }) else null,
                    onNavigateBack = { showParametricEq = false }
                )
            }
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
        HdrMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            FilterChip(
                selected = selected,
                onClick = { onModeSelected(mode) },
                label = { Text(mode.displayName) },
                enabled = enabled
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
        DbfbMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            FilterChip(
                selected = selected,
                onClick = { onModeSelected(mode) },
                label = { Text(mode.displayName) },
                enabled = enabled
            )
        }
    }
}

/** Representative icon for each physical device type, see DeviceType. */
private fun deviceTypeIcon(type: com.jadoo.amp.audio.DeviceType): ImageVector =
    when (type) {
        com.jadoo.amp.audio.DeviceType.General -> Icons.Default.Tune
        com.jadoo.amp.audio.DeviceType.Iem -> Icons.Default.GraphicEq
        com.jadoo.amp.audio.DeviceType.OnEar -> Icons.Default.Headset
        com.jadoo.amp.audio.DeviceType.OverEar -> Icons.Default.Headphones
        com.jadoo.amp.audio.DeviceType.CompactSpeaker -> Icons.Default.SurroundSound
        com.jadoo.amp.audio.DeviceType.HomeSpeaker -> Icons.Default.Speaker
    }

/**
 * Device type picker: General gets a full-width row (it's "skip this feature",
 * not a physical type), the 5 real device types fill a 2-column grid below.
 * Same animated card pattern as everywhere else in this dialog: color, border,
 * and scale all spring on selection, plus a small checkmark fade-in.
 */
@Composable
private fun DeviceTypePicker(
    selected: com.jadoo.amp.audio.DeviceType,
    onSelected: (com.jadoo.amp.audio.DeviceType) -> Unit,
    enabled: Boolean = true
) {
    val realTypes = com.jadoo.amp.audio.DeviceType.entries
        .filter { it != com.jadoo.amp.audio.DeviceType.General }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DeviceTypeCard(
            type = com.jadoo.amp.audio.DeviceType.General,
            isSelected = selected == com.jadoo.amp.audio.DeviceType.General,
            onClick = { onSelected(com.jadoo.amp.audio.DeviceType.General) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        realTypes.chunked(2).forEach { rowTypes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowTypes.forEach { type ->
                    DeviceTypeCard(
                        type = type,
                        isSelected = type == selected,
                        onClick = { onSelected(type) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowTypes.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DeviceTypeCard(
    type: com.jadoo.amp.audio.DeviceType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val containerColor by animateColorAsState(
        targetValue = if (!enabled) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                      else if (isSelected) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceContainer,
        label = "device_type_bg_${type.name}"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected && enabled) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        label = "device_type_border_${type.name}"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected && enabled) 2.dp else 1.dp,
        label = "device_type_border_width_${type.name}"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected && enabled) 1.03f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "device_type_scale_${type.name}"
    )
    val iconColor by animateColorAsState(
        targetValue = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                      else if (isSelected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "device_type_icon_${type.name}"
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.scale(scale),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = deviceTypeIcon(type),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = type.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            AnimatedVisibility(
                visible = isSelected,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = 400f)) + fadeIn(tween(150)),
                exit = scaleOut(tween(100)) + fadeOut(tween(100))
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun GainControlDeck(
    preGainDb: Float,
    postGainDb: Float,
    onPreGainChanged: (Float) -> Unit,
    onPostGainChanged: (Float) -> Unit,
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
                    "Input and output gain",
                    style = MaterialTheme.typography.titleSmall,
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
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = String.format(Locale.US, "%+.1f dB", value),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            // 0.1 dB resolution, without drawing 240 tick marks to get it.
            onValueChange = { onValueChange((it * 10f).roundToInt() / 10f) },
            valueRange = valueRange,
            colors = jadooSliderColors()
        )
    }
}

@Composable
private fun jadooSliderColors() = SliderDefaults.colors(
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent
)

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
                Text(label, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(valueLabel, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                 color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            // Snap to the same (steps + 1) positions as before, but draw a
            // clean continuous track instead of a row of tick dots.
            onValueChange = { raw ->
                if (steps <= 0) onValueChange(raw) else {
                    val span = valueRange.endInclusive - valueRange.start
                    val t = ((raw - valueRange.start) / span * (steps + 1)).roundToInt() / (steps + 1f)
                    onValueChange(valueRange.start + t * span)
                }
            },
            valueRange = valueRange,
            colors = jadooSliderColors()
        )
    }
}

/**
 * Base and mid tone of the recessed EQ panel. Dark themes sink it below the
 * card (surfaceDim) so the glowing curve has contrast; light themes must NOT
 * — surfaceDim plus a black vignette turned the panel into a dark slab on a
 * light card. There the panel sits a step lighter than its card instead,
 * like paper under a light, and the curve reads by its own colour.
 */
@Composable
private fun eqPanelColors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() < 0.5f) {
        scheme.surfaceDim to androidx.compose.ui.graphics.lerp(scheme.surfaceDim, scheme.surface, 0.30f)
    } else {
        scheme.surfaceContainerLow to scheme.surfaceContainerLowest
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
    val labelHeight = with(density) { 50.dp.toPx() }
    // Must match InteractiveEqGraph's own graphPaddingTop exactly — these
    // ticks are the Y-axis for that graph's gridlines, drawn in a separate
    // Canvas. A mismatch here doesn't clip anything, it just makes the "+15"
    // tick and the graph's own 15dB gridline drift apart. Bumped from 14dp
    // (which happened to almost exactly equal this Box's own 14dp corner
    // radius, so the topmost "+15" tick sat right where the rounding curves)
    // to 20dp, clearing it from the corner.
    val graphPaddingTop = with(density) { 20.dp.toPx() }
    val dbLabelWidthDp = if (expanded) 46.dp else 40.dp
    val dbLabelRightInset = with(density) { 8.dp.toPx() }
    // Same panel tone InteractiveEqGraph uses for its own background — the
    // sidebar used to be a flat surfaceContainerHigh strip while the graph
    // next to it was a darker gradient panel, so the two visibly disagreed
    // right at the seam between them ("detached from the corner roundings").
    // Matching the tone here, and moving the bezel border to THIS outer Box
    // instead of the inner scrollable graph, makes the sidebar and the graph
    // read as one continuous panel instead of two separately-styled pieces.
    val panelShapeOuter = RoundedCornerShape(14.dp)
    val sidebarBackground = eqPanelColors().second
    val bezelColorOuter = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    // Full range: -15 to +15, every 1 dB step
    val dbSteps = (-15..15).toList()

    Box(
        modifier = modifier
            .clip(panelShapeOuter)
            .border(1.dp, bezelColorOuter, panelShapeOuter)
    ) {
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
                .background(sidebarBackground)
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
                        canvas.nativeCanvas.drawText(label, w - dbLabelRightInset, y + labelTextSize / 3f, paint)
                    }
                }
            }
        }
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
    // Was a flat surfaceContainerHigh fill — the same tone (or near enough)
    // as the card this graph already sits inside, so the whole panel read as
    // "a gray rectangle," not an instrument. Real EQ UIs (FabFilter,
    // iZotope, Apple's own Music EQ editor) always sink the plot into a
    // visibly RECESSED panel — darker than its surroundings, with a soft
    // vertical gradient and vignette — so the glowing curve has real
    // contrast to read against. surfaceDim is M3's own token for exactly
    // this "receded, inset" role; panelMid lifts it slightly toward the
    // vertical centre (where 0dB sits) so the panel reads as gently lit
    // from its own reference line rather than uniformly flat.
    val (panelBase, panelMid) = eqPanelColors()
    val vignette = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
        Color.Black.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val panelBrush = remember(panelBase, panelMid) {
        Brush.verticalGradient(listOf(panelBase, panelMid, panelBase))
    }
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    // Authoritative, un-animated value per band — the source of truth for
    // both the dB readout text and the drag math. Kept separate from the
    // animated draw position below (see animatedY) so a preset/reset change
    // can ease the CURVE in without the printed number lagging behind it.
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
    val labelHeight = with(density) { 50.dp.toPx() }
    val graphPaddingTop = with(density) { 20.dp.toPx() }
    // Horizontal inset so band 0 (25Hz) and the last band (16k) — and their
    // centre-aligned frequency/dB labels — never sit flush against the plot
    // edge. Without this, half of "25"/"16k" and their dB numbers drew past
    // the Canvas bounds outright, and what little survived that was then cut
    // further by the rounded-corner clip below — the two compounded. Same
    // technique ParametricEqScreen already uses (inset baked into the
    // coordinate mapping itself, not bolted onto the label draw calls after
    // the fact), sized to comfortably fit "-15.0", the widest label drawn.
    val hInset = with(density) { if (expanded) 22.dp.toPx() else 18.dp.toPx() }

    fun xFor(index: Int, w: Float): Float =
        hInset + (index / (EqBands.count - 1f)) * (w - 2f * hInset)

    fun indexFor(x: Float, w: Float): Int =
        (((x - hInset) / (w - 2f * hInset).coerceAtLeast(1f)) * (EqBands.count - 1))
            .roundToInt().coerceIn(0, EqBands.count - 1)

    // Draw position per band, eased toward localGains — but SNAPPED (no
    // spring) for whichever band is actively being dragged, so a live touch
    // still tracks 1:1 exactly as before. Every other band (a preset load,
    // Reset, or a per-app profile switch moving the whole curve) now eases
    // in instead of jumping, matching every other stateful control in this
    // app. animateFloatAsState already re-targets cleanly on interruption
    // (a second preset tap mid-animation), so no extra bookkeeping is needed.
    val animatedDb = List(EqBands.count) { index ->
        animateFloatAsState(
            targetValue = localGains.getOrElse(index) { 0f },
            animationSpec = if (activeDragIndex == index) snap() else JadooTheme.motion.standard,
            label = "eq_band_db_$index"
        )
    }
    // The dragged point's pickup pop — the same "physical moment" token used
    // for the power toggle and this session's icon-tile pop.
    val animatedRadius = List(EqBands.count) { index ->
        animateFloatAsState(
            targetValue = if (activeDragIndex == index) activeRadius else normalRadius,
            animationSpec = JadooTheme.motion.expressiveSpec(),
            label = "eq_band_radius_$index"
        )
    }
    val guideAlpha by animateFloatAsState(
        targetValue = if (activeDragIndex != null) 1f else 0f,
        animationSpec = JadooTheme.motion.fastSpec(),
        label = "eq_guide_alpha"
    )

    val textMeasurer = rememberTextMeasurer()
    // Frequency labels never change at runtime — measured once and reused
    // every frame instead of re-laying-out 15 static strings 60 times a
    // second. The dB readout DOES change on every drag frame, so it stays on
    // the raw-Paint path below (a measured TextLayoutResult per frame, per
    // band, during a drag is the exact shape of per-tick-recompute jank this
    // codebase already paid for once — see applySingleBand's own history);
    // `fontFeatureSettings = "tnum"` gets it tabular-figure stability
    // without that cost.
    val freqLabelStyle = TextStyle(
        fontSize = with(density) { labelTextSize.toSp() },
        color = labelColor.copy(alpha = 0.88f)
    )
    val freqLayouts = remember(EqBands.labels, freqLabelStyle) {
        EqBands.labels.map { label -> textMeasurer.measure(label, freqLabelStyle) }
    }

    val panelShape = RoundedCornerShape(if (expanded) 16.dp else 14.dp)
    // No border here — this Canvas is wider than its viewport and scrolls
    // horizontally inside EqGraphWithStickyLabels's Row, so a border on IT
    // would draw at the true content edges (mostly off-screen mid-scroll)
    // instead of at the visible viewport. The bezel lives on the OUTER Box
    // in EqGraphWithStickyLabels instead, framing the graph and the sticky
    // dB sidebar as one continuous panel — this Canvas only needs the fill.
    Canvas(
        modifier = modifier
            .clip(panelShape)
            .background(panelBrush)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    if (!expanded) {
                        onTap()
                    } else if (enabled) {
                        val closestIndex = indexFor(offset.x, size.width.toFloat())
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
                                dragIdx = indexFor(startX, size.width.toFloat())
                                activeDragIndex = dragIdx
                            }
                        }
                    }

                    if (!isVertical) return@awaitEachGesture  // let parent horizontalScroll handle it

                    // Phase 2: consume and handle vertical band adjustment.
                    // onBandLevelChanged fires on every move (not just at
                    // release) so the audio tracks the drag live — it used to
                    // only fire once in the `finally` block below, which made
                    // the dot move instantly on screen while the actual sound
                    // stayed frozen at the old gain until you lifted your
                    // finger, then jumped all at once — exactly the "laggy,
                    // takes a few seconds to reflect" feel.
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            dragIdx?.let { idx ->
                                val plotHeight = size.height - labelHeight - graphPaddingTop
                                val yRatio = 1f - ((change.position.y - graphPaddingTop) / plotHeight)
                                val newDb = (yRatio * 30f - 15f).coerceIn(-15f, 15f)
                                localGains[idx] = newDb
                                onBandLevelChanged(idx, newDb)
                            }
                        }
                    } finally {
                        activeDragIndex = null
                    }
                }
            }
    ) {
        val w = size.width
        val plotTop = graphPaddingTop
        val plotBottom = size.height - labelHeight
        val plotHeight = plotBottom - plotTop

        // A soft vignette centred on the 0dB reference line — corners recede
        // a little further, centre stays clearest. This plus the panel
        // gradient/bezel above is what turns a flat fill into something that
        // reads as a lit instrument panel rather than a plain rectangle.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, vignette),
                center = Offset(w / 2f, plotTop + plotHeight / 2f),
                radius = kotlin.math.hypot(w / 2f, plotHeight / 2f) * 1.15f
            ),
            size = Size(w, plotBottom)
        )

        // 0dB is the reference every other band is judged against — drawn
        // heavier/brighter than the rest instead of all steps sharing one
        // flat weight, so the grid itself carries a little hierarchy.
        for (db in -15..15 step 3) {
            val y = plotBottom - ((db + 15f) / 30f) * plotHeight
            val isZero = db == 0
            drawLine(
                color = if (isZero) gridColor.copy(alpha = gridColor.alpha * 2.2f) else gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (isZero) 1.6f else 1f
            )
        }

        for (index in 0 until EqBands.count) {
            val x = xFor(index, w)
            drawLine(
                color = gridColor,
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 1f
            )
        }

        // Vertical guide at the actively-dragged band, fading in/out with the
        // grab itself — reinforces which band is being edited while you're
        // not looking directly at your finger.
        if (guideAlpha > 0.01f) {
            val guideIndex = activeDragIndex ?: 0
            val guideX = xFor(guideIndex, w)
            drawLine(
                color = secondaryColor.copy(alpha = 0.35f * guideAlpha),
                start = Offset(guideX, plotTop),
                end = Offset(guideX, plotBottom),
                strokeWidth = 2f
            )
        }

        val points = mutableListOf<Offset>()
        for (index in 0 until EqBands.count) {
            val x = xFor(index, w)
            val db = animatedDb[index].value.coerceIn(-15f, 15f)
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

        // Tail geometry — how far the curve eases toward the 0dB centre line
        // out past band 0 / band 14, instead of stopping dead at a hard
        // vertical wall right at the control dot. Computed here (before the
        // fill) so the fill's own outer edge can follow the exact same curve
        // as the stroke drawn further down, rather than the two disagreeing.
        val midY = plotBottom - 0.5f * plotHeight
        val leftTailEndY = points.first().y + (midY - points.first().y) * 0.5f
        val rightTailEndY = points.last().y + (midY - points.last().y) * 0.5f

        // Built as one continuous contour (not addPath(path), which would
        // insert its own moveTo and break continuity with the tail curves
        // joining it here) so the fill's edge is exactly the same line the
        // stroke follows, start to end.
        val fillPath = Path().apply {
            moveTo(0f, leftTailEndY)
            quadraticTo(hInset * 0.4f, leftTailEndY, points.first().x, points.first().y)
            for (index in 0 until points.size - 1) {
                val p0 = points[index]
                val p1 = points[index + 1]
                val cpX = (p0.x + p1.x) / 2f
                cubicTo(cpX, p0.y, cpX, p1.y, p1.x, p1.y)
            }
            quadraticTo(w - hInset * 0.4f, rightTailEndY, w, rightTailEndY)
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

        // A soft, wide, low-alpha pass behind the crisp stroke — reads as a
        // little glow/depth rather than a flat single-width line, without
        // needing a real blur.
        drawPath(
            path = path,
            color = primaryColor.copy(alpha = 0.16f),
            style = Stroke(width = strokeWidthPx * 3f)
        )
        drawPath(
            path = path,
            color = primaryColor,
            style = Stroke(width = strokeWidthPx)
        )

        // ── Edge tails ───────────────────────────────────────────────────
        // The curve used to stop dead at band 0 / band 14 with a hard
        // vertical wall right at the control dot — reading as "the graph
        // got cut off" rather than "the spectrum continues past what's
        // shown". This stroke retraces the same tail the fill above already
        // follows (see leftTailEndY/rightTailEndY), just faded to nothing
        // out past the last real band instead of staying solid, so the line
        // itself also trails off rather than just the colour underneath it.
        val leftTailStroke = Path().apply {
            moveTo(points.first().x, points.first().y)
            quadraticTo(hInset * 0.4f, leftTailEndY, 0f, leftTailEndY)
        }
        val rightTailStroke = Path().apply {
            moveTo(points.last().x, points.last().y)
            quadraticTo(w - hInset * 0.4f, rightTailEndY, w, rightTailEndY)
        }
        drawPath(
            path = leftTailStroke,
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, primaryColor.copy(alpha = 0.6f)),
                startX = 0f, endX = points.first().x
            ),
            style = Stroke(width = strokeWidthPx * 0.7f)
        )
        drawPath(
            path = rightTailStroke,
            brush = Brush.horizontalGradient(
                colors = listOf(primaryColor.copy(alpha = 0.6f), Color.Transparent),
                startX = points.last().x, endX = w
            ),
            style = Stroke(width = strokeWidthPx * 0.7f)
        )

        points.forEachIndexed { index, point ->
            drawCircle(
                color = if (activeDragIndex == index) secondaryColor else primaryColor,
                radius = animatedRadius[index].value,
                center = point
            )
        }

        freqLayouts.forEachIndexed { index, layout ->
            val x = xFor(index, w)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x - layout.size.width / 2f, plotBottom + labelHeight * 0.22f)
            )
        }

        drawIntoCanvas { canvas ->
            val dbPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = labelTextSize * 0.86f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                // Tabular figures — digits keep a fixed advance width as they
                // change on every drag frame, so the readout doesn't visibly
                // jitter left/right the way proportional digits would.
                fontFeatureSettings = "tnum"
            }
            EqBands.labels.forEachIndexed { index, _ ->
                val x = xFor(index, w)
                val db = localGains[index].coerceIn(-15f, 15f)
                dbPaint.color = when {
                    db > 0.05f -> android.graphics.Color.argb(210, 90, 195, 90)
                    db < -0.05f -> android.graphics.Color.argb(200, 220, 75, 75)
                    else -> labelColor.copy(alpha = 0.38f).toArgb()
                }
                val dbText = when {
                    db > 0.05f -> "+${"%.1f".format(db)}"
                    db < -0.05f -> "${"%.1f".format(db)}"
                    else -> "0"
                }
                canvas.nativeCanvas.drawText(dbText, x, plotBottom + labelHeight * 0.80f, dbPaint)
            }
        }
    }
}

@Composable
private fun ExpandedEqDialog(
    bandGains: FloatArray,
    savedPresets: List<SavedEqPreset>,
    enabled: Boolean,
    selectedPresetName: String?,
    onPresetNameChanged: (String?) -> Unit,
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

                // Derive the actually-matching preset name from the live band gains
                // instead of trusting the last-clicked name: that name is plain
                // Compose `remember` state with no backing store, so it resets to
                // null on process death, on switching output-device profiles (which
                // loads a different bandGains array), and on app restart — making
                // the highlighted chip and the "Overwrite" affordance appear to
                // randomly vanish even though the active curve is still a named
                // preset. Matching by content is self-healing in all those cases.
                val matchedPresetName = remember(bandGains, savedPresets) {
                    Presets.entries.find { it.value.contentEquals(bandGains) }?.key
                        ?: savedPresets.find { it.gains.contentEquals(bandGains) }?.name
                }
                // Overwrite target: the last custom preset the user explicitly clicked,
                // regardless of whether the current gains still content-match it.
                // matchedPresetName becomes null the moment any band is moved (no longer
                // an exact match), which previously caused the overwrite chip to vanish
                // immediately after the first slider drag — selectedPresetName persists
                // the intent even as the gains diverge from the saved values.
                val overwriteTargetPreset = savedPresets.find { it.name == selectedPresetName }
                val selectedBorder = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)

                if (overwriteTargetPreset != null && !overwriteTargetPreset.gains.contentEquals(bandGains)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { onSavePreset(overwriteTargetPreset.name, bandGains.copyOf()) },
                            label = { Text("Overwrite \"${overwriteTargetPreset.name}\"") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            enabled = enabled,
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {
                            onPresetNameChanged(null)
                            onPresetSelected(FloatArray(EqBands.count))
                        },
                        label = { Text("Reset (Flat)") },
                        enabled = enabled
                    )
                    Presets.forEach { (name, gains) ->
                        AssistChip(
                            onClick = {
                                onPresetNameChanged(name)
                                onPresetSelected(gains)
                            },
                            label = { Text(name) },
                            enabled = enabled,
                            border = if (name == matchedPresetName) {
                                selectedBorder
                            } else {
                                AssistChipDefaults.assistChipBorder(enabled = enabled)
                            }
                        )
                    }
                    savedPresets.forEach { preset ->
                        // Box wraps the chip so we can anchor a DropdownMenu to it
                        Box {
                            AssistChip(
                                onClick = {
                                    onPresetNameChanged(preset.name)
                                    onPresetSelected(preset.gains)
                                },
                                label = { Text(preset.name) },
                                enabled = enabled,
                                border = if (preset.name == matchedPresetName) {
                                    selectedBorder
                                } else {
                                    AssistChipDefaults.assistChipBorder(enabled = enabled)
                                },
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
                                        onPresetNameChanged(preset.name)
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
                                        if (matchedPresetName == preset.name) onPresetNameChanged(null)
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
                        onClick = {
                            onPresetNameChanged(null)
                            onPresetSelected(FloatArray(EqBands.count))
                        },
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
                onPresetNameChanged(name)
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

/**
 * Card wrapper for one Settings section.
 *
 * surfaceContainerHighest, not surfaceContainerHigh — the dialog's own Card
 * (see SettingsDialog below) already sits at surfaceContainerHigh, and a
 * nested Surface at the SAME token plus a few dp of tonal elevation is too
 * subtle a shift to read as a distinct card against its own parent (this was
 * tried first and looked unchanged). surfaceContainerHighest is Material 3's
 * token for exactly this "one level up" nesting and gives real contrast.
 *
 * Padding is 16dp, not 20dp — the dialog's outer Column already contributes
 * 16dp of its own (see SettingsDialog), and the two stack; the first version
 * of this used 20+24 and made every section noticeably narrower from the
 * sides than before cards existed at all.
 */
/**
 * Settings' Updates card. The check itself is per distribution channel (see
 * UpdateChannel.kt in src/github and src/play): GitHub builds query GitHub
 * Releases and install in-app, Play builds hand off to the Play Store.
 */
@Composable
private fun UpdatesCard() {
    val context = LocalContext.current
    val installed = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = installed?.versionName ?: "unknown"

    SettingsCard {
        Text(
            text = "Updates",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Installed version $versionName",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        UpdateCheckControls(installedVersion = versionName)
    }
}

@Composable
private fun SettingsCard(
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // Same container as the grouped lists (FeatureGroup) so the Updates
        // block reads as one more group, not a different kind of card.
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/**
 * Settings, as its own full screen rather than a Dialog.
 *
 * A Dialog is a genuinely separate floating window — a card that doesn't
 * fill the height, with a dim scrim visible around it — which reads as
 * "popup," not "screen," no matter how the content inside it is styled.
 * This fills the whole window itself, edge-to-edge, with a real back arrow,
 * the same way ParametricEqScreen's content is designed to (that one is
 * still Dialog-wrapped by its caller today, which has the identical
 * "popup" issue — out of scope here, but the same fix applies there later).
 */
@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    themeSettings: com.jadoo.amp.settings.ThemeSettings,
    onThemeModeChanged: (Offset, String) -> Unit,
    onToneModeChanged: (Offset, String) -> Unit,
    onBackdropChanged: (Offset, String) -> Unit,
    onSeedColorChanged: (Offset, Color) -> Unit,
    dumpPermissionEnabled: Boolean,
    currentOutputDevice: String,
    deviceType: com.jadoo.amp.audio.DeviceType,
    onDeviceTypeChanged: (com.jadoo.amp.audio.DeviceType) -> Unit,
    onHelpRequested: (HelpContent) -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    savedProfileNames: List<String>,
    onLoadProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    activeAppPackage: String?,
    activeAppLabel: String?,
    perAppProfilePackages: Set<String>,
    onPerAppProfileToggled: (String, Boolean) -> Unit,
    preciseGainStaging: Boolean,
    gainBudgetDb: Float,
    autoTrimDb: Float,
    onPreciseGainStagingChanged: (Boolean) -> Unit,
    autoEqProfileName: String,
    onOpenAutoEq: () -> Unit,
    onRemoveAutoEq: () -> Unit,
    onNavigateBack: () -> Unit
) {
    // Back handling for this screen is owned by the PredictiveBackHandler at
    // the DashboardScreen call site (it needs the gesture's live progress to
    // drive the slide-out), so this screen doesn't register its own.
    Column(
        modifier = modifier
            .fillMaxSize()
            .jadooBackdrop()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JadooTheme.dimens.screenPadding)
            .padding(top = 4.dp, bottom = 40.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Text("Settings", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))

        // ── Headphone correction (AutoEQ) ─────────────────────────────────
        if (AUTOEQ_ENABLED) Entrance(0) {
            Column {
                GroupHeader("Headphone correction")
                FeatureGroup {
                    NavRow(
                        icon = Icons.Default.Headphones,
                        title = if (autoEqProfileName.isBlank()) "Find your headphone" else autoEqProfileName,
                        value = if (autoEqProfileName.isBlank())
                            "8,000+ measured headphones and IEMs · AutoEQ"
                        else "Applied to $currentOutputDevice · tap to change",
                        accent = autoEqProfileName.isNotBlank(),
                        onClick = onOpenAutoEq
                    )
                    if (autoEqProfileName.isNotBlank()) {
                        GroupDivider()
                        TextButton(onClick = onRemoveAutoEq,
                                   modifier = Modifier.padding(start = 56.dp, bottom = 4.dp)) {
                            Text("Remove correction")
                        }
                    }
                }
                Text("Corrections are saved per output device and load into the Parametric EQ, " +
                     "where you can fine-tune them.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
            }
        }

        // ── Driver type ───────────────────────────────────────────────────
        Entrance(1) {
            Column {
                GroupHeader("Driver type")
                FeatureGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (currentOutputDevice == "Phone Speaker")
                                "Locked to General on the phone speaker. Mobile Bass already scales for it."
                            else
                                "Scales bass and treble features to what your driver can reproduce.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DeviceTypePicker(
                            selected = deviceType,
                            onSelected = onDeviceTypeChanged,
                            enabled = currentOutputDevice != "Phone Speaker"
                        )
                    }
                }
            }
        }

        // ── Sound ─────────────────────────────────────────────────────────
        Entrance(2) {
            Column {
                GroupHeader("Sound")
                FeatureGroup {
                    SettingToggleRow(
                        title = "Precise gain staging",
                        subtitle = when {
                            preciseGainStaging && autoTrimDb < -0.05f -> String.format(Locale.US,
                                "Input %.1f dB to keep boosts from clipping", autoTrimDb)
                            preciseGainStaging -> "Boosts get room so loud songs don't clip"
                            gainBudgetDb > 0.05f -> String.format(Locale.US,
                                "Legacy: limiter ceiling %.1f dB, no clip protection", -gainBudgetDb)
                            else -> "Legacy: the limiter ceiling drops by the full boost"
                        },
                        checked = preciseGainStaging,
                        onCheckedChange = onPreciseGainStagingChanged,
                        onHelpClick = { onHelpRequested(HelpContent.GainStaging) }
                    )
                    GroupDivider(inset = false)
                    if (activeAppPackage == null) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                            Text("Per-app profiles", style = MaterialTheme.typography.bodyLarge,
                                 fontWeight = FontWeight.Medium)
                            Text("Play something to give that app its own settings.",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        SettingToggleRow(
                            title = "Own profile for ${activeAppLabel ?: activeAppPackage}",
                            subtitle = if (activeAppPackage in perAppProfilePackages)
                                "Its own settings on every output" else "Uses this output's shared settings",
                            checked = activeAppPackage in perAppProfilePackages,
                            onCheckedChange = { onPerAppProfileToggled(activeAppPackage, it) },
                            onHelpClick = { onHelpRequested(HelpContent.PerAppProfiles) }
                        )
                    }
                    // Apps opted in but not currently playing — the only place
                    // to turn one back off without switching to it first.
                    perAppProfilePackages.filter { it != activeAppPackage }.sorted().forEach { pkg ->
                        GroupDivider(inset = false)
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(pkg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onPerAppProfileToggled(pkg, false) }) { Text("Remove") }
                        }
                    }
                }
            }
        }

        // ── Appearance ────────────────────────────────────────────────────
        Entrance(3) {
            Column {
                GroupHeader("Appearance")
                AppearanceSection(
                    settings = themeSettings,
                    onModeChanged = onThemeModeChanged,
                    onToneChanged = onToneModeChanged,
                    onBackdropChanged = onBackdropChanged,
                    onSeedChanged = onSeedColorChanged
                )
            }
        }

        // ── Backup ────────────────────────────────────────────────────────
        Entrance(4) {
            Column {
                GroupHeader("Backup")
                FeatureGroup {
                    NavRow(Icons.Default.Save, "Export settings",
                           "Every setting and preset to one file", onClick = onExportSettings)
                    GroupDivider()
                    NavRow(Icons.Default.Download, "Import settings",
                           "Restore from a backup file", onClick = onImportSettings)
                    savedProfileNames.forEach { name ->
                        GroupDivider(inset = false)
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onLoadProfile(name) }) { Text("Load") }
                            TextButton(onClick = { onDeleteProfile(name) }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        // ── System ────────────────────────────────────────────────────────
        Entrance(5) {
            Column {
                GroupHeader("System")
                FeatureGroup {
                    NavRow(
                        icon = Icons.Default.Info,
                        title = "Live stream data",
                        value = if (dumpPermissionEnabled) "DUMP granted · full signal path"
                                else "Optional DUMP grant over adb",
                        accent = dumpPermissionEnabled,
                        onClick = { onHelpRequested(HelpContent.DumpPermission) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                UpdatesCard()
            }
        }
    }
}

/**
 * A feature's explainer, as a bottom sheet: grab handle, swipe or tap the
 * scrim to dismiss, and a Done button so the way out is never only a
 * gesture. The diagram leads — it is what most people actually read — and
 * plays its mechanism once, then holds still under the text (see
 * HelpIllustrations.kt). Tapping it, or Replay, runs it again.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HelpDialog(
    content: HelpContent,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val illustration: (@Composable (Modifier) -> Unit)? = when (content) {
        HelpContent.HiResUpscaler   -> { m -> HiResIllustration(m) }
        HelpContent.Dbfb            -> { m -> DbfbIllustration(m) }
        HelpContent.HdrDynamics     -> { m -> HdrIllustration(m) }
        HelpContent.AnalogBass      -> { m -> AnalogBassIllustration(m) }
        HelpContent.SpatialSurround -> { m -> SurroundIllustrationAnimated(m) }
        HelpContent.TubeWarmth      -> { m -> TubeWarmthIllustration(m) }
        HelpContent.MobileBass      -> { m -> MobileBassIllustration(m) }
        HelpContent.HarmonicExciter -> { m -> ExciterIllustration(m) }
        HelpContent.DigitalFilters  -> { m -> ParametricEqIllustration(m) }
        HelpContent.SbcEnhancement  -> { m -> SbcIllustration(m) }
        HelpContent.LoudnessContour -> { m -> LoudnessIllustration(m) }
        HelpContent.PerAppProfiles  -> { m -> PerAppIllustration(m) }
        // Not DSP features: an ADB instruction and a limiter policy have no
        // mechanism worth drawing.
        HelpContent.GainStaging     -> null
        HelpContent.DumpPermission  -> null
    }
    // One shared demonstration timeline; bumping replayKey restarts it.
    val demo = remember { Animatable(0f) }
    var replayKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(replayKey) {
        demo.snapTo(0f)
        kotlinx.coroutines.delay(280) // let the sheet finish rising first
        demo.animateTo(1f, tween(DEMO_MS, easing = LinearEasing))
    }
    val (summary, rest) = remember(content) {
        val paragraphs = content.body.split("\n\n", limit = 2)
        paragraphs[0] to paragraphs.getOrNull(1)
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            if (illustration != null) {
                Box {
                    CompositionLocalProvider(LocalDemoProgress provides demo.value) {
                        illustration(
                            Modifier
                                .fillMaxWidth()
                                .height(184.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClickLabel = "Replay animation") { replayKey++ }
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = demo.value >= 1f,
                        enter = fadeIn(JadooTheme.motion.fade),
                        exit = fadeOut(JadooTheme.motion.fade),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    ) {
                        Surface(
                            onClick = { replayKey++ },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)
                        ) {
                            Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Replay, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Replay", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Text(content.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = parseInlineBold(summary.trim(), MaterialTheme.colorScheme.onSurface),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            rest?.let {
                Spacer(Modifier.height(12.dp))
                HelpBodyText(body = it)
            }
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Done")
            }
        }
    }
}

/**
 * The rest of a help body after its summary line, in the app's quiet voice:
 * blank lines separate paragraphs, "## " starts a sentence-case sub-heading,
 * "•" lines become a list, and "**term**" bolds the one word that matters.
 * Colour stays neutral — the accent is for state, not for bullets.
 */
@Composable
private fun HelpBodyText(body: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        body.split("\n").forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(10.dp))
                trimmed.startsWith("## ") -> Text(
                    text = trimmed.removePrefix("## "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
                trimmed.startsWith("•") -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                              verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 9.dp, end = 12.dp).size(5.dp)
                        .background(onSurfaceVariant, CircleShape))
                    Text(
                        text = parseInlineBold(trimmed.removePrefix("•").trim(), onSurface),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurfaceVariant
                    )
                }
                else -> Text(
                    text = parseInlineBold(trimmed, onSurface),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceVariant
                )
            }
        }
    }
}

/** Turns "**term**" markers in [text] into bold spans, [boldColor] otherwise inheriting the surrounding Text's color. */
private fun parseInlineBold(text: String, boldColor: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = boldColor)) { append(part) }
        } else {
            append(part)
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
    // JadooTheme.motion.fast is documented for exactly this ("chip
    // selection, small state flips") — picking a mode used to snap the
    // container colour/elevation/text tint instantly, which read as inert
    // next to the rest of the app's now-animated toggles.
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = JadooTheme.motion.fastSpec(),
        label = "surround_card_color"
    )
    val elevation by animateDpAsState(
        targetValue = if (selected) 3.dp else 1.dp,
        animationSpec = JadooTheme.motion.fastSpec(),
        label = "surround_card_elevation"
    )
    val titleColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurface,
        animationSpec = JadooTheme.motion.fastSpec(),
        label = "surround_card_title"
    )
    // A small overshoot on the illustration/icon specifically when a mode
    // becomes selected — the "expressive" token this project reserves for
    // moments that should feel physical (the power toggle uses the same
    // one). Landing on a mode is exactly that kind of moment; picking one
    // should feel like it clicked into place, not just recolour.
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = JadooTheme.motion.expressive,
        label = "surround_card_icon_scale"
    )
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = elevation
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
                        .scale(iconScale)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Off",
                    modifier = Modifier
                        .size(40.dp)
                        .scale(iconScale),
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
                    color = titleColor
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

