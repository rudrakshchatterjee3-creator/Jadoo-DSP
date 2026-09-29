package com.jadoo.amp.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

// Play flavor's update channel. Google Play installs updates itself, and
// Play's Device and Network Abuse policy forbids an app updating itself any
// other way — so this build has no GitHub release check, no APK download and
// no REQUEST_INSTALL_PACKAGES. The github flavor's UpdateChannel.kt has the
// same declarations.

/**
 * Tuning, presets and device profiles come only from the copy bundled in the
 * app (res/raw/jadoo_content.json); new content ships with app updates. With
 * this off the build makes no network requests at all, so the play manifest
 * drops INTERNET too.
 */
const val REMOTE_CONTENT_ENABLED = false

/**
 * Per-headphone correction curves are left out of the Play build: the service
 * drops the content document's headphoneProfiles, so no device suggestion,
 * picker or correction curve appears.
 */
const val DEVICE_TUNING_ENABLED = false

/** Nothing to check at launch: the Play Store notifies and updates on its own. */
@Composable
fun UpdateLaunchCheck() = Unit

/** Settings' Updates card: sends the user to the Play listing to update there. */
@Composable
fun UpdateCheckControls(@Suppress("UNUSED_PARAMETER") installedVersion: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { openStoreListing(context) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Check for updates on Google Play")
    }
}

private fun openStoreListing(context: Context) {
    val id = context.packageName
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
    } catch (_: ActivityNotFoundException) {
        // No Play Store app (e.g. a de-Googled ROM) — the web listing still works.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
        )
    }
}
