import { AiceState } from '../protocol/state.ts'
import { encode as encodeFrame } from '../protocol/codec.ts'
import { AckGate } from './ack-gate.ts'

const SERVICE_UUID = 0xff00
const CHAR_CONTROL_UUID = 0xff04
const CHAR_NOTIFY_UUID = 0xff03
const DIS_SERVICE_UUID = 0x180a
const DIS_FIRMWARE_UUID = 0x2a26

const MIN_BACKOFF_MS = 1_000
const MAX_BACKOFF_MS = 8_000

/**
 * How long to wait for the device's echo before giving up and sending the
 * next queued command anyway. Generous relative to a typical unencrypted,
 * default-MTU BLE round trip — this is a stall guard, not a measured value.
 */
const ACK_TIMEOUT_MS = 800

export type ConnectionState =
  | { kind: 'disconnected' }
  | { kind: 'connecting' }
  | { kind: 'connected' }
  | { kind: 'reconnecting' }

export interface AiceBleListener {
  onState?: (state: AiceState | null) => void
  onConnection?: (conn: ConnectionState) => void
  onFirmware?: (firmware: string) => void
  onDeviceName?: (name: string) => void
  onLog?: (direction: 'tx' | 'rx', bytes: Uint8Array) => void
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * Web Bluetooth transport for the RANVOO AICE Lite (PROTOCOL.md §2).
 * `ff04` advertises only the `write` property, so Chrome emits an ATT Write
 * Request; the firmware accepts it once the frame carries the FF FF FF FF
 * command header (stamped upstream, by the store). This client prefers
 * writeValueWithoutResponse when the characteristic supports it and falls
 * back to writeValueWithResponse — both drive the device.
 */
export class AiceBleClient {
  private device: BluetoothDevice | null = null
  private controlChar: BluetoothRemoteGATTCharacteristic | null = null
  private writeQueue: Promise<void> = Promise.resolve()
  private backoffMs = MIN_BACKOFF_MS
  private reconnecting = false
  private listener: AiceBleListener = {}
  private readonly ackGate: AckGate

  constructor() {
    this.ackGate = new AckGate((frame) => {
      this.writeQueue = this.writeQueue.then(() => this.writeFrame(frame)).catch(() => {})
    }, ACK_TIMEOUT_MS)
  }

  static get isSupported(): boolean {
    return 'bluetooth' in navigator
  }

  setListener(listener: AiceBleListener): void {
    this.listener = listener
  }

  /** Requires a user gesture (e.g. a click handler) — Chrome rejects requestDevice otherwise. */
  async connect(): Promise<void> {
    this.listener.onConnection?.({ kind: 'connecting' })
    try {
      const device = await navigator.bluetooth.requestDevice({
        filters: [{ namePrefix: 'LH-' }, { services: [SERVICE_UUID] }],
        optionalServices: [SERVICE_UUID, DIS_SERVICE_UUID],
      })
      this.device = device
      this.listener.onDeviceName?.(device.name ?? 'AICE Lite')
      device.addEventListener('gattserverdisconnected', this.handleDisconnected)
      await this.attach(device)
    } catch (error) {
      // e.g. the user closed the chooser without picking a device — reset
      // so the Connect button doesn't stay stuck on "Connecting…".
      this.listener.onConnection?.({ kind: 'disconnected' })
      throw error
    }
  }

  private handleDisconnected = (): void => {
    this.controlChar = null
    this.ackGate.reset()
    this.listener.onState?.(null)
    if (!this.reconnecting) this.beginReconnect()
  }

  private beginReconnect(): void {
    this.reconnecting = true
    this.backoffMs = MIN_BACKOFF_MS
    void this.retryLoop()
  }

  private async retryLoop(): Promise<void> {
    const device = this.device
    if (device === null) return
    this.listener.onConnection?.({ kind: 'reconnecting' })
    while (this.reconnecting) {
      try {
        await this.attach(device)
        this.reconnecting = false
        return
      } catch {
        await sleep(this.backoffMs)
        this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS)
      }
    }
  }

  private async attach(device: BluetoothDevice): Promise<void> {
    const gatt = device.gatt
    if (gatt === undefined) throw new Error('device has no GATT server')
    const server = await gatt.connect()
    const service = await server.getPrimaryService(SERVICE_UUID)
    this.controlChar = await service.getCharacteristic(CHAR_CONTROL_UUID)
    const notifyChar = await service.getCharacteristic(CHAR_NOTIFY_UUID)
    notifyChar.addEventListener('characteristicvaluechanged', this.handleNotification)
    await notifyChar.startNotifications()
    this.backoffMs = MIN_BACKOFF_MS
    this.listener.onConnection?.({ kind: 'connected' })
    void this.readFirmware(server)
  }

  private async readFirmware(server: BluetoothRemoteGATTServer): Promise<void> {
    try {
      const dis = await server.getPrimaryService(DIS_SERVICE_UUID)
      const char = await dis.getCharacteristic(DIS_FIRMWARE_UUID)
      const value = await char.readValue()
      this.listener.onFirmware?.(new TextDecoder().decode(value).trim())
    } catch {
      // Not every revision exposes DIS; the Settings row just shows "—".
    }
  }

  private handleNotification = (event: Event): void => {
    const char = event.target as BluetoothRemoteGATTCharacteristic
    const value = char.value
    if (value === undefined) return
    const bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength)
    this.listener.onLog?.('rx', bytes)
    this.listener.onState?.(AiceState.fromNotification(bytes))
    this.ackGate.onAck()
  }

  /**
   * Send a bare 16-byte command payload. Wraps it in the wire frame and
   * hands it to the ack gate: at most one command is in flight at a time,
   * and the next one only goes out once the device's echo (or a timeout)
   * settles the previous one — see `ack-gate.ts`.
   */
  send(payload: Uint8Array): void {
    this.ackGate.send(encodeFrame(payload))
  }

  /** Debug panel: write an already-framed 20-byte (or arbitrary) buffer as-is. */
  sendRawFrame(frame: Uint8Array): void {
    this.ackGate.send(frame)
  }

  private async writeFrame(frame: Uint8Array): Promise<void> {
    const char = this.controlChar
    if (char === null) return
    this.listener.onLog?.('tx', frame)
    if (char.properties.writeWithoutResponse) {
      await char.writeValueWithoutResponse(frame as BufferSource)
    } else {
      await char.writeValueWithResponse(frame as BufferSource)
    }
  }

  disconnect(): void {
    this.reconnecting = false
    this.ackGate.reset()
    this.device?.gatt?.disconnect()
    this.device = null
    this.controlChar = null
  }
}
