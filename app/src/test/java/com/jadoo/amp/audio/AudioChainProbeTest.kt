package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChainProbeTest {

    private val audioFlingerDump = """
        Output thread 0x7a2f8c4b40, name AudioOut_D, tid 1578, type 0 (MIXER):
          I/O handle: 13
          Standby: no
          Sample rate: 48000 Hz
          HAL frame count: 1920
          HAL format: 0x1 (AUDIO_FORMAT_PCM_16_BIT)
          Channel count: 2
          Processing format: 0x5 (AUDIO_FORMAT_PCM_FLOAT)
          Output devices: 0x80 (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)
          AudioStreamOut: 0x7b1c flags 0x8 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)
          1 Tracks of which 1 are active
            Type     Id Active Client(pid/uid) Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT
                     42    yes   12345/  10234     129      25 A  0x000 00000001 00000003  44100  3  1  2
          Effect Chains: 1
            1 effects for session 129
            Effect ID 77:
              - TYPE: 7261676f-6d75-7369-6364-28e2fd3ac39e
              - Name: Dynamics Processing

        Output thread 0x7a2f8c5000, name AudioOut_15, tid 1600, type 4 (OFFLOAD):
          Standby: yes
          Sample rate: 44100 Hz
          HAL format: 0x1000000 (AUDIO_FORMAT_MP3)
          Output devices: 0x2 (AUDIO_DEVICE_OUT_SPEAKER)
          AudioStreamOut: 0x7b1d flags 0x31 (AUDIO_OUTPUT_FLAG_DIRECT|AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD)

        Input thread 0x7a2f8c6000, name AudioIn_1, tid 1700, type 3 (RECORD):
          Sample rate: 16000 Hz
    """.trimIndent()

    @Test
    fun parsesOutputThreadsOnly() {
        val threads = AudioChainProbe.parseAudioFlinger(audioFlingerDump)
        assertEquals(2, threads.size)
        val mixer = threads[0]
        assertEquals("AudioOut_D", mixer.name)
        assertEquals("MIXER", mixer.type)
        assertFalse(mixer.standby)
        assertEquals(48000, mixer.sampleRate)
        assertEquals("AUDIO_FORMAT_PCM_16_BIT", mixer.halFormat)
        assertEquals("AUDIO_FORMAT_PCM_FLOAT", mixer.processingFormat)
        assertEquals(listOf("BLUETOOTH_A2DP"), mixer.devices)
        assertEquals(listOf("DEEP_BUFFER"), mixer.flags)
        assertTrue(mixer.hasDynamicsProcessing)
        assertEquals(setOf(129), mixer.effectSessions)
        assertFalse(mixer.bypassesSoftwareEffects)

        val offload = threads[1]
        assertTrue(offload.standby)
        assertTrue(offload.bypassesSoftwareEffects)
        assertEquals("Hardware offload", AudioChainProbe.describePath(offload))
    }

    @Test
    fun parsesTrackRowWithPaddedUid() {
        val track = AudioChainProbe.parseAudioFlinger(audioFlingerDump)[0].tracks.single()
        assertEquals(10234, track.uid)
        assertEquals(129, track.sessionId)
        assertTrue(track.active)
        assertEquals(0x1, track.formatCode)
        assertEquals(0x3, track.channelMask)
        assertEquals(44100, track.sampleRate)
    }

    @Test
    fun parsesRealAndroid16TrackRow() {
        // Apple Music on the iQOO I2221, float output, with the latency column.
        val row = "           1297    yes   14637/  10368   10257    1318 A  0x000 00000005 00000003  44100  3   1  0  " +
            "-5.9     0     0     0         -5.9      false 01B97C08  14144   14144 A         0        0      false        false  461.19 t"
        val track = AudioChainProbe.parseTrackRow(row)!!
        assertEquals(10368, track.uid)
        assertEquals(10257, track.sessionId)
        assertEquals(0x5, track.formatCode)
        assertEquals(44100, track.sampleRate)
        assertEquals(461.19f, track.latencyMs!!, 0.01f)
        assertEquals("32-bit float", AudioChainProbe.describeBitDepth(track.formatCode))
        assertEquals("Stereo (2.0)", AudioChainProbe.describeChannels(track.channelMask))
    }

    @Test
    fun parsesNowPlayingForPackage() {
        val dump = """
            package=com.android.server.telecom
              metadata: null
              package=com.apple.android.music
              active=true
              state=PlaybackState {state=PLAYING(3), position=266503}
              metadata: size=44, description=Why Worry?, Dire Straits, Brothers In Arms (40th Anniversary)
        """.trimIndent()
        val np = AudioChainProbe.parseNowPlaying(dump, "com.apple.android.music")!!
        assertEquals("Why Worry?", np.title)
        assertEquals("Dire Straits", np.artist)
        assertNull(AudioChainProbe.parseNowPlaying(dump, "com.spotify.music"))
    }

    @Test
    fun selectsAppTrackByUidAndReportsResampling() {
        val threads = AudioChainProbe.parseAudioFlinger(audioFlingerDump)
        val (thread, track) = AudioChainProbe.selectPath(threads, uid = 10234, sessionId = 0)!!
        assertEquals("AudioOut_D", thread.name)
        assertEquals(44100, track!!.sampleRate)
    }

    @Test
    fun fallsBackToAwakeThreadWhenNoTracks() {
        val threads = AudioChainProbe.parseAudioFlinger(audioFlingerDump).map { it.copy(tracks = emptyList()) }
        val (thread, track) = AudioChainProbe.selectPath(threads, uid = 1, sessionId = null)!!
        assertEquals("AudioOut_D", thread.name)
        assertNull(track)
    }

    @Test
    fun parsesLdacCodecForActiveDevice() {
        val dump = """
            A2DP State:
              mActiveDevice: AA:BB:CC:DD:EE:FF
              mDevice: 11:22:33:44:55:66
                mCodecStatus: {mCodecConfig:{codecName:AAC,mCodecType:AAC,mSampleRate:44100(0x1),mBitsPerSample:16(0x1),mChannelMode:STEREO(0x2),mCodecSpecific1:0},mCodecsLocalCapabilities:[]}
              mDevice: AA:BB:CC:DD:EE:FF
                mCodecStatus: {mCodecConfig:{codecName:LDAC,mCodecType:LDAC,mSampleRate:96000(0x8),mBitsPerSample:24(0x2),mChannelMode:STEREO(0x2),mCodecSpecific1:1000},mCodecsLocalCapabilities:[]}
        """.trimIndent()
        val codec = AudioChainProbe.parseBluetoothCodec(dump)
        assertNotNull(codec)
        assertEquals("LDAC", codec!!.codec)
        assertEquals(96000, codec.sampleRate)
        assertEquals(24, codec.bitsPerSample)
        assertEquals("Stereo", codec.channelMode)
        assertEquals("990 kbps", codec.ldacQuality)
    }

    // Android 16 layout (iQOO I2221): one state machine per bonded headset,
    // config on its own line, history rows embedding old configs.
    private fun a2dpStateMachine(address: String, state: String, codec: String) = """
          === A2dpStateMachine for $address ===
            mConnectionState: $state, mLastConnectionState: STATE_CONNECTING
            mIsPlaying: false
            mCodecConfig: {codecName:$codec,mCodecType:4,mCodecPriority:6001,mSampleRate:0x8(96000),mBitsPerSample:0x2(24),mChannelMode:0x2(STEREO),mCodecSpecific1:1003,mCodecSpecific2:0}
            StateMachineLog:
               rec[0]: time=10-25 01:02:08.606 obj=A2dpStackEvent {codecStatus:{mCodecConfig:{codecName:SBC,mCodecType:0,mSampleRate:0x1(44100)}}}
    """

    @Test
    fun ignoresDisconnectedHeadsets() {
        val dump = "Profile: A2dpService\n  mActiveDevice: null\n" +
            a2dpStateMachine("E0:E2:58:41:C9:94", "STATE_DISCONNECTED", "SBC") +
            a2dpStateMachine("CC:15:31:E8:C8:48", "STATE_DISCONNECTED", "LDAC")
        assertNull(AudioChainProbe.parseBluetoothCodec(dump))
    }

    @Test
    fun picksConnectedHeadsetOwnConfig() {
        val dump = "Profile: A2dpService\n  mActiveDevice: CC:15:31:E8:C8:48\n" +
            a2dpStateMachine("E0:E2:58:41:C9:94", "STATE_DISCONNECTED", "AAC") +
            a2dpStateMachine("CC:15:31:E8:C8:48", "STATE_CONNECTED", "LDAC")
        val codec = AudioChainProbe.parseBluetoothCodec(dump)!!
        assertEquals("LDAC", codec.codec)
        assertEquals(96000, codec.sampleRate)
        assertEquals(24, codec.bitsPerSample)
        assertEquals("Adaptive", codec.ldacQuality)
    }

    @Test
    fun skipsLocalLogHistoryRows() {
        val dump = """
            Output thread 0xb400006dcd1e3e40, name AudioOut_D, tid 3696, type 0 (MIXER):
              Standby: yes
              Sample rate: 48000 Hz
              0 Tracks
                Type     Id Active Client(pid/uid) Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT
              Local log:
                                                             Type     Id Active Client(pid/uid) Session Port Id S  Flags   Format Chn mask  SRate ST
               10-25 12:07:27.476 AT::add       (0xb400006e9d1f2e00)        1288     no   16622/  10370   10177    1307 r  0x000 00000001 00000003  44100  3
        """.trimIndent()
        assertTrue(AudioChainProbe.parseAudioFlinger(dump).single().tracks.isEmpty())
    }

    @Test
    fun parsesHexFirstCodecFields() {
        val codec = AudioChainProbe.parseCodecConfig(
            "codecName:SBC,mCodecType:0,mSampleRate:0x2,mBitsPerSample:0x1,mChannelMode:0x2"
        )!!
        assertEquals("SBC", codec.codec)
        assertEquals(48000, codec.sampleRate)
        assertEquals(16, codec.bitsPerSample)
        assertEquals("Stereo", codec.channelMode)
        assertNull(codec.ldacQuality)
    }

    @Test
    fun describesFormats() {
        assertEquals("32-bit float", AudioChainProbe.describeFormat(0x5))
        assertEquals("Dolby Atmos E-AC-3 (passthrough)", AudioChainProbe.describeFormat(0x0A000001))
        assertEquals("16-bit PCM", AudioChainProbe.describeFormatName("AUDIO_FORMAT_PCM_16_BIT"))
        assertEquals("44.1 kHz", AudioChainProbe.formatRate(44100))
        assertEquals("48 kHz", AudioChainProbe.formatRate(48000))
    }
}
