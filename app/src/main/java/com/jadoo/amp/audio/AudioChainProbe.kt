package com.jadoo.amp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Reads the live audio path the active app's sound takes through Android,
 * for the Live Chain screen: what format the app hands the mixer, which
 * AudioFlinger output thread mixes it (and at what rate/format), whether
 * JadOO's DynamicsProcessing is actually on that thread, and what the
 * Bluetooth codec is doing at the far end.
 *
 * Almost all of it comes from `dumpsys media.audio_flinger` and
 * `dumpsys bluetooth_manager`, so it needs the DUMP permission (the same
 * optional adb grant DumpSysSessionProvider already uses). Without it the
 * snapshot only carries what public APIs expose: the platform mixer rate,
 * buffer size and Spatializer state.
 *
 * dumpsys output is not a stable API — the column layout of the track table
 * has changed across releases — so every parser here matches by pattern
 * instead of by column position, and returns null/empty instead of guessing
 * when a pattern isn't there.
 */
object AudioChainProbe {

    private const val TAG = "AudioChainProbe"

    /** DynamicsProcessing's effect TYPE UUID, as printed in the effect-chain dump. */
    private const val DYNAMICS_PROCESSING_TYPE = "7261676f-6d75-7369-6364-28e2fd3ac39e"

    data class TrackInfo(
        val uid: Int,
        val sessionId: Int,
        val active: Boolean,
        val formatCode: Int,
        val channelMask: Int,
        val sampleRate: Int,
        /** AudioFlinger's own estimate for this track, app write to speaker. */
        val latencyMs: Float?
    )

    data class OutputThreadInfo(
        val name: String,
        /** AudioFlinger thread type: MIXER, DIRECT, OFFLOAD, SPATIALIZER, BIT_PERFECT, ... */
        val type: String,
        val standby: Boolean,
        val sampleRate: Int?,
        val halFrameCount: Int?,
        val halFormat: String?,
        val processingFormat: String?,
        val channelCount: Int?,
        /** AUDIO_DEVICE_OUT_* names with the prefix stripped, e.g. BLUETOOTH_A2DP. */
        val devices: List<String>,
        /** AUDIO_OUTPUT_FLAG_* names with the prefix stripped, e.g. DEEP_BUFFER. */
        val flags: List<String>,
        val tracks: List<TrackInfo>,
        val effectSessions: Set<Int>,
        val hasDynamicsProcessing: Boolean
    ) {
        /** Paths where Android does not run the software mixer's effect chains. */
        val bypassesSoftwareEffects: Boolean
            get() = type == "OFFLOAD" || type == "DIRECT" || type == "BIT_PERFECT" ||
                "COMPRESS_OFFLOAD" in flags || "DIRECT" in flags || "BIT_PERFECT" in flags
    }

    data class BluetoothCodecInfo(
        val codec: String,
        val sampleRate: Int?,
        val bitsPerSample: Int?,
        val channelMode: String?,
        /** LDAC only: the quality mode picked in Developer options. */
        val ldacQuality: String?
    )

    data class NowPlaying(val title: String, val artist: String?)

    data class SpatialAudioInfo(
        val available: Boolean,
        val enabled: Boolean,
        val headTrackerAvailable: Boolean
    )

    data class ChainSnapshot(
        val dumpAvailable: Boolean,
        val platformSampleRate: Int?,
        val framesPerBuffer: Int?,
        val thread: OutputThreadInfo?,
        val track: TrackInfo?,
        val bluetoothCodec: BluetoothCodecInfo?,
        val spatialAudio: SpatialAudioInfo?,
        val nowPlaying: NowPlaying?,
        /** The app actually producing the stream — see [probe] for how it is resolved. */
        val sourcePackage: String?,
        val sourceLabel: String?
    ) {
        /** True when the app's track is resampled by AudioFlinger before mixing. */
        val resampled: Boolean
            get() {
                val trackRate = track?.sampleRate ?: return false
                val mixRate = thread?.sampleRate ?: return false
                return trackRate > 0 && trackRate != mixRate
            }
    }

    // ── Live probe ──────────────────────────────────────────────────────

