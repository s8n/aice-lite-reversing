import {
  AiceState,
  Mode,
  ModeOption,
  TempUnit,
  Idx,
  Power,
  MODE_SEND_CODE,
  MODE_OPTION_NONE_CODE,
  WIND_MIN,
  WIND_MAX,
  tempRange,
} from './state.ts'

function clampToRange(value: number, range: [number, number]): number {
  const [min, max] = range
  return Math.min(max, Math.max(min, value))
}

export function powerOn(s: AiceState): AiceState {
  return s.withByte(Idx.POWER, s.isRunning ? Power.ON : Power.TURN_ON)
}

export function powerOff(s: AiceState): AiceState {
  return s.withByte(Idx.POWER, Power.OFF)
}

export function pause(s: AiceState): AiceState {
  return s.withByte(Idx.POWER, Power.IDLE)
}

export function resume(s: AiceState): AiceState {
  return s.withByte(Idx.POWER, Power.ON)
}

/** Mode, mode-option, and wind changes are refused while paused, not just off — confirmed live. */
export function setMode(s: AiceState, mode: Mode): AiceState | null {
  if (!s.isRunning) return null
  const withMode = s.withByte(Idx.MODE, MODE_SEND_CODE[mode])
  const range = tempRange(mode)
  if (range === null) return withMode
  return withMode.withByte(Idx.TARGET_TEMP, clampToRange(s.targetTempC, range))
}

export function adjustTemp(s: AiceState, delta: number): AiceState | null {
  if (!s.isRunning) return null
  const mode = s.mode
  const range = mode === null ? null : tempRange(mode)
  if (range === null) return null
  const next = clampToRange(s.targetTempC + delta, range)
  return next === s.targetTempC ? null : s.withByte(Idx.TARGET_TEMP, next)
}

export function setWind(s: AiceState, level: number): AiceState | null {
  if (!s.isRunning) return null
  return s.withByte(Idx.WIND_LEVEL, Math.min(WIND_MAX, Math.max(WIND_MIN, level)))
}

export function setModeOption(s: AiceState, option: ModeOption | null, on: boolean): AiceState | null {
  if (!s.isRunning) return null
  const code = option !== null && on ? option : MODE_OPTION_NONE_CODE
  return s.withByte(Idx.MODE_OPTION, code)
}

export function setUnit(s: AiceState, unit: TempUnit): AiceState {
  return s.withByte(Idx.TEMP_UNIT, unit)
}

const LIGHT_ON = 0x10
const LIGHT_OFF = 0x20
const VOICE_ON = 0x01
const VOICE_OFF = 0x02

export function setLight(s: AiceState, on: boolean): AiceState {
  return s.withByte(Idx.FLAGS, (s.flags & 0x0f) | (on ? LIGHT_ON : LIGHT_OFF))
}

export function setVoice(s: AiceState, on: boolean): AiceState {
  return s.withByte(Idx.FLAGS, (s.flags & 0xf0) | (on ? VOICE_ON : VOICE_OFF))
}
