package com.jadoo.amp.session

import android.content.Context
import android.util.Log

class SessionController(private val context: Context) {

    private val dumpSysProvider = DumpSysSessionProvider()

    suspend fun getActiveAudioSessionId(): Int? {
        Log.d("SessionController", "Resolving session via DumpSys")
        return dumpSysProvider.getActiveSessionId()
    }
}
