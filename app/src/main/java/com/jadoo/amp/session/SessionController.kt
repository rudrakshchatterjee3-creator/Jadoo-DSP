package com.jadoo.amp.session

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

class SessionController(private val context: Context) {

    private val dumpSysProvider = DumpSysSessionProvider()

    private fun hasDumpPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, "android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    suspend fun getActiveAudioSessionId(): Int? {
        if (!hasDumpPermission()) {
            Log.d("SessionController", "DUMP permission not granted, skipping dumpsys")
            return null
        }
        Log.d("SessionController", "Resolving session via DumpSys")
        return dumpSysProvider.getActiveSessionId()
    }
}
