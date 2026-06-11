package com.jadoo.amp.session

data class SessionInfo(val sessionId: Int, val packageName: String?)

interface AudioSessionProvider {
    suspend fun getActiveSessionId(): SessionInfo?
}
