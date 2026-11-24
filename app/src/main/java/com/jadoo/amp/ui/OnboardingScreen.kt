package com.jadoo.amp.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadoo.amp.R
import com.jadoo.amp.ui.components.Cine
import com.jadoo.amp.ui.components.areaUnder
import com.jadoo.amp.ui.components.breathe
import com.jadoo.amp.ui.components.frac
import com.jadoo.amp.ui.components.glowStroke
import com.jadoo.amp.ui.components.hash01
import com.jadoo.amp.ui.components.mix
import com.jadoo.amp.ui.components.pointAt
import com.jadoo.amp.ui.components.rememberSceneClock
import com.jadoo.amp.ui.components.seg
import com.jadoo.amp.ui.components.smoothPath
import com.jadoo.amp.ui.components.spark
import com.jadoo.amp.ui.components.trimmed
import com.jadoo.amp.ui.theme.BrandPalette
import com.jadoo.amp.ui.theme.BrandScheme
import com.jadoo.amp.ui.theme.JadooTheme
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// ── Page data ────────────────────────────────────────────────────────────────

private data class OnboardingPage(
    @StringRes val tag: Int,
    @StringRes val headline: Int,
    @StringRes val body: Int,
    val accentColor: Color,
    val visual: OnboardingVisual
)

private enum class OnboardingVisual {
    Waveform, Headphones, EqBars, Surround, Constellation
}

// ── Why this screen is NOT theme-following ───────────────────────────────────
// Onboarding runs before the user has any theme preference — there is nothing
// to follow yet. It is also art-directed: the copy, the glow and the visuals
// are composed against a dark ground, and rendering them on a light scheme
// would not merely recolour the screen, it would break it.
//
// So rather than leave it accidentally dark via hardcoded literals, it is
// PINNED to the brand's dark expression. Same outcome on screen, but now it is
// a decision with a reason instead of five arbitrary colours.
//
// The five page accents used to be an unrelated green/cyan/amber/violet set.
// They are now a ramp anchored on the brand gold, warm through cool and back,
// so the sequence has a direction and every page still belongs to JadOO.
private val onboardingPages = listOf(
    OnboardingPage(
        tag = R.string.scr_onb_tag_1,
        headline = R.string.scr_onb_headline_1,
        body = R.string.scr_onb_body_1,
        accentColor = BrandPalette.Gold,
        visual = OnboardingVisual.Waveform
    ),
    OnboardingPage(
        tag = R.string.scr_onb_tag_2,
        headline = R.string.scr_onb_headline_2,
        body = R.string.scr_onb_body_2,
        accentColor = Color(0xFFE8C67A),   // gold, lifted and softened
        visual = OnboardingVisual.Headphones
    ),
    OnboardingPage(
        tag = R.string.scr_onb_tag_3,
        headline = R.string.scr_onb_headline_3,
        body = R.string.scr_onb_body_3,
        accentColor = Color(0xFFA6CFC4),   // the one cool counterweight, as in the scheme
        visual = OnboardingVisual.EqBars
    ),
    OnboardingPage(
        tag = R.string.scr_onb_tag_4,
        headline = R.string.scr_onb_headline_4,
        body = R.string.scr_onb_body_4,
        accentColor = BrandPalette.Gold,   // returns home
        visual = OnboardingVisual.Constellation
    )
)

