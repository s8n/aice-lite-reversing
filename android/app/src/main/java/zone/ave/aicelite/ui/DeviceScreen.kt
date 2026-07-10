package zone.ave.aicelite.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zone.ave.aicelite.protocol.AiceState
import zone.ave.aicelite.protocol.Mode
import zone.ave.aicelite.protocol.ModeOption
import zone.ave.aicelite.protocol.WIND_MAX
import zone.ave.aicelite.ui.theme.Ink
import zone.ave.aicelite.ui.theme.accent
import kotlin.math.roundToInt

@Composable
fun DeviceScreen(
    deviceName: String,
    state: AiceState?,
    selectedOption: ModeOption?,
    onPower: () -> Unit,
    onPause: () -> Unit,
    onTemp: (Int) -> Unit,
    onWindPreview: (Int) -> Unit,
    onWindCommit: () -> Unit,
    onMode: (Mode) -> Unit,
    onOption: (ModeOption) -> Unit,
    onSettings: () -> Unit,
) {
    val accent by animateColorAsState(state?.mode.accent, tween(400), label = "accent")

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Background)
            .padding(horizontal = 20.dp),
    ) {
        TopBar(deviceName = deviceName, accent = accent, running = state?.isRunning == true, onSettings = onSettings)

        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            NeckRing(accent = accent, running = state?.isRunning == true, modifier = Modifier.fillMaxSize())
            if (state != null) {
                BatteryIndicator(
                    percent = state.batteryPercent,
                    charging = state.isCharging,
                    accent = accent,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            PowerButton(
                on = state?.isPowered == true,
                accent = accent,
                enabled = state != null,
                onClick = onPower,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        // Push the controls to the bottom of the screen.
        Spacer(Modifier.weight(1f))

        if (state == null) {
            SyncingCard()
        } else {
            ClimateCard(state, accent, onTemp, onWindPreview, onWindCommit, onPause)
            Spacer(Modifier.height(14.dp))
            ModeCard(state, selectedOption, accent, onMode, onOption)
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun TopBar(deviceName: String, accent: Color, running: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("PIDCE LITE", style = MaterialTheme.typography.titleLarge, color = Ink.Primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(6.dp)) {
                    drawCircle(if (running) accent else Ink.Tertiary)
                }
                Spacer(Modifier.width(6.dp))
                Text(deviceName, style = MaterialTheme.typography.bodySmall, color = Ink.Tertiary)
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Gear, Ink.Secondary, size = 24.dp)
        }
    }
}

@Composable
private fun BatteryIndicator(percent: Int, charging: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val bolt = Color(0xFFFFD24A)
    val fill = when {
        charging -> Color(0xFF35D6C0)
        percent <= 15 -> Ink.Danger
        else -> Ink.Secondary
    }
    Row(
        modifier.padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 40.dp, height = 19.dp)) {
            val nub = size.width * 0.055f
            val bodyW = size.width - nub
            val stroke = size.height * 0.1f
            val r = CornerRadius(size.height * 0.28f, size.height * 0.28f)
            drawRoundRect(
                color = Ink.Secondary,
                topLeft = Offset(0f, 0f),
                size = Size(bodyW, size.height),
                cornerRadius = r,
                style = Stroke(width = stroke),
            )
            drawRoundRect(
                color = Ink.Secondary,
                topLeft = Offset(bodyW, size.height * 0.3f),
                size = Size(nub, size.height * 0.4f),
                cornerRadius = CornerRadius(nub, nub),
            )
            val inset = stroke * 1.6f
            val maxFillW = bodyW - inset * 2
            val fillW = maxFillW * (percent.coerceIn(0, 100) / 100f)
            if (fillW > 0f) {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(inset, inset),
                    size = Size(fillW.coerceAtLeast(stroke), size.height - inset * 2),
                    cornerRadius = CornerRadius(size.height * 0.16f, size.height * 0.16f),
                )
            }
            if (charging) {
                // Lightning bolt centered on the body, ~85% of its height, in yellow.
                val bs = size.height * 0.85f
                val ox = bodyW / 2 - bs / 2
                val oy = (size.height - bs) / 2
                fun px(ux: Float, uy: Float) = Offset(ox + ux * bs, oy + uy * bs)
                val p = Path().apply {
                    moveTo(px(0.58f, 0.05f).x, px(0.58f, 0.05f).y)
                    lineTo(px(0.18f, 0.56f).x, px(0.18f, 0.56f).y)
                    lineTo(px(0.46f, 0.56f).x, px(0.46f, 0.56f).y)
                    lineTo(px(0.40f, 0.95f).x, px(0.40f, 0.95f).y)
                    lineTo(px(0.82f, 0.42f).x, px(0.82f, 0.42f).y)
                    lineTo(px(0.54f, 0.42f).x, px(0.54f, 0.42f).y)
                    close()
                }
                // dark halo for contrast on the teal fill, then the yellow bolt
                drawPath(p, Ink.Background.copy(alpha = 0.55f), style = Stroke(width = stroke * 1.4f))
                drawPath(p, bolt)
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = if (charging) bolt else if (percent <= 15) Ink.Danger else Ink.Secondary,
        )
    }
}

@Composable
private fun PowerButton(
    on: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (on) accent else Ink.Secondary
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (on) accent.copy(alpha = 0.14f) else Ink.Surface)
            .border(1.dp, if (on) accent.copy(alpha = 0.5f) else Ink.Outline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Canvas(Modifier.fillMaxSize()) { powerSymbol(tint, strokePx) }
    }
}

