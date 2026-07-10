import { decode, STATE_PAYLOAD_SIZE, encode as codecEncode } from './codec.ts'

export const Power = {
  IDLE: 1,
  ON: 2,
  OFF: 3,
  TURN_ON: 4,
} as const
export type Power = (typeof Power)[keyof typeof Power]

export function powerFrom(code: number): Power | null {
  return code === Power.IDLE || code === Power.ON || code === Power.OFF || code === Power.TURN_ON
    ? (code as Power)
    : null
}

export const Mode = {
  COOLING: 'COOLING',
  HEATING: 'HEATING',
  FAN: 'FAN',
  AI: 'AI',
} as const
export type Mode = (typeof Mode)[keyof typeof Mode]

export const MODE_SEND_CODE: Record<Mode, number> = {
  [Mode.COOLING]: 1,
  [Mode.HEATING]: 2,
  [Mode.FAN]: 3,
  [Mode.AI]: 4,
}

export const MODE_LABEL: Record<Mode, string> = {
  [Mode.COOLING]: 'Cooling',
  [Mode.HEATING]: 'Heating',
  [Mode.FAN]: 'Fan',
  [Mode.AI]: 'PID Mode',
}

export function modeFrom(code: number): Mode | null {
  if (code === 4 || code === 6) return Mode.AI
  if (code === 1) return Mode.COOLING
  if (code === 2) return Mode.HEATING
  if (code === 3) return Mode.FAN
  return null
}

export function tempRange(mode: Mode): [number, number] | null {
  switch (mode) {
    case Mode.COOLING:
      return [16, 30]
    case Mode.HEATING:
      return [40, 50]
    case Mode.AI:
      return [16, 30]
    case Mode.FAN:
      return null
  }
}

export function modeHasWind(mode: Mode): boolean {
  return mode !== Mode.HEATING
}

export const ModeOption = {
  SILENT: 0x01,
  HOT_PACK: 0x02,
  COOLING_FIRST: 0x03,
  LOW_POWER: 0x04,
} as const
export type ModeOption = (typeof ModeOption)[keyof typeof ModeOption]

export const MODE_OPTION_NONE_CODE = 0xff

export const MODE_OPTION_LABEL: Record<ModeOption, string> = {
  [ModeOption.SILENT]: 'Quiet',
  [ModeOption.HOT_PACK]: 'Hot Pack',
  [ModeOption.COOLING_FIRST]: 'Cooling First',
  [ModeOption.LOW_POWER]: 'Low Power',
}

export function modeOptionFrom(code: number): ModeOption | null {
  switch (code) {
    case ModeOption.SILENT:
      return ModeOption.SILENT
    case ModeOption.HOT_PACK:
      return ModeOption.HOT_PACK
    case ModeOption.COOLING_FIRST:
      return ModeOption.COOLING_FIRST
    case ModeOption.LOW_POWER:
      return ModeOption.LOW_POWER
    default:
      return null
  }
}

export const TempUnit = {
  CELSIUS: 1,
  FAHRENHEIT: 2,
} as const
export type TempUnit = (typeof TempUnit)[keyof typeof TempUnit]

export function tempUnitFrom(code: number): TempUnit {
  return code === TempUnit.FAHRENHEIT ? TempUnit.FAHRENHEIT : TempUnit.CELSIUS
}

export function unitSuffix(unit: TempUnit): string {
  return unit === TempUnit.FAHRENHEIT ? '°F' : '°C'
}

export function formatTemp(unit: TempUnit, celsius: number): number {
  return unit === TempUnit.FAHRENHEIT ? Math.round((celsius * 9) / 5) + 32 : celsius
}

export const Flags = {
  LIGHT_ON: 0x10,
  LIGHT_OFF: 0x20,
  VOICE_ON: 0x01,
  VOICE_OFF: 0x02,
} as const

