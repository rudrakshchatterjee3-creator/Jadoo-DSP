package com.jadoo.amp.session

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DumpSysSessionProvider : AudioSessionProvider {

    override suspend fun getActiveSessionId(): Int? = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("dumpsys media.audio_flinger")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            // Regex to find session IDs in dumpsys output.
            // typically looks like: "session 1234" or "Session: 1234" in track info
            val regex = Regex("(?i)session\\s*(?:id)?[:=]?\\s*(\\d{2,5})")
            
            // We want to find the most likely active media session. We take the last one or most frequent.
            // For a simple implementation, returning the first non-zero match found.
            val matches = regex.findAll(output)
            for (match in matches) {
                val sessionId = match.groupValues[1].toIntOrNull()
                if (sessionId != null && sessionId > 0) {
                    // Audio sessions are typically > 0
                    return@withContext sessionId
                }
            }
            null
        } catch (e: Exception) {
            Log.e("DumpSysSessionProvider", "Error executing dumpsys media.audio_flinger", e)
            null
        }
    }
}
