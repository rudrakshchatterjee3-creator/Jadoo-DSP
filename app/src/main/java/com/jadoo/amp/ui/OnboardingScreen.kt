package com.jadoo.amp.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── Page data ────────────────────────────────────────────────────────────────

private data class OnboardingPage(
    val tag: String,
    val headline: String,
    val body: String,
    val accentColor: Color,
    val visual: OnboardingVisual
)

private enum class OnboardingVisual {
    Waveform, Headphones, EqBars, Surround, Constellation
}

private val onboardingPages = listOf(
    OnboardingPage(
        tag = "JADOO DSP",
        headline = "Your music deserves better.",
        body = "Professional-grade audio processing built from first principles — running entirely on your phone, in real time.",
        accentColor = Color(0xFF9AD48F),
        visual = OnboardingVisual.Waveform
    ),
    OnboardingPage(
        tag = "THE MOMENT",
        headline = "You've heard it.\nThat feeling.",
        body = "Great headphones. Your best track. And something still feels off.\n\nThe bass is muddy. The vocals drift. The mix sounds lifeless, compressed, flat.",
        accentColor = Color(0xFF80DEEA),
        visual = OnboardingVisual.Headphones
    ),
    OnboardingPage(
        tag = "GRAPHIC EQ",
        headline = "Precision that listens.",
        body = "A full 15-band graphic EQ at ISO standard frequencies, plus an 8-band parametric EQ for surgical control — shape your sound exactly the way you want it.",
        accentColor = Color(0xFFFFCC80),
        visual = OnboardingVisual.EqBars
    ),
    OnboardingPage(
        tag = "ANALOG BASS · SURROUND+",
        headline = "Warmth. Width. Life.",
        body = "The Analog Bass Engine models vintage tube and transformer circuits — saturation, drift, Pultec EQ curves — to make bass feel three-dimensional.\n\nSurround+ stretches your stereo field without ever moving the vocals.",
        accentColor = Color(0xFFCE93D8),
        visual = OnboardingVisual.Surround
    ),
    OnboardingPage(
        tag = "READY",
        headline = "Your ears deserve this.",
        body = "Everything runs locally. No cloud. No subscriptions. No compromises.\n\nGrant permissions when asked — the DSP engine needs them to reach your music.",
        accentColor = Color(0xFF9AD48F),
        visual = OnboardingVisual.Constellation
    )
)

// ── Root composable ──────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage
    val accent = onboardingPages[currentPage].accentColor

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080D08))
    ) {
        // Ambient radial glow behind content
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = accent.copy(alpha = 0.07f),
                radius = size.minDimension * 0.9f,
                center = Offset(size.width * 0.5f, size.height * 0.3f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Skip row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (currentPage < onboardingPages.size - 1) {
                    TextButton(onClick = onFinished) {
                        Text("Skip", color = Color.White.copy(alpha = 0.38f), fontSize = 14.sp)
                    }
                }
            }

            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { index ->
                PageContent(page = onboardingPages[index])
            }

            // Dots + button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                PageDots(
                    pageCount = onboardingPages.size,
                    current = currentPage,
                    accentColor = accent
                )

                val isLast = currentPage == onboardingPages.size - 1
                Button(
                    onClick = {
                        if (isLast) onFinished()
                        else scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color(0xFF071007)
                    )
                ) {
                    Text(
                        text = if (isLast) "Get started" else "Continue",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── Dot indicator ────────────────────────────────────────────────────────────

@Composable
private fun PageDots(pageCount: Int, current: Int, accentColor: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until pageCount) {
            val selected = i == current
            val widthDp by animateFloatAsState(
                targetValue = if (selected) 24f else 8f,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                label = "dot_$i"
            )
            Box(
                modifier = Modifier
                    .width(widthDp.dp)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) accentColor else Color.White.copy(alpha = 0.22f)
                    )
            )
        }
    }
}

// ── Page layout ──────────────────────────────────────────────────────────────