// ── Root composable ──────────────────────────────────────────────────────────
//
// Directed like a title sequence. One frame clock drives everything; each page
// gets its own "cut" time, restarted whenever it becomes the page being swiped
// to, and every element (the visual's acts, the tag, each headline line, the
// body) enters on its own beat of that time. The clock is read in draw and
// layer blocks only, so the screen redraws without recomposing.

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage
    val accent by animateColorAsState(onboardingPages[pagerState.targetPage].accentColor,
                                      tween(600), label = "onboarding_accent")
    val clock = rememberSceneClock()
    val motion = JadooTheme.motion.enabled
    val cutAt = remember { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(pagerState.targetPage) { cutAt[pagerState.targetPage] = clock.value }
    fun pageTime(index: Int): Float =
        if (!motion) Cine.SETTLED_S else (clock.value - (cutAt[index] ?: clock.value)).coerceAtLeast(0f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandScheme.Dark.background)
    ) {
        // Aurora: three slow pools of light in the page's colour, drifting on
        // incommensurate periods so the sky never visibly repeats.
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            fun pool(cx: Float, cy: Float, r: Float, a: Float) {
                val c = Offset(size.width * cx, size.height * cy)
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = a), Color.Transparent), c, size.minDimension * r),
                    size.minDimension * r, c)
            }
            pool(0.5f + 0.12f * sin(t * 0.11f), 0.22f + 0.04f * cos(t * 0.09f), 1.0f, 0.17f)
            pool(0.15f + 0.08f * cos(t * 0.07f), 0.55f + 0.06f * sin(t * 0.13f), 0.7f, 0.07f)
            pool(0.92f + 0.06f * sin(t * 0.05f), 0.38f + 0.07f * sin(t * 0.08f), 0.6f, 0.06f)
            // Bottom vignette so the copy always sits on deep ground.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, BrandScheme.Dark.background.copy(alpha = 0.85f)),
                size.height * 0.45f, size.height))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (currentPage < onboardingPages.size - 1) {
                    TextButton(onClick = onFinished) {
                        Text(stringResource(R.string.scr_onb_skip), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { index ->
                val offset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                PageContent(page = onboardingPages[index], offset = offset, time = { pageTime(index) },
                            clock = { clock.value })
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.Start,
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
                        .height(54.dp)
                        .drawWithContent {
                            drawContent()
                            // A slow sheen across the button, once every few seconds.
                            if (motion) {
                                val u = frac(clock.value / 4.2f)
                                if (u < 0.35f) {
                                    val x = size.width * (u / 0.35f) * 1.4f - size.width * 0.2f
                                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                                        x - 60f, x + 60f), Offset(x - 60f, 0f), Size(120f, size.height))
                                }
                            }
                        },
                    shape = RoundedCornerShape(27.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = BrandScheme.Dark.onPrimary
                    )
                ) {
                    Text(
                        text = stringResource(if (isLast) R.string.scr_onb_start else R.string.scr_onb_continue),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
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
                targetValue = if (selected) 28f else 8f,
                animationSpec = tween(420, easing = FastOutSlowInEasing),
                label = "dot_$i"
            )
            Box(
                modifier = Modifier
                    .width(widthDp.dp)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) Brush.horizontalGradient(listOf(accentColor, accentColor.copy(alpha = 0.6f)))
                        else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.22f)))
                    )
            )
        }
    }
}

// ── Page layout ──────────────────────────────────────────────────────────────

