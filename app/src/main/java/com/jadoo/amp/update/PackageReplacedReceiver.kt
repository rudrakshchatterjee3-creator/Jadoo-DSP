package com.jadoo.amp.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.jadoo.amp.audio.JadooDspService

/**
 * Restarts the DSP service after this app is updated.
 *
 * Installing an update kills the running process, service included, and
 * nothing relaunches it. For a DSP app that failure is invisible: music keeps
 * playing, just unprocessed, until the user happens to open JadOO again.
 *
 * ACTION_MY_PACKAGE_REPLACED is delivered only to the updated app itself, and
 * it is on the platform's list of broadcasts allowed to start a foreground
 * service from the background (Android 12+), so the start below is permitted.
 * The service's own restoreSession() decides whether processing is actually
 * turned on — this only makes sure it is running to decide.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, JadooDspService::class.java)
            )
            Log.i(TAG, "Restarted DSP service after update")
        } catch (e: Exception) {
            // Some OEM ROMs still refuse; the service comes back the next time
            // the app or a player's audio-effect broadcast starts it.
            Log.w(TAG, "Could not restart DSP service after update: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "PackageReplacedReceiver"
    }
}
