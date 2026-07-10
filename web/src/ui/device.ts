import { AiceState, Mode, ModeOption, MODE_LABEL, MODE_OPTION_LABEL, unitSuffix, formatTemp, WIND_MAX } from '../protocol/state.ts'
import { el } from './dom.ts'
import { icon, chargeBolt } from './icons.ts'

export interface DeviceScreenHandlers {
  onPower: () => void
  onPause: () => void
  onTempDelta: (delta: number) => void
  onWindPreview: (level: number) => void
  onWindCommit: () => void
  onMode: (mode: Mode) => void
  onOption: (option: ModeOption) => void
  onOpenSettings: () => void
}

const MODES: { mode: Mode; icon: string; label: string }[] = [
  { mode: Mode.COOLING, icon: 'cooling', label: MODE_LABEL[Mode.COOLING] },
  { mode: Mode.HEATING, icon: 'heating', label: MODE_LABEL[Mode.HEATING] },
  { mode: Mode.FAN, icon: 'wind', label: MODE_LABEL[Mode.FAN] },
  { mode: Mode.AI, icon: 'ai', label: MODE_LABEL[Mode.AI] },
]

const OPTIONS: { option: ModeOption; icon: string; label: string }[] = [
  { option: ModeOption.SILENT, icon: 'silent', label: MODE_OPTION_LABEL[ModeOption.SILENT] },
  { option: ModeOption.HOT_PACK, icon: 'hotpack', label: MODE_OPTION_LABEL[ModeOption.HOT_PACK] },
  { option: ModeOption.COOLING_FIRST, icon: 'coolingfirst', label: MODE_OPTION_LABEL[ModeOption.COOLING_FIRST] },
  { option: ModeOption.LOW_POWER, icon: 'lowpower', label: MODE_OPTION_LABEL[ModeOption.LOW_POWER] },
]

/**
 * The hardware only reports battery in 25%-wide steps (0/25/50/75/100), so
 * a reading like "75%" really means "somewhere between 50% and 75%" — show
 * that range instead of implying more precision than the sensor has. 100%
 * has the same ambiguity (could be anywhere from 75~100%), so only the
 * empty reading is a true single value.
 */
function batteryRangeText(percent: number): string {
  if (percent <= 0) return '0%'
  return `${percent - 25}~${percent}%`
}

