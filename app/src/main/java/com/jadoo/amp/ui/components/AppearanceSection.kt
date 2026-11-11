package com.jadoo.amp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jadoo.amp.settings.ThemeSettings
import com.jadoo.amp.settings.effectiveBackdrop
import com.jadoo.amp.settings.toSpec
import androidx.compose.ui.draw.alpha
import com.jadoo.amp.ui.theme.BrandScheme
import com.jadoo.amp.ui.theme.JadooTheme
import com.jadoo.amp.ui.theme.SeedSwatches
import com.jadoo.amp.ui.theme.buildColorScheme

/**
 * Appearance controls.
 *
 * Presented in a card, matching every other settings/dashboard section — this
 * used to be a bare Column with no elevation or grouping surface, which read
 * as an unfinished page next to sections that all use the same rounded,
 * tonally-elevated container.
 *
 * Three levels of commitment:
 *
 *  - **Mode** is the choice everyone makes, so it is always visible — and
 *    shown as three live colour previews rather than a text-only segmented
 *    control. A settings screen about colour that shows no colour is the
 *    thing that reads as empty; the previews are the actual fix, not the card.
 *  - **Tone** (auto/light/dark) is collapsed by default. Almost nobody
 *    overrides it — "follow the system" is right for them — but when someone
 *    does want it, they want it badly. The collapsed row resolves the live
 *    state in its subtitle ("Follows system · Dark now") so the information is
 *    never hidden, only the controls are.
 *  - **Background** shows each backdrop as a live preview of its real
 *    surfaces. Black is inert in light mode: a pure-black light theme is
 *    not a thing.
 */
