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
            Traditional -> "Gentle widening that preserves center imaging and vocal focus. Ideal for long listening sessions."
            Front       -> "Mid-strength widening aimed at the forward arc. Makes headphones sound like front-firing speakers."
            Wide        -> "Maximum spatial spread. Creates an enveloping 180° virtual soundstage ideal for movies and ambient music."
        }
}
