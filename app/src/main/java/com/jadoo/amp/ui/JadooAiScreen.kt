package com.jadoo.amp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadoo.amp.R
import com.jadoo.amp.ai.JadooAi
import com.jadoo.amp.ui.components.GlassSection
import com.jadoo.amp.ui.theme.JadooTheme
import java.util.Locale

private fun mb(bytes: Long) = String.format(Locale.US, "%.0f", bytes / 1_000_000.0)

/**
 * JadOO AI's install page: what it is, what it costs (one download), that it
 * stays on the phone, and a progress ring while it downloads. The download
 * keeps going if this page is closed.
 */
@Composable
fun JadooAiScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { JadooAi.refresh(context) }
    val state by JadooAi.state.collectAsState()
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.scr_cd_close))
            }
        }
        Box(Modifier.fillMaxSize().padding(horizontal = JadooTheme.dimens.screenPadding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                StepLayout(footer = {
                    when (val s = state) {
                        JadooAi.State.NotInstalled, is JadooAi.State.Failed ->
                            PrimaryButton(stringResource(R.string.ai_install, mb(JadooAi.DOWNLOAD_BYTES))) { JadooAi.install(context) }
                        is JadooAi.State.Downloading ->
                            TextButton(onClick = { JadooAi.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_pause)) }
                        JadooAi.State.Installed -> {
                            PrimaryButton(stringResource(R.string.ai_done), onClick = onClose)
                            TextButton(onClick = { JadooAi.remove(context) }, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.ai_remove))
                            }
                        }
                        JadooAi.State.Checking -> {}
                        JadooAi.State.Unsupported -> PrimaryButton(stringResource(R.string.ai_done), onClick = onClose)
                    }
                }) {
                    val s = state
                    if (s is JadooAi.State.Downloading || s is JadooAi.State.Checking) ProgressRing(s)
                    else Hero(Icons.Default.AutoAwesome, pulsing = s == JadooAi.State.Installed)
                    Headline(stringResource(R.string.ai_title))
                    Body(stringResource(R.string.ai_pitch))
                    Spacer(Modifier.padding(top = 16.dp))
                    GlassSection {
                        InfoLine(Icons.Default.Lock, stringResource(R.string.ai_private))
                        InfoLine(Icons.Default.CloudDownload, stringResource(R.string.ai_size, mb(JadooAi.DOWNLOAD_BYTES)))
                        InfoLine(Icons.Default.Memory, stringResource(R.string.ai_model))
                    }
                    when (s) {
                        JadooAi.State.Installed -> Note(Icons.Default.CheckCircle, stringResource(R.string.ai_ready), warn = false)
                        JadooAi.State.Unsupported -> Note(Icons.Default.Info, stringResource(R.string.ai_unsupported), warn = true)
                        is JadooAi.State.Failed -> Note(Icons.Default.Info, stringResource(when (s.why) {
                            JadooAi.Failure.Network -> R.string.ai_fail_network
                            JadooAi.Failure.Storage -> R.string.ai_fail_storage
                            JadooAi.Failure.Corrupt -> R.string.ai_fail_corrupt
                        }), warn = true)
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProgressRing(s: JadooAi.State) {
    val fraction = if (s is JadooAi.State.Downloading) s.doneBytes.toFloat() / s.totalBytes else 1f
    val shown by animateFloatAsState(fraction, JadooTheme.motion.gentle, label = "ai_progress")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val label = if (s is JadooAi.State.Downloading)
        stringResource(R.string.ai_progress, mb(s.doneBytes), mb(s.totalBytes)) else stringResource(R.string.ai_checking)
    Box(
        Modifier.size(168.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 10.dp.toPx()
            drawArc(track, -90f, 360f, false, style = Stroke(w))
            drawArc(primary, -90f, 360f * shown, false, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(shown * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                 modifier = Modifier.semantics { heading() })
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