@Composable
private fun PageContent(page: OnboardingPage, offset: Float, time: () -> Float, clock: () -> Float) {
    val motion = JadooTheme.motion.enabled
    val distance = abs(offset).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Parallax: the visual drifts at a third of the page's speed and
        // recedes a touch as it leaves.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .graphicsLayer {
                    if (motion) {
                        translationX = offset * size.width * 0.66f
                        val scale = 1f - distance * 0.12f
                        scaleX = scale; scaleY = scale
                    }
                    alpha = 1f - distance * 0.7f
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val t = time()
                val g = clock()
                when (page.visual) {
                    OnboardingVisual.Waveform      -> lensScene(page.accentColor, t, g)
                    OnboardingVisual.Headphones    -> measureScene(page.accentColor, t, g)
                    OnboardingVisual.EqBars        -> shapeScene(page.accentColor, t, g)
                    OnboardingVisual.Surround      -> lensScene(page.accentColor, t, g)
                    OnboardingVisual.Constellation -> privateScene(page.accentColor, t, g)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        val lines = stringResource(page.headline).split("\n")
        Column(Modifier.graphicsLayer {
            if (motion) translationX = offset * 48.dp.toPx()
            alpha = 1f - distance
        }) {
            Text(
                stringResource(page.tag).uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = page.accentColor,
                letterSpacing = 1.6.sp,
                modifier = Modifier.reveal(time, 0.35f, 10.dp.value)
            )
            Spacer(Modifier.height(12.dp))
            lines.forEachIndexed { i, line ->
                Text(
                    line,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    lineHeight = 42.sp,
                    modifier = Modifier.reveal(time, 0.5f + i * 0.12f, 22.dp.value)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(page.body),
                fontSize = 17.sp,
                color = Color.White.copy(alpha = 0.72f),
                lineHeight = 25.sp,
                modifier = Modifier.reveal(time, 0.75f + lines.size * 0.12f, 14.dp.value)
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Fades and rises into place on its beat of the page's [time]. */
private fun Modifier.reveal(time: () -> Float, at: Float, riseDp: Float): Modifier = graphicsLayer {
    val a = seg(time(), at, 0.9f)
    alpha = a
    translationY = (1f - a) * riseDp * density
}

// ── Scenes ───────────────────────────────────────────────────────────────────
// Each takes the page's cut time [t] (its entrance) and the free-running
// clock [g] (its life once settled).

/**
 * "Every app. Better sound." A ragged grey signal flows in from the left,
 * passes through a ring of light (JadOO), and leaves as a smooth golden
 * ribbon.
 */
private fun DrawScope.lensScene(accent: Color, t: Float, g: Float) {
    val mid = size.height * 0.5f
    val lensX = size.width * 0.5f
    val enter = seg(t, 0.0f, 1.4f, Cine.Draw)
    val lensIn = seg(t, 0.3f, 1.0f)
    val warm = seg(t, 0.9f, 1.4f)
    val n = 140
    fun raw(x: Float): Float {
        val u = x / size.width
        return sin(u * 26f - g * 3.1f) * 0.55f + sin(u * 61f - g * 5.3f) * 0.25f + sin(u * 113f + g * 2f) * 0.12f
    }
    fun clean(x: Float, k: Float): Float = sin(x / size.width * 14f - g * 1.9f + k) * (0.62f + 0.12f * k)

    // Raw: left of the lens only.
    val rawPath = Path().apply {
        for (i in 0..n) {
            val x = lensX * i / n
            val y = mid + raw(x) * size.height * 0.16f
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    glowStroke(rawPath.trimmed(enter), Color.White.copy(alpha = 0.38f), 1.6f, glow = 0.2f)

    // Polished ribbons: right of the lens, layered.
    for (k in 0..2) {
        val xs = FloatArray(60) { lensX + (size.width - lensX) * it / 59f }
        val ys = FloatArray(60) {
            val fade = (it / 59f).let { u -> 1f - u * 0.15f }
            mid + clean(xs[it], k * 0.9f) * size.height * (0.24f - k * 0.05f) * warm * fade
        }
        val p = smoothPath(xs, ys)
        glowStroke(p, Brush.horizontalGradient(listOf(Color.White, accent, accent.copy(alpha = 0.3f)), lensX, size.width),
            3f - k * 0.8f, glow = (1f - k * 0.3f) * warm, alpha = warm * (1f - k * 0.28f))
    }

    // The lens: a ring of light with a slow breathing halo.
    val r = size.height * 0.30f * (0.6f + 0.4f * lensIn)
    val c = Offset(lensX, mid)
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.30f * lensIn), Color.Transparent), c, r * 2.2f), r * 2.2f, c)
    drawArc(Brush.sweepGradient(listOf(accent, Color.White, accent.copy(alpha = 0.2f), accent), c),
        startAngle = g * 40f, sweepAngle = 360f * lensIn, useCenter = false,
        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(2.4f, cap = StrokeCap.Round))
    drawCircle(accent.copy(alpha = 0.18f * lensIn), r * (0.82f + 0.05f * breathe(g, 3f)), c, style = Stroke(1f))
    spark(c, Color.White, 3.2f, lensIn * (0.7f + 0.3f * breathe(g, 2.2f)))

    // Motes riding out on the ribbon.
    for (i in 0 until 10) {
        val u = frac(g * (0.08f + 0.05f * hash01(i, 1)) + hash01(i, 2))
        val x = lensX + (size.width - lensX) * u
        val y = mid + clean(x, hash01(i, 3) * 1.8f) * size.height * 0.22f * warm + (hash01(i, 4) - 0.5f) * 18f
        spark(Offset(x, y), accent, 1.4f + hash01(i, 5), warm * sin(u * PI.toFloat()) * 0.9f)
    }
}

/**
 * "Your headphones, measured." A headphone's measured response draws on,
 * ragged; a scanning beam passes and corrects it onto the target.
 */
private fun DrawScope.measureScene(accent: Color, t: Float, g: Float) {
    val top = size.height * 0.12f
    val bottom = size.height * 0.88f
    val mid = (top + bottom) / 2f
    val grid = seg(t, 0f, 0.8f)
    for (i in 0..6) {
        val x = size.width * i / 6f
        drawLine(Color.White.copy(alpha = 0.06f * grid), Offset(x, top), Offset(x, bottom), 1f)
    }
    for (i in 0..4) {
        val y = mix(top, bottom, i / 4f)
        drawLine(Color.White.copy(alpha = 0.06f * grid), Offset(0f, y), Offset(size.width, y), 1f)
    }
    val n = 64
    val xs = FloatArray(n + 1) { size.width * it / n }
    fun measured(u: Float): Float =
        0.55f * exp(-((u - 0.12f) / 0.10f).let { it * it }) - 0.35f * exp(-((u - 0.46f) / 0.08f).let { it * it }) +
        0.75f * exp(-((u - 0.68f) / 0.05f).let { it * it }) - 0.5f * exp(-((u - 0.80f) / 0.04f).let { it * it }) +
        0.3f * sin(u * 31f) * u - 0.6f * ((u - 0.9f).coerceAtLeast(0f) * 10f)
    fun target(u: Float) = 0.22f * exp(-((u - 0.06f) / 0.12f).let { it * it }) - 0.15f * ((u - 0.85f).coerceAtLeast(0f) * 6.6f)
    val amp = (bottom - top) * 0.32f
    val draw = seg(t, 0.2f, 1.2f, Cine.Draw)
    // The beam sweeps once on entrance, then every 7 s.
    val sweepT = if (t < 1.4f) 0f else frac((t - 1.4f) / 7f) * 7f
    val beam = (sweepT / 1.8f).coerceIn(0f, 1f)
    val beamX = size.width * Cine.InOut.transform(beam)
    val correctedBy = { u: Float -> if (t < 1.4f) 0f else if (sweepT >= 1.8f) 1f else smooth(u * size.width, beamX) }
    val ys = FloatArray(n + 1) { val u = it / n.toFloat(); mid - mix(measured(u), target(u), correctedBy(u)) * amp }
    val curve = smoothPath(xs, ys)
    val targetPath = smoothPath(xs, FloatArray(n + 1) { mid - target(it / n.toFloat()) * amp })
    drawPath(targetPath.trimmed(draw), accent.copy(alpha = 0.35f), style = Stroke(1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 7f))))
    drawPath(areaUnder(curve, 0f, size.width, bottom), Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f * draw), Color.Transparent), top, bottom))
    glowStroke(curve.trimmed(draw), Brush.horizontalGradient(listOf(accent, Color.White.copy(alpha = 0.85f)), beamX - 1f, beamX + 1f), 2.6f, glow = 0.9f)
    if (t >= 1.4f && sweepT < 1.9f) {
        val a = (1f - ((sweepT - 1.6f) / 0.3f).coerceIn(0f, 1f))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.22f * a), Color.Transparent), beamX - 50f, beamX + 50f),
            Offset(beamX - 50f, top), Size(100f, bottom - top))
        drawLine(Color.White.copy(alpha = 0.8f * a), Offset(beamX, top), Offset(beamX, bottom), 1.4f)
        val u = (beamX / size.width).coerceIn(0f, 1f)
        spark(Offset(beamX, mid - target(u) * amp), Color.White, 3f, a)
    }
}