@Composable
private fun PageContent(page: OnboardingPage) {
    val infinite = rememberInfiniteTransition(label = "pg")
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3200, easing = EaseInOutCubic), RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // A fixed-size visual box plus weight(0.5f)/weight(1f) spacers had no
    // scroll fallback — on shorter screens, or pages with longer body text
    // (e.g. the Analog Bass/Surround+ page), the available space could run
    // out and Compose would collapse the spacers to near-zero, jamming the
    // visual and text together with no breathing room ("muffled up"). A
    // scrollable column with fixed, modest gaps instead of competing
    // weights guarantees the same comfortable spacing on every screen size,
    // scrolling only on the rare page/device where it's actually needed.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        // Visual illustration
        Box(
            modifier = Modifier.size(200.dp),
            contentAlignment = Alignment.Center
        ) {
            when (page.visual) {
                OnboardingVisual.Waveform      -> WaveformVisual(page.accentColor, pulse)
                OnboardingVisual.Headphones    -> HeadphonesVisual(page.accentColor, pulse)
                OnboardingVisual.EqBars        -> EqBarsVisual(page.accentColor, pulse)
                OnboardingVisual.Surround      -> SurroundVisual(page.accentColor, pulse)
                OnboardingVisual.Constellation -> ConstellationVisual(page.accentColor, pulse)
            }
        }

        Spacer(Modifier.height(28.dp))

        // Chip tag
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(page.accentColor.copy(alpha = 0.13f))
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Text(
                page.tag,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = page.accentColor,
                letterSpacing = 1.6.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            page.headline,
            fontSize = 25.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            lineHeight = 31.sp
        )

        Spacer(Modifier.height(14.dp))

        Text(
            page.body,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ── Visuals ──────────────────────────────────────────────────────────────────

@Composable
private fun WaveformVisual(accent: Color, pulse: Float) {
    val infinite = rememberInfiniteTransition(label = "wv")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "phase"
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height; val mid = h / 2f

        data class WaveSpec(val amp: Float, val freq: Float, val off: Float, val alpha: Float, val sw: Float)
        val waves = listOf(
            WaveSpec(0.26f, 1.0f, 0.0f, 0.90f, 3.0f),
            WaveSpec(0.16f, 1.7f, 0.9f, 0.60f, 2.2f),
            WaveSpec(0.09f, 2.4f, 1.8f, 0.35f, 1.6f)
        )
        waves.forEach { ws ->
            val a = ws.amp * h * (0.75f + 0.25f * pulse)
            val path = Path()
            var first = true
            for (xi in 0..w.toInt() step 2) {
                val y = mid + a * sin(ws.freq * xi * 2.0 * PI / w + phase + ws.off).toFloat()
                if (first) { path.moveTo(xi.toFloat(), y); first = false }
                else path.lineTo(xi.toFloat(), y)
            }
            drawPath(path, accent.copy(alpha = ws.alpha), style = Stroke(ws.sw, cap = StrokeCap.Round))
        }
        val cx = w / 2f
        drawCircle(accent, 5f + 2f * pulse, Offset(cx, mid))
        drawCircle(accent.copy(0.20f), 20f + 8f * pulse, Offset(cx, mid))
    }
}

@Composable
private fun HeadphonesVisual(accent: Color, pulse: Float) {
    val infinite = rememberInfiniteTransition(label = "hp")
    val ring by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "ring"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(accent.copy(0.05f + 0.04f * ring), size.minDimension * 0.42f, c)
            drawCircle(accent.copy(0.03f + 0.02f * ring), size.minDimension * 0.50f, c)
            // Sound arcs
            listOf(0.62f, 0.76f).forEachIndexed { i, rf ->
                val r = size.minDimension * (rf + 0.04f * ring)
                drawArc(
                    accent.copy(alpha = (0.45f - i * 0.18f) * ring),
                    startAngle = 195f, sweepAngle = 150f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(2.4f - i * 0.5f)
                )
                drawArc(
                    accent.copy(alpha = (0.45f - i * 0.18f) * ring),
                    startAngle = -15f, sweepAngle = -150f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(2.4f - i * 0.5f)
                )
            }
        }
        Icon(Icons.Filled.Headphones, null, Modifier.size(90.dp), accent.copy(0.80f + 0.20f * pulse))
    }
}

