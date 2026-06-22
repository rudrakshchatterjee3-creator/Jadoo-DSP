package com.jadoo.amp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.jadoo.amp.audio.AutoEqTargetMode
import com.jadoo.amp.audio.DbfbMode
import com.jadoo.amp.audio.DigitalFilterEngine
import com.jadoo.amp.audio.HdrMode
import com.jadoo.amp.audio.EqBands
import com.jadoo.amp.audio.JadooDspService
import com.jadoo.amp.audio.SurroundMode
import com.jadoo.amp.settings.EqPresetPreferences
import com.jadoo.amp.settings.OnboardingPreferences
import com.jadoo.amp.settings.ThemePreferences
import com.jadoo.amp.settings.ThemeSettings
import com.jadoo.amp.settings.UpdatePreferences
import com.jadoo.amp.ui.DashboardScreen
import com.jadoo.amp.ui.OnboardingScreen
import com.jadoo.amp.ui.WhatsNewDialog
import com.jadoo.amp.ui.theme.JadOOampTheme
import com.jadoo.amp.update.ReleaseInfo
import com.jadoo.amp.update.UpdateChecker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var audioService: JadooDspService? by mutableStateOf(null)
    private var isBound = false
    // Reactive DUMP permission state — refreshed every onResume so it updates when
    // the user grants it via ADB while the app is in the background.
    private var dumpPermissionEnabled by mutableStateOf(false)
    // Set when launched via a music app's "External EQ" picker
    // (ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL). Consumed once audioService is bound.
    private var pendingExternalSession by mutableStateOf<Pair<Int, String?>?>(null)
    private lateinit var themePreferences: ThemePreferences
    private lateinit var eqPresetPreferences: EqPresetPreferences
    private lateinit var onboardingPreferences: OnboardingPreferences
    private lateinit var updatePreferences: UpdatePreferences

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as JadooDspService.LocalBinder
            audioService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        themePreferences = ThemePreferences(this)
        eqPresetPreferences = EqPresetPreferences(this)
        onboardingPreferences = OnboardingPreferences(this)
        updatePreferences = UpdatePreferences(this)
        refreshDumpPermission()
        handleExternalEqIntent(intent)

        ContextCompat.startForegroundService(
            this,
            Intent(this, JadooDspService::class.java)
        )
        
        Intent(this, JadooDspService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            val themeSettings by themePreferences.settings.collectAsState(initial = ThemeSettings())
            // null = loading (DataStore not yet read), true/false = resolved
            val onboardingDone by onboardingPreferences.hasCompletedOnboarding
                .collectAsState(initial = null)

            JadOOampTheme(
                useMaterialYou = themeSettings.useMaterialYou,
                customPrimaryColor = Color(themeSettings.customPrimaryColor)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Wait for DataStore to resolve before showing anything, so we never
                    // flash the dashboard for a single frame on first launch.
                    when (onboardingDone) {
                        null  -> { /* Still loading — render nothing (splash is already shown) */ }
                        false -> {
                            // First launch: full-screen onboarding
                            OnboardingScreen(
                                onFinished = {
                                    lifecycleScope.launch {
                                        onboardingPreferences.markCompleted()
                                    }
                                }
                            )
                        }
                        true  -> {
                    // ── Normal app flow ──────────────────────────────────────────────
                    var hasPermissions by remember { mutableStateOf(checkPermissions()) }
                    var showBatteryDialog by remember {
                        mutableStateOf(shouldRequestBatteryOptimizationExemption())
                    }
                    var newRelease by remember { mutableStateOf<ReleaseInfo?>(null) }

                    // Runs on every launch, as requested — silently fails offline.
                    // The dialog itself only appears once per genuinely new release,
                    // tracked via UpdatePreferences, so it doesn't nag on every open.
                    LaunchedEffect(Unit) {
                        val release = UpdateChecker.fetchLatestRelease() ?: return@LaunchedEffect
                        val installedVersion = try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                        } catch (_: Exception) { "0" }
                        if (!UpdateChecker.isNewer(release.tagName, installedVersion)) return@LaunchedEffect
                        if (updatePreferences.lastSeenTag() == release.tagName) return@LaunchedEffect
                        newRelease = release
                    }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        hasPermissions = permissions.entries.all { it.value }
                    }

                    LaunchedEffect(Unit) {
                        val perms = mutableListOf<String>()
                        // RECORD_AUDIO is needed by the Visualizer used for dynamic Auto-EQ.
                        // It's a dangerous permission and must be requested at runtime.
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            perms.add(Manifest.permission.RECORD_AUDIO)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        if (perms.isNotEmpty()) {
                            permissionLauncher.launch(perms.toTypedArray())
                        }
                    }

                    if (hasPermissions) {
                        MainContent(themeSettings)
                    } else {
                        PermissionsErrorCard()
                    }

                    if (showBatteryDialog) {
                        AlertDialog(
                            onDismissRequest = { showBatteryDialog = false },
                            title = { Text("Allow unrestricted background usage") },
                            text = {
                                Text("OriginOS may stop the JadOO DSP engine after a few seconds. Allow JadOO DSP to ignore battery optimizations so the DSP can keep running while music plays.")
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showBatteryDialog = false
                                        requestBatteryOptimizationExemption()
                                    }
                                ) {
                                    Text("Allow")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showBatteryDialog = false }) {
                                    Text("Later")
                                }
                            }
                        )
                    }

                    newRelease?.let { release ->
                        WhatsNewDialog(
                            release = release,
                            onDismiss = {
                                newRelease = null
                                lifecycleScope.launch { updatePreferences.markSeen(release.tagName) }
                            }
                        )
                    }
                                            } // end true -> branch
                    }   // end when(onboardingDone)
                }
            }
        }
    }

    @Composable
    private fun MainContent(themeSettings: ThemeSettings) {
        // Forward an "External EQ" launch to the service once it's bound.
        LaunchedEffect(audioService, pendingExternalSession) {
            val pending = pendingExternalSession
            val service = audioService
            if (pending != null && service != null) {
                service.attachExternalSession(pending.first, pending.second)
                pendingExternalSession = null
            }
        }
        val sessionId by audioService?.audioSessionId?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
        val activePackageName by audioService?.activeAppLabel?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
        val currentOutputDevice by audioService?.currentOutputDevice?.collectAsState(initial = "Phone Speaker") ?: remember { mutableStateOf("Phone Speaker") }
        val masterEnabled by audioService?.masterEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val dspBypassed by audioService?.dspBypassed?.collectAsState(initial = true) ?: remember { mutableStateOf(true) }
        val jadooEnabled by audioService?.jadooEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val autoEqTargetMode by audioService?.autoEqTargetMode?.collectAsState(initial = AutoEqTargetMode.HarmanCurve) ?: remember { mutableStateOf(AutoEqTargetMode.HarmanCurve) }
        val preGainDb by audioService?.preGainDb?.collectAsState(initial = 0f) ?: remember { mutableStateOf(0f) }
        val postGainDb by audioService?.postGainDb?.collectAsState(initial = 0f) ?: remember { mutableStateOf(0f) }
        val hiResUpscalerEnabled by audioService?.hiResUpscalerEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val hdrDynamicsEnabled by audioService?.hdrDynamicsEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val hdrMode = audioService?.hdrMode?.collectAsState(initial = HdrMode.Restoration)?.value ?: HdrMode.Restoration
        val dbfbMode by audioService?.dbfbMode?.collectAsState(initial = DbfbMode.Off) ?: remember { mutableStateOf(DbfbMode.Off) }
        val surroundMode by audioService?.surroundMode?.collectAsState(initial = SurroundMode.Off) ?: remember { mutableStateOf(SurroundMode.Off) }
        val bandGains by audioService?.bandGains?.collectAsState(initial = FloatArray(EqBands.count) { 0f }) ?: remember { mutableStateOf(FloatArray(EqBands.count) { 0f }) }
        val savedPresets by eqPresetPreferences.presets.collectAsState(initial = emptyList())
        // Analog Bass
        val analogBassEnabled by audioService?.analogBassEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val analogBassDrive by audioService?.analogBassDrive?.collectAsState(initial = 0.4f) ?: remember { mutableStateOf(0.4f) }
        val analogBassWarmth by audioService?.analogBassWarmth?.collectAsState(initial = 0.7f) ?: remember { mutableStateOf(0.7f) }
        val analogBassDrift by audioService?.analogBassDrift?.collectAsState(initial = 0.2f) ?: remember { mutableStateOf(0.2f) }
        val analogBassPultecBoost by audioService?.analogBassPultecBoost?.collectAsState(initial = 0.5f) ?: remember { mutableStateOf(0.5f) }
        val analogBassPultecCut by audioService?.analogBassPultecCut?.collectAsState(initial = 0.3f) ?: remember { mutableStateOf(0.3f) }
        val analogBassPultecFreqIndex by audioService?.analogBassPultecFreqIndex?.collectAsState(initial = 2) ?: remember { mutableStateOf(2) }

        // Tube Warmth
        val tubeWarmthEnabled by audioService?.tubeWarmthEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val tubeWarmthIntensity by audioService?.tubeWarmthIntensity?.collectAsState(initial = 0.5f) ?: remember { mutableStateOf(0.5f) }

        // Mobile Bass
        val mobileBassEnabled by audioService?.mobileBassEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val mobileBassIntensity by audioService?.mobileBassIntensity?.collectAsState(initial = 0.5f) ?: remember { mutableStateOf(0.5f) }

        // Digital Filters
        val digitalFilterBandStates by audioService?.digitalFilterBandStates?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
        var digitalFilterEnabled by remember { mutableStateOf(false) }

        DashboardScreen(
            sessionId = sessionId,
            isAttached = sessionId != null,
            activePackageName = activePackageName,
            currentOutputDevice = currentOutputDevice,
            masterEnabled = masterEnabled,
            dspBypassed = dspBypassed,
            jadooEnabled = jadooEnabled,
            autoEqTargetMode = autoEqTargetMode,
            preGainDb = preGainDb,
            postGainDb = postGainDb,
            hiResUpscalerEnabled = hiResUpscalerEnabled,
            hdrDynamicsEnabled = hdrDynamicsEnabled,
            hdrMode = hdrMode,
            dbfbMode = dbfbMode,
            surroundMode = surroundMode,
            bandGains = bandGains,
            // Analog Bass
            analogBassEnabled = analogBassEnabled,
            analogBassDrive = analogBassDrive,
            analogBassWarmth = analogBassWarmth,
            analogBassDrift = analogBassDrift,
            analogBassPultecBoost = analogBassPultecBoost,
            analogBassPultecCut = analogBassPultecCut,
            analogBassPultecFreqIndex = analogBassPultecFreqIndex,
            // Tube Warmth
            tubeWarmthEnabled = tubeWarmthEnabled,
            tubeWarmthIntensity = tubeWarmthIntensity,
            // Mobile Bass
            mobileBassEnabled = mobileBassEnabled,
            mobileBassIntensity = mobileBassIntensity,
            // Digital Filters
            digitalFilterEnabled = digitalFilterEnabled,
            digitalFilterBandStates = digitalFilterBandStates,
            savedPresets = savedPresets,
            useMaterialYou = themeSettings.useMaterialYou,
            customPrimaryColor = Color(themeSettings.customPrimaryColor),
            dumpPermissionEnabled = dumpPermissionEnabled,
            onMasterPowerToggled = { enabled ->
                audioService?.setMasterPower(enabled)
            },
            onJadooToggled = { enabled ->
                audioService?.setJadooEnabled(enabled)
            },
            onAutoEqTargetModeChanged = { mode ->
                audioService?.setAutoEqTargetMode(mode)
            },
            onPreGainChanged = { gain ->
                audioService?.setPreGain(gain)
            },
            onPostGainChanged = { gain ->
                audioService?.setPostGain(gain)
            },
            onHiResUpscalerToggled = { enabled ->
                audioService?.setHiResUpscalerEnabled(enabled)
            },
            onHdrDynamicsToggled = { enabled ->
                audioService?.setHdrDynamicsEnabled(enabled)
            },
            onHdrModeChanged = { mode ->
                audioService?.setHdrMode(mode)
            },
            onDbfbModeChanged = { mode ->
                audioService?.setDbfbMode(mode)
            },
            onSurroundModeChanged = { mode ->
                audioService?.setSurroundMode(mode)
            },
            onBandLevelChanged = { band, level ->
                audioService?.setManualBandGain(band, level)
            },
            onPresetSelected = { gains ->
                audioService?.applyPreset(gains)
            },
            onSavePreset = { name, gains ->
                lifecycleScope.launch {
                    eqPresetPreferences.savePreset(name, gains)
                }
            },
            onDeletePreset = { name ->
                lifecycleScope.launch {
                    eqPresetPreferences.deletePreset(name)
                }
            },
            // Analog Bass callbacks
            onAnalogBassEnabledChanged = { enabled ->
                audioService?.setAnalogBassEnabled(enabled)
            },
            onAnalogBassDriveChanged = { value ->
                audioService?.setAnalogBassDrive(value)
            },
            onAnalogBassWarmthChanged = { value ->
                audioService?.setAnalogBassWarmth(value)
            },
            onAnalogBassDriftChanged = { value ->
                audioService?.setAnalogBassDrift(value)
            },
            onAnalogBassPultecBoostChanged = { value ->
                audioService?.setAnalogBassPultecBoost(value)
            },
            onAnalogBassPultecCutChanged = { value ->
                audioService?.setAnalogBassPultecCut(value)
            },
            onAnalogBassPultecFreqIndexChanged = { index ->
                audioService?.setAnalogBassPultecFreqIndex(index)
            },
            onTubeWarmthEnabledChanged = { enabled ->
                audioService?.setTubeWarmthEnabled(enabled)
            },
            onTubeWarmthIntensityChanged = { value ->
                audioService?.setTubeWarmthIntensity(value)
            },
            onMobileBassEnabledChanged = { enabled ->
                audioService?.setMobileBassEnabled(enabled)
            },
            onMobileBassIntensityChanged = { value ->
                audioService?.setMobileBassIntensity(value)
            },
            onDigitalFilterEnabledChanged = { enabled ->
                digitalFilterEnabled = enabled
                audioService?.setDigitalFilterEnabled(enabled)
            },
            onDigitalFilterBandEnabledChanged = { index, enabled ->
                audioService?.setDigitalFilterBandEnabled(index, enabled)
            },
            onDigitalFilterBandTypeChanged = { index, type ->
                audioService?.setDigitalFilterBandType(index, type)
            },
            onDigitalFilterBandFrequencyChanged = { index, freq ->
                audioService?.setDigitalFilterBandFrequency(index, freq)
            },
            onDigitalFilterBandGainChanged = { index, gain ->
                audioService?.setDigitalFilterBandGain(index, gain)
            },
            onDigitalFilterBandQChanged = { index, q ->
                audioService?.setDigitalFilterBandQ(index, q)
            },
            onUseMaterialYouChanged = { enabled ->
                lifecycleScope.launch {
                    themePreferences.setUseMaterialYou(enabled)
                }
            },
            onCustomPrimaryColorChanged = { color ->
                lifecycleScope.launch {
                    themePreferences.setCustomPrimaryColor(color.toArgb())
                }
            },
            onResetDigitalFilterBands = {
                audioService?.resetDigitalFilterBands()
            },
                    )
    }

    @Composable
    private fun PermissionsErrorCard() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.padding(32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Permissions Required",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "JadOO DSP requires notifications permission to keep the DSP engine running.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return notifGranted
    }

    private fun shouldRequestBatteryOptimizationExemption(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun refreshDumpPermission() {
        dumpPermissionEnabled = ContextCompat.checkSelfPermission(
            this, "android.permission.DUMP"
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalEqIntent(intent)
    }

    /**
     * Handles ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL — sent by music apps'
     * "External EQ" / "Audio effects" picker. Stashes the session info so the
     * MainContent LaunchedEffect can forward it once audioService is bound.
     */
    private fun handleExternalEqIntent(intent: Intent?) {
        if (intent?.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        pendingExternalSession = sessionId to packageName
    }

    
    override fun onResume() {
        super.onResume()
        // Re-check every time the user returns so DUMP status updates after ADB grant
        refreshDumpPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
