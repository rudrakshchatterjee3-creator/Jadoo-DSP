package com.jadoo.amp.ui.components

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.modifier.ModifierLocalMap
import androidx.compose.ui.modifier.ModifierLocalModifierNode
import androidx.compose.ui.modifier.modifierLocalMapOf
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.jadoo.amp.ui.theme.GLASS_BLUR_RADIUS_DP
import com.jadoo.amp.ui.theme.GLASS_WINDOW_BLUR_DP
import com.jadoo.amp.ui.theme.GlassLevel
import com.jadoo.amp.ui.theme.GlassRendering
import com.jadoo.amp.ui.theme.JadooTheme
import com.jadoo.amp.ui.theme.LocalGlassRendering
import com.jadoo.amp.ui.theme.LocalUiStyle
import com.jadoo.amp.ui.theme.UiStyle
import com.jadoo.amp.ui.theme.glassDialogDim
import com.jadoo.amp.ui.theme.glassFillAlpha
import com.jadoo.amp.ui.theme.samplesBackdrop
import com.jadoo.amp.ui.theme.drawsEffects
import com.jadoo.amp.ui.theme.glassLabelTintAlpha
import com.jadoo.amp.ui.theme.glassLiveAlpha
import com.jadoo.amp.ui.theme.GLASS_ADAPT
import com.jadoo.amp.ui.theme.GLASS_SATURATION
import kotlin.math.ceil
import kotlin.math.min

/*
 * Liquid Glass engine.
 *
 * The rule, as on Apple's platforms: glass is the navigation and control layer
 * that floats above content. It is never the content itself.
 *
 *  - Cards (GlassLevel.Card, feature groups, sections): lens glass over the
 *    backdrop, with no blur. Rows inside a card stay plain.
 *  - Pinned bars over scrolling content (GlassLevel.Floating) and overlays
 *    (GlassLevel.Overlay: dialogs, sheets, menus): real glass. It blurs and
 *    refracts what is behind it.
 *  - Small controls on a surface (GlassLevel.Nested: chips, tonal buttons,
 *    segmented rows): a thin translucent fill and a rim. No sampling.
 *
 * Three node types cooperate through a ModifierLocal, so they work across
 * composable boundaries without any screen passing state around:
 *
 *  - GlassBackdropNode  (Modifier.jadooBackdrop under Liquid Glass): paints
 *    the screen's plain background into its own GraphicsLayer and draws it.
 *  - GlassContentNode   (Modifier.glassContentSource): records the content
 *    that scrolls under a pinned bar into a second GraphicsLayer.
 *  - GlassSurfaceNode   (Modifier.liquidGlass): draws those layers back,
 *    offset to where the surface sits, through a RenderEffect (blur on API
 *    31/32; blur plus the AGSL lens on API 33+), then the tint, the rim and
 *    its content.
 *
 * A glass surface inside recorded content samples the backdrop only, because
 * sampling the layer it is being recorded into would be a cycle. Glass inside
 * glass never samples. Overlays in their own window cannot sample the screen,
 * so they ask the window manager to blur behind them instead (see
 * GlassDialogWindow).
 *
 * Legibility on live glass is per pixel, inside the lens shader: the tint is
 * thin (glassLiveAlpha) and the shader thickens it only where the blurred
 * backdrop is dark under a light scheme or bright under a dark one
 * (GLASS_ADAPT). Tiers without the shader keep the WCAG-driven glassFillAlpha.
 *
 * Draw code allocates nothing per frame: the record blocks are fields, and
 * a RenderEffect is rebuilt only when the surface's size, shape or tint
 * changes.
 */

private const val TAG = "LiquidGlass"

internal class GlassHost {
    /** The screen colour the backdrop paints over; see GlassSurfaceNode.recordBehind. */
    var background: Color = Color.Transparent
    var backdrop: GraphicsLayer? = null
    var backdropNode: Modifier.Node? = null
    var content: GraphicsLayer? = null
    var contentNode: Modifier.Node? = null
}

/**
 * What a descendant may sample. allowContent is false inside the recorded
 * content (cycle guard). [host] is a var because Compose attaches children
 * before their parent: the content node hands out this object at creation
 * and fills in the host in its own onAttach; surfaces read it at draw time.
 */
internal class GlassSampler(var host: GlassHost?, val allowContent: Boolean)

internal val ModifierLocalGlassSampler = modifierLocalOf<GlassSampler?> { null }

// ── Backdrop ────────────────────────────────────────────────────────────

internal data class GlassBackdropElement(
    val background: Color,
    val orb1: Color,
    val orb2: Color,
    val orb3: Color
) : ModifierNodeElement<GlassBackdropNode>() {
    override fun create() = GlassBackdropNode(background, orb1, orb2, orb3)
    override fun update(node: GlassBackdropNode) = node.update(background, orb1, orb2, orb3)
    override fun InspectorInfo.inspectableProperties() { name = "glassBackdrop" }
}

internal class GlassBackdropNode(
    private var background: Color,
    private var orb1: Color,
    private var orb2: Color,
    private var orb3: Color
) : Modifier.Node(), DrawModifierNode, ModifierLocalModifierNode {
    private val host = GlassHost()

    override val providedValues: ModifierLocalMap =
        modifierLocalMapOf(ModifierLocalGlassSampler to GlassSampler(host, allowContent = true))

    private val paintBlock: DrawScope.() -> Unit = { paintBackground() }

    fun update(background: Color, orb1: Color, orb2: Color, orb3: Color) {
        this.background = background; this.orb1 = orb1; this.orb2 = orb2; this.orb3 = orb3
        host.background = background
        invalidateDraw()
    }

    override fun onAttach() {
        host.background = background
        host.backdrop = requireGraphicsContext().createGraphicsLayer()
        host.backdropNode = this
    }

    override fun onDetach() {
        host.backdrop?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        host.backdrop = null
        host.backdropNode = null
    }

    private fun DrawScope.paintBackground() {
        drawRect(background)
    }

    override fun ContentDrawScope.draw() {
        val layer = host.backdrop
        if (layer == null) {
            paintBackground()
        } else {
            layer.record(block = paintBlock)
            drawLayer(layer)
        }
        drawContent()
    }
}

// ── Content that scrolls under pinned glass ─────────────────────────────

/**
 * Records this node's content into the screen's content layer so pinned glass
 * bars (GlassLevel.Floating) can blur and refract it. Place it before
 * `verticalScroll`, inside a `jadooBackdrop()`. Standard: no-op.
 */
