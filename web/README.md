# AICE Lite — Web Bluetooth PWA

A Web Bluetooth controller for the **RANVOO AICE Lite** neck air conditioner
(`LH-Aice3 Lite`), implementing [`../PROTOCOL.md`](../PROTOCOL.md). Installable as a
PWA; runs fully offline once installed. Built from scratch — the `android/` app is
the reference implementation this ports, and `webbt/` (the two earlier prototype
pages) was not used as a starting point.

## Requirements

Web Bluetooth is implemented by **Chrome, Edge, and Opera** on desktop and Android.
**iOS Safari and Firefox do not support it at all** — the app detects this at startup
and shows an explanation instead of a Connect button that would throw.

## Develop

```sh
npm install
npm run dev        # http://localhost:5173
```

Web Bluetooth requires a secure context; `localhost` counts, `file://` does not.

## Test

```sh
npm test           # node --test — the protocol/store layer, no browser needed
```

34 tests cover the CRC codec against the five frames captured live in PROTOCOL.md
§4, the state model's field decoding, every command builder, and the store's
echo-adoption and wind-drag-throttle behavior.

## Build

```sh
npm run build       # tsc -b && vite build → dist/
npm run preview      # serve the production build locally
```

## How it maps to the protocol

| PROTOCOL.md | code |
|---|---|
| §3 frame, §4 CRC-16/ARC + `lhKey1` | `src/protocol/codec.ts` |
| §5 16-byte state payload, §5a flags, §5b options | `src/protocol/state.ts` |
| §5 command operations | `src/protocol/commands.ts` |
| §5 ⚑ `FF FF FF FF` command header, §6 echo adoption, wind-drag throttle | `src/store.ts` |
| §2 GATT map, write type, notifications, DIS firmware read | `src/ble/client.ts` |

Three things are easy to break, same as the Android app:

**Every command carries `payload[0..3] = FF FF FF FF`.** Stamped in `store.ts`'s
`enqueue`, never on the buffer the UI reads — so a device echo can never be mistaken
for a command. A frame that mirrors the device's own `02 00 00 00` header is ACKed
and silently ignored by the firmware.

**A command is the whole 16-byte state.** There are no per-command packets; the
buffer is seeded from the first `ff03` notification and never synthesized. Until
that first frame arrives, the UI shows "Waiting for the first status frame…" and the
controls stay inert.

**The device is authoritative.** Its echo replaces the local buffer even when it
contradicts what was sent (PID picks its own target and wind; Hot Pack/Cooling First
change the mode). The one exception is the wind level during a drag, and for 500ms
after release — a lagging echo must not yank the slider back under the user's finger.

## Debug panel

Settings → Debug. Shows the live 16-byte state buffer decoded byte-by-byte, a raw
TX/RX hex log, and arbitrary frame injection — the tool for closing PROTOCOL.md §9's
open questions (wind range, AI temp bounds, battery endpoints, bytes `[4]`/`[5]`).

## Not implemented

OTA firmware update (service `00000001…`) — never traced; the Settings row says so.
Cross-session auto-reconnect without a chooser prompt needs
`chrome://flags#enable-web-bluetooth-new-permissions-backend` and isn't relied on;
in-session reconnect after a dropped link does work (exponential backoff, no prompt).
