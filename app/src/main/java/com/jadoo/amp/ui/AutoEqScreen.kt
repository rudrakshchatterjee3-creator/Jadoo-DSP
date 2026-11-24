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
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.jadoo.amp.R
import com.jadoo.amp.audio.AutoEq
import com.jadoo.amp.audio.DigitalFilterEngine
import com.jadoo.amp.ui.theme.JadooTheme
import com.jadoo.amp.ui.components.*
import com.jadoo.amp.ui.theme.GlassLevel
import com.jadoo.amp.ui.theme.LocalUiStyle
import com.jadoo.amp.ui.theme.UiStyle
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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

    val glass = LocalUiStyle.current == UiStyle.LiquidGlass
    val titleRow: @Composable RowScope.() -> Unit = {
        IconButton(onClick = onNavigateBack) { Icon(Icons.Default.Close, stringResource(R.string.scr_cd_close)) }
        Text(stringResource(R.string.scr_ae_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
    val searchField: @Composable (Modifier) -> Unit = { m ->
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.scr_ae_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.scr_ae_clear_search)) } }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = m.focusRequester(focus)
        )
    }
    // [top] clears the pinned glass header (0 under Standard).
    val results: @Composable BoxScope.(Dp) -> Unit = { top ->
        when {
            indexError -> Column(Modifier.align(Alignment.Center).padding(top = top).padding(32.dp),
                                 horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.scr_ae_list_failed), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.scr_ae_check_connection), style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { reloadKey++ }) { Text(stringResource(R.string.scr_ae_try_again)) }
            }
            entries == null -> Column(Modifier.align(Alignment.Center).padding(top = top),
                                      horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.scr_ae_loading), style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            query.isBlank() -> Box(Modifier.padding(top = top)) {
                EmptySearch(suggestedQuery(currentOutputDevice)) { query = it }
            }
            models.isEmpty() -> Text(stringResource(R.string.scr_ae_no_match, query),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = top).padding(24.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = if (glass) PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = 24.dp)
                                 else PaddingValues(0.dp),
                verticalArrangement = if (glass) Arrangement.spacedBy(8.dp) else Arrangement.Top
            ) {
                items(models, key = { it.name }) { m ->
                    ModelRow(m) { selected = m }
                }
            }
        }
    }

    if (glass) {
        // Liquid Glass: title and search float as one glass panel; results
        // scroll under it and are refracted by it.
        GlassPinnedLayout(
            modifier = Modifier.fillMaxSize(),
            header = {
                GlassTopPanel {
                    Row(Modifier.fillMaxWidth().padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, content = titleRow)
                    searchField(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
                }
            }
        ) { top ->
            Box(Modifier.fillMaxSize().glassContentSource()) { results(top + 8.dp) }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically, content = titleRow)
        searchField(Modifier.fillMaxWidth()
            .padding(horizontal = JadooTheme.dimens.screenPadding, vertical = 8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) { results(0.dp) }
    }
}

@Composable
private fun EmptySearch(suggestion: String?, onPick: (String) -> Unit) {
    val glass = LocalUiStyle.current == UiStyle.LiquidGlass
    GlassSection(Modifier.padding(horizontal = if (glass) 16.dp else 0.dp)) {
    Column(Modifier.fillMaxWidth().padding(horizontal = if (glass) 4.dp else 24.dp, vertical = if (glass) 4.dp else 16.dp),
           verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (suggestion != null) {
            JadooSurface(onClick = { onPick(suggestion) }, shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth(),
                    level = GlassLevel.Nested) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(stringResource(R.string.scr_ae_connected_now), style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(suggestion, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Text(stringResource(R.string.scr_ae_explainer),
             style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.scr_ae_data_credit), style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
}

@Composable
private fun ModelRow(model: Model, onClick: () -> Unit) {
    if (LocalUiStyle.current == UiStyle.LiquidGlass) {
        // One glass row per model, inset from the edges like a grouped list.
        JadooSurface(onClick = onClick, shape = RoundedCornerShape(22.dp),
                     color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(model.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
                val labs = model.entries.map { it.source }.distinct()
                Text(labsLine(model, labs),
                     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(model.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
             maxLines = 2, overflow = TextOverflow.Ellipsis)
        val labs = model.entries.map { it.source }.distinct()
        Text(labsLine(model, labs),
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

    val glass = LocalUiStyle.current == UiStyle.LiquidGlass
    val profileBody: @Composable ColumnScope.() -> Unit = {
            Text(model.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            // The correction itself is the content, so it leads.
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                val p = profile
                when {
                    p != null -> ResponseCurve(p, Modifier.fillMaxSize())
                    failed -> Text(stringResource(R.string.scr_ae_profile_failed),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center))
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            profile?.let { p ->
                Text(pluralStringResource(R.plurals.scr_ae_preamp_filters, p.bands.size,
                         String.format(Locale.US, "%+.1f dB", p.preampDb), p.bands.size),
                     style = MaterialTheme.typography.bodyMedium.merge(tabular),
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(top = 8.dp))
            }

            if (model.entries.size > 1) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.scr_ae_measured_by), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.entries.forEach { e ->
                        FilterChip(selected = e == entry, onClick = { entry = e },
                                   label = { Text(e.sourceLabel) },
                                   shape = jadooChipShape(FilterChipDefaults.shape),
                                   colors = jadooFilterChipColors(),
                                   border = jadooFilterChipBorder(true, e == entry))
                    }
                }
            } else {
                Text(stringResource(R.string.scr_ae_measured_by_lab, entry.sourceLabel), style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }

            profile?.let { p ->
                Spacer(Modifier.height(12.dp))
                val chevron = if (showFilters) 180f else 0f
                Row(Modifier.fillMaxWidth().clickable { showFilters = !showFilters }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.scr_peq_filters), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore,
                         stringResource(if (showFilters) R.string.scr_ae_hide_filters else R.string.scr_ae_show_filters),
                         modifier = Modifier.rotate(chevron))
                }
                AnimatedVisibility(showFilters) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.bands.forEach { b ->
                            Row {
                                Text(stringResource(filterTypeLabel(b.type)), style = MaterialTheme.typography.bodyMedium,
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
            Spacer(Modifier.height(if (glass) 4.dp else 24.dp))
    }
    val applyPanel: @Composable ColumnScope.() -> Unit = {
            Text(stringResource(R.string.scr_ae_replaces, com.jadoo.amp.audio.localizedOutputLabel(LocalContext.current, currentOutputDevice)),
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { profile?.let { onApply(it, "${model.name} · ${entry.source}") } },
                   enabled = profile != null,
                   border = jadooTonalButtonBorder(),
                   modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.scr_ae_apply))
            }
    }

    if (glass) {
        // Liquid Glass: back button in a pinned glass bar, the Apply panel as
        // a floating glass bottom bar; the profile scrolls between and under both.
        var bottomPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        GlassPinnedLayout(
            modifier = Modifier.fillMaxSize(),
            header = {
                GlassTopBar {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.scr_ae_back_to_search)) }
                    Text(stringResource(R.string.scr_ae_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        ) { top ->
            Column(Modifier.fillMaxSize().glassContentSource().verticalScroll(rememberScrollState())
                    .padding(horizontal = JadooTheme.dimens.screenPadding)
                    .padding(top = top + 4.dp, bottom = with(density) { bottomPx.toDp() } + 16.dp)) {
                GlassSection(content = profileBody)
            }
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .onSizeChanged { bottomPx = it.height }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .liquidGlass(RoundedCornerShape(30.dp), level = GlassLevel.Floating)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                content = applyPanel
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.scr_ae_back_to_search)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = JadooTheme.dimens.screenPadding + 4.dp), content = profileBody)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Column(Modifier.fillMaxWidth().padding(horizontal = JadooTheme.dimens.screenPadding, vertical = 12.dp),
               content = applyPanel)
    }
}

@StringRes
private fun filterTypeLabel(type: DigitalFilterEngine.FilterType): Int = when (type) {
    DigitalFilterEngine.FilterType.Peak -> R.string.scr_filter_peak
    DigitalFilterEngine.FilterType.LowShelf -> R.string.scr_filter_low_shelf
    DigitalFilterEngine.FilterType.HighShelf -> R.string.scr_filter_high_shelf
    DigitalFilterEngine.FilterType.LowPass -> R.string.scr_filter_low_pass
    DigitalFilterEngine.FilterType.HighPass -> R.string.scr_filter_high_pass
    DigitalFilterEngine.FilterType.BandPass -> R.string.scr_filter_band_pass
    DigitalFilterEngine.FilterType.Notch -> R.string.scr_filter_notch
    DigitalFilterEngine.FilterType.AllPass -> R.string.scr_filter_all_pass
}

/** "Lab A" for one lab, "Lab A + 2 more labs" for several. */
@Composable
private fun labsLine(model: Model, labs: List<String>): String =
    if (labs.size == 1) model.entries.first().sourceLabel
    else pluralStringResource(R.plurals.scr_ae_more_labs, labs.size - 1, labs.first(), labs.size - 1)

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