@Composable
fun Modifier.glassContentSource(): Modifier =
    if (LocalUiStyle.current != UiStyle.LiquidGlass) this else this.then(GlassContentElement)

private object GlassContentElement : ModifierNodeElement<GlassContentNode>() {
    override fun create() = GlassContentNode()
    override fun update(node: GlassContentNode) = Unit
    override fun hashCode() = 7
    override fun equals(other: Any?) = other === this
    override fun InspectorInfo.inspectableProperties() { name = "glassContentSource" }
}

internal class GlassContentNode : Modifier.Node(), DrawModifierNode, ModifierLocalModifierNode {
    // Surfaces inside the recorded content sample the backdrop only.
    private val inner = GlassSampler(null, allowContent = false)
    override val providedValues: ModifierLocalMap = modifierLocalMapOf(ModifierLocalGlassSampler to inner)
    private var host: GlassHost? = null
    private var layer: GraphicsLayer? = null
    private var scope: ContentDrawScope? = null
    private val recordBlock: DrawScope.() -> Unit = { scope?.drawContent() }

    override fun onAttach() {
        val parent = ModifierLocalGlassSampler.current
        val h = parent?.host
        inner.host = h
        if (h != null && parent.allowContent && h.content == null) {
            host = h
            val l = requireGraphicsContext().createGraphicsLayer()
            layer = l
            h.content = l
            h.contentNode = this
        }
    }

    override fun onDetach() {
        val h = host
        if (h != null && h.contentNode === this) { h.content = null; h.contentNode = null }
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        host = null
        inner.host = null
    }

    override fun ContentDrawScope.draw() {
        val l = layer
        if (l == null) { drawContent(); return }
        scope = this
        l.record(block = recordBlock)
        scope = null
        drawLayer(l)
    }
}

// ── Surface ─────────────────────────────────────────────────────────────

/** Lens and rim settings; all in dp, converted at draw time. */
internal data class GlassLens(
    val bendZone: Dp,
    val bend: Dp,
    val dispersion: Float,
    val tintZone: Dp,
    val rimTintScale: Float,
    val specular: Float,
    val rimWidth: Dp,
    val blur: Dp
)

/**
 * Pinned bars sit over scrolling content and need the heavy blur. Overlays
 * sample the screen's smooth orb backdrop only, which a blur would not
 * change, so they run the lens alone (blur 0). Cards do the same (the lens alone),
 * content), and Nested glass never samples either.
 */
internal fun glassLensFor(level: GlassLevel, dark: Boolean): GlassLens = when (level) {
    // Refraction band and bend follow KMPLiquidGlass's lens recipe (20 dp band, ~50 dp bend).
    GlassLevel.Floating -> GlassLens(20.dp, 48.dp, 0f, 18.dp, 0.15f, if (dark) 0.85f else 1.0f, 1.5.dp, GLASS_BLUR_RADIUS_DP.dp)
    GlassLevel.Overlay -> GlassLens(28.dp, 20.dp, 0f, 18.dp, 0.20f, if (dark) 0.75f else 0.95f, 1.5.dp, 0.dp)
    // Cards refract the backdrop only: no specular (no white edge), no blur (a smooth
    // backdrop has nothing to blur, and it costs a pass per card).
    GlassLevel.Card -> GlassLens(20.dp, 48.dp, 0f, 18.dp, 0.15f, 0f, 1.5.dp, 0.dp)
    else -> GlassLens(28.dp, 22.dp, 0.20f, 18.dp, 0.15f, if (dark) 0.75f else 0.95f, 1.4.dp, 5.dp)
}

/**
 * [alpha] is the thin live fill (used whenever the backdrop shows through);
 * [safeAlpha] the WCAG fill for when nothing can be sampled. [dark] drives
 * the shader's adaptive tint direction.
 */
internal data class GlassSurfaceElement(
    val tint: Color,
    val alpha: Float,
    val safeAlpha: Float,
    val dark: Boolean,
    /** Nothing to sample, but the backdrop is already glass or blurred behind this window: use [alpha]. */
    val thinWithoutSampling: Boolean,
    val level: GlassLevel,
    val rendering: GlassRendering,
    val shape: Shape,
    val lens: GlassLens
) : ModifierNodeElement<GlassSurfaceNode>() {
    override fun create() = GlassSurfaceNode(tint, alpha, safeAlpha, dark, thinWithoutSampling, level, rendering, shape, lens)
    override fun update(node: GlassSurfaceNode) =
        node.update(tint, alpha, safeAlpha, dark, thinWithoutSampling, level, rendering, shape, lens)
    override fun InspectorInfo.inspectableProperties() { name = "liquidGlass" }
}

