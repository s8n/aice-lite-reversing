import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AiceStore } from './store.ts'
import { AiceState, Power, Idx, TempUnit, ModeOption, Mode } from './protocol/state.ts'

function poweredOnState(overrides: Partial<Record<number, number>> = {}): AiceState {
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

test('a command sent to the wire always carries the FF FF FF FF header', () => {
  const sent: Uint8Array[] = []
  const store = new AiceStore((frame) => sent.push(frame))
  store.onDeviceFrame(poweredOnState())
  store.togglePause()
  assert.equal(sent.length, 1)
  assert.deepEqual(Array.from(sent[0].slice(0, 4)), [0xff, 0xff, 0xff, 0xff])
  assert.notEqual(store.state!.raw[0], 0xff)
})

test('no command is sent before the first device frame arrives', () => {
  const sent: Uint8Array[] = []
  const store = new AiceStore((frame) => sent.push(frame))
  store.togglePower()
  store.adjustTemp(1)
  assert.equal(sent.length, 0)
  assert.equal(store.state, null)
})

test('the device echo replaces the local buffer, even when it contradicts what was sent', () => {
  const store = new AiceStore(() => {})
  store.onDeviceFrame(poweredOnState({ [Idx.TARGET_TEMP]: 22 }))
  store.adjustTemp(1)
  assert.equal(store.state!.targetTempC, 23)
  store.onDeviceFrame(poweredOnState({ [Idx.TARGET_TEMP]: 18 }))
  assert.equal(store.state!.targetTempC, 18)
})

test('while a wind drag is in flight, an arriving echo keeps the locally dragged wind level', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const store = new AiceStore(() => {})
  store.onDeviceFrame(poweredOnState({ [Idx.WIND_LEVEL]: 40 }))
  store.previewWind(70)
  assert.equal(store.state!.windLevel, 70)
  store.onDeviceFrame(poweredOnState({ [Idx.WIND_LEVEL]: 40, [Idx.AMBIENT_TEMP]: 27 }))
  assert.equal(store.state!.windLevel, 70, 'the drag value must survive the echo')
  assert.equal(store.state!.ambientTempC, 27, 'other fields still adopt the echo')
})

test('wind drag throttles the radio to one frame per 120ms and sends the final value on release', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const store = new AiceStore((frame) => sent.push(frame))
  store.onDeviceFrame(poweredOnState({ [Idx.WIND_LEVEL]: 0 }))
  store.previewWind(10)
  store.previewWind(20)
  store.previewWind(30)
  assert.equal(sent.length, 0, 'no frame yet — still inside the throttle window')
  t.mock.timers.tick(120)
  assert.equal(sent.length, 1)
  assert.equal(sent[0].length, 16, 'store.send receives the bare 16-byte payload, not a wire frame')
  assert.equal(sent[0][Idx.WIND_LEVEL], 30, 'only the latest value during the window is sent')
  store.commitWind()
  assert.equal(sent.length, 2, 'release sends the final value immediately')
})

test('after the drag settles (500ms after release), the echo takes over the wind level again', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const store = new AiceStore(() => {})
  store.onDeviceFrame(poweredOnState({ [Idx.WIND_LEVEL]: 40 }))
  store.previewWind(70)
  store.commitWind()
  t.mock.timers.tick(500)
  store.onDeviceFrame(poweredOnState({ [Idx.WIND_LEVEL]: 65 }))
  assert.equal(store.state!.windLevel, 65)
})

test('temp adjustments send immediately then throttle to one command per second', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const store = new AiceStore((frame) => sent.push(frame))
  store.onDeviceFrame(poweredOnState({ [Idx.TARGET_TEMP]: 20 }))
  store.adjustTemp(1)
  assert.equal(sent.length, 1, 'the first press sends immediately')
  assert.equal(sent[0][Idx.TARGET_TEMP], 21)

  store.adjustTemp(1)
  store.adjustTemp(1)
  store.adjustTemp(1)
  assert.equal(store.state!.targetTempC, 24, 'the UI reflects every press right away')
  assert.equal(sent.length, 1, 'no extra frame yet — still inside the throttle window')

  t.mock.timers.tick(1000)
  assert.equal(sent.length, 2, 'the window flushes exactly one frame with the latest value')
  assert.equal(sent[1][Idx.TARGET_TEMP], 24)

  t.mock.timers.tick(1000)
  assert.equal(sent.length, 2, 'no further presses means no further frames')
})

test('toggleModeOption clears the active option on a second tap of the same one', () => {
  const store = new AiceStore(() => {})
  store.onDeviceFrame(poweredOnState())
  store.toggleModeOption(ModeOption.SILENT)
  assert.equal(store.state!.modeOptionCode, ModeOption.SILENT)
  store.toggleModeOption(ModeOption.SILENT)
  assert.equal(store.state!.modeOptionCode, 0xff)
})

test('while paused, only power off and resume take effect — mode, mode option, and wind are all refused', () => {
  const sent: Uint8Array[] = []
  const store = new AiceStore((frame) => sent.push(frame))
  store.onDeviceFrame(poweredOnState({ [Idx.POWER]: Power.IDLE, [Idx.MODE]: 1, [Idx.WIND_LEVEL]: 40 }))

  store.setMode(Mode.HEATING)
  assert.equal(store.state!.modeCode, 1, 'mode must not change while paused')

  store.toggleModeOption(ModeOption.SILENT)
  assert.equal(store.state!.modeOptionCode, 0xff, 'mode option must not change while paused')

  store.previewWind(70)
  assert.equal(store.state!.windLevel, 40, 'wind level must not change while paused')

  assert.equal(sent.length, 0, 'no command should have been sent for any of the refused actions')

  store.togglePause()
  assert.equal(store.state!.powerCode, Power.ON, 'resume still works while paused')
  assert.equal(sent.length, 1)
})
