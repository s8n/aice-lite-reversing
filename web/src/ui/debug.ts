import { AiceState, Idx } from '../protocol/state.ts'
import { encode, crcValid } from '../protocol/codec.ts'
import { el } from './dom.ts'
import { icon } from './icons.ts'

export interface DebugScreenHandlers {
  onBack: () => void
  onSendRaw: (bytes: Uint8Array) => void
  onClearLog: () => void
}

const BYTE_NAMES: Record<number, string> = {
  0: 'state[0] (unnamed)',
  1: 'state[1] (unnamed)',
  2: 'state[2] (unnamed)',
  3: 'state[3] (unnamed)',
  4: 'state[4] (const 0x00)',
  5: 'state[5] (const 0x01)',
  [Idx.POWER]: 'power',
  [Idx.CHARGE_STATUS]: 'chargeStatus',
  [Idx.BATTERY]: 'battery',
  [Idx.FLAGS]: 'flags',
  [Idx.MODE]: 'mode',
  [Idx.AMBIENT_TEMP]: 'ambientTemp',
  [Idx.TARGET_TEMP]: 'targetTemp',
  [Idx.WIND_LEVEL]: 'windLevel',
  [Idx.MODE_OPTION]: 'modeOpt',
  [Idx.TEMP_UNIT]: 'tempUnit',
}

function hex(byte: number): string {
  return byte.toString(16).padStart(2, '0').toUpperCase()
}

function parseHex(text: string): Uint8Array | null {
  const cleaned = text.replace(/0x/gi, '').replace(/[^0-9a-f]/gi, '')
  if (cleaned.length === 0 || cleaned.length % 2 !== 0) return null
  const out = new Uint8Array(cleaned.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(cleaned.substr(i * 2, 2), 16)
  return out
}

export function mountDebugScreen(root: HTMLElement, handlers: DebugScreenHandlers) {
  const backButton = el('button', { class: 'icon-button' }, icon('back'))
  backButton.addEventListener('click', handlers.onBack)

  const byteTableBody = el('tbody')
  const byteTable = el(
    'table',
    { class: 'byte-table' },
    el('thead', {}, el('tr', {}, el('th', {}, 'idx'), el('th', {}, 'name'), el('th', {}, 'hex'), el('th', {}, 'dec'))),
    byteTableBody,
  )

  const hexLog = el('div', { class: 'hex-log' })
  const clearButton = el('button', {}, 'Clear log')
  clearButton.addEventListener('click', () => {
    hexLog.replaceChildren()
    handlers.onClearLog()
  })

  const frameInput = el('input', {
    placeholder: '00 CC 29 76 FF FF FF FF ... (16 or 20 bytes)',
  }) as HTMLInputElement
  const sendButton = el('button', {}, 'Send')
  const errorNote = el('div', { class: 'row-value' })
  sendButton.addEventListener('click', () => {
    const bytes = parseHex(frameInput.value)
    if (bytes === null) {
      errorNote.textContent = 'Could not parse hex input.'
      return
    }
    errorNote.textContent = ''
    handlers.onSendRaw(bytes.length === 16 ? encode(bytes) : bytes)
  })

  root.append(
    el('div', { class: 'topbar' }, backButton, el('h1', {}, 'Debug')),
    el('div', { class: 'section' }, el('div', { class: 'row' }, byteTable)),
    el(
      'div',
      { class: 'section' },
      el('div', { class: 'row' }, el('div', { class: 'row-label' }, 'TX / RX log'), clearButton),
      el('div', { class: 'row' }, hexLog),
      el('div', { class: 'row' }, el('div', { class: 'frame-input-row' }, frameInput, sendButton)),
      el('div', { class: 'row' }, errorNote),
    ),
  )

  function render(state: AiceState | null): void {
    byteTableBody.replaceChildren()
    if (state === null) return
    for (let i = 0; i < state.raw.length; i++) {
      const byte = state.raw[i] ?? 0
      byteTableBody.append(
        el(
          'tr',
          {},
          el('td', {}, String(i)),
          el('td', {}, BYTE_NAMES[i] ?? `state[${i}]`),
          el('td', {}, `0x${hex(byte)}`),
          el('td', {}, String(byte)),
        ),
      )
    }
  }

  function logFrame(direction: 'tx' | 'rx', bytes: Uint8Array): void {
    const hexText = Array.from(bytes, hex).join(' ')
    const mismatch = direction === 'rx' && !crcValid(bytes)
    const line = el('div', { class: direction }, `${direction.toUpperCase()}  ${hexText}${mismatch ? '  (CRC mismatch)' : ''}`)
    hexLog.append(line)
    hexLog.scrollTop = hexLog.scrollHeight
  }

  return { render, logFrame }
}
