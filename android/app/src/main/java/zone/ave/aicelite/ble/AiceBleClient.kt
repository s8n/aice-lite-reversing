package zone.ave.aicelite.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import zone.ave.aicelite.protocol.AiceCodec
import zone.ave.aicelite.protocol.AiceState
import zone.ave.aicelite.protocol.toHex
import java.util.UUID

private const val TAG = "AiceBle"

/** GATT map, from the live capture (PROTOCOL.md §2). */
object Uuids {
    val SERVICE: UUID = uuid16("ff00")
    val CHAR_WRITE: UUID = uuid16("ff04")
    val CHAR_NOTIFY: UUID = uuid16("ff03")
    val CCCD: UUID = uuid16("2902")

    val DEVICE_INFO: UUID = uuid16("180a")
    val FIRMWARE_REV: UUID = uuid16("2a26")

    fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}

const val DEVICE_NAME_PREFIX = "LH-"

sealed interface Conn {
    data object Idle : Conn
    data object Scanning : Conn
    data class Connecting(val name: String) : Conn
    data class Ready(val name: String, val address: String) : Conn
    data class Failed(val reason: String) : Conn
}

data class ScannedDevice(val address: String, val name: String, val rssi: Int)

enum class Dir { TX, RX, INFO }

data class LogLine(val dir: Dir, val text: String, val atMillis: Long)

/**
 * Drives an AICE Lite over a bare, unencrypted GATT link.
 *
 * Two things here are load-bearing and easy to break:
 *  - Control writes **must** be ATT Write Commands (`WRITE_TYPE_NO_RESPONSE`).
 *    `ff04` only advertises the `write` property, but Android emits a Write
 *    Command anyway because it doesn't gate the write type on the property.
 *    A Write Request gets ACKed and then ignored by the firmware.
 *  - GATT operations must not overlap. Android silently drops a write issued
 *    while another is in flight, so everything goes through [gattOp].
 */