@Composable
private fun SyncingCard() {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink.Secondary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Waiting for the first status frame…",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Secondary,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Ink.Surface)
            .border(1.dp, Ink.Outline, RoundedCornerShape(24.dp)),
    ) { content() }
}

@Composable
private fun ClimateCard(
    state: AiceState,
    accent: Color,
    onTemp: (Int) -> Unit,
    onWindPreview: (Int) -> Unit,
    onWindCommit: () -> Unit,
    onPause: () -> Unit,
) {
    val unit = state.unit
    val canAdjust = state.isRunning && state.supportsTargetTemp

    Card {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton(Glyph.Minus, "Lower temperature", canAdjust) { onTemp(-1) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (state.supportsTargetTemp) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                "${unit.format(state.targetTempC)}",
                                style = MaterialTheme.typography.displayLarge,
                                color = if (canAdjust) Ink.Primary else Ink.Tertiary,
                            )
                            Text(
                                unit.suffix,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.Secondary,
                                modifier = Modifier.padding(top = 18.dp, start = 4.dp),
                            )
                        }
                    } else {
                        Text(
                            "Fan only",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink.Secondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                StepButton(Glyph.Plus, "Raise temperature", canAdjust) { onTemp(+1) }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Ambient Temperature  ${unit.format(state.ambientTempC)}${unit.suffix}",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Secondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WindBar(
                    level = state.windLevel,
                    accent = accent,
                    enabled = state.isPowered && state.supportsWind,
                    onPreview = onWindPreview,
                    onCommit = onWindCommit,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(14.dp))
                PausePlayButton(paused = state.isPaused, accent = accent, enabled = state.isPowered, onClick = onPause)
            }
        }
    }
}

@Composable
private fun PausePlayButton(paused: Boolean, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) accent else Ink.SurfaceElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(
            if (paused) Glyph.Play else Glyph.Pause,
            if (enabled) Color.Black.copy(alpha = 0.85f) else Ink.Tertiary,
            size = 22.dp,
        )
    }
}

@Composable
private fun StepButton(glyph: Glyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(1.dp, if (enabled) Ink.Secondary.copy(alpha = 0.5f) else Ink.Outline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = label),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (enabled) Ink.Primary else Ink.Tertiary, size = 20.dp)
    }
}

/**
 * Wind level, dragged directly. A Slider would work, but the fill *is* the
 * label here — the bar reads as a single object rather than a track and a knob.
 */
@Composable
private fun WindBar(
    level: Int,
    accent: Color,
    enabled: Boolean,
    onPreview: (Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val height = 56.dp
    val corner = with(density) { (height / 2).toPx() }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Ink.SurfaceElevated)
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onPreview(((offset.x / size.width) * WIND_MAX).roundToInt().coerceIn(0, WIND_MAX))
                    onCommit()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = onCommit,
                    onDragCancel = onCommit,
                ) { change, _ ->
                    onPreview(((change.position.x / size.width) * WIND_MAX).roundToInt().coerceIn(0, WIND_MAX))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val fill = size.width * (level.coerceIn(0, WIND_MAX) / WIND_MAX.toFloat())
            if (fill > 0f) {
                drawRoundRect(
                    color = accent,
                    size = Size(fill.coerceAtLeast(corner * 2), size.height),
                    cornerRadius = CornerRadius(corner, corner),
                )
            }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Fan Speed", style = MaterialTheme.typography.titleMedium, color = Ink.Primary)
            Spacer(Modifier.weight(1f))
            Text("$level", style = MaterialTheme.typography.titleMedium, color = Ink.Primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModeCard(
    state: AiceState,
    selectedOption: ModeOption?,
    accent: Color,
    onMode: (Mode) -> Unit,
    onOption: (ModeOption) -> Unit,
) {
    // Mode and option switches are refused only while the device is off.
    val active = !state.isOff

    Card {
        Column(Modifier.padding(vertical = 22.dp, horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                modeGlyphs.forEach { (mode, glyph) ->
                    PickerCell(
                        glyph = glyph,
                        label = mode.label,
                        selected = state.mode == mode,
                        enabled = active,
                        accent = mode.accent,
                    ) { onMode(mode) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                optionGlyphs.forEach { (option, glyph) ->
                    PickerCell(
                        glyph = glyph,
                        label = option.label,
                        selected = selectedOption == option,
                        enabled = active,
                        accent = accent,
                    ) { onOption(option) }
                }
            }
        }
    }
}

private val modeGlyphs = listOf(
    Mode.COOLING to Glyph.Cooling,
    Mode.HEATING to Glyph.Heating,
    Mode.FAN to Glyph.Wind,
    Mode.AI to Glyph.Ai,
)

private val optionGlyphs = listOf(
    ModeOption.SILENT to Glyph.Silent,
    ModeOption.HOT_PACK to Glyph.HotPack,
    ModeOption.COOLING_FIRST to Glyph.CoolingFirst,
    ModeOption.LOW_POWER to Glyph.LowPower,
)

@Composable
private fun PickerCell(
    glyph: Glyph,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) accent else Ink.SurfaceElevated,
        tween(240),
        label = "cellBg",
    )
    val tint = when {
        selected -> Color.Black.copy(alpha = 0.85f)
        enabled -> Ink.Secondary
        else -> Ink.Tertiary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp)) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable(enabled = enabled, onClick = onClick)
                .alpha(if (enabled) 1f else 0.45f),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, tint, size = 26.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Ink.Primary else Ink.Tertiary,
            textAlign = TextAlign.Center,
            fontSize = 11.sp,
        )
    }
}
