package com.jadoo.amp.session

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DumpSysSessionProvider : AudioSessionProvider {

    override suspend fun getActiveSessionId(): SessionInfo? = withContext(Dispatchers.IO) {
        try {
            parsePlayingMediaSession(runDumpsys("media_session"))
                ?: parseGlobalSessionRefs(runDumpsys("media.audio_flinger"))
        } catch (e: Exception) {
            Log.e("DumpSysSessionProvider", "Error executing dumpsys", e)
            null
        }
    }

    private fun runDumpsys(service: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("dumpsys", service))
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    /**
     * "Sessions Stack" lists every active MediaSession together with its
     * owning package and PlaybackState. Returns the package of the session
     * whose state is STATE_PLAYING (3) — i.e. what's actually audible right
     * now — unlike the audio_flinger effect-chain table, which can include
     * stale or background sessions that aren't currently producing sound.
     */
    internal fun parsePlayingMediaSession(output: String): SessionInfo? {
        var currentPackage: String? = null
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("package=") ->
                    currentPackage = line.removePrefix("package=").trim()
                line.startsWith("state=PlaybackState") -> {
                    // e.g. "state=PlaybackState {state=PLAYING(3), position=..."
                    val state = Regex("""\{state=\w+\((\d+)\)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val pkg = currentPackage
                    if (state == 3 && pkg != null && pkg != "com.jadoo.amp") {
                        return SessionInfo(0, pkg)
                    }
                    currentPackage = null
                }
            }
        }
        return null
    }

    /**
     * Fallback: "Global session refs:" is a table listing every audio session
     * that currently has an AudioEffect attached, together with the owning
     * package:
     *   session  cnt     pid    uid  name
     *      6641    1   30808  10469  com.spotify.music
     * Returns the first entry that isn't our own session.
     */
    internal fun parseGlobalSessionRefs(output: String): SessionInfo? {
        val rowRegex = Regex("""^\s*(\d+)\s+\d+\s+\d+\s+\d+\s+(\S+)\s*$""")
        val lines = output.lineSequence().toList()
        val startIdx = lines.indexOfFirst { it.contains("Global session refs", ignoreCase = true) }
        if (startIdx < 0) return null

        for (line in lines.drop(startIdx + 1).take(50)) {
            val match = rowRegex.find(line) ?: continue
            val sessionId = match.groupValues[1].toIntOrNull() ?: continue
            val pkg = match.groupValues[2]
            if (sessionId <= 0 || pkg == "com.jadoo.amp") continue
            return SessionInfo(sessionId, pkg)
        }
        return null
    }
}
