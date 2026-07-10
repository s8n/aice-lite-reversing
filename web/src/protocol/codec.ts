const CRC_TABLE = ((): Uint16Array => {
  const t = new Uint16Array(256)
  for (let i = 0; i < 256; i++) {
    let c = i
    for (let j = 0; j < 8; j++) c = c & 1 ? (c >>> 1) ^ 0xa001 : c >>> 1
    t[i] = c
  }
  return t
})()

export const HEADER = 0x00
export const SYNC = 0xcc

export const LH_KEY1 = Uint8Array.from([
  0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff,
])

export const STATE_PAYLOAD_SIZE = 16

export function crc16arc(data: Uint8Array | number[]): number {
  let c = 0
  for (const b of data) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8)
  return c & 0xffff
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length)
  out.set(a, 0)
  out.set(b, a.length)
  return out
}

export function checksum(payload: Uint8Array): number {
  return crc16arc(concat(payload, LH_KEY1))
}

export function encode(payload: Uint8Array): Uint8Array {
  const crc = checksum(payload)
  const frame = new Uint8Array(4 + payload.length)
  frame[0] = HEADER
  frame[1] = SYNC
  frame[2] = crc & 0xff
  frame[3] = (crc >>> 8) & 0xff
  frame.set(payload, 4)
  return frame
}

export function decode(frame: Uint8Array): Uint8Array | null {
  if (frame.length < 5) return null
  if (frame[0] !== HEADER || frame[1] !== SYNC) return null
  return frame.slice(4)
}

export function crcValid(frame: Uint8Array): boolean {
  const payload = decode(frame)
  if (payload === null) return false
  const expected = frame[2] | (frame[3] << 8)
  return checksum(payload) === expected
}
