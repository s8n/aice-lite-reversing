import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  AiceState,
  Power,
  Mode,
  ModeOption,
  TempUnit,
  Idx,
  modeFrom,
  tempRange,
  modeHasWind,
  formatTemp,
  unitSuffix,
} from './state.ts'

function stateWith(overrides: Partial<Record<number, number>>): AiceState {
  const raw = new Uint8Array(16)
  raw[Idx.POWER] = Power.ON
  raw[Idx.MODE] = 1
  raw[Idx.BATTERY] = 5
  raw[Idx.CHARGE_STATUS] = 1
  raw[Idx.TEMP_UNIT] = TempUnit.CELSIUS
  raw[Idx.MODE_OPTION] = 0xff
  for (const [k, v] of Object.entries(overrides)) raw[Number(k)] = v as number
  return new AiceState(raw)
}

test('modeFrom decodes AI from both the send code 4 and the echo code 6', () => {
  assert.equal(modeFrom(4), Mode.AI)
  assert.equal(modeFrom(6), Mode.AI)
  assert.equal(modeFrom(1), Mode.COOLING)
  assert.equal(modeFrom(2), Mode.HEATING)
  assert.equal(modeFrom(3), Mode.FAN)
  assert.equal(modeFrom(99), null)
})

test('tempRange matches the operator-confirmed bounds per mode', () => {
  assert.deepEqual(tempRange(Mode.COOLING), [16, 30])
  assert.deepEqual(tempRange(Mode.HEATING), [40, 50])
  assert.deepEqual(tempRange(Mode.AI), [16, 30])
  assert.equal(tempRange(Mode.FAN), null)
})

test('modeHasWind is false only for heating', () => {
  assert.equal(modeHasWind(Mode.HEATING), false)
  assert.equal(modeHasWind(Mode.COOLING), true)
  assert.equal(modeHasWind(Mode.FAN), true)
  assert.equal(modeHasWind(Mode.AI), true)
})

test('power state derivations: running, paused, off, powered', () => {
  assert.equal(stateWith({ [Idx.POWER]: Power.ON }).isRunning, true)
  assert.equal(stateWith({ [Idx.POWER]: Power.IDLE }).isPaused, true)
  assert.equal(stateWith({ [Idx.POWER]: Power.OFF }).isOff, true)
  assert.equal(stateWith({ [Idx.POWER]: Power.IDLE }).isPowered, true)
  assert.equal(stateWith({ [Idx.POWER]: Power.OFF }).isPowered, false)
})

test('battery bars and percent: 4=2 bars, 5=3 bars (captured live)', () => {
  assert.equal(stateWith({ [Idx.BATTERY]: 4 }).batteryBars, 2)
  assert.equal(stateWith({ [Idx.BATTERY]: 4 }).batteryPercent, 50)
  assert.equal(stateWith({ [Idx.BATTERY]: 5 }).batteryBars, 3)
  assert.equal(stateWith({ [Idx.BATTERY]: 5 }).batteryPercent, 75)
  assert.equal(stateWith({ [Idx.BATTERY]: 2 }).batteryPercent, 0)
  assert.equal(stateWith({ [Idx.BATTERY]: 6 }).batteryPercent, 100)
})

test('charging: charge status 1 = on battery, 3 = charging, 2 = transient charging', () => {
  assert.equal(stateWith({ [Idx.CHARGE_STATUS]: 1 }).isCharging, false)
  assert.equal(stateWith({ [Idx.CHARGE_STATUS]: 2 }).isCharging, true)
  assert.equal(stateWith({ [Idx.CHARGE_STATUS]: 3 }).isCharging, true)
})

test('flags: light and voice nibbles decode independently', () => {
  assert.equal(stateWith({ [Idx.FLAGS]: 0x22 }).lightOn, false)
  assert.equal(stateWith({ [Idx.FLAGS]: 0x22 }).voiceOn, false)
  assert.equal(stateWith({ [Idx.FLAGS]: 0x11 }).lightOn, true)
  assert.equal(stateWith({ [Idx.FLAGS]: 0x11 }).voiceOn, true)
  assert.equal(stateWith({ [Idx.FLAGS]: 0x12 }).lightOn, true)
  assert.equal(stateWith({ [Idx.FLAGS]: 0x12 }).voiceOn, false)
})

test('supportsTargetTemp and supportsWind follow the active mode', () => {
  assert.equal(stateWith({ [Idx.MODE]: 3 }).supportsTargetTemp, false)
  assert.equal(stateWith({ [Idx.MODE]: 1 }).supportsTargetTemp, true)
  assert.equal(stateWith({ [Idx.MODE]: 2 }).supportsWind, false)
  assert.equal(stateWith({ [Idx.MODE]: 1 }).supportsWind, true)
})

test('activeOption reads the mode-option byte directly; 0xFF means none', () => {
  assert.equal(stateWith({ [Idx.MODE_OPTION]: 0x01 }).activeOption, ModeOption.SILENT)
  assert.equal(stateWith({ [Idx.MODE_OPTION]: 0x04 }).activeOption, ModeOption.LOW_POWER)
  assert.equal(stateWith({ [Idx.MODE_OPTION]: 0xff }).activeOption, null)
  assert.equal(stateWith({ [Idx.MODE_OPTION]: 0xff }).hasModeOption, false)
})

test('withByte returns a new state and does not mutate the original', () => {
  const original = stateWith({ [Idx.TARGET_TEMP]: 22 })
  const next = original.withByte(Idx.TARGET_TEMP, 25)
  assert.equal(original.targetTempC, 22)
  assert.equal(next.targetTempC, 25)
})

test('formatTemp converts to Fahrenheit only for display; unitSuffix matches', () => {
  assert.equal(formatTemp(TempUnit.CELSIUS, 22), 22)
  assert.equal(formatTemp(TempUnit.FAHRENHEIT, 22), 72)
  assert.equal(unitSuffix(TempUnit.CELSIUS), '°C')
  assert.equal(unitSuffix(TempUnit.FAHRENHEIT), '°F')
})

test('fromNotification decodes a captured status frame end to end', () => {
  const frame = Uint8Array.from(
    '00CC297602000000000102010622011C16290101'.match(/../g)!.map((h) => parseInt(h, 16)),
  )
  const state = AiceState.fromNotification(frame)
  assert.ok(state)
  assert.equal(state!.targetTempC, 22)
  assert.equal(state!.windLevel, 41)
})