    /**
     * One snapshot of the chain. [packageName] is only a hint (the service's
     * idea of the active app, which an EQ app's effect-session broadcast can
     * hijack): the source is resolved from the media session that is
     * actually PLAYING, then confirmed against the uid that owns the active
     * track, so labels always describe the stream the numbers come from.
     *
     * [bluetoothCodec] is passed in rather than
     * read here because `dumpsys bluetooth_manager` is large (it carries the
     * snoop-log summary) and the codec rarely changes — the caller polls it
     * on a much slower cadence than the mixer path.
     */
    suspend fun probe(
        context: Context,
        packageName: String?,
        sessionId: Int?,
        dumpAvailable: Boolean,
        bluetoothCodec: BluetoothCodecInfo?
    ): ChainSnapshot = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val platformRate = try {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        } catch (_: Exception) { null }
        val framesPerBuffer = try {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        } catch (_: Exception) { null }

        var thread: OutputThreadInfo? = null
        var track: TrackInfo? = null
        var nowPlaying: NowPlaying? = null
        var sourcePackage = packageName
        if (dumpAvailable) {
            val sessions = try { runDumpsys("media_session") } catch (_: Exception) { "" }
            val hint = parsePlayingPackage(sessions) ?: packageName
            val threads = parseAudioFlinger(runDumpsys("media.audio_flinger"))
            selectPath(threads, uidOf(context, hint), sessionId)?.let { (t, tr) -> thread = t; track = tr }
            sourcePackage = track?.let { packageForUid(context, it.uid, hint) } ?: hint
            nowPlaying = parseNowPlaying(sessions, sourcePackage)
        }

