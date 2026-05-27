package com.jadoo.amp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
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

    private val _masterEnabled = MutableStateFlow(false)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

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

    private val _headroomDb = MutableStateFlow(0f)
    val headroomDb: StateFlow<Float> = _headroomDb.asStateFlow()

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

    private val spatialLock = Any()
    // Stores PreEQ gains before surround widening is applied so they can be restored exactly.
    private var preSurroundGains: FloatArray? = null
    private var saveDebounceJob: Job? = null

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
        startForegroundService()
        registerMediaSessionListener()
        restoreSession()
    }

    private fun restoreSession() {
        serviceScope.launch(Dispatchers.IO) {
            val state = sessionPreferences.load() ?: return@launch
            // Restore on main thread so flows update correctly
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _autoEqTargetMode.value = AutoEqTargetMode.entries
                    .firstOrNull { it.name == state.autoEqMode } ?: AutoEqTargetMode.HarmanCurve
                _preGainDb.value  = state.preGain
                _postGainDb.value = state.postGain
                _headroomDb.value = state.headroomDb
                _hiResUpscalerEnabled.value = state.hiResEnabled
                _dbfbMode.value = DbfbMode.entries
                    .firstOrNull { it.name == state.dbfbMode } ?: DbfbMode.Off
                _hdrDynamicsEnabled.value = state.hdrEnabled
                _hdrMode.value = HdrMode.entries
                    .firstOrNull { it.name == state.hdrMode } ?: HdrMode.Restoration
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
                // Restore Parametric EQ
                digitalFilterEngine.enabled = state.peqEnabled
                deserializePeqBands(state.peqBands)

                if (state.masterEnabled) setMasterPower(true)
                if (state.jadooEnabled)  setJadooEnabled(true)
                val restoredSurround = SurroundMode.entries
                    .firstOrNull { it.name == state.surroundMode } ?: SurroundMode.Off
                if (restoredSurround != SurroundMode.Off) setSurroundMode(restoredSurround)
            }
        }
    }

    private fun saveSession() {
        saveDebounceJob?.cancel()
        saveDebounceJob = serviceScope.launch(Dispatchers.IO) {
            delay(800)
            sessionPreferences.save(SessionState(
                masterEnabled = _masterEnabled.value,
                jadooEnabled  = _jadooEnabled.value,
                autoEqMode    = _autoEqTargetMode.value.name,
                preGain       = _preGainDb.value,
                postGain      = _postGainDb.value,
                headroomDb    = _headroomDb.value,
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
                // Parametric EQ
                peqEnabled = digitalFilterEngine.enabled,
                peqBands   = serializePeqBands()
            ))
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
            _audioSessionId.value != null && _masterEnabled.value ->
                "DSP active · ${_activeAppLabel.value ?: _activePackageName.value ?: "Global session"}"
            _masterEnabled.value -> "Engine on · Waiting for playback"
            else -> "Engine off"
        }
        val largeIcon = android.graphics.BitmapFactory.decodeResource(resources, com.jadoo.amp.R.drawable.ic_jadoo_dsp)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("JadOO DSP")
            .setContentText(statusText)
            .setSmallIcon(com.jadoo.amp.R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerMediaSessionListener()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterMediaSessionListener()
        brain.stop()
        releaseSpatial()
        dspEngine.release()
        serviceScope.cancel()
    }

    private fun registerMediaSessionListener() {
        if (mediaSessionListenerRegistered) return

        val notificationListenerComponent = ComponentName(this, MediaSessionNotificationListener::class.java)
            .takeIf { isNotificationListenerEnabled() }

        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                notificationListenerComponent
            )
            mediaSessionListenerRegistered = true
            handleActiveSessionsChanged(mediaSessionManager.getActiveSessions(notificationListenerComponent))
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

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(packageName, ignoreCase = true)
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

    private fun resolveAndAttachSession(packageName: String? = _activePackageName.value) {
        serviceScope.launch {
            val resolvedSessionId = sessionController.getActiveAudioSessionId()
                ?.takeIf { it > 0 }
                ?: GLOBAL_AUDIO_SESSION_ID
            attachSession(resolvedSessionId, packageName)
        }
    }
    
    private fun attachGlobalSession(packageName: String? = _activePackageName.value) {
        attachSession(_audioSessionId.value ?: GLOBAL_AUDIO_SESSION_ID, packageName)
    }

    private fun attachSession(sessionId: Int, packageName: String? = _activePackageName.value) {
        if (dspEngine.dynamicsProcessing == null || _audioSessionId.value != sessionId) {
            if (dspEngine.attach(
                sessionId = sessionId,
                initialGains = _bandGains.value,
                initialPreGainDb = _preGainDb.value + _headroomDb.value,
                initialPostGainDb = _postGainDb.value,
                hiResEnabled = _hiResUpscalerEnabled.value,
                dbfbMode = _dbfbMode.value,
                surroundPlusEnabled = _surroundMode.value != SurroundMode.Off,
                hdrDynamicsEnabled = _hdrDynamicsEnabled.value,
                hdrMode = _hdrMode.value,
                analogBassEnabled = _analogBassEnabled.value,
                analogBassDrive = _analogBassDrive.value,
                analogBassWarmth = _analogBassWarmth.value,
                analogBassPultecBoost = _analogBassPultecBoost.value,
                analogBassPultecCut = _analogBassPultecCut.value,
                analogBassPultecFreqIndex = _analogBassPultecFreqIndex.value
            )) {
                _audioSessionId.value = sessionId
                _activePackageName.value = packageName
                updateNotification()
                if (_surroundMode.value != SurroundMode.Off) {
                    applySpatialToSession(sessionId)
                }
            }
        }
    }
    
    private fun detachSession() {
        brain.stop()
        releaseSpatial()
        dspEngine.release()
        _audioSessionId.value = null
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
            resolveAndAttachSession()
            brain.start(_audioSessionId.value)
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
            // Small delay to ensure cancellation propagates before restarting
            serviceScope.launch {
                kotlinx.coroutines.delay(50)
                if (_jadooEnabled.value) brain.start(_audioSessionId.value)
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

    fun setHeadroomDb(db: Float) {
        _headroomDb.value = db.coerceIn(-6f, 0f)
        if (_masterEnabled.value) {
            attachGlobalSession()
            dspEngine.setPreGain(_preGainDb.value + _headroomDb.value)
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
        _hdrDynamicsEnabled.value = enabled
        if (_masterEnabled.value) rebuildDspTopology()
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
            releaseSpatial()
        } else if (mode != SurroundMode.Off) {
            val sessionId = _audioSessionId.value
            if (sessionId != null) {
                // Apply widening for ANY active session (specific or global session 0).
                // Previously excluded sessionId==0 which caused surround to silently
                // not apply when the engine was on the global session.
                applySpatialToSession(sessionId)
            } else if (_masterEnabled.value) {
                resolveAndAttachSession()
            }
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

    /**
     * Update engine sample rates when the actual audio session rate is detected.
     * Called from PsychoacousticsBrain when the Visualizer reports a rate.
     */
    fun updateEngineSampleRate(sampleRateHz: Float) {
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
        if (_masterEnabled.value) applyDigitalFilterToPreEq()
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
        if (_masterEnabled.value) applyDigitalFilterToPreEq()
        saveSession()
    }

    fun setDigitalFilterBandType(index: Int, type: DigitalFilterEngine.FilterType) {
        digitalFilterEngine.setBandType(index, type)
        if (_masterEnabled.value) applyDigitalFilterToPreEq()
        saveSession()
    }

    fun setDigitalFilterBandFrequency(index: Int, frequency: Float) {
        digitalFilterEngine.setBandFrequency(index, frequency)
        if (_masterEnabled.value) applyDigitalFilterToPreEq()
        saveSession()
    }

    fun setDigitalFilterBandGain(index: Int, gain: Float) {
        digitalFilterEngine.setBandGain(index, gain)
        if (_masterEnabled.value) applyDigitalFilterToPreEq()
        saveSession()
    }

    fun setDigitalFilterBandQ(index: Int, q: Float) {
        digitalFilterEngine.setBandQ(index, q)
        if (_masterEnabled.value) applyDigitalFilterToPreEq()
        saveSession()
    }

    fun setDigitalFilterBandEnabled(index: Int, enabled: Boolean) {
        digitalFilterEngine.setBandEnabled(index, enabled)
        if (_masterEnabled.value) applyDigitalFilterToPreEq()
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
        // applyAllBands already evaluates the parametric EQ response,
        // adds HDR air boost, and preserves surround differentials.
        // Calling it here prevents the surround L/R offset from being
        // wiped out by setPreEqBandAllChannelsTo inside DspEngine.
        val currentGains = _bandGains.value
        applyAllBands(currentGains)
        Log.d(TAG, "PEQ applied via applyAllBands (preserves surround + Auto-EQ + HDR air)")
    }

    /**
     * Apply spatial processing using frequency-dependent stereo widening.
     * Pure per-channel EQ differential — no deprecated Virtualizer.
     * Bass stays mono-centered, mids get gentle widening, treble gets full width.
     */
    private fun applySpatialToSession(sessionId: Int) {
        val mode = _surroundMode.value
        if (mode == SurroundMode.Off) { releaseSpatial(); return }
        // The actual widening is now handled by applyAllBands
        // Just trigger a re-apply of current gains with surround enabled
        val currentGains = if (_jadooEnabled.value) _bandGains.value.copyOf() else manualBandGains.copyOf()
        applyAllBands(currentGains)
        Log.d(TAG, "Spatial [$mode] applied to session $sessionId")
    }

    private fun rebuildDspTopology() {
        val sessionId = _audioSessionId.value
        if (sessionId == null) {
            Log.d(TAG, "rebuildDspTopology skipped — no active session")
            return
        }
        val wasJadooEnabled = _jadooEnabled.value
        if (wasJadooEnabled) brain.stop()

        // Clear surround state before rebuild
        preSurroundGains = null

        val currentGains = if (wasJadooEnabled) _bandGains.value.copyOf() else manualBandGains.copyOf()
        val attached = dspEngine.attach(
            sessionId = sessionId,
            initialGains = currentGains,
            initialPreGainDb = _preGainDb.value + _headroomDb.value,
            initialPostGainDb = _postGainDb.value,
            hiResEnabled = _hiResUpscalerEnabled.value,
            dbfbMode = _dbfbMode.value,
            surroundPlusEnabled = _surroundMode.value != SurroundMode.Off,
            hdrDynamicsEnabled = _hdrDynamicsEnabled.value,
            hdrMode = _hdrMode.value,
            analogBassEnabled = _analogBassEnabled.value,
            analogBassDrive = _analogBassDrive.value,
            analogBassWarmth = _analogBassWarmth.value,
            analogBassPultecBoost = _analogBassPultecBoost.value,
            analogBassPultecCut = _analogBassPultecCut.value,
            analogBassPultecFreqIndex = _analogBassPultecFreqIndex.value
        )
        if (attached) {
            applyAllBands(currentGains)
            if (wasJadooEnabled) {
                serviceScope.launch {
                    kotlinx.coroutines.delay(30)
                    if (_jadooEnabled.value) brain.start(_audioSessionId.value)
                }
            }
        }
    }

    private fun releaseSpatial() {
        synchronized(spatialLock) {
            preSurroundGains?.let {
                try {
                    // Restore both channels to equal gains (remove differential).
                    // We use current target gains rather than stale saved ones so
                    // AutoEQ corrections that arrived while surround was active are preserved.
                    val currentGains = if (_jadooEnabled.value) _bandGains.value.copyOf() else manualBandGains.copyOf()
                    applyAllBands(currentGains)
                } catch (_: Exception) {}
                preSurroundGains = null
            }
        }
    }

    private fun resetStereoWidening() {
        releaseSpatial()
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

    private fun applyAllBands(gains: FloatArray) {
        val dp = dspEngine.dynamicsProcessing ?: return
        val surroundEnabled = _surroundMode.value != SurroundMode.Off
        // Surround intensity controls the per-channel L/R EQ differential in dB.
        // Previous values (0.8/1.2/1.6 × 1.2 multiplier = max ~1.9 dB) were
        // below the audibility threshold — Traditional and Front Stage were
        // completely inaudible. New values create clearly perceptible widening:
        //   Traditional: up to ±3.5 dB (subtle, fatigue-free for long listening)
        //   Front Stage:  up to ±6.5 dB (clear, speaker-like projection)
        //   Ultra Wide:   up to ±11 dB  (dramatic 180° immersion)
        val surroundIntensity = when (_surroundMode.value) {
            SurroundMode.Off -> 0f
            SurroundMode.Traditional -> 3.5f
            SurroundMode.Front -> 6.5f
            SurroundMode.Wide -> 11.0f
        }

        for (index in 0 until EqBands.count) {
            val graphicGain = gains.getOrNull(index) ?: 0f
            val peqGain = digitalFilterEngine.evaluateMagnitudeResponseDb(EqBands.frequencies[index])
            val hdrAirBoost = when {
                !_hdrDynamicsEnabled.value -> 0f
                _hdrMode.value != HdrMode.Restoration -> 0f
                index == 13 -> 0.6f   // 10 kHz
                index == 14 -> 1.0f   // 16 kHz
                else -> 0f
            }
            val baseGain = graphicGain + peqGain + hdrAirBoost

            if (surroundEnabled) {
                // Frequency-dependent stereo widening via per-channel EQ differential.
                // Bass (0-4): mono — keeps low-end tight and centered.
                // Low-mids (5-7): moderate width — adds body without smearing.
                // Mids/vocals (8-10): ZERO width — critical! Keeps vocal formants
                //   (1-2.5 kHz) locked to center so singers don't drift sideways.
                // Upper presence (11-12): strong width — opens up the "room".
                // Treble/air (13-14): full width — creates spaciousness and shimmer.
                val widthFactor = when (index) {
                    in 0..4 -> 0f
                    in 5..7 -> 0.5f
                    in 8..10 -> 0f
                    in 11..12 -> 0.8f
                    in 13..14 -> 1.0f
                    else -> 0f
                }
                val offset = widthFactor * surroundIntensity  // max ±11 dB for Ultra Wide

                // Update saved base gains
                if (preSurroundGains == null) {
                    preSurroundGains = FloatArray(EqBands.count) { i ->
                        dp.getPreEqByChannelIndex(0).getBand(i).gain
                    }
                }
                preSurroundGains!![index] = baseGain

                // Direction alternates within each widened region so the total
                // left/right energy is balanced. Vocals (indices 8-10) get
                // offset = 0, so they stay perfectly centered.
                val leftBoost = when (index) {
                    in 5..7 -> if (index % 2 == 1) offset else -offset     // 5:+, 6:-, 7:+
                    in 11..12 -> if (index % 2 == 1) -offset else offset   // 11:-, 12:+
                    in 13..14 -> if (index % 2 == 1) offset else -offset   // 13:+, 14:-
                    else -> 0f
                }
                val rightBoost = -leftBoost

                val leftBand = dp.getPreEqByChannelIndex(0).getBand(index)
                val rightBand = dp.getPreEqByChannelIndex(1).getBand(index)
                leftBand.gain = (baseGain + leftBoost).coerceIn(-15f, 15f)
                rightBand.gain = (baseGain + rightBoost).coerceIn(-15f, 15f)
                dp.setPreEqBandByChannelIndex(0, index, leftBand)
                dp.setPreEqBandByChannelIndex(1, index, rightBand)
            } else {
                // Standard: both channels get same gain
                dspEngine.setPreEqBandGainAllChannels(index, baseGain)
            }
        }
    }
}
