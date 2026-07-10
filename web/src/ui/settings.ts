import { AiceState, TempUnit } from '../protocol/state.ts'
import { el } from './dom.ts'
import { icon } from './icons.ts'

const REPO_URL = 'https://github.com/s8n/aice-lite-reversing'

export interface SettingsScreenHandlers {
  onBack: () => void
  onVoice: (on: boolean) => void
  onLight: (on: boolean) => void
  onUnit: (unit: TempUnit) => void
  onDisconnect: () => void
  onOpenDebug: () => void
}

function switchControl(onChange: (checked: boolean) => void) {
  const thumb = el('div', { class: 'thumb' })
  const root = el('div', { class: 'switch' }, thumb)
  let checked = false
  let enabled = true
  root.addEventListener('click', () => {
    if (!enabled) return
    checked = !checked
    root.classList.toggle('checked', checked)
    onChange(checked)
  })
  return {
    root,
    setChecked(next: boolean): void {
      checked = next
      root.classList.toggle('checked', next)
    },
    setEnabled(next: boolean): void {
      enabled = next
      root.classList.toggle('disabled', !next)
    },
  }
}

export function mountSettingsScreen(root: HTMLElement, initialName: string, handlers: SettingsScreenHandlers) {
  const backButton = el('button', { class: 'icon-button' }, icon('back'))
  backButton.addEventListener('click', handlers.onBack)

  const deviceNameValue = el('span', { class: 'row-value' }, initialName)

  const soundSwitch = switchControl(handlers.onVoice)
  const lightSwitch = switchControl(handlers.onLight)

  const celsiusButton = el('button', {}, '°C')
  const fahrenheitButton = el('button', {}, '°F')
  celsiusButton.addEventListener('click', () => handlers.onUnit(TempUnit.CELSIUS))
  fahrenheitButton.addEventListener('click', () => handlers.onUnit(TempUnit.FAHRENHEIT))
  const unitPicker = el('div', { class: 'unit-picker' }, celsiusButton, fahrenheitButton)

  const firmwareValue = el('span', { class: 'row-value' }, '—')
  const firmwareRow = el(
    'div',
    { class: 'row' },
    el('div', { class: 'row-label' }, 'Firmware Version'),
    firmwareValue,
  )

  const debugRow = el('div', { class: 'row clickable' }, el('div', { class: 'row-label' }, 'Debug'))
  debugRow.addEventListener('click', handlers.onOpenDebug)

  const sourceRow = el(
    'a',
    { class: 'row clickable', href: REPO_URL, target: '_blank', rel: 'noopener noreferrer' },
    el('div', { class: 'row-label' }, 'Source code'),
  )

  const disconnectRow = el('div', { class: 'row clickable danger' }, el('div', { class: 'row-label' }, 'Disconnect'))
  disconnectRow.addEventListener('click', handlers.onDisconnect)

  root.append(
    el('div', { class: 'topbar' }, backButton, el('h1', {}, 'Settings')),
    el(
      'div',
      { class: 'section' },
      el('div', { class: 'row' }, el('div', { class: 'row-label' }, 'Device Name'), deviceNameValue),
      el('div', { class: 'row' }, el('div', { class: 'row-label' }, 'Button Sounds'), soundSwitch.root),
      el('div', { class: 'row' }, el('div', { class: 'row-label' }, 'Light'), lightSwitch.root),
    ),
    el(
      'div',
      { class: 'section' },
      el('div', { class: 'row' }, el('div', { class: 'row-label' }, 'Unit'), unitPicker),
      firmwareRow,
    ),
    el('div', { class: 'section' }, debugRow, sourceRow, disconnectRow),
  )

  function render(state: AiceState | null, firmware: string | null): void {
    const connected = state !== null
    soundSwitch.setEnabled(connected)
    lightSwitch.setEnabled(connected)
    if (state !== null) {
      soundSwitch.setChecked(state.voiceOn)
      lightSwitch.setChecked(state.lightOn)
    }
    celsiusButton.classList.toggle('active', (state?.unit ?? TempUnit.CELSIUS) === TempUnit.CELSIUS)
    fahrenheitButton.classList.toggle('active', state?.unit === TempUnit.FAHRENHEIT)
    firmwareValue.textContent = firmware ?? '—'
  }

  function setDeviceName(name: string): void {
    deviceNameValue.textContent = name
  }

  return { render, setDeviceName }
}
