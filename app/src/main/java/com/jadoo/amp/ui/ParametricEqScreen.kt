package com.jadoo.amp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadoo.amp.audio.DigitalFilterEngine
import kotlin.math.*

private val BandColors = listOf(
    Color(0xFFFFFFFF), // White (selected default)
    Color(0xFF00E5FF), // Electric Cyan
    Color(0xFFFF0055), // Neon Pink
    Color(0xFF00FF66), // Neon Green
    Color(0xFFFFD500), // Cyber Yellow
    Color(0xFF8C00FF), // Neon Purple
    Color(0xFFFF5500), // Neon Orange
    Color(0xFF00BFFF), // Deep Sky Blue
    Color(0xFF00FFCC), // Aquamarine
    Color(0xFFFF00E5), // Magenta
    Color(0xFF39FF14), // Neon Lime
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParametricEqScreen(
    bandStates: List<DigitalFilterEngine.BiquadBandState>,
    preampDb: Float = 0f,
    onBandTypeChanged: (Int, DigitalFilterEngine.FilterType) -> Unit,
    onBandFrequencyChanged: (Int, Float) -> Unit,
    onBandGainChanged: (Int, Float) -> Unit,
    onBandQChanged: (Int, Float) -> Unit,
    onBandEnabledChanged: (Int, Boolean) -> Unit,
    onPreampChanged: (Float) -> Unit = {},
    onResetAllBands: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showResetConfirm by remember { mutableStateOf(false) }
    var selectedBandIndex by remember { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf(0) } // 0=Parametric, 1=Graphic, 2=Table

    val selectedBand = bandStates.getOrNull(selectedBandIndex)
        ?: DigitalFilterEngine.BiquadBandState(
            index = 0, type = DigitalFilterEngine.FilterType.Peak,
            frequency = 1000f, gain = 0f, q = 1.0f, isEnabled = false
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Top bar: back + title + reset ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Parametric EQ",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { showResetConfirm = true },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("Reset", style = MaterialTheme.typography.labelMedium)
            }
        }

        // ── Tabs ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf("Parametric", "Graphic", "Table")
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(21.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { selectedTab = index },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (selectedTab == 0) {
            // ── Graph ──
            EqGraph(
                bandStates = bandStates,
                selectedBandIndex = selectedBandIndex,
                onSelectBand = { selectedBandIndex = it },
                onBandFreqGainChanged = { index, freq, gain ->
                    onBandFrequencyChanged(index, freq)
                    onBandGainChanged(index, gain)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )

            // ── Band selector ──
            Text(
                text = "Bands",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            BandSelector(
                bandStates = bandStates,
                selectedIndex = selectedBandIndex,
                onSelect = { selectedBandIndex = it },
                onAddBand = {
                    val firstDisabled = bandStates.indexOfFirst { !it.isEnabled }
                    if (firstDisabled != -1) {
                        onBandEnabledChanged(firstDisabled, true)
                        selectedBandIndex = firstDisabled
                    }
                }
            )

            // ── Active band info card ──
            val bandColor = BandColors[(selectedBandIndex % (BandColors.size - 1)) + 1]
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(bandColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Band ${selectedBandIndex + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedBand.isEnabled)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (selectedBand.isEnabled) selectedBand.type.name else "BYPASS",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedBand.isEnabled)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (selectedBand.isEnabled) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${formatFrequency(selectedBand.frequency)} Hz · ${String.format("%+.1f", selectedBand.gain)} dB · Q ${String.format("%.2f", selectedBand.q)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Filter type ──
            Text(
                text = "Filter Type",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        DigitalFilterEngine.FilterType.Peak to "PEAK",
                        DigitalFilterEngine.FilterType.LowShelf to "LOW SHELF",
                        DigitalFilterEngine.FilterType.HighShelf to "HIGH SHELF"
                    ).forEach { (type, label) ->
                        FilterTypePill(
                            label = label,
                            selected = selectedBand.isEnabled && selectedBand.type == type,
                            onClick = {
                                if (!selectedBand.isEnabled) onBandEnabledChanged(selectedBandIndex, true)
                                onBandTypeChanged(selectedBandIndex, type)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        DigitalFilterEngine.FilterType.LowPass to "LOW PASS",
                        DigitalFilterEngine.FilterType.HighPass to "HIGH PASS"
                    ).forEach { (type, label) ->
                        FilterTypePill(
                            label = label,
                            selected = selectedBand.isEnabled && selectedBand.type == type,
                            onClick = {
                                if (!selectedBand.isEnabled) onBandEnabledChanged(selectedBandIndex, true)
                                onBandTypeChanged(selectedBandIndex, type)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    FilterTypePill(
                        label = "BYPASS",
                        selected = !selectedBand.isEnabled,
                        onClick = { onBandEnabledChanged(selectedBandIndex, false) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Parameter sliders ──
            if (selectedBand.isEnabled) {
                Text(
                    text = "Parameters",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    tonalElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        EqParamSlider(
                            label = "Freq",
                            value = selectedBand.frequency,
                            valueRange = 20f..20000f,
                            logarithmic = true,
                            displayFormatter = { formatFrequency(it) },
                            onValueChange = { onBandFrequencyChanged(selectedBandIndex, it) },
                            activeColor = bandColor
                        )
                        EqParamSlider(
                            label = "Gain",
                            value = selectedBand.gain,
                            valueRange = -15f..15f,
                            logarithmic = false,
                            displayFormatter = { String.format("%+.1f dB", it) },
                            onValueChange = { onBandGainChanged(selectedBandIndex, it) },
                            activeColor = bandColor
                        )
                        EqParamSlider(
                            label = "Q",
                            value = selectedBand.q,
                            valueRange = 0.1f..18f,
                            logarithmic = false,
                            displayFormatter = { String.format("%.2f", it) },
                            onValueChange = { onBandQChanged(selectedBandIndex, it) },
                            activeColor = bandColor
                        )
                    }
                }
            }

            // ── Preamp ──
            Text(
                text = "Preamp",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            PreampSlider(
                value = preampDb,
                onValueChange = onPreampChanged
            )

            Spacer(Modifier.height(8.dp))
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (selectedTab == 1) "Graphic view coming soon" else "Table view coming soon",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all bands?") },
            text = { Text("This will disable all 8 PEQ bands and restore their default values.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    onResetAllBands()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  GRAPH
// ═══════════════════════════════════════════════════════════

@Composable
private fun EqGraph(
    bandStates: List<DigitalFilterEngine.BiquadBandState>,
    selectedBandIndex: Int,
    onSelectBand: (Int) -> Unit,
    onBandFreqGainChanged: (Int, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val responsePoints = remember(bandStates) {
        calculateFrequencyResponse(bandStates)
    }
    val graphBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val zeroDbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val curveColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    var graphSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val padding = 40f

    fun freqToX(freq: Float, width: Float): Float {
        val logMin = log10(20f)
        val logMax = log10(20000f)
        return padding + ((log10(freq.coerceIn(20f, 20000f)) - logMin) / (logMax - logMin)) * (width - 2 * padding)
    }

    fun dbToY(db: Float, height: Float): Float {
        val dbMin = -20f
        val dbMax = 20f
        return height - padding - ((db.coerceIn(dbMin, dbMax) - dbMin) / (dbMax - dbMin)) * (height - 2 * padding)
    }

    fun xToFreq(x: Float, width: Float): Float {
        val logMin = log10(20f)
        val logMax = log10(20000f)
        val ratio = ((x - padding) / (width - 2 * padding)).coerceIn(0f, 1f)
        return 10f.pow(logMin + ratio * (logMax - logMin))
    }

    fun yToDb(y: Float, height: Float): Float {
        val dbMin = -20f
        val dbMax = 20f
        val ratio = ((height - padding - y) / (height - 2 * padding)).coerceIn(0f, 1f)
        return dbMin + ratio * (dbMax - dbMin)
    }

    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = graphBackground)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top info bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, null,
                            tint = textColor, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Edit, null,
                            tint = textColor, modifier = Modifier.size(18.dp))
                    }
                    val selectedBand = bandStates.getOrNull(selectedBandIndex)
                    if (selectedBand != null && selectedBand.isEnabled) {
                        Text(
                            text = "Band ${selectedBandIndex + 1}: ${selectedBand.frequency.roundToInt()} Hz (${String.format("%.1f", selectedBand.gain)} dB) | ${selectedBand.type.name.uppercase()}",
                            color = textColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = "No band selected",
                            color = textColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, null,
                            tint = textColor, modifier = Modifier.size(18.dp))
                    }
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp, bottom = 4.dp)
                        .pointerInput(bandStates) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val w = graphSize.width
                                    val h = graphSize.height
                                    if (w <= 0 || h <= 0) return@detectDragGestures
                                    // Hit-test: only select a band if touch is within
                                    // a reasonable distance of its control point.
                                    // Threshold is 6% of the smaller canvas dimension.
                                    val threshold = minOf(w, h) * 0.06f
                                    var closestDist = Float.MAX_VALUE
                                    var closestIndex = -1
                                    for ((i, band) in bandStates.withIndex()) {
                                        if (!band.isEnabled) continue
                                        val px = freqToX(band.frequency, w)
                                        val py = dbToY(band.gain, h)
                                        val dist = hypot(offset.x - px, offset.y - py)
                                        if (dist < threshold && dist < closestDist) {
                                            closestDist = dist
                                            closestIndex = i
                                        }
                                    }
                                    if (closestIndex != -1) {
                                        onSelectBand(closestIndex)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val w = graphSize.width
                                    val h = graphSize.height
                                    if (w <= 0 || h <= 0) return@detectDragGestures
                                    val band = bandStates.getOrNull(selectedBandIndex)
                                        ?: return@detectDragGestures
                                    if (!band.isEnabled) return@detectDragGestures
                                    val newFreq = xToFreq(change.position.x, w)
                                    val newGain = yToDb(change.position.y, h)
                                    onBandFreqGainChanged(selectedBandIndex, newFreq, newGain)
                                }
                            )
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    graphSize = size

                    // Horizontal grid lines (dB)
                    for (db in listOf(-20, -10, 0, 10, 20)) {
                        val y = dbToY(db.toFloat(), h)
                        val color = if (db == 0) zeroDbColor else gridColor
                        val stroke = if (db == 0) 1.5f else 1f
                        drawLine(color, Offset(padding, y), Offset(w - padding, y), strokeWidth = stroke)
                    }

                    // Vertical grid lines (freq)
                    for (freq in listOf(100, 1000, 10000)) {
                        val x = freqToX(freq.toFloat(), w)
                        drawLine(gridColor, Offset(x, padding), Offset(x, h - padding), strokeWidth = 1f)
                    }

                    // Draw filled area + curve
                    if (responsePoints.isNotEmpty()) {
                        val path = Path()
                        val fillPath = Path()
                        val firstX = freqToX(responsePoints.first().frequency, w)
                        val firstY = dbToY(responsePoints.first().gain, h)
                        path.moveTo(firstX, firstY)
                        fillPath.moveTo(firstX, h - padding)
                        fillPath.lineTo(firstX, firstY)

                        for (point in responsePoints) {
                            val x = freqToX(point.frequency, w)
                            val y = dbToY(point.gain, h)
                            path.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                        fillPath.lineTo(freqToX(responsePoints.last().frequency, w), h - padding)
                        fillPath.close()

                        drawPath(path = fillPath, color = fillColor)
                        drawPath(path = path, color = curveColor, style = Stroke(width = 2.5f))
                    }

                    // Draw control points
                    for ((i, band) in bandStates.withIndex()) {
                        if (!band.isEnabled) continue
                        val px = freqToX(band.frequency, w)
                        val py = dbToY(band.gain, h)
                        val color = BandColors[(i % (BandColors.size - 1)) + 1]
                        val isSelected = i == selectedBandIndex

                        if (isSelected) {
                            // Neon glow effect
                            drawCircle(
                                color = color.copy(alpha = 0.3f),
                                radius = 24f,
                                center = Offset(px, py)
                            )
                        }
                        // Outer circle
                        drawCircle(
                            color = if (isSelected) color else color.copy(alpha = 0.7f),
                            radius = if (isSelected) 10f else 8f,
                            center = Offset(px, py)
                        )
                        // Inner white dot
                        drawCircle(
                            color = Color.White,
                            radius = if (isSelected) 4f else 3f,
                            center = Offset(px, py)
                        )
                        // Number label below
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint().apply {
                                this.color = android.graphics.Color.argb(
                                    (color.alpha * 255).toInt(),
                                    (color.red * 255).toInt(),
                                    (color.green * 255).toInt(),
                                    (color.blue * 255).toInt()
                                )
                                textSize = 20f
                                textAlign = android.graphics.Paint.Align.CENTER
                                isFakeBoldText = true
                            }
                            canvas.nativeCanvas.drawText(
                                "${i + 1}",
                                px,
                                py + 22f,
                                paint
                            )
                        }
                    }
                }

                // Frequency labels at bottom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("100", "1k", "10k").forEach { label ->
                        Text(
                            text = label,
                            color = textColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  BAND SELECTOR
// ═══════════════════════════════════════════════════════════

@Composable
private fun BandSelector(
    bandStates: List<DigitalFilterEngine.BiquadBandState>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onAddBand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        bandStates.forEachIndexed { index, band ->
            val selected = index == selectedIndex
            val enabled = band.isEnabled
            val color = BandColors[(index % (BandColors.size - 1)) + 1]

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.primaryContainer
                            enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
                            else -> MaterialTheme.colorScheme.surfaceContainer
                        }
                    )
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Filter curve icon
                    FilterCurveIcon(
                        type = band.type,
                        color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            selected -> MaterialTheme.colorScheme.onPrimaryContainer
                            enabled -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Add band button
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable { onAddBand() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  FILTER TYPE PILL
// ═══════════════════════════════════════════════════════════

@Composable
private fun FilterTypePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  PARAMETER SLIDER WITH NUMERIC INPUT
// ═══════════════════════════════════════════════════════════

@Composable
private fun EqParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    logarithmic: Boolean,
    displayFormatter: (Float) -> String,
    onValueChange: (Float) -> Unit,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(44.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        val sliderValue = if (logarithmic) {
            val logMin = log10(valueRange.start.coerceAtLeast(1f))
            val logMax = log10(valueRange.endInclusive)
            val logCur = log10(value.coerceAtLeast(1f))
            ((logCur - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
        } else {
            ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        }

        Slider(
            value = sliderValue,
            onValueChange = { newSliderValue ->
                val newValue = if (logarithmic) {
                    val logMin = log10(valueRange.start.coerceAtLeast(1f))
                    val logMax = log10(valueRange.endInclusive)
                    10f.pow(logMin + newSliderValue * (logMax - logMin))
                } else {
                    valueRange.start + newSliderValue * (valueRange.endInclusive - valueRange.start)
                }
                onValueChange(newValue)
            },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                activeTrackColor = activeColor,
                inactiveTrackColor = activeColor.copy(alpha = 0.2f),
                thumbColor = activeColor
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Numeric display box
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayFormatter(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  PREAMP SLIDER
// ═══════════════════════════════════════════════════════════

@Composable
private fun PreampSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Preamp (dB)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(90.dp)
        )

        Slider(
            value = ((value + 12f) / 24f).coerceIn(0f, 1f),
            onValueChange = { onValueChange(-12f + it * 24f) },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .width(64.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = String.format("%.1f", value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  FILTER CURVE ICON (mini SVG-like drawing)
// ═══════════════════════════════════════════════════════════

@Composable
private fun FilterCurveIcon(
    type: DigitalFilterEngine.FilterType,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path()
        when (type) {
            DigitalFilterEngine.FilterType.Peak -> {
                path.moveTo(0f, h * 0.7f)
                path.quadraticBezierTo(w * 0.5f, h * 0.1f, w, h * 0.7f)
            }
            DigitalFilterEngine.FilterType.LowShelf -> {
                path.moveTo(0f, h * 0.85f)
                path.lineTo(w * 0.4f, h * 0.85f)
                path.lineTo(w * 0.6f, h * 0.3f)
                path.lineTo(w, h * 0.3f)
            }
            DigitalFilterEngine.FilterType.HighShelf -> {
                path.moveTo(0f, h * 0.3f)
                path.lineTo(w * 0.4f, h * 0.3f)
                path.lineTo(w * 0.6f, h * 0.85f)
                path.lineTo(w, h * 0.85f)
            }
            DigitalFilterEngine.FilterType.LowPass -> {
                path.moveTo(0f, h * 0.2f)
                path.lineTo(w * 0.5f, h * 0.2f)
                path.quadraticBezierTo(w * 0.8f, h * 0.2f, w, h * 0.9f)
            }
            DigitalFilterEngine.FilterType.HighPass -> {
                path.moveTo(0f, h * 0.9f)
                path.quadraticBezierTo(w * 0.2f, h * 0.2f, w * 0.5f, h * 0.2f)
                path.lineTo(w, h * 0.2f)
            }
            else -> {
                path.moveTo(0f, h * 0.5f)
                path.lineTo(w, h * 0.5f)
            }
        }
        drawPath(path = path, color = color, style = Stroke(width = 2f))
    }
}

// ═══════════════════════════════════════════════════════════
//  HELPERS
// ═══════════════════════════════════════════════════════════

private fun formatFrequency(freq: Float): String {
    return when {
        freq < 1000f -> "${freq.roundToInt()}"
        else -> String.format("%.1f", freq / 1000f)
    }
}

private data class ResponsePoint(val frequency: Float, val gain: Float)

private fun calculateFrequencyResponse(
    bandStates: List<DigitalFilterEngine.BiquadBandState>,
    sampleRateHz: Float = 48000f
): List<ResponsePoint> {
    val enabledBands = bandStates.filter { it.isEnabled }
    if (enabledBands.isEmpty()) {
        return (20..20000 step 100).map { ResponsePoint(it.toFloat(), 0f) }
    }
    val frequencies = mutableListOf<Float>()
    var freq = 20f
    while (freq <= 20000f) {
        frequencies.add(freq)
        freq *= 1.08f
    }
    return frequencies.map { f ->
        var totalGain = 0f
        for (band in enabledBands) {
            totalGain += calculateBandGain(band, f, sampleRateHz)
        }
        ResponsePoint(f, totalGain.coerceIn(-20f, 20f))
    }
}

private fun calculateBandGain(
    band: DigitalFilterEngine.BiquadBandState,
    frequency: Float,
    sampleRateHz: Float
): Float {
    val w0 = 2.0 * PI * band.frequency / sampleRateHz
    val cosW0 = cos(w0)
    val sinW0 = sin(w0)
    val alpha = sinW0 / (2.0 * band.q)
    val A = 10.0.pow(band.gain / 40.0)

    val b0: Double; val b1: Double; val b2: Double
    val a0: Double; val a1: Double; val a2: Double

    when (band.type) {
        DigitalFilterEngine.FilterType.Peak -> {
            b0 = 1.0 + alpha * A; b1 = -2.0 * cosW0; b2 = 1.0 - alpha * A
            a0 = 1.0 + alpha / A; a1 = -2.0 * cosW0; a2 = 1.0 - alpha / A
        }
        DigitalFilterEngine.FilterType.LowShelf -> {
            val sqrtA = sqrt(A)
            b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
            b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
            b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
            a0 = (A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha
            a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
            a2 = (A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha
        }
        DigitalFilterEngine.FilterType.HighShelf -> {
            val sqrtA = sqrt(A)
            b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
            b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)
            b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
            a0 = (A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha
            a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0)
            a2 = (A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha
        }
        DigitalFilterEngine.FilterType.LowPass -> {
            b0 = (1.0 - cosW0) / 2.0; b1 = 1.0 - cosW0; b2 = (1.0 - cosW0) / 2.0
            a0 = 1.0 + alpha; a1 = -2.0 * cosW0; a2 = 1.0 - alpha
        }
        DigitalFilterEngine.FilterType.HighPass -> {
            b0 = (1.0 + cosW0) / 2.0; b1 = -(1.0 + cosW0); b2 = (1.0 + cosW0) / 2.0
            a0 = 1.0 + alpha; a1 = -2.0 * cosW0; a2 = 1.0 - alpha
        }
        else -> return 0f
    }

    if (a0 == 0.0) return 0f
    return DigitalFilterEngine.evaluateMagnitudeResponseDb(
        b0 = (b0 / a0).toFloat(), b1 = (b1 / a0).toFloat(), b2 = (b2 / a0).toFloat(),
        a1 = (a1 / a0).toFloat(), a2 = (a2 / a0).toFloat(),
        frequencyHz = frequency, sampleRateHz = sampleRateHz
    )
}
