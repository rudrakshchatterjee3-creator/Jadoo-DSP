package com.jadoo.amp.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

private val NavyBackground = Color(0xFF0F172A)
private val BrandCyan = Color(0xFF22D3EE)
private val BlobViolet = Color(0xFF7C3AED)
private val BlobTeal = Color(0xFF0EA5A4)

/**
 * Full-screen "What's New" announcement shown once per newly detected
 * GitHub release. Background is a slow-drifting abstract aurora of soft
 * radial blobs in the app's brand colors; the changelog reveals line by
 * line rather than appearing all at once.
 */
@Composable
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
        Surface(modifier = Modifier.fillMaxSize(), color = NavyBackground) {
            Box(modifier = Modifier.fillMaxSize()) {
                AuroraBackground()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_jadoo_dsp),
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "What's New",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(50.dp),
                        color = BrandCyan.copy(alpha = 0.18f)
                    ) {
                        Text(
                            release.tagName,
                            color = BrandCyan,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(Modifier.height(28.dp))

                    val lines = remember(release.body) { UpdateChecker.changelogLines(release.body) }
                    var revealedCount by remember { mutableIntStateOf(0) }
                    LaunchedEffect(lines) {
                        revealedCount = 0
                        lines.indices.forEach { i ->
                            delay(120)
                            revealedCount = i + 1
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
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

                    Spacer(Modifier.height(36.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                            onDownload()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandCyan, contentColor = NavyBackground),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download Update", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onRemindLater,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Remind Me Later")
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Made with Love, by Rudraksh Chatterjee (JadOO)",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
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
                .background(BrandCyan)
        )
        Text(
            text,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 15.sp,
            lineHeight = 21.sp
        )
    }
}

/** Three soft, slow-drifting radial-gradient blobs — an abstract "aurora" backdrop. */
@Composable
private fun AuroraBackground() {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase1 by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(22000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase1"
    )
    val phase2 by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(28000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase2"
    )
    val phase3 by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(34000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase3"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        fun blob(phaseDeg: Float, radiusFrac: Float, color: Color, cx: Float, cy: Float, orbit: Float) {
            val rad = Math.toRadians(phaseDeg.toDouble())
            val x = cx + (Math.cos(rad) * orbit).toFloat()
            val y = cy + (Math.sin(rad) * orbit).toFloat()
            val r = w * radiusFrac
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f)),
                    center = Offset(x, y),
                    radius = r
                ),
                radius = r,
                center = Offset(x, y)
            )
        }

        blob(phase1, 0.55f, BrandCyan, w * 0.25f, h * 0.25f, w * 0.18f)
        blob(phase2, 0.5f, BlobViolet, w * 0.8f, h * 0.35f, w * 0.15f)
        blob(phase3, 0.6f, BlobTeal, w * 0.5f, h * 0.85f, w * 0.2f)
    }
}
