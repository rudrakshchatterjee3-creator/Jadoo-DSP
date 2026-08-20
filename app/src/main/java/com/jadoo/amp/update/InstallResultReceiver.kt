package com.jadoo.amp.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Receives the outcome of a PackageInstaller session started by [ApkUpdater].
 *
 * The important case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: a
 * committed session does NOT install anything on its own — the system hands
 * back an Intent that must be launched to show the user the install
 * confirmation screen. Dropping it (the easy mistake here) makes the update
 * appear to silently do nothing.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation == null) {
                    Log.w(TAG, "Pending user action with no confirmation intent")
                    return
                }
                // Arrives with no activity on the stack in the general case, so
                // it needs its own task.
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmation)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not show install confirmation", e)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // The system restarts the app itself after an in-place update,
                // so there's nothing to do here beyond the log.
                Log.i(TAG, "Update installed successfully")
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install failed (status=$status): $message")
                // STATUS_FAILURE_ABORTED is the user declining the system
                // prompt — expected, not worth a toast.
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(
                        context,
                        "Update install failed${if (message != null) ": $message" else ""}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private companion object {
        const val TAG = "InstallResultReceiver"
    }
}
