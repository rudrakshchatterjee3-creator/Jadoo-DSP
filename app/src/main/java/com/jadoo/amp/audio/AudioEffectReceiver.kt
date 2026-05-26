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
        
        if (action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            if (sessionId != 0) {
                Log.d("AudioEffectReceiver", "Opening session: $sessionId")
                val serviceIntent = Intent(context, JadooDspService::class.java).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                }
                context.startForegroundService(serviceIntent)
            }
        } else if (action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
            // Can optionally inform service to release if it matches current session
            Log.d("AudioEffectReceiver", "Closing session")
        }
    }
}