@SuppressLint("MissingPermission")
class AiceBleClient(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob())

    private val manager get() = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private val _connection = MutableStateFlow<Conn>(Conn.Idle)
    val connection: StateFlow<Conn> = _connection.asStateFlow()

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private val _state = MutableStateFlow<AiceState?>(null)
    val state: StateFlow<AiceState?> = _state.asStateFlow()

    private val _firmware = MutableStateFlow<String?>(null)
    val firmware: StateFlow<String?> = _firmware.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _mtu = MutableStateFlow(23)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    private var gatt: BluetoothGatt? = null
    private var controlService: BluetoothGattService? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val writeChar: BluetoothGattCharacteristic?
        get() = controlService?.getCharacteristic(Uuids.CHAR_WRITE)

    // ---- one GATT operation at a time -------------------------------------

    private val opMutex = Mutex()
    private var pending: CompletableDeferred<Boolean>? = null
    private var pendingOp: Op? = null
    private var pendingRead: CompletableDeferred<ByteArray?>? = null

    private enum class Op { WRITE, DESCRIPTOR, MTU }

    private suspend fun gattOp(op: Op, timeoutMs: Long = 5_000, start: () -> Boolean): Boolean =
        opMutex.withLock {
            val done = CompletableDeferred<Boolean>()
            pending = done
            pendingOp = op
            if (!start()) {
                pending = null
                pendingOp = null
                return@withLock false
            }
            val ok = withTimeoutOrNull(timeoutMs) { done.await() }
            if (ok == null) addLog(Dir.INFO, "$op timed out after ${timeoutMs}ms")
            pending = null
            pendingOp = null
            ok ?: false
        }

    /**
     * The device can start an MTU exchange on its own, so a callback that
     * doesn't match the operation we're waiting on must not complete it.
     */
    private fun completeOp(op: Op, success: Boolean) {
        if (pendingOp != op) return
        pending?.complete(success)
    }

    // ---- scanning ----------------------------------------------------------

    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.startsWith(DEVICE_NAME_PREFIX)) return
            val found = ScannedDevice(result.device.address, name, result.rssi)
            _devices.update { list ->
                (list.filterNot { it.address == found.address } + found).sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _connection.value = Conn.Failed("Scan failed (code $errorCode)")
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _connection.value = Conn.Failed("Bluetooth is off")
            return
        }
        if (scanning) return
        _devices.value = emptyList()
        _connection.value = Conn.Scanning
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No ScanFilter: the device advertises its name in the scan response on
        // some firmware revisions, so filter on the prefix ourselves.
        scanner.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_connection.value is Conn.Scanning) _connection.value = Conn.Idle
    }

    // ---- connect / disconnect ---------------------------------------------

    fun connect(device: ScannedDevice) {
        stopScan()
        val remote: BluetoothDevice = adapter?.getRemoteDevice(device.address) ?: return
        _connection.value = Conn.Connecting(device.name)
        _state.value = null
        _mtu.value = 23
        gatt = remote.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        controlService = null
        notifyChar = null
        _state.value = null
        _firmware.value = null
        _connection.value = Conn.Idle
    }

    // ---- writes ------------------------------------------------------------

    /** Frame a payload per §3 and write it to `ff04` as a Write Command. */
    suspend fun sendPayload(payload: ByteArray): Boolean = sendFrame(AiceCodec.encode(payload))

    /** Write an already-framed buffer verbatim. Used by the developer panel. */
    suspend fun sendFrame(frame: ByteArray): Boolean {
        val char = writeChar ?: run {
            addLog(Dir.INFO, "write ignored: not connected")
            return false
        }
        // A frame longer than (MTU - 3) is silently truncated by the stack.
        if (frame.size > _mtu.value - 3) {
            val ok = requestMtu(frame.size + 3 + 8)
            if (!ok || frame.size > _mtu.value - 3) {
                addLog(Dir.INFO, "write refused: ${frame.size}B frame needs MTU ≥ ${frame.size + 3}, have ${_mtu.value}")
                return false
            }
        }
        addLog(Dir.TX, frame.toHex())
        val ok = gattOp(Op.WRITE) {
            writeNoResponse(char, frame)
        }
        if (!ok) addLog(Dir.INFO, "write failed")
        return ok
    }

    /**
     * Emit an ATT Write Command (0x52), the only thing `ff04`'s firmware acts on.
     * `ff04` advertises `write` but not write-no-response; both Android write APIs
     * still emit 0x52 here (confirmed on hardware). Uses the modern API on 33+ and
     * the legacy path below it.
     */
    private fun writeNoResponse(char: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val g = gatt ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = g.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (code != BluetoothStatusCodes.SUCCESS) {
                addLog(Dir.INFO, "writeCharacteristic → ${writeStatusName(code)}")
                return false
            }
            return true
        }

        @Suppress("DEPRECATION")
        return run {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            char.value = value
            val ok = g.writeCharacteristic(char)
            if (!ok) addLog(Dir.INFO, "legacy writeCharacteristic returned false")
            ok
        }
    }

    private fun writeStatusName(code: Int): String = when (code) {
        BluetoothStatusCodes.SUCCESS -> "SUCCESS"
        BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED -> "BLUETOOTH_NOT_ENABLED"
        BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED -> "BLUETOOTH_NOT_ALLOWED"
        BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED -> "DEVICE_NOT_BONDED"
        BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED -> "GATT_WRITE_NOT_ALLOWED"
        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> "GATT_WRITE_REQUEST_BUSY"
        BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> "MISSING_CONNECT_PERMISSION"
        BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> "PROFILE_SERVICE_NOT_BOUND"
        else -> "code $code"
    }

    /** Needed only for the 36-byte light/voice config frame (§5c). */
    suspend fun requestMtu(bytes: Int): Boolean {
        val g = gatt ?: return false
        val target = bytes.coerceIn(23, 517)
        if (_mtu.value >= target) return true
        addLog(Dir.INFO, "requesting MTU $target")
        return gattOp(Op.MTU) { g.requestMtu(target) }
    }

    private suspend fun readCharacteristic(service: UUID, characteristic: UUID): ByteArray? {
        val g = gatt ?: return null
        val char = g.getService(service)?.getCharacteristic(characteristic) ?: return null
        return opMutex.withLock {
            val done = CompletableDeferred<ByteArray?>()
            pendingRead = done
            if (!g.readCharacteristic(char)) {
                pendingRead = null
                return@withLock null
            }
            val value = withTimeoutOrNull(5_000) { done.await() }
            pendingRead = null
            value
        }
    }

    private suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val char = notifyChar ?: return false
        if (!g.setCharacteristicNotification(char, true)) return false
        val cccd = char.getDescriptor(Uuids.CCCD) ?: return false
        return gattOp(Op.DESCRIPTOR) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }
    }

    // ---- callbacks ---------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    addLog(Dir.INFO, "connected, discovering services")
                    g.discoverServices()
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    pendingOp?.let { completeOp(it, false) }
                    pendingRead?.complete(null)
                    runCatching { g.close() }
                    gatt = null
                    controlService = null
                    notifyChar = null
                    _state.value = null
                    val was = _connection.value
                    _connection.value = when {
                        status != BluetoothGatt.GATT_SUCCESS -> Conn.Failed("Disconnected (status $status)")
                        was is Conn.Connecting -> Conn.Failed("Could not connect")
                        else -> Conn.Idle
                    }
                    addLog(Dir.INFO, "disconnected (status $status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(Uuids.SERVICE)
            if (service == null) {
                _connection.value = Conn.Failed("Control service ff00 not found")
                addLog(Dir.INFO, "service ff00 missing — different hardware revision?")
                return
            }
            controlService = service
            notifyChar = service.getCharacteristic(Uuids.CHAR_NOTIFY)
            if (writeChar == null || notifyChar == null) {
                _connection.value = Conn.Failed("Characteristics ff03/ff04 not found")
                return
            }
            val name = g.device.name ?: DEVICE_NAME_PREFIX
            _connection.value = Conn.Ready(name, g.device.address)

            // PROTOCOL.md §2 infers "handle 0x0026 = ff04" from GATT adjacency;
            // the HCI snoop only ever proved the official app writes to 0x0026.
            // Android hands us the real attribute handles, so print them and
            // stop guessing: whichever characteristic reports handle 0x0026 is
            // the one the official app drives.
            service.characteristics.forEach { c ->
                addLog(
                    Dir.INFO,
                    "char %s handle=0x%04X props=%s".format(
                        c.uuid.short(), c.instanceId, describeProps(c.properties),
                    ),
                )
            }
            addLog(Dir.INFO, "bond state=${bondStateName(g.device.bondState)}")

            scope.launch {
                if (!enableNotifications()) addLog(Dir.INFO, "failed to enable notifications on ff03")
                readCharacteristic(Uuids.DEVICE_INFO, Uuids.FIRMWARE_REV)?.let {
                    _firmware.value = String(it).trim()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _mtu.value = mtu
            addLog(Dir.INFO, "MTU is now $mtu")
            completeOp(Op.MTU, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) addLog(Dir.INFO, "write status=$status")
            completeOp(Op.WRITE, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            addLog(Dir.INFO, "notifications ${if (status == BluetoothGatt.GATT_SUCCESS) "enabled" else "FAILED status=$status"}")
            completeOp(Op.DESCRIPTOR, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onNotify(char, value)

        @Deprecated("Pre-Tiramisu callback", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            onNotify(char, char.value ?: return)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Pre-Tiramisu callback", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) char.value else null)
        }
    }

    private fun onNotify(char: BluetoothGattCharacteristic, value: ByteArray) {
        if (char.uuid != Uuids.CHAR_NOTIFY) return
        addLog(Dir.RX, value.toHex())
        val parsed = AiceState.fromFrame(value)
        if (parsed == null) {
            Log.w(TAG, "unparseable notification ${value.toHex()}")
            return
        }
        if (!AiceCodec.crcValid(value)) addLog(Dir.INFO, "CRC mismatch on RX frame")
        _state.value = parsed
    }

    // ---- log ---------------------------------------------------------------

    private fun addLog(dir: Dir, text: String) {
        val line = LogLine(dir, text, SystemClock.elapsedRealtime())
        _log.update { (it + line).takeLast(200) }
        Log.i(TAG, "$dir $text")   // mirrored so `adb logcat -s AiceBle` sees everything
    }

    fun clearLog() {
        _log.value = emptyList()
    }
}

private fun UUID.short(): String = toString().substring(4, 8)

private fun describeProps(props: Int): String {
    val names = buildList {
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-no-response")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }
    return "0x%02X [%s]".format(props, names.joinToString(","))
}

private fun bondStateName(state: Int): String = when (state) {
    BluetoothDevice.BOND_NONE -> "none"
    BluetoothDevice.BOND_BONDING -> "bonding"
    BluetoothDevice.BOND_BONDED -> "bonded"
    else -> "unknown($state)"
}
