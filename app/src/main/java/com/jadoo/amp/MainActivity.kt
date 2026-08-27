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
import com.jadoo.amp.audio.DbfbMode
import com.jadoo.amp.audio.DigitalFilterEngine
import com.jadoo.amp.audio.HdrMode
import com.jadoo.amp.audio.EqBands
import com.jadoo.amp.audio.JadooDspService
import com.jadoo.amp.audio.SurroundMode
import com.jadoo.amp.settings.BackupCodec
import com.jadoo.amp.settings.EqPresetPreferences
import com.jadoo.amp.settings.SavedEqPreset
import com.jadoo.amp.settings.SessionState
import com.jadoo.amp.settings.OnboardingPreferences
import com.jadoo.amp.settings.ThemePreferences
import com.jadoo.amp.settings.ThemeSettings
import com.jadoo.amp.settings.toSpec
import com.jadoo.amp.settings.UpdatePreferences
import com.jadoo.amp.ui.DashboardScreen
import com.jadoo.amp.ui.OnboardingScreen
import com.jadoo.amp.ui.WhatsNewDialog
import com.jadoo.amp.ui.theme.JadOOampTheme
import com.jadoo.amp.ui.theme.buildColorScheme
import com.jadoo.amp.ui.theme.rememberMotionEnabled
import com.jadoo.amp.ui.components.ThemeTransitionHost
import com.jadoo.amp.ui.components.rememberThemeTransitionState
import com.jadoo.amp.ui.components.play
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.isSystemInDarkTheme
import com.jadoo.amp.update.ReleaseInfo
import com.jadoo.amp.update.UpdateChecker
import kotlinx.coroutines.flow.first
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
    // Set when launched via "Share to JadOO DSP" or tapping a .json file
    // directly (see handleIncomingBackupIntent). Consumed once audioService is bound.
    private var pendingBackupUri by mutableStateOf<Uri?>(null)
    // Decoded import waiting for user to name it before saving as a custom profile
    private var pendingImportData by mutableStateOf<BackupCodec.DecodedBackup?>(null)
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
        requestHighestRefreshRate()
        themePreferences = ThemePreferences(this)
        eqPresetPreferences = EqPresetPreferences(this)
        onboardingPreferences = OnboardingPreferences(this)
        updatePreferences = UpdatePreferences(this)
        // One-time, read-once-before-Compose-starts check for whether this
        // install already has a completed onboarding — see
        // ThemePreferences.settings for why this decides the theme default
        // for a user with no saved appearance choice at all. A single local
        // DataStore read; blocking onCreate for it is negligible next to the
        // rest of this method's own synchronous setup, and it must resolve
        // before setContent so the very first frame already has the right
        // default (no flash of the wrong theme to correct later).
        val wasExistingUserAtLaunch = kotlinx.coroutines.runBlocking {
            onboardingPreferences.hasCompletedOnboarding.first()
        }
        refreshDumpPermission()
        handleExternalEqIntent(intent)
        handleIncomingBackupIntent(intent)

        ContextCompat.startForegroundService(
            this,
            Intent(this, JadooDspService::class.java)
        )
        
        Intent(this, JadooDspService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            // initial = null, NOT ThemeSettings() — a non-null default here
            // fires SYNCHRONOUSLY on first composition, before DataStore's
            // real (async) read completes. The guard below used to be
            // `if (appliedSettings == null) appliedSettings = themeSettings`,
            // which fired immediately on that fake first value (Brand,
            // ThemeSettings()'s own default), latched appliedSettings to it,
            // and then silently ignored the REAL persisted value when it
            // arrived a moment later — appliedSettings was already non-null,
            // so the "first resolution" branch never ran again. The user's
            // actual saved theme was read correctly; it just never reached
            // the screen. Every relaunch looked like "always resets to
            // Brand" because that's exactly what was happening — a race, not
            // a persistence bug.
            val persistedThemeSettings by themePreferences.settings(wasExistingUserAtLaunch).collectAsState(initial = null)
            // null = loading (DataStore not yet read), true/false = resolved
            val onboardingDone by onboardingPreferences.hasCompletedOnboarding
                .collectAsState(initial = null)

            // ── Theme changes are deferred, not applied on the spot ───────
            // `themeSettings` is the persisted truth. `appliedSettings` is
            // what is on screen. An appearance change writes the former and
            // then plays the eclipse, which commits the latter mid-animation
            // — so the whole-subtree invalidation happens under an opaque
            // curtain instead of in front of the user. See ThemeTransition.
            val motionEnabled = rememberMotionEnabled()
            val transition = rememberThemeTransitionState(motionEnabled)
            var appliedSettings by remember { mutableStateOf<ThemeSettings?>(null) }
            val themeSettings = persistedThemeSettings ?: ThemeSettings()
            val effectiveSettings = appliedSettings ?: themeSettings

            // First REAL resolution (persistedThemeSettings != null, i.e. an
            // actual DataStore emission, not the loading placeholder), and
            // any change that arrives while a transition is already running,
            // applies without ceremony.
            LaunchedEffect(persistedThemeSettings) {
                if (appliedSettings == null && persistedThemeSettings != null) {
                    appliedSettings = persistedThemeSettings
                }
            }

            val themeSpec = remember(effectiveSettings) { effectiveSettings.toSpec() }
            val systemDark = isSystemInDarkTheme()
            val scope = rememberCoroutineScope()

            /** Commit an appearance change behind the eclipse. */
            val applyThemeAnimated: (Offset, ThemeSettings) -> Unit = { origin, next ->
                scope.launch {
                    val incoming = buildColorScheme(next.toSpec(), systemDark, this@MainActivity)
                    transition.play(
                        from = origin,
                        incomingBackground = incoming.background
                    ) { appliedSettings = next }
                }
            }

            JadOOampTheme(spec = themeSpec) {
                ThemeTransitionHost(state = transition) {
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
                    // Non-null exactly once, right after a launch that follows a
                    // crash — see CrashHandler. Most people who hit an OEM-specific
                    // startup crash have no idea what logcat is; this turns "please
                    // send me a crash log" into a single Share tap.
                    var crashReportText by remember {
                        mutableStateOf(
                            CrashHandler.reportFile(this@MainActivity).takeIf { it.exists() }?.readText()
                        )
                    }

                    // Runs on every launch, as requested — silently fails offline.
                    // Keeps showing on every launch until the update is actually
                    // installed (a newer versionName than the release) — "Download
                    // Update" opens the browser but does NOT suppress the popup,
                    // since backing out without installing shouldn't mean never
                    // seeing it again. Only "Remind me later" snoozes it, and only
                    // temporarily (see UpdatePreferences).
                    LaunchedEffect(Unit) {
                        val release = UpdateChecker.fetchLatestRelease() ?: return@LaunchedEffect
                        val installedVersion = try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                        } catch (_: Exception) { "0" }
                        if (!UpdateChecker.isNewer(release.tagName, installedVersion)) return@LaunchedEffect
                        if (updatePreferences.isSnoozed(release.tagName)) return@LaunchedEffect
                        newRelease = release
                    }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        val notifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
                        hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifGranted else true
                    }

                    LaunchedEffect(Unit) {
                        val perms = mutableListOf<String>()
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
                        MainContent(
                            onAppearanceChanged = applyThemeAnimated,
                            currentAppearance = effectiveSettings,
                            themeSettings = themeSettings
                        )
                    } else {
                        PermissionsErrorCard()
                    }

                    if (showBatteryDialog) {
                        AlertDialog(
                            onDismissRequest = { showBatteryDialog = false },
                            title = { Text("Allow unrestricted background usage") },
                            text = {
                                Text("Some OEM ROMs may stop the JadOO DSP engine after a few seconds. Allow JadOO DSP to ignore battery optimizations so the DSP can keep running while music plays.")
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

                    crashReportText?.let { report ->
                        AlertDialog(
                            onDismissRequest = {
                                CrashHandler.reportFile(this@MainActivity).delete()
                                crashReportText = null
                            },
                            title = { Text("JadOO DSP crashed last time") },
                            text = { Text("Sharing this report helps get it fixed — it's just device info and a stack trace, no personal data.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, report)
                                    }
                                    startActivity(Intent.createChooser(sendIntent, "Share crash report"))
                                    CrashHandler.reportFile(this@MainActivity).delete()
                                    crashReportText = null
                                }) { Text("Share crash report") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    CrashHandler.reportFile(this@MainActivity).delete()
                                    crashReportText = null
                                }) { Text("Dismiss") }
                            }
                        )
                    }

                    newRelease?.let { release ->
                        WhatsNewDialog(
                            release = release,
                            onDownload = {
                                // Closes the dialog for this session only — does NOT
                                // snooze, so if the user backs out of the browser
                                // without installing, they'll see this again next launch.
                                newRelease = null
                            },
                            onRemindLater = {
                                newRelease = null
                                lifecycleScope.launch { updatePreferences.snooze(release.tagName) }
                            }
                        )
                    }
                                            } // end true -> branch
                    }   // end when(onboardingDone)
                }
                }   // end ThemeTransitionHost
            }
        }
    }

    /** Shared by the in-app file picker and the share-target/file-tap entry points.
     *  Decodes the backup and shows the naming dialog — the profile is saved only
     *  after the user confirms a unique name, so the current device profile is
     *  never overwritten by an import. */
    private fun importBackupFrom(uri: Uri) {
        lifecycleScope.launch {
            val raw = runCatching {
                contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            }.getOrNull()
            val decoded = raw?.let { BackupCodec.decode(it) } ?: return@launch
            pendingImportData = decoded
        }
    }

    @Composable
    private fun MainContent(
        themeSettings: ThemeSettings,
        currentAppearance: ThemeSettings,
        /**
         * Appearance changes go through here rather than writing DataStore
         * directly, so the eclipse can cover the recomposition. The [Offset]
         * is where the user tapped — the curtain wipes out from that exact
         * point, which is what makes the transition feel caused rather than
         * merely triggered.
         */
        onAppearanceChanged: (Offset, ThemeSettings) -> Unit
    ) {
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
        val preGainDb by audioService?.preGainDb?.collectAsState(initial = 0f) ?: remember { mutableFloatStateOf(0f) }
        val postGainDb by audioService?.postGainDb?.collectAsState(initial = 0f) ?: remember { mutableFloatStateOf(0f) }
        val hiResUpscalerEnabled by audioService?.hiResUpscalerEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val hdrDynamicsEnabled by audioService?.hdrDynamicsEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val hdrMode = audioService?.hdrMode?.collectAsState(initial = HdrMode.Restoration)?.value ?: HdrMode.Restoration
        val dbfbMode by audioService?.dbfbMode?.collectAsState(initial = DbfbMode.Off) ?: remember { mutableStateOf(DbfbMode.Off) }
        val surroundMode by audioService?.surroundMode?.collectAsState(initial = SurroundMode.Off) ?: remember { mutableStateOf(SurroundMode.Off) }
        val bandGains by audioService?.bandGains?.collectAsState(initial = FloatArray(EqBands.count)) ?: remember { mutableStateOf(FloatArray(EqBands.count)) }
        val userPresets by eqPresetPreferences.presets.collectAsState(initial = emptyList())
        // Analog Bass
        val analogBassEnabled by audioService?.analogBassEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val analogBassDrive by audioService?.analogBassDrive?.collectAsState(initial = 0.4f) ?: remember { mutableFloatStateOf(0.4f) }
        val analogBassWarmth by audioService?.analogBassWarmth?.collectAsState(initial = 0.7f) ?: remember { mutableFloatStateOf(0.7f) }
        val analogBassDrift by audioService?.analogBassDrift?.collectAsState(initial = 0.2f) ?: remember { mutableFloatStateOf(0.2f) }
        val analogBassPultecBoost by audioService?.analogBassPultecBoost?.collectAsState(initial = 0.5f) ?: remember { mutableFloatStateOf(0.5f) }
        val analogBassPultecCut by audioService?.analogBassPultecCut?.collectAsState(initial = 0.3f) ?: remember { mutableFloatStateOf(0.3f) }
        val analogBassPultecFreqIndex by audioService?.analogBassPultecFreqIndex?.collectAsState(initial = 2) ?: remember { mutableIntStateOf(2) }

        // Tube Warmth
        val tubeWarmthEnabled by audioService?.tubeWarmthEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val tubeWarmthIntensity by audioService?.tubeWarmthIntensity?.collectAsState(initial = 0.5f) ?: remember { mutableFloatStateOf(0.5f) }

        // Mobile Bass
        val mobileBassEnabled by audioService?.mobileBassEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val mobileBassIntensity by audioService?.mobileBassIntensity?.collectAsState(initial = 0.5f) ?: remember { mutableFloatStateOf(0.5f) }

        // Harmonic Exciter
        val harmonicExciterEnabled by audioService?.harmonicExciterEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val harmonicExciterIntensity by audioService?.harmonicExciterIntensity?.collectAsState(initial = 0.5f) ?: remember { mutableFloatStateOf(0.5f) }

        // Device Type
        val deviceType by audioService?.deviceType?.collectAsState(initial = com.jadoo.amp.audio.DeviceType.General) ?: remember { mutableStateOf(com.jadoo.amp.audio.DeviceType.General) }
        val deviceQualityTier by audioService?.deviceQualityTier?.collectAsState(initial = 0.5f) ?: remember { mutableFloatStateOf(0.5f) }

        // Crossfeed (Beta)
        val crossfeedEnabled by audioService?.crossfeedEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val crossfeedStrength by audioService?.crossfeedStrength?.collectAsState(initial = 0.5f) ?: remember { mutableFloatStateOf(0.5f) }

        // Loudness Contour (ISO 226)
        val loudnessEnabled by audioService?.loudnessEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val loudnessAmount by audioService?.loudnessAmount?.collectAsState(initial = 0.7f) ?: remember { mutableFloatStateOf(0.7f) }
        val loudnessReferencePhon by audioService?.loudnessReferencePhon?.collectAsState(initial = 80f) ?: remember { mutableFloatStateOf(80f) }
        val loudnessCurrentPhon by audioService?.loudnessCurrentPhon?.collectAsState(initial = 80f) ?: remember { mutableFloatStateOf(80f) }

        // Gain staging
        val preciseGainStaging by audioService?.preciseGainStaging?.collectAsState(initial = true) ?: remember { mutableStateOf(true) }
        val gainBudgetDb by audioService?.gainBudgetDb?.collectAsState(initial = 0f) ?: remember { mutableFloatStateOf(0f) }
        val autoTrimDb by audioService?.autoTrimDb?.collectAsState(initial = 0f) ?: remember { mutableFloatStateOf(0f) }
        val selectedPresetName by audioService?.selectedPresetName?.collectAsState(initial = "") ?: remember { mutableStateOf("") }

        // Per-app profiles — activePackageName above is the human-readable
        // label; the raw package is what keys a profile, so it's collected
        // separately rather than reverse-resolved from the label.
        val activeAppPackage by audioService?.activePackageName?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
        val perAppProfileActive by audioService?.perAppProfileActive?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val perAppProfilePackages by audioService?.perAppProfilePackages?.collectAsState(initial = emptySet()) ?: remember { mutableStateOf(emptySet<String>()) }

        // Remotely updatable content (Lane A)
        val remoteContent by audioService?.remoteContent?.collectAsState(initial = com.jadoo.amp.update.RemoteContent.EMPTY) ?: remember { mutableStateOf(com.jadoo.amp.update.RemoteContent.EMPTY) }
        val suggestedDeviceProfile by audioService?.suggestedDeviceProfile?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
        val activeDeviceProfileName by audioService?.activeDeviceProfileName?.collectAsState(initial = "") ?: remember { mutableStateOf("") }

        // Digital Filters
        val digitalFilterBandStates by audioService?.digitalFilterBandStates?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
        val digitalFilterEnabled by audioService?.digitalFilterEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }

        // SBC Enhancement + custom profiles
        val sbcModeEnabled by audioService?.sbcModeEnabled?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
        val customProfileNames by (audioService?.customProfileNames?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList<String>()) })

        // Preset packs delivered over the content channel (Lane A) appear
        // alongside the user's own saved presets. A user preset with the same
        // name always wins — someone who saved "Late Night" themselves must
        // not have it silently replaced by a remote curve of the same name.
        val savedPresets = remember(userPresets, remoteContent) {
            val userNames = userPresets.map { it.name }.toSet()
            userPresets + remoteContent.presets
                .filter { it.name !in userNames }
                .map { SavedEqPreset(it.name, it.gains) }
        }

        // Import profile naming dialog
        val pendingImport = pendingImportData
        if (pendingImport != null) {
            var profileName by remember(pendingImport) { mutableStateOf("") }
            var nameError by remember(pendingImport) { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { pendingImportData = null },
                title = { Text("Save Imported Profile") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Give this imported profile a name. It'll be saved separately - your current device profile won't be changed.")
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it; nameError = null },
                            label = { Text("Profile name") },
                            isError = nameError != null,
                            supportingText = nameError?.let { { Text(it) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = profileName.trim()
                        if (name.isBlank()) { nameError = "Please enter a name"; return@TextButton }
                        lifecycleScope.launch {
                            val exists = audioService?.profileExists(name) ?: false
                            if (exists) {
                                nameError = "Name already exists - try another"
                            } else {
                                audioService?.saveAsCustomProfile(name, pendingImport.sessionState)
                                pendingImport.eqPresets.forEach { preset ->
                                    eqPresetPreferences.savePreset(preset.name, preset.gains)
                                }
                                pendingImportData = null
                            }
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingImportData = null }) { Text("Cancel") }
                }
            )
        }

        // Export/import: SAF pickers write/read a single JSON backup file
        // covering the active device profile's SessionState plus every
        // saved EQ preset — see BackupCodec.
        val exportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            val service = audioService ?: return@rememberLauncherForActivityResult
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch {
                val json = BackupCodec.encode(service.exportSessionState(), eqPresetPreferences.presets.first())
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) importBackupFrom(uri) }

        // Consumes a .json backup shared via "Share to JadOO DSP" or tapped
        // directly in a file manager (see handleIncomingBackupIntent) — runs
        // it through the same import path as the in-app picker above, once
        // audioService is actually bound.
        LaunchedEffect(audioService, pendingBackupUri) {
            val uri = pendingBackupUri
            if (uri != null && audioService != null) {
                importBackupFrom(uri)
                pendingBackupUri = null
            }
        }

        DashboardScreen(
            sessionId = sessionId,
            isAttached = sessionId != null,
            activePackageName = activePackageName,
            currentOutputDevice = currentOutputDevice,
            masterEnabled = masterEnabled,
            dspBypassed = dspBypassed,
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
            // Harmonic Exciter
            harmonicExciterEnabled = harmonicExciterEnabled,
            harmonicExciterIntensity = harmonicExciterIntensity,
            deviceType = deviceType,
            deviceQualityTier = deviceQualityTier,
            crossfeedEnabled = crossfeedEnabled,
            crossfeedStrength = crossfeedStrength,
            // Loudness Contour
            loudnessEnabled = loudnessEnabled,
            loudnessAmount = loudnessAmount,
            loudnessReferencePhon = loudnessReferencePhon,
            loudnessCurrentPhon = loudnessCurrentPhon,
            // Gain staging
            preciseGainStaging = preciseGainStaging,
            gainBudgetDb = gainBudgetDb,
            autoTrimDb = autoTrimDb,
            selectedPresetName = selectedPresetName,
            // Per-app profiles
            activeAppPackage = activeAppPackage,
            perAppProfileActive = perAppProfileActive,
            perAppProfilePackages = perAppProfilePackages,
            // Content channel
            contentVersion = remoteContent.contentVersion,
            suggestedDeviceProfileName = suggestedDeviceProfile?.name,
            deviceProfileNames = remoteContent.headphoneProfiles.map { it.name },
            activeDeviceProfileName = activeDeviceProfileName,
            // Digital Filters
            digitalFilterEnabled = digitalFilterEnabled,
            digitalFilterBandStates = digitalFilterBandStates,
            savedPresets = savedPresets,
            // currentAppearance, NOT themeSettings — themeSettings is the raw
            // DataStore-collected value, which only catches up to a toggle
            // once the async write's Flow re-emits. Reading it here made the
            // Appearance switches (Pure Black in particular) render one beat
            // behind the tap: a toggle's visual effect (via applyThemeAnimated)
            // landed immediately, but the switch's own `checked` prop stayed on
            // the stale value and snapped back on the next recomposition,
            // reading as "didn't take" until a second toggle happened to land
            // after the DataStore flow had caught up. currentAppearance is
            // exactly what onThemeModeChanged/onAmoledChanged/etc already use
            // as their write-side base below — using it here too makes reads
            // and writes agree.
            themeSettings = currentAppearance,
            dumpPermissionEnabled = dumpPermissionEnabled,
            onMasterPowerToggled = { enabled ->
                audioService?.setMasterPower(enabled)
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
                if (mode == com.jadoo.amp.audio.SurroundMode.Front) {
                    audioService?.setAnalogBassEnabled(false)
                }
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
            onHarmonicExciterEnabledChanged = { enabled ->
                audioService?.setHarmonicExciterEnabled(enabled)
            },
            onHarmonicExciterIntensityChanged = { value ->
                audioService?.setHarmonicExciterIntensity(value)
            },
            onDeviceTypeChanged = { type ->
                audioService?.setDeviceType(type)
            },
            onDeviceQualityTierChanged = { value ->
                audioService?.setDeviceQualityTier(value)
            },
            onCrossfeedEnabledChanged = { enabled ->
                audioService?.setCrossfeedEnabled(enabled)
            },
            onCrossfeedStrengthChanged = { value ->
                audioService?.setCrossfeedStrength(value)
            },
            onDigitalFilterEnabledChanged = { enabled ->
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
            // Each of these persists the change AND plays the eclipse. The
            // persist is fire-and-forget; what the user sees is driven by
            // onAppearanceChanged committing the new settings mid-animation.
            onThemeModeChanged = { origin, mode ->
                lifecycleScope.launch { themePreferences.setMode(mode) }
                onAppearanceChanged(origin, currentAppearance.copy(mode = mode))
            },
            onToneModeChanged = { origin, tone ->
                lifecycleScope.launch { themePreferences.setTone(tone) }
                onAppearanceChanged(origin, currentAppearance.copy(tone = tone))
            },
            onAmoledChanged = { origin, enabled ->
                lifecycleScope.launch { themePreferences.setAmoled(enabled) }
                onAppearanceChanged(origin, currentAppearance.copy(amoled = enabled))
            },
            onSeedColorChanged = { origin, color ->
                lifecycleScope.launch { themePreferences.setSeedColor(color.toArgb()) }
                onAppearanceChanged(origin, currentAppearance.copy(seedColor = color.toArgb()))
            },
            onResetDigitalFilterBands = {
                audioService?.resetDigitalFilterBands()
            },
            onExportSettings = {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                exportLauncher.launch("jadoo-dsp-backup-$timestamp.json")
            },
            onImportSettings = {
                importLauncher.launch(arrayOf("application/json"))
            },
            // SBC Enhancement
            sbcModeEnabled = sbcModeEnabled,
            onSbcModeEnabledChanged = { audioService?.setSbcModeEnabled(it) },
            // Custom profiles
            savedProfileNames = customProfileNames,
            onLoadProfile = { name -> audioService?.loadCustomProfile(name) },
            onDeleteProfile = { name -> audioService?.deleteCustomProfile(name) },
            // Loudness Contour
            onLoudnessEnabledChanged = { audioService?.setLoudnessEnabled(it) },
            onLoudnessAmountChanged = { audioService?.setLoudnessAmount(it) },
            onLoudnessReferencePhonChanged = { audioService?.setLoudnessReferencePhon(it) },
            // Gain staging
            onPreciseGainStagingChanged = { audioService?.setPreciseGainStaging(it) },
            onSelectedPresetNameChanged = { audioService?.setSelectedPresetName(it) },
            // Per-app profiles
            onPerAppProfileToggled = { pkg, enabled ->
                audioService?.setPerAppProfileEnabled(pkg, enabled)
            },
            // Content channel
            onRefreshContent = { audioService?.refreshRemoteContent() },
            onApplySuggestedDeviceProfile = { audioService?.applySuggestedDeviceProfile() },
            onSelectDeviceProfile = { name -> audioService?.selectDeviceProfile(name) },
            onClearDeviceProfile = { audioService?.clearDeviceProfile() },
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
        handleIncomingBackupIntent(intent)
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

    /**
     * Handles a .json backup shared via "Share to JadOO DSP" (ACTION_SEND)
     * or tapped directly in a file manager (ACTION_VIEW). Stashes the URI so
     * the MainContent LaunchedEffect can run it through the same
     * BackupCodec import path as the in-app picker once audioService is bound.
     */
    private fun handleIncomingBackupIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        pendingBackupUri = uri
    }


    override fun onResume() {
        super.onResume()
        // Re-check every time the user returns so DUMP status updates after ADB grant
        refreshDumpPermission()
        // Battery saver / a display-mode change while backgrounded can drop
        // the window off its preferred mode; cheap to re-assert on resume.
        requestHighestRefreshRate()
    }

    /**
     * Opts this window into the display's highest available refresh rate at
     * the current resolution.
     *
     * Android caps a window to 60Hz by default unless it explicitly asks for
     * more — on a 90/120Hz-capable phone, that alone makes every scroll and
     * animation look less smooth than the hardware can actually do,
     * regardless of how cheap the Compose work driving them is. This is a
     * one-time window attribute, not a per-frame cost.
     *
     * `Display.getSupportedModes()` returns every resolution/refresh-rate
     * combination the panel supports; filtering to modes matching the
     * CURRENT resolution (rather than just the highest refresh rate overall)
     * avoids requesting a mode that would also silently change resolution.
     * `Context.getDisplay()` is the correct accessor from API 30 on;
     * `windowManager.defaultDisplay` (deprecated, but still the only route)
     * is needed below that — this app's minSdk is 28.
     */
    private fun requestHighestRefreshRate() {
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return
            val currentMode = display.mode ?: return
            val bestMode = display.supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: return
            if (bestMode.refreshRate <= currentMode.refreshRate) return
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = bestMode.modeId
            }
        } catch (_: Exception) {
            // Best-effort — worst case the window stays at its current mode.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