export function mountDeviceScreen(root: HTMLElement, initialName: string, handlers: DeviceScreenHandlers) {
  const statusDot = el('span', { class: 'status-dot' })
  const deviceNameEl = el('span', {}, initialName)
  const batteryEl = el('div', { class: 'battery' })
  const powerButton = el('button', { class: 'icon-button', 'aria-label': 'Power' }, icon('power'))
  const settingsButton = el('button', { class: 'icon-button', 'aria-label': 'Settings' }, icon('gear'))

  powerButton.addEventListener('click', handlers.onPower)
  settingsButton.addEventListener('click', handlers.onOpenSettings)

  const topbar = el(
    'div',
    { class: 'topbar' },
    el('div', { class: 'brand' }, el('h1', {}, 'PIDCE LITE'), el('div', { class: 'sub' }, statusDot, deviceNameEl)),
    batteryEl,
    powerButton,
    settingsButton,
  )

  const minusButton = el('button', { class: 'step-button' }, icon('minus'))
  const plusButton = el('button', { class: 'step-button' }, icon('plus'))
  minusButton.addEventListener('click', () => handlers.onTempDelta(-1))
  plusButton.addEventListener('click', () => handlers.onTempDelta(1))
  const tempValue = el('span', { class: 'value' })
  const tempUnit = el('span', { class: 'unit' })
  const tempDisplay = el('div', { class: 'temp-display' }, tempValue, tempUnit)
  const ambient = el('div', { class: 'ambient' })

  const windFill = el('div', { class: 'fill' })
  const windLabel = el('span', {}, 'Wind Speed')
  const windValue = el('span', {})
  const windBar = el('div', { class: 'wind-bar' }, windFill, el('div', { class: 'label' }, windLabel, windValue))
  const playPauseButton = el('button', { class: 'play-pause-button' }, icon('pause'))
  playPauseButton.addEventListener('click', handlers.onPause)

  let dragging = false
  function levelFromEvent(clientX: number): number {
    const rect = windBar.getBoundingClientRect()
    const ratio = (clientX - rect.left) / rect.width
    return Math.round(Math.min(1, Math.max(0, ratio)) * WIND_MAX)
  }
  windBar.addEventListener('pointerdown', (e) => {
    if (windBar.classList.contains('disabled')) return
    dragging = true
    windBar.setPointerCapture(e.pointerId)
    handlers.onWindPreview(levelFromEvent(e.clientX))
  })
  windBar.addEventListener('pointermove', (e) => {
    if (!dragging) return
    handlers.onWindPreview(levelFromEvent(e.clientX))
  })
  function endDrag(): void {
    if (!dragging) return
    dragging = false
    handlers.onWindCommit()
  }
  windBar.addEventListener('pointerup', endDrag)
  windBar.addEventListener('pointercancel', endDrag)

  const climateCard = el(
    'div',
    { class: 'card climate-card' },
    el('div', { class: 'temp-row' }, minusButton, tempDisplay, plusButton),
    ambient,
    el('div', { class: 'wind-row' }, windBar, playPauseButton),
  )

  const modeButtons = new Map<Mode, HTMLButtonElement>()
  const modeRow = el(
    'div',
    { class: 'grid4' },
    ...MODES.map(({ mode, icon: name, label }) => {
      const button = el(
        'button',
        { class: 'picker-cell' },
        el('span', { class: 'bubble' }, icon(name)),
        el('span', { class: 'label' }, label),
      )
      button.addEventListener('click', () => handlers.onMode(mode))
      modeButtons.set(mode, button)
      return button
    }),
  )
  const optionButtons = new Map<ModeOption, HTMLButtonElement>()
  const optionRow = el(
    'div',
    { class: 'grid4' },
    ...OPTIONS.map(({ option, icon: name, label }) => {
      const button = el(
        'button',
        { class: 'picker-cell' },
        el('span', { class: 'bubble' }, icon(name)),
        el('span', { class: 'label' }, label),
      )
      button.addEventListener('click', () => handlers.onOption(option))
      optionButtons.set(option, button)
      return button
    }),
  )
  const modeCard = el('div', { class: 'card mode-card' }, modeRow, el('div', { class: 'divider' }), optionRow)

  const syncingCard = el(
    'div',
    { class: 'card syncing-card' },
    el('div', { class: 'spinner' }),
    el('span', {}, 'Waiting for the first status frame…'),
  )

  const cards = el('div', { class: 'cards' })
  root.append(topbar, cards)

  function render(state: AiceState | null, connected: boolean): void {
    root.dataset.mode = state?.mode ?? ''
    statusDot.classList.toggle('running', state?.isRunning === true)
    if (!connected) deviceNameEl.textContent = `${initialName} (reconnecting…)`

    cards.replaceChildren()
    if (state === null) {
      cards.append(syncingCard)
      powerButton.disabled = true
      powerButton.classList.remove('power-on')
      batteryEl.replaceChildren()
      return
    }
    cards.append(climateCard, modeCard)

    powerButton.disabled = false
    powerButton.classList.toggle('power-on', state.isPowered)

    batteryEl.className = 'battery' + (state.isCharging ? ' charging' : state.batteryPercent <= 15 ? ' low' : '')
    const fill = el('div', { class: 'battery-fill', style: `width:${state.batteryPercent}%` })
    const shell = state.isCharging
      ? el('div', { class: 'battery-shell' }, fill, chargeBolt())
      : el('div', { class: 'battery-shell' }, fill)
    batteryEl.replaceChildren(shell, el('span', {}, batteryRangeText(state.batteryPercent)))

    const canAdjust = state.isRunning && state.supportsTargetTemp
    minusButton.disabled = !canAdjust
    plusButton.disabled = !canAdjust
    tempDisplay.classList.toggle('dimmed', !canAdjust)
    if (state.supportsTargetTemp) {
      tempValue.textContent = String(formatTemp(state.unit, state.targetTempC))
      tempUnit.textContent = unitSuffix(state.unit)
    } else {
      tempValue.textContent = ''
      tempUnit.textContent = ''
    }
    ambient.textContent = `Ambient Temperature  ${formatTemp(state.unit, state.ambientTempC)}${unitSuffix(state.unit)}`

    const windEnabled = state.isRunning && state.supportsWind
    windBar.classList.toggle('disabled', !windEnabled)
    windFill.style.width = `${Math.min(100, Math.max(0, state.windLevel))}%`
    windValue.textContent = state.supportsWind ? String(state.windLevel) : '—'
    windLabel.textContent = state.supportsWind ? 'Wind Speed' : 'No wind in heating'

    playPauseButton.disabled = !state.isPowered
    playPauseButton.replaceChildren(icon(state.isPaused ? 'play' : 'pause'))

    // Mode and option changes, like wind and target temp, are refused while paused —
    // only power off and resume work then (confirmed live).
    const modeActive = state.isRunning
    for (const [mode, button] of modeButtons) {
      button.disabled = !modeActive
      button.classList.toggle('selected', state.mode === mode)
    }
    for (const [option, button] of optionButtons) {
      button.disabled = !modeActive
      button.classList.toggle('selected', state.activeOption === option)
    }
  }

  function setDeviceName(name: string): void {
    deviceNameEl.textContent = name
  }

  return { render, setDeviceName }
}
