package com.jadoo.amp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadoo.amp.R
import com.jadoo.amp.audio.Taste
import com.jadoo.amp.audio.Taste.Dim
import com.jadoo.amp.audio.ReferenceTrack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.LibraryMusic
import com.jadoo.amp.ai.AiReply
import com.jadoo.amp.ai.JadooAi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.jadoo.amp.ui.components.JadooScreenSurface
import kotlinx.coroutines.launch
import com.jadoo.amp.ui.components.GlassPinnedLayout
import com.jadoo.amp.ui.components.GlassSection
import com.jadoo.amp.ui.components.GlassTopBar
import com.jadoo.amp.ui.components.JadooAlertDialog
import com.jadoo.amp.ui.components.glassContentSource
import com.jadoo.amp.ui.theme.JadooTheme
import com.jadoo.amp.ui.theme.LocalUiStyle
import com.jadoo.amp.ui.theme.UiStyle
import kotlinx.coroutines.delay
import java.util.Locale

private enum class TpStep { Intro, Questions, Compare, Result }

private const val QUESTION_COUNT = 7

@Composable
internal fun dimName(d: Dim) = stringResource(when (d) {
    Dim.Bass -> R.string.tp_dim_bass
    Dim.Warmth -> R.string.tp_dim_warmth
    Dim.Clarity -> R.string.tp_dim_clarity
    Dim.Air -> R.string.tp_dim_air
})

internal fun signedDb(v: Float) = (if (v > 0f) "+" else "") + String.format(Locale.US, "%.1f", v)

/**
 * The taste profile: a few questions (where the search starts), then
 * voice-matched A/B choices on the user's own music (the answer), see
 * [Taste]. The A/B sides play live through the engine; leaving at any point
 * drops the audition and keeps whatever taste was saved before.
 */
