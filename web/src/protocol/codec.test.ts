import { test } from 'node:test'
import assert from 'node:assert/strict'
import { crc16arc, encode, decode, crcValid, LH_KEY1, STATE_PAYLOAD_SIZE } from './codec.ts'

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.replace(/\s+/g, '')
  const out = new Uint8Array(clean.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.substr(i * 2, 2), 16)
  return out
}

test('crc16arc matches the CRC-16/ARC check value for "123456789"', () => {
  const bytes = Uint8Array.from('123456789'.split('').map((c) => c.charCodeAt(0)))
  assert.equal(crc16arc(bytes), 0xbb3d)
})

const CAPTURED_FRAMES = [
  { hex: '00CC297602000000000102010622011C16290101', temp: 22, level: 41 },
  { hex: '00CCBCED02000000000102010622011C1A500101', temp: 26, level: 80 },
  { hex: '00CCC03002000000000102010622011C1E3C0101', temp: 30, level: 60 },
  { hex: '00CC73CA02000000000102010622011C155A0101', temp: 21, level: 90 },
  { hex: '00CC9A7202000000000102010622011C10640101', temp: 16, level: 100 },
]

test('captured frames reproduce their own CRC bytes when hashed as payload ++ lhKey1', () => {
  for (const { hex, temp, level } of CAPTURED_FRAMES) {
    const frame = hexToBytes(hex)
    const payload = decode(frame)
    assert.ok(payload, `frame ${hex} should decode`)
    assert.equal(payload!.length, STATE_PAYLOAD_SIZE)
    assert.equal(payload![12], temp, `temp byte in ${hex}`)
    assert.equal(payload![13], level, `level byte in ${hex}`)
    assert.ok(crcValid(frame), `CRC should validate for ${hex}`)
  }
})

test('a plain CRC over the payload alone does not reproduce any captured frame', () => {
  for (const { hex } of CAPTURED_FRAMES) {
    const frame = hexToBytes(hex)
    const payload = decode(frame)!
    const expected = frame[2] | (frame[3] << 8)
    assert.notEqual(crc16arc(payload), expected)
  }
})

test('encode/decode round-trip preserves the payload and produces a valid CRC', () => {
  const payload = Uint8Array.from([0xff, 0xff, 0xff, 0xff, 0, 1, 2, 1, 5, 0x22, 1, 28, 22, 41, 0xff, 1])
  const frame = encode(payload)
  assert.equal(frame.length, 20)
  assert.ok(crcValid(frame))
  const decoded = decode(frame)
  assert.deepEqual(decoded, payload)
})

test('decode rejects frames with the wrong header or sync byte', () => {
  const good = encode(new Uint8Array(16))
  const badHeader = good.slice()
  badHeader[0] = 0x01
  assert.equal(decode(badHeader), null)
  const badSync = good.slice()
  badSync[1] = 0xcd
  assert.equal(decode(badSync), null)
})

test('LH_KEY1 is the documented 16-byte sequence', () => {
  assert.deepEqual(
    Array.from(LH_KEY1),
    [0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff],
  )
})
