package com.jadoo.amp.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log

class AudioEffectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("AudioEffectReceiver", "Received action: $action")

        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        if (sessionId == 0) return

        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        val serviceIntent = Intent(context, JadooDspService::class.java).apply {
            this.action = action
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        }

        when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.d("AudioEffectReceiver", "Opening session: $sessionId ($packageName)")
                startServiceSafely(context, serviceIntent)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.d("AudioEffectReceiver", "Closing session: $sessionId ($packageName)")
                startServiceSafely(context, serviceIntent)
            }
        }
    }

    private fun startServiceSafely(context: Context, intent: Intent) {
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when the app
            // is in the background on some OEM ROMs with no active foreground service exemption.
            // The broadcast arrived but we cannot start the service right now — log and drop.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.w("AudioEffectReceiver", "Cannot start service from background on API 31+: ${e.message}")
            } else {
                Log.e("AudioEffectReceiver", "Failed to start DSP service", e)
            }
        }
    }
}
