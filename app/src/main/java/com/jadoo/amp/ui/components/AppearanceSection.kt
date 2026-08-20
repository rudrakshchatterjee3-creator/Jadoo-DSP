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
import androidx.compose.material3.Switch
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
 *  - **Pure Black** is disabled unless the effective tone is dark, because a
 *    pure-black light theme is not a thing.
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
    onAmoledChanged: (Offset, Boolean) -> Unit,
    onSeedChanged: (Offset, Color) -> Unit,
    modifier: Modifier = Modifier
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
    var amoledRowCentre by remember { mutableStateOf(Offset.Zero) }

    // surfaceContainerHighest, not surfaceContainerHigh — this card lives
    // inside SettingsDialog, whose own Card is already surfaceContainerHigh;
    // see SettingsCard's doc comment in DashboardScreen.kt for why the same
    // token one level down doesn't read as a distinct card.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = JadooTheme.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.lg, vertical = dimens.lg),
        verticalArrangement = Arrangement.spacedBy(dimens.md)
    ) {
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

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
                        shape = SegmentedButtonDefaults.itemShape(index, tones.size)
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }

        // ── Pure Black ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { amoledRowCentre = it.centreInRoot() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pure Black",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (effectiveDark) alpha.full else alpha.disabled
                    )
                )
                Text(
                    text = if (effectiveDark)
                        "True black backgrounds for OLED screens"
                    else
                        "Available in dark mode",
                    style = JadooTheme.text.bodyCompact,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (effectiveDark) alpha.full else alpha.disabled
                    )
                )
            }
            Switch(
                checked = settings.amoled && effectiveDark,
                onCheckedChange = { onAmoledChanged(amoledRowCentre, it) },
                enabled = effectiveDark
            )
        }
    }
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
                .height(56.dp)
                .clip(JadooTheme.shapes.control)
                .background(scheme.background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) scheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                    shape = JadooTheme.shapes.control
                ),
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
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
        Text(
            text = label,
            style = JadooTheme.text.bodyCompact,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Centre of a laid-out node in root coordinates. */
private fun LayoutCoordinates.centreInRoot(): Offset = boundsInRoot().center
