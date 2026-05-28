package com.jadoo.amp.audio

enum class SurroundMode {
    Off, Traditional, Front, Wide;

    val displayName: String
        get() = when (this) {
            Off         -> "Off"
            Traditional -> "Traditional"
            Front       -> "Front Stage"
            Wide        -> "Ultra Wide"
        }

    val tagline: String
        get() = when (this) {
            Off         -> "No spatial processing"
            Traditional -> "Natural stereo width"
            Front       -> "Front projection"
            Wide        -> "Immersive 180° sound"
        }

    val description: String
        get() = when (this) {
            Off         -> "Spatial processing disabled. Audio passes through unmodified."
            Traditional -> "DTS-style smooth stereo tilt. Subtle high-frequency widening with vocals locked center. Fatigue-free for long listening."
            Front       -> "Enhanced DTS stereo expansion. Clearer instrument separation with forward projection. Vocals stay centered."
            Wide        -> "Full DTS immersive soundstage. Maximum high-frequency spread for movies, gaming, and ambient music."
        }
}
