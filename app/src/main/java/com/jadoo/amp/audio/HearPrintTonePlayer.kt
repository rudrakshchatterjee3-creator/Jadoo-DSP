package com.jadoo.amp.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Plays HearPrint's test tones: pulsed sine bursts in both ears at once, float PCM so
 * levels down to -100 dBFS keep their shape until the system mixer.
 *
 * Pulses are 220 ms on / 160 ms off with 25 ms raised-cosine ramps: pulsed
 * tones are easier to tell apart from tinnitus than steady ones, and the
 * ramps keep each onset free of clicks (a click is broadband, it would be
 * heard before the tone itself). The level is read once at each pulse onset,
 * so a level change never lands inside a pulse.
 *
 * The synthesis loop runs on its own thread and allocates nothing after
 * [start]. Plays through the normal media stream with no new permission.
 */
class HearPrintTonePlayer(private val sampleRate: Int = 48_000) {

    @Volatile var frequencyHz: Float = 1000f
    /** Tone level in dBFS, read at each pulse onset. */
    @Volatile var levelDb: Float = HearPrint.START_LEVEL_DB
    /** False = silence between steps (still running, so no start-up gap). */
    @Volatile var sounding: Boolean = false

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start(): Boolean {
        if (running) return true
        val chunk = sampleRate / 100 // 10 ms
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuf, chunk * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e("HearPrint", "AudioTrack create failed", e)
            return false
        }
        track = t
        running = true
        t.play()
        thread = Thread({ loop(t, chunk) }, "HearPrintTone").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun loop(t: AudioTrack, chunk: Int) {
        val buf = FloatArray(chunk * 2)
        val onSamples = (0.220f * sampleRate).toInt()
        val period = (0.380f * sampleRate).toInt()
        val ramp = (0.025f * sampleRate).toInt()
        var pos = 0             // position inside the current pulse period
        var phase = 0.0
        var amp = 0f
        var pulseFreq = frequencyHz
        var pulseOn = false
        while (running) {
            for (i in 0 until chunk) {
                if (pos == 0) {
                    // Pulse onset: latch level and frequency for this pulse.
                    pulseOn = sounding
                    pulseFreq = frequencyHz
                    amp = 10f.pow(levelDb / 20f)
                    phase = 0.0
                }
                var s = 0f
                if (pulseOn && pos < onSamples) {
                    val env = when {
                        pos < ramp -> 0.5f - 0.5f * cos(PI * pos / ramp).toFloat()
                        pos > onSamples - ramp -> 0.5f - 0.5f * cos(PI * (onSamples - pos) / ramp).toFloat()
                        else -> 1f
                    }
                    s = amp * env * sin(phase).toFloat()
                    phase += 2.0 * PI * pulseFreq / sampleRate
                }
                buf[2 * i] = s
                buf[2 * i + 1] = s
                pos = (pos + 1) % period
            }
            try {
                t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Log.w("HearPrint", "tone write failed: ${e.message}")
                break
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track?.let {
            try { it.pause(); it.flush(); it.release() } catch (_: Exception) {}
        }
        track = null
    }
}
