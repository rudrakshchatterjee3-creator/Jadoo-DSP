package com.jadoo.amp.update

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadoo.amp.settings.UpdatePreferences
import com.jadoo.amp.ui.WhatsNewDialog
import kotlinx.coroutines.launch

// GitHub flavor's update channel: polls GitHub Releases and installs the APK
// in-app (see ApkUpdater), and refreshes tuning content over the air. The
// play flavor has its own UpdateChannel.kt with the same declarations,
// backed by Google Play instead.

/**
 * Whether tuning, presets and device profiles refresh from the content
 * document on GitHub (see ContentRepository). Off in the play flavor.
 */
const val REMOTE_CONTENT_ENABLED = true

/** Whether per-headphone correction curves (headphoneProfiles) are offered. */
const val DEVICE_TUNING_ENABLED = true

/**
 * Launch-time update check, hosted once in MainActivity.
 *
 * Runs on every launch, as requested — silently fails offline. Keeps showing
 * on every launch until the update is actually installed (a newer versionName
 * than the release) — "Download Update" opens the browser but does NOT
 * suppress the popup, since backing out without installing shouldn't mean
 * never seeing it again. Only "Remind me later" snoozes it, and only
 * temporarily (see UpdatePreferences).
 */
@Composable
fun UpdateLaunchCheck() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updatePreferences = remember { UpdatePreferences(context) }
    var newRelease by remember { mutableStateOf<ReleaseInfo?>(null) }

    LaunchedEffect(Unit) {
        val release = UpdateChecker.fetchLatestRelease() ?: return@LaunchedEffect
        val installedVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: Exception) { "0" }
        if (!UpdateChecker.isNewer(release.tagName, installedVersion)) return@LaunchedEffect
        if (updatePreferences.isSnoozed(release.tagName)) return@LaunchedEffect
        newRelease = release
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
                scope.launch { updatePreferences.snooze(release.tagName) }
            }
        )
    }
}

/**
 * Manual "Check for updates", shown in Settings' Updates card. The launch-time
 * check honours "Remind me later"; this one deliberately does not — tapping
 * the button is an explicit request, so a snoozed release is shown anyway, in
 * the same WhatsNewDialog (and the same verified in-app install).
 */
@Composable
fun UpdateCheckControls(installedVersion: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updatePreferences = remember { UpdatePreferences(context) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var newRelease by remember { mutableStateOf<ReleaseInfo?>(null) }

    status?.let {
        Text(
            text = it,
            color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            fontSize = 13.sp
        )
    }
    OutlinedButton(
        onClick = {
            checking = true
            status = null
            scope.launch {
                val release = UpdateChecker.fetchLatestRelease()
                checking = false
                when {
                    release == null -> {
                        statusIsError = true
                        status = "Couldn't reach the update server. Check your connection and try again."
                    }
                    UpdateChecker.isNewer(release.tagName, installedVersion) -> {
                        statusIsError = false
                        status = "Version ${release.tagName.removePrefix("v")} is available."
                        newRelease = release
                    }
                    else -> {
                        statusIsError = false
                        status = "You're on the latest version."
                    }
                }
            }
        },
        enabled = !checking,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Checking…")
        } else {
            Text("Check for updates")
        }
    }

    newRelease?.let { release ->
        WhatsNewDialog(
            release = release,
            onDownload = { newRelease = null },
            onRemindLater = {
                newRelease = null
                scope.launch { updatePreferences.snooze(release.tagName) }
            }
        )
    }
}