        ChainSnapshot(
            dumpAvailable = dumpAvailable,
            platformSampleRate = platformRate,
            framesPerBuffer = framesPerBuffer,
            thread = thread,
            track = track,
            bluetoothCodec = bluetoothCodec,
            spatialAudio = readSpatialAudio(am),
            nowPlaying = nowPlaying,
            sourcePackage = sourcePackage,
            sourceLabel = sourcePackage?.let { appLabel(context, it) }
        )
    }

    private fun uidOf(context: Context, pkg: String?): Int? = pkg?.let {
        try { context.packageManager.getPackageUid(it, 0) } catch (_: Exception) { null }
    }

    /** The package behind [uid]; [preferred] wins when several share the uid. */
    private fun packageForUid(context: Context, uid: Int, preferred: String?): String? {
        val packages = try { context.packageManager.getPackagesForUid(uid) } catch (_: Exception) { null }
        if (packages.isNullOrEmpty()) return null
        return packages.firstOrNull { it == preferred } ?: packages.first()
    }

    private fun appLabel(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { null }

    suspend fun probeBluetoothCodec(): BluetoothCodecInfo? = withContext(Dispatchers.IO) {
        try { parseBluetoothCodec(runDumpsys("bluetooth_manager")) } catch (e: Exception) {
            Log.w(TAG, "bluetooth_manager dump failed", e)
            null
        }
    }

    private fun readSpatialAudio(am: AudioManager): SpatialAudioInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return null
        return try {
            val spatializer = am.spatializer
            // Spatializer.isAvailable answers for the CURRENT route, so it
            // flips with the output device — exactly what the chain needs.
            val canSpatializeMusic = spatializer.canBeSpatialized(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).setSampleRate(48_000).build()
            )
            SpatialAudioInfo(
                available = spatializer.isAvailable,
                enabled = spatializer.isEnabled && canSpatializeMusic,
                headTrackerAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    spatializer.isHeadTrackerAvailable
            )
        } catch (_: Exception) { null }
    }

    private fun runDumpsys(service: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("dumpsys", service))
        // The timeout has to fire WHILE reading: readText() only returns at
        // end-of-stream, so checking waitFor() afterwards (as this used to)
        // could never interrupt a dumpsys that hung mid-write. The watchdog
        // kills it after 3 s, which closes the pipe and ends the read.
        val watchdog = Thread {
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    Log.w(TAG, "dumpsys $service timed out — killed")
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        return try {
            process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            watchdog.interrupt()
        }
    }

    // ── audio_flinger parsing ───────────────────────────────────────────

    private val threadHeader = Regex("""^(\w+ )?thread 0x[0-9a-fA-F]+""", RegexOption.IGNORE_CASE)
    private val threadType = Regex("""type \d+ \((\w+)\)""")
    private val headerName = Regex("""name (\S+?),""")
    private val threadNameLine = Regex("""^Thread name:\s*(\S+)""")
    private val halFrameCountLine = Regex("""^HAL frame count:\s*(\d+)""")
    private val sampleRateLine = Regex("""^Sample rate:\s*(\d+)""")
    private val halFormatLine = Regex("""^HAL format:\s*0x[0-9a-fA-F]+\s*\((\w+)\)""")
    private val processingFormatLine = Regex("""^Processing format:\s*0x[0-9a-fA-F]+\s*\((\w+)\)""")
    private val channelCountLine = Regex("""^Channel count:\s*(\d+)""")
    private val standbyLine = Regex("""^Standby:\s*(yes|no|true|false)""")
    private val deviceName = Regex("""AUDIO_DEVICE_OUT_([A-Z0-9_]+)""")
    private val flagName = Regex("""AUDIO_OUTPUT_FLAG_([A-Z0-9_]+)""")
    private val effectSessionLine = Regex("""effects? for session (\d+)""")
    private val logTimestamp = Regex("""^\d{2}-\d{2} \d{2}:\d{2}""")
    // Track rows: "...  yes  12345/  10234   129   25 A  0x000 00000001 00000003  44100 ..."
    // pid/uid is printed with %7u/%7u, so the uid can carry leading spaces.
    private val trackClient = Regex("""(\d+)\s*/\s*(\d+)""")
    private val trackTail = Regex(
        """^\s+(\d+)\s+\d+.*?\s0x[0-9a-fA-F]+\s+([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})\s+(\d+)"""
    )
    // Last column, e.g. "461.19 t" (t = from timestamps, k = from the kernel).
    private val trackLatency = Regex("""(\d+\.\d+)\s+[a-z]\s*$""")

    fun parseAudioFlinger(output: String): List<OutputThreadInfo> {
        val blocks = mutableListOf<MutableList<String>>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (threadHeader.containsMatchIn(line) && threadHeader.find(line)?.range?.first == 0) {
                blocks += mutableListOf(line)
            } else {
                blocks.lastOrNull()?.add(raw)
            }
        }
        return blocks.filter { it.first().startsWith("Output", ignoreCase = true) ||
            it.first().contains("MMAP_PLAYBACK") }
            .map { parseThreadBlock(it) }
    }

    private fun parseThreadBlock(lines: List<String>): OutputThreadInfo {
        val header = lines.first()
        var name = headerName.find(header)?.groupValues?.get(1)
        val type = threadType.find(header)?.groupValues?.get(1) ?: "UNKNOWN"
        var standby = false
        var sampleRate: Int? = null
        var halFrameCount: Int? = null
        var halFormat: String? = null
        var processingFormat: String? = null
        var channelCount: Int? = null
        val devices = linkedSetOf<String>()
        val flags = linkedSetOf<String>()
        val tracks = mutableListOf<TrackInfo>()
        val effectSessions = mutableSetOf<Int>()
        var hasDp = false
        var inTrackTable = false

        for (raw in lines.drop(1)) {
            val line = raw.trim()
            if (line.isEmpty()) { inTrackTable = false; continue }
            if (name == null) threadNameLine.find(line)?.let { name = it.groupValues[1] }
            if (sampleRate == null) sampleRateLine.find(line)?.let { sampleRate = it.groupValues[1].toIntOrNull() }
            if (halFrameCount == null) halFrameCountLine.find(line)?.let { halFrameCount = it.groupValues[1].toIntOrNull() }
            if (halFormat == null) halFormatLine.find(line)?.let { halFormat = it.groupValues[1] }
            if (processingFormat == null) processingFormatLine.find(line)?.let { processingFormat = it.groupValues[1] }
            if (channelCount == null) channelCountLine.find(line)?.let { channelCount = it.groupValues[1].toIntOrNull() }
            standbyLine.find(line)?.let { standby = it.groupValues[1] == "yes" || it.groupValues[1] == "true" }
            if (line.startsWith("Output device", ignoreCase = true)) {
                deviceName.findAll(line).forEach { devices += it.groupValues[1] }
            }
            if (line.contains("flags", ignoreCase = true)) {
                flagName.findAll(line).forEach { flags += it.groupValues[1] }
            }
            effectSessionLine.find(line)?.let { m -> m.groupValues[1].toIntOrNull()?.let { effectSessions += it } }
            if (line.contains(DYNAMICS_PROCESSING_TYPE, ignoreCase = true) ||
                line.contains("Dynamics Processing", ignoreCase = true)) hasDp = true

            // The thread's "Local log" repeats the same table header over
            // timestamped history rows (AT::add, removeTrack_l, ...) — those
            // are past tracks, not live ones.
            if (line.startsWith("Local log", ignoreCase = true)) { inTrackTable = false; continue }
            if (line.contains("SRate") && (line.contains("Session") || line.contains("Client"))) {
                inTrackTable = true
                continue
            }
            if (inTrackTable && !logTimestamp.containsMatchIn(line)) parseTrackRow(raw)?.let { tracks += it }
        }

        return OutputThreadInfo(
            name = name ?: "unknown",
            type = type,
            standby = standby,
            sampleRate = sampleRate,
            halFrameCount = halFrameCount,
            halFormat = halFormat,
            processingFormat = processingFormat,
            channelCount = channelCount,
            devices = devices.toList(),
            flags = flags.toList(),
            tracks = tracks,
            effectSessions = effectSessions,
            hasDynamicsProcessing = hasDp
        )
    }

    internal fun parseTrackRow(row: String): TrackInfo? {
        val client = trackClient.find(row) ?: return null
        val uid = client.groupValues[2].toIntOrNull() ?: return null
        val tail = trackTail.find(row.substring(client.range.last + 1)) ?: return null
        val rate = tail.groupValues[4].toIntOrNull()?.takeIf { it in 4_000..768_000 } ?: return null
        val active = Regex("""\byes\b""").containsMatchIn(row.substring(0, client.range.first))
        return TrackInfo(
            uid = uid,
            sessionId = tail.groupValues[1].toIntOrNull() ?: 0,
            active = active,
            formatCode = tail.groupValues[2].toLong(16).toInt(),
            channelMask = tail.groupValues[3].toLong(16).toInt(),
            sampleRate = rate,
            latencyMs = trackLatency.find(row)?.groupValues?.get(1)?.toFloatOrNull()
        )
    }

    /**
     * Picks the thread + track the active app is playing through: its own
     * active track by uid first, then by session, then any active track on
     * an awake output, then just the first awake output.
     */
    fun selectPath(
        threads: List<OutputThreadInfo>,
        uid: Int?,
        sessionId: Int?
    ): Pair<OutputThreadInfo, TrackInfo?>? {
        fun find(predicate: (TrackInfo) -> Boolean): Pair<OutputThreadInfo, TrackInfo>? {
            for (t in threads) t.tracks.firstOrNull(predicate)?.let { return t to it }
            return null
        }
        if (uid != null) find { it.uid == uid && it.active }?.let { return it }
        if (sessionId != null && sessionId > 0) find { it.sessionId == sessionId && it.active }?.let { return it }
        if (uid != null) find { it.uid == uid }?.let { return it }
        find { it.active }?.let { return it }
        return threads.firstOrNull { !it.standby }?.let { it to null }
    }

    // ── media_session parsing ───────────────────────────────────────────

    private val metadataLine = Regex("""metadata:\s*size=\d+,\s*description=(.*)""")

    /**
     * Title/artist of [packageName]'s media session. The metadata line is
     * MediaDescription.toString(), "title, subtitle, description" — so a
     * comma inside a title splits it early. Good enough for a readout.
     */
    fun parseNowPlaying(output: String, packageName: String?): NowPlaying? {
        var currentPackage: String? = null
        var playing = false
        var fallback: NowPlaying? = null
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("package=") -> {
                    currentPackage = line.removePrefix("package=").trim()
                    playing = false
                }
                line.startsWith("state=PlaybackState") -> playing = line.contains("state=PLAYING")
                else -> metadataLine.find(line)?.let { m ->
                    if (packageName != null && currentPackage != packageName) return@let
                    val parts = m.groupValues[1].split(", ")
                    val title = parts.firstOrNull()?.takeIf { it.isNotBlank() && it != "null" } ?: return@let
                    val info = NowPlaying(title, parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" })
                    if (playing) return info
                    if (fallback == null) fallback = info
                }
            }
        }
        return fallback
    }

    /** First media session in PLAYING state, ignoring JadOO's own. */
    fun parsePlayingPackage(output: String): String? {
        var currentPackage: String? = null
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("package=")) currentPackage = line.removePrefix("package=").trim()
            else if (line.startsWith("state=PlaybackState") && line.contains("state=PLAYING") &&
                currentPackage != null && currentPackage != "com.jadoo.amp") return currentPackage
        }
        return null
    }

    // ── bluetooth_manager parsing ───────────────────────────────────────

    private val activeDeviceLine = Regex("""mActiveDevice:\s*([0-9A-Fa-f:]{17}|\S+)""")
    private val stateMachineDevice = Regex("""(?:A2dpStateMachine for|mDevice:)\s*([0-9A-Fa-f:]{17})""")
    private val connectionStateLine = Regex("""mConnectionState:\s*(\w+)""")
    private val codecConfig = Regex("""mCodecConfig:\s*\{([^}]*)\}""")

    private class CodecCandidate(val device: String?, val connected: Boolean?, val config: String)

    /**
     * A2dpService dumps one state machine per bonded headset, each with its
     * own "mCodecConfig" — including headsets that are long disconnected —
     * plus a StateMachineLog whose history rows embed old configs too. Only
     * the active (or else connected) device's own config line counts.
     */
    fun parseBluetoothCodec(output: String): BluetoothCodecInfo? {
        var activeDevice: String? = null
        var device: String? = null
        var connected: Boolean? = null
        val candidates = mutableListOf<CodecCandidate>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("rec[")) continue
            if (activeDevice == null) activeDeviceLine.find(line)?.let { activeDevice = it.groupValues[1] }
            stateMachineDevice.find(line)?.let { device = it.groupValues[1]; connected = null }
            connectionStateLine.find(line)?.let { connected = it.groupValues[1] == "STATE_CONNECTED" }
            if (line.startsWith("mCodecConfig:") || line.contains("mCodecStatus")) {
                codecConfig.find(line)?.let { candidates += CodecCandidate(device, connected, it.groupValues[1]) }
            }
        }
        val pick = candidates.firstOrNull { it.device != null && it.device == activeDevice }
            ?: candidates.firstOrNull { it.connected == true }
            // Older dumps carry no connection state at all; trust the first.
            ?: candidates.firstOrNull { it.connected == null }
            ?: return null
        return parseCodecConfig(pick.config)
    }

    internal fun parseCodecConfig(config: String): BluetoothCodecInfo? {
        fun field(name: String) = Regex("""$name:\s*([^,}]*)""").find(config)?.groupValues?.get(1)?.trim()
        val codec = field("codecName")?.takeIf { it.isNotBlank() && it != "INVALID" } ?: return null

        val rateText = field("mSampleRate")
        val sampleRate = rateText?.let { text ->
            Regex("""\b(44100|48000|88200|96000|176400|192000|16000|24000|32000)\b""").find(text)
                ?.groupValues?.get(1)?.toInt()
                ?: hexBitmask(text)?.let { mask ->
                    mapOf(0x1 to 44100, 0x2 to 48000, 0x4 to 88200, 0x8 to 96000,
                          0x10 to 176400, 0x20 to 192000)[mask]
                }
        }
        val bitsText = field("mBitsPerSample")
        val bits = bitsText?.let { text ->
            Regex("""\b(16|24|32)\b""").find(text)?.groupValues?.get(1)?.toInt()
                ?: hexBitmask(text)?.let { mapOf(0x1 to 16, 0x2 to 24, 0x4 to 32)[it] }
        }
        val channelText = field("mChannelMode")
        val channel = when {
            channelText == null -> null
            channelText.contains("STEREO", true) -> "Stereo"
            channelText.contains("MONO", true) -> "Mono"
            hexBitmask(channelText) == 0x2 -> "Stereo"
            hexBitmask(channelText) == 0x1 -> "Mono"
            else -> null
        }
        val ldacQuality = if (codec.contains("LDAC", true)) {
            when (field("mCodecSpecific1")?.let { Regex("""\d+""").find(it)?.value }) {
                "1000" -> "990 kbps"
                "1001" -> "660 kbps"
                "1002" -> "330 kbps"
                "1003" -> "Adaptive"
                else -> null
            }
        } else null
        return BluetoothCodecInfo(codec, sampleRate, bits, channel, ldacQuality)
    }

    private fun hexBitmask(text: String): Int? =
        Regex("""0x([0-9a-fA-F]+)""").find(text)?.groupValues?.get(1)?.toIntOrNull(16)

    // ── Display helpers ─────────────────────────────────────────────────

    /** audio_format_t as printed in hex in the track table. */
    fun describeFormat(code: Int): String = when (code) {
        0x1 -> "16-bit PCM"
        0x2 -> "8-bit PCM"
        0x3 -> "32-bit PCM"
        0x4 -> "24-bit PCM (8.24)"
        0x5 -> "32-bit float"
        0x6 -> "24-bit PCM"
        else -> when (code ushr 24) {
            0x01 -> "MP3 (offload)"
            0x04, 0x05, 0x06, 0x1E, 0x25 -> "AAC (offload)"
            0x07 -> "Vorbis (offload)"
            0x08 -> "Opus (offload)"
            0x09 -> "Dolby Digital (passthrough)"
            0x0A -> if (code and 0x1 != 0) "Dolby Atmos E-AC-3 (passthrough)" else "Dolby Digital Plus (passthrough)"
            0x0B, 0x0C -> "DTS (passthrough)"
            0x0D -> "IEC 61937 (passthrough)"
            0x0E -> "Dolby TrueHD (passthrough)"
            0x1B -> "FLAC (offload)"
            0x1C -> "ALAC (offload)"
            0x22 -> "Dolby AC-4 (passthrough)"
            0x24 -> "Dolby MAT (passthrough)"
            else -> String.format("format 0x%08X", code)
        }
    }

    /** "AUDIO_FORMAT_PCM_16_BIT" or "PCM_16_BIT" → "16-bit PCM". */
    fun describeFormatName(name: String): String = when (name.removePrefix("AUDIO_FORMAT_")) {
        "PCM_16_BIT" -> "16-bit PCM"
        "PCM_8_BIT" -> "8-bit PCM"
        "PCM_32_BIT" -> "32-bit PCM"
        "PCM_8_24_BIT" -> "24-bit PCM (8.24)"
        "PCM_FLOAT" -> "32-bit float"
        "PCM_24_BIT_PACKED" -> "24-bit PCM"
        else -> name.removePrefix("AUDIO_FORMAT_").replace('_', ' ')
    }

    fun describeDevice(device: String): String = when {
        device.startsWith("BLUETOOTH_A2DP") -> "Bluetooth A2DP"
        device.startsWith("BLE_") -> "Bluetooth LE Audio"
        device.startsWith("BLUETOOTH_SCO") -> "Bluetooth SCO (call)"
        device == "SPEAKER" || device == "SPEAKER_SAFE" -> "Speaker"
        device.startsWith("WIRED") -> "Wired headphones"
        device.startsWith("USB") -> "USB audio"
        device.startsWith("HDMI") -> "HDMI"
        device == "EARPIECE" -> "Earpiece"
        device == "TELEPHONY_TX" -> "Telephony"
        device == "REMOTE_SUBMIX" -> "Remote submix (cast/record)"
        else -> device.lowercase().replace('_', ' ')
    }

    /** Human description of the mixing path, from thread type + output flags. */
    fun describePath(thread: OutputThreadInfo): String = when {
        thread.type == "BIT_PERFECT" || "BIT_PERFECT" in thread.flags -> "Bit-perfect output"
        thread.type == "OFFLOAD" || "COMPRESS_OFFLOAD" in thread.flags -> "Hardware offload"
        thread.type == "DIRECT" || "DIRECT" in thread.flags -> "Direct output"
        thread.type == "SPATIALIZER" || "SPATIALIZER" in thread.flags -> "System spatializer mixer"
        thread.type.startsWith("MMAP") -> "MMAP (AAudio exclusive)"
        "DEEP_BUFFER" in thread.flags -> "Mixer · deep buffer"
        "FAST" in thread.flags -> "Mixer · low latency"
        "PRIMARY" in thread.flags -> "Mixer · primary"
        thread.type == "DUPLICATING" -> "Duplicating mixer"
        else -> "Mixer"
    }

    /** Bit depth part of [describeFormat] for PCM, e.g. "16-bit" / "32-bit float". */
    fun describeBitDepth(code: Int): String? = when (code) {
        0x1 -> "16-bit"
        0x2 -> "8-bit"
        0x3 -> "32-bit"
        0x4, 0x6 -> "24-bit"
        0x5 -> "32-bit float"
        else -> null
    }

    fun describeChannels(mask: Int): String = when (mask) {
        0x1 -> "Mono"
        0x3 -> "Stereo (2.0)"
        0x3F -> "5.1"
        0x63F -> "7.1"
        else -> "${Integer.bitCount(mask)} channels"
    }

    fun formatRate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", hz / 1000f)
}