export const Idx = {
  POWER: 6,
  CHARGE_STATUS: 7,
  BATTERY: 8,
  FLAGS: 9,
  MODE: 10,
  AMBIENT_TEMP: 11,
  TARGET_TEMP: 12,
  WIND_LEVEL: 13,
  MODE_OPTION: 14,
  TEMP_UNIT: 15,
} as const

export const WIND_MIN = 0
export const WIND_MAX = 100

export const BATTERY_RAW_EMPTY = 2
export const BATTERY_RAW_FULL = 6

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

export class AiceState {
  readonly raw: Uint8Array

  constructor(raw: Uint8Array) {
    if (raw.length < STATE_PAYLOAD_SIZE) {
      throw new Error(`state payload must be ${STATE_PAYLOAD_SIZE} bytes, got ${raw.length}`)
    }
    this.raw = raw.slice(0, STATE_PAYLOAD_SIZE)
  }

  private u(i: number): number {
    return this.raw[i]
  }

  get powerCode(): number {
    return this.u(Idx.POWER)
  }
  get power(): Power | null {
    return powerFrom(this.powerCode)
  }
  get modeCode(): number {
    return this.u(Idx.MODE)
  }
  get mode(): Mode | null {
    return modeFrom(this.modeCode)
  }
  get ambientTempC(): number {
    return this.u(Idx.AMBIENT_TEMP)
  }
  get targetTempC(): number {
    return this.u(Idx.TARGET_TEMP)
  }
  get windLevel(): number {
    return this.u(Idx.WIND_LEVEL)
  }
  get modeOptionCode(): number {
    return this.u(Idx.MODE_OPTION)
  }
  get flags(): number {
    return this.u(Idx.FLAGS)
  }
  get unit(): TempUnit {
    return tempUnitFrom(this.u(Idx.TEMP_UNIT))
  }

  get isRunning(): boolean {
    return this.powerCode === Power.ON
  }
  get isPaused(): boolean {
    return this.powerCode === Power.IDLE
  }
  get isOff(): boolean {
    return this.powerCode === Power.OFF
  }

  get isPowered(): boolean {
    return this.isRunning || this.isPaused
  }

  get activeOption(): ModeOption | null {
    return modeOptionFrom(this.modeOptionCode)
  }
  get hasModeOption(): boolean {
    return this.activeOption !== null
  }

  get lightOn(): boolean {
    return (this.flags & Flags.LIGHT_ON) !== 0
  }
  get voiceOn(): boolean {
    return (this.flags & Flags.VOICE_ON) !== 0
  }

  get isCharging(): boolean {
    return this.u(Idx.CHARGE_STATUS) >= 2
  }

  get batteryRaw(): number {
    return this.u(Idx.BATTERY)
  }

  get batteryBars(): number {
    return clamp(this.batteryRaw - BATTERY_RAW_EMPTY, 0, 4)
  }

  get batteryPercent(): number {
    return clamp(
      Math.round(((this.batteryRaw - BATTERY_RAW_EMPTY) * 100) / (BATTERY_RAW_FULL - BATTERY_RAW_EMPTY)),
      0,
      100,
    )
  }

  get supportsTargetTemp(): boolean {
    const mode = this.mode
    return mode !== null && tempRange(mode) !== null
  }

  get supportsWind(): boolean {
    const mode = this.mode
    return mode === null || modeHasWind(mode)
  }

  withByte(index: number, value: number): AiceState {
    const next = this.raw.slice()
    next[index] = value & 0xff
    return new AiceState(next)
  }

  equals(other: AiceState): boolean {
    if (this.raw.length !== other.raw.length) return false
    for (let i = 0; i < this.raw.length; i++) if (this.raw[i] !== other.raw[i]) return false
    return true
  }

  toFrame(): Uint8Array {
    return codecEncode(this.raw)
  }

  static fromNotification(frame: Uint8Array): AiceState | null {
    const payload = decode(frame)
    if (payload === null || payload.length < STATE_PAYLOAD_SIZE) return null
    return new AiceState(payload)
  }
}
