package com.jadoo.amp.audio

import androidx.annotation.StringRes
import com.jadoo.amp.R

enum class DbfbMode(val displayName: String) {
    Off("Off"),
    Normal("Normal"),
    High("High");

    /** Localized label; [displayName] stays English for logs and tests. */
    @get:StringRes
    val displayNameRes: Int
        get() = when (this) {
            Off    -> R.string.aud_dbfb_off
            Normal -> R.string.aud_dbfb_normal
            High   -> R.string.aud_dbfb_high
        }
}
