package com.jadoo.amp.audio

/**
 * Which SBC Enhancement the user wants. Both are kept because they trade
 * differently and the choice is by ear:
 *
 *  - [Classic] is the pre-1.6.4 curve: a plain tonal push of the top two
 *    bands (+4.5 dB at 10 kHz, +7 dB at 16 kHz) with small mid cuts. No
 *    dynamics stage, no headroom trim. Brighter and more open.
 *  - [New] is SbcEngine's encoder-input conditioning: top octave cut, HF
 *    crest control on an MBC band, -1 dB decoder-overshoot trim.
 */
enum class SbcStyle(val displayName: String) {
    Classic("Classic"),
    New("New");

    companion object {
        fun fromName(name: String?): SbcStyle = entries.firstOrNull { it.name == name } ?: New
    }
}
