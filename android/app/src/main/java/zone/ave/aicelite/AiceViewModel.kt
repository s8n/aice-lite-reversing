package zone.ave.aicelite

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import zone.ave.aicelite.ble.AiceBleClient
import zone.ave.aicelite.ble.Conn
import zone.ave.aicelite.ble.ScannedDevice
import zone.ave.aicelite.protocol.AiceCodec
import zone.ave.aicelite.protocol.AiceState
import zone.ave.aicelite.protocol.Commands
import zone.ave.aicelite.protocol.Idx
import zone.ave.aicelite.protocol.Mode
import zone.ave.aicelite.protocol.ModeOption
import zone.ave.aicelite.protocol.TempUnit

/**
 * Holds the command buffer. Every command is "take the last known state, edit
 * one byte, resend the whole thing" — so the buffer must be seeded from the
 * device's first notification and never synthesized from scratch (§5).
 */
class AiceViewModel(app: Application) : AndroidViewModel(app) {

    val client = AiceBleClient(app)

    private val _state = MutableStateFlow<AiceState?>(null)

    /** The buffer the UI renders: device state, overlaid with in-flight local edits. */
    val state: StateFlow<AiceState?> = _state.asStateFlow()

    /** Which mode option is lit — read straight from the state byte (each option has a distinct id). */
    val selectedOption: ModeOption? get() = _state.value?.activeOption

    /** Writes are serialized; the GATT stack drops overlapping ones. */
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var windJob: Job? = null
    private var windSettleJob: Job? = null

    /** True while the wind slider is being worked; keeps its echo from snapping the slider back. */
    private var windDragging = false

    // ---- auto-connect ------------------------------------------------------

    /** On until the user explicitly disconnects; drives scan → connect → retry. */
    private var autoConnect = true
    private var permissionsReady = false
    private var backoffMs = MIN_BACKOFF_MS

    init {
        viewModelScope.launch {
            client.state.collect { fromDevice -> mergeFromDevice(fromDevice) }
        }
        viewModelScope.launch {
            for (payload in outbox) client.sendPayload(payload)
        }
        // Keep a connection alive: scan while idle/failed (with backoff), and adopt
        // the first device found. collectLatest cancels a pending backoff the moment
        // the state changes.
        viewModelScope.launch {
            client.connection.collectLatest { conn ->
                if (!autoConnect || !permissionsReady) return@collectLatest
                when (conn) {
                    is Conn.Ready -> backoffMs = MIN_BACKOFF_MS   // reset on success
                    is Conn.Failed -> {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        if (client.isBluetoothOn) client.startScan()
                    }
                    is Conn.Idle -> if (client.isBluetoothOn) client.startScan()
                    else -> {}   // Scanning / Connecting — already in progress
                }
            }
        }
        viewModelScope.launch {
            client.devices.collectLatest { list ->
                if (autoConnect && permissionsReady && client.connection.value is Conn.Scanning) {
                    list.firstOrNull()?.let { client.connect(it) }
                }
            }
        }
    }

    /** Called once Bluetooth permissions are granted — kicks off the first scan. */
    fun onPermissionsReady() {
        permissionsReady = true
        if (autoConnect && client.connection.value is Conn.Idle && client.isBluetoothOn) {
            client.startScan()
        }
    }

    private fun mergeFromDevice(device: AiceState?) {
        if (device == null) {
            _state.value = null
            return
        }
        val local = _state.value
        // The device is authoritative — it may override what we sent (PID mode picks
        // its own target, sets its own wind). Adopt its echo. The one exception is the
        // wind level while the user is actively dragging: a lagging echo would yank the
        // slider back under their finger, so keep the local value until the drag settles.
        _state.value = when {
            local == null -> device
            windDragging -> device.withByte(Idx.WIND_LEVEL, local.windLevel)
            else -> device
        }
    }

    /** Apply a command locally (so the UI responds now) and queue the frame. */
    private fun send(next: AiceState?) {
        if (next == null) return
        _state.value = next
        enqueue(next)
    }

