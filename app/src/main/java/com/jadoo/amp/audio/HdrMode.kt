package com.jadoo.amp.audio

import androidx.annotation.StringRes
import com.jadoo.amp.R

enum class HdrMode(val displayName: String) {
    Pure("Pure"),
    Restoration("Restoration");

    /** Localized label; [displayName] stays English for logs and tests. */
    @get:StringRes
    val displayNameRes: Int
        get() = when (this) {
            Pure        -> R.string.aud_hdr_pure
            Restoration -> R.string.aud_hdr_restoration
        }
}
