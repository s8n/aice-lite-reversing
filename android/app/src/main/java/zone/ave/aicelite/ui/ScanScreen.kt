package zone.ave.aicelite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zone.ave.aicelite.ble.Conn
import zone.ave.aicelite.ble.DEVICE_NAME_PREFIX
import zone.ave.aicelite.ble.ScannedDevice
import zone.ave.aicelite.ui.theme.Ink

@Composable
fun ScanScreen(
    connection: Conn,
    devices: List<ScannedDevice>,
    bluetoothOn: Boolean,
    onScan: () -> Unit,
    onConnect: (ScannedDevice) -> Unit,
) {
    val scanning = connection is Conn.Scanning

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Background)
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text("AICE LITE", style = MaterialTheme.typography.titleLarge, color = Ink.Primary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Looking for a device advertising as “$DEVICE_NAME_PREFIX…”",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.Secondary,
        )

        Spacer(Modifier.height(32.dp))
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            NeckRing(
                accent = MaterialTheme.colorScheme.primary,
                running = scanning,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(24.dp))

        when (connection) {
            is Conn.Connecting -> StatusLine("Connecting to ${connection.name}…", busy = true)
            is Conn.Failed -> StatusLine(connection.reason, busy = false, error = true)
            is Conn.Scanning -> StatusLine("Scanning…", busy = true)
            else -> if (!bluetoothOn) StatusLine("Bluetooth is off", busy = false, error = true) else Spacer(Modifier.height(24.dp))
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(devices, key = { it.address }) { device ->
                DeviceRow(device) { onConnect(device) }
            }
        }

        Button(
            onClick = onScan,
            enabled = bluetoothOn && !scanning,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = Ink.Surface,
                disabledContentColor = Ink.Tertiary,
            ),
        ) {
            Text(if (scanning) "Scanning…" else "Scan for devices")
        }
    }
}

@Composable
private fun StatusLine(text: String, busy: Boolean, error: Boolean = false) {
    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Ink.Secondary)
            Spacer(Modifier.size(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) Ink.Danger else Ink.Secondary,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Ink.Surface)
            .border(1.dp, Ink.Outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium, color = Ink.Primary)
            Text(device.address, style = MaterialTheme.typography.bodySmall, color = Ink.Tertiary)
        }
        Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
        Spacer(Modifier.size(10.dp))
        GlyphIcon(Glyph.ChevronRight, Ink.Tertiary, size = 18.dp)
    }
}
