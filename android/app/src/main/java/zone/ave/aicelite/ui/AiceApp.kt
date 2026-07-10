package zone.ave.aicelite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import zone.ave.aicelite.AiceViewModel
import zone.ave.aicelite.ble.Conn
import zone.ave.aicelite.ui.theme.Ink

private enum class Screen { Device, Settings, Developer }

@Composable
fun AiceApp(hasPermissions: Boolean, onRequestPermissions: () -> Unit) {
    val vm: AiceViewModel = viewModel()
    val connection by vm.client.connection.collectAsStateWithLifecycle()
    val devices by vm.client.devices.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val firmware by vm.client.firmware.collectAsStateWithLifecycle()
    val log by vm.client.log.collectAsStateWithLifecycle()
    val mtu by vm.client.mtu.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(Screen.Device) }

    // A disconnect anywhere drops you back to the device screen, which shows
    // the scanner. Settings for a device that isn't there is a dead end.
    val ready = connection is Conn.Ready
    LaunchedEffect(ready) {
        if (!ready) screen = Screen.Device
    }

    // Auto-scan and connect as soon as we have permission.
    LaunchedEffect(hasPermissions) {
        if (hasPermissions) vm.onPermissionsReady()
    }

    BackHandler(enabled = screen != Screen.Device) {
        screen = if (screen == Screen.Developer) Screen.Settings else Screen.Device
    }

    // enableEdgeToEdge() draws behind the system bars; without this the header
    // sits underneath the status bar and the gear icon can't be tapped.
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when {
            !hasPermissions -> PermissionScreen(onRequestPermissions)

            connection !is Conn.Ready -> ScanScreen(
                connection = connection,
                devices = devices,
                bluetoothOn = vm.client.isBluetoothOn,
                onScan = vm::startScan,
                onConnect = vm::connect,
            )

            screen == Screen.Device -> DeviceScreen(
                deviceName = (connection as Conn.Ready).name,
                state = state,
                selectedOption = vm.selectedOption,
                onPower = vm::togglePower,
                onPause = vm::togglePause,
                onTemp = vm::adjustTemp,
                onWindPreview = vm::previewWind,
                onWindCommit = vm::commitWind,
                onMode = vm::setMode,
                onOption = vm::toggleModeOption,
                onSettings = { screen = Screen.Settings },
            )

            screen == Screen.Settings -> SettingsScreen(
                deviceName = (connection as Conn.Ready).name,
                firmware = firmware,
                state = state,
                onBack = { screen = Screen.Device },
                onVoice = vm::setVoice,
                onLight = vm::setLight,
                onUnit = vm::setUnit,
                onDisconnect = vm::disconnect,
                onOpenDeveloper = { screen = Screen.Developer },
            )

            screen == Screen.Developer -> DeveloperScreen(
                state = state,
                log = log,
                mtu = mtu,
                onSendPayload = vm::sendRawPayload,
                onSendFrame = vm::sendRawFrame,
                onRequestMtu = { vm.requestMtu(64) },
                onClearLog = vm::clearLog,
                onBack = { screen = Screen.Settings },
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PIDCE LITE", style = MaterialTheme.typography.titleLarge, color = Ink.Primary)
        Spacer(Modifier.height(16.dp))
        Text(
            "This app talks to the neck cooler over Bluetooth Low Energy, so Android needs " +
                "you to grant nearby-device access before it can scan.",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.Secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRequest,
            shape = RoundedCornerShape(50),
            modifier = Modifier.height(52.dp),
        ) {
            Text("Grant Bluetooth permission")
        }
    }
}