private fun DrawScope.smooth(x: Float, edge: Float): Float {
    val w = size.width * 0.08f
    val v = ((edge - x) / w + 0.5f).coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/**
 * "Then make it yours." Fifteen bands on their tracks; the curve through
 * their knobs reshapes itself, band by band, through three tunings.
 */
private fun DrawScope.shapeScene(accent: Color, t: Float, g: Float) {
    val bands = 15
    val top = size.height * 0.10f
    val bottom = size.height * 0.90f
    val mid = (top + bottom) / 2f
    val step = size.width / bands
    val shapes = arrayOf(
        floatArrayOf(4f, 3.6f, 2.8f, 1.6f, 0.6f, 0f, -0.6f, -0.8f, -0.4f, 0.4f, 1.4f, 2.4f, 3f, 3.4f, 3.6f),
        floatArrayOf(3f, 3.4f, 3.2f, 2.6f, 1.8f, 1.2f, 0.6f, 0f, -0.4f, -0.8f, -1.2f, -1.6f, -2f, -2.4f, -2.6f),
        floatArrayOf(-2f, -1.8f, -1.4f, -0.8f, 0f, 0.6f, 1.6f, 2.6f, 3.2f, 3f, 2.2f, 1.2f, 0.4f, 0f, -0.4f)
    )
    val per = 3.2f
    val life = (t - 1.0f).coerceAtLeast(0f)
    val idx = (life / per).toInt()
    val within = frac(life / per)
    val a = shapes[idx % 3]; val b = shapes[(idx + 1) % 3]
    val pxDb = (bottom - top) / 10f
    val xs = FloatArray(bands) { step * it + step / 2f }
    val ys = FloatArray(bands) { i ->
        val rise = seg(t, 0.15f + i * 0.04f, 0.8f)
        // Each band moves a beat after the one to its left: the wave runs across.
        val m = seg(within, 0.55f + i * 0.018f, 0.3f, Cine.InOut)
        mid - mix(a[i], b[i], m) * pxDb * rise
    }
    val trackA = seg(t, 0f, 0.7f)
    for (i in 0 until bands) {
        drawLine(Color.White.copy(alpha = 0.08f * trackA), Offset(xs[i], top), Offset(xs[i], bottom), 3f, cap = StrokeCap.Round)
        drawLine(Brush.verticalGradient(listOf(accent.copy(alpha = 0.7f), accent.copy(alpha = 0.15f)), ys[i], mid),
            Offset(xs[i], mid), Offset(xs[i], ys[i]), 3f, cap = StrokeCap.Round)
    }
    val curve = smoothPath(xs, ys)
    val draw = seg(t, 0.5f, 1.1f, Cine.Draw)
    drawPath(areaUnder(curve, xs.first(), xs.last(), bottom), Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f * draw), Color.Transparent), top, bottom))
    glowStroke(curve.trimmed(draw), Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.9f), accent, Color.White.copy(alpha = 0.9f))), 2.8f, glow = 1f)
    for (i in 0 until bands) {
        val k = seg(t, 0.3f + i * 0.04f, 0.6f)
        drawCircle(BrandScheme.Dark.background, 6f * k, Offset(xs[i], ys[i]))
        drawCircle(accent.copy(alpha = k), 6f * k, Offset(xs[i], ys[i]), style = Stroke(2f))
    }
    // A light runs along the curve.
    if (draw >= 1f) spark(curve.pointAt(frac(g * 0.16f)), Color.White, 2.4f, 0.8f)
}