@Composable
fun AppearanceSection(
    settings: ThemeSettings,
    // Each callback carries the ROOT-COORDINATE position of the control that
    // was hit. The theme transition wipes out from that point, which is what
    // makes the change read as caused by the tap rather than merely following
    // it. Resolved with onGloballyPositioned rather than a pointer event so
    // that keyboard and accessibility activation get a sensible origin too.
    onModeChanged: (Offset, String) -> Unit,
    onToneChanged: (Offset, String) -> Unit,
    onBackdropChanged: (Offset, String) -> Unit,
    onSeedChanged: (Offset, Color) -> Unit,
    modifier: Modifier = Modifier,
    onUiStyleChanged: (Offset, String) -> Unit = { _, _ -> }
) {
    val dimens = JadooTheme.dimens
    val alpha = JadooTheme.alpha
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (settings.tone) {
        "Light" -> false
        "Dark" -> true
        else -> systemDark
    }

    var toneExpanded by remember { mutableStateOf(false) }

    // Centre of each control group in root coordinates, captured as it lays
    // out. Zero until the first layout pass, which is harmless — the curtain
    // simply wipes from the top-left corner on a tap that beats layout.
    var modeRowCentre by remember { mutableStateOf(Offset.Zero) }
    var swatchRowCentre by remember { mutableStateOf(Offset.Zero) }
    var toneRowCentre by remember { mutableStateOf(Offset.Zero) }
    var backdropRowCentre by remember { mutableStateOf(Offset.Zero) }
    var styleRowCentre by remember { mutableStateOf(Offset.Zero) }

    // surfaceContainerHighest, not surfaceContainerHigh — this card lives
    // inside SettingsDialog, whose own Card is already surfaceContainerHigh;
    // see SettingsCard's doc comment in DashboardScreen.kt for why the same
    // token one level down doesn't read as a distinct card.
    GlassAwareSurface(modifier = modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.lg, vertical = dimens.lg),
        verticalArrangement = Arrangement.spacedBy(dimens.md)
    ) {
        // ── Mode: live colour previews, not a text control ────────────────
        val context = androidx.compose.ui.platform.LocalContext.current
        val modes = listOf("Brand" to "Brand", "MaterialYou" to "You", "CustomSeed" to "Custom")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { modeRowCentre = it.centreInRoot() }
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(dimens.sm)
        ) {
            modes.forEach { (key, label) ->
                val previewScheme = remember(key, effectiveDark, settings.seedColor) {
                    when (key) {
                        "Brand" -> if (effectiveDark) BrandScheme.Dark else BrandScheme.Light
                        "CustomSeed" -> buildColorScheme(
                            com.jadoo.amp.ui.theme.ThemeSpec(
                                mode = com.jadoo.amp.ui.theme.ThemeMode.CustomSeed,
                                tone = if (effectiveDark) com.jadoo.amp.ui.theme.ToneMode.Dark
                                       else com.jadoo.amp.ui.theme.ToneMode.Light,
                                seed = Color(settings.seedColor)
                            ),
                            effectiveDark, context
                        )
                        else -> buildColorScheme(
                            com.jadoo.amp.ui.theme.ThemeSpec(
                                mode = com.jadoo.amp.ui.theme.ThemeMode.MaterialYou,
                                tone = if (effectiveDark) com.jadoo.amp.ui.theme.ToneMode.Dark
                                       else com.jadoo.amp.ui.theme.ToneMode.Light
                            ),
                            effectiveDark, context
                        )
                    }
                }
                ModePreviewTile(
                    label = label,
                    scheme = previewScheme,
                    selected = settings.mode == key,
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = settings.mode == key,
                            role = Role.RadioButton,
                            onClick = { onModeChanged(modeRowCentre, key) }
                        )
                )
            }
        }

        // ── Seed swatches, only when Custom is the mode ───────────────────
        AnimatedVisibility(
            visible = settings.mode == "CustomSeed",
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { swatchRowCentre = it.centreInRoot() }
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(dimens.md)
            ) {
                SeedSwatches.forEach { color ->
                    val selected = color.value == Color(settings.seedColor).value
                    Box(
                        modifier = Modifier
                            .size(dimens.xxxl)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (selected) Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                ) else Modifier
                            )
                            .clickable(role = Role.RadioButton) { onSeedChanged(swatchRowCentre, color) }
                            .semantics { contentDescription = "Accent colour swatch" },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // ── Tone: collapsed by default ────────────────────────────────────
        val chevronRotation by animateFloatAsState(
            targetValue = if (toneExpanded) 180f else 0f,
            animationSpec = JadooTheme.motion.standard,
            label = "toneChevron"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { toneRowCentre = it.centreInRoot() }
                .clip(JadooTheme.shapes.control)
                .clickable { toneExpanded = !toneExpanded }
                .padding(vertical = dimens.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Light / Dark", style = MaterialTheme.typography.bodyLarge)
                Text(
                    // The live resolved state, so collapsing hides the
                    // controls without hiding the information.
                    text = when (settings.tone) {
                        "Light" -> "Always light"
                        "Dark" -> "Always dark"
                        else -> "Follows system · ${if (systemDark) "Dark" else "Light"} now"
                    },
                    style = JadooTheme.text.bodyCompact,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (toneExpanded) "Collapse" else "Expand",
                modifier = Modifier.rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = toneExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val tones = listOf("System" to "Auto", "Light" to "Light", "Dark" to "Dark")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                tones.forEachIndexed { index, (key, label) ->
                    SegmentedButton(
                        selected = settings.tone == key,
                        onClick = { onToneChanged(toneRowCentre, key) },
                        shape = SegmentedButtonDefaults.itemShape(index, tones.size),
                        colors = jadooSegmentedColors()
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }

        // ── Background: live previews of each backdrop ────────────────────
        Text("Background", style = MaterialTheme.typography.bodyLarge)
        val backdrops = listOf("Dim" to "Dim", "Tinted" to "Tinted", "Aurora" to "Aurora", "Black" to "Black")
        val current = settings.effectiveBackdrop
        val previewContext = androidx.compose.ui.platform.LocalContext.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { backdropRowCentre = it.centreInRoot() }
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(dimens.sm)
        ) {
            backdrops.forEach { (key, label) ->
                // Black only exists in dark mode; in light mode it is shown
                // but inert, with the reason in its label.
                val available = key != "Black" || effectiveDark
                val preview = remember(key, effectiveDark, settings.mode, settings.seedColor) {
                    buildColorScheme(
                        settings.copy(backdrop = key, tone = if (effectiveDark) "Dark" else "Light").toSpec(),
                        effectiveDark, previewContext
                    )
                }
                BackdropPreviewTile(
                    label = if (available) label else "Black (dark)",
                    scheme = preview,
                    aurora = key == "Aurora",
                    selected = current == key && available,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (available) 1f else 0.4f)
                        .selectable(
                            selected = current == key,
                            enabled = available,
                            role = Role.RadioButton,
                            onClick = { onBackdropChanged(backdropRowCentre, key) }
                        )
                )
            }
        }

        // ── Style: Standard or the Liquid Glass emulation ─────────────────
        Text("Style", style = MaterialTheme.typography.bodyLarge)
        val styles = listOf("Standard" to "Standard", "LiquidGlass" to "UI - Liquid Glass")
        val currentStyle = if (settings.uiStyle == "LiquidGlass") "LiquidGlass" else "Standard"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { styleRowCentre = it.centreInRoot() }
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(dimens.sm)
        ) {
            styles.forEach { (key, label) ->
                StylePreviewTile(
                    label = label,
                    glass = key == "LiquidGlass",
                    selected = currentStyle == key,
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = currentStyle == key,
                            role = Role.RadioButton,
                            onClick = { if (currentStyle != key) onUiStyleChanged(styleRowCentre, key) }
                        )
                )
            }
        }
    }
    }
}

