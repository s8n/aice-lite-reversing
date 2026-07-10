package zone.ave.aicelite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import zone.ave.aicelite.protocol.AiceState
import zone.ave.aicelite.protocol.TempUnit
import zone.ave.aicelite.ui.theme.Ink

private const val TAPS_TO_UNLOCK_DEVELOPER = 5

@Composable
fun SettingsScreen(
    deviceName: String,
    firmware: String?,
    state: AiceState?,
    onBack: () -> Unit,
    onVoice: (Boolean) -> Unit,
    onLight: (Boolean) -> Unit,
    onUnit: (TempUnit) -> Unit,
    onDisconnect: () -> Unit,
    onOpenDeveloper: () -> Unit,
) {
    var taps by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Back, Ink.Secondary, size = 22.dp)
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = Ink.Primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Section {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Device Name", style = MaterialTheme.typography.titleMedium, color = Ink.Primary)
                Spacer(Modifier.weight(1f))
                Text(deviceName, style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary)
            }
            Divider()
            ToggleRow(
                label = "Sound",
                checked = state?.voiceOn == true,
                enabled = state != null,
                onCheckedChange = onVoice,
            )
            Divider()
            ToggleRow(
                label = "Light",
                checked = state?.lightOn == true,
                enabled = state != null,
                onCheckedChange = onLight,
            )
        }

        Spacer(Modifier.height(24.dp))

        Section {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Unit", style = MaterialTheme.typography.titleMedium, color = Ink.Primary)
                Spacer(Modifier.weight(1f))
                UnitPicker(
                    selected = state?.unit ?: TempUnit.CELSIUS,
                    enabled = state != null,
                    onSelect = onUnit,
                )
            }
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        taps++
                        if (taps >= TAPS_TO_UNLOCK_DEVELOPER) {
                            taps = 0
                            onOpenDeveloper()
                        }
                    }
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Firmware Update", style = MaterialTheme.typography.titleMedium, color = Ink.Primary)
                    Text(
                        "OTA is not implemented",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Tertiary,
                    )
                }
                Text(
                    firmware ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Secondary,
                    modifier = Modifier.padding(end = 10.dp),
                )
                GlyphIcon(Glyph.ChevronRight, Ink.Tertiary, size = 18.dp)
            }
        }

        Spacer(Modifier.height(24.dp))

        Section {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenDeveloper).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Debug options", style = MaterialTheme.typography.titleMedium, color = Ink.Primary)
                Spacer(Modifier.weight(1f))
                GlyphIcon(Glyph.ChevronRight, Ink.Tertiary, size = 18.dp)
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onDisconnect).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Disconnect", style = MaterialTheme.typography.titleMedium, color = Ink.Danger)
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Ink.Surface),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Outline))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Ink.Primary)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Ink.SurfaceElevated,
                uncheckedBorderColor = Ink.Outline,
            ),
        )
    }
}


@Composable
private fun UnitPicker(selected: TempUnit, enabled: Boolean, onSelect: (TempUnit) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Ink.SurfaceElevated)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TempUnit.entries.forEach { unit ->
            val active = unit == selected
            Box(
                Modifier
                    .width(52.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(enabled = enabled && !active) { onSelect(unit) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    unit.suffix,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) Color.Black else Ink.Secondary,
                )
            }
        }
    }
}
