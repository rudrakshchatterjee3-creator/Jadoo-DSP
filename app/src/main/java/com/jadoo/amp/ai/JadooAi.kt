package com.jadoo.amp.ai

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * JadOO AI: an optional small language model that runs on the phone and reads
 * what the user writes in the taste profile ([AiReply]).
 *
 * Nothing of it ships in the APK. On the user's tap it downloads, once:
 *  - the LiteRT-LM engine (Google, Apache 2.0): the official Maven AAR, from
 *    which only the arm64 native library is kept;
 *  - Qwen3 0.6B in LiteRT-LM format, INT4 (Qwen team / litert-community,
 *    Apache 2.0), pinned to one Hugging Face revision.
 * Both are checked against SHA-256 values fixed here before anything is
 * loaded, so a changed or corrupted file is never run. The engine's Java side
 * skips its own System.loadLibrary when the library is already loaded, which
 * is what lets the library live in app storage instead of the APK.
 *
 * Text the user writes never leaves the phone; the only network traffic is
 * the two downloads.
 */
object JadooAi {
    private const val TAG = "JadooAi"

    private const val ENGINE_URL =
        "https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.19.1/litertlm-android-0.19.1.aar"
    private const val ENGINE_AAR_SHA256 = "341f66349ac962e0b6733b8da419b045a4834fca2f7db0bd13e049ce1cfcca72"
    private const val ENGINE_AAR_BYTES = 21_200_360L
    private const val ENGINE_SO_ENTRY = "jni/arm64-v8a/liblitertlm_jni.so"
    private const val ENGINE_SO_SHA256 = "1b2a020e948136e5560703ab85600d0b6bc39e34223f6c0fa6fb30d454316bd5"

    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/8d42ab5f1db14cad987fac57aeb17d070f9e180d/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
    private const val MODEL_SHA256 = "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0"
    private const val MODEL_BYTES = 343_490_560L

    const val DOWNLOAD_BYTES = ENGINE_AAR_BYTES + MODEL_BYTES

    enum class Failure { Network, Storage, Corrupt }

    sealed interface State {
        data object Unsupported : State
        data object NotInstalled : State
        data class Downloading(val doneBytes: Long, val totalBytes: Long) : State
        data object Checking : State
        data object Installed : State
        data class Failed(val why: Failure) : State
    }

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val aiThread = Executors.newSingleThreadExecutor { Thread(it, "JadooAi") }.asCoroutineDispatcher()
    private var engine: Engine? = null
    @Volatile private var nativeLoaded = false

