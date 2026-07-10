# PIDCE Lite (AICE Lite — Android controller)

A native Kotlin/Compose controller for the **RANVOO AICE Lite** neck air conditioner
(`LH-Aice3 Lite`), implementing [`../PROTOCOL.md`](../PROTOCOL.md).

## Build & install

The Android SDK must have platform `android-36`. No `local.properties` is needed if
`$ANDROID_HOME` is set.

```sh
./gradlew :app:testDebugUnitTest    # protocol tests, no device needed
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires `minSdk 26`. On Android 12+ the app asks for `BLUETOOTH_SCAN` (flagged
`neverForLocation`) and `BLUETOOTH_CONNECT`; below that, for `ACCESS_FINE_LOCATION`,
because pre-12 Android classifies a BLE scan as a location capability.

## How it maps to the protocol

| PROTOCOL.md | code |
|---|---|
| §3 frame, §4 CRC-16/ARC + `lhKey1` | `protocol/AiceCodec.kt` |
| §5 16-byte state payload, §5a flags, §5b options | `protocol/AiceState.kt` |
| §5 command operations | `protocol.Commands` |
| §2 GATT map, Write Command, notifications | `ble/AiceBleClient.kt` |

Three things are easy to break and worth knowing before you touch this code:

**A command is the whole state.** There are no per-command packets. Every command
copies the last 16-byte buffer received from the device, edits one byte, and resends
all 16. The buffer is seeded from the first `ff03` notification and never synthesized
— bytes 4 and 5 are still unidentified constants and are echoed back verbatim. Until
that first notification arrives the UI shows "Waiting for the first status frame…" and
the controls stay inert.

**GATT operations must not overlap.** Android silently drops a write issued while
another is in flight, so every operation goes through a mutex-guarded queue
(`AiceBleClient`'s `opMutex`) that waits for its callback. Wind-slider drags are
throttled to one frame per 120 ms.

**The device is authoritative, except mid-drag.** `AiceViewModel.mergeFromDevice`
adopts every incoming device frame as-is — including a PID-picked target temp that
doesn't match what was sent. The one exception is the wind level: while
`windDragging` is set (during the drag and for 500 ms after the finger lifts), the
local wind byte is kept and the rest of the incoming frame is still adopted. Without
that grace period, a lagging echo yanks the slider back under your finger.

## Not implemented

OTA firmware update. The OTA service (`00000001…`) is documented in §2 but the update
flow was never traced, and the Settings row says so.
