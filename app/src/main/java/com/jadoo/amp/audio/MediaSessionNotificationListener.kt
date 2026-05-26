package com.jadoo.amp.audio

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat

class MediaSessionNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MediaSessionListener", "Notification listener connected")
        ContextCompat.startForegroundService(
            this,
            Intent(this, JadooDspService::class.java)
        )
    }
}