    /**
     * The single point where a buffer becomes a wire frame.
     *
     * The official app writes `payload[0..3] = 0xFF` on every command — captured
     * live from its own `data:` log, e.g. `FF FF FF FF 00 01 02 01 05 22 …`. The
     * device replies with `02 00 00 00 …` (the §4 echoes), so command and status
     * frames are distinguished by this 4-byte header. Mirroring the device's
     * `01/02 00 00 00` back is silently ignored; the `0xFF` header is what makes
     * the firmware act. This is `_writeDataToDevice`'s `field_7b` branch.
     */
    private fun enqueue(next: AiceState) {
        val raw = next.raw.copyOf()
        raw[0] = 0xFF.toByte()
        raw[1] = 0xFF.toByte()
        raw[2] = 0xFF.toByte()
        raw[3] = 0xFF.toByte()
        outbox.trySend(raw)
    }

    // ---- connection --------------------------------------------------------

    fun startScan() {
        autoConnect = true
        backoffMs = MIN_BACKOFF_MS
        client.startScan()
    }

    fun stopScan() = client.stopScan()

    fun connect(device: ScannedDevice) {
        autoConnect = true
        backoffMs = MIN_BACKOFF_MS
        client.connect(device)
    }

    /** User-initiated: stop auto-reconnecting until they scan again. */
    fun disconnect() {
        autoConnect = false
        client.disconnect()
    }

    // ---- commands ----------------------------------------------------------

    /** Top-right button: off when powered, on when off. */
    fun togglePower() {
        val s = _state.value ?: return
        send(if (s.isPowered) Commands.powerOff(s) else Commands.powerOn(s))
    }

    /** Pause/resume airflow without powering off. */
    fun togglePause() {
        val s = _state.value ?: return
        send(if (s.isRunning) Commands.pause(s) else Commands.resume(s))
    }

    fun setMode(mode: Mode) {
        val s = _state.value ?: return
        send(Commands.setMode(s, mode))
    }

    fun adjustTemp(delta: Int) {
        val s = _state.value ?: return
        send(Commands.adjustTemp(s, delta))
    }

    /** Drag: update the UI every frame, but rate-limit the radio. */
    fun previewWind(level: Int) {
        val s = _state.value ?: return
        val next = Commands.setWind(s, level)
        if (next == s) return
        windDragging = true
        windSettleJob?.cancel()
        _state.value = next
        windJob?.cancel()
        windJob = viewModelScope.launch {
            delay(120)
            enqueue(next)
        }
    }

    /** Finger lifted: send the final value, then let echoes take over again after they catch up. */
    fun commitWind() {
        windJob?.cancel()
        val s = _state.value ?: return
        enqueue(s)
        windSettleJob?.cancel()
        windSettleJob = viewModelScope.launch {
            delay(500)
            windDragging = false
        }
    }

    /**
     * The four options are mutually exclusive — they share `payload[14]`, so
     * selecting one clears any other. Tapping the active option clears it (none).
     */
    fun toggleModeOption(option: ModeOption) {
        val s = _state.value ?: return
        val turningOff = s.activeOption == option
        send(Commands.setModeOption(s, option, on = !turningOff))
    }

    fun setUnit(unit: TempUnit) {
        val s = _state.value ?: return
        send(Commands.setUnit(s, unit))
    }

    /** Light + Sound ride the ordinary state frame at `payload[9]` (confirmed live). */
    fun setLight(on: Boolean) {
        val s = _state.value ?: return
        send(Commands.setLight(s, on))
    }

    fun setVoice(on: Boolean) {
        val s = _state.value ?: return
        send(Commands.setVoice(s, on))
    }

    // ---- developer panel ---------------------------------------------------

    fun sendRawFrame(frame: ByteArray) {
        viewModelScope.launch { client.sendFrame(frame) }
    }

    fun sendRawPayload(payload: ByteArray) {
        viewModelScope.launch { client.sendPayload(payload) }
    }

    fun requestMtu(bytes: Int) {
        viewModelScope.launch { client.requestMtu(bytes) }
    }


    fun clearLog() = client.clearLog()

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }

    companion object {
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 8_000L
    }
}
