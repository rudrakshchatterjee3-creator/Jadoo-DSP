package com.jadoo.amp.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Headphone correction curves from the AutoEQ project
 * (github.com/jaakkopasanen/AutoEq, MIT licence) — ~8,800 measured
 * headphones and IEMs across oratory1990, crinacle, Rtings and others.
 *
 * Nothing is bundled. The index (~850 KB of markdown) is fetched on first
 * use and cached; each profile is a ~500-byte ParametricEQ.txt fetched when
 * picked and cached too, so a profile applied once keeps working offline.
 *
 * A profile is a preamp plus up to 10 RBJ biquads with Q — the same filter
 * math [DigitalFilterEngine] uses, so it loads 1:1 into the Parametric EQ.
 */
object AutoEq {

    private const val TAG = "AutoEq"
    private const val RESULTS_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private const val INDEX_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    data class Entry(
        val name: String,
        val source: String,
        val rig: String?,
        /** URL-encoded folder path under results/, e.g. "oratory1990/over-ear/Sennheiser%20HD%20650". */
        val path: String
    ) {
        val sourceLabel: String get() = if (rig != null) "$source · $rig" else source
    }

    data class Profile(
        val preampDb: Float,
        val bands: List<DigitalFilterEngine.FilterBand>
    )

    // ── Parsing ─────────────────────────────────────────────────────────

    // "- [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990"
    // "- [1Custom SA02](./crinacle/711%20in-ear/1Custom%20SA02) by crinacle on 711"
    private val indexLine = Regex("""^- \[(.+)]\(\./(.+)\) by (.+?)(?: on (.+))?$""")

    fun parseIndex(markdown: String): List<Entry> =
        markdown.lineSequence().mapNotNull { line ->
            val m = indexLine.find(line.trim()) ?: return@mapNotNull null
            Entry(
                name = m.groupValues[1],
                path = m.groupValues[2],
                source = m.groupValues[3],
                rig = m.groupValues[4].takeIf { it.isNotBlank() }
            )
        }.toList()

    private val preampLine = Regex("""Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterLine = Regex(
        """Filter\s*\d*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz""" +
            """(?:\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB)?(?:\s+Q\s+(\d+(?:\.\d+)?))?""",
        RegexOption.IGNORE_CASE
    )

    /** Parses AutoEQ / Equalizer APO "ParametricEQ.txt". Null when no usable filter is found. */
    fun parseParametricEq(text: String): Profile? {
        val preamp = preampLine.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val bands = filterLine.findAll(text).mapNotNull { m ->
            if (!m.groupValues[1].equals("ON", ignoreCase = true)) return@mapNotNull null
            val type = when (m.groupValues[2].uppercase(Locale.US)) {
                "PK", "PEQ" -> DigitalFilterEngine.FilterType.Peak
                "LS", "LSC" -> DigitalFilterEngine.FilterType.LowShelf
                "HS", "HSC" -> DigitalFilterEngine.FilterType.HighShelf
                "LP", "LPQ" -> DigitalFilterEngine.FilterType.LowPass
                "HP", "HPQ" -> DigitalFilterEngine.FilterType.HighPass
                "BP" -> DigitalFilterEngine.FilterType.BandPass
                "NO" -> DigitalFilterEngine.FilterType.Notch
                else -> return@mapNotNull null
            }
            DigitalFilterEngine.sanitizeBand(
                DigitalFilterEngine.FilterBand(
                    enabled = true,
                    type = type,
                    frequencyHz = m.groupValues[3].toFloat(),
                    gainDb = m.groupValues[4].toFloatOrNull() ?: 0f,
                    q = m.groupValues[5].toFloatOrNull() ?: 0.707f
                )
            )
        }.take(DigitalFilterEngine.MAX_BANDS).toList()
        if (bands.isEmpty()) return null
        return Profile(preamp, bands)
    }

    /**
     * AutoEQ's own source preference: anechoic/GRAS-rig measurers first, so
     * when one headphone has several entries the most trusted one leads.
     */
    private val sourceRank = listOf("oratory1990", "crinacle", "Innerfidelity", "Rtings", "Headphone.com Legacy")

    fun search(entries: List<Entry>, query: String, limit: Int = 150): List<Entry> {
        val tokens = query.lowercase(Locale.US).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val q = tokens.joinToString(" ")
        return entries.asSequence()
            .filter { e -> val n = e.name.lowercase(Locale.US); tokens.all { it in n } }
            .sortedWith(compareBy<Entry>(
                { if (it.name.lowercase(Locale.US).startsWith(q)) 0 else 1 },
                { it.name.length },
                { it.name.lowercase(Locale.US) },
                { sourceRank.indexOf(it.source).let { r -> if (r < 0) sourceRank.size else r } }
            ))
            .take(limit)
            .toList()
    }

    // ── Network + cache ─────────────────────────────────────────────────

    class Repository(context: Context) {
        private val dir = File(context.filesDir, "autoeq").apply { mkdirs() }
        private val indexFile = File(dir, "INDEX.md")
        private val indexLock = Mutex()
        @Volatile private var entries: List<Entry>? = null

        /**
         * The parsed index. Served from cache when fresh; refetched when
         * stale or [forceRefresh]; a failed refetch falls back to any cached
         * copy, however old, and only throws when there is nothing at all.
         */
        suspend fun index(forceRefresh: Boolean = false): List<Entry> = indexLock.withLock {
            entries?.takeIf { !forceRefresh }?.let { return it }
            withContext(Dispatchers.IO) {
                val stale = !indexFile.exists() ||
                    System.currentTimeMillis() - indexFile.lastModified() > INDEX_MAX_AGE_MS
                if (forceRefresh || stale) {
                    try {
                        val text = fetch(RESULTS_URL + "INDEX.md")
                        if (parseIndex(text).isNotEmpty()) writeAtomically(indexFile, text)
                    } catch (e: Exception) {
                        Log.w(TAG, "Index fetch failed; using cache if any", e)
                        if (!indexFile.exists()) throw e
                    }
                }
                parseIndex(indexFile.readText()).also { entries = it }
            }
        }

        suspend fun profile(entry: Entry): Profile = withContext(Dispatchers.IO) {
            val cache = File(dir, "p_" + entry.path.hashCode().toUInt().toString(16) + ".txt")
            val text = try {
                val folder = entry.path.substringAfterLast('/')
                fetch(RESULTS_URL + entry.path + "/" + folder + "%20ParametricEQ.txt")
                    .also { writeAtomically(cache, it) }
            } catch (e: Exception) {
                if (cache.exists()) cache.readText() else throw e
            }
            parseParametricEq(text) ?: throw IllegalStateException("No usable filters in ${entry.name}")
        }

        private fun fetch(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("HTTP ${connection.responseCode} for $url")
                }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        private fun writeAtomically(target: File, text: String) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }
}
