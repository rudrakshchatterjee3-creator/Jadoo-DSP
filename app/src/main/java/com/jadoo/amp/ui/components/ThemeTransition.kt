package com.jadoo.amp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.IntSize
import com.jadoo.amp.ui.theme.BrandPalette
import kotlin.math.hypot

/**
 * Drives the theme-change transition — "the logo eclipse".
 *
 * ## What it looks like
 *
 * 620 ms, three overlapping phases on one [Animatable]:
 *
 *  - **A (0–186 ms)** the frozen OLD frame scales to 0.96 and fades, while a
 *    curtain in the NEW background colour wipes out from the exact point the
 *    user tapped.
 *  - **B (136–360 ms)** the brand mark strikes on over the curtain — left
 *    ring, datum bar, then the gold right ring and its bent bar.
 *  - **C (360–620 ms)** the mark's two circles expand past the screen bounds,
 *    punched out of the curtain with [BlendMode.DstOut], so the new UI is
 *    revealed *through the mark itself*.
 *
 * ## Why it is built this way
 *
 * The hard part of a theme change is not the animation, it is that applying a
 * new theme invalidates the entire composition — every card, every slider,
 * every piece of text recomposes in one frame. That is visible as a hitch no
 * matter how it is scheduled.
 *
 * So it is hidden rather than optimised. [rememberGraphicsLayer] captures the
 * old frame as a display list; one frame later the new theme is applied and
 * the whole subtree invalidates *underneath an opaque curtain*. There are
 * ~21 frames of cover, so a dropped frame in there cannot be seen.
 *
 * Only one live composition exists at any moment. A crossfade between old and
 * new would need the dashboard composed twice simultaneously, which on this
 * screen — 17 sliders, an EQ graph, 23 cards — genuinely does jank.
 *
 * Zero recomposition per animation frame: `progress` is read only inside
 * `drawWithContent`, i.e. in the draw phase. [CompositingStrategy.Offscreen]
 * scopes the `DstOut` punch to this overlay so it doesn't erase the app.
 *
 * ## Accessibility
 *
 * Under reduced motion there is no capture, no overlay and no animation — the
 * theme simply changes. That is the correct outcome, not a shortened
 * animation: someone who has asked for no motion should get none.
 *
 * ## What must NOT be done here
 *
 * Never `Activity.recreate()` or `setDefaultNightMode()`. Either would unbind
 * `JadooDspService`, re-run the update check, and re-fire external-EQ intent
 * handling — the audio would drop out to change a colour.
 */
class ThemeTransitionState internal constructor(
    internal val progress: Animatable<Float, *>,
    internal val motionEnabled: Boolean
) {
    /** Where the transition originates, in root coordinates. */
    internal var origin by mutableStateOf(Offset.Zero)

    /** The captured previous frame; null when idle or under reduced motion. */
    internal var captured by mutableStateOf<GraphicsLayer?>(null)

    /** The incoming background colour, painted by the curtain. */
    internal var incomingBackground by mutableStateOf(Color.Black)

    internal var running by mutableStateOf(false)

    val isRunning: Boolean get() = running
}

@Composable
fun rememberThemeTransitionState(motionEnabled: Boolean): ThemeTransitionState {
    val progress = remember { Animatable(0f) }
    return remember(motionEnabled) { ThemeTransitionState(progress, motionEnabled) }
}

/**
 * Wraps the app content, capturing it and playing the eclipse when
 * [ThemeTransitionState.start] is called.
 *
 * @param onApplyTheme invoked at the moment the curtain is fully opaque —
 *   this is where the caller actually commits the new theme.
 */
@Composable
fun ThemeTransitionHost(
    state: ThemeTransitionState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val layer = rememberGraphicsLayer()
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    size = IntSize(this.size.width.toInt(), this.size.height.toInt())
                    // Record every frame while idle so a capture is always
                    // one frame old at most. Recording is a display-list
                    // copy, not a rasterisation, so this is cheap.
                    if (!state.running) {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    } else {
                        drawContent()
                    }
                }
        ) {
            content()
        }

        if (state.running && state.motionEnabled) {
            EclipseOverlay(state, size)
        }
    }

    LaunchedEffect(state.running) {
        if (state.running) state.captured = layer
    }
}

@Composable
private fun EclipseOverlay(state: ThemeTransitionState, size: IntSize) {
    val captured = state.captured
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val p = state.progress.value
                val w = this.size.width
                val h = this.size.height
                if (w <= 0f || h <= 0f) return@drawWithContent

                // ── Phase A: the frozen old frame, retreating ─────────────
                val pA = ((p - 0f) / 0.30f).coerceIn(0f, 1f)
                if (captured != null && pA < 1f) {
                    val s = 1f - 0.04f * pA
                    withTransform({
                        scale(s, s, pivot = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f))
                    }) {
                        drawIntoCanvas { drawLayer(captured) }
                    }
                }

                // ── Phase A: the curtain, wiping from the tap point ───────
                val maxR = hypot(
                    maxOf(state.origin.x, w - state.origin.x).toDouble(),
                    maxOf(state.origin.y, h - state.origin.y).toDouble()
                ).toFloat()
                drawCircle(
                    color = state.incomingBackground,
                    radius = maxR * pA,
                    center = state.origin
                )

                // ── Phase B: the mark strikes on ─────────────────────────
                val pB = ((p - 0.22f) / 0.36f).coerceIn(0f, 1f)
                if (pB > 0f) {
                    val markSize = minOf(w, h) * 0.42f
                    withTransform({
                        translate(w / 2f - markSize / 2f, h / 2f - markSize * 0.30f)
                    }) {
                        inset(0f, 0f, this.size.width - markSize, this.size.height - markSize * 0.6f) {
                            drawBrandMark(
                                progress = pB,
                                datumCurve = pB,
                                referenceColor = Color.White,
                                processedColor = BrandPalette.Gold
                            )
                        }
                    }
                }

                // ── Phase C: punch the curtain out through the mark ──────
                val pC = ((p - 0.58f) / 0.42f).coerceIn(0f, 1f)
                if (pC > 0f) {
                    // Eased so the reveal accelerates rather than sliding
                    // linearly off the edge.
                    val e = pC * pC
                    val r = e * maxR * 1.25f
                    val gap = r * 0.582f   // the mark's own centre spacing
                    drawCircle(
                        color = Color.Black,
                        radius = r,
                        center = Offset(w / 2f - gap / 2f, h / 2f),
                        blendMode = BlendMode.DstOut
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = r,
                        center = Offset(w / 2f + gap / 2f, h / 2f),
                        blendMode = BlendMode.DstOut
                    )
                }
            }
    ) {}
}

/**
 * Runs the transition.
 *
 * Under reduced motion this applies the theme immediately and returns without
 * drawing anything.
 */
suspend fun ThemeTransitionState.play(
    from: Offset,
    incomingBackground: Color,
    applyTheme: () -> Unit
) {
    if (!motionEnabled) {
        applyTheme()
        return
    }
    origin = from
    this.incomingBackground = incomingBackground
    running = true
    progress.snapTo(0f)

    // One frame of opaque cover before the theme is committed, so the
    // subtree-wide invalidation happens behind the curtain rather than in
    // front of it.
    progress.animateTo(0.32f, tween(200, easing = LinearEasing))
    applyTheme()
    progress.animateTo(1f, tween(420, easing = LinearEasing))

    running = false
    captured = null
    progress.snapTo(0f)
}