    /** The engine is built for 64-bit ARM, which nearly every phone from the last years is. */
    val supported: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "jadoo-ai").apply { mkdirs() }
    private fun engineFile(ctx: Context) = File(dir(ctx), "liblitertlm_jni.so")
    private fun modelFile(ctx: Context) = File(dir(ctx), "qwen3-0.6b.litertlm")

    fun refresh(ctx: Context) {
        if (job?.isActive == true) return
        _state.value = when {
            !supported -> State.Unsupported
            engineFile(ctx).exists() && modelFile(ctx).exists() -> State.Installed
            else -> State.NotInstalled
        }
    }

    fun install(ctx: Context) {
        if (!supported || job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            try {
                val dir = dir(app)
                if (dir.usableSpace < DOWNLOAD_BYTES + 200L * 1024 * 1024) {
                    _state.value = State.Failed(Failure.Storage); return@launch
                }
                if (!engineFile(app).exists()) {
                    val aar = File(dir, "engine.aar")
                    download(ENGINE_URL, aar, ENGINE_AAR_BYTES) { _state.value = State.Downloading(it, DOWNLOAD_BYTES) }
                    _state.value = State.Checking
                    if (sha256(aar) != ENGINE_AAR_SHA256) { aar.delete(); _state.value = State.Failed(Failure.Corrupt); return@launch }
                    val part = File(dir, "engine.so.part")
                    extract(aar, ENGINE_SO_ENTRY, part)
                    aar.delete()
                    if (sha256(part) != ENGINE_SO_SHA256) { part.delete(); _state.value = State.Failed(Failure.Corrupt); return@launch }
                    part.renameTo(engineFile(app))
                }
                if (!modelFile(app).exists()) {
                    val part = File(dir, "model.part")
                    download(MODEL_URL, part, MODEL_BYTES) { _state.value = State.Downloading(ENGINE_AAR_BYTES + it, DOWNLOAD_BYTES) }
                    _state.value = State.Checking
                    if (sha256(part) != MODEL_SHA256) { part.delete(); _state.value = State.Failed(Failure.Corrupt); return@launch }
                    part.renameTo(modelFile(app))
                }
                _state.value = State.Installed
                Log.i(TAG, "JadOO AI installed")
            } catch (e: CancellationException) {
                _state.value = State.NotInstalled
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "install failed: ${e.message}")
                _state.value = State.Failed(Failure.Network)
            }
        }
    }

    /** Stops a download; what arrived so far is kept and resumed next time. */
    fun cancel() { job?.cancel() }

    fun remove(ctx: Context) {
        job?.cancel()
        scope.launch {
            withContext(aiThread) { engine?.close(); engine = null }
            dir(ctx.applicationContext).deleteRecursively()
            refresh(ctx.applicationContext)
        }
    }

    /** Frees the model's memory (about 1–2 GB while loaded); it reloads on the next use. */
    fun release() {
        scope.launch { withContext(aiThread) { engine?.close(); engine = null } }
    }

    /**
     * Reads [text] with the model. Null when JadOO AI is not installed, the
     * text is blank, or anything goes wrong: the taste profile then simply
     * starts from the questionnaire.
     */
    suspend fun interpret(ctx: Context, text: String): AiReply.Interpretation? {
        if (text.isBlank() || _state.value != State.Installed) return null
        return withContext(aiThread) {
            try {
                val e = engineFor(ctx.applicationContext)
                val config = ConversationConfig(
                    systemInstruction = Contents.of(AiReply.SYSTEM),
                    initialMessages = AiReply.EXAMPLES.flatMap { (q, a) -> listOf(Message.user(q), Message.model(a)) },
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0),
                    thinkingConfig = ThinkingConfig(false)
                )
                val started = System.nanoTime()
                val reply = e.createConversation(config).use { c -> c.sendMessage(text.take(AiReply.MAX_INPUT_CHARS)) }
                val out = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                Log.i(TAG, "reply in ${(System.nanoTime() - started) / 1_000_000} ms: $out")
                AiReply.parse(out)
            } catch (t: Throwable) {
                Log.w(TAG, "interpret failed", t)
                null
            }
        }
    }

    private fun engineFor(ctx: Context): Engine {
        engine?.let { return it }
        if (!nativeLoaded) {
            System.load(engineFile(ctx).absolutePath)
            nativeLoaded = true
        }
        val started = System.nanoTime()
        val e = Engine(EngineConfig(
            modelPath = modelFile(ctx).absolutePath,
            backend = Backend.CPU(),
            maxNumTokens = 1024,
            cacheDir = ctx.cacheDir.absolutePath
        ))
        e.initialize()
        Log.i(TAG, "engine ready in ${(System.nanoTime() - started) / 1_000_000} ms")
        engine = e
        return e
    }

    /** Downloads [url] to [file], resuming a partial file with a Range request. */
    private suspend fun download(url: String, file: File, expected: Long, progress: (Long) -> Unit) {
        var have = if (file.exists()) file.length() else 0L
        if (have > expected) { file.delete(); have = 0L }
        if (have == expected) { progress(have); return }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JadOO")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL && have > 0
            if (code != HttpURLConnection.HTTP_OK && !append) throw IOException("HTTP $code")
            if (!append) have = 0L
            conn.inputStream.use { input ->
                FileOutputStream(file, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport >= 512 * 1024) { progress(have); lastReport = have }
                    }
                }
            }
            progress(have)
            if (have != expected) throw IOException("got $have of $expected bytes")
        } finally {
            conn.disconnect()
        }
    }

    private fun extract(zip: File, entry: String, to: File) {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: throw IOException("$entry missing")
                if (e.name == entry) { FileOutputStream(to).use { z.copyTo(it) }; return }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