/**
 * "Nothing leaves your phone." A phone draws itself; sound ripples inside
 * it, and the motes that drift to its edge turn back.
 */
private fun DrawScope.privateScene(accent: Color, t: Float, g: Float) {
    val h = size.height * 0.92f
    val w = h * 0.52f
    val left = (size.width - w) / 2f
    val topY = (size.height - h) / 2f
    val r = w * 0.16f
    val outline = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(left, topY, left + w, topY + h, CornerRadius(r)))
    }
    val draw = seg(t, 0f, 1.3f, Cine.Draw)
    val fill = seg(t, 0.8f, 0.9f)
    drawPath(outline, Brush.verticalGradient(listOf(accent.copy(alpha = 0.10f * fill), Color.Transparent), topY, topY + h))
    glowStroke(outline.trimmed(draw), Brush.verticalGradient(listOf(accent, Color.White.copy(alpha = 0.6f), accent), topY, topY + h), 2.2f, glow = 0.8f)
    val c = Offset(left + w / 2f, topY + h * 0.48f)
    // Ripples, kept inside: they fade before they reach the edge.
    for (k in 0..3) {
        val life = frac(g * 0.3f + k / 4f)
        val rr = life * w * 0.46f
        drawCircle(accent.copy(alpha = (1f - life) * 0.45f * fill), rr, c, style = Stroke(1.4f))
    }
    spark(c, Color.White, 3.6f, fill * (0.75f + 0.25f * breathe(g, 2.4f)))
    // Motes bouncing inside the frame: they reach the wall and turn back.
    val inset = r * 0.6f
    val bx0 = left + inset; val bx1 = left + w - inset
    val by0 = topY + inset; val by1 = topY + h - inset
    for (i in 0 until 12) {
        fun bounce(v: Float, lo: Float, hi: Float): Float {
            val span = hi - lo
            val p = frac(v / (2f * span)) * 2f * span
            return lo + if (p < span) p else 2f * span - p
        }
        val vx = (40f + 60f * hash01(i, 1)) * (if (hash01(i, 2) > 0.5f) 1f else -1f)
        val vy = (30f + 70f * hash01(i, 3)) * (if (hash01(i, 4) > 0.5f) 1f else -1f)
        val x = bounce(hash01(i, 5) * 400f + g * vx, bx0, bx1)
        val y = bounce(hash01(i, 6) * 400f + g * vy, by0, by1)
        val nearWall = minOf(x - bx0, bx1 - x, y - by0, by1 - y)
        val hit = (1f - (nearWall / 10f).coerceIn(0f, 1f))
        spark(Offset(x, y), if (hit > 0.3f) Color.White else accent, 1.6f + hit * 1.4f, fill * (0.55f + 0.45f * hit))
    }
    // Speaker slot and camera, quiet detail.
    drawRoundRect(Color.White.copy(alpha = 0.25f * fill), Offset(c.x - w * 0.14f, topY + h * 0.05f), Size(w * 0.28f, 4f), CornerRadius(2f))
}
