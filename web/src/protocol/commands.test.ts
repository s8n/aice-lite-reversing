import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AiceState, Power, Mode, ModeOption, TempUnit, Idx } from './state.ts'
import * as Commands from './commands.ts'

function baseState(overrides: Partial<Record<number, number>> = {}): AiceState {
  const raw = new Uint8Array(16)
  raw[Idx.POWER] = Power.ON
  raw[Idx.MODE] = 1
  raw[Idx.TARGET_TEMP] = 22
  raw[Idx.WIND_LEVEL] = 40
  raw[Idx.MODE_OPTION] = 0xff
  raw[Idx.TEMP_UNIT] = TempUnit.CELSIUS
  for (const [k, v] of Object.entries(overrides)) raw[Number(k)] = v as number
  return new AiceState(raw)
}

test('powerOn writes 2 when already running, 4 (turn-on) otherwise', () => {
  const running = baseState({ [Idx.POWER]: Power.ON })
  assert.equal(Commands.powerOn(running).powerCode, Power.ON)
  const off = baseState({ [Idx.POWER]: Power.OFF })
  assert.equal(Commands.powerOn(off).powerCode, Power.TURN_ON)
})

test('powerOff, pause, resume write their documented codes and touch only payload[6]', () => {
  const s = baseState()
  const off = Commands.powerOff(s)
  assert.equal(off.powerCode, Power.OFF)
  assert.equal(off.targetTempC, s.targetTempC)
  assert.equal(Commands.pause(s).powerCode, Power.IDLE)
  assert.equal(Commands.resume(s).powerCode, Power.ON)
})

test('setMode writes the send code and clamps the target into the new range', () => {
  const cooling22 = baseState({ [Idx.MODE]: 1, [Idx.TARGET_TEMP]: 22 })
  const heating = Commands.setMode(cooling22, Mode.HEATING)
  assert.ok(heating)
  assert.equal(heating!.modeCode, 2)
  assert.equal(heating!.targetTempC, 40)

  const ai = Commands.setMode(cooling22, Mode.AI)
  assert.equal(ai!.modeCode, 4)
})

test('setMode is refused while the device is off or paused — only running accepts it', () => {
  const off = baseState({ [Idx.POWER]: Power.OFF })
  assert.equal(Commands.setMode(off, Mode.HEATING), null)
  const paused = baseState({ [Idx.POWER]: Power.IDLE })
  assert.equal(Commands.setMode(paused, Mode.HEATING), null)
})

test('adjustTemp steps by delta, clamps to the mode range, and is refused unless running', () => {
  const s = baseState({ [Idx.MODE]: 1, [Idx.TARGET_TEMP]: 30, [Idx.POWER]: Power.ON })
  assert.equal(Commands.adjustTemp(s, 1), null)
  assert.equal(Commands.adjustTemp(s, -1)!.targetTempC, 29)

  const paused = baseState({ [Idx.POWER]: Power.IDLE })
  assert.equal(Commands.adjustTemp(paused, 1), null)

  const fan = baseState({ [Idx.MODE]: 3, [Idx.POWER]: Power.ON })
  assert.equal(Commands.adjustTemp(fan, 1), null)
})

test('setWind clamps to 0..100 and is refused unless running', () => {
  assert.equal(Commands.setWind(baseState(), 150)!.windLevel, 100)
  assert.equal(Commands.setWind(baseState(), -5)!.windLevel, 0)
  assert.equal(Commands.setWind(baseState(), 41)!.windLevel, 41)
  const paused = baseState({ [Idx.POWER]: Power.IDLE })
  assert.equal(Commands.setWind(paused, 50), null)
  const off = baseState({ [Idx.POWER]: Power.OFF })
  assert.equal(Commands.setWind(off, 50), null)
})

test('setModeOption writes the option code when turning on, 0xFF when turning off, refused while off or paused', () => {
  const s = baseState()
  assert.equal(Commands.setModeOption(s, ModeOption.SILENT, true)!.modeOptionCode, ModeOption.SILENT)
  assert.equal(Commands.setModeOption(s, ModeOption.SILENT, false)!.modeOptionCode, 0xff)
  const off = baseState({ [Idx.POWER]: Power.OFF })
  assert.equal(Commands.setModeOption(off, ModeOption.SILENT, true), null)
  const paused = baseState({ [Idx.POWER]: Power.IDLE })
  assert.equal(Commands.setModeOption(paused, ModeOption.SILENT, true), null)
})

test('setLight and setVoice edit only their own nibble of payload[9]', () => {
  const s = baseState({ [Idx.FLAGS]: 0x22 })
  const lit = Commands.setLight(s, true)
  assert.equal(lit.flags, 0x12)
  const voiced = Commands.setVoice(s, true)
  assert.equal(voiced.flags, 0x21)
})

test('setUnit writes the unit byte and does not touch the target temperature', () => {
  const s = baseState({ [Idx.TARGET_TEMP]: 25, [Idx.TEMP_UNIT]: TempUnit.CELSIUS })
  const next = Commands.setUnit(s, TempUnit.FAHRENHEIT)
  assert.equal(next.unit, TempUnit.FAHRENHEIT)
  assert.equal(next.targetTempC, 25)
})
