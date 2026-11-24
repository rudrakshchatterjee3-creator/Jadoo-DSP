package com.jadoo.amp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * The taste profile's built-in reference music: one track, looped, so every
 * listener and every retake judges A and B on the same material.
 *
 * The track is an asset, `assets/reference/taste_reference.(ogg|m4a|mp3|wav)`.
 * One ships: a 50 s excerpt of "Come Inside" by snowflake (ccMixter,
 * CC BY 3.0, commercial use allowed with credit). The credit is in the taste
 * screen's strings and the README; keep both if the track stays. Remove the
 * file and the taste profile offers only the user's own music. It plays through the normal media stream, so JadOO's
 * chain (and the A/B preview) processes it like any other music, and it takes
 * transient audio focus so another music app pauses meanwhile.
 */
class ReferenceTrack(private val context: Context) {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun start(): Boolean {
        val name = assetName(context) ?: return false
        if (player != null) { resume(); return true }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
                .also { am.requestAudioFocus(it) }
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                context.assets.openFd("reference/$name").use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.w("ReferenceTrack", "start failed: ${e.message}")
            stop()
            false
        }
    }

    fun pause() { runCatching { player?.pause() } }

    fun resume() { runCatching { player?.start() } }

    fun stop() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        focus?.let { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focus = null
    }

    companion object {
        private val EXTENSIONS = listOf("ogg", "m4a", "mp3", "wav")

        /** The bundled track's file name, or null when the app ships none. */
        fun assetName(context: Context): String? = runCatching {
            context.assets.list("reference")?.firstOrNull { f -> EXTENSIONS.any { f == "taste_reference.$it" } }
        }.getOrNull()
    }
}
