package com.jadoo.amp.audio

import androidx.annotation.StringRes
import com.jadoo.amp.R

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

    // Localized counterparts of displayName / tagline / description: resolve
    // with context.getString() (or stringResource) where the text is shown.
    // The String properties above stay English for logs and tests.
    @get:StringRes
    val displayNameRes: Int
        get() = when (this) {
            Off         -> R.string.aud_surround_off_name
            Traditional -> R.string.aud_surround_traditional_name
            Front       -> R.string.aud_surround_front_name
            Wide        -> R.string.aud_surround_wide_name
        }

    @get:StringRes
    val taglineRes: Int
        get() = when (this) {
            Off         -> R.string.aud_surround_off_tagline
            Traditional -> R.string.aud_surround_traditional_tagline
            Front       -> R.string.aud_surround_front_tagline
            Wide        -> R.string.aud_surround_wide_tagline
        }

    @get:StringRes
    val descriptionRes: Int
        get() = when (this) {
            Off         -> R.string.aud_surround_off_desc
            Traditional -> R.string.aud_surround_traditional_desc
            Front       -> R.string.aud_surround_front_desc
            Wide        -> R.string.aud_surround_wide_desc
        }

    val description: String
        get() = when (this) {
            Off         -> "Spatial processing disabled. Audio passes through unmodified."
            Traditional -> "Smooth stereo tilt. Subtle high-frequency widening with vocals locked center. Fatigue-free for long listening."
            Front       -> "Stronger stereo expansion. Clearer instrument separation with forward projection. Vocals stay centered."
            Wide        -> "Widest soundstage. Maximum high-frequency spread for movies, gaming, and ambient music."
        }
}