internal class GlassSurfaceNode(
    private var tint: Color,
    private var alpha: Float,
    private var safeAlpha: Float,
    private var dark: Boolean,
    private var thinWithoutSampling: Boolean,
    private var level: GlassLevel,
    private var rendering: GlassRendering,
    private var shape: Shape,
    private var lens: GlassLens
) : Modifier.Node(), DrawModifierNode, ModifierLocalModifierNode, GlobalPositionAwareModifierNode {

    // Glass inside this glass is drawn over it, not over the backdrop.
    override val providedValues: ModifierLocalMap = modifierLocalMapOf(ModifierLocalGlassSampler to null)

    private var sampler: GlassSampler? = null
    private var layer: GraphicsLayer? = null
    // The blur pass the lens samples; see draw. Only exists while the lens runs.
    private var blurLayer: GraphicsLayer? = null
    private var blurEffect: RenderEffect? = null
    private var blurEffectPx = -1f
    private val lensBlock: DrawScope.() -> Unit = { blurLayer?.let { drawLayer(it) } }
    private var lastRel = Offset.Unspecified

    // Record-block inputs (fields, so the block itself is allocated once).
    private var dx = 0f; private var dy = 0f; private var cdx = 0f; private var cdy = 0f
    private var withContent = false
    private val recordBlock: DrawScope.() -> Unit = { recordBehind() }

    // RenderEffect cache.
    private var effect: RenderEffect? = null
    private var effW = -1; private var effH = -1
    private var effSize = Size.Unspecified
    private var effRadius = 0f
    private var effDirty = true

    fun update(tint: Color, alpha: Float, safeAlpha: Float, dark: Boolean, thinWithoutSampling: Boolean,
               level: GlassLevel, rendering: GlassRendering, shape: Shape, lens: GlassLens) {
        val resample = level != this.level || rendering != this.rendering
        if (tint != this.tint || alpha != this.alpha || dark != this.dark || shape != this.shape ||
            lens != this.lens || resample) effDirty = true
        this.tint = tint; this.alpha = alpha; this.safeAlpha = safeAlpha; this.dark = dark
        this.thinWithoutSampling = thinWithoutSampling
        this.level = level; this.rendering = rendering
        this.shape = shape; this.lens = lens
        if (resample && isAttached) { releaseSampling(); resolveSampling() }
        invalidateDraw()
    }

    override fun onAttach() = resolveSampling()
    override fun onDetach() = releaseSampling()

    private fun resolveSampling() {
        // Cards, pinned bars and overlays sample the backdrop; nested controls are a
        // thin fill over their parent. Opaque and Scrim never sample.
        sampler = if (level != GlassLevel.Nested &&
                      rendering.samplesBackdrop() && Build.VERSION.SDK_INT >= 31)
            ModifierLocalGlassSampler.current else null
        // The layer is created lazily, on the first draw that has a host.
    }

    private fun releaseSampling() {
        releaseLayer()
        sampler = null; lastRel = Offset.Unspecified
    }

    private fun releaseLayer() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        blurLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null; blurLayer = null; blurEffect = null; blurEffectPx = -1f
        effect = null; effDirty = true
    }

    private fun blurEffectFor(blurPx: Float): RenderEffect {
        val cached = blurEffect
        if (cached != null && blurEffectPx == blurPx) return cached
        val fresh = BlurEffect(blurPx, blurPx, TileMode.Clamp)
        blurEffect = fresh; blurEffectPx = blurPx
        return fresh
    }

    private fun offsetInHost(): Offset? {
        val hostNode = sampler?.host?.backdropNode ?: return null
        if (!hostNode.isAttached || !isAttached) return null
        return hostNode.requireLayoutCoordinates().localPositionOf(requireLayoutCoordinates(), Offset.Zero)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (sampler == null) return
        // Scrolling moves the surface over a different part of the backdrop.
        val rel = offsetInHost() ?: return
        if (rel != lastRel) invalidateDraw()
    }

    private fun DrawScope.recordBehind() {
        val host = sampler?.host ?: return
        // The blur and lens margin can reach past the backdrop's edges. Fill the
        // whole layer with the screen colour first, so they read solid backdrop
        // there instead of transparency (which showed as a dark rim).
        drawRect(host.background)
        translate(dx, dy) {
            host.backdrop?.let { drawLayer(it) }
            if (withContent) translate(cdx, cdy) { host.content?.let { drawLayer(it) } }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = sampler
        val host = s?.host
        if (host == null && layer != null) releaseLayer()
        if (host != null && layer == null) layer = requireGraphicsContext().createGraphicsLayer()
        val l = layer
        val backdrop = host?.backdrop
        val rel = if (backdrop != null) offsetInHost() else null
        if (l != null && host != null && rel != null) {
            lastRel = rel
            val blurPx = lens.blur.toPx()
            val m = ceil(blurPx * 2f + lens.bend.toPx())
            dx = m - rel.x
            dy = m - rel.y
            val contentNode = host.contentNode
            withContent = level == GlassLevel.Floating && s.allowContent &&
                host.content != null && contentNode != null && contentNode.isAttached
            if (withContent && contentNode != null) {
                val c = host.backdropNode!!.requireLayoutCoordinates()
                    .localPositionOf(contentNode.requireLayoutCoordinates(), Offset.Zero)
                cdx = c.x; cdy = c.y
            }
            val w = ceil(size.width + 2f * m).toInt()
            val h = ceil(size.height + 2f * m).toInt()
            val fx = effectFor(w, h, m, blurPx)
            if (lensActive && blurPx >= 0.5f) {
                // Two passes, each its own layer: the blur is recorded first, and the
                // lens layer draws it and refracts it. A single chained RenderEffect
                // (lens over blur) cut the lens output short of the rim on the right
                // and bottom, leaving a dark strip.
                val b = blurLayer ?: requireGraphicsContext().createGraphicsLayer().also { blurLayer = it }
                b.record(IntSize(w, h), recordBlock)
                b.renderEffect = blurEffectFor(blurPx)
                l.record(IntSize(w, h), lensBlock)
            } else {
                blurLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
                blurLayer = null; blurEffect = null; blurEffectPx = -1f
                l.record(IntSize(w, h), recordBlock)
            }
            l.renderEffect = fx
            translate(-m, -m) { drawLayer(l) }
            // The lens shader composites the tint itself (thinner at the rim,
            // thicker where the backdrop fights the text).
            if (!lensActive) drawRect(tint, alpha = alpha)
        } else if (backdrop != null && rel != null) {
            // No layer for this frame: the backdrop still shows through the tint.
            lastRel = rel
            translate(-rel.x, -rel.y) { drawLayer(backdrop) }
            drawRect(tint, alpha = alpha)
        } else {
            drawRect(tint, alpha = if (thinWithoutSampling) alpha else safeAlpha)
        }
        drawContent()
    }

    private var lensActive = false

    private fun ContentDrawScope.effectFor(w: Int, h: Int, m: Float, blurPx: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < 31) return null
        // Refraction at the rim. Blur is its own pass (see draw), so the lens
        // samples already-blurred content.
        if (rendering == GlassRendering.Refraction && Build.VERSION.SDK_INT >= 33 && GlassLensShader.available) {
            if (effSize != size) {
                effRadius = cornerRadiusOf(shape.createOutline(size, layoutDirection, this), size)
                effSize = size
                effDirty = true
            }
            if (!effDirty && effect != null && effW == w && effH == h) return effect
            val e = GlassLensShader.effect(
                surfaceW = size.width, surfaceH = size.height, margin = m, radius = effRadius,
                bendZone = lens.bendZone.toPx(), bend = lens.bend.toPx(), dispersion = lens.dispersion,
                tint = tint, alpha = alpha, rimTintScale = lens.rimTintScale, tintZone = lens.tintZone.toPx(),
                specular = lens.specular, rimWidth = lens.rimWidth.toPx(), blurPx = 0f, dark = dark
            )
            if (e != null) {
                effect = e; effW = w; effH = h; effDirty = false; lensActive = true
                return e
            }
        }
        lensActive = false
        if (blurPx < 0.5f) { effect = null; effDirty = false; return null }
        if (effect == null || effDirty) {
            effect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
            effDirty = false
        }
        return effect
    }
}

