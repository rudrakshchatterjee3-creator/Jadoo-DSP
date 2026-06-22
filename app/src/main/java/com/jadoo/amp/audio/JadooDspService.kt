package com.jadoo.amp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jadoo.amp.session.SessionController
import com.jadoo.amp.settings.SessionPreferences
import com.jadoo.amp.settings.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class JadooDspService : Service() {

    companion object {
        private const val TAG = "JadooDspService"
        private const val GLOBAL_AUDIO_SESSION_ID = 0
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val dspEngine = DspEngine()
    lateinit var brain: PsychoacousticsBrain
    private lateinit var mediaSessionManager: MediaSessionManager
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

    private val _jadooEnabled = MutableStateFlow(false)
    val jadooEnabled: StateFlow<Boolean> = _jadooEnabled.asStateFlow()

    private val _autoEqTargetMode = MutableStateFlow(AutoEqTargetMode.HarmanCurve)
    val autoEqTargetMode: StateFlow<AutoEqTargetMode> = _autoEqTargetMode.asStateFlow()

    private val manualBandGains = FloatArray(EqBands.count) { 0f }

    private val _bandGains = MutableStateFlow(FloatArray(EqBands.count) { 0f })
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

    // ── Digital Filter State ───────────────────────────────────────────
    val digitalFilterBandStates: StateFlow<List<DigitalFilterEngine.BiquadBandState>>
        get() = digitalFilterEngine.bandStates

    private var saveDebounceJob: Job? = null
    // Generation counter guarding delayed brain.start() restarts (AutoEQ mode
    // changes, DSP topology rebuilds) so only the most recently scheduled
    // restart actually fires, even if several are queued back-to-back.
    private var autoEqRestartToken = 0
    // Generation counter guarding the delayed PreEQ "settle" re-apply after
    // HDR Dynamics is enabled (see setHdrDynamicsEnabled).
    private var hdrSettleToken = 0

    // Per-output-device profile switching (see computeOutputDeviceKey/switchToDeviceProfile)
    @Volatile private var currentDeviceKey: String = "speaker"
    private var audioDeviceCallback: AudioDeviceCallback? = null

    inner class LocalBinder : Binder() {
        fun getService(): JadooDspService = this@JadooDspService
    }

    override fun onCreate() {
        super.onCreate()
        brain = PsychoacousticsBrain(this)
        // Detect actual device sample rate (fallback to 48000 if unavailable)
        val detectedRate = try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toFloatOrNull() ?: 48000f
        } catch (_: Exception) { 48000f }
        analogBassEngine.initialize(detectedRate)
        digitalFilterEngine.initialize(detectedRate)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionController = SessionController(this)
        sessionPreferences = SessionPreferences(this)

        val (initialDeviceKey, initialDeviceLabel) = computeOutputDeviceKey()
        currentDeviceKey = initialDeviceKey
        _currentOutputDevice.value = initialDeviceLabel
        registerAudioDeviceCallback()

        startForegroundService()
        registerMediaSessionListener()
        restoreSession()
    }

    /**
     * Identify the active audio output device so each one (phone speaker,
     * wired headphones, Bluetooth headset, USB DAC...) can keep its own
     * persisted DSP profile — mirroring Wavelet's per-device profiles.
     * Priority: USB > Bluetooth > Wired > built-in speaker (default/fallback).
     * Returns (deviceKey, displayLabel).
     */
    private fun computeOutputDeviceKey(): Pair<String, String> {
        val devices = try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }

        val usb = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (usb != null) return "usb" to "USB Audio"

        val bluetooth = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        if (bluetooth != null) {
            val rawName = try { bluetooth.productName?.toString()?.trim() } catch (_: Exception) { null }
            return if (!rawName.isNullOrBlank()) {
                val safeName = rawName.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().take(40)
                if (safeName.isNotBlank()) {
                    "bt_${safeName.replace(' ', '_')}" to "Bluetooth: $safeName"
                } else {
                    "bt" to "Bluetooth"
                }
            } else {
                "bt" to "Bluetooth"
            }
        }

        val wired = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (wired != null) return "wired" to "Wired Headphones"

        return "speaker" to "Phone Speaker"
    }

    private fun registerAudioDeviceCallback() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                handleOutputRouteChange()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                handleOutputRouteChange()
            }
        }
        audioDeviceCallback = callback
        am.registerAudioDeviceCallback(callback, null)
    }

    /**
     * Called whenever the system's audio output routing changes (headphones
     * plugged/unplugged, Bluetooth connect/disconnect, etc). If the resolved
     * device key differs from the one currently in use, switches to that
     * device's saved DSP profile (or sensible defaults for a new device).
     */
    private fun handleOutputRouteChange() {
        val (newKey, newLabel) = computeOutputDeviceKey()
        if (newKey == currentDeviceKey) {
            _currentOutputDevice.value = newLabel
            return
        }
        switchToDeviceProfile(newKey, newLabel)
    }

    /**
     * Persist the current settings under the OLD device key, then load (or
     * default-initialize) the profile for the NEW device and apply it live.
     * Reuses rebuildDspTopology() — already proven to correctly re-attach the
     * DSP with every settings category (EQ gains, hiRes/dbfb/hdr/analogBass/
     * tubeWarmth topology, surround tilt, PEQ) and restart Auto-EQ if needed.
     */
    private fun switchToDeviceProfile(newKey: String, newLabel: String) {
        saveDebounceJob?.cancel()
        val oldKey = currentDeviceKey
        val stateToSave = buildSessionState()
        serviceScope.launch(Dispatchers.IO) {
            sessionPreferences.save(stateToSave, oldKey)
            val newState = sessionPreferences.load(newKey) ?: SessionState()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                currentDeviceKey = newKey
                _currentOutputDevice.value = newLabel

                val wasJadooEnabled = _jadooEnabled.value
                applyState(newState)
                if (wasJadooEnabled && !_jadooEnabled.value) {
                    brain.stop()
                }
                if (_masterEnabled.value) {
                    rebuildDspTopology()
                }
                Log.d(TAG, "Switched to output device profile: $newLabel ($newKey)")
            }
        }
    }

    private fun restoreSession() {
        serviceScope.launch(Dispatchers.IO) {
            val state = sessionPreferences.load(currentDeviceKey) ?: return@launch
            // Restore on main thread so flows update correctly
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                applyState(state)

                if (state.masterEnabled) setMasterPower(true)
                if (state.jadooEnabled)  setJadooEnabled(true)
                val restoredSurround = SurroundMode.entries
                    .firstOrNull { it.name == state.surroundMode } ?: SurroundMode.Off
                if (restoredSurround != SurroundMode.Off) setSurroundMode(restoredSurround)
            }
        }
    }

    /**
     * Apply every persisted setting from [state] to the in-memory flows and
     * engines, WITHOUT triggering any DSP rebuilds itself — callers
     * (restoreSession, switchToDeviceProfile) decide what to do afterwards.
     */
    private fun applyState(state: SessionState) {
        _autoEqTargetMode.value = AutoEqTargetMode.entries
            .firstOrNull { it.name == state.autoEqMode } ?: AutoEqTargetMode.HarmanCurve
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
        // Restore Analog Bass
        _analogBassEnabled.value = state.analogBassEnabled
        _analogBassDrive.value = state.analogBassDrive
        _analogBassWarmth.value = state.analogBassWarmth
        _analogBassDrift.value = state.analogBassDrift
        _analogBassPultecBoost.value = state.analogBassPultecBoost
        _analogBassPultecCut.value = state.analogBassPultecCut
        _analogBassPultecFreqIndex.value = state.analogBassPultecFreqIndex
        analogBassEngine.enabled = state.analogBassEnabled
        analogBassEngine.drive = state.analogBassDrive
        analogBassEngine.warmth = state.analogBassWarmth
        analogBassEngine.drift = state.analogBassDrift
        analogBassEngine.pultecBoost = state.analogBassPultecBoost
        analogBassEngine.pultecCut = state.analogBassPultecCut
        analogBassEngine.pultecFreqIndex = state.analogBassPultecFreqIndex
        // Restore Tube Warmth
        _tubeWarmthEnabled.value = state.tubeWarmthEnabled
        _tubeWarmthIntensity.value = state.tubeWarmthIntensity
        // Restore Mobile Bass
        _mobileBassEnabled.value = state.mobileBassEnabled
        _mobileBassIntensity.value = state.mobileBassIntensity
        // Restore Parametric EQ
        digitalFilterEngine.enabled = state.peqEnabled
        deserializePeqBands(state.peqBands)
        _jadooEnabled.value = state.jadooEnabled && _masterEnabled.value
    }

    /** Snapshot every current setting into a [SessionState] for persistence. */
    private fun buildSessionState(): SessionState = SessionState(
        masterEnabled = _masterEnabled.value,
        jadooEnabled  = _jadooEnabled.value,
        autoEqMode    = _autoEqTargetMode.value.name,
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
        // Parametric EQ
        peqEnabled = digitalFilterEngine.enabled,
        peqBands   = serializePeqBands()
    )

    private fun saveSession() {
        saveDebounceJob?.cancel()
        saveDebounceJob = serviceScope.launch(Dispatchers.IO) {
            delay(800)
            sessionPreferences.save(buildSessionState(), currentDeviceKey)
        }
    }

    // ── PEQ serialization helpers ────────────────────────────────────────
    // Format: "Type,freq,gain,q,enabled" per band, bands separated by "|"

    private fun serializePeqBands(): String =
        (0 until DigitalFilterEngine.MAX_BANDS).joinToString("|") { i ->
            val b = digitalFilterEngine.getBand(i)
            "${b.type.name},${b.frequencyHz},${b.gainDb},${b.q},${b.enabled}"
        }

    private fun deserializePeqBands(serialized: String) {
        if (serialized.isBlank()) return
        serialized.split("|").forEachIndexed { i, part ->
            if (i >= DigitalFilterEngine.MAX_BANDS) return@forEachIndexed
            val fields = part.split(",")
            if (fields.size < 5) return@forEachIndexed
            val type    = DigitalFilterEngine.FilterType.entries
                            .firstOrNull { it.name == fields[0] } ?: DigitalFilterEngine.FilterType.Peak
            val freq    = fields[1].toFloatOrNull() ?: 1000f
            val gain    = fields[2].toFloatOrNull() ?: 0f
            val q       = fields[3].toFloatOrNull() ?: 1.0f
            val enabled = fields[4].toBooleanStrictOrNull() ?: false
            digitalFilterEngine.setBand(i, DigitalFilterEngine.FilterBand(
                enabled = enabled, type = type, frequencyHz = freq, gainDb = gain, q = q
            ))
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
        // for "external audio effects" support) — gives us the EXACT session ID
        // and package name for the app that's actually playing, no guessing needed.
        val sessionId = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1) ?: -1
        if (sessionId > 0) {
            when (intent?.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                    _activePackageName.value = pkg
                    _activeAppLabel.value = resolveAppLabel(pkg)
                    if (_masterEnabled.value) {
                        attachSession(sessionId, pkg)
                    }
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (_masterEnabled.value && _audioSessionId.value == sessionId) {
                        _activePackageName.value = null
                        _activeAppLabel.value = null
                        attachGlobalSession(packageName = null)
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
        unregisterMediaSessionListener()
        audioDeviceCallback?.let {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it)
        }
        audioDeviceCallback = null
        brain.stop()
        dspEngine.release()
        serviceScope.cancel()
    }

    private fun registerMediaSessionListener() {
        if (mediaSessionListenerRegistered) return

        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                null
            )
            mediaSessionListenerRegistered = true
            handleActiveSessionsChanged(mediaSessionManager.getActiveSessions(null))
        } catch (securityException: SecurityException) {
            Log.w(
                TAG,
                "Media session access requires MEDIA_CONTENT_CONTROL or an enabled Notification Listener.",
                securityException
            )
        }
    }

    private fun unregisterMediaSessionListener() {
        if (!mediaSessionListenerRegistered) return

        mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
        mediaSessionListenerRegistered = false
    }

    private fun handleActiveSessionsChanged(controllers: List<MediaController>) {
        val activeController = controllers.firstOrNull { controller ->
            controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        val pkg = activeController?.packageName
        _activePackageName.value = pkg
        _activeAppLabel.value = resolveAppLabel(pkg)

        if (_masterEnabled.value && activeController != null) {
            resolveAndAttachSession(pkg)
        } else if (activeController == null) {
            _activePackageName.value = null
            _activeAppLabel.value = null
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
        if (_jadooEnabled.value) return true
        if (_hiResUpscalerEnabled.value) return true
        if (_dbfbMode.value != DbfbMode.Off) return true
        if (_hdrDynamicsEnabled.value) return true
        if (_surroundMode.value != SurroundMode.Off) return true
        if (_analogBassEnabled.value) return true
        if (_tubeWarmthEnabled.value) return true
        if (_mobileBassEnabled.value) return true
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
            }
            attachSession(GLOBAL_AUDIO_SESSION_ID, resolvedPackageName)
            onComplete()
        }
    }
    
    private fun attachGlobalSession(packageName: String? = _activePackageName.value) {
        attachSession(_audioSessionId.value ?: GLOBAL_AUDIO_SESSION_ID, packageName)
    }

    private fun attachSession(sessionId: Int, packageName: String? = _activePackageName.value) {
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
        if (dspEngine.dynamicsProcessing == null || _audioSessionId.value != sessionId) {
            if (dspEngine.attach(
                sessionId = sessionId,
                initialGains = _bandGains.value,
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
                analogBassPultecBoost = _analogBassPultecBoost.value,
                analogBassPultecCut = _analogBassPultecCut.value,
                analogBassPultecFreqIndex = _analogBassPultecFreqIndex.value,
                tubeWarmthEnabled = _tubeWarmthEnabled.value,
                tubeWarmthIntensity = _tubeWarmthIntensity.value,
                mobileBassEnabled = _mobileBassEnabled.value,
                mobileBassIntensity = _mobileBassIntensity.value
            )) {
                _audioSessionId.value = sessionId
                _activePackageName.value = packageName
                updateNotification()
                applyDigitalFilterToPreEq()
            }
        }
    }

    private fun detachSession() {
        brain.stop()
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
        if (sessionId > 0) {
            _activePackageName.value = packageName
            _activeAppLabel.value = resolveAppLabel(packageName)
            attachSession(sessionId, packageName)
        } else {
            resolveAndAttachSession(packageName)
        }
        if (_jadooEnabled.value) {
            brain.start(_audioSessionId.value)
        }
        updateNotification()
        saveSession()
    }

    fun setMasterPower(enabled: Boolean) {
        _masterEnabled.value = enabled
        if (enabled) {
            resolveAndAttachSession()
            if (_jadooEnabled.value) {
                brain.start(_audioSessionId.value)
            }
        } else {
            // Do NOT clear _jadooEnabled — it's a user preference that should
            // persist across master power cycles. When master comes back on,
            // Jadoo will automatically restart if it was previously enabled.
            brain.stop()
            updateBandGains(manualBandGains.copyOf())
            detachSession()
        }
        updateNotification()
        saveSession()
    }

    fun setJadooEnabled(enabled: Boolean) {
        _jadooEnabled.value = enabled && _masterEnabled.value
        if (_jadooEnabled.value) {
            // Wait for the (async) session resolve/attach to finish before
            // starting the brain — starting it immediately can hand it a
            // stale or null session ID, leaving Auto-EQ silently inert until
            // toggled again.
            val token = ++autoEqRestartToken
            resolveAndAttachSession {
                if (_jadooEnabled.value && token == autoEqRestartToken) brain.start(_audioSessionId.value)
            }
        } else {
            brain.stop()
            restoreManualEq()
        }
        saveSession()
    }

    fun setAutoEqTargetMode(mode: AutoEqTargetMode) {
        _autoEqTargetMode.value = mode
        if (_jadooEnabled.value) {
            // The brain's correction loop reads autoEqTargetMode.value each cycle,
            // so it will adapt on the next correction pass without a full restart.
            // Only restart if we need to flush the rolling window (e.g. drastic change).
            // A soft restart preserves currentGains to avoid audible jumps:
            // stop clears FFT buffer, start re-syncs gains from the current bandGains.
            brain.stop()
            // Small delay to ensure cancellation propagates before restarting.
            // Token guard: if the mode (or another restart) changes again
            // before this fires, skip — only the latest restart should run.
            val token = ++autoEqRestartToken
            serviceScope.launch {
                kotlinx.coroutines.delay(50)
                if (_jadooEnabled.value && token == autoEqRestartToken) brain.start(_audioSessionId.value)
            }
        }
        saveSession()
    }
    
    fun setManualBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until EqBands.count) return

        manualBandGains[bandIndex] = gainDb.coerceIn(-15f, 15f)
        updateBandGains(manualBandGains.copyOf())  // show raw gains without hi-res

        if (_masterEnabled.value && !_jadooEnabled.value) {
            attachGlobalSession()
            applyAllBands(manualBandGains)
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
        if (_masterEnabled.value && !_jadooEnabled.value) {
            attachGlobalSession()
            applyAllBands(manualBandGains)
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
                val gainsSnapshot = if (_jadooEnabled.value) _bandGains.value.copyOf() else manualBandGains.copyOf()
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
        if (_masterEnabled.value) {
            dspEngine.updateHeadroom(
                hiResEnabled = _hiResUpscalerEnabled.value,
                dbfbMode = _dbfbMode.value,
                analogBassEnabled = _analogBassEnabled.value,
                tubeWarmthEnabled = _tubeWarmthEnabled.value,
                tubeWarmthIntensity = _tubeWarmthIntensity.value,
                mobileBassEnabled = _mobileBassEnabled.value,
                mobileBassIntensity = _mobileBassIntensity.value,
                surroundMode = mode
            )
        }
        saveSession()
    }

    // ── Analog Bass Controls ─────────────────────────────────────────

    fun setAnalogBassEnabled(enabled: Boolean) {
        _analogBassEnabled.value = enabled
        analogBassEngine.enabled = enabled
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setAnalogBassDrive(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassDrive.value = clamped
        analogBassEngine.drive = clamped
        if (_analogBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateAnalogBassMbc(clamped, _analogBassWarmth.value)
        }
        saveSession()
    }

    fun setAnalogBassWarmth(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassWarmth.value = clamped
        analogBassEngine.warmth = clamped
        if (_analogBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateAnalogBassMbc(_analogBassDrive.value, clamped)
            dspEngine.updateAnalogBassPostEq(_analogBassPultecBoost.value, _analogBassPultecCut.value, _analogBassPultecFreqIndex.value, clamped)
        }
        saveSession()
    }

    fun setAnalogBassDrift(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassDrift.value = clamped
        analogBassEngine.drift = clamped
        saveSession()
    }

    fun setAnalogBassPultecBoost(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassPultecBoost.value = clamped
        analogBassEngine.pultecBoost = clamped
        if (_analogBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateAnalogBassPostEq(clamped, _analogBassPultecCut.value, _analogBassPultecFreqIndex.value, _analogBassWarmth.value)
        }
        saveSession()
    }

    fun setAnalogBassPultecCut(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _analogBassPultecCut.value = clamped
        analogBassEngine.pultecCut = clamped
        if (_analogBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateAnalogBassPostEq(_analogBassPultecBoost.value, clamped, _analogBassPultecFreqIndex.value, _analogBassWarmth.value)
        }
        saveSession()
    }

    fun setAnalogBassPultecFreqIndex(index: Int) {
        val clamped = index.coerceIn(0, AnalogBassEngine.PULTEC_FREQUENCIES.size - 1)
        _analogBassPultecFreqIndex.value = clamped
        analogBassEngine.pultecFreqIndex = clamped
        if (_analogBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateAnalogBassPostEq(_analogBassPultecBoost.value, _analogBassPultecCut.value, clamped, _analogBassWarmth.value)
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
            dspEngine.updateHeadroom(
                hiResEnabled = _hiResUpscalerEnabled.value,
                dbfbMode = _dbfbMode.value,
                analogBassEnabled = _analogBassEnabled.value,
                tubeWarmthEnabled = true,
                tubeWarmthIntensity = clamped,
                mobileBassEnabled = _mobileBassEnabled.value,
                mobileBassIntensity = _mobileBassIntensity.value,
                surroundMode = _surroundMode.value
            )
            val currentGains = if (_jadooEnabled.value) _bandGains.value.copyOf() else manualBandGains.copyOf()
            applyAllBands(currentGains)
        }
        saveSession()
    }

    // ── Mobile Bass Controls ──────────────────────────────────────────

    fun setMobileBassEnabled(enabled: Boolean) {
        _mobileBassEnabled.value = enabled
        if (_masterEnabled.value) rebuildDspTopology()
        saveSession()
    }

    fun setMobileBassIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _mobileBassIntensity.value = clamped
        if (_mobileBassEnabled.value && _masterEnabled.value) {
            dspEngine.updateMobileBassIntensity(clamped, _analogBassEnabled.value, _dbfbMode.value)
            dspEngine.updateHeadroom(
                hiResEnabled = _hiResUpscalerEnabled.value,
                dbfbMode = _dbfbMode.value,
                analogBassEnabled = _analogBassEnabled.value,
                tubeWarmthEnabled = _tubeWarmthEnabled.value,
                tubeWarmthIntensity = _tubeWarmthIntensity.value,
                mobileBassEnabled = true,
                mobileBassIntensity = clamped,
                surroundMode = _surroundMode.value
            )
        }
        saveSession()
    }

    /**
     * Update engine sample rates when the actual audio session rate is detected.
     * Called from PsychoacousticsBrain when the Visualizer reports a rate.
     */
    fun updateEngineSampleRate(sampleRateHz: Float) {
        if (sampleRateHz < 8000f) {
            Log.w(TAG, "Ignoring implausible sample rate: $sampleRateHz Hz")
            return
        }
        val currentRate = digitalFilterEngine.sampleRateHz
        if (kotlin.math.abs(currentRate - sampleRateHz) > 100f) {
            Log.d(TAG, "Sample rate updated: $currentRate Hz -> $sampleRateHz Hz")
            analogBassEngine.initialize(sampleRateHz)
            digitalFilterEngine.initialize(sampleRateHz)
        }
    }

    // ── Digital Filter Controls ───────────────────────────────────────────

    fun setDigitalFilterEnabled(enabled: Boolean) {
        digitalFilterEngine.enabled = enabled
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyDigitalFilterToPreEq()
        }
        saveSession()
    }

    fun updateDigitalFilterBand(index: Int, type: DigitalFilterEngine.FilterType, frequency: Float, gain: Float, q: Float, isEnabled: Boolean) {
        digitalFilterEngine.setBand(index, DigitalFilterEngine.FilterBand(
            enabled = isEnabled,
            type = type,
            frequencyHz = frequency,
            gainDb = gain,
            q = q
        ))
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyDigitalFilterToPreEq()
        }
        saveSession()
    }

    fun setDigitalFilterBandType(index: Int, type: DigitalFilterEngine.FilterType) {
        digitalFilterEngine.setBandType(index, type)
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyDigitalFilterToPreEq()
        }
        saveSession()
    }

    fun setDigitalFilterBandFrequency(index: Int, frequency: Float) {
        digitalFilterEngine.setBandFrequency(index, frequency)
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyDigitalFilterToPreEq()
        }
        saveSession()
    }

    fun setDigitalFilterBandGain(index: Int, gain: Float) {
        digitalFilterEngine.setBandGain(index, gain)
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyDigitalFilterToPreEq()
        }
        saveSession()
    }

    fun setDigitalFilterBandQ(index: Int, q: Float) {
        digitalFilterEngine.setBandQ(index, q)
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyDigitalFilterToPreEq()
        }
        saveSession()
    }

    fun setDigitalFilterBandEnabled(index: Int, enabled: Boolean) {
        digitalFilterEngine.setBandEnabled(index, enabled)
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyDigitalFilterToPreEq()
        }
        saveSession()
    }

    /** Reset all 8 PEQ bands to defaults in a single batch — avoids 40 rapid DSP writes. */
    fun resetDigitalFilterBands() {
        for (i in 0 until DigitalFilterEngine.MAX_BANDS) {
            digitalFilterEngine.setBand(i, DigitalFilterEngine.FilterBand(
                enabled = false,
                type = DigitalFilterEngine.FilterType.Peak,
                frequencyHz = 1000f,
                gainDb = 0f,
                q = 1.0f
            ))
        }
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyDigitalFilterToPreEq()
        }
        saveSession()
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
        val currentGains = _bandGains.value
        applyAllBands(currentGains)
        Log.d(TAG, "PEQ applied via applyAllBands (preserves surround shape + Auto-EQ + HDR air)")
    }

    /**
     * Re-applies the current EQ gains together with the active surround
     * mode's centered tonal shape (see [surroundBandProfile]). Always
     * identical on both channels — no L/R differential.
     */
    private fun applySurroundShaping() {
        val currentGains = if (_jadooEnabled.value) _bandGains.value.copyOf() else manualBandGains.copyOf()
        applyAllBands(currentGains)
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
            if (_jadooEnabled.value) brain.stop()
            dspEngine.release()
            _dspBypassed.value = true
            return
        }
        _dspBypassed.value = false

        val wasJadooEnabled = _jadooEnabled.value
        if (wasJadooEnabled) brain.stop()

        val currentGains = if (wasJadooEnabled) _bandGains.value.copyOf() else manualBandGains.copyOf()
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
            analogBassPultecBoost = _analogBassPultecBoost.value,
            analogBassPultecCut = _analogBassPultecCut.value,
            analogBassPultecFreqIndex = _analogBassPultecFreqIndex.value,
            tubeWarmthEnabled = _tubeWarmthEnabled.value,
            tubeWarmthIntensity = _tubeWarmthIntensity.value,
            mobileBassEnabled = _mobileBassEnabled.value,
            mobileBassIntensity = _mobileBassIntensity.value
        )
        if (attached) {
            applyAllBands(currentGains)
            if (wasJadooEnabled) {
                val token = ++autoEqRestartToken
                serviceScope.launch {
                    kotlinx.coroutines.delay(30)
                    if (_jadooEnabled.value && token == autoEqRestartToken) brain.start(_audioSessionId.value)
                }
            }
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

    private fun restoreManualEq() {
        updateBandGains(manualBandGains.copyOf())  // show raw gains, hi-res is invisible
        if (_masterEnabled.value) {
            attachGlobalSession()
            applyAllBands(manualBandGains)
        }
    }

    // Called by PsychoacousticsBrain to apply auto-EQ corrections
    fun applyBandsToEngine(gains: FloatArray) {
        applyAllBands(gains)
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
        SurroundMode.Traditional -> when (index) {
            0, 14 -> 4.0f   // 25 Hz / 16 kHz
            1, 13 -> 3.0f   // 40 Hz / 10 kHz
            2, 12 -> 2.0f   // 63 Hz / 6.3 kHz
            3, 11 -> 1.0f   // 100 Hz / 4 kHz
            else -> 0f
        }
        SurroundMode.Front -> when (index) {
            0, 14 -> 2.5f
            1, 13 -> 1.8f
            2, 12 -> 1.0f
            8 -> 0.8f   // 1kHz vocal fundamental: forward, centered
            9 -> 1.2f   // 1.6kHz vocal presence peak: forward, centered
            10 -> 0.8f  // 2.5kHz vocal clarity: forward, centered
            else -> 0f
        }
        SurroundMode.Wide -> when (index) {
            0, 14 -> 6.0f   // 25 Hz / 16 kHz
            1, 13 -> 4.5f   // 40 Hz / 10 kHz
            2, 12 -> 3.0f   // 63 Hz / 6.3 kHz
            3, 11 -> 1.5f   // 100 Hz / 4 kHz
            // 4-10 (160Hz-2.5kHz, vocals/mids): untouched — zero extra gain
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
        SurroundMode.Off, SurroundMode.Front -> 0f
        SurroundMode.Traditional -> when (index) {
            13 -> 1.5f   // 10 kHz: left leads
            14 -> -1.5f  // 16 kHz: right leads
            else -> 0f
        }
        SurroundMode.Wide -> when (index) {
            11 -> 2.5f   // 4 kHz: left leads
            12 -> -2.5f  // 6.3 kHz: right leads
            13 -> 3.5f   // 10 kHz: left leads
            14 -> -3.5f  // 16 kHz: right leads
            else -> 0f
        }
    }

    private fun applyAllBands(gains: FloatArray) {
        // Defensive: applyAllBands can be invoked from the PsychoacousticsBrain's
        // background thread while a topology rebuild releases/swaps the
        // DynamicsProcessing instance on the main thread. Guard the whole body
        // so a transient "effect not initialized" race never crashes a caller
        // (several call sites invoke this directly from UI callbacks without
        // their own try/catch).
        try {
            if (dspEngine.dynamicsProcessing == null) return
            val mode = _surroundMode.value

            val baseGains = FloatArray(EqBands.count)
            for (index in 0 until EqBands.count) {
                val graphicGain = gains.getOrNull(index) ?: 0f
                val peqGain = digitalFilterEngine.evaluateMagnitudeResponseDb(EqBands.frequencies[index])
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
                baseGains[index] = graphicGain + peqGain + hdrAirBoost + tubeWarmthShape
            }

            dspEngine.setPreGain(_preGainDb.value)

            // Second pass: apply gains to DSP. Most bands are identical on
            // both channels; only the treble bands in Traditional/Wide get a
            // small, balanced left/right differential for stereo width (see
            // surroundChannelDifferential) — and even then the centered total
            // below is the midpoint, so loudness for that band is unchanged.
            for (index in 0 until EqBands.count) {
                val centered = baseGains[index] + surroundBandProfile(mode, index)
                val diff = surroundChannelDifferential(mode, index)
                if (diff == 0f) {
                    dspEngine.setPreEqBandGainAllChannels(index, centered.coerceIn(-15f, 15f))
                } else {
                    dspEngine.setPreEqBandGainByChannel(0, index, (centered + diff / 2f).coerceIn(-15f, 15f))
                    dspEngine.setPreEqBandGainByChannel(1, index, (centered - diff / 2f).coerceIn(-15f, 15f))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyAllBands skipped: ${e.message}")
        }
    }
}
