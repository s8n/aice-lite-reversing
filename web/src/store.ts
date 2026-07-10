import { AiceState, Idx, ModeOption, TempUnit, Mode } from './protocol/state.ts'
import * as Commands from './protocol/commands.ts'

export type StoreListener = (state: AiceState | null) => void

const WIND_DRAG_THROTTLE_MS = 120
const WIND_SETTLE_MS = 500
const TEMP_TX_INTERVAL_MS = 1000

export class AiceStore {
  private current: AiceState | null = null
  private listeners = new Set<StoreListener>()
  private windDragging = false
  private windThrottleTimer: ReturnType<typeof setTimeout> | null = null
  private windSettleTimer: ReturnType<typeof setTimeout> | null = null
  private tempSendTimer: ReturnType<typeof setTimeout> | null = null
  private tempSendPending: AiceState | null = null
  private readonly send: (frame: Uint8Array) => void

  constructor(send: (frame: Uint8Array) => void) {
    this.send = send
  }

  get state(): AiceState | null {
    return this.current
  }

  subscribe(listener: StoreListener): () => void {
    this.listeners.add(listener)
    listener(this.current)
    return () => this.listeners.delete(listener)
  }

  private notify(): void {
    for (const listener of this.listeners) listener(this.current)
  }

  onDeviceFrame(device: AiceState | null): void {
    if (device === null) {
      this.current = null
      this.notify()
      return
    }
    const local = this.current
    this.current = local === null || !this.windDragging ? device : device.withByte(Idx.WIND_LEVEL, local.windLevel)
    this.notify()
  }

  private apply(next: AiceState | null): void {
    if (next === null) return
    this.current = next
    this.notify()
    this.enqueue(next)
  }

  private enqueue(next: AiceState): void {
    const raw = next.raw.slice()
    raw[0] = 0xff
    raw[1] = 0xff
    raw[2] = 0xff
    raw[3] = 0xff
    this.send(raw)
  }

  togglePower(): void {
    const s = this.current
    if (s === null) return
    this.apply(s.isPowered ? Commands.powerOff(s) : Commands.powerOn(s))
  }

  togglePause(): void {
    const s = this.current
    if (s === null) return
    this.apply(s.isRunning ? Commands.pause(s) : Commands.resume(s))
  }

  setMode(mode: Mode): void {
    const s = this.current
    if (s === null) return
    this.apply(Commands.setMode(s, mode))
  }

  adjustTemp(delta: number): void {
    const s = this.current
    if (s === null) return
    const next = Commands.adjustTemp(s, delta)
    if (next === null) return
    this.current = next
    this.notify()
    this.tempSendPending = next
    if (this.tempSendTimer === null) this.flushTempSend()
  }

  private flushTempSend(): void {
    const pending = this.tempSendPending
    this.tempSendPending = null
    if (pending !== null) this.enqueue(pending)
    this.tempSendTimer = setTimeout(() => {
      this.tempSendTimer = null
      if (this.tempSendPending !== null) this.flushTempSend()
    }, TEMP_TX_INTERVAL_MS)
  }

  previewWind(level: number): void {
    const s = this.current
    if (s === null) return
    const next = Commands.setWind(s, level)
    if (next === null || next.equals(s)) return
    this.windDragging = true
    if (this.windSettleTimer !== null) clearTimeout(this.windSettleTimer)
    this.windSettleTimer = null
    this.current = next
    this.notify()
    if (this.windThrottleTimer !== null) clearTimeout(this.windThrottleTimer)
    this.windThrottleTimer = setTimeout(() => this.enqueue(next), WIND_DRAG_THROTTLE_MS)
  }

  commitWind(): void {
    if (this.windThrottleTimer !== null) clearTimeout(this.windThrottleTimer)
    this.windThrottleTimer = null
    const s = this.current
    if (s === null) return
    this.enqueue(s)
    if (this.windSettleTimer !== null) clearTimeout(this.windSettleTimer)
    this.windSettleTimer = setTimeout(() => {
      this.windDragging = false
    }, WIND_SETTLE_MS)
  }

  toggleModeOption(option: ModeOption): void {
    const s = this.current
    if (s === null) return
    const turningOff = s.activeOption === option
    this.apply(Commands.setModeOption(s, option, !turningOff))
  }

  setUnit(unit: TempUnit): void {
    const s = this.current
    if (s === null) return
    this.apply(Commands.setUnit(s, unit))
  }

  setLight(on: boolean): void {
    const s = this.current
    if (s === null) return
    this.apply(Commands.setLight(s, on))
  }

  setVoice(on: boolean): void {
    const s = this.current
    if (s === null) return
    this.apply(Commands.setVoice(s, on))
  }
}
