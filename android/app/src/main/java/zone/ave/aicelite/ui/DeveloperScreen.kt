package zone.ave.aicelite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zone.ave.aicelite.ble.Dir
import zone.ave.aicelite.ble.LogLine
import zone.ave.aicelite.protocol.AiceCodec
import zone.ave.aicelite.protocol.AiceState
import zone.ave.aicelite.protocol.parseHex
import zone.ave.aicelite.protocol.toHex
import zone.ave.aicelite.ui.theme.Ink

/**
 * Reached by tapping the firmware version five times. This is the tool for
 * closing out PROTOCOL.md §9 — watch bytes 1–5, 7 and 8 while the battery
 * drains, or poke a byte and see what the device does.
 */
@Composable
fun DeveloperScreen(
    state: AiceState?,
    log: List<LogLine>,
    mtu: Int,
    onSendPayload: (ByteArray) -> Unit,
    onSendFrame: (ByteArray) -> Unit,
    onRequestMtu: () -> Unit,
    onClearLog: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Background),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Back, Ink.Secondary, size = 22.dp)
            }
            Text("Debug options", style = MaterialTheme.typography.titleLarge, color = Ink.Primary)
            Spacer(Modifier.weight(1f))
            Text("MTU $mtu", style = MaterialTheme.typography.bodySmall, color = Ink.Tertiary)
            Spacer(Modifier.width(12.dp))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                StateInspector(state)
                Spacer(Modifier.height(14.dp))
                Injector(state, onSendPayload, onSendFrame)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRequestMtu) { Text("Request MTU 64") }
                    TextButton(onClick = onClearLog) { Text("Clear log") }
                }
            }

            Text(
                "Traffic (newest first)",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Tertiary,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Ink.Surface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (log.isEmpty()) {
                    Text("—", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Ink.Tertiary)
                } else {
                    log.asReversed().forEach { line -> LogRow(line) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The named bytes from §5, plus the ones still up for grabs. */
private val byteNames = mapOf(
    0 to "state",
    1 to "?",
    2 to "?",
    3 to "?",
    4 to "?",
    5 to "?",
    6 to "power",
    7 to "charge",
    8 to "battery",
    9 to "flags",
    10 to "mode",
    11 to "ambient",
    12 to "target",
    13 to "wind",
    14 to "option",
    15 to "unit",
)

@Composable
private fun StateInspector(state: AiceState?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Surface)
            .padding(14.dp),
    ) {
        Text("State buffer", style = MaterialTheme.typography.bodySmall, color = Ink.Tertiary)
        Spacer(Modifier.height(8.dp))
        if (state == null) {
            Text("No frame received yet", style = MaterialTheme.typography.bodyMedium, color = Ink.Tertiary)
            return@Column
        }
        // 16 bytes, 4 per row: index, hex, decimal, name.
        state.raw.toList().chunked(4).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                chunk.forEachIndexed { col, byte ->
                    val idx = row * 4 + col
                    val unsigned = byte.toInt() and 0xFF
                    Column(Modifier.weight(1f)) {
                        Text(
                            "[$idx] ${byteNames[idx]}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.Tertiary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "%02X".format(unsigned) + "  $unsigned",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Ink.Primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Injector(state: AiceState?, onSendPayload: (ByteArray) -> Unit, onSendFrame: (ByteArray) -> Unit) {
    var text by remember(state == null) {
        mutableStateOf(state?.raw?.toHex() ?: "")
    }
    val bytes = parseHex(text)
    val isPayload = bytes?.size == AiceCodec.STATE_PAYLOAD_SIZE

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Surface)
            .padding(14.dp),
    ) {
        Text("Inject", style = MaterialTheme.typography.bodySmall, color = Ink.Tertiary)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text("02 00 00 …", fontFamily = FontFamily.Monospace) },
            singleLine = false,
            isError = text.isNotBlank() && bytes == null,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                bytes == null -> "Not valid hex"
                isPayload -> "${bytes.size}-byte payload — will be CRC-wrapped before sending"
                else -> "${bytes.size} bytes — send raw only (payload must be 16 bytes)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (bytes == null) Ink.Danger else Ink.Tertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { bytes?.let(onSendPayload) },
                enabled = isPayload,
            ) { Text("Send as payload") }
            TextButton(
                onClick = { bytes?.let(onSendFrame) },
                enabled = bytes != null,
            ) { Text("Send raw frame") }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.dir) {
        Dir.TX -> Color(0xFF7FD4FF)
        Dir.RX -> Color(0xFF9BE39B)
        Dir.INFO -> Ink.Tertiary
    }
    Row {
        Text(
            line.dir.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.width(34.dp),
        )
        Text(
            line.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (line.dir == Dir.INFO) Ink.Tertiary else Ink.Secondary,
        )
    }
}