private fun cornerRadiusOf(outline: Outline, size: Size): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
    is Outline.Rectangle -> 0f
    else -> min(size.width, size.height) * 0.25f
}

/**
 * The API 33+ lens: an AGSL RuntimeShader chained after the blur (or alone
 * when the surface samples only the smooth backdrop).
 *
 * Inside the surface's rounded-rect SDF it:
 *  1. refracts: across a rim band with a circular (lens) profile it samples
 *     the backdrop displaced OUTWARD along the edge normal, so what lies just
 *     beyond the edge is pulled in and compressed at the rim, the way a thick
 *     convex slab bends light; R and B are split slightly along that
 *     displacement (dispersion);
 *  2. adds vibrancy: boosts the saturation of what shows through and lifts
 *     the mid-tones a touch;
 *  3. composites the tint: thin in the rim band so the bending shows, full
 *     where text sits, and thickened per pixel where the backdrop is dark
 *     under a light scheme / bright under a dark one (adaptive legibility);
 *  4. shades the slab: a Fresnel-like bright band and thin specular line on
 *     the edges facing the light (top-left), a barely-there shade on the
 *     edges away from it (kept faint: a dark rim reads as dirt), and a faint
 *     top-to-bottom sheen.
 *
 * One RuntimeShader is shared: a RenderEffect copies the uniforms when it is
 * created (Skia's builder is copy-on-write), so each surface's effect keeps
 * its own values.
 */
