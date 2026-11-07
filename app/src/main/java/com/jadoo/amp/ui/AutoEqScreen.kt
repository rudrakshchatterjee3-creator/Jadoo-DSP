package com.jadoo.amp.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jadoo.amp.audio.AutoEq
import com.jadoo.amp.audio.DigitalFilterEngine
import com.jadoo.amp.ui.theme.JadooTheme
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/** One repository per process, so the parsed index survives reopening the screen. */
private var sharedRepository: AutoEq.Repository? = null

private fun repository(context: Context): AutoEq.Repository =
    sharedRepository ?: AutoEq.Repository(context.applicationContext).also { sharedRepository = it }

/** One headphone model and every lab that measured it, best lab first. */
private class Model(val name: String, val entries: List<AutoEq.Entry>)

private val tabular = TextStyle(fontFeatureSettings = "tnum")

/** "Bluetooth: WH-1000XM4" → "WH-1000XM4"; nothing for outputs with no model name. */
private fun suggestedQuery(outputLabel: String): String? = when {
    outputLabel.startsWith("Bluetooth: ") -> outputLabel.removePrefix("Bluetooth: ")
    outputLabel.startsWith("USB: ") -> outputLabel.removePrefix("USB: ")
    else -> null
}?.trim()?.takeIf { it.length >= 2 }

/**
 * Search the AutoEQ headphone database and load a correction into the
 * Parametric EQ. The load replaces the PEQ bank and preamp of the current
 * output device's profile, so the preview spells out which device that is.
 */