@Composable
fun TasteScreen(ui: HearPrintUi, retakeIndex: Int? = null, onNavigateBack: () -> Unit) {
    var step by remember { mutableStateOf(TpStep.Intro) }
    var question by remember { mutableIntStateOf(0) }
    var answers by remember { mutableStateOf(Taste.Answers()) }
    var words by remember { mutableStateOf("") }
    var estimator by remember { mutableStateOf<Taste.Estimator?>(null) }
    var comparison by remember { mutableStateOf<Taste.Comparison?>(null) }
    var result by remember { mutableStateOf<Taste.Profile?>(null) }
    var askStop by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<AiReply.Interpretation?>(null) }
    var readFor by remember { mutableStateOf("") }
    var readingBusy by remember { mutableStateOf(false) }
    var readFailed by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiState by JadooAi.state.collectAsState()
    LaunchedEffect(Unit) { JadooAi.refresh(context) }

    val reference = remember { ReferenceTrack(context.applicationContext) }
    val hasReference = remember { ReferenceTrack.assetName(context) != null }
    var useReference by remember { mutableStateOf(hasReference) }
    var referencePlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { ui.onTastePreview(null); JadooAi.release(); reference.stop() } }

    fun understand(then: (() -> Unit)? = null) {
        if (readingBusy) return
        val text = words
        readingBusy = true; readFailed = false
        scope.launch {
            val r = JadooAi.interpret(context, text)
            reading = r; readFor = text; readFailed = r == null
            readingBusy = false
            then?.invoke()
        }
    }

    if (showAi) {
        Dialog(onDismissRequest = { showAi = false },
               properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            JadooScreenSurface(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                               color = MaterialTheme.colorScheme.surface) {
                JadooAiScreen(onClose = { showAi = false })
            }
        }
    }

    val inProgress = step == TpStep.Questions || step == TpStep.Compare
    val close: () -> Unit = { if (inProgress) askStop = true else onNavigateBack() }
    BackHandler(onBack = close)

    // A taste belongs to the output it was chosen on (and a retake to that
    // output's library). If the sound moves, the audition stops, nothing is
    // saved and the screen closes.
    var outputChanged by remember { mutableStateOf(false) }
    val startOutput = remember { ui.outputKey }
    LaunchedEffect(ui.outputKey) {
        if (ui.outputKey != startOutput && !outputChanged) {
            ui.onTastePreview(null)
            reference.stop(); referencePlaying = false
            result = null
            outputChanged = true
        }
    }
    if (outputChanged) OutputChangedDialog(onDismiss = { outputChanged = false; onNavigateBack() })
    if (askStop) {
        JadooAlertDialog(
            onDismissRequest = { askStop = false },
            title = { Text(stringResource(R.string.tp_stop_title)) },
            text = { Text(stringResource(R.string.tp_stop_body)) },
            confirmButton = { TextButton(onClick = { askStop = false; ui.onTastePreview(null); onNavigateBack() }) { Text(stringResource(R.string.hp_stop_confirm)) } },
            dismissButton = { TextButton(onClick = { askStop = false }) { Text(stringResource(R.string.hp_keep_going)) } }
        )
    }

    fun startComparing() {
        val nudges = reading?.takeIf { readFor == words }?.deltasDb ?: FloatArray(Taste.DIMS.size)
        val prior = Taste.prior(answers.copy(wordsDb = nudges))
        val est = Taste.Estimator(prior.first, prior.second)
        estimator = est
        comparison = est.next()
        if (useReference) referencePlaying = reference.start()
        step = TpStep.Compare
    }

    val glass = LocalUiStyle.current == UiStyle.LiquidGlass
    val titleRow: @Composable RowScope.() -> Unit = {
        IconButton(onClick = close, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.Close, stringResource(if (inProgress) R.string.hp_cd_stop else R.string.scr_cd_close))
        }
        Text(stringResource(R.string.tp_title), style = MaterialTheme.typography.titleLarge,
             fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    }
    GlassPinnedLayout(Modifier.fillMaxSize(), header = { if (glass) GlassTopBar(content = titleRow) }) { headerTop ->
        Column(Modifier.fillMaxSize()) {
            if (!glass) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, content = titleRow)
            }
            Box(
                Modifier.fillMaxSize().glassContentSource()
                    .padding(horizontal = JadooTheme.dimens.screenPadding)
                    .padding(top = if (glass) headerTop + 8.dp else 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                val fade = JadooTheme.motion.fade
                AnimatedContent(step, modifier = Modifier.widthIn(max = 560.dp).fillMaxSize(),
                    transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, label = "tp_step") { s ->
                    when (s) {
                        TpStep.Intro -> TasteIntro(ui.isMusicPlaying, hasReference, useReference, { useReference = it }) {
                            question = 0; step = TpStep.Questions
                        }
                        TpStep.Questions -> QuestionStep(
                            index = question, answers = answers, words = words,
                            onAnswers = { answers = it }, onWords = { words = it },
                            onBack = { if (question > 0) question-- },
                            onNext = {
                                when {
                                    question < QUESTION_COUNT - 1 -> question++
                                    words.isNotBlank() && aiState == JadooAi.State.Installed && readFor != words -> understand { startComparing() }
                                    else -> startComparing()
                                }
                            },
                            ai = AiBoxState(aiState, reading?.takeIf { readFor == words }, readingBusy, readFailed && readFor == words),
                            onUnderstand = { understand() },
                            onGetAi = { showAi = true }
                        )
                        TpStep.Compare -> comparison?.let { c ->
                            CompareStep(c, estimator!!, ui,
                                reference = if (useReference) ReferenceControl(referencePlaying) {
                                    if (referencePlaying) reference.pause() else reference.resume()
                                    referencePlaying = !referencePlaying
                                } else null) { choice ->
                                val est = estimator!!
                                est.answer(c, choice)
                                val next = est.next()
                                if (next == null) {
                                    val r = est.result(System.currentTimeMillis())
                                    result = r
                                    ui.onTastePreview(r.gainsDb)
                                    step = TpStep.Result
                                } else comparison = next
                            }
                        }
                        TpStep.Result -> result?.let { r ->
                            val defaultName = retakeIndex?.let { ui.tasteLibrary.items.getOrNull(it)?.name }?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.tp_default_name, ui.tasteLibrary.items.size + 1)
                            TasteResult(r, ui.curve, defaultName,
                                onSave = { name -> ui.onSaveTaste(r, name, retakeIndex); onNavigateBack() },
                                onStartOver = {
                                    ui.onTastePreview(null); result = null; comparison = null; estimator = null
                                    question = 0; step = TpStep.Questions
                                },
                                onDiscard = { ui.onTastePreview(null); onNavigateBack() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicStatus(isMusicPlaying: () -> Boolean) {
    var playing by remember { mutableStateOf(isMusicPlaying()) }
    LaunchedEffect(Unit) { while (true) { playing = isMusicPlaying(); delay(1500) } }
    val tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(
        Modifier.padding(top = 16.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.MusicNote, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (playing) R.string.tp_music_on else R.string.tp_music_off),
             style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun TasteIntro(
    isMusicPlaying: () -> Boolean,
    hasReference: Boolean,
    useReference: Boolean,
    onUseReference: (Boolean) -> Unit,
    onStart: () -> Unit
) {
    StepLayout(footer = { PrimaryButton(stringResource(R.string.tp_start), onClick = onStart) }) {
        Hero(Icons.Default.AutoAwesome, pulsing = true)
        Headline(stringResource(R.string.tp_intro_headline))
        Body(stringResource(R.string.tp_intro_body))
        Spacer(Modifier.height(16.dp))
        if (hasReference) {
            SourceCard(Icons.Default.LibraryMusic, stringResource(R.string.tp_source_reference),
                       stringResource(R.string.tp_source_reference_body), useReference) { onUseReference(true) }
            Spacer(Modifier.height(8.dp))
            SourceCard(Icons.Default.MusicNote, stringResource(R.string.tp_source_own),
                       stringResource(R.string.tp_play_music), !useReference) { onUseReference(false) }
            if (!useReference) MusicStatus(isMusicPlaying)
            Text(stringResource(R.string.tp_ref_credit), style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                 modifier = Modifier.padding(top = 16.dp))
        } else {
            Text(stringResource(R.string.tp_play_music), style = MaterialTheme.typography.bodyMedium,
                 fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            MusicStatus(isMusicPlaying)
        }
    }
}

@Composable
private fun SourceCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String,
                       selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        JadooTheme.motion.fastSpec(), label = "tp_source")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** The reference track's play/pause in the A/B step. */
internal class ReferenceControl(val playing: Boolean, val toggle: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<Pair<T, Int>>, isSelected: (T) -> Boolean, onToggle: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = isSelected(value), onClick = { onToggle(value) },
                label = { Text(stringResource(label), modifier = Modifier.padding(vertical = 10.dp)) },
                shape = CircleShape)
        }
    }
}

@Composable
private fun QuestionStep(
    index: Int,
    answers: Taste.Answers,
    words: String,
    onAnswers: (Taste.Answers) -> Unit,
    onWords: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    ai: AiBoxState,
    onUnderstand: () -> Unit,
    onGetAi: () -> Unit
) {
    val answered = when (index) {
        0 -> answers.genres.isNotEmpty()
        1 -> answers.bothers.isNotEmpty()
        2 -> answers.bass != null
        3 -> answers.vocals != null
        4 -> answers.treble != null
        5 -> answers.volume != null
        else -> words.isNotBlank()
    }
    StepLayout(centered = false, footer = {
        PrimaryButton(stringResource(if (answered) R.string.tp_next else R.string.tp_skip), enabled = !ai.busy, onClick = onNext)
        if (index > 0) TextButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.tp_back))
        }
    }) {
        val progress by animateFloatAsState((index + 1f) / QUESTION_COUNT, JadooTheme.motion.gentle, label = "tp_q")
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.5f).height(6.dp).clip(CircleShape))
        Text(stringResource(R.string.tp_q_progress, index + 1, QUESTION_COUNT), style = MaterialTheme.typography.labelLarge,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        val (title, hint) = when (index) {
            0 -> R.string.tp_q_genre to R.string.tp_pick_any
            1 -> R.string.tp_q_bother to R.string.tp_pick_any
            2 -> R.string.tp_q_bass to null
            3 -> R.string.tp_q_vocal to null
            4 -> R.string.tp_q_treble to null
            5 -> R.string.tp_q_volume to null
            else -> R.string.tp_q_words to R.string.tp_words_optional
        }
        Headline(stringResource(title))
        hint?.let { Body(stringResource(it)) }
        when (index) {
            0 -> Chips(listOf(
                Taste.Genre.Pop to R.string.tp_g_pop, Taste.Genre.EdmHipHop to R.string.tp_g_edm,
                Taste.Genre.RockMetal to R.string.tp_g_rock, Taste.Genre.Acoustic to R.string.tp_g_acoustic,
                Taste.Genre.VocalPodcast to R.string.tp_g_vocal, Taste.Genre.FilmRegional to R.string.tp_g_film),
                { it in answers.genres }) { g ->
                onAnswers(answers.copy(genres = if (g in answers.genres) answers.genres - g else answers.genres + g))
            }
            1 -> Chips(listOf(
                Taste.Bother.Harsh to R.string.tp_b_harsh, Taste.Bother.Dull to R.string.tp_b_dull,
                Taste.Bother.Muddy to R.string.tp_b_muddy, Taste.Bother.Thin to R.string.tp_b_thin,
                Taste.Bother.Tiring to R.string.tp_b_tiring),
                { it in answers.bothers }) { b ->
                onAnswers(answers.copy(bothers = if (b in answers.bothers) answers.bothers - b else answers.bothers + b))
            }
            2 -> Chips(levels(R.string.tp_bass_light, R.string.tp_bass_strong), { it == answers.bass }) { onAnswers(answers.copy(bass = it)) }
            3 -> Chips(levels(R.string.tp_vocal_smooth, R.string.tp_vocal_forward), { it == answers.vocals }) { onAnswers(answers.copy(vocals = it)) }
            4 -> Chips(levels(R.string.tp_treble_soft, R.string.tp_treble_bright), { it == answers.treble }) { onAnswers(answers.copy(treble = it)) }
            5 -> Chips(listOf(Taste.Volume.Quiet to R.string.tp_vol_quiet, Taste.Volume.Medium to R.string.tp_vol_medium,
                Taste.Volume.Loud to R.string.tp_vol_loud), { it == answers.volume }) { onAnswers(answers.copy(volume = it)) }
            else -> WordsBox(words, onWords, ai, onUnderstand, onGetAi)
        }
    }
}

private fun levels(low: Int, high: Int) = listOf(
    Taste.Level.Low to low, Taste.Level.Natural to R.string.tp_natural, Taste.Level.High to high)

/** What the words question needs to know about JadOO AI. */
internal data class AiBoxState(
    val state: JadooAi.State,
    val reading: AiReply.Interpretation?,
    val busy: Boolean,
    val failed: Boolean
)

/**
 * The free-text question. With JadOO AI installed the user writes anything
 * (their day, their music, what bothers them) and the model reads it; without
 * it, an invitation to install it. Either way the A/B choices decide.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordsBox(words: String, onWords: (String) -> Unit, ai: AiBoxState, onUnderstand: () -> Unit, onGetAi: () -> Unit) {
    if (ai.state != JadooAi.State.Installed) {
        Column(
            Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Text(stringResource(R.string.ai_invite_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                 textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(if (ai.state == JadooAi.State.Unsupported) R.string.ai_unsupported else R.string.ai_invite_body),
                 style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            if (ai.state != JadooAi.State.Unsupported) {
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(if (ai.state is JadooAi.State.Downloading || ai.state == JadooAi.State.Checking)
                    R.string.ai_invite_progress else R.string.ai_invite_button), onClick = onGetAi)
            }
        }
        return
    }
    OutlinedTextField(
        value = words, onValueChange = { if (it.length <= AiReply.MAX_INPUT_CHARS) onWords(it) },
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 140.dp),
        placeholder = { Text(stringResource(R.string.ai_words_hint)) },
        shape = RoundedCornerShape(20.dp)
    )
    when {
        ai.busy -> Row(Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                       verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.ai_reading), style = MaterialTheme.typography.labelLarge)
        }
        ai.reading != null -> {
            if (ai.reading.summary.isNotBlank()) {
                Text("\u201C" + ai.reading.summary + "\u201D", style = MaterialTheme.typography.bodyLarge,
                     textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ai.reading.understood.forEach { (d, dir) ->
                    Text(dimName(d) + if (dir > 0) " \u2191" else " \u2193", style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.onPrimaryContainer,
                         modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                             .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            if (ai.reading.stereo) Note(Icons.Default.Info, stringResource(R.string.tp_words_stereo), warn = false)
            TextButton(onClick = onUnderstand, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_read_again))
            }
        }
        ai.failed -> {
            Note(Icons.Default.Info, stringResource(R.string.ai_read_failed), warn = true)
            TextButton(onClick = onUnderstand, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ai_read_again))
            }
        }
        words.isNotBlank() -> TextButton(onClick = onUnderstand, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_understand))
        }
    }
}

/**
 * One A/B choice. A plays as soon as the question appears; both must be
 * heard before answering. Which side carries the higher setting is random.
 */
@Composable
private fun CompareStep(c: Taste.Comparison, est: Taste.Estimator, ui: HearPrintUi, reference: ReferenceControl?,
                        onChoice: (Taste.Choice) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var playingA by remember(c) { mutableStateOf(true) }
    var heardA by remember(c) { mutableStateOf(true) }
    var heardB by remember(c) { mutableStateOf(false) }
    LaunchedEffect(c) { ui.onTastePreview(c.gains(true)) }
    fun play(a: Boolean) {
        if (playingA == a) return
        playingA = a
        if (a) heardA = true else heardB = true
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        ui.onTastePreview(c.gains(a))
    }
    val focus = stringResource(when (c.dim) {
        Dim.Bass -> R.string.tp_focus_bass
        Dim.Warmth -> R.string.tp_focus_warmth
        Dim.Clarity -> R.string.tp_focus_clarity
        Dim.Air -> R.string.tp_focus_air
    })
    val total = est.answered + est.remainingEstimate()
    StepLayout(footer = {
        val canAnswer = heardA && heardB
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { PrimaryButton(stringResource(R.string.tp_prefer_a), enabled = canAnswer) { onChoice(Taste.Choice.A) } }
            Box(Modifier.weight(1f)) { PrimaryButton(stringResource(R.string.tp_prefer_b), enabled = canAnswer) { onChoice(Taste.Choice.B) } }
        }
        TextButton(onClick = { onChoice(Taste.Choice.Tie) }, enabled = canAnswer,
                   modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
            Text(stringResource(if (canAnswer) R.string.tp_cant_tell else R.string.tp_listen_both))
        }
    }, scroll = false) {
        val progress by animateFloatAsState(est.answered.toFloat() / total.coerceAtLeast(1), JadooTheme.motion.gentle, label = "tp_cmp")
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.5f).height(6.dp).clip(CircleShape))
        Text(stringResource(R.string.tp_cmp_progress, est.answered + 1), style = MaterialTheme.typography.labelLarge,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Headline(focus)
        Body(stringResource(R.string.tp_cmp_body))
        reference?.let { r ->
            TextButton(onClick = r.toggle, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                Icon(if (r.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (r.playing) R.string.tp_ref_pause else R.string.tp_ref_play))
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SideTile("A", playingA, Modifier.weight(1f)) { play(true) }
            SideTile("B", !playingA, Modifier.weight(1f)) { play(false) }
        }
    }
}

