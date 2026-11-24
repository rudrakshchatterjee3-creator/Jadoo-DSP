package com.jadoo.amp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jadoo.amp.update.ApkUpdater
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jadoo.amp.R
import com.jadoo.amp.update.ReleaseInfo
import com.jadoo.amp.update.UpdateChecker
import kotlinx.coroutines.delay

// These were five hardcoded colours — a navy background and a cyan accent from
// the old off-brand launcher icon. The dialog therefore stayed dark navy in
// light mode regardless of the user's theme, which was the most visible
// theming defect in the app. They are now theme roles, resolved at use.
//
// This is a mechanical substitution and nothing about the layout changed.

/**
 * Full-screen "What's New" announcement shown once per newly detected
 * GitHub release. Background is a slow-drifting abstract aurora of soft
 * radial blobs in the app's brand colors; the changelog reveals line by
 * line rather than appearing all at once.
 */
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // read in click handlers; a language change recreates the activity
fun WhatsNewDialog(
    release: ReleaseInfo,
    onDownload: () -> Unit,
    onRemindLater: () -> Unit
) {
    val context = LocalContext.current
    // Backing out of the dialog (tap outside / back button) is treated the
    // same as "Remind me later" — it's a dismissal without acting, not "I
    // installed it," so it should snooze rather than vanish forever AND
    // rather than reappear instantly on the very next recomposition.
    Dialog(onDismissRequest = onRemindLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val glassWindow = com.jadoo.amp.ui.components.GlassDialogWindow()
        val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        val scheme = MaterialTheme.colorScheme
        val clock = com.jadoo.amp.ui.components.rememberSceneClock()
        val motion = com.jadoo.amp.ui.theme.JadooTheme.motion.enabled
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            com.jadoo.amp.ui.components.JadooSurface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp).heightIn(max = maxCardHeight),
                shape = RoundedCornerShape(34.dp),
                color = scheme.surfaceContainer,
                level = com.jadoo.amp.ui.theme.GlassLevel.Overlay,
                windowBlur = glassWindow,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), scheme.primary.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f)))
                )
            ) {
              Box(modifier = Modifier.fillMaxWidth().drawWithContent {
                    drawContent()
                    // A soft specular band drifting across the glass, like light
                    // moving over a lens. Drawn above the content, touches nothing.
                    if (motion) {
                        val t = clock.value
                        val u = (t / 7f) - kotlin.math.floor(t / 7f)
                        val x = size.width * (u * 1.8f - 0.4f)
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                                start = Offset(x - size.width * 0.25f, 0f),
                                end = Offset(x + size.width * 0.05f, size.height)
                            )
                        )
                    }
                }) {
                CardAurora()

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_jadoo_dsp),
                        contentDescription = null,
                        modifier = Modifier.size(60.dp).clip(RoundedCornerShape(18.dp))
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.upd_title),
                        color = scheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.upd_subtitle, release.tagName.removePrefix("v")),
                        color = scheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = scheme.onSurface.copy(alpha = 0.10f))
                    Spacer(Modifier.height(16.dp))

                    Text(
                        stringResource(R.string.upd_whats_new),
                        color = scheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Image(
                        painter = painterResource(R.drawable.upd_banner),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(22.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                    )
                    Spacer(Modifier.height(20.dp))

                    val lines = remember(release.body) { UpdateChecker.changelogLines(release.body).take(5) }
                    var revealedCount by remember { mutableIntStateOf(0) }
                    LaunchedEffect(lines) {
                        revealedCount = 0
                        lines.indices.forEach { i ->
                            delay(120)
                            revealedCount = i + 1
                        }
                    }
                    Text(
                        stringResource(R.string.upd_highlights),
                        color = scheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        lines.forEachIndexed { index, line ->
                            AnimatedVisibility(
                                visible = index < revealedCount,
                                enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 3 }
                            ) {
                                ChangelogLine(line)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // ── Lane B: in-app update ─────────────────────────────
                    // Still a package install — Android has no way to swap an
                    // app's code without one — but the browser/Downloads/file
                    // manager detour is gone, and the same signing key means
                    // it updates in place with every setting preserved.
                    // Falls back to the release page whenever the release has
                    // no APK asset attached.
                    val scope = rememberCoroutineScope()
                    var state by remember { mutableStateOf<ApkUpdater.State>(ApkUpdater.State.Idle) }

                    when (val s = state) {
                        is ApkUpdater.State.Downloading -> {
                            Text(stringResource(R.string.upd_downloading, (s.progress * 100).toInt()),
                                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { s.progress },
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        ApkUpdater.State.Verifying -> {
                            Text(stringResource(R.string.upd_verifying),
                                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), fontSize = 13.sp)
                        }
                        ApkUpdater.State.Installing -> {
                            Text(stringResource(R.string.upd_opening_installer),
                                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), fontSize = 13.sp)
                        }
                        is ApkUpdater.State.Failed -> {
                            Text(s.reason,
                                 color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                                 textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                                onDownload()
                            }) {
                                Text(stringResource(R.string.upd_open_release_page), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        ApkUpdater.State.Idle -> Unit
                    }

                    if (state is ApkUpdater.State.Idle || state is ApkUpdater.State.Failed) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (release.apkAssetUrl == null) {
                                    // No APK attached to this release — the
                                    // browser is the only route.
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                                    )
                                    onDownload()
                                    return@Button
                                }
                                if (!ApkUpdater.canInstall(context)) {
                                    ApkUpdater.requestInstallPermission(context)
                                    state = ApkUpdater.State.Failed(
                                        context.getString(R.string.upd_allow_install_then_retry)
                                    )
                                    return@Button
                                }
                                scope.launch {
                                    state = ApkUpdater.State.Downloading(0f)
                                    val file = ApkUpdater.download(context, release) { p ->
                                        state = ApkUpdater.State.Downloading(p)
                                    }
                                    if (file == null) {
                                        state = ApkUpdater.State.Failed(context.getString(R.string.upd_download_failed))
                                        return@launch
                                    }
                                    // Signature check before ANY install is
                                    // attempted — see ApkUpdater.verifyApk.
                                    state = ApkUpdater.State.Verifying
                                    val problem = ApkUpdater.verifyApk(context, file)
                                    if (problem != null) {
                                        file.delete()
                                        state = ApkUpdater.State.Failed(problem)
                                        return@launch
                                    }
                                    state = ApkUpdater.State.Installing
                                    val installError = ApkUpdater.install(context, file)
                                    state = if (installError != null) {
                                        ApkUpdater.State.Failed(installError)
                                    } else {
                                        // A null return means the session was
                                        // COMMITTED, not that the install
                                        // finished — the system installer takes
                                        // over from here and reports its outcome
                                        // to InstallResultReceiver, which has no
                                        // way to reach this dialog.
                                        //
                                        // Staying on Installing therefore left
                                        // "Opening installer…" on screen forever
                                        // with both buttons hidden (they render
                                        // only for Idle/Failed) whenever the user
                                        // declined the system prompt — which is
                                        // silent by design — or the confirmation
                                        // activity failed to launch. Returning to
                                        // Idle restores the buttons, so backing
                                        // out of the system installer leaves a
                                        // usable dialog to retry or dismiss.
                                        ApkUpdater.State.Idle
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                        ) {
                            Text(
                                stringResource(if (release.apkAssetUrl != null) R.string.upd_install_update else R.string.upd_download_update),
                                fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = onRemindLater,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(stringResource(R.string.upd_remind_later), fontSize = 16.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        stringResource(R.string.upd_made_with_love),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
              }
            }
        }
    }
}

@Composable
private fun ChangelogLine(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            fontSize = 15.sp,
            lineHeight = 21.sp
        )
    }
}

/** Soft, slow-drifting pools of brand colour behind the card content. */
@Composable
private fun BoxScope.CardAurora() {
    val blobA = MaterialTheme.colorScheme.primary
    val blobB = MaterialTheme.colorScheme.tertiary
    val clock = com.jadoo.amp.ui.components.rememberSceneClock()
    Canvas(modifier = Modifier.matchParentSize()) {
        val t = clock.value
        fun pool(color: Color, cx: Float, cy: Float, r: Float, a: Float) {
            val c = Offset(size.width * cx, size.height * cy)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), c, size.width * r), size.width * r, c)
        }
        pool(blobA, 0.25f + 0.08f * kotlin.math.sin(t * 0.20f), 0.06f, 0.75f, 0.24f)
        pool(blobB, 0.85f + 0.05f * kotlin.math.cos(t * 0.17f), 0.45f + 0.05f * kotlin.math.sin(t * 0.13f), 0.6f, 0.10f)
        pool(blobA, 0.5f + 0.1f * kotlin.math.cos(t * 0.11f), 1.0f, 0.8f, 0.12f)
    }
}
