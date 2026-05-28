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
            
            // Find session IDs associated with active output tracks.
            // Prefer sessions that appear near "Output" or "Active" context lines.
            val sessionRegex = Regex("(?i)session\\s*(?:id)?[:=]?\\s*(\\d+)")
            val activeBlockRegex = Regex("(?i)(Output.*?track|Active.*?track|F\\(\\d+\\)).*?session", RegexOption.DOT_MATCHES_ALL)

            // First pass: look for sessions in active output blocks
            val activeBlocks = activeBlockRegex.findAll(output).toList()
            for (block in activeBlocks) {
                val match = sessionRegex.find(block.value)
                val sessionId = match?.groupValues?.get(1)?.toIntOrNull()
                if (sessionId != null && sessionId > 0) return@withContext sessionId
            }

            // Fallback: any session ID found
            for (match in sessionRegex.findAll(output)) {
                val sessionId = match.groupValues[1].toIntOrNull()
                if (sessionId != null && sessionId > 0) return@withContext sessionId
            }
            null
        } catch (e: Exception) {
            Log.e("DumpSysSessionProvider", "Error executing dumpsys media.audio_flinger", e)
            null
        }
    }
}