@Composable
private fun SideTile(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val motion = JadooTheme.motion
    val fill by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        motion.fastSpec(), label = "tp_tile")
    val scale by animateFloatAsState(if (active) 1f else 0.94f, motion.expressive, label = "tp_tile_scale")
    val playing = stringResource(R.string.tp_playing)
    Box(
        modifier.heightIn(min = 160.dp).scale(scale).clip(RoundedCornerShape(32.dp)).background(fill)
            .semantics { role = Role.Tab; selected = active }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                 color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (active) Text(playing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun TasteResult(
    r: Taste.Profile,
    hearing: FloatArray,
    defaultName: String,
    onSave: (String) -> Unit,
    onStartOver: () -> Unit,
    onDiscard: () -> Unit
) {
    val taste = Taste.curve(r.gainsDb)
    val combined = FloatArray(taste.size) { (hearing.getOrNull(it) ?: 0f) + taste[it] }
    var name by remember { mutableStateOf(defaultName) }
    StepLayout(footer = {
        PrimaryButton(stringResource(R.string.tp_save), enabled = name.isNotBlank()) { onSave(name.trim()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onStartOver, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tp_start_over))
            }
            TextButton(onClick = onDiscard, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tp_discard))
            }
        }
    }) {
        Headline(stringResource(R.string.tp_result_headline))
        Body(stringResource(R.string.tp_result_body))
        Spacer(Modifier.height(24.dp))
        GlassSection { HearPrintChart(combined, Modifier.fillMaxWidth().height(200.dp)) }
        TasteSummary(r, Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(8.dp))
        if (r.consistent) Note(Icons.Default.CheckCircle, stringResource(R.string.tp_note_consistent), warn = false)
        else Note(Icons.Default.Info, stringResource(R.string.tp_note_inconsistent), warn = true)
        OutlinedTextField(
            value = name, onValueChange = { if (it.length <= Taste.MAX_NAME) name = it },
            label = { Text(stringResource(R.string.tp_name_label)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(20.dp)
        )
    }
}

/** The four dimensions as pills: "Bass +3.5 dB". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TasteSummary(r: Taste.Profile, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Taste.DIMS.forEach { d ->
            Text(stringResource(R.string.tp_value, dimName(d), signedDb(r[d])), style = MaterialTheme.typography.labelLarge,
                 color = MaterialTheme.colorScheme.onSecondaryContainer,
                 modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                     .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}