/** A style option: a mini surface drawn the way that style draws cards. */
@Composable
private fun StylePreviewTile(
    label: String,
    glass: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val dimens = JadooTheme.dimens
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.xs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(JadooTheme.shapes.control)
                .background(scheme.background)
                .then(if (glass) Modifier.background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(scheme.primary.copy(alpha = 0.45f), scheme.tertiary.copy(alpha = 0.35f)))) else Modifier)
                .selectionOutline(selected, JadooTheme.shapes.control),
            contentAlignment = Alignment.Center
        ) {
            val chip = Modifier.fillMaxWidth().padding(10.dp).height(26.dp)
            if (glass) Box(chip.liquidGlass(CircleShape, forceGlass = true))
            else Box(chip.clip(CircleShape).background(scheme.surfaceContainer))
            if (selected) SelectedBadge()
        }
        Text(
            text = label,
            style = JadooTheme.text.bodyCompact,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** A backdrop option: its real background with a grouped-list chip on top. */
@Composable
private fun BackdropPreviewTile(
    label: String,
    scheme: androidx.compose.material3.ColorScheme,
    aurora: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val dimens = JadooTheme.dimens
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.xs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(JadooTheme.shapes.control)
                .background(scheme.background)
                .then(if (aurora) Modifier.background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(scheme.primary.copy(alpha = 0.35f), Color.Transparent))) else Modifier)
                .selectionOutline(selected, JadooTheme.shapes.control),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(Modifier.fillMaxWidth().padding(8.dp).height(18.dp).clip(CircleShape).background(scheme.surfaceContainer))
            if (selected) SelectedBadge()
        }
        Text(
            text = label,
            style = JadooTheme.text.bodyCompact,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * One mode option, rendered as an actual swatch of that scheme rather than a
 * label — a background chip in the scheme's real background colour, three dots
 * for primary/secondary/tertiary, and a check mark when selected. This is what
 * replaced the plain segmented-button text row.
 */
@Composable
private fun ModePreviewTile(
    label: String,
    scheme: androidx.compose.material3.ColorScheme,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val dimens = JadooTheme.dimens
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.xs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(JadooTheme.shapes.control)
                .background(scheme.background)
                .selectionOutline(selected, JadooTheme.shapes.control),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { dot ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(dot)
                    )
                }
            }
            if (selected) SelectedBadge()
        }
        Text(
            text = label,
            style = JadooTheme.text.bodyCompact,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Centre of a laid-out node in root coordinates. */
private fun LayoutCoordinates.centreInRoot(): Offset = boundsInRoot().center
