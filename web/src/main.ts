import './styles.css'
import { AiceStore } from './store.ts'
import { AiceBleClient } from './ble/client.ts'
import { TempUnit } from './protocol/state.ts'
import { mountDeviceScreen } from './ui/device.ts'
import { mountSettingsScreen } from './ui/settings.ts'
import { mountDebugScreen } from './ui/debug.ts'
import { el } from './ui/dom.ts'

type Route = 'device' | 'settings' | 'debug'

function currentRoute(): Route {
  const hash = window.location.hash.replace('#', '')
  return hash === 'settings' || hash === 'debug' ? hash : 'device'
}

function renderUnsupported(app: HTMLElement): void {
  app.replaceChildren(
    el(
      'div',
      { class: 'connect-screen' },
      el('h1', {}, 'PIDCE LITE'),
      el(
        'p',
        { class: 'unsupported-note' },
        'This browser does not support Web Bluetooth, so it cannot talk to the device. ' +
          'Try Chrome, Edge, or Opera on desktop or Android.',
      ),
    ),
  )
}

function main(): void {
  const app = document.querySelector<HTMLDivElement>('#app')
  if (app === null) throw new Error('#app not found')

  if (!AiceBleClient.isSupported) {
    renderUnsupported(app)
    return
  }

  const DEFAULT_NAME = 'LH-Aice3 Lite'
  const client = new AiceBleClient()
  const store = new AiceStore((payload) => client.send(payload))
  let firmware: string | null = null
  let connected = false

  const connectButton = el('button', { class: 'connect-button' }, 'Connect')
  const connectScreen = el(
    'div',
    { class: 'connect-screen' },
    el('h1', {}, 'PIDCE LITE'),
    el('p', { class: 'unsupported-note' }, 'Connect to your AICE Lite neck air conditioner.'),
    connectButton,
  )

  const deviceRoot = el('div', { class: 'screen' })
  const deviceScreen = mountDeviceScreen(deviceRoot, DEFAULT_NAME, {
    onPower: () => store.togglePower(),
    onPause: () => store.togglePause(),
    onTempDelta: (delta) => store.adjustTemp(delta),
    onWindPreview: (level) => store.previewWind(level),
    onWindCommit: () => store.commitWind(),
    onMode: (mode) => store.setMode(mode),
    onOption: (option) => store.toggleModeOption(option),
    onOpenSettings: () => {
      window.location.hash = 'settings'
    },
  })

  const settingsRoot = el('div', { class: 'screen' })
  const settingsScreen = mountSettingsScreen(settingsRoot, DEFAULT_NAME, {
    onBack: () => {
      window.location.hash = ''
    },
    onVoice: (on) => store.setVoice(on),
    onLight: (on) => store.setLight(on),
    onUnit: (unit: TempUnit) => store.setUnit(unit),
    onDisconnect: () => {
      client.disconnect()
      window.location.hash = ''
    },
    onOpenDebug: () => {
      window.location.hash = 'debug'
    },
  })

  const debugRoot = el('div', { class: 'screen' })
  const debugScreen = mountDebugScreen(debugRoot, {
    onBack: () => {
      window.location.hash = 'settings'
    },
    onSendRaw: (bytes) => client.sendRawFrame(bytes),
    onClearLog: () => {},
  })

  function renderRoute(): void {
    const route = currentRoute()
    const notConnectedYet = !connected && store.state === null
    app!.replaceChildren(
      notConnectedYet
        ? connectScreen
        : route === 'settings'
          ? settingsRoot
          : route === 'debug'
            ? debugRoot
            : deviceRoot,
    )
  }

  window.addEventListener('hashchange', renderRoute)

  client.setListener({
    onState: (state) => store.onDeviceFrame(state),
    onConnection: (conn) => {
      connected = conn.kind === 'connected'
      connectButton.textContent =
        conn.kind === 'connecting' ? 'Connecting…' : conn.kind === 'reconnecting' ? 'Reconnecting…' : 'Connect'
      connectButton.disabled = conn.kind === 'connecting' || conn.kind === 'reconnecting'
      renderRoute()
    },
    onFirmware: (fw) => {
      firmware = fw
      settingsScreen.render(store.state, firmware)
    },
    onDeviceName: (name) => {
      deviceScreen.setDeviceName(name)
      settingsScreen.setDeviceName(name)
    },
    onLog: (direction, bytes) => debugScreen.logFrame(direction, bytes),
  })

  store.subscribe((state) => {
    deviceScreen.render(state, connected)
    settingsScreen.render(state, firmware)
    debugScreen.render(state)
    renderRoute()
  })

  connectButton.addEventListener('click', () => {
    void client.connect().catch((error: unknown) => {
      console.error('Bluetooth connect failed', error)
    })
  })

  renderRoute()
}

main()
