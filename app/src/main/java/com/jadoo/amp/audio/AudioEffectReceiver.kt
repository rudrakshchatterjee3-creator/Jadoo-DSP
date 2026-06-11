package com.jadoo.amp.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
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
                context.startForegroundService(serviceIntent)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.d("AudioEffectReceiver", "Closing session: $sessionId ($packageName)")
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
