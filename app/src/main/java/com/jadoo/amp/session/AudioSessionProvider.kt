package com.jadoo.amp.session

interface AudioSessionProvider {
    suspend fun getActiveSessionId(): Int?
}