@Composable
fun AutoEqScreen(
    currentOutputDevice: String,
    initialQuery: String = "",
    onApply: (AutoEq.Profile, String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { repository(context) }

    var entries by remember { mutableStateOf<List<AutoEq.Entry>?>(null) }
    var indexError by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf(initialQuery) }
    var selected by remember { mutableStateOf<Model?>(null) }

    LaunchedEffect(reloadKey) {
        indexError = false
        entries = try {
            repo.index(forceRefresh = reloadKey > 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            indexError = true
            null
        }
    }

    val models = remember(entries, query) {
        val hits = entries?.let { AutoEq.search(it, query, limit = 400) } ?: emptyList()
        hits.groupBy { it.name }.map { (name, list) -> Model(name, list) }.take(120)
    }

    val model = selected
    if (model != null) {
        ProfileScreen(model, currentOutputDevice, repo,
            onApply = { profile, name -> onApply(profile, name); onNavigateBack() },
            onBack = { selected = null })
        return
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) { Icon(Icons.Default.Close, "Close") }
            Text("Headphone EQ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search 8,000+ headphones and IEMs") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = JadooTheme.dimens.screenPadding, vertical = 8.dp)
                .focusRequester(focus)
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                indexError -> Column(Modifier.align(Alignment.Center).padding(32.dp),
                                     horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("The headphone list didn't download.", style = MaterialTheme.typography.titleMedium)
                    Text("Check your connection and try again.", style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { reloadKey++ }) { Text("Try again") }
                }
                entries == null -> Column(Modifier.align(Alignment.Center),
                                          horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading the headphone list…", style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                query.isBlank() -> EmptySearch(suggestedQuery(currentOutputDevice)) { query = it }
                models.isEmpty() -> Text("No headphone matches \"$query\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(models, key = { it.name }) { m ->
                        ModelRow(m) { selected = m }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySearch(suggestion: String?, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
           verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (suggestion != null) {
            Surface(onClick = { onPick(suggestion) }, shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Connected now", style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(suggestion, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Text("Corrections from AutoEQ tune your headphone toward a neutral target, measured by " +
             "independent labs. Most models have several labs - the first is the one AutoEQ trusts most.",
             style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Data: AutoEQ by Jaakko Pasanen (MIT licence).", style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelRow(model: Model, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(model.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
             maxLines = 2, overflow = TextOverflow.Ellipsis)
        val labs = model.entries.map { it.source }.distinct()
        Text(if (labs.size == 1) model.entries.first().sourceLabel
             else "${labs.first()} + ${labs.size - 1} more lab${if (labs.size > 2) "s" else ""}",
             style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 24.dp),
                      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun ProfileScreen(
    model: Model,
    currentOutputDevice: String,
    repo: AutoEq.Repository,
    onApply: (AutoEq.Profile, String) -> Unit,
    onBack: () -> Unit
) {
    var entry by remember(model) { mutableStateOf(model.entries.first()) }
    var profile by remember { mutableStateOf<AutoEq.Profile?>(null) }
    var failed by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(entry) {
        profile = null
        failed = false
        profile = try {
            repo.profile(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to search") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = JadooTheme.dimens.screenPadding + 4.dp)) {
            Text(model.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            // The correction itself is the content, so it leads.
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                val p = profile
                when {
                    p != null -> ResponseCurve(p, Modifier.fillMaxSize())
                    failed -> Text("This profile didn't download. Pick another lab or try again later.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center))
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            profile?.let { p ->
                Text(String.format(Locale.US, "Preamp %+.1f dB · %d filters", p.preampDb, p.bands.size),
                     style = MaterialTheme.typography.bodyMedium.merge(tabular),
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(top = 8.dp))
            }

            if (model.entries.size > 1) {
                Spacer(Modifier.height(20.dp))
                Text("Measured by", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.entries.forEach { e ->
                        FilterChip(selected = e == entry, onClick = { entry = e },
                                   label = { Text(e.sourceLabel) })
                    }
                }
            } else {
                Text("Measured by ${entry.sourceLabel}", style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }

            profile?.let { p ->
                Spacer(Modifier.height(12.dp))
                val chevron = if (showFilters) 180f else 0f
                Row(Modifier.fillMaxWidth().clickable { showFilters = !showFilters }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Filters", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, if (showFilters) "Hide filters" else "Show filters",
                         modifier = Modifier.rotate(chevron))
                }
                AnimatedVisibility(showFilters) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.bands.forEach { b ->
                            Row {
                                Text(filterTypeLabel(b.type), style = MaterialTheme.typography.bodyMedium,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                                     modifier = Modifier.width(96.dp))
                                Text(String.format(Locale.US, "%,.0f Hz   %+.1f dB   Q %.2f",
                                                   b.frequencyHz, b.gainDb, b.q),
                                     style = MaterialTheme.typography.bodyMedium.merge(tabular))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Column(Modifier.fillMaxWidth().padding(horizontal = JadooTheme.dimens.screenPadding, vertical = 12.dp)) {
            Text("Replaces the Parametric EQ for $currentOutputDevice. Very narrow filters are smoothed " +
                 "by JadOO's 15-band engine.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { profile?.let { onApply(it, "${model.name} · ${entry.source}") } },
                   enabled = profile != null,
                   modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Apply to Parametric EQ")
            }
        }
    }
}

private fun filterTypeLabel(type: DigitalFilterEngine.FilterType) = when (type) {
    DigitalFilterEngine.FilterType.Peak -> "Peak"
    DigitalFilterEngine.FilterType.LowShelf -> "Low shelf"
    DigitalFilterEngine.FilterType.HighShelf -> "High shelf"
    DigitalFilterEngine.FilterType.LowPass -> "Low pass"
    DigitalFilterEngine.FilterType.HighPass -> "High pass"
    DigitalFilterEngine.FilterType.BandPass -> "Band pass"
    DigitalFilterEngine.FilterType.Notch -> "Notch"
    DigitalFilterEngine.FilterType.AllPass -> "All pass"
}

/** The correction's exact biquad response with preamp, 20 Hz-20 kHz, ±12 dB, decade gridlines. */
@Composable
private fun ResponseCurve(profile: AutoEq.Profile, modifier: Modifier = Modifier) {
    val states = remember(profile) {
        profile.bands.mapIndexed { i, b ->
            DigitalFilterEngine.BiquadBandState(i, b.type, b.frequencyHz, b.gainDb, b.q, b.enabled)
        }
    }
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
            val labelHeight = 18.dp.toPx()
            val plotHeight = size.height - labelHeight
            val dbRange = 12f
            val logLo = ln(20.0); val logHi = ln(20_000.0)
            fun xFor(f: Double) = (size.width * (ln(f) - logLo) / (logHi - logLo)).toFloat()
            fun yFor(db: Float) = plotHeight / 2f - (db / dbRange) * (plotHeight / 2f)
            for (f in listOf(100.0, 1_000.0, 10_000.0)) {
                drawLine(grid, Offset(xFor(f), 0f), Offset(xFor(f), plotHeight), strokeWidth = 1f)
                val label = measurer.measure(if (f >= 1_000.0) "${(f / 1_000).toInt()}k" else "${f.toInt()}", labelStyle)
                drawText(label, topLeft = Offset(xFor(f) - label.size.width / 2f, plotHeight + 4.dp.toPx()))
            }
            drawLine(grid, Offset(0f, yFor(0f)), Offset(size.width, yFor(0f)), strokeWidth = 2f)
            val path = Path()
            val area = Path().apply { moveTo(0f, yFor(0f)) }
            val steps = 200
            for (s in 0..steps) {
                val f = exp(logLo + (logHi - logLo) * s / steps).toFloat()
                val db = (DigitalFilterEngine.evaluateCombinedMagnitudeResponseDb(states, f) + profile.preampDb)
                    .coerceIn(-dbRange, dbRange)
                val x = size.width * s / steps
                if (s == 0) path.moveTo(x, yFor(db)) else path.lineTo(x, yFor(db))
                area.lineTo(x, yFor(db))
            }
            area.lineTo(size.width, yFor(0f)); area.close()
            drawPath(area, fill)
            drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
    }
}