@Composable
private fun EqBarsVisual(accent: Color, pulse: Float) {
    val BAR_COUNT = 15
    val infinite = rememberInfiniteTransition(label = "eq")
    val heights = (0 until BAR_COUNT).map { i ->
        infinite.animateFloat(
            initialValue = 0.12f + (i % 4) * 0.08f,
            targetValue  = 0.45f + (i % 5) * 0.11f,
            animationSpec = infiniteRepeatable(
                tween(1100 + i * 70, easing = EaseInOutCubic), RepeatMode.Reverse
            ),
            label = "h$i"
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val barW = w / (BAR_COUNT * 1.7f)
        val step  = w / BAR_COUNT.toFloat()
        heights.forEachIndexed { i, ha ->
            val bh = h * (ha.value + 0.08f * pulse)
            val x  = i * step + step / 2f - barW / 2f
            val top = (h - bh) / 2f
            drawRoundRect(
                accent.copy(0.7f + 0.3f * pulse),
                Offset(x, top),
                Size(barW, bh),
                CornerRadius(barW / 2)
            )
        }
    }
}

@Composable
private fun SurroundVisual(accent: Color, pulse: Float) {
    val infinite = rememberInfiniteTransition(label = "sv")
    val rot by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "rot"
    )
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.38f

        listOf(0.55f, 0.72f, 0.88f).forEachIndexed { i, frac ->
            val ar = r * frac
            val alpha = (0.38f - i * 0.08f) + 0.10f * pulse
            val start = rot * (0.4f + i * 0.25f)
            // Left arc
            drawArc(accent.copy(alpha), start - 20f, 200f, false,
                    Offset(c.x - ar, c.y - ar), Size(ar * 2, ar * 2), style = Stroke(2.2f + i * 0.4f))
            // Right arc (mirrored)
            drawArc(accent.copy(alpha), start + 160f, 200f, false,
                    Offset(c.x - ar, c.y - ar), Size(ar * 2, ar * 2), style = Stroke(2.2f + i * 0.4f))
        }
        // Center dot
        drawCircle(accent.copy(0.25f + 0.12f * pulse), r * 0.14f, c)
        drawCircle(accent, r * 0.055f, c)

        // Orbiting particles
        for (d in 0 until 6) {
            val angle = (d * 60f + rot * 1.6f) * PI.toFloat() / 180f
            val dx = c.x + r * 0.94f * cos(angle)
            val dy = c.y + r * 0.94f * sin(angle)
            val pa = 0.35f + 0.45f * ((sin(angle + pulse * PI.toFloat()) + 1f) / 2f)
            drawCircle(accent.copy(pa), 3.5f, Offset(dx, dy))
        }
    }
}

@Composable
private fun ConstellationVisual(accent: Color, pulse: Float) {
    val infinite = rememberInfiniteTransition(label = "cs")
    val twinkle by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "tw"
    )
    val stars = remember {
        listOf(
            0.18f to 0.12f, 0.50f to 0.06f, 0.80f to 0.18f,
            0.10f to 0.44f, 0.34f to 0.36f, 0.66f to 0.28f,
            0.90f to 0.48f, 0.20f to 0.70f, 0.50f to 0.60f,
            0.76f to 0.74f, 0.38f to 0.86f, 0.62f to 0.92f
        )
    }
    val edges = remember {
        listOf(0 to 1, 1 to 2, 0 to 3, 3 to 4, 4 to 5, 5 to 6,
               4 to 8, 7 to 8, 8 to 9, 8 to 10, 10 to 11)
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        edges.forEach { (a, b) ->
            drawLine(
                accent.copy(0.18f + 0.07f * pulse),
                Offset(stars[a].first * w, stars[a].second * h),
                Offset(stars[b].first * w, stars[b].second * h),
                strokeWidth = 1.1f
            )
        }
        stars.forEachIndexed { i, (sx, sy) ->
            val t = (twinkle + i * 0.083f) % 1f
            val sr = 2.8f + 2f * t
            val sa = 0.45f + 0.55f * t
            val pos = Offset(sx * w, sy * h)
            drawCircle(accent.copy(sa), sr, pos)
            drawCircle(accent.copy(sa * 0.22f), sr * 2.8f, pos)
        }
        // Central bright star
        val cx = w / 2f; val cy = h * 0.54f
        val cr = 5.5f + 2.5f * pulse
        drawCircle(accent, cr, Offset(cx, cy))
        drawCircle(accent.copy(0.28f + 0.18f * pulse), cr * 3f, Offset(cx, cy))
        drawCircle(accent.copy(0.08f + 0.06f * pulse), cr * 6.5f, Offset(cx, cy))
    }
}