@RequiresApi(33)
private object GlassLensShader {
    private const val AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float uMargin;
uniform float uRadius;
uniform float uBendZone;
uniform float uBend;
uniform float uDispersion;
uniform float4 uTint;
uniform float uRimTintScale;
uniform float uTintZone;
uniform float uSpecular;
uniform float uRimWidth;
uniform float uSaturation;
uniform float uAdapt;
uniform float uDark;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

half4 main(float2 xy) {
    float2 hs = uSize * 0.5;
    float2 p = xy - float2(uMargin, uMargin) - hs;
    float d = sdRoundRect(p, hs, uRadius);
    if (d > 1.0) {
        // Outside the lens the same tint as inside, so the fill runs to the clip edge.
        float4 r = content.eval(xy);
        float ra = max(r.a, 0.001);
        float3 ro = mix(r.rgb / ra, uTint.rgb, uTint.a);
        return half4(half3(ro * ra), half(ra));
    }
    float depth = max(-d, 0.0);
    float2 g = float2(
        sdRoundRect(p + float2(1.0, 0.0), hs, uRadius) - sdRoundRect(p - float2(1.0, 0.0), hs, uRadius),
        sdRoundRect(p + float2(0.0, 1.0), hs, uRadius) - sdRoundRect(p - float2(0.0, 1.0), hs, uRadius));
    float gl = length(g);
    float2 n = gl > 0.0001 ? g / gl : float2(0.0, 0.0);

    // 1. Lens: circular thickness profile, zero at the inner edge of the band, full at the rim.
    float x = clamp(1.0 - depth / uBendZone, 0.0, 1.0);
    float bend = uBend * (1.0 - sqrt(max(1.0 - x * x, 0.0)));
    float2 off = n * bend;
    float4 c;
    if (bend > 0.05 && uDispersion > 0.0) {
        float4 cr = float4(content.eval(xy + off * (1.0 + uDispersion)));
        float4 cg = float4(content.eval(xy + off));
        float4 cb = float4(content.eval(xy + off * (1.0 - uDispersion)));
        c = float4(cr.r, cg.g, cb.b, cg.a);
    } else {
        c = float4(content.eval(xy + off));
    }

    // 2. Vibrancy (on un-premultiplied colour).
    float a = max(c.a, 0.001);
    float3 rgb = c.rgb / a;
    float y = luma(rgb);
    rgb = mix(float3(y), rgb, uSaturation);
    rgb = rgb + float3(0.03) * (1.0 - uDark);
    rgb = clamp(rgb, float3(0.0), float3(1.0));
    y = luma(rgb);

    // 3. Tint: thin in the rim band, full inside, thicker where the backdrop fights the text.
    float inner = smoothstep(0.0, uTintZone, depth);
    float ta = uTint.a * mix(uRimTintScale, 1.0, inner);
    float fight = mix(smoothstep(0.78, 0.30, y), smoothstep(0.12, 0.55, y), uDark);
    ta = clamp(ta + uAdapt * fight * inner, 0.0, 1.0);
    float3 o = mix(rgb, uTint.rgb, ta);

    // 4. Slab shading.
    float2 L = normalize(float2(-0.45, -1.0));
    float facing = dot(n, L);
    float rim = exp(-depth / uRimWidth);
    float band = 1.0 - smoothstep(0.0, uBendZone, depth);
    float fres = band * band;
    float lit = max(facing, 0.0);
    float unlit = max(-facing, 0.0);
    float spec = uSpecular * (rim * (0.35 + 0.65 * lit) + fres * (0.22 * lit + 0.05));
    float shade = 0.05 * fres * unlit;
    float sheen = 0.06 * uSpecular * (1.0 - smoothstep(-hs.y, hs.y * 0.6, p.y));
    o = o * (1.0 - shade) + float3(spec + sheen);
    o = clamp(o, float3(0.0), float3(1.0));
    return half4(half3(o * a), half(a));
}
"""

    private var shader: RuntimeShader? = null
    private var failed = false

    val available: Boolean get() = !failed

    private fun shader(): RuntimeShader? {
        if (failed) return null
        shader?.let { return it }
        return try {
            RuntimeShader(AGSL).also {
                shader = it
                Log.i(TAG, "Lens shader compiled (API ${Build.VERSION.SDK_INT})")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Lens shader unavailable, using blur only", t)
            failed = true
            null
        }
    }

    fun effect(
        surfaceW: Float, surfaceH: Float, margin: Float, radius: Float,
        bendZone: Float, bend: Float, dispersion: Float,
        tint: Color, alpha: Float, rimTintScale: Float, tintZone: Float,
        specular: Float, rimWidth: Float, blurPx: Float, dark: Boolean
    ): RenderEffect? {
        val s = shader() ?: return null
        return try {
            val r = radius.coerceIn(0f, min(surfaceW, surfaceH) / 2f)
            s.setFloatUniform("uSize", surfaceW, surfaceH)
            s.setFloatUniform("uMargin", margin)
            s.setFloatUniform("uRadius", r)
            s.setFloatUniform("uBendZone", bendZone.coerceAtLeast(1f).coerceAtMost(min(surfaceW, surfaceH) / 2f))
            s.setFloatUniform("uBend", bend)
            s.setFloatUniform("uDispersion", dispersion)
            s.setFloatUniform("uTint", tint.red, tint.green, tint.blue, alpha)
            s.setFloatUniform("uRimTintScale", rimTintScale)
            s.setFloatUniform("uTintZone", tintZone.coerceAtLeast(1f))
            s.setFloatUniform("uSpecular", specular)
            s.setFloatUniform("uRimWidth", rimWidth.coerceAtLeast(0.5f))
            s.setFloatUniform("uSaturation", GLASS_SATURATION)
            s.setFloatUniform("uAdapt", GLASS_ADAPT)
            s.setFloatUniform("uDark", if (dark) 1f else 0f)
            val lensEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content")
            if (blurPx < 0.5f) return lensEffect.asComposeRenderEffect()
            val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            android.graphics.RenderEffect.createChainEffect(lensEffect, blur).asComposeRenderEffect()
        } catch (t: Throwable) {
            Log.w(TAG, "Lens effect failed, using blur only", t)
            failed = true
            null
        }
    }
}

// ── Shadow ──────────────────────────────────────────────────────────────

/**
 * A soft, colour-tinted shadow drawn only OUTSIDE [shape] (clip-out), so it
 * never shows through translucent glass as a smudge the way an elevation
 * shadow does. Blurred with BlurMaskFilter (hardware-accelerated from API 28,
 * this app's minSdk). Paint and path are rebuilt only when size or colour change.
 */
private fun Modifier.glassShadow(shape: Shape, color: Color, radius: Dp): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val clip = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
    val r = radius.toPx()
    val dy = r * 0.30f
    val shadowPath = android.graphics.Path(clip.asAndroidPath()).apply { offset(0f, dy) }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        maskFilter = android.graphics.BlurMaskFilter(r, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    onDrawBehind {
        clipPath(clip, ClipOp.Difference) {
            drawIntoCanvas { it.nativeCanvas.drawPath(shadowPath, paint) }
        }
    }
}

// ── Card material ───────────────────────────────────────────────────────

/**
 * What makes a card read as glass without an outline: light falling from the
 * top (a soft sheen that fades by the middle) and a slight settling shade
 * toward the bottom edge. Drawn over the fill, under the content.
 */
private fun Modifier.glassSheen(dark: Boolean, shape: Shape): Modifier = this.drawWithCache {
    val brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.07f else 0.32f),
        0.45f to Color.Transparent,
        1f to Color.Black.copy(alpha = if (dark) 0.10f else 0.04f)
    )
    // A thin bezel a few dp inside the edge: the glass "padding" that gives the
    // card its own edge without an outline. Faint, brighter at the top.
    val inset = 4.dp.toPx()
    val ringSize = Size(size.width - 2 * inset, size.height - 2 * inset)
    val ring = shape.createOutline(ringSize, layoutDirection, this)
    val ringBrush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.16f else 0.60f),
        1f to Color.White.copy(alpha = if (dark) 0.03f else 0.15f)
    )
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
    val radius = (ring as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
    onDrawBehind {
        drawRect(brush)
        drawRoundRect(ringBrush, topLeft = Offset(inset, inset), size = ringSize,
                      cornerRadius = CornerRadius(radius), style = stroke)
    }
}

/**
 * Selected state for a tile. Standard: the usual outline. Liquid Glass: no hard
 * outline; the tile takes on the accent as a tint with a soft glow around it.
 * Unselected glass tiles stay plain.
 */
@Composable
fun Modifier.selectionOutline(selected: Boolean, shape: Shape): Modifier {
    val scheme = MaterialTheme.colorScheme
    if (LocalUiStyle.current != UiStyle.LiquidGlass) {
        return this.border(if (selected) 2.dp else 1.dp,
                           if (selected) scheme.primary else scheme.outlineVariant, shape)
    }
    val dark = scheme.background.luminance() < 0.5f
    val accent = scheme.primary
    val restRim = if (dark) Color.White else scheme.onSurface
    // Everything is drawn inside the shape (clipped): nothing spills past the edge,
    // and the tile's own preview colours are never tinted. Selected: an accent ring.
    // Resting: a faint top-lit edge, so unselected tiles are not borderless.
    return this
        .clip(shape)
        .drawWithContent {
            drawContent()
            val outline = shape.createOutline(size, layoutDirection, this)
            if (selected) {
                drawOutline(outline, accent, style = Stroke(width = 4.dp.toPx()))
            } else {
                drawOutline(
                    outline,
                    Brush.verticalGradient(listOf(restRim.copy(alpha = if (dark) 0.22f else 0.16f),
                                                  restRim.copy(alpha = 0.04f))),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
}

/** The tick on the option that is currently set. Place inside a tile's Box. */
@Composable
fun BoxScope.SelectedBadge(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(scheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Check, contentDescription = "Selected", tint = scheme.onPrimary,
             modifier = Modifier.size(12.dp))
    }
}

// ── Public modifier ─────────────────────────────────────────────────────

/**
 * The Liquid Glass material on any shape. Does nothing under Standard unless
 * [forceGlass] (the Style preview tile, so Standard users can see it).
 *
 * The fill alpha comes from [glassFillAlpha]: the look's minimum for this
 * tier and [level], raised until [contentColor] (and onSurfaceVariant on
 * neutral surfaces) reaches 4.5:1 over the worst backdrop the surface can sit
 * on. Pass [interactionSource] to get the press response (a slight squeeze
 * and a brighter rim; no squeeze under reduced motion).
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = MaterialTheme.colorScheme.surfaceContainer,
    level: GlassLevel = GlassLevel.Card,
    contentColor: Color = contentColorFor(tint),
    interactionSource: InteractionSource? = null,
    forceGlass: Boolean = false,
    /** This surface is in a window the system is blurring behind (see [GlassDialogWindow]). */
    windowBlur: Boolean = false,
    /** An active control: its accent tint is nearly solid so it reads as a filled, prominent control. */
    prominent: Boolean = false
): Modifier {
    if (!forceGlass && LocalUiStyle.current != UiStyle.LiquidGlass) return this
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val rendering = LocalGlassRendering.current
    val neutral = contentColor == scheme.onSurface || contentColor == scheme.onSurfaceVariant
    val fillAlpha = remember(scheme, dark, level, rendering, tint.copy(alpha = 1f), contentColor) {
        val texts = if (neutral) listOf(scheme.onSurface, scheme.onSurfaceVariant) else listOf(contentColor)
        glassFillAlpha(scheme, dark, level, rendering, tint, texts)
    }
    val safeAlpha = fillAlpha * tint.alpha
    // Refraction: the thin material fill; the lens thickens it per pixel where
    // needed. Nested glass never samples: neutral nested surfaces are a thin
    // wash over their (already adaptive) parent; accent-coloured ones (a
    // selected tile) stay thick enough to read as selected. A surface in a
    // window the system blurs behind is thin too: the blur is its backdrop.
    val thin = rendering == GlassRendering.Refraction || (windowBlur && rendering.samplesBackdrop())
    val alpha = when {
        prominent && !neutral -> 0.80f * tint.alpha
        !thin -> safeAlpha
        level == GlassLevel.Nested && !neutral -> min(safeAlpha, if (dark) 0.62f else 0.72f)
        else -> glassLiveAlpha(level, dark) * tint.alpha
    }
    val thinWithoutSampling = thin && (windowBlur || level == GlassLevel.Nested)
    val lens = remember(level, dark) { glassLensFor(level, dark) }

    val pressed = interactionSource?.collectIsPressedAsState()?.value ?: false
    // Effects and the press squeeze both follow the one glass mode (LocalGlassRendering).
    val effects = rendering.drawsEffects()
    val squeeze = animateFloatAsState(
        if (pressed && effects) 0.97f else 1f,
        if (effects) spring(dampingRatio = 0.6f, stiffness = 600f) else snap(),
        label = "glass_press"
    )
    // Sampling glass is a near-neutral material: strip most of the scheme hue
    // from neutral tints (accent tints such as a selected tile keep theirs).
    val drawTint = if (neutral && level == GlassLevel.Nested && dark && thin) {
        // A dark wash over a dark card is invisible; small pills lift toward white instead.
        Color.White
    } else if (neutral && rendering.samplesBackdrop() && level != GlassLevel.Card) {
        val l = if (dark) 0.10f else 0.97f
        lerp(tint.copy(alpha = 1f), Color(l, l, l), 0.75f)
    } else if (level == GlassLevel.Card && dark) {
        // Lift dark cards a touch toward white so they separate from the background.
        lerp(tint.copy(alpha = 1f), Color.White, 0.08f)
    } else tint.copy(alpha = 1f)

    // Only floating glass casts a shadow. Cards stay flat: a tinted blur shadow reads
    // as a pale halo outside the card on dark backgrounds. Small controls stay flat too.
    val shadowRadius = if (!effects) 0.dp else when (level) {
        GlassLevel.Overlay -> 28.dp
        GlassLevel.Floating -> 22.dp
        GlassLevel.Card -> 0.dp
        GlassLevel.Nested -> 0.dp
    }
    // Neutral, tight shadow: a tinted one reads as a coloured gradient leaking out of the box.
    val shadowColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.14f)

    return this
        .graphicsLayer {
            scaleX = squeeze.value
            scaleY = squeeze.value
        }
        .then(if (shadowRadius > 0.dp) Modifier.glassShadow(shape, shadowColor, shadowRadius) else Modifier)
        .clip(shape)
        .then(GlassSurfaceElement(drawTint, alpha, safeAlpha, dark, thinWithoutSampling,
                                  level, rendering, shape, lens))
        .then(if (level == GlassLevel.Card || level == GlassLevel.Overlay) Modifier.glassSheen(dark, shape) else Modifier)
        .then(if (level == GlassLevel.Nested && neutral) Modifier.border(controlRim(selected = false), shape) else Modifier)
}

// ── Surfaces ────────────────────────────────────────────────────────────

/**
 * Drop-in for Material's Surface. Standard: exactly `Surface(...)` with the
 * same arguments. Liquid Glass: [liquidGlass] tinted with [color], plus
 * [border] if given (e.g. a selection outline).
 */
@Composable
fun JadooSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    level: GlassLevel = GlassLevel.Card,
    windowBlur: Boolean = false,
    content: @Composable () -> Unit
) {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) {
        Surface(modifier = modifier, shape = shape, color = color, contentColor = contentColor,
                tonalElevation = tonalElevation, shadowElevation = shadowElevation, border = border,
                content = content)
        return
    }
    Box(
        modifier
            .liquidGlass(shape, color, level, contentColor, windowBlur = windowBlur)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .semantics(mergeDescendants = false) { isTraversalGroup = true }
            .pointerInput(Unit) {},
        propagateMinConstraints = true
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Clickable drop-in for Material's Surface(onClick = …). Standard: exactly that Surface. */
@Composable
fun JadooSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    level: GlassLevel = GlassLevel.Card,
    prominent: Boolean = false,
    content: @Composable () -> Unit
) {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) {
        Surface(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, color = color,
                contentColor = contentColor, tonalElevation = tonalElevation, shadowElevation = shadowElevation,
                border = border, interactionSource = interactionSource, content = content)
        return
    }
    val source = interactionSource ?: remember { MutableInteractionSource() }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .liquidGlass(shape, color, level, contentColor, source, prominent = prominent)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .semantics(mergeDescendants = false) { isTraversalGroup = true }
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled, onClick = onClick),
        propagateMinConstraints = true
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * A full-window screen container (the screens shown in a full-screen
 * Dialog). Standard: the square Surface those screens always had. Liquid
 * Glass: the orb backdrop across the whole window, so the screen's cards
 * and bars have something to sample.
 */
@Composable
fun JadooScreenSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) {
        Surface(modifier = modifier, shape = RoundedCornerShape(0.dp), color = color, content = content)
        return
    }
    Box(Modifier.fillMaxSize().jadooBackdrop()) {
        Box(modifier, propagateMinConstraints = true) {
            CompositionLocalProvider(LocalContentColor provides contentColorFor(color)) { content() }
        }
    }
}

/**
 * A block of a screen's content. Standard: a plain Column on a card. Liquid
 * Glass: the same card as lens glass, so text never sits
 * straight on the orb backdrop.
 */
@Composable
fun GlassSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) {
        Column(modifier, content = content)
        return
    }
    JadooSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                 color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), content = content)
    }
}

// ── Pinned bars ─────────────────────────────────────────────────────────

/** A floating capsule bar under the status bar, 16 dp from the screen edges. */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 56.dp)
            .liquidGlass(shape, level = GlassLevel.Floating)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** A floating glass panel for headers taller than one row (title + search field). */
@Composable
fun GlassTopPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .liquidGlass(RoundedCornerShape(30.dp), level = GlassLevel.Floating)
            .padding(vertical = 4.dp),
        content = content
    )
}

/**
 * Content under a pinned glass header. [content] gets the header's height
 * as top padding and should put [glassContentSource] before its scroll
 * modifier; the header floats over it and refracts it. A soft fade under the
 * status bar keeps the clock legible over scrolled content.
 * Must sit inside a `jadooBackdrop()` (or [JadooScreenSurface]).
 */
@Composable
fun GlassPinnedLayout(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable BoxScope.(top: Dp) -> Unit
) {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) {
        Box(modifier) { content(0.dp) }
        return
    }
    var headerPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bg = MaterialTheme.colorScheme.background
    Box(modifier) {
        content(with(density) { headerPx.toDp() })
        Box(
            // Scroll edge: content fades out under the status bar and a little below it,
            // so clock and battery never sit on top of scrolling text.
            Modifier.fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = 24.dp)))
                .background(Brush.verticalGradient(
                    0f to bg.copy(alpha = 0.95f), 0.6f to bg.copy(alpha = 0.75f), 1f to bg.copy(alpha = 0f)))
        )
        Box(Modifier.align(Alignment.TopCenter).onSizeChanged { headerPx = it.height }) { header() }
    }
}

// ── Dialogs, sheets and menus ───────────────────────────────────────────

private fun findDialogWindow(view: View): Window? {
    var v: Any? = view
    while (v != null) {
        if (v is DialogWindowProvider) return v.window
        v = (v as? View)?.parent ?: (v as? android.view.ViewParent)?.parent
    }
    return null
}

/**
 * Inside a Dialog / bottom sheet: on API 31+, asks the window manager to blur
 * everything behind this window (FLAG_BLUR_BEHIND) and lowers the dim, for
 * as long as the device allows cross-window blur (it can turn it off, e.g.
 * in battery saver). Returns whether the blur is on. Standard: does nothing.
 */
@Composable
fun GlassDialogWindow(): Boolean {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) return false
    if (!LocalGlassRendering.current.samplesBackdrop() || Build.VERSION.SDK_INT < 31) return false
    val view = LocalView.current
    val radiusPx = with(LocalDensity.current) { GLASS_WINDOW_BLUR_DP.dp.roundToPx() }
    var blurOn by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val window = findDialogWindow(view)
        val unregister = if (window != null && Build.VERSION.SDK_INT >= 31)
            registerWindowBlur(window, radiusPx) { blurOn = it } else null
        onDispose { unregister?.invoke() }
    }
    return blurOn
}

@RequiresApi(31)
private fun registerWindowBlur(window: Window, radiusPx: Int, onChange: (Boolean) -> Unit): () -> Unit {
    val listener = java.util.function.Consumer<Boolean> { enabled ->
        applyWindowBlur(window, enabled, radiusPx)
        onChange(enabled)
    }
    // Called back at once with the current state, then on every change.
    window.windowManager.addCrossWindowBlurEnabledListener(listener)
    return { window.windowManager.removeCrossWindowBlurEnabledListener(listener) }
}

@RequiresApi(31)
private fun applyWindowBlur(window: Window, enabled: Boolean, radiusPx: Int) {
    if (enabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.also { it.blurBehindRadius = radiusPx }
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    }
    window.setDimAmount(glassDialogDim(enabled))
}

/** Dialog { Card(...) } drop-in. Standard: that Card. Glass: an Overlay glass card with window blur. */
@Composable
fun JadooDialogCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable ColumnScope.() -> Unit
) {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) {
        Card(modifier = modifier, shape = shape,
             colors = CardDefaults.cardColors(containerColor = containerColor), content = content)
        return
    }
    val blurOn = GlassDialogWindow()
    JadooSurface(modifier = modifier, shape = shape, color = containerColor, level = GlassLevel.Overlay,
                 windowBlur = blurOn) {
        Column(content = content)
    }
}

/** AlertDialog drop-in (the subset this app uses). Standard: exactly AlertDialog. */
@Composable
fun JadooAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) {
        AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
                    dismissButton = dismissButton, title = title, text = text)
        return
    }
    val shape = RoundedCornerShape(32.dp)
    var blurOn by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { blurOn = GlassDialogWindow(); confirmButton() },
        modifier = modifier.liquidGlass(shape, MaterialTheme.colorScheme.surfaceContainerHigh, GlassLevel.Overlay,
                                        windowBlur = blurOn),
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp
    )
}

/**
 * DropdownMenu styling. A menu is a Popup, a window the app can't sample
 * behind or ask to blur, so under Liquid Glass it is a thick Overlay-alpha
 * fill (AA over any screen colour) with the glass rim and capsule-ish radius.
 * Standard: Material's defaults.
 */
@Composable
fun jadooMenuShape(): Shape =
    if (LocalUiStyle.current != UiStyle.LiquidGlass) MenuDefaults.shape else RoundedCornerShape(22.dp)

@Composable
fun jadooMenuContainer(): Color {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) return MenuDefaults.containerColor
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val rendering = LocalGlassRendering.current
    val a = remember(scheme, dark, rendering) {
        glassFillAlpha(scheme, dark, GlassLevel.Overlay, GlassRendering.Scrim, scheme.surfaceContainerHigh,
                       listOf(scheme.onSurface, scheme.onSurfaceVariant))
    }
    return scheme.surfaceContainerHigh.copy(alpha = if (rendering == GlassRendering.Opaque) 0.97f else a)
}

/** ModalBottomSheet drag handle. Liquid Glass sheets have no outline stroke. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GlassSheetHandle() {
    androidx.compose.material3.BottomSheetDefaults.DragHandle()
}

/** ModalBottomSheet shape. Liquid Glass: 36 dp top corners. Standard: [standard]. */
@Composable
fun jadooSheetShape(standard: Shape): Shape =
    if (LocalUiStyle.current != UiStyle.LiquidGlass) standard
    else RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)

@Composable
fun jadooMenuBorder(): BorderStroke? =
    if (LocalUiStyle.current != UiStyle.LiquidGlass) null
    else null

/**
 * ModalBottomSheet container. Liquid Glass: a fill thick enough for AA over
 * any screen colour (the sheet also asks for window blur-behind, see
 * [GlassDialogWindow]), so the blurred screen shows through. Standard: [standard].
 */
@Composable
fun jadooSheetContainer(standard: Color, windowBlur: Boolean = false): Color {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) return standard
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val rendering = LocalGlassRendering.current
    if (windowBlur && rendering.samplesBackdrop()) return standard.copy(alpha = glassLiveAlpha(GlassLevel.Overlay, dark))
    val a = remember(scheme, dark, rendering, standard) {
        glassFillAlpha(scheme, dark, GlassLevel.Overlay, rendering, standard,
                       listOf(scheme.onSurface, scheme.onSurfaceVariant))
    }
    return standard.copy(alpha = a)
}

// ── Small controls (chips, buttons, segmented rows) ─────────────────────
// Glass controls sit inside a card's glass: a translucent fill and a rim,
// no sampling of their own. Standard: each returns the Material default.

@Composable
private fun isGlass() = LocalUiStyle.current == UiStyle.LiquidGlass

@Composable
private fun controlFill(color: Color): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val rendering = LocalGlassRendering.current
    val content = contentColorFor(color)
    val a = remember(scheme, dark, rendering, color, content) {
        glassFillAlpha(scheme, dark, GlassLevel.Nested, rendering, color, listOf(content))
    }
    return color.copy(alpha = a)
}

/**
 * One look for every small glass control, so chips, segmented buttons and tonal
 * buttons agree with each other and with the toggles:
 *  - resting: a lifted fill that reads against the card (not a ghost), onSurface text;
 *  - selected: the accent itself, like a switched-on toggle, onPrimary text.
 * The edge is a light catching the top of the control (a gradient hairline that
 * fades to nothing), not a uniform outline.
 */
@Composable
private fun controlRestFill(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return if (dark) Color.White.copy(alpha = 0.10f) else scheme.onSurface.copy(alpha = 0.07f)
}

@Composable
private fun controlRim(selected: Boolean): BorderStroke {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val brush = when {
        selected -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.06f)))
        dark -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)))
        else -> Brush.verticalGradient(listOf(scheme.onSurface.copy(alpha = 0.16f), scheme.onSurface.copy(alpha = 0.04f)))
    }
    return BorderStroke(1.dp, brush)
}

@Composable
fun jadooFilterChipColors(): SelectableChipColors =
    if (!isGlass()) FilterChipDefaults.filterChipColors()
    else {
        val scheme = MaterialTheme.colorScheme
        FilterChipDefaults.filterChipColors(
            containerColor = controlRestFill(),
            labelColor = scheme.onSurface,
            disabledContainerColor = controlRestFill().copy(alpha = 0.06f),
            selectedContainerColor = scheme.primary,
            selectedLabelColor = scheme.onPrimary,
            selectedLeadingIconColor = scheme.onPrimary
        )
    }

@Composable
fun jadooFilterChipBorder(enabled: Boolean, selected: Boolean): BorderStroke? =
    if (!isGlass()) FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected)
    else controlRim(selected)

@Composable
fun jadooChipShape(standard: Shape): Shape = if (!isGlass()) standard else RoundedCornerShape(50)

@Composable
fun jadooAssistChipColors(): ChipColors =
    if (!isGlass()) AssistChipDefaults.assistChipColors()
    else AssistChipDefaults.assistChipColors(
        containerColor = controlRestFill(),
        labelColor = MaterialTheme.colorScheme.onSurface
    )

@Composable
fun jadooAssistChipBorder(enabled: Boolean): BorderStroke =
    if (!isGlass()) AssistChipDefaults.assistChipBorder(enabled = enabled)
    else controlRim(selected = false)

@Composable
fun jadooTonalButtonColors(): ButtonColors =
    if (!isGlass()) ButtonDefaults.filledTonalButtonColors()
    else ButtonDefaults.filledTonalButtonColors(
        containerColor = controlRestFill(),
        contentColor = MaterialTheme.colorScheme.onSurface
    )

@Composable
fun jadooTonalButtonBorder(): BorderStroke? =
    if (!isGlass()) null
    else controlRim(selected = false)

@Composable
fun jadooSegmentedColors(): SegmentedButtonColors =
    if (!isGlass()) SegmentedButtonDefaults.colors()
    else {
        val scheme = MaterialTheme.colorScheme
        SegmentedButtonDefaults.colors(
            activeContainerColor = scheme.primary,
            activeContentColor = scheme.onPrimary,
            inactiveContainerColor = controlRestFill(),
            inactiveContentColor = scheme.onSurface,
            activeBorderColor = Color.Transparent,
            inactiveBorderColor = Color.Transparent
        )
    }

/**
 * A plain background on a control row (tab strips, value pills). Standard:
 * `background(color)` as before. Glass: nested glass in that colour.
 */
@Composable
fun Modifier.jadooControlBackground(color: Color, shape: Shape, level: GlassLevel = GlassLevel.Nested): Modifier =
    if (!isGlass()) this.background(color)
    else this.liquidGlass(shape, color, level)

/** Card(colors = cardColors(containerColor)) drop-in. Standard: that Card. Liquid Glass: the same card as lens glass (Level Card). */
@Composable
fun JadooCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    level: GlassLevel = GlassLevel.Card,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!isGlass()) {
        Card(modifier = modifier, shape = shape,
             colors = CardDefaults.cardColors(containerColor = containerColor), content = content)
        return
    }
    JadooSurface(modifier = modifier, shape = shape, color = containerColor, level = level) {
        Column(content = content)
    }
}

// ── Contrast tint under labels ──────────────────────────────────────────

/**
 * A tint drawn directly beneath a label (draw phase only: no layout or size
 * change) so the label reaches WCAG AA 4.5:1 against the worst case the
 * dynamic glass backdrop can show, in the current scheme. The alpha comes from
 * [glassLabelTintAlpha] using the real colours. Standard: no-op.
 */
@Composable
fun Modifier.glassLabelTint(): Modifier {
    if (LocalUiStyle.current != UiStyle.LiquidGlass) return this
    // Refraction: the lens thickens the tint under text itself; a box here would show.
    if (LocalGlassRendering.current == GlassRendering.Refraction) return this
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val tint = scheme.surfaceContainer
    val alpha = remember(scheme, dark) {
        glassLabelTintAlpha(scheme, dark, tint, listOf(scheme.onSurface, scheme.onSurfaceVariant))
    }
    val color = tint.copy(alpha = alpha)
    return this.drawBehind {
        val padX = 4.dp.toPx(); val padY = 1.dp.toPx()
        drawRoundRect(color, Offset(-padX, -padY), Size(size.width + 2 * padX, size.height + 2 * padY),
                      CornerRadius(6.dp.toPx()))
    }
}
