package com.jadoo.amp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jadoo.amp.session.SessionController
import com.jadoo.amp.settings.SessionPreferences
import com.jadoo.amp.settings.SessionState
import com.jadoo.amp.update.ContentRepository
import com.jadoo.amp.update.RemoteContent
import com.jadoo.amp.update.RemoteHeadphoneProfile
import com.jadoo.amp.update.RemoteTuning
import kotlin.math.abs
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class JadooDspService : Service() {

    companion object {
        private const val TAG = "JadooDspService"
        private const val GLOBAL_AUDIO_SESSION_ID = 0

        // How long to wait after a playback-stream change before re-attaching,
        // so the new output mix thread is fully up first. See
        // registerAudioPlaybackCallback.
        private const val PLAYBACK_REATTACH_DEBOUNCE_MS = 450L

        // Floor on the interval between playback-triggered re-attaches. A
        // single track transition can emit several config callbacks; rebuilding
        // the topology for each one would be audible.
        private const val PLAYBACK_REATTACH_MIN_INTERVAL_MS = 1_500L
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
        kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Uncaught exception in serviceScope coroutine", t)
        }
    )
    val dspEngine = DspEngine()
    private var mediaSessionManager: MediaSessionManager? = null
    private lateinit var sessionController: SessionController
    private lateinit var sessionPreferences: SessionPreferences
    private var mediaSessionListenerRegistered = false

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            handleActiveSessionsChanged(controllers.orEmpty())
        }

    // State for UI to observe
    private val _audioSessionId = MutableStateFlow<Int?>(null)
    val audioSessionId: StateFlow<Int?> = _audioSessionId.asStateFlow()

    private val _activePackageName = MutableStateFlow<String?>(null)
    val activePackageName: StateFlow<String?> = _activePackageName.asStateFlow()

    private val _activeAppLabel = MutableStateFlow<String?>(null)
    val activeAppLabel: StateFlow<String?> = _activeAppLabel.asStateFlow()

    // Human-readable label for the audio output device currently in use
    // (e.g. "Phone Speaker", "Wired Headphones", "Bluetooth: WH-1000XM4").
    // Each output device gets its own persisted DSP profile, switched
    // automatically when the route changes — see computeOutputDeviceKey().
    private val _currentOutputDevice = MutableStateFlow("Phone Speaker")
    val currentOutputDevice: StateFlow<String> = _currentOutputDevice.asStateFlow()

    private val _masterEnabled = MutableStateFlow(false)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    // True when master power is on but every feature is at its neutral/flat
    // setting, so the DynamicsProcessing effect is fully released and audio
    // passes through completely untouched (true bypass).
    private val _dspBypassed = MutableStateFlow(true)
    val dspBypassed: StateFlow<Boolean> = _dspBypassed.asStateFlow()

    private val manualBandGains = FloatArray(EqBands.count)

    private val _bandGains = MutableStateFlow(FloatArray(EqBands.count))
    val bandGains: StateFlow<FloatArray> = _bandGains.asStateFlow()

    private val _preGainDb = MutableStateFlow(0f)
    val preGainDb: StateFlow<Float> = _preGainDb.asStateFlow()

    private val _postGainDb = MutableStateFlow(0f)
    val postGainDb: StateFlow<Float> = _postGainDb.asStateFlow()

    private val _hiResUpscalerEnabled = MutableStateFlow(false)
    val hiResUpscalerEnabled: StateFlow<Boolean> = _hiResUpscalerEnabled.asStateFlow()

    private val _dbfbMode = MutableStateFlow(DbfbMode.Off)
    val dbfbMode: StateFlow<DbfbMode> = _dbfbMode.asStateFlow()

    private val _hdrDynamicsEnabled = MutableStateFlow(false)
    val hdrDynamicsEnabled: StateFlow<Boolean> = _hdrDynamicsEnabled.asStateFlow()

    private val _hdrMode = MutableStateFlow(HdrMode.Restoration)
    val hdrMode: StateFlow<HdrMode> = _hdrMode.asStateFlow()

    private val _surroundMode = MutableStateFlow(SurroundMode.Off)
    val surroundMode: StateFlow<SurroundMode> = _surroundMode.asStateFlow()

    // ── Tube Warmth state ────────────────────────────────────────────
    private val _tubeWarmthEnabled = MutableStateFlow(false)
    val tubeWarmthEnabled: StateFlow<Boolean> = _tubeWarmthEnabled.asStateFlow()

    private val _tubeWarmthIntensity = MutableStateFlow(0.5f)
    val tubeWarmthIntensity: StateFlow<Float> = _tubeWarmthIntensity.asStateFlow()

    // ── JadOO Mobile Bass state ───────────────────────────────────────
    // Psychoacoustic bass restoration for phone speakers (see DspEngine's
    // BassBoost wrapper) — manual toggle + a single intensity slider, only
    // ever shown in the UI while output is routed to the phone speaker
    // (see currentOutputDevice), never auto-enabled.
    private val _mobileBassEnabled = MutableStateFlow(false)
    val mobileBassEnabled: StateFlow<Boolean> = _mobileBassEnabled.asStateFlow()

    private val _mobileBassIntensity = MutableStateFlow(0.5f)
    val mobileBassIntensity: StateFlow<Float> = _mobileBassIntensity.asStateFlow()

    // ── Harmonic Exciter state ────────────────────────────────────────
    // BBE/Aphex-style presence-band saturation (2-8kHz) for added
    // sparkle/clarity — works on every output device, unlike Mobile Bass.
    private val _harmonicExciterEnabled = MutableStateFlow(false)
    val harmonicExciterEnabled: StateFlow<Boolean> = _harmonicExciterEnabled.asStateFlow()

    private val _harmonicExciterIntensity = MutableStateFlow(0.5f)
    val harmonicExciterIntensity: StateFlow<Float> = _harmonicExciterIntensity.asStateFlow()

    // Physical output device type + budget-to-flagship quality tier (see DeviceType) —
    // scales how much bass/treble boost Analog Bass, DBFB, Mobile Bass, HiRes Upscaler,
    // and Harmonic Exciter are allowed to request. "General" = no scaling.
    private val _deviceType = MutableStateFlow(DeviceType.General)
    val deviceType: StateFlow<DeviceType> = _deviceType.asStateFlow()

    private val _deviceQualityTier = MutableStateFlow(0.5f)
    val deviceQualityTier: StateFlow<Float> = _deviceQualityTier.asStateFlow()

    private fun bassExtension(): Float = _deviceType.value.bassExtension(_deviceQualityTier.value)
    private fun trebleExtension(): Float = _deviceType.value.trebleExtension(_deviceQualityTier.value)
    // Mobile Bass always runs on the phone's own built-in speaker, never on
    // whatever DeviceType the user has manually selected (that picker
    // describes the OUTPUT device on the current route, which for Mobile
    // Bass is irrelevant — it's gated to "Phone Speaker" regardless). Always
    // scale it as CompactSpeaker so it can't be over/under-driven by an
    // unrelated DeviceType choice like HomeSpeaker.
    private fun mobileBassExtension(): Float = DeviceType.CompactSpeaker.bassExtension(_deviceQualityTier.value)

    /**
     * The user's Crossfeed strength slider, scaled by how much of a problem
     * Crossfeed actually solves on the connected headphone type — see
     * DeviceType.crossfeedFactor. The RAW slider value is what gets
     * persisted (buildSessionState); this is only ever computed at the point
     * it's handed to the engine, so a later DeviceType change re-derives it
     * rather than compounding on top of an already-scaled stored value.
     */
    private fun crossfeedEffectiveStrength(): Float =
        _crossfeedStrength.value * _deviceType.value.crossfeedFactor()

    /**
     * Crossfeed's per-band PreEQ contribution — 0 everywhere unless enabled
     * on a real headphone route (see isHeadphoneRoute).
     *
     * Used to run on Android's Virtualizer effect (vendor HRTF/virtual-
     * surround processing) and it never sounded right — reported repeatedly
     * as "muddy/smeared", because that's what it structurally is: an
     * OEM-implemented spatializer, not a BS2B/Meier-style crossfeed circuit,
     * and no amount of strength/mode tuning changes what algorithm is
     * actually running. There is no way to build a REAL crossfeed circuit
     * through the public AudioEffect API either — that needs sample-accurate
     * cross-channel mixing (a low-passed, delayed copy of each channel
     * summed into the other), and this app only ever gets a session-level
     * effect attach, never raw PCM.
     *
     * So Crossfeed is this instead: the same shape of static tonal-EQ curve
     * SurroundMode.Front uses to approximate crossfeed's net perceptual
     * result (see surroundBandProfile's SurroundMode.Front case for the full
     * reasoning — low-mid warmth, forward vocal lock, soft high rolloff),
     * scaled up from Front's own magnitude — confirmed on real IEMs that
     * Front's numbers, copied verbatim, were too small to register as
     * audible at all. It can't narrow the stereo image the way real
     * cross-channel mixing does, but it's a curve we fully control: no
     * per-OEM variance, no reverb/room artefacts, completely predictable.
     * Scaled by the same
     * strength slider and DeviceType.crossfeedFactor as before.
     */
    private fun crossfeedShape(index: Int): Float {
        if (!_crossfeedEnabled.value || !isHeadphoneRoute()) return 0f
        val strength = crossfeedEffectiveStrength()
        // Confirmed on real IEMs at max strength: the original magnitude
        // (peak +1.8dB/-2.5dB, matching SurroundMode.Front's curve exactly)
        // was correctly wired end-to-end but read as "does nothing" — not a
        // bug, just too small a move to register, especially since this
        // curve can never deliver the one thing that actually defines
        // crossfeed to a listener (image narrowing needs real cross-channel
        // mixing, which the platform doesn't expose — see crossfeedShape's
        // class doc). Since subtlety was never the goal here — the whole
        // point of dropping the Virtualizer was to trade "processed and
        // wrong" for "small but honest" — there's no reason to also make it
        // "honest but inaudible." Scaled up ~1.7x: peak now +3.0dB/-4.5dB,
        // in the same range Surround Wide's own treble leg already uses
        // safely elsewhere in this app.
        val fullStrength = when (index) {
            3  ->  2.5f  // 100 Hz
            4  ->  3.0f  // 160 Hz: peak of the low-mid bloom
            5  ->  2.0f  // 250 Hz
            6  ->  1.0f  // 400 Hz
            8  ->  1.5f  // 1 kHz: forward vocal lock
            9  ->  1.8f  // 1.6 kHz
            10 ->  1.2f  // 2.5 kHz
            11 -> -1.5f  // 4 kHz: high rolloff begins
            12 -> -2.5f  // 6.3 kHz
            13 -> -3.5f  // 10 kHz
            14 -> -4.5f  // 16 kHz: maximum rolloff
            else -> 0f
        }
        return fullStrength * strength
    }

    /**
     * Driver capability used to scale ONLY the Loudness Contour's sub-100 Hz
     * leg — see LoudnessContour.compensationDb for why that leg is scaled and
     * the rest is not.
     *
     * The phone's own speaker is always treated as a CompactSpeaker regardless
     * of the DeviceType picker (which is locked to General on that route and
     * doesn't describe it), for the same reason mobileBassExtension does.
     */
    private fun loudnessDriverExtension(): Float =
        if (currentDeviceKey == "speaker") {
            DeviceType.CompactSpeaker.bassExtension(_deviceQualityTier.value)
        } else {
            bassExtension()
        }

    // ── Crossfeed state ───────────────────────────────────────────────
    // A static tonal-EQ curve applied via the ordinary PreEQ path — see
    // crossfeedShape() for why (used to be the Virtualizer effect; dropped).
    private val _crossfeedEnabled = MutableStateFlow(false)
    val crossfeedEnabled: StateFlow<Boolean> = _crossfeedEnabled.asStateFlow()

    private val _crossfeedStrength = MutableStateFlow(0.5f)
    val crossfeedStrength: StateFlow<Float> = _crossfeedStrength.asStateFlow()

    // ── Loudness Contour (ISO 226) state ──────────────────────────────
    // Tracks the system media volume and re-tilts the PreEQ so the mix keeps
    // its perceived tonal balance as the level drops. See LoudnessContour for
    // the psychoacoustics and computeCurrentPhon() for how a volume index is
    // turned into an SPL estimate.
    private val _loudnessEnabled = MutableStateFlow(false)
    val loudnessEnabled: StateFlow<Boolean> = _loudnessEnabled.asStateFlow()

    private val _loudnessAmount = MutableStateFlow(0.7f)
    val loudnessAmount: StateFlow<Float> = _loudnessAmount.asStateFlow()

    /** Assumed SPL at maximum system volume for this output device. */
    private val _loudnessReferencePhon = MutableStateFlow(LoudnessContour.DEFAULT_REFERENCE_PHON)
    val loudnessReferencePhon: StateFlow<Float> = _loudnessReferencePhon.asStateFlow()

    /** Estimated current listening level, surfaced in the UI so the correction is legible. */
    private val _loudnessCurrentPhon = MutableStateFlow(LoudnessContour.DEFAULT_REFERENCE_PHON)
    val loudnessCurrentPhon: StateFlow<Float> = _loudnessCurrentPhon.asStateFlow()

    // ── Gain staging ──────────────────────────────────────────────────
    // See DspEngine.splitGainBudget. ON is the corrected model; OFF restores
    // the pre-v1.6 behaviour of paying the entire budget on the limiter
    // threshold, kept only so the change is A/B-able by ear.
    private val _preciseGainStaging = MutableStateFlow(true)
    val preciseGainStaging: StateFlow<Boolean> = _preciseGainStaging.asStateFlow()

    // Name of the EQ preset last explicitly selected, so the highlighted
    // chip / "Overwrite" target survives a relaunch. Pure UI metadata, not
    // DSP config — never passed to dspEngine.attach(), the actual band
    // gains it produced are what's real and those already persist via
    // manualBandGains/bandGains regardless of this field.
    private val _selectedPresetName = MutableStateFlow("")
    val selectedPresetName: StateFlow<String> = _selectedPresetName.asStateFlow()

    /** Total gain budget the active feature stack is asking for, dB. */
    private val _gainBudgetDb = MutableStateFlow(0f)
    val gainBudgetDb: StateFlow<Float> = _gainBudgetDb.asStateFlow()

    /** Automatic input trim currently applied, dB (0 or negative). */
    private val _autoTrimDb = MutableStateFlow(0f)
    val autoTrimDb: StateFlow<Float> = _autoTrimDb.asStateFlow()

    /**
     * The live per-band correction, summed into every PreEQ band write by
     * writeCombinedBand(). All-zero whenever the feature is off, so the
     * summing path needs no special-casing.
     */
    @Volatile private var loudnessCompensation = FloatArray(EqBands.count)

    /**
     * Peak static PreEQ gain currently sitting in the bass/treble zones from
     * Graphic EQ, Parametric EQ, Loudness Contour, Crossfeed, device correction,
     * and SBC pre-emphasis combined — fed
     * to DspEngine's gain-budget model (see calculateGainBudget) so a manual
     * EQ boost gets the same limiter-headroom credit every other narrowband
     * feature already gets. Graphic EQ and Crossfeed were both previously
     * uncredited (Crossfeed used to run on the Virtualizer, which never
     * touched PreEQ at all — now that it's a tonal-EQ curve, its own
     * low-mid boost needs the same credit as everything else that lands there).
     *
     * All contributors are summed per band before the peak is taken, matching
     * the live PreEQ write. This prevents an active narrow PEQ boost or the SBC
     * air curve from escaping the limiter-headroom model.
     */
    /**
     * The device-class tonal correction for one band — see
     * [DeviceType.correctionDb]. Gated on the phone speaker, which is locked
     * to General in the UI and has Mobile Bass doing its own thing.
     *
     * A model-specific curve from the content channel, when one is active,
     * REPLACES the class average rather than stacking on it: both describe the
     * same correction, so summing them would double-correct whatever the two
     * agree about. The specific measurement always wins over the class guess.
     */
    private fun deviceCorrectionShape(index: Int): Float {
        if (currentDeviceKey == "speaker") return 0f
        deviceProfileCurve?.let { return it.getOrElse(index) { 0f } }
        return _deviceType.value.correctionDb(index, _deviceQualityTier.value)
    }

    private fun parametricEqShape(index: Int): Float {
        val loHz = if (index == 0) 20f else EqBands.cutoffFrequencies[index - 1]
        return digitalFilterEngine.evaluateBandPeakDb(loHz, EqBands.cutoffFrequencies[index])
    }

    /**
     * True when SBC encoder-input conditioning should be running: the user
     * enabled it AND we are actually on a Bluetooth route. Single source of
     * truth for the tonal half (below), the dynamics half (DspEngine's crest
     * band) and hasActiveDspFeatures, so the three cannot disagree and leave
     * the curve written with no crest band behind it, or vice versa.
     */
    private fun sbcConditioningActive(): Boolean =
        _sbcModeEnabled.value && currentDeviceKey.startsWith("bt")

    /**
     * The tonal half of SBC conditioning — see [SbcEngine] for why this curve
     * cuts the top octave instead of boosting it.
     */
    private fun sbcPreEmphasisShape(index: Int): Float {
        if (!sbcConditioningActive()) return 0f
        return remoteTuning.sbcPreEmphasis?.getOrNull(index)
            ?: SbcEngine.conditioningCurveDb.getOrElse(index) { 0f }
    }

    private fun staticPreEqShape(index: Int): Float =
        manualBandGains[index] + parametricEqShape(index) + loudnessCompensation[index] +
            crossfeedShape(index) + deviceCorrectionShape(index) + sbcPreEmphasisShape(index)

    private fun preEqBassPeakDb(): Float =
        LoudnessContour.BASS_BANDS.maxOf(::staticPreEqShape).coerceAtLeast(0f)

    private fun preEqTreblePeakDb(): Float =
        LoudnessContour.TREBLE_BANDS.maxOf(::staticPreEqShape).coerceAtLeast(0f)

    private var volumeObserver: ContentObserver? = null
    private var loudnessDebounceJob: Job? = null

    /** Volume-tracking poll for the Loudness Contour — see updateLoudnessTracking. */
    private var loudnessPollJob: Job? = null

    /**
     * AudioDeviceInfo.TYPE_* of the active output route, kept in sync by
     * computeOutputDeviceKey(). Needed because getStreamVolumeDb() reports a
     * DIFFERENT attenuation curve per device type — the same volume index is
     * not the same dB on speaker vs Bluetooth.
     */
    @Volatile private var currentOutputDeviceApiType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    // ── Analog Bass state ────────────────────────────────────────────
    val analogBassEngine = AnalogBassEngine()
    val digitalFilterEngine = DigitalFilterEngine()

    private val _analogBassEnabled = MutableStateFlow(false)
    val analogBassEnabled: StateFlow<Boolean> = _analogBassEnabled.asStateFlow()

    private val _analogBassDrive = MutableStateFlow(0.4f)
    val analogBassDrive: StateFlow<Float> = _analogBassDrive.asStateFlow()

    private val _analogBassWarmth = MutableStateFlow(0.7f)
    val analogBassWarmth: StateFlow<Float> = _analogBassWarmth.asStateFlow()

    private val _analogBassDrift = MutableStateFlow(0.2f)
    val analogBassDrift: StateFlow<Float> = _analogBassDrift.asStateFlow()

    private val _analogBassPultecBoost = MutableStateFlow(0.5f)
    val analogBassPultecBoost: StateFlow<Float> = _analogBassPultecBoost.asStateFlow()

    private val _analogBassPultecCut = MutableStateFlow(0.3f)
    val analogBassPultecCut: StateFlow<Float> = _analogBassPultecCut.asStateFlow()

    private val _analogBassPultecFreqIndex = MutableStateFlow(2)
    val analogBassPultecFreqIndex: StateFlow<Int> = _analogBassPultecFreqIndex.asStateFlow()

    // ── SBC Enhancement state ─────────────────────────────────────────────
    // Pre-emphasis for Bluetooth SBC codec: boosts 10–16kHz to force SBC's
    // bit-allocator to spend more bits on treble, masking quantization
    // harshness. Must never auto-enable on LDAC/LHDC profiles.
    private val _sbcModeEnabled = MutableStateFlow(false)
    val sbcModeEnabled: StateFlow<Boolean> = _sbcModeEnabled.asStateFlow()

    // ── Digital Filter State ───────────────────────────────────────────
    val digitalFilterBandStates: StateFlow<List<DigitalFilterEngine.BiquadBandState>>
        get() = digitalFilterEngine.bandStates

    private val _digitalFilterEnabled = MutableStateFlow(false)
    val digitalFilterEnabled: StateFlow<Boolean> = _digitalFilterEnabled.asStateFlow()

    private var saveDebounceJob: Job? = null
    private var peqApplyJob: Job? = null
    // Generation counter guarding the delayed PreEQ "settle" re-apply after
    // HDR Dynamics is enabled (see setHdrDynamicsEnabled).
    private var hdrSettleToken = 0

    // Per-output-device profile switching (see computeOutputDeviceKey/switchToProfile)
    @Volatile private var currentDeviceKey: String = "speaker"

    /** Serializes profile switches — see [switchToProfileNow]. */
    private val profileSwitchMutex = Mutex()

    // ── Per-app profiles ──────────────────────────────────────────────────
    // Opt-in per package. When an app is opted in, its settings are stored
    // under "<deviceKey>@<package>" instead of plain "<deviceKey>", and the
    // service swaps profiles when playback moves between apps — the same
    // save-old/load-new dance already used for output-route changes.
    // currentProfileKey is the single source of truth for which key
    // saveSession()/switchToProfile() are actually operating on.
    @Volatile private var currentProfileKey: String = "speaker"
    @Volatile private var perAppPackages: Set<String> = emptySet()

    /**
     * False until restoreSession() has finished applying persisted state.
     *
     * Guards the save half of switchToProfile: a route or app change arriving
     * in that window would otherwise persist the service's blank startup
     * defaults over a real, already-tuned profile. Loading is always safe;
     * only saving has to wait.
     */
    @Volatile private var sessionRestored = false

    private val _perAppProfilePackages = MutableStateFlow<Set<String>>(emptySet())
    val perAppProfilePackages: StateFlow<Set<String>> = _perAppProfilePackages.asStateFlow()

    /** True when the settings currently on screen belong to a per-app profile, not the device profile. */
    private val _perAppProfileActive = MutableStateFlow(false)
    val perAppProfileActive: StateFlow<Boolean> = _perAppProfileActive.asStateFlow()

    // ── Remotely updatable content (Lane A) ───────────────────────────────
    // Tuning constants and device profiles that can change without an APK.
    // See ContentRepository for the fetch/cache/fallback chain. Held as a
    // plain field (not a flow) on the audio side because every read is on the
    // hot PreEQ write path — see writeCombinedBand.
    lateinit var contentRepository: ContentRepository
        private set

    @Volatile private var remoteTuning: RemoteTuning = RemoteTuning()

    private val _remoteContent = MutableStateFlow(RemoteContent.EMPTY)
    val remoteContent: StateFlow<RemoteContent> = _remoteContent.asStateFlow()

    /**
     * The content-channel device profile matching the current output, if any.
     * Only a suggestion — it's surfaced in the UI as a one-tap "apply" rather
     * than being forced, because the user's own DeviceType choice for a route
     * is a deliberate setting and shouldn't be silently overwritten by a
     * remote document.
     */
    private val _suggestedDeviceProfile = MutableStateFlow<RemoteHeadphoneProfile?>(null)
    val suggestedDeviceProfile: StateFlow<RemoteHeadphoneProfile?> = _suggestedDeviceProfile.asStateFlow()

    /**
     * Name of the content-channel device profile currently applied, or "" for
     * none. Persisted per output device; the CURVE itself is resolved from
     * live content (see applyDeviceProfileCurve) rather than stored, so a
     * later content update improving a curve reaches everyone already on it.
     */
    private val _activeDeviceProfileName = MutableStateFlow("")
    val activeDeviceProfileName: StateFlow<String> = _activeDeviceProfileName.asStateFlow()

    /** Live resolved curve for [_activeDeviceProfileName]; null when none. */
    @Volatile private var deviceProfileCurve: FloatArray? = null

    /**
     * Profile key for the current (device, app) pair: the per-app key when the
     * active package is opted in, otherwise the plain device key.
     */
    private fun resolveProfileKey(): String {
        val pkg = _activePackageName.value
        return if (pkg != null && pkg in perAppPackages) {
            sessionPreferences.perAppProfileKey(currentDeviceKey, pkg)
        } else currentDeviceKey
    }

    private var audioDeviceCallback: AudioDeviceCallback? = null
    // Background thread for AudioDeviceCallback so routing-change logic
    // (filter re-init, getDevices queries) never runs on the main looper.
    private var audioDeviceHandlerThread: HandlerThread? = null
    private var audioDeviceHandler: Handler? = null

    // ── Playback-stream watcher (see registerAudioPlaybackCallback) ───────
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    private var lastPlaybackFingerprint: String? = null
    private var playbackReattachJob: Job? = null
    private var lastPlaybackReattachAt = 0L

    inner class LocalBinder : Binder() {
        fun getService(): JadooDspService = this@JadooDspService
    }

    override fun onCreate() {
        super.onCreate()
        // Detect actual device sample rate (fallback to 48000 if unavailable)
        val detectedRate = try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toFloatOrNull() ?: 48000f
        } catch (_: Exception) { 48000f }
        analogBassEngine.initialize(detectedRate)
        digitalFilterEngine.initialize(detectedRate)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionController = SessionController(this)
        sessionPreferences = SessionPreferences(this)
        contentRepository = ContentRepository(this)

        // Lane A content: resolves bundled → cached immediately, then refreshes
        // from the network if due. A newly adopted document can change the SBC
        // curve and the HDR expander shape, so the live topology is rebuilt
        // when one arrives (only when something is actually running).
        serviceScope.launch {
            contentRepository.content.collect { content ->
                val tuningChanged = content.tuning != remoteTuning
                remoteTuning = content.tuning
                _remoteContent.value = content
                refreshSuggestedDeviceProfile()
                // Re-resolve any applied device correction against the new
                // content, so an improved curve reaches devices already using
                // that profile without them having to re-apply it.
                val previousCurve = deviceProfileCurve
                applyDeviceProfileCurve()
                val curveChanged = !(previousCurve?.contentEquals(deviceProfileCurve ?: FloatArray(0))
                    ?: (deviceProfileCurve == null))
                if (curveChanged && _masterEnabled.value && dspEngine.dynamicsProcessing != null) {
                    applyAllBands(manualBandGains.copyOf())
                    refreshHeadroom()
                }
                if (tuningChanged && _masterEnabled.value && dspEngine.dynamicsProcessing != null) {
                    rebuildDspTopology()
                }
            }
        }
        serviceScope.launch { contentRepository.initialize() }

        val (initialDeviceKey, initialDeviceLabel) = computeOutputDeviceKey()
        currentDeviceKey = initialDeviceKey
        currentProfileKey = initialDeviceKey
        _currentOutputDevice.value = initialDeviceLabel
        refreshSuggestedDeviceProfile()
        registerAudioDeviceCallback()
        registerAudioPlaybackCallback()
        registerVolumeObserver()

        // Load the per-app opt-in list before anything can resolve a profile
        // key from it, so the very first restoreSession() below already picks
        // the per-app profile when the active app has one.
        serviceScope.launch {
            sessionPreferences.perAppProfilePackages.collect { packages ->
                perAppPackages = packages
                _perAppProfilePackages.value = packages
                // The opt-in list changing can change which profile the
                // CURRENT app should be using (the user just toggled it).
                syncProfileForActiveApp()
            }
        }

        startForegroundService()
        registerMediaSessionListener()
        restoreSession()
    }

    /**
     * Watches system volume changes so the Loudness Contour can re-tilt the
     * PreEQ as the level moves.
     *
     * Android has no public broadcast for media-volume changes
     * (VOLUME_CHANGED_ACTION is hidden), so the supported route is a
     * ContentObserver on Settings.System — it fires for every volume-key
     * press and every slider drag. It also fires for unrelated system
     * settings, which is why recomputeLoudness() short-circuits when the
     * resulting level hasn't actually moved.
     */
    private fun registerVolumeObserver() {
        val handler = audioDeviceHandler ?: Handler(mainLooper)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                recomputeLoudness()
            }
        }
        volumeObserver = observer
        try {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register volume observer: ${e.message}")
        }
    }

    /**
     * Identify the ACTIVE audio output route so each device type can keep its
     * own persisted DSP profile. Priority: USB > Bluetooth > Wired > Speaker.
     *
     * IMPORTANT: AudioManager.getDevices(GET_DEVICES_OUTPUTS) returns all
     * *connected* output devices, NOT the currently active route — on most
     * phones the built-in speaker is always present in that list even while
     * headphones are plugged in. The correct APIs to detect the active route
     * are the AudioManager state flags (isWiredHeadsetOn, isBluetoothA2dpOn)
     * combined with getDevices() to look up the device name for BT.
     * On API 31+ we also check for BLE Audio (TYPE_BLE_HEADSET/SPEAKER/BROADCAST).
     */
    private fun computeOutputDeviceKey(): Pair<String, String> {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ── USB: highest priority — check connected output list ──────────
        // USB DAC/headset is always the active route when plugged in (Android
        // forces routing to USB audio). getDevices() is correct here because
        // USB audio is exclusive — if it's in the list it IS the active route.
        val allOutputs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
                         catch (_: Exception) { emptyArray() }
        val mediaOutputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                am.getAudioDevicesForAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                ).toTypedArray().takeIf { it.isNotEmpty() } ?: allOutputs
            } catch (e: Exception) {
                Log.w(TAG, "Active media route query failed; using legacy detection: ${e.message}")
                allOutputs
            }
        } else allOutputs

        val usb = mediaOutputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (usb != null) {
            // Build a per-device profile key using the DAC's product name, the
            // same way Bluetooth headphone profiles are keyed. Two different USB
            // DACs (e.g. Topping D10s vs Fiio BTR5) have different frequency
            // responses and should each keep their own saved EQ profile.
            val rawUsbName = try { usb.productName?.toString()?.trim() } catch (_: Exception) { null }
            val (usbKey, usbLabel) = if (!rawUsbName.isNullOrBlank()) {
                val safeName = rawUsbName.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().take(40)
                if (safeName.isNotBlank()) "usb_${safeName.replace(' ', '_')}" to "USB: $safeName"
                else "usb" to "USB Audio"
            } else "usb" to "USB Audio"

            updateFilterSampleRate(am, usb, usbLabel)
            currentOutputDeviceApiType = usb.type
            return usbKey to usbLabel
        }

        // ── Bluetooth A2DP / BLE Audio ────────────────────────────────────
        // isBluetoothA2dpOn() is the authoritative flag for the active A2DP
        // route — true only when a BT device is both connected AND currently
        // the audio output. getDevices() alone can include paired-but-idle BT
        // devices which aren't actually playing.
        @Suppress("DEPRECATION")
        val btA2dpActive = try { am.isBluetoothA2dpOn } catch (_: Exception) { false }
        val bleActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mediaOutputs.any {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
            }
        } else false

        val routedBtDevice = mediaOutputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                         it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        if (btA2dpActive || bleActive ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && routedBtDevice != null)) {
            val btDevice = routedBtDevice
            currentOutputDeviceApiType = btDevice?.type ?: AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

            // Re-initialise the biquad engines at this route's rate, exactly as
            // the USB and speaker branches already do. Bluetooth was the one
            // route that never did, so switching from the 48kHz speaker to a
            // 44.1kHz A2DP sink left every coefficient still solved for 48kHz.
            // Both engines are bilinear-transform designs, whose frequency
            // warping is a function of the sample rate — so the Parametric EQ
            // magnitude response folded into the PreEQ, and Analog Bass's
            // shaping, both land slightly off their intended frequencies, with
            // the error growing towards Nyquist where it is most audible.
            updateFilterSampleRate(am, btDevice, "Bluetooth")

            val rawName = try { btDevice?.productName?.toString()?.trim() } catch (_: Exception) { null }
            return if (!rawName.isNullOrBlank()) {
                val safeName = rawName.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().take(40)
                if (safeName.isNotBlank()) "bt_${safeName.replace(' ', '_')}" to "Bluetooth: $safeName"
                else "bt" to "Bluetooth"
            } else "bt" to "Bluetooth"
        }

        // ── Wired headset / headphones ────────────────────────────────────
        // isWiredHeadsetOn() is the active-route flag; TYPE_WIRED_* in
        // getDevices() is the connected flag. Use the flag to confirm routing.
        @Suppress("DEPRECATION")
        val wiredActive = try { am.isWiredHeadsetOn } catch (_: Exception) { false }
        if (wiredActive) {
            val wiredDevice = mediaOutputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            currentOutputDeviceApiType = wiredDevice?.type ?: AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            updateFilterSampleRate(am, wiredDevice, "Wired Headphones")
            return "wired" to "Wired Headphones"
        }

        // ── Phone speaker (default) ───────────────────────────────────────
        // Re-detect sample rate in case a USB DAC was just unplugged — the HAL
        // switches back to the phone's native rate. PROPERTY_OUTPUT_SAMPLE_RATE
        // can still briefly reflect the USB rate right after removal; the built-in
        // speaker's AudioDeviceInfo entry is more reliable for its supported rates.
        val speakerDevice = mediaOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: allOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        updateFilterSampleRate(am, speakerDevice, "Phone Speaker")
        currentOutputDeviceApiType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        return "speaker" to "Phone Speaker"
    }

    /**
     * AudioDeviceInfo.sampleRates is a capability list, not the active rate.
     * Android does not expose the exact live output-mix rate here. Prefer the
     * platform's native primary-output rate; if an OEM reports a value the
     * route cannot use, choose the closest capability instead of incorrectly
     * treating the highest advertised rate as active.
     */
    private fun updateFilterSampleRate(
        audioManager: AudioManager,
        device: AudioDeviceInfo?,
        routeLabel: String
    ) {
        val reportedRate = try {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toFloatOrNull()
        } catch (_: Exception) { null }
        val safeReportedRate = reportedRate
            ?.takeIf { it.isFinite() && it in 8_000f..384_000f }
            ?: DigitalFilterEngine.DEFAULT_SAMPLE_RATE_HZ
        val supportedRates = (device?.sampleRates ?: intArrayOf()).filter { it in 8_000..384_000 }
        val resolvedRate = if (supportedRates.isEmpty()) safeReportedRate else {
            supportedRates.minByOrNull { abs(it - safeReportedRate) }?.toFloat() ?: safeReportedRate
        }
        if (resolvedRate != digitalFilterEngine.sampleRateHz) {
            analogBassEngine.initialize(resolvedRate)
            digitalFilterEngine.initialize(resolvedRate)
            Log.d(TAG, "$routeLabel — filters re-initialized at ${resolvedRate}Hz " +
                "(supported: ${supportedRates.joinToString()}, platform reports: ${safeReportedRate}Hz)")
        }
    }

    // ── Loudness Contour ──────────────────────────────────────────────────

    /**
     * Estimates the current listening level in phon from the system media
     * volume.
     *
     * getStreamVolumeDb (API 28) is the key API here: unlike getStreamVolume,
     * which returns an opaque index whose relationship to loudness is
     * non-linear and device-specific, this returns the ACTUAL attenuation in
     * dB that the platform applies at that index on that output device. The
     * current level is therefore the reference level minus however far below
     * maximum the user currently is:
     *
     *   currentPhon = referencePhon - (maxVolumeDb - currentVolumeDb)
     *
     * The reference level itself can't be measured — it's the SPL the device
     * actually produces at full volume, which depends on the transducer, not
     * on anything Android exposes. It's a user-set per-device calibration
     * (see loudnessReferencePhon); everything else here is measured.
     */
    private fun computeCurrentPhon(): Float {
        val reference = _loudnessReferencePhon.value
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return reference
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxIndex = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxIndex <= 0) return reference
            val currentDb = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, currentOutputDeviceApiType)
            val maxDb = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, maxIndex, currentOutputDeviceApiType)
            // At volume 0 the platform reports a huge negative (often
            // -Float.MAX_VALUE) for "muted". Nothing is audible there, so
            // pinning to the model's floor is both correct and keeps the
            // arithmetic finite.
            if (!currentDb.isFinite() || !maxDb.isFinite()) return LoudnessContour.MIN_PHON
            var attenuation = (maxDb - currentDb).coerceAtLeast(0f)
            // ── Bluetooth absolute-volume fallback ───────────────────────
            // On an A2DP route with absolute volume (the default on modern
            // Android), the phone does not attenuate the stream at all — it
            // forwards the level to the sink, which does its own scaling.
            // getStreamVolumeDb then reports the SAME dB at every index, so
            // the measurement above yields 0 dB of attenuation no matter
            // where the slider sits, the contour concludes it is already at
            // the reference level, and the whole feature silently does
            // nothing on Bluetooth. That is the reported behaviour.
            //
            // When the reading is degenerate (no attenuation reported even
            // though the user is demonstrably below maximum), fall back to
            // estimating from the index ratio as a plain amplitude ratio.
            // Deliberately conservative: Android's real curve is steeper at
            // the bottom than -20*log10(ratio), so this under-corrects
            // rather than over-corrects on a route where the true curve is
            // unknowable. Only engages in the degenerate case, so it cannot
            // disturb routes where the measurement works.
            if (attenuation <= 0.01f && index < maxIndex) {
                val ratio = (index.toFloat() / maxIndex.toFloat()).coerceIn(0.0001f, 1f)
                attenuation = (-20f * log10(ratio)).coerceAtLeast(0f)
            }
            (reference - attenuation).coerceIn(LoudnessContour.MIN_PHON, LoudnessContour.MAX_PHON)
        } catch (e: Exception) {
            Log.w(TAG, "getStreamVolumeDb unavailable: ${e.message}")
            reference
        }
    }

    /**
     * Recomputes the loudness correction and pushes it into the live PreEQ.
     *
     * Debounced because the ContentObserver fires on every volume-key repeat
     * (and on unrelated Settings.System writes) — without it, holding
     * volume-down would trigger a 15-band PreEQ rewrite plus a limiter
     * headroom update per step. The comparison against the previous curve
     * then discards the remaining no-op wakeups entirely.
     */
    private fun recomputeLoudness() {
        loudnessDebounceJob?.cancel()
        loudnessDebounceJob = serviceScope.launch {
            delay(120)
            applyLoudnessNow()
        }
    }

    /**
     * Starts/stops the volume-tracking poll that backs the Loudness Contour.
     *
     * The ContentObserver on Settings.System is NOT a dependable volume signal
     * on modern Android — media volume is increasingly not written there, and
     * on ROMs where it isn't, the observer never fires. The correction then
     * freezes at whatever level it was last computed for: turn the volume down
     * and no bass compensation arrives; turn it back up and a large stale
     * boost stays applied. The feature looks broken and unpredictable, which
     * matches the reported behaviour.
     *
     * A slow poll is the honest fix. One getStreamVolume plus two
     * getStreamVolumeDb reads per second, and [applyLoudnessNow] already
     * discards any change under 0.1 dB before it touches a single band — so in
     * the steady state this costs three cheap AudioManager calls a second and
     * nothing else. It only runs while the feature is actually engaged.
     */
    private fun updateLoudnessTracking() {
        val shouldTrack = _loudnessEnabled.value && _masterEnabled.value
        if (!shouldTrack) {
            loudnessPollJob?.cancel()
            loudnessPollJob = null
            return
        }
        if (loudnessPollJob?.isActive == true) return
        loudnessPollJob = serviceScope.launch {
            while (isActive && _loudnessEnabled.value && _masterEnabled.value) {
                delay(1000)
                applyLoudnessNow()
            }
        }
    }

    /** Immediate (undebounced) recompute — for toggles and slider commits. */
    private fun applyLoudnessNow() {
        val enabled = _loudnessEnabled.value
        val phon = if (enabled) computeCurrentPhon() else _loudnessReferencePhon.value
        _loudnessCurrentPhon.value = phon
        val next = if (enabled) {
            LoudnessContour.compensationDb(
                referencePhon = _loudnessReferencePhon.value,
                currentPhon = phon,
                amount = _loudnessAmount.value,
                driverExtension = loudnessDriverExtension()
            )
        } else FloatArray(EqBands.count)

        // Sub-0.1dB moves are inaudible and not worth a full band rewrite —
        // this is what turns the ContentObserver's firehose of unrelated
        // Settings.System changes into near-zero work.
        val changed = next.indices.any { kotlin.math.abs(next[it] - loudnessCompensation[it]) > 0.1f }
        if (!changed) return
        loudnessCompensation = next

        if (!_masterEnabled.value) return
        if (dspEngine.dynamicsProcessing == null) {
            // Feature was just switched on while the engine was fully bypassed
            // (nothing else active) — attachSession now sees it as an active
            // feature and will build the topology.
            attachGlobalSession()
            return
        }
        applyAllBands(manualBandGains.copyOf())
        refreshHeadroom()
    }

    /**
     * Re-applies the limiter's gain-budget headroom from every current flow
     * value. Single entry point so a new boosting feature only has to be
     * added to DspEngine.calculateHeadroomOffset and here, rather than to
     * each of the half-dozen live-update call sites that all need the same
     * full picture.
     */
    private fun refreshHeadroom() {
        dspEngine.updateHeadroom(
            hiResEnabled = _hiResUpscalerEnabled.value,
            dbfbMode = _dbfbMode.value,
            analogBassEnabled = _analogBassEnabled.value,
            tubeWarmthEnabled = _tubeWarmthEnabled.value,
            tubeWarmthIntensity = _tubeWarmthIntensity.value,
            mobileBassEnabled = _mobileBassEnabled.value,
            mobileBassIntensity = _mobileBassIntensity.value,
            surroundMode = _surroundMode.value,
            harmonicExciterEnabled = _harmonicExciterEnabled.value,
            harmonicExciterIntensity = _harmonicExciterIntensity.value,
            bassExtension = bassExtension(),
            trebleExtension = trebleExtension(),
            mobileBassExtension = mobileBassExtension(),
            preEqBassPeakDb = preEqBassPeakDb(),
            preEqTreblePeakDb = preEqTreblePeakDb(),
            preciseGainStaging = _preciseGainStaging.value
        )
        publishGainStagingReadout()
    }

    /** Mirror the engine's live gain-staging numbers into the UI flows. */
    private fun publishGainStagingReadout() {
        _gainBudgetDb.value = dspEngine.gainBudgetDb
        _autoTrimDb.value = dspEngine.autoTrimReadoutDb
    }

    /**
     * Toggle between the corrected gain-staging model and the pre-v1.6 one.
     * Needs a full rebuild rather than a headroom refresh: the two models
     * differ in the INPUT gain as well as the limiter threshold, and the
     * input stage is written during attach.
     */
    fun setPreciseGainStaging(enabled: Boolean) {
        _preciseGainStaging.value = enabled
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    /**
     * Records which preset is "selected" (built-in or custom) so the
     * highlighted chip and Overwrite target survive a relaunch. Pure UI
     * bookkeeping — never touches the DSP chain, the band gains that preset
     * produced are already live and already persisted independently of this.
     */
    fun setSelectedPresetName(name: String?) {
        _selectedPresetName.value = name ?: ""
        saveSession()
    }

    fun setLoudnessEnabled(enabled: Boolean) {
        _loudnessEnabled.value = enabled
        if (!enabled) {
            // Zero the curve and re-flatten the PreEQ before anything else, so
            // turning the feature off can never leave a stale tilt behind.
            loudnessCompensation = FloatArray(EqBands.count)
            if (_masterEnabled.value) {
                applyAllBands(manualBandGains.copyOf())
                // May have been the only active feature — re-evaluate so the
                // engine can drop to true bypass.
                attachGlobalSession()
            }
        } else if (_masterEnabled.value) {
            applyLoudnessNow()
        }
        updateLoudnessTracking()
        saveSession()
    }

    fun setLoudnessAmount(value: Float) {
        _loudnessAmount.value = value.coerceIn(0f, 1f)
        if (_loudnessEnabled.value && _masterEnabled.value) applyLoudnessNow()
        saveSession()
    }

    fun setLoudnessReferencePhon(value: Float) {
        _loudnessReferencePhon.value =
            value.coerceIn(LoudnessContour.MIN_PHON, LoudnessContour.MAX_PHON)
        if (_loudnessEnabled.value && _masterEnabled.value) applyLoudnessNow()
        saveSession()
    }

    private fun registerAudioDeviceCallback() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Run callbacks on a dedicated background thread — computeOutputDeviceKey()
        // re-initializes filter engines and queries AudioManager.getDevices(), both of
        // which should not block the main looper.
        val ht = HandlerThread("JadOO-AudioDevice").also { it.start() }
        audioDeviceHandlerThread = ht
        audioDeviceHandler = Handler(ht.looper)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                handleOutputRouteChange()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                handleOutputRouteChange()
            }
        }
        audioDeviceCallback = callback
        am.registerAudioDeviceCallback(callback, audioDeviceHandler)
    }

    /**
     * Watches the set of live playback streams, and re-attaches the DSP when
     * it changes.
     *
     * This is what fixes "lossless sounds wrong until I toggle the app off and
     * on again, every single track". Apple Music and other lossless sources
     * switch output sample rate per track (44.1 / 48 / 96 / 192 kHz). Each
     * switch makes AudioFlinger tear down and rebuild the output mix thread.
     * Our DynamicsProcessing is attached to GLOBAL_AUDIO_SESSION_ID, so it is
     * left bound to the mix that no longer exists — processing silently stops
     * or half-applies, which is the "odd" sound. attachSession() already knows
     * this (see its forceReattach note); what was missing was anything that
     * NOTICED it happening mid-album.
     *
     * None of the three existing triggers fire on a track change inside one
     * app:
     *   - ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION: Apple Music never
     *     broadcasts them at all.
     *   - OnActiveSessionsChangedListener: the app keeps ONE MediaSession
     *     across its whole queue, so the active-session list is unchanged.
     *   - AudioDeviceCallback: the route did not change, only the format.
     *
     * A playback-configuration change does fire, because the old AudioTrack is
     * destroyed and a new one created for the new format.
     *
     * Two guards keep this from thrashing. The fingerprint ignores callbacks
     * that do not actually change the playback set (gapless playback at the
     * same rate never tears the track down, and correctly gets no re-attach).
     * The rate limit stops a burst of callbacks around one transition from
     * rebuilding the topology several times, which would be audible.
     *
     * AudioPlaybackConfiguration does not expose the stream's sample rate on
     * any public API level, so it serves as the TRIGGER only — the rate itself
     * still comes from handleOutputRouteChange()'s existing resolution, which
     * is as accurate as the platform's own reporting (see updateFilterSampleRate).
     */
    private fun registerAudioPlaybackCallback() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                handlePlaybackConfigChanged(configs)
            }
        }
        audioPlaybackCallback = callback
        try {
            // Same background handler as the device callback: the work this
            // schedules queries AudioManager.getDevices() and re-initialises
            // the filter engines, neither of which belongs on the main looper.
            am.registerAudioPlaybackCallback(callback, audioDeviceHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Playback callback unavailable on this ROM: ${e.message}")
            audioPlaybackCallback = null
        }
    }

    /**
     * Collapses the live playback set into a string that changes only when the
     * set itself meaningfully changes. Restricted to media usages so that a
     * notification chime or a navigation prompt starting does not count as a
     * track change.
     *
     * Only public AudioPlaybackConfiguration surface is used here:
     * getAudioAttributes() is public, while the client uid/session accessors
     * that would let us attribute a stream to an app are @SystemApi and not
     * callable from a normal app.
     */
    private fun playbackFingerprint(configs: List<AudioPlaybackConfiguration>): String =
        configs.asSequence()
            .map { it.audioAttributes }
            .filter {
                it.usage == AudioAttributes.USAGE_MEDIA ||
                    it.usage == AudioAttributes.USAGE_UNKNOWN
            }
            .map { "${it.usage}:${it.contentType}:${it.flags}" }
            .sorted()
            .joinToString("|")

    private fun handlePlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
        val fingerprint = try {
            playbackFingerprint(configs)
        } catch (e: Exception) {
            Log.w(TAG, "Playback fingerprint failed: ${e.message}")
            return
        }
        if (fingerprint == lastPlaybackFingerprint) return
        val previous = lastPlaybackFingerprint
        lastPlaybackFingerprint = fingerprint
        // Nothing is playing — no mix to re-attach to. The next transition
        // back to a non-empty set is the one worth acting on.
        if (fingerprint.isEmpty()) return
        // First fingerprint after the service starts is not a track change;
        // restoreSession()/attachSession have already handled the initial attach.
        if (previous == null) return
        if (!_masterEnabled.value) return

        val now = System.currentTimeMillis()
        if (now - lastPlaybackReattachAt < PLAYBACK_REATTACH_MIN_INTERVAL_MS) return
        lastPlaybackReattachAt = now

        playbackReattachJob?.cancel()
        playbackReattachJob = serviceScope.launch {
            // Let the new output stream settle before we resolve its rate and
            // rebuild against it — re-attaching into a half-built mix thread
            // is how we would end up bound to a stale session all over again.
            delay(PLAYBACK_REATTACH_DEBOUNCE_MS)
            // Re-resolves the route AND re-reads the output sample rate, so a
            // 44.1 -> 96kHz track change also re-derives the parametric EQ
            // coefficients instead of leaving them on the old rate.
            audioDeviceHandler?.post { handleOutputRouteChange() }
            resolveAndAttachSession()
            Log.d(TAG, "Playback stream changed — re-attached DSP to the new output mix")
        }
    }

    /**
     * Called whenever the system's audio output routing changes (headphones
     * plugged/unplugged, Bluetooth connect/disconnect, etc). If the resolved
     * device key differs from the one currently in use, switches to that
     * device's saved DSP profile (or sensible defaults for a new device).
     */
    private fun handleOutputRouteChange() {
        try {
            val (newKey, newLabel) = computeOutputDeviceKey()
            _currentOutputDevice.value = newLabel
            if (newKey == currentDeviceKey) {
                recomputeLoudness()
                return
            }
            currentDeviceKey = newKey
            refreshSuggestedDeviceProfile()
            switchToProfile(resolveProfileKey(), "output device: $newLabel")
        } catch (e: Exception) {
            Log.e(TAG, "handleOutputRouteChange failed (OEM HAL issue?): ${e.message}", e)
        }
    }

    /**
     * Looks for a content-channel profile matching the current output device's
     * label (e.g. "Bluetooth: WH-1000XM4"). Cleared on routes that carry no
     * product name — the phone speaker and generic wired output can't be
     * identified, so there's nothing to match against.
     */
    private fun refreshSuggestedDeviceProfile() {
        val label = _currentOutputDevice.value
        _suggestedDeviceProfile.value = _remoteContent.value.headphoneProfiles
            .firstOrNull { it.matches(label) }
            // Only meaningful once we know it would actually change something.
            ?.takeIf {
                it.deviceType != _deviceType.value.name ||
                    kotlin.math.abs(it.qualityTier - _deviceQualityTier.value) > 0.01f
            }
    }

    /**
     * Applies the matched content-channel device profile — user-initiated
     * only. Sets the same two values the manual DeviceType picker does, so it
     * goes through exactly the same code path and persists in the current
     * profile like any other manual change.
     */
    fun applySuggestedDeviceProfile() {
        val suggestion = _suggestedDeviceProfile.value ?: return
        // Remember WHICH profile is active rather than copying its curve into
        // the user's own band gains. Copying would silently overwrite an EQ
        // they had dialled in, and would also freeze the correction at the
        // version that happened to be live when they tapped Apply — a later
        // content update improving the curve would never reach them.
        //
        // Set before setDeviceType for the same reason as selectDeviceProfile:
        // the rebuild it triggers decides whether the engine stays attached.
        _activeDeviceProfileName.value = suggestion.name
        applyDeviceProfileCurve()
        val type = DeviceType.entries.firstOrNull { it.name == suggestion.deviceType }
        if (type != null) setDeviceType(type)
        setDeviceQualityTier(suggestion.qualityTier)
        _suggestedDeviceProfile.value = null
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyAllBands(manualBandGains.copyOf())
            refreshHeadroom()
        }
        saveSession()
    }

    /**
     * Applies a content-channel device tuning chosen by name.
     *
     * The manual counterpart to [applySuggestedDeviceProfile]. Auto-matching
     * only works where the phone can see the transducer itself; anything
     * behind an intermediary (a PC over Bluetooth, an AV receiver, a DAC
     * driving passive speakers) reports the intermediary's name instead, so
     * for those devices no match string is ever correct and hand-selection is
     * the only route to the tuning.
     */
    fun selectDeviceProfile(name: String) {
        val profile = _remoteContent.value.headphoneProfiles.firstOrNull { it.name == name } ?: return
        // Resolve the curve FIRST. setDeviceType below triggers a topology
        // rebuild, which asks hasActiveDspFeatures() whether to keep the
        // engine attached — and with the curve not yet live, a profile
        // selected as the only active feature would answer "no", release the
        // engine, and never write the correction.
        _activeDeviceProfileName.value = profile.name
        applyDeviceProfileCurve()
        DeviceType.entries.firstOrNull { it.name == profile.deviceType }?.let { setDeviceType(it) }
        setDeviceQualityTier(profile.qualityTier)
        _suggestedDeviceProfile.value = null
        if (_masterEnabled.value) {
            // Re-attach in case the engine was sitting in transparent bypass
            // with nothing else enabled; applyAllBands is a no-op without it.
            attachGlobalSession()
            applyAllBands(manualBandGains.copyOf())
            refreshHeadroom()
        }
        saveSession()
    }

    /** Clears any active content-channel device correction. */
    fun clearDeviceProfile() {
        _activeDeviceProfileName.value = ""
        deviceProfileCurve = null
        if (_masterEnabled.value) {
            // Write the bands back without the correction FIRST, then let
            // attachGlobalSession re-evaluate — if this was the only thing
            // active, the engine can now legitimately drop to bypass.
            applyAllBands(manualBandGains.copyOf())
            refreshHeadroom()
            attachGlobalSession()
        }
        saveSession()
    }

    /**
     * Resolves [_activeDeviceProfileName] against the current content into a
     * live curve. Re-run whenever content refreshes or the name is restored, so
     * an improved curve shipped later reaches devices already using it.
     */
    private fun applyDeviceProfileCurve() {
        val name = _activeDeviceProfileName.value
        deviceProfileCurve = if (name.isBlank()) null
        else _remoteContent.value.headphoneProfiles.firstOrNull { it.name == name }?.curve
    }

    /** Manual "check for new content now" — used by the settings UI. */
    fun refreshRemoteContent(onComplete: (Boolean) -> Unit = {}) {
        serviceScope.launch {
            val updated = contentRepository.refresh()
            onComplete(updated)
        }
    }

    /**
     * Called whenever the active app or the per-app opt-in list changes: if
     * that means a different profile key now applies, switch to it. No-op in
     * the common case where the active app has no per-app profile and the
     * device profile was already loaded.
     */
    private fun syncProfileForActiveApp() {
        serviceScope.launch { syncProfileForActiveAppNow(rebuild = true) }
    }

    /**
     * Suspending form, so [resolveAndAttachSession] can WAIT for the profile
     * to be in place before it attaches.
     *
     * It used to fire-and-forget: the attach then ran against whatever profile
     * happened to still be loaded, and the correct one landed a few hundred ms
     * later via its own rebuild. That is the "sometimes I have to toggle the
     * app off and on again to make it sound normal" report — the DSP really was
     * configured from the previous app's profile, and toggling forced a
     * re-attach that happened to read the settled state.
     *
     * [rebuild] is false when the caller is going to attach itself right
     * afterwards, so the profile switch doesn't tear the chain down and
     * rebuild it only for the caller to do it again one line later.
     */
    private suspend fun syncProfileForActiveAppNow(rebuild: Boolean) {
        val target = resolveProfileKey()
        if (target == currentProfileKey) {
            _perAppProfileActive.value = target != currentDeviceKey
            return
        }
        switchToProfileNow(target, "app: ${_activePackageName.value ?: "unknown"}", rebuild)
    }

    /**
     * Persist the current settings under the OLD profile key, then load (or
     * fork) the profile for [newKey] and apply it live.
     *
     * Forking matters for per-app profiles: a brand-new one should start from
     * the device profile the user has already tuned, not from blank defaults
     * and not from the pre-per-device legacy keys that plain load() falls back
     * to (that fallback is right for a new output device, wrong here — see
     * SessionPreferences.hasProfile).
     *
     * Reuses rebuildDspTopology() — already proven to correctly re-attach the
     * DSP with every settings category (EQ gains, hiRes/dbfb/hdr/analogBass/
     * tubeWarmth topology, surround tilt, PEQ).
     */
    private fun switchToProfile(newKey: String, reason: String) {
        serviceScope.launch { switchToProfileNow(newKey, reason, rebuild = true) }
    }

    /**
     * Serialized by [profileSwitchMutex]. Two switches used to be able to
     * interleave — a route change and an app change arriving together each
     * launched their own coroutine, and the read-modify-write of "snapshot the
     * current settings, save them under the OLD key, load the NEW key, apply
     * it" is not atomic. The observed damage was one profile's settings being
     * written into another profile's key: switch A applied its state, switch B
     * (already in flight, holding a stale oldKey) then snapshotted A's
     * freshly-applied values and saved them over B's old profile. That is the
     * per-app profile corruption.
     *
     * Everything that reads or writes currentProfileKey now happens inside the
     * lock, and the state snapshot is taken inside it too, so a queued switch
     * always sees the settled result of the one before it.
     */
    private suspend fun switchToProfileNow(newKey: String, reason: String, rebuild: Boolean) {
        profileSwitchMutex.withLock {
            // Re-checked INSIDE the lock: while this call was queued, the
            // switch ahead of it may already have landed on the same key.
            if (newKey == currentProfileKey) return@withLock
            saveDebounceJob?.cancel()
            val oldKey = currentProfileKey
            val deviceKey = currentDeviceKey
            // Null only during the startup window before restoreSession() has
            // run — see sessionRestored. Writing here then would overwrite a
            // real profile with untouched defaults.
            val stateToSave = if (sessionRestored) buildSessionState() else null
            val newState = withContext(Dispatchers.IO) {
                stateToSave?.let { sessionPreferences.save(it, oldKey) }
                if (sessionPreferences.hasProfile(newKey)) {
                    sessionPreferences.load(newKey) ?: SessionState()
                } else {
                    // First time this profile is used — fork the device profile
                    // so the user starts from their existing tuning, then
                    // persist it immediately so the fork is a real profile from
                    // here on.
                    val forked = sessionPreferences.load(deviceKey) ?: SessionState()
                    sessionPreferences.save(forked, newKey)
                    forked
                }
            }
            withContext(Dispatchers.Main.immediate) {
                currentProfileKey = newKey
                _perAppProfileActive.value = newKey != deviceKey

                applyState(newState)
                if (rebuild && _masterEnabled.value) {
                    rebuildDspTopology()
                }
                Log.d(TAG, "Switched profile to $newKey ($reason, rebuild=$rebuild)")
            }
        }
    }

    // ── Per-app profile controls ──────────────────────────────────────────

    /**
     * Opt [packageName] in or out of having its own profile. Turning it ON
     * forks the current device profile (see switchToProfile); turning it OFF
     * leaves the stored per-app values in place but stops consulting them, so
     * re-enabling later restores rather than resets.
     */
    fun setPerAppProfileEnabled(packageName: String, enabled: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            sessionPreferences.setPerAppProfileEnabled(packageName, enabled)
            // The collector registered in onCreate picks the change up and
            // calls syncProfileForActiveApp(), which does the actual switch.
        }
    }

    private fun restoreSession() {
        serviceScope.launch(Dispatchers.IO) {
            val state = sessionPreferences.load(currentProfileKey)
            // Restore on main thread so flows update correctly
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (state != null) {
                    applyState(state)

                    // applyState already sets _surroundMode.value and _masterEnabled is NOT
                    // set by applyState (it's set here). setMasterPower → resolveAndAttachSession
                    // → attach() reads all current flow values including _surroundMode, so the
                    // surround mode is already included in the topology — no need to call
                    // setSurroundMode again (that would race with the async attach coroutine).
                    if (state.masterEnabled) setMasterPower(true)
                }
                // Set even when there was nothing to restore (fresh install):
                // "no saved state" is still a settled starting point, and
                // leaving this false would permanently block profile switches
                // from persisting anything — see sessionRestored.
                sessionRestored = true
            }
        }
    }

    /**
     * Apply every persisted setting from [state] to the in-memory flows and
     * engines, WITHOUT triggering any DSP rebuilds itself — callers
     * (restoreSession, switchToDeviceProfile) decide what to do afterwards.
     */
    private fun applyState(state: SessionState) {
        _preGainDb.value  = state.preGain
        _postGainDb.value = state.postGain
        _hiResUpscalerEnabled.value = state.hiResEnabled
        _dbfbMode.value = DbfbMode.entries
            .firstOrNull { it.name == state.dbfbMode } ?: DbfbMode.Off
        _hdrDynamicsEnabled.value = state.hdrEnabled
        _hdrMode.value = HdrMode.entries
            .firstOrNull { it.name == state.hdrMode } ?: HdrMode.Restoration
        _surroundMode.value = SurroundMode.entries
            .firstOrNull { it.name == state.surroundMode } ?: SurroundMode.Off
        for (i in state.bandGains.indices) manualBandGains[i] = state.bandGains[i]
        updateBandGains(manualBandGains.copyOf())
        // Restore Analog Bass — Front Stage's own bass shaping conflicts with
        // it, so force off on restore if a stale/imported profile has both.
        val analogBassEnabledResolved = state.analogBassEnabled && _surroundMode.value != SurroundMode.Front
        _analogBassEnabled.value = analogBassEnabledResolved
        _analogBassDrive.value = state.analogBassDrive
        _analogBassWarmth.value = state.analogBassWarmth
        _analogBassDrift.value = state.analogBassDrift
        _analogBassPultecBoost.value = state.analogBassPultecBoost
        _analogBassPultecCut.value = state.analogBassPultecCut
        _analogBassPultecFreqIndex.value = state.analogBassPultecFreqIndex
        analogBassEngine.enabled = analogBassEnabledResolved
        analogBassEngine.drive = state.analogBassDrive
        analogBassEngine.warmth = state.analogBassWarmth
        analogBassEngine.drift = state.analogBassDrift
        analogBassEngine.pultecBoost = state.analogBassPultecBoost
        analogBassEngine.pultecCut = state.analogBassPultecCut
        analogBassEngine.pultecFreqIndex = state.analogBassPultecFreqIndex
        // Restore Tube Warmth
        _tubeWarmthEnabled.value = state.tubeWarmthEnabled
        _tubeWarmthIntensity.value = state.tubeWarmthIntensity
        // Restore Mobile Bass — speaker-only feature. Force off on any non-speaker
        // device regardless of what was saved, so it can never leak into a BT/wired/
        // USB profile via legacy-key fallback or an imported backup from a speaker session.
        _mobileBassEnabled.value = state.mobileBassEnabled && currentDeviceKey == "speaker"
        _mobileBassIntensity.value = state.mobileBassIntensity
        // Restore Harmonic Exciter
        _harmonicExciterEnabled.value = state.harmonicExciterEnabled
        _harmonicExciterIntensity.value = state.harmonicExciterIntensity
        // Restore Parametric EQ
        digitalFilterEngine.enabled = state.peqEnabled
        _digitalFilterEnabled.value = state.peqEnabled
        deserializePeqBands(state.peqBands)
        // SBC Enhancement — only meaningful on BT profiles
        _sbcModeEnabled.value = state.sbcModeEnabled && currentDeviceKey.startsWith("bt")
        // Restore device type + quality tier — locked to General on the phone's
        // own speaker. That route already has its own dedicated scaling
        // (Mobile Bass is hardcoded to CompactSpeaker's extension regardless
        // of this picker — see mobileBassExtension()), and the picker's real
        // categories (IEM/OnEar/OverEar/HomeSpeaker) don't describe it.
        val restoredDeviceType = DeviceType.entries.firstOrNull { it.name == state.deviceType } ?: DeviceType.General
        _deviceType.value = if (currentDeviceKey == "speaker") DeviceType.General else restoredDeviceType
        _deviceQualityTier.value = state.deviceQualityTier
        // Enforced here too, not just in the setter and hasActiveDspFeatures —
        // an imported backup or a profile saved while a different DeviceType
        // was selected can carry crossfeedEnabled=true for a route/DeviceType
        // combination that no longer makes sense. See isHeadphoneRoute.
        _crossfeedEnabled.value = state.crossfeedEnabled && isHeadphoneRoute()
        _crossfeedStrength.value = state.crossfeedStrength

        // Restore Loudness Contour. The compensation array is recomputed from
        // the restored reference/amount plus the CURRENT system volume rather
        // than persisted — a stored curve would be stale the moment the user
        // changed volume while the service was dead.
        _preciseGainStaging.value = state.preciseGainStaging
        _selectedPresetName.value = state.selectedPresetName
        _activeDeviceProfileName.value = state.deviceProfileName
        applyDeviceProfileCurve()
        _loudnessEnabled.value = state.loudnessEnabled
        _loudnessAmount.value = state.loudnessAmount.coerceIn(0f, 1f)
        _loudnessReferencePhon.value = state.loudnessReferencePhon
            .coerceIn(LoudnessContour.MIN_PHON, LoudnessContour.MAX_PHON)
        loudnessCompensation = if (state.loudnessEnabled) {
            val phon = computeCurrentPhon()
            _loudnessCurrentPhon.value = phon
            LoudnessContour.compensationDb(
                _loudnessReferencePhon.value, phon, _loudnessAmount.value,
                loudnessDriverExtension()
            )
        } else FloatArray(EqBands.count)
        // Job lifecycle only — no DSP work, so this does not violate the
        // "applyState triggers no rebuilds" contract above. A profile that
        // turns Loudness on or off has to start or stop the volume poll, or
        // the correction stops tracking after any profile switch.
        updateLoudnessTracking()
    }

    /** Snapshot every current setting into a [SessionState] for persistence. */
    private fun buildSessionState(): SessionState = SessionState(
        masterEnabled = _masterEnabled.value,
        preGain       = _preGainDb.value,
        postGain      = _postGainDb.value,
        hiResEnabled  = _hiResUpscalerEnabled.value,
        dbfbMode      = _dbfbMode.value.name,
        hdrEnabled    = _hdrDynamicsEnabled.value,
        hdrMode       = _hdrMode.value.name,
        surroundMode  = _surroundMode.value.name,
        bandGains     = manualBandGains.copyOf(),
        analogBassEnabled       = _analogBassEnabled.value,
        analogBassDrive         = _analogBassDrive.value,
        analogBassWarmth        = _analogBassWarmth.value,
        analogBassDrift         = _analogBassDrift.value,
        analogBassPultecBoost   = _analogBassPultecBoost.value,
        analogBassPultecCut     = _analogBassPultecCut.value,
        analogBassPultecFreqIndex = _analogBassPultecFreqIndex.value,
        // Tube Warmth
        tubeWarmthEnabled   = _tubeWarmthEnabled.value,
        tubeWarmthIntensity = _tubeWarmthIntensity.value,
        // Mobile Bass
        mobileBassEnabled   = _mobileBassEnabled.value,
        mobileBassIntensity = _mobileBassIntensity.value,
        // Harmonic Exciter
        harmonicExciterEnabled   = _harmonicExciterEnabled.value,
        harmonicExciterIntensity = _harmonicExciterIntensity.value,
        // Parametric EQ
        peqEnabled = digitalFilterEngine.enabled,
        peqBands   = serializePeqBands(),
        // SBC Enhancement
        sbcModeEnabled = _sbcModeEnabled.value,
        deviceType = _deviceType.value.name,
        deviceQualityTier = _deviceQualityTier.value,
        // Crossfeed
        crossfeedEnabled = _crossfeedEnabled.value,
        crossfeedStrength = _crossfeedStrength.value,
        // Loudness Contour
        loudnessEnabled = _loudnessEnabled.value,
        loudnessAmount = _loudnessAmount.value,
        loudnessReferencePhon = _loudnessReferencePhon.value,
        preciseGainStaging = _preciseGainStaging.value,
        selectedPresetName = _selectedPresetName.value,
        deviceProfileName = _activeDeviceProfileName.value
    )

    private fun saveSession() {
        saveDebounceJob?.cancel()
        saveDebounceJob = serviceScope.launch(Dispatchers.IO) {
            delay(800)
            // currentProfileKey, not currentDeviceKey: when a per-app profile
            // is active every edit must land in that profile, otherwise
            // tweaking settings while YouTube plays would silently overwrite
            // the shared device profile instead.
            sessionPreferences.save(buildSessionState(), currentProfileKey)
        }
    }

    /** Snapshot the active device profile's settings for export — see BackupCodec. */
    fun exportSessionState(): SessionState = buildSessionState()

    /**
     * Apply an imported [SessionState] to the active device profile: updates
     * every in-memory flow/engine, rebuilds the live DSP topology if the
     * engine is running, and persists immediately (bypassing the normal
     * 800ms debounce so a backup is never lost to a quick app kill right
     * after import).
     */
    fun importSessionState(state: SessionState) {
        saveDebounceJob?.cancel()
        applyState(state)
        // Explicitly set master power to match the imported state — applyState does not
        // touch _masterEnabled, so without this the power toggle stays at its prior value.
        if (state.masterEnabled) {
            setMasterPower(true)
        } else {
            _masterEnabled.value = false
            detachSession()
            updateNotification()
        }
        serviceScope.launch(Dispatchers.IO) {
            sessionPreferences.save(buildSessionState(), currentProfileKey)
        }
    }

    // ── PEQ serialization helpers ────────────────────────────────────────
    // Format: "Type,freq,gain,q,enabled" per band, bands separated by "|".
    // PeqBandCodec keeps old 8-band profiles compatible with the 16-band bank.

    private fun serializePeqBands(): String =
        PeqBandCodec.encode(
            List(DigitalFilterEngine.MAX_BANDS) { digitalFilterEngine.getBand(it) }
        )

    private fun deserializePeqBands(serialized: String) {
        PeqBandCodec.decode(serialized).forEachIndexed { index, band ->
            digitalFilterEngine.setBand(index, band)
        }
    }

    private fun startForegroundService() {
        val channelId = "jadoo_dsp_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "JadOO DSP Engine", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        startForeground(1, buildNotification(channelId, active = false))
    }

    fun updateNotification() {
        val channelId = "jadoo_dsp_channel"
        val isActive = _masterEnabled.value
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(channelId, isActive))
    }

    private fun buildNotification(channelId: String, active: Boolean): Notification {
        val statusText = when {
            _audioSessionId.value != null && _masterEnabled.value && _dspBypassed.value ->
                "Engine on · Bypass (no effects active) · ${_activeAppLabel.value ?: _activePackageName.value ?: "Global session"}"
            _audioSessionId.value != null && _masterEnabled.value ->
                "DSP active · ${_activeAppLabel.value ?: _activePackageName.value ?: "Global session"}"
            _masterEnabled.value -> "Engine on · Waiting for playback"
            else -> "Engine off"
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("JadOO DSP")
            .setContentText(statusText)
            .setSmallIcon(com.jadoo.amp.R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerMediaSessionListener()

        // Sent by AudioEffectReceiver when a player broadcasts
        // ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION (the official mechanism
        // for "external audio effects" support) — gives us the package name for
        // the app that is opening/closing a playback session.
        //
        // IMPORTANT: We do NOT attach DynamicsProcessing to the per-app session ID
        // (even though it's provided here). DynamicsProcessing is always kept on
        // GLOBAL_AUDIO_SESSION_ID (0) which is the output mix. Attaching to a
        // per-app session causes two separate DynamicsProcessing effect chains to
        // coexist (one on 0, one on the app session), which doubles processing,
        // causes volume spikes on feature toggle, and glitches on many OEM ROMs.
        val sessionId = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1) ?: -1
        if (sessionId > 0) {
            when (intent?.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                    _activePackageName.value = pkg
                    _activeAppLabel.value = resolveAppLabel(pkg)
                    syncProfileForActiveApp()
                    // New player session = new audio stream on the global mix.
                    // Force re-attach to global session 0 so the DynamicsProcessing
                    // effect is fresh and bound to the current mix (not a stale one).
                    if (_masterEnabled.value) resolveAndAttachSession(pkg)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    val closingPkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                    if (_activePackageName.value == closingPkg) {
                        _activePackageName.value = null
                        _activeAppLabel.value = null
                        syncProfileForActiveApp()
                        // Re-attach to global session so the effect is refreshed
                        // for whoever plays next.
                        if (_masterEnabled.value) resolveAndAttachSession(null)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        peqApplyJob?.cancel()
        peqApplyJob = null
        unregisterMediaSessionListener()
        audioDeviceCallback?.let {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it)
        }
        audioDeviceCallback = null
        playbackReattachJob?.cancel()
        playbackReattachJob = null
        audioPlaybackCallback?.let {
            try {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .unregisterAudioPlaybackCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Playback callback unregister failed: ${e.message}")
            }
        }
        audioPlaybackCallback = null
        volumeObserver?.let {
            try { contentResolver.unregisterContentObserver(it) }
            catch (e: Exception) { Log.w(TAG, "Volume observer unregister failed: ${e.message}") }
        }
        volumeObserver = null
        audioDeviceHandlerThread?.quitSafely()
        audioDeviceHandlerThread = null
        audioDeviceHandler = null
        dspEngine.release()
        serviceScope.cancel()
    }

    private fun registerMediaSessionListener() {
        if (mediaSessionListenerRegistered) return
        val msm = mediaSessionManager ?: return

        try {
            msm.addOnActiveSessionsChangedListener(activeSessionsListener, null)
            mediaSessionListenerRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Media session listener unavailable; falling back to global session.", e)
            return
        }
        try {
            handleActiveSessionsChanged(msm.getActiveSessions(null))
        } catch (e: Exception) {
            Log.w(TAG, "getActiveSessions failed on this ROM; skipping initial session probe.", e)
        }
    }

    private fun unregisterMediaSessionListener() {
        if (!mediaSessionListenerRegistered) return
        val msm = mediaSessionManager ?: run {
            mediaSessionListenerRegistered = false
            return
        }
        msm.removeOnActiveSessionsChangedListener(activeSessionsListener)
        mediaSessionListenerRegistered = false
    }

    private fun handleActiveSessionsChanged(controllers: List<MediaController>) {
        val activeController = controllers.firstOrNull { controller ->
            controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        val pkg = activeController?.packageName
        _activePackageName.value = pkg
        _activeAppLabel.value = resolveAppLabel(pkg)
        // Playback moved to a different app — if either the old or the new one
        // has a per-app profile, swap profiles before touching the topology,
        // so the attach below is built from the right settings rather than
        // being immediately rebuilt with different ones.
        syncProfileForActiveApp()

        if (_masterEnabled.value && activeController != null) {
            resolveAndAttachSession(pkg)
        } else if (activeController == null) {
            _activePackageName.value = null
            _activeAppLabel.value = null
            syncProfileForActiveApp()
        }
    }

    /**
     * True if any user-facing DSP feature would actually change the audio.
     * When this is false (master on, but everything flat/off), the
     * DynamicsProcessing effect is released entirely for true bypass —
     * fixes "any effect enabled degrades quality no matter what" by ensuring
     * nothing is ever processed unless something is actually configured.
     */
    private fun hasActiveDspFeatures(): Boolean {
        if (_hiResUpscalerEnabled.value) return true
        if (_dbfbMode.value != DbfbMode.Off) return true
        if (_hdrDynamicsEnabled.value) return true
        if (_surroundMode.value != SurroundMode.Off) return true
        if (_analogBassEnabled.value) return true
        if (_tubeWarmthEnabled.value) return true
        if (_mobileBassEnabled.value) return true
        if (_harmonicExciterEnabled.value) return true
        // Loudness Contour only counts as active once it's actually producing
        // a non-zero tilt — enabled at full system volume is genuinely a
        // no-op (currentPhon == referencePhon), and claiming otherwise would
        // hold the DynamicsProcessing effect open for nothing.
        if (_loudnessEnabled.value && loudnessCompensation.any { it != 0f }) return true
        // Crossfeed writes a PreEQ tonal curve via writeCombinedBand (see
        // crossfeedShape), which needs the DP attached — if this returns
        // false, attachSession() releases the engine and the curve is never
        // written at all. Omitting it here meant Crossfeed silently did
        // nothing whenever it was the only feature on.
        if (_crossfeedEnabled.value && isHeadphoneRoute()) return true
        // SBC Enhancement writes a PreEQ pre-emphasis curve (up to +7dB at
        // 16kHz) via writeCombinedBand, which needs the DP attached. Same
        // failure as Crossfeed: enabled alone, it was inert. Gated on the
        // route for the same reason writeCombinedBand is — the curve is only
        // applied on Bluetooth, so off-BT it genuinely changes nothing.
        if (sbcConditioningActive()) return true
        // Device tuning — both the content-channel model curve and the
        // DeviceType class curve are PreEQ contributors written by
        // writeCombinedBand (see deviceCorrectionShape), so they need the DP
        // attached for exactly the same reason Crossfeed and SBC above do.
        // Missing here, selecting a device tuning with no other feature
        // enabled released the engine and the correction was never written —
        // audibly identical to not selecting one at all.
        if ((0 until EqBands.count).any { deviceCorrectionShape(it) != 0f }) return true
        if (digitalFilterEngine.hasActiveBand()) return true
        if (_preGainDb.value != 0f) return true
        if (_postGainDb.value != 0f) return true
        if (manualBandGains.any { it != 0f }) return true
        return false
    }

    private fun resolveAndAttachSession(packageName: String? = _activePackageName.value, onComplete: () -> Unit = {}) {
        serviceScope.launch {
            // Effects are always attached to the GLOBAL output mix session so
            // the DSP processes whatever is currently playing, regardless of
            // which app owns it. dumpsys is only used to look up a friendly
            // package/app name for the notification and dashboard.
            val sessionInfo = sessionController.getActiveAudioSessionId()
            val resolvedPackageName = sessionInfo?.packageName ?: packageName
            if (sessionInfo?.packageName != null) {
                _activePackageName.value = sessionInfo.packageName
                _activeAppLabel.value = resolveAppLabel(sessionInfo.packageName)
                // AWAITED, and with rebuild=false: the attachSession call below
                // is the rebuild. Previously this was fire-and-forget, so the
                // attach ran against the outgoing app's profile and the correct
                // one only arrived later on its own rebuild.
                syncProfileForActiveAppNow(rebuild = false)
            }
            // Force re-attach: when a new media session is active, the global mix
            // session under the hood has changed even though sessionId stays 0.
            // Without forceReattach, the stale DynamicsProcessing instance stays
            // bound to the old (dead) mix and silently stops processing audio.
            attachSession(GLOBAL_AUDIO_SESSION_ID, resolvedPackageName, forceReattach = true)
            onComplete()
        }
    }
    
    private fun attachGlobalSession(packageName: String? = _activePackageName.value) {
        attachSession(_audioSessionId.value ?: GLOBAL_AUDIO_SESSION_ID, packageName)
    }

    private fun attachSession(sessionId: Int, packageName: String? = _activePackageName.value, forceReattach: Boolean = false) {
        if (!hasActiveDspFeatures()) {
            // Nothing to process — stay (or become) fully bypassed.
            if (dspEngine.dynamicsProcessing != null) {
                dspEngine.release()
            }
            _audioSessionId.value = sessionId
            _activePackageName.value = packageName
            _dspBypassed.value = true
            updateNotification()
            return
        }
        _dspBypassed.value = false
        // forceReattach: used when the media session changed (new app/track) — even
        // though the session ID is still 0 (global mix), the underlying AudioFlinger
        // mix session has been replaced and the existing DynamicsProcessing effect is
        // now stale/detached. Without forcing a re-attach here, the engine stays
        // bound to the dead old session and silently stops processing audio.
        if (forceReattach || dspEngine.dynamicsProcessing == null || _audioSessionId.value != sessionId) {
            val attached = dspEngine.attach(
                sessionId = sessionId,
                initialGains = manualBandGains.copyOf(),
                initialPreGainDb = _preGainDb.value,
                initialPostGainDb = _postGainDb.value,
                hiResEnabled = _hiResUpscalerEnabled.value,
                dbfbMode = _dbfbMode.value,
                surroundMode = _surroundMode.value,
                hdrDynamicsEnabled = _hdrDynamicsEnabled.value,
                hdrMode = _hdrMode.value,
                analogBassEnabled = _analogBassEnabled.value,
                analogBassDrive = _analogBassDrive.value,
                analogBassWarmth = _analogBassWarmth.value,
                analogBassDrift = _analogBassDrift.value,
                analogBassPultecBoost = _analogBassPultecBoost.value,
                analogBassPultecCut = _analogBassPultecCut.value,
                analogBassPultecFreqIndex = _analogBassPultecFreqIndex.value,
                tubeWarmthEnabled = _tubeWarmthEnabled.value,
                tubeWarmthIntensity = _tubeWarmthIntensity.value,
                mobileBassEnabled = _mobileBassEnabled.value,
                mobileBassIntensity = _mobileBassIntensity.value,
                harmonicExciterEnabled = _harmonicExciterEnabled.value,
                harmonicExciterIntensity = _harmonicExciterIntensity.value,
                bassExtension = bassExtension(),
                trebleExtension = trebleExtension(),
                mobileBassExtension = mobileBassExtension(),
                preEqBassPeakDb = preEqBassPeakDb(),
                preEqTreblePeakDb = preEqTreblePeakDb(),
                preciseGainStaging = _preciseGainStaging.value,
                hdrRestorationThreshold = remoteTuning.hdrRestorationThreshold,
                hdrRestorationKnee = remoteTuning.hdrRestorationKnee,
                hdrRestorationExpanderRatio = remoteTuning.hdrRestorationExpanderRatio,
                sbcConditioningEnabled = sbcConditioningActive(),
                sbcCrestCutoffHz = SbcEngine.crestBandCutoffHz(digitalFilterEngine.sampleRateHz)
            )
            if (attached) {
                _audioSessionId.value = sessionId
                _activePackageName.value = packageName
                updateNotification()
                publishGainStagingReadout()
                applyDigitalFilterToPreEq()
            } else {
                // attach() failed for the NEW session (e.g. a malformed band
                // config from a feature combination, or a device band-count
                // limit) — any DynamicsProcessing instance that survived is
                // still bound to the OLD session, not this one, so the new
                // track/app would otherwise play with NO DSP at all while
                // the UI still shows every feature as on. Fall back to a
                // plain transparent passthrough on the new session so
                // processing keeps working (even if every custom feature is
                // briefly inert) instead of going silent until the user
                // happens to toggle something.
                Log.e(TAG, "attach() failed for session=$sessionId — falling back to a transparent passthrough")
                dspEngine.release()
                if (dspEngine.attach(sessionId = sessionId, initialGains = manualBandGains.copyOf())) {
                    _audioSessionId.value = sessionId
                    _activePackageName.value = packageName
                    updateNotification()
                    // The fallback attach writes ONLY the raw manual EQ gains.
                    // Everything else that lives in the PreEQ sum — parametric
                    // EQ, HDR's air shelf, Tube Warmth's tonal shape, SBC
                    // pre-emphasis, Loudness Contour, Surround's tilt — is
                    // applied by applyAllBands, which was never called here.
                    // Without it the fallback silently dropped most of the
                    // signal chain until some unrelated setting changed.
                    applyDigitalFilterToPreEq()
                    applyAllBands(manualBandGains.copyOf())
                } else {
                    _dspBypassed.value = true
                }
            }
        }
    }

    private fun detachSession() {
        dspEngine.release()
        _audioSessionId.value = null
        _dspBypassed.value = true
    }

    /**
     * Called when the user opens JadOO via a music player's "External EQ" /
     * "Audio effects" picker (ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL). The
     * player hands us the exact session ID for its own playback — attach to
     * it directly (skipping dumpsys/global-session resolution) and turn the
     * master switch on if it wasn't already.
     */
    fun attachExternalSession(sessionId: Int, packageName: String?) {
        _masterEnabled.value = true
        if (packageName != null) {
            _activePackageName.value = packageName
            _activeAppLabel.value = resolveAppLabel(packageName)
        }
        // Always attach to global session 0 — never to a per-app session.
        resolveAndAttachSession(packageName)
        updateNotification()
        saveSession()
    }

    fun setMasterPower(enabled: Boolean) {
        _masterEnabled.value = enabled
        if (enabled) {
            // Re-derive the loudness curve from the CURRENT system volume
            // before attaching: the user may have changed volume while the
            // engine was off, and the stored curve would be stale. Done first
            // so hasActiveDspFeatures() and the attach both see the real one.
            if (_loudnessEnabled.value) {
                val phon = computeCurrentPhon()
                _loudnessCurrentPhon.value = phon
                loudnessCompensation = LoudnessContour.compensationDb(
                    _loudnessReferencePhon.value, phon, _loudnessAmount.value,
                    loudnessDriverExtension()
                )
            }
            resolveAndAttachSession()
        } else {
            updateBandGains(manualBandGains.copyOf())
            detachSession()
        }
        updateLoudnessTracking()
        updateNotification()
        saveSession()
    }

    fun setManualBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until EqBands.count) return

        val clamped = gainDb.coerceIn(-15f, 15f)
        manualBandGains[bandIndex] = clamped
        updateBandGains(manualBandGains.copyOf())  // show raw gains without hi-res

        if (_masterEnabled.value) {
            val wasAttached = dspEngine.dynamicsProcessing != null
            attachGlobalSession()
            // If the DSP was already attached, take the fast single-band path.
            // If it was in bypass (wasAttached == false), attachGlobalSession() just
            // called attachSession() which already invoked applyDigitalFilterToPreEq()
            // → applyAllBands() on success — all 15 combined bands are already written,
            // so no extra call is needed here.
            if (wasAttached) {
                applySingleBand(bandIndex, clamped)
            }
            // Graphic EQ boosts are credited in the gain budget (see
            // preEqBassPeakDb/preEqTreblePeakDb) — without this the limiter
            // threshold and the Settings readout both stayed stale at
            // whatever they were before this band moved.
            refreshHeadroom()
        }
        saveSession()
    }

    fun updateBandGains(gains: FloatArray) {
        _bandGains.value = FloatArray(EqBands.count) { index ->
            gains.getOrNull(index)?.coerceIn(-15f, 15f) ?: 0f
        }
    }

    fun applyPreset(gains: FloatArray) {
        for (index in 0 until EqBands.count) {
            manualBandGains[index] = gains.getOrNull(index)?.coerceIn(-15f, 15f) ?: 0f
        }
        updateBandGains(manualBandGains.copyOf())
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyAllBands(manualBandGains)
            refreshHeadroom()
        }
        saveSession()
    }

    fun setPreGain(gainDb: Float) {
        _preGainDb.value = gainDb.coerceIn(-12f, 12f)
        if (_masterEnabled.value) {
            attachGlobalSession()
            dspEngine.setPreGain(_preGainDb.value)
        }
        saveSession()
    }

    fun setPostGain(gainDb: Float) {
        _postGainDb.value = gainDb.coerceIn(-12f, 12f)
        if (_masterEnabled.value) {
            attachGlobalSession()
            dspEngine.setPostGain(_postGainDb.value)
        }
        saveSession()
    }

    fun setHiResUpscalerEnabled(enabled: Boolean) {
        _hiResUpscalerEnabled.value = enabled
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setDbfbMode(mode: DbfbMode) {
        _dbfbMode.value = mode
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setHdrDynamicsEnabled(enabled: Boolean) {
        val wasEnabled = _hdrDynamicsEnabled.value
        _hdrDynamicsEnabled.value = enabled
        if (_masterEnabled.value) {
            rebuildDspTopology()
            if (enabled && !wasEnabled) {
                // On a fresh attach, the new broadband HDR expander's envelope
                // detector starts cold and can make the air bands read as thin/
                // tinny for the first moment. Re-asserting the same PreEQ gains
                // shortly after gives it time to settle — mirroring the manual
                // "disable then re-enable" workaround that already fixes this.
                val token = ++hdrSettleToken
                val gainsSnapshot = manualBandGains.copyOf()
                serviceScope.launch {
                    kotlinx.coroutines.delay(200)
                    if (_hdrDynamicsEnabled.value && token == hdrSettleToken) applyAllBands(gainsSnapshot)
                }
            }
        }
        saveSession()
    }

    fun setHdrMode(mode: HdrMode) {
        _hdrMode.value = mode
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setSurroundMode(mode: SurroundMode) {
        val oldMode = _surroundMode.value
        _surroundMode.value = mode
        // Front Stage's own bass shaping conflicts with Analog Bass; enforced
        // here (not just in the UI callback) so every entry point that can
        // set surround mode — restore, device-profile switch, backup import —
        // converges on the same rule.
        if (mode == SurroundMode.Front && _analogBassEnabled.value) {
            _analogBassEnabled.value = false
            analogBassEngine.enabled = false
        }
        if (oldMode != SurroundMode.Off && mode == SurroundMode.Off) {
            applySurroundShaping()
            // Re-evaluate: if surround was the only active feature, this
            // releases the engine entirely for true bypass.
            if (_masterEnabled.value) attachGlobalSession()
        } else if (mode != SurroundMode.Off) {
            val sessionId = _audioSessionId.value
            if (sessionId != null) {
                // Ensures the engine is attached (it may currently be
                // bypassed if surround is the first feature being enabled).
                if (_masterEnabled.value) attachGlobalSession()
                applySurroundShaping()
            } else if (_masterEnabled.value) {
                resolveAndAttachSession()
            }
        }
        // Surround mode's own bass/treble "smile" was never reflected in the
        // limiter's gain budget — keep it in sync live so e.g. Ultra Wide
        // stacked with Mobile Bass/DBFB/Analog Bass doesn't push past what
        // the limiter was set up to expect (see calculateHeadroomOffset).
        if (_masterEnabled.value) refreshHeadroom()
        saveSession()
    }

    // ── Analog Bass Controls ─────────────────────────────────────────

    /** Front Stage's own bass shaping conflicts with Analog Bass; never allow both at once. */
    fun setAnalogBassEnabled(enabled: Boolean) {
        val resolved = enabled && _surroundMode.value != SurroundMode.Front
        _analogBassEnabled.value = resolved
        analogBassEngine.enabled = resolved
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setAnalogBassDrive(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassDrive.value = clamped
        analogBassEngine.drive = clamped
        if (_analogBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateAnalogBassMbc(clamped, _analogBassWarmth.value, _analogBassDrift.value, bassExtension())
        }
        saveSession()
    }

    fun setAnalogBassWarmth(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassWarmth.value = clamped
        analogBassEngine.warmth = clamped
        if (_analogBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateAnalogBassMbc(_analogBassDrive.value, clamped, _analogBassDrift.value, bassExtension())
            // Mobile Bass owns the PostEQ layout when both features are on — skip Analog Bass
            // PostEQ update to avoid writing Pultec frequencies onto Mobile Bass's band slots.
            if (!_mobileBassEnabled.value) {
                dspEngine.updateAnalogBassPostEq(_analogBassPultecBoost.value, _analogBassPultecCut.value, _analogBassPultecFreqIndex.value, clamped, bassExtension())
            }
        }
        saveSession()
    }

    fun setAnalogBassDrift(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassDrift.value = clamped
        analogBassEngine.drift = clamped
        if (_analogBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateAnalogBassMbc(_analogBassDrive.value, _analogBassWarmth.value, clamped, bassExtension())
        }
        saveSession()
    }

    fun setAnalogBassPultecBoost(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassPultecBoost.value = clamped
        analogBassEngine.pultecBoost = clamped
        if (_analogBassEnabled.value && _masterEnabled.value && !_mobileBassEnabled.value) {
            dspEngine.updateAnalogBassPostEq(clamped, _analogBassPultecCut.value, _analogBassPultecFreqIndex.value, _analogBassWarmth.value, bassExtension())
        }
        saveSession()
    }

    fun setAnalogBassPultecCut(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassPultecCut.value = clamped
        analogBassEngine.pultecCut = clamped
        if (_analogBassEnabled.value && _masterEnabled.value && !_mobileBassEnabled.value) {
            dspEngine.updateAnalogBassPostEq(_analogBassPultecBoost.value, clamped, _analogBassPultecFreqIndex.value, _analogBassWarmth.value, bassExtension())
        }
        saveSession()
    }

    fun setAnalogBassPultecFreqIndex(index: Int) {
        val clamped = index.coerceIn(0, AnalogBassEngine.PULTEC_FREQUENCIES.size - 1)
        _analogBassPultecFreqIndex.value = clamped
        analogBassEngine.pultecFreqIndex = clamped
        if (_analogBassEnabled.value && _masterEnabled.value && !_mobileBassEnabled.value) {
            dspEngine.updateAnalogBassPostEq(_analogBassPultecBoost.value, _analogBassPultecCut.value, clamped, _analogBassWarmth.value, bassExtension())
        }
        saveSession()
    }

    // ── Tube Warmth Controls ──────────────────────────────────────────

    fun setTubeWarmthEnabled(enabled: Boolean) {
        _tubeWarmthEnabled.value = enabled
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setTubeWarmthIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _tubeWarmthIntensity.value = clamped
        if (_tubeWarmthEnabled.value && _masterEnabled.value) {
            dspEngine.updateTubeWarmthIntensity(clamped)
            // Tube Warmth's broadband contribution to the gain budget changes
            // with intensity — keep the limiter's headroom in sync live.
            refreshHeadroom()
            applyAllBands(manualBandGains.copyOf())
        }
        saveSession()
    }

    // ── Mobile Bass Controls ──────────────────────────────────────────

    fun setMobileBassEnabled(enabled: Boolean) {
        // Speaker-only, enforced here rather than only in the UI — so every
        // entry point (restore, profile switch, backup import, external
        // caller) converges on the same rule, matching how Analog Bass vs
        // Surround Front Stage is handled in setAnalogBassEnabled.
        _mobileBassEnabled.value = enabled && currentDeviceKey == "speaker"
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setMobileBassIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _mobileBassIntensity.value = clamped
        if (_mobileBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateMobileBassIntensity(clamped, _analogBassEnabled.value, _dbfbMode.value)
            refreshHeadroom()
        }
        saveSession()
    }

    // ── Harmonic Exciter Controls ──────────────────────────────────────

    fun setHarmonicExciterEnabled(enabled: Boolean) {
        _harmonicExciterEnabled.value = enabled
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setHarmonicExciterIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _harmonicExciterIntensity.value = clamped
        if (_harmonicExciterEnabled.value && _masterEnabled.value) {
            dspEngine.updateHarmonicExciterIntensity(
                clamped,
                _analogBassEnabled.value,
                _dbfbMode.value,
                _mobileBassEnabled.value,
                _hdrDynamicsEnabled.value,
                trebleExtension()
            )
            refreshHeadroom()
        }
        saveSession()
    }

    // ── Device Type Controls ───────────────────────────────────────────

    /**
     * Manually set the output device's physical type (see DeviceType). Saved per output
     * device profile like every other setting, so a Bluetooth speaker and a pair of
     * Bluetooth earbuds keep separate choices. A full topology rebuild is used here
     * since this is a rare settings change, not something dragged in real time.
     */
    fun setDeviceType(type: DeviceType) {
        // Locked to General on the phone's own speaker — see applyState.
        if (currentDeviceKey == "speaker" && type != DeviceType.General) return
        _deviceType.value = type
        // No user-facing quality slider anymore (it fought the sound too
        // hard at its 0.5 midpoint default, cutting HiRes/Harmonic Exciter
        // treble roughly in half on IEM/headphone profiles and making them
        // sound noticeably duller than General). Fix the tier high instead
        // of exposing it — most real drivers, even budget ones, reproduce
        // treble far better than bass, so this stays close to the
        // uncompromised General sound while still respecting each type's
        // bass ceiling.
        _deviceQualityTier.value = 0.85f
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    // ── Crossfeed Controls ───────────────────────────────────────────────

    /**
     * True only when the output is genuinely a pair of headphones/IEMs near
     * the ears — the one situation Crossfeed's tonal curve (see
     * crossfeedShape) is meaningful for. It exists to soften the "hard L/R
     * panning, everything inside your head" feeling, which is only a problem
     * when each ear hears exactly one channel with zero natural crosstalk.
     * Any speaker already delivers both channels to both ears acoustically,
     * so applying the curve there isn't "less effective" — it's warming up
     * and rolling off the treble of a signal that doesn't have the problem
     * this curve is meant to solve.
     *
     * TWO things gate this, not one:
     *  - route: the phone's own built-in speaker is never headphone-like.
     *  - [DeviceType]: a Bluetooth or wired CONNECTION can still be an
     *    external SPEAKER (CompactSpeaker/HomeSpeaker) rather than
     *    headphones. Route alone can't distinguish a BT speaker from BT
     *    headphones — only the user's DeviceType choice can. The dashboard
     *    already hides the Crossfeed card for those two types; this makes
     *    the engine agree, so a state left over from before a DeviceType
     *    change (or an imported backup) can't leave the curve quietly
     *    applied somewhere it never should be.
     */
    private fun isHeadphoneRoute(): Boolean {
        if (currentDeviceKey == "speaker") return false
        return when (_deviceType.value) {
            DeviceType.CompactSpeaker, DeviceType.HomeSpeaker -> false
            else -> true
        }
    }

    fun setCrossfeedEnabled(enabled: Boolean) {
        _crossfeedEnabled.value = enabled
        if (_masterEnabled.value) {
            // Just another PreEQ contributor now (see crossfeedShape) — the
            // same cheap path every other toggle already uses. No dedicated
            // effect to create/tear down, so no dropout risk either; this
            // used to reconfigure a Virtualizer in place specifically to
            // avoid a topology-rebuild dropout, which is moot now that
            // there's no separate effect at all.
            attachGlobalSession()
            applyAllBands(manualBandGains.copyOf())
            refreshHeadroom()
        }
        saveSession()
    }

    fun setCrossfeedStrength(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _crossfeedStrength.value = clamped
        if (_crossfeedEnabled.value && _masterEnabled.value) {
            applyAllBands(manualBandGains.copyOf())
            refreshHeadroom()
        }
        saveSession()
    }

    /**
     * The slider is dragged in real time (unlike setDeviceType above), so a
     * full topology rebuild per tick would click/dropout on every step —
     * instead this re-patches only the postGain/gain values already
     * affected by bassExtension()/trebleExtension() via each feature's
     * existing live-update helper, then refreshes the headroom credit.
     */
    fun setDeviceQualityTier(value: Float) {
        _deviceQualityTier.value = value.coerceIn(0f, 1f)
        if (_masterEnabled.value) {
            val bass = bassExtension()
            val treble = trebleExtension()
            if (_dbfbMode.value != DbfbMode.Off) {
                dspEngine.updateDbfbGain(_dbfbMode.value, _analogBassEnabled.value, bass)
            }
            if (_analogBassEnabled.value) {
                dspEngine.updateAnalogBassMbc(_analogBassDrive.value, _analogBassWarmth.value, _analogBassDrift.value, bass)
                // Mobile Bass owns the PostEQ layout when both are on — same
                // guard every Analog Bass slider setter already uses. Without
                // it this path wrote Pultec cutoffs onto Mobile Bass's slots
                // and then overwrote them again two lines later, once per
                // frame of a quality-tier drag.
                if (!_mobileBassEnabled.value) {
                    dspEngine.updateAnalogBassPostEq(
                        _analogBassPultecBoost.value, _analogBassPultecCut.value,
                        _analogBassPultecFreqIndex.value, _analogBassWarmth.value, bass
                    )
                }
            }
            if (_mobileBassEnabled.value) {
                dspEngine.updateMobileBassIntensity(_mobileBassIntensity.value, _analogBassEnabled.value, _dbfbMode.value)
            }
            if (_harmonicExciterEnabled.value) {
                dspEngine.updateHarmonicExciterIntensity(
                    _harmonicExciterIntensity.value, _analogBassEnabled.value, _dbfbMode.value,
                    _mobileBassEnabled.value, _hdrDynamicsEnabled.value, treble
                )
            }
            if (_hiResUpscalerEnabled.value) {
                dspEngine.updateHiResGain(
                    treble, _analogBassEnabled.value, _dbfbMode.value, _mobileBassEnabled.value,
                    _hdrDynamicsEnabled.value, _harmonicExciterEnabled.value
                )
            }
            refreshHeadroom()
        }
        saveSession()
    }

    // ── Digital Filter Controls ───────────────────────────────────────────

    fun setDigitalFilterEnabled(enabled: Boolean) {
        digitalFilterEngine.enabled = enabled
        _digitalFilterEnabled.value = enabled
        scheduleDigitalFilterApply()
        saveSession()
    }

    /**
     * Coalesces dense slider/graph gestures into one frame-paced DSP update.
     * This prevents repeated full 15-band writes from piling up while retaining
     * immediate UI state and persistence for every edit.
     */
    private fun scheduleDigitalFilterApply() {
        peqApplyJob?.cancel()
        peqApplyJob = serviceScope.launch {
            delay(16L)
            if (!_masterEnabled.value) return@launch
            val wasAttached = dspEngine.dynamicsProcessing != null
            attachGlobalSession()
            if (wasAttached && dspEngine.dynamicsProcessing != null) applyDigitalFilterToPreEq()
            refreshHeadroom()
        }
    }

    /** Applies a single PEQ band mutation, then frame-paces the DSP write and persistence. */
    private inline fun applyDigitalFilterChange(mutate: () -> Unit) {
        mutate()
        scheduleDigitalFilterApply()
        saveSession()
    }

    fun setDigitalFilterBandType(index: Int, type: DigitalFilterEngine.FilterType) =
        applyDigitalFilterChange { digitalFilterEngine.setBandType(index, type) }

    fun setDigitalFilterBandFrequency(index: Int, frequency: Float) =
        applyDigitalFilterChange { digitalFilterEngine.setBandFrequency(index, frequency) }

    fun setDigitalFilterBandGain(index: Int, gain: Float) =
        applyDigitalFilterChange { digitalFilterEngine.setBandGain(index, gain) }

    fun setDigitalFilterBandQ(index: Int, q: Float) =
        applyDigitalFilterChange { digitalFilterEngine.setBandQ(index, q) }

    fun setDigitalFilterBandEnabled(index: Int, enabled: Boolean) =
        applyDigitalFilterChange { digitalFilterEngine.setBandEnabled(index, enabled) }

    /** Reset the complete 16-band bank in one batch. */
    fun resetDigitalFilterBands() {
        for (i in 0 until DigitalFilterEngine.MAX_BANDS) {
            digitalFilterEngine.setBand(i, DigitalFilterEngine.defaultBand(i))
        }
        scheduleDigitalFilterApply()
        saveSession()
    }

    fun setSbcModeEnabled(enabled: Boolean) {
        _sbcModeEnabled.value = enabled
        if (_masterEnabled.value) {
            // attachGlobalSession() first: when SBC is the FIRST active
            // feature the engine is still released for true bypass, so
            // rebuildDspTopology() would bail on a null session and the
            // conditioning would silently never land. It also tears the
            // engine back down when SBC was the LAST thing on.
            attachGlobalSession()
            // Then a full topology rebuild, which attachGlobalSession() does
            // NOT do on its own once a DynamicsProcessing already exists for
            // this session. SBC conditioning adds an MBC band (see
            // SbcEngine's crest control), and band count is fixed at
            // construction — without this, toggling SBC on top of a live
            // engine would write the new PreEQ curve while the dynamics half
            // of the feature stayed missing.
            rebuildDspTopology()
            applyAllBands(manualBandGains.copyOf())
        }
        saveSession()
    }

    // ── Custom (imported) profile management ─────────────────────────────

    val customProfileNames: Flow<List<String>> get() = sessionPreferences.customProfileNames

    suspend fun profileExists(name: String): Boolean = sessionPreferences.profileExists(name)

    fun saveAsCustomProfile(name: String, state: SessionState) {
        serviceScope.launch(Dispatchers.IO) {
            sessionPreferences.saveAsCustomProfile(name, state)
        }
    }

    fun loadCustomProfile(name: String) {
        serviceScope.launch(Dispatchers.IO) {
            val state = sessionPreferences.loadCustomProfile(name) ?: return@launch
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                importSessionState(state)
            }
        }
    }

    fun deleteCustomProfile(name: String) {
        serviceScope.launch(Dispatchers.IO) {
            sessionPreferences.deleteCustomProfile(name)
        }
    }

    /**
     * Apply the parametric EQ (DigitalFilterEngine biquad response) to the DynamicsProcessing
     * PreEQ bands. Since JadOO intercepts audio at the OS session level (DynamicsProcessing API),
     * there is no raw PCM access — processSample() is never called. Instead, we evaluate the
     * combined biquad frequency response at each of the 15 PreEQ band center frequencies and
     * apply those as PreEQ gains, combined with the graphic EQ's manual band gains.
     */
    private fun applyDigitalFilterToPreEq() {
        // applyAllBands already evaluates the parametric EQ response, adds
        // HDR air boost, and applies the active surround mode's centered
        // tonal shape (see surroundBandProfile) — calling it here keeps all
        // of that from being wiped out by setPreEqBandAllChannelsTo inside DspEngine.
        applyAllBands(manualBandGains.copyOf())
        Log.d(TAG, "PEQ applied via applyAllBands (preserves surround shape + HDR air)")
    }

    /**
     * Re-applies the current EQ gains together with the active surround
     * mode's centered tonal shape (see [surroundBandProfile]). Always
     * identical on both channels — no L/R differential.
     */
    private fun applySurroundShaping() {
        applyAllBands(manualBandGains.copyOf())
    }

    private fun rebuildDspTopology() {
        val sessionId = _audioSessionId.value
        if (sessionId == null) {
            Log.d(TAG, "rebuildDspTopology skipped — no active session")
            return
        }

        if (!hasActiveDspFeatures()) {
            // The toggle that triggered this rebuild was the last active
            // feature being turned off — release for true bypass instead of
            // re-attaching a transparent-but-still-processing topology.
            dspEngine.release()
            _dspBypassed.value = true
            return
        }
        _dspBypassed.value = false

        val currentGains = manualBandGains.copyOf()
        val attached = dspEngine.attach(
            sessionId = sessionId,
            initialGains = currentGains,
            initialPreGainDb = _preGainDb.value,
            initialPostGainDb = _postGainDb.value,
            hiResEnabled = _hiResUpscalerEnabled.value,
            dbfbMode = _dbfbMode.value,
            surroundMode = _surroundMode.value,
            hdrDynamicsEnabled = _hdrDynamicsEnabled.value,
            hdrMode = _hdrMode.value,
            analogBassEnabled = _analogBassEnabled.value,
            analogBassDrive = _analogBassDrive.value,
            analogBassWarmth = _analogBassWarmth.value,
            analogBassDrift = _analogBassDrift.value,
            analogBassPultecBoost = _analogBassPultecBoost.value,
            analogBassPultecCut = _analogBassPultecCut.value,
            analogBassPultecFreqIndex = _analogBassPultecFreqIndex.value,
            tubeWarmthEnabled = _tubeWarmthEnabled.value,
            tubeWarmthIntensity = _tubeWarmthIntensity.value,
            mobileBassEnabled = _mobileBassEnabled.value,
            mobileBassIntensity = _mobileBassIntensity.value,
            harmonicExciterEnabled = _harmonicExciterEnabled.value,
            harmonicExciterIntensity = _harmonicExciterIntensity.value,
            bassExtension = bassExtension(),
            trebleExtension = trebleExtension(),
            mobileBassExtension = mobileBassExtension(),
            preEqBassPeakDb = preEqBassPeakDb(),
            preEqTreblePeakDb = preEqTreblePeakDb(),
            hdrRestorationThreshold = remoteTuning.hdrRestorationThreshold,
            hdrRestorationKnee = remoteTuning.hdrRestorationKnee,
            hdrRestorationExpanderRatio = remoteTuning.hdrRestorationExpanderRatio,
            sbcConditioningEnabled = sbcConditioningActive(),
            sbcCrestCutoffHz = SbcEngine.crestBandCutoffHz(digitalFilterEngine.sampleRateHz)
        )
        if (attached) {
            applyAllBands(currentGains)
        }
    }

    private fun resolveAppLabel(pkg: String?): String? {
        if (pkg == null) return null
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) {
            // If we can't resolve the app label, don't show the package name
            // Return null so it falls back to "Session X" instead
            null
        }
    }

    /**
     * Per-band tonal "shape" for a surround mode, in dB, applied EQUALLY to
     * both channels — the centered loudness anchor for that band. Any
     * left/right width comes separately from [surroundChannelDifferential],
     * which is layered on top of this in [applyAllBands].
     *
     * Earlier versions tried to create "width" purely via a per-band L/R gain
     * difference applied across a whole region (e.g. "everything above
     * 1.5kHz is N dB louder in the left channel"). Above ~1.5kHz, ILD
     * (inter-aural level difference) is the brain's dominant localization
     * cue, so a *consistent* difference across a whole region reads as that
     * region being "thrown" toward one ear. The fix isn't to remove ILD
     * differences — it's to never let one channel lead consistently across a
     * contiguous region (see [surroundChannelDifferential]).
     *
     * This function is the centered "shape" each mode starts from: a bass +
     * treble "smile" curve — the gain ramps up toward the extremes (25Hz and
     * 16kHz) and tapers toward the center, the same kind of curve real
     * on-device "surround"/"3D"/spatial modes apply to stereo content (true
     * binaural/HRTF rendering needs raw PCM access this
     * DynamicsProcessing-based engine doesn't have).
     *
     * The first version of this redesign used a 0.6-2.0dB shape, which
     * turned out to be too subtle to notice — a clearly audible "wider,
     * bigger" sound needs a real loudness-contour-style curve, so the
     * extremes now go up to 4-6dB depending on mode:
     *  - Traditional: a broad smile peaking at +4dB at 25Hz/16kHz.
     *  - Front Stage: a smaller +2.5dB smile plus its forward
     *    vocal-presence lift (1k/1.6k/2.5k) — dialogue stays forward and
     *    centered. No left/right differential in this mode.
     *  - Wide: the biggest smile, peaking at +6dB at 25Hz/16kHz, for an
     *    enveloping, "bigger" sound.
     * In every mode, bands 4-10 (160Hz-2.5kHz, vocals/mids) get ZERO extra
     * gain — vocal/mid quality is never touched.
     */
    private fun surroundBandProfile(mode: SurroundMode, index: Int): Float = when (mode) {
        SurroundMode.Off -> 0f
        // The bass leg's 25Hz/40Hz contribution was pulled back from
        // 4.0/3.0 (Traditional) and 6.0/4.5 (Wide) — these are
        // DynamicsProcessing.Eq bands, which are sequential/contiguous, not
        // isolated peaking filters, so boosting 25/40/63/100Hz together
        // with a smoothly decreasing ramp produces one continuous broad
        // boost from ~0-200Hz rather than four separate "bumps". With no
        // dynamics control in PreEQ (it's pure static gain), that broad,
        // undifferentiated lift blurs sub-bass rumble into the punchier
        // 60-100Hz note content — exactly what reads as "smeared" bass.
        // Narrowing the low end so 63/100Hz (where actual bass note
        // definition lives) keep their lift while 25/40Hz taper off faster
        // keeps the "bigger" feel without washing out note definition.
        // Treble leg, vocal lift, and stereo differential are unchanged.
        SurroundMode.Traditional -> when (index) {
            0  ->  1.5f  // 25 Hz
            1  ->  2.2f  // 40 Hz
            2  ->  2.0f  // 63 Hz — bass definition
            3  ->  1.0f  // 100 Hz
            4  ->  0.3f  // 160 Hz — bridge; smooths 100Hz→mid step, fills 120-130Hz dip
            7  -> -0.3f  // 630 Hz — mild mud cut; -0.5f recessed mids enough to unbalance bass weight with Analog Bass active
            14 ->  3.6f  // 16 kHz — was 4.0f (sibilant) then 3.2f (too little air, bass felt heavy); 3.6f balances both
            13 ->  2.9f  // 10 kHz — slight taper from 3.0f
            12 ->  2.0f  // 6.3 kHz
            11 ->  1.0f  // 4 kHz
            else -> 0f
        }
        // Front Stage: tonal crossfeed approximation — same technique as PowerAmp EQ's
        // crossfeed mode. Real crossfeed (Bauer/Meier) mixes a low-passed, attenuated
        // copy of each channel into the opposite ear. Since we can't do cross-channel
        // routing via AudioEffect API, we simulate the NET tonal result of that process:
        //
        // - The crosstalk bleed is low-passed (~700Hz), so it adds energy to the
        //   low-mid region on both channels → slight lift 100–400Hz.
        // - The stereo DIFFERENCE signal (L−R) is attenuated above 700Hz by the
        //   crossfeed matrix. Net result on a summed signal: highs appear slightly
        //   reduced, image narrows and moves forward → gentle cut above 4kHz.
        // - Sub-bass stays neutral (crossfeed doesn't touch it).
        // - Vocal presence (1–2.5kHz) pinned centered and forward — the phantom center
        //   collapses to the front in a real crossfeed setup.
        //
        // The net curve: low-mid warmth + forward vocal lock + soft high rolloff.
        // This is what crossfeed *sounds like* without actual channel mixing.
        SurroundMode.Front -> when (index) {
            3  ->  1.5f  // 100 Hz: crosstalk low-mid energy addition
            4  ->  1.8f  // 160 Hz: peak of crossfeed low-mid bloom
            5  ->  1.2f  // 250 Hz: taper
            6  ->  0.6f  // 400 Hz: taper end
            8  ->  1.0f  // 1 kHz: phantom center forward lock
            9  ->  1.2f  // 1.6 kHz: vocal presence, centered
            10 ->  0.8f  // 2.5 kHz: taper
            11 -> -0.8f  // 4 kHz: high-freq difference signal attenuation begins
            12 -> -1.5f  // 6.3 kHz: crossfeed high rolloff
            13 -> -2.0f  // 10 kHz: crossfeed high rolloff
            14 -> -2.5f  // 16 kHz: maximum rolloff — stereo difference fully collapsed
            else -> 0f
        }
        SurroundMode.Wide -> when (index) {
            0  ->  2.0f  // 25 Hz
            1  ->  3.2f  // 40 Hz
            2  ->  3.0f  // 63 Hz — bass definition
            3  ->  1.5f  // 100 Hz
            4  ->  0.5f  // 160 Hz — transition bridge; fills 120-140Hz dip between 100Hz lift and flat mids
            7  -> -0.5f  // 630 Hz — mud cut; classic width trick, makes image feel wider without scooping vocals
            14 ->  4.5f  // 16 kHz — reduced from 6.0f; was causing ear fatigue/pain especially combined with ILD differential
            13 ->  3.8f  // 10 kHz — reduced from 4.5f
            12 ->  3.0f  // 6.3 kHz
            11 ->  1.5f  // 4 kHz
            else -> 0f
        }
    }

    /**
     * Per-band LEFT-minus-RIGHT gain differential (dB) for a surround mode —
     * the actual stereo-WIDTH component, layered on top of
     * [surroundBandProfile]'s centered "shape". Only the treble/air bands
     * (11-14, 4kHz-16kHz) ever get a differential; bass (0-3) and
     * vocals/mids (4-10) are always 0 here, same as in [surroundBandProfile].
     *
     * This is deliberately NOT a single-direction tilt. A positive value
     * means the LEFT channel leads at that band (and right trails by the same
     * amount); a negative value means right leads. The sign ALTERNATES
     * band-to-band, and across all four treble bands the values sum to
     * exactly zero — so there is no contiguous frequency region where one
     * channel is consistently louder, and no overall left/right bias at all.
     * What's left is a frequency-dependent ILD "comb" across the air band:
     * left leads at 4kHz/10kHz, right leads at 6.3kHz/16kHz (Wide). Because
     * ILD is the brain's dominant localization cue up here, this comb is read
     * as "wide"/"enveloping" rather than "panned to one side" — unlike the
     * earlier single-direction approach that caused exactly that complaint.
     *
     * [applyAllBands] splits this evenly: +diff/2 to the leading channel,
     * -diff/2 to the other, around the centered total from
     * [surroundBandProfile] — so the centered loudness for that band is
     * unchanged and only the width around it changes.
     *
     *  - Off / Front Stage: no differential — Front Stage's "speaker-like
     *    imaging" comes entirely from its centered vocal-presence lift, and
     *    dialogue must stay perfectly centered.
     *  - Traditional ("a little more stereo widening"): a small +/-1.5dB
     *    swap at 10kHz/16kHz.
     *  - Wide ("180 degree" widest image): a stronger +/-2.5 to +/-3.5dB
     *    swap across all four treble bands (4k/6.3k/10k/16k) for a
     *    noticeably wider, more enveloping image.
     */
    private fun surroundChannelDifferential(mode: SurroundMode, index: Int): Float = when (mode) {
        SurroundMode.Off -> 0f
        // Front Stage: zero differential — real bookshelf speakers produce a phantom
        // center at the listener's midpoint; the "in front" cue is entirely tonal
        // (presence peak, sub-bass rolloff, room reinforcement), not a stereo width effect.
        // Any L/R differential here reads as compression/comb, not speaker imaging.
        SurroundMode.Front -> 0f
        SurroundMode.Traditional -> when (index) {
            11 ->  1.0f  // 4 kHz: left leads — anchors width in audible range; previous 10/16kHz-only diff was inaudible on rolled-off drivers
            12 -> -1.0f  // 6.3 kHz: right leads
            13 ->  1.5f  // 10 kHz: left leads
            14 -> -1.5f  // 16 kHz: right leads
            else -> 0f
        }
        SurroundMode.Wide -> when (index) {
            11 ->  1.0f  // 4 kHz: left leads
            12 -> -2.0f  // 6.3 kHz: right leads — reduced from 2.5f
            13 ->  2.5f  // 10 kHz: left leads — reduced from 3.5f
            14 -> -2.5f  // 16 kHz: right leads — reduced from 3.5f; combined with centered gain was causing ear pain
            else -> 0f
        }
    }

    /**
     * Combines the manual graphic-EQ gain for [index] with every other
     * tonal-shape contributor that also lands on that band (Parametric EQ,
     * HDR's air-shelf, Tube Warmth's bloom/rolloff, Surround+'s smile) and
     * writes the result to the live DSP. Shared by [applyAllBands] (full
     * 15-band recombination, needed whenever something that affects every
     * band's shape changes — a preset, PEQ, HDR, Tube Warmth, or Surround
     * mode) and [applySingleBand] (the fast path for a manual-EQ slider/drag
     * touching exactly one band).
     */
    private fun writeCombinedBand(index: Int, graphicGain: Float, mode: SurroundMode) {
        // Peak across this PreEQ band's real cutoff span, not sampled at one
        // point — see DigitalFilterEngine.evaluateBandPeakDb for why a
        // single-point sample silently swallowed narrow (high-Q) PEQ filters
        // that didn't happen to land on one of the 15 fixed centres.
        val peqGain = parametricEqShape(index)
        val hdrAirBoost = when {
            !_hdrDynamicsEnabled.value -> 0f
            _hdrMode.value != HdrMode.Restoration -> 0f
            index == 13 -> 0.6f
            index == 14 -> 1.0f
            else -> 0f
        }
        // Tube Warmth tonal shape: a low-end "bloom" around 60-100Hz from
        // transformer-coupled output stages, plus a gentle high-frequency
        // roll-off above ~10kHz — the two tonal traits that, together with
        // the LoudnessEnhancer saturation stage, give the "tube" character.
        val tubeWarmthShape = if (_tubeWarmthEnabled.value) {
            val intensity = _tubeWarmthIntensity.value
            when (index) {
                2 -> 1.2f * intensity   // 63 Hz bloom
                3 -> 0.8f * intensity   // 100 Hz bloom
                13 -> -1.2f * intensity // 10 kHz roll-off
                14 -> -2.5f * intensity // 16 kHz roll-off
                else -> 0f
            }
        } else 0f
        // SBC pre-emphasis: biases the PreEQ signal that the codec encodes so
        // SBC's subband bit-allocator invests more bits in the treble bands —
        // audible result is cleaner highs with less quantization grunge.
        // Applied only when the user has explicitly enabled SBC Enhancement for
        // this BT device; zero on all non-BT routes and LDAC/LHDC profiles.
        // SBC pre-emphasis: a carefully shaped curve tuned to SBC's specific failure modes.
        // SBC uses only 8 subbands — its bit-allocator starves the top 2 subbands (roughly
        // 10-22kHz) because they have less energy than the bass-heavy subbands. Boosting
        // those bands forces the allocator to invest more bits there, reducing quantisation
        // grunge. Simultaneously, SBC's subband boundaries at ~4kHz and ~8kHz introduce
        // mild quantisation noise — a small cut there removes the harshness without dulling
        // the sound. A gentle sub-bass lift (63-100Hz) restores the low-end "body" that
        // SBC's wide subbands tend to spread and thin out. The result sounds like a better
        // codec, not like an EQ was applied.
        //
        // The curve can be replaced over the content channel (see RemoteTuning)
        // without shipping an APK — it's exactly the kind of by-ear tuning that
        // gets revised between releases. The remote array is already clamped to
        // ±10dB by RemoteContent.parseTuning; absent or malformed, the built-in
        // curve below is used unchanged.
        val sbcPreEmphasis = sbcPreEmphasisShape(index)
        // Loudness Contour (ISO 226): re-tilts the balance to match how the
        // ear actually behaves at the current listening level. Summed in here
        // like every other tonal shape rather than applied as its own stage,
        // so it shares the same single PreEQ write per band and the same
        // ±15dB clamp below. Exactly 0dB at 1kHz by construction, and
        // all-zero whenever the feature is off — see LoudnessContour.
        val loudnessShape = loudnessCompensation.getOrElse(index) { 0f }
        // Crossfeed's tonal-EQ curve — see crossfeedShape() for why Crossfeed
        // is a PreEQ contributor now instead of a Virtualizer effect.
        val crossfeedTonalShape = crossfeedShape(index)
        // Device-class tonal correction — see DeviceType.correctionDb. Applies
        // whenever a DeviceType other than General is selected, independent of
        // which features are on, which is what finally gives the Device Type
        // selector a sound of its own rather than only scaling other features.
        val deviceCorrection = deviceCorrectionShape(index)

        val baseGain = graphicGain + peqGain + hdrAirBoost + tubeWarmthShape +
            sbcPreEmphasis + loudnessShape + crossfeedTonalShape + deviceCorrection

        val centered = baseGain + surroundBandProfile(mode, index)
        val diff = surroundChannelDifferential(mode, index)
        if (diff == 0f) {
            dspEngine.setPreEqBandGainAllChannels(index, centered.coerceIn(-15f, 15f))
        } else {
            dspEngine.setPreEqBandGainByChannel(0, index, (centered + diff / 2f).coerceIn(-15f, 15f))
            dspEngine.setPreEqBandGainByChannel(1, index, (centered - diff / 2f).coerceIn(-15f, 15f))
        }
    }

    /**
     * Fast path for a single manual-EQ band changing (slider/drag) — writes
     * just that one band instead of [applyAllBands]'s full 15-band
     * recombination. The drag handler in InteractiveEqGraph now calls this
     * on every pointer-move (not just at gesture end), so this needed to be
     * cheap: applyAllBands was doing up to 15 synchronized DynamicsProcessing
     * band writes per call, which at "every move event during a drag"
     * frequency was the actual cause of the laggy, multi-second-delayed feel
     * — visually the dot tracked the finger instantly (that's local Compose
     * state), but the audio was stuck re-running a 15-band combine+write on
     * every single pixel of movement.
     */
    private fun applySingleBand(index: Int, gainDb: Float) {
        try {
            if (dspEngine.dynamicsProcessing == null) return
            writeCombinedBand(index, gainDb, _surroundMode.value)
        } catch (e: Exception) {
            Log.w(TAG, "applySingleBand skipped: ${e.message}")
        }
    }

    private fun applyAllBands(gains: FloatArray) {
        // Defensive: a topology rebuild can release/swap the DynamicsProcessing
        // instance on the main thread between calls. Guard the whole body so a
        // transient "effect not initialized" race never crashes a caller
        // (several call sites invoke this directly from UI callbacks without
        // their own try/catch).
        try {
            if (dspEngine.dynamicsProcessing == null) return
            val mode = _surroundMode.value
            dspEngine.setPreGain(_preGainDb.value)
            for (index in 0 until EqBands.count) {
                writeCombinedBand(index, gains.getOrNull(index) ?: 0f, mode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyAllBands skipped: ${e.message}")
        }
    }
}
