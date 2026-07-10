# RANVOO AICE Lite — BLE Protocol Specification

Reverse-engineered from **RANVOO / "Metaura" app v1.3.19** (Flutter, Dart 3.5.0, `flutter_reactive_ble`)
and **verified against a live nRF Connect capture** of a **LH-Aice3 Lite** device. Where static analysis and the live capture disagree, the
**capture is authoritative** (this happened with the GATT UUIDs — see §2).

The reference controller is the **Android app in `android/`** (Kotlin + Compose; see
`android/README.md`).

---

## 1. Device & stack

| item | value | source |
|---|---|---|
| Product | RANVOO AICE Lite neck air conditioner | app |
| BLE advertised name | **`LH-Aice3 Lite`** (prefix `LH-`) | nRF capture (2A00) |
| Device address (sample) | Redacted | nRF capture |
| Model Number | `BLE-03` | 2A24 |
| Manufacturer | `LANHE` (蓝禾) | 2A29 |
| Firmware rev | `000.000.001` (app reports `eceSoftVersion`) | 2A26 |
| Hardware rev | `003.00c.001` | 2A27 |
| SoC | SYD8811-class ARM Cortex-M (OTA blob `SYD8811_BLE_aice1_0.1.23.bin`) | assets + `file(1)` |
| App architecture | Flutter; AICE3 Lite uses **`flutter_reactive_ble`** (Dart) directly — NOT the ECELL native plugin (that is the separate "AICE3 full/MCU" device) | blutter |

> The APK also ships an ECELL BLE SDK (`com.ecell.bluetooth.*`, a Jieli/RCSP-style
> stack, sync byte `0xCB`) used by the *AICE3 (full)* device. **The AICE3 Lite does
> not use it.** This document covers the **AICE3 Lite** only. The two devices share
> the `LH-` name prefix and app UI but have different GATT services and framing.

---

## 2. GATT map  *(from live capture — authoritative)*

The discovered services on `LH-Aice3 Lite`:

```
Generic Access          0x1800   (2A00 name, 2A01 appearance, 2A04 PPCP)
Generic Attribute       0x1801   (2A05 Service Changed [I])
Device Information      0x180A   (2A23..2A29, 2A50)
Custom control service  0000ff00-0000-1000-8000-00805f9b34fb
   ├─ ff01  [W]   write
   ├─ ff02  [W]   write
   ├─ ff03  [N]   notify (status/telemetry)  ← value handle 0x0023, CCCD 0x0024
   └─ ff04  [W]   write  ← CONTROL WRITE (value handle 0x0026; app writes here)
OTA service             00000001-0000-1000-8000-00805f9b34fb
   ├─ 0002  [W]   write  (OTA data, write-with-response)
   └─ 0003  [N]   notify (OTA progress)
```
Observed ATT handles (from `btsnooz_hci.log`): notify `ff03` = `0x0023`, control write
`ff04` = `0x0026`. The app writes the 20-byte command frame to `0x0026` via Write Command.

| role | UUID |
|---|---|
| **Primary service (control)** | `0000ff00-0000-1000-8000-00805f9b34fb` |
| **Write characteristic (control)** | **`0000ff04-0000-1000-8000-00805f9b34fb`** (confirmed — see note) (fallbacks: `ff01`, `ff02`) |
| **Notify characteristic** | `0000ff03-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` (write `01 00` to enable notifications) |
| OTA service / write / notify | `00000001…` / `00000002…` / `00000003…` |

**Write type — either a Write Command (`0x52`) or a Write Request (`0x12`) works,**
provided the frame carries the `FF FF FF FF` command header (§5). *(Corrected
2026-07-10 — this reverses the single most-repeated claim in earlier drafts.)*

- The official app writes `ff04` with a **Write Command `0x52`** (HCI snoop,
  `btsnooz_hci.log`; `writeCharacteristicWithoutResponse` in `ble_controller.dart`).
  That is what it *does*, but it is **not a requirement**.
- **A Write Request (`0x12`, with-response) also drives the device** — as long as the
  payload has the correct header. This is what **Chrome / Web Bluetooth** emits:
  `ff04` advertises only `write` (not `write-without-response`), so Chrome uses a
  with-response write, and the device obeys it. `webbt/aice-app.html` drives the
  device this way — **Web Bluetooth is viable.**
- **The earlier "only `0x52` works; `0x12` is ACKed and ignored" result was wrong.**
  It was an artifact of the payload-header bug (§5): those ignored `0x12` writes
  mirrored the device's own `02/01 00 00 00` status header instead of `FF FF FF FF`,
  so the firmware discarded them **regardless of opcode**. With the right header,
  both opcodes work. (Native `bleak`/L2CAP paths in `native/` work too — again, it
  was never the opcode.)
- **No bonding / no encryption** — proven from the HCI snoop (SMP=0,
  Encryption-Change=0). The device drives on a bare, unencrypted link on every
  platform; there is nothing to gain from pairing.

OTA writes are write-with-response.

**Android note.** Both `BluetoothGatt.writeCharacteristic(char, value,
WRITE_TYPE_NO_RESPONSE)` (API 33+) and the legacy `setWriteType` +
`writeCharacteristic(char)` path emit `0x52` and drive the device (verified on
hardware); the `android/` app uses the modern one. No bonding, no `requestMtu`
(20-byte frames fit the default MTU 23). Enable notifications on `ff03` (write
`01 00` to CCCD `0x0024`); seed the command buffer from the first `ff03` notification,
then edit one byte per command, set `payload[0..3]=FF FF FF FF` (§5), recompute the
CRC, and write to `ff04`.

**Which characteristic (confirmed `ff04`, two independent ways).** Control frames go to
**`ff04`**, not `ff01`.

1. *Static.* `BleController._initQualifiedCharacteristic` (`ble_controller.dart`): the
   AICE3 Lite hits the *default* branch (it is not `LH-FG2A`/`LH-Aice1`/`LH-Aice2`), which
   builds three `QualifiedCharacteristic`s on service `ff00` — write=`ff04`, notify=`ff03`,
   plus `ff02` — and stores the **write** one in the per-device map entry's `field_23`.
   `BleController.writeToDevice` writes the command frame to that `entry.field_23` = `ff04`.

2. *Live HCI capture* (`btsnooz_hci.log`, the authoritative app→device snoop). The app's
   control write decodes as:
   ```
   02 41 00 | 1b 00 | 17 00 04 00 | 52 | 26 00 | 00 CC 3F …   (ACL→L2CAP CID 4 (ATT))
                                     │     └ handle 0x0026 = ff04 value
                                     └ ATT opcode 0x52 = Write Command (WITHOUT response)
   ```
   L2CAP length `0x17`=23 = 1 (op) + 2 (handle) + **20-byte value** = the full
   `[00 CC crcL crcH payload×16]` frame (btsnooz truncates the stored copy). Notifications
   arrive on handle **`0x0023` = ff03** (`00 CC 39 …`). Handle `0x0026` = `ff04` by GATT
   adjacency: ff03 value `0x0023` → CCCD `0x0024` → ff04 decl `0x0025` → ff04 value `0x0026`.

Writing to `ff01` gets ACK'd (it is writable) but the firmware's command handler isn't
there, so the device silently ignores it — "command does nothing". Control goes to
`ff04` only. (`ff04` advertises only the `write` property — no `write-without-response`
bit — but that does not matter: both Android's forced no-response write and Chrome's
with-response write reach the handler; see the write-type note above.)

> ⚠ **UUID discrepancy (static vs live).** The decompiled Dart
> (`BleController._initQualifiedCharacteristic`) parses `0000fff1`/`0000fff2`
> (note the extra `f`) for some device variants. The **real AICE3 Lite exposes
> `ff00`/`ff01`/`ff03`** (no extra `f`). The Web Bluetooth controller therefore
> uses the **captured** `ff00`/`ff01`/`ff03`. If a different hardware revision
> advertises `fff1`/`fff2`, switch the controller to those (a dropdown is provided).

---

## 3. Frame format  *(verified — same layout both directions)*

Every frame is **20 bytes**:

```
 offset  0    1    2     3     4 … 19
        ┌────┬────┬──────┬──────┬─────────────────────┐
        │ 00 │ CC │ crcL │ crcH │  payload (16 bytes) │
        └────┴────┴──────┴──────┴─────────────────────┘
         │    │     └── CRC-16/ARC(payload ++ lhKey1), little-endian
         │    └── sync byte 0xCC
         └── fixed header byte 0x00
```

- Bytes `[0]=0x00`, `[1]=0xCC` are fixed (decoded from the app builder:
  AllocateArray(2) → `[0, 0xCC]`, then `++ [crcLo,crcHi] ++ payload`).
- `[2]=crcL`, `[3]=crcH` = CRC-16/ARC of **(payload ++ lhKey1)**, stored **little-endian**.
- `[4..19]` = the 16-byte **state payload** (full-state-sync; see §5).

This layout was confirmed two ways: (a) static decode of `_writeDataToDevice`
(`aice3_lite_device_ctrl.dart`), and (b) **all 5 captured notify packets**
reproduce their `[2..3]` bytes exactly when CRC is computed as specified (§4).

---

## 4. Checksum  *(verified against capture)*

**CRC-16/ARC** (CRC-16 with reflected polynomial 0xA001, init 0).

| param | value |
|---|---|
| width | 16 |
| poly | 0x8005 (reflected table 0xA001) |
| init | **0x0000** |
| refin / refout | true / true |
| xorout | 0x0000 |
| check ("123456789") | 0xBB3D |

**Input = `payload(16 bytes) ++ lhKey1(16 bytes)`** (32 bytes total). `lhKey1` is
a fixed suffix/"key" (weak MAC) decoded from `global_functions.dart`:

```
lhKey1 = 00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF
```

> This keyed suffix is **essential** — a plain CRC over the 16-byte payload does
> **not** match the device. The brute-force that confirmed this is reproduced in
> the controller's self-test.

Reference implementation:

```js
const T = (() => { const t = new Uint16Array(256);
  for (let i = 0; i < 256; i++) { let c = i;
    for (let j = 0; j < 8; j++) c = (c & 1) ? ((c >> 1) ^ 0xA001) : (c >> 1);
    t[i] = c; } return t; })();
function crc16arc(bytes) {                 // CRC-16/ARC, init 0
  let c = 0;
  for (const b of bytes) c = T[(c ^ b) & 0xff] ^ (c >> 8);
  return c & 0xffff;                        // caller writes lo, then hi
}
// frame CRC = crc16arc( payload16.concat(LH_KEY1_16) )  → [lo, hi]
```

### Capture verification (5 status notifications, char `ff03`)

| # | temp°C | level | full frame (hex) | crc bytes | crc recompute ✓ |
|---|-------|-------|------------------|-----------|---|
| 1 | 22 | 41 | `00CC 2976 0200000000 010201062201 1C 1629 0101` | `29 76` | ✓ |
| 2 | 26 | 80 | `00CC BCED 0200000000 010201062201 1C 1A50 0101` | `BC ED` | ✓ |
| 3 | 30 | 60 | `00CC C030 0200000000 010201062201 1C 1E3C 0101` | `C0 30` | ✓ |
| 4 | 21 | 90 | `00CC 73CA 0200000000 010201062201 1C 155A 0101` | `73 CA` | ✓ |
| 5 | 16 |100 | `00CC 9A72 0200000000 010201062201 1C 1064 0101` | `9A 72` | ✓ |

(`temp` and `level` are payload bytes [12] and [13]; see §5.)

---

## 5. Payload — 16-byte state buffer  *(full-state-sync)*

The device and app exchange the **entire 16-byte state** on every change. Each
"command" edits one or two bytes of the buffer and re-sends the whole frame; the
device replies with the same structure (echo/updated state).

> ### ⚑ The 4-byte header `payload[0..3]` is the command/status discriminator
> A command (app→device) and a status report (device→app) share the same 16-byte
> layout but are told apart by their first four bytes:
> ```
>   command (app→device):  payload[0..3] = FF FF FF FF
>   status  (device→app):  payload[0..3] = 02 00 00 00
> ```
> **A command MUST carry `FF FF FF FF`.** A frame that mirrors the device's own
> `02 00 00 00` header back is accepted at the ATT layer (Write Command `0x52`, no
> error) and then **silently ignored** — no state change, no echo. This is the one
> fact that gates a working controller, and it is the thing this doc got wrong the
> longest.
>
> **Verified live (2026-07-10)** by instrumenting the official app: a frida-gadget
> build hooking the app's own `writeToDevice` (`flutter : data:` log) shows every
> written frame is `00 CC <crc> FF FF FF FF …`, and the device echoes
> `00 CC <crc> 02 00 00 00 …`. The `native/aice_probe.py` `p[0]=0x02` and the
> earlier "`[0]=0x02` command / `[0]=0x01` status" theories were **both wrong** —
> the header is four bytes and the command value is `0xFF`, not `0x02`. In the
> decompiled builder this is `_writeDataToDevice`'s `field_7b` branch, which writes
> `field_1b[0..3]=0xFF` before every command (mislabelled "light/voice config only"
> in an earlier §5c).

Payload byte map (offset within the 16-byte payload = wire byte − 4):

| idx | field | meaning | verified values / notes |
|-----|-------|---------|--------------------------|
| **0–3** | **frame header** | **command vs status discriminator** | **command = `FF FF FF FF`; status/echo = `02 00 00 00`.** Set all four to `0xFF` on every write (see box above). |
| 4 | — | | `0x00` |
| 5 | — | | `0x01` |
| **6** | **power** | **master power state** | **`1`=paused (idle), `2`=running, `3`=off, `4`=turn-on.** Pause writes `1`, resume writes `2`, full off writes `3` (all captured live). |
| **7** | **chargeStatus** | **charge state** | **`1`=on battery, `3`=charging** (steady); `2` is a brief transient seen while the plug is inserted. App treats `≥2` as "plugged/charging". Confirmed live: `3` charging vs `1` unplugged, both at the same battery level. |
| **8** | **battery** | **battery gauge** (read-only) | **coarse — one count per bar on a 4-bar meter**: `2`=empty … `6`=full (observed `4`=2 bars, `5`=3 bars; stable for hours, so it quantizes to bars, not a fine %). App shows `bars = raw−2` and a per-bar % (0/25/50/75/100). Unchanged whether charging or not. Was misfiled as a "constant 0x05/0x06" earlier. |
| **9** | **flags** | **light/voice toggles** — see §5a | capture: `0x22` = light-off + voice-off |
| **10** | **mode** | **operating mode** — send ≠ echo for AI | **SEND (app→device): `1`=cooling, `2`=heating, `3`=wind/fan, `4`=AI.** **ECHO (device→app): `1`/`2`/`3` same, but AI echoes `6`.** The AI *button* writes `4`; the device then reports `6`. AI = PID auto-regulation; the `android/` app labels it **"PID Mode."** (captured live) |
| **11** | **ambientTemp** | **ambient temperature** (read-only telemetry) | integer °C; `environmentTemperature` (`Aice3ControlModel`) |
| **12** | **temp** | **target temperature** — always °C on the wire | integer °C. **Cooling 16–30, Heating 40–50** (operator-confirmed); **Wind/Fan has no target** (control hidden); AI bounds unverified. The value does **not** change with the display unit (byte 15) — see below. **In AI/PID mode the device picks its own target** (and wind) on entry and echoes those back, ignoring what you sent — a controller must adopt the echo, not hold its optimistic value. |
| **13** | **windLevel** | **fan / wind level** | integer 0–100. **No wind in Heating** — that mode ignores it. |
| **14** | **modeOpt** | **mode option** (exclusive) — see §5b | **`1`=Silent, `2`=Hot Pack, `3`=Cooling First, `4`=Low Power, `0xFF`=none** (captured live) |
| **15** | **tempUnit** | **display unit** | **`0x01`=°C, `0x02`=°F** (captured live). **Display-only** — toggling it leaves `payload[12]` unchanged (verified: target stayed `25` across a °C→°F toggle). Convert for display: `°F = round(°C×9/5)+32`. |

> Bytes marked `—` were constant across every capture (`[4]=0x00`, `[5]=0x01`);
> their semantics (LED, timers, error flags?) still need a broader capture. The
> `battery`/`rechargeStatus` fields in the decompiled `Aice3ControlModel` belong to
> the AICE3 **full/MCU** device (a different byte layout); the Lite's battery and
> charge bytes are `[8]`/`[7]`, found empirically (above).

### Command operations (app → device)

Every command: mirror the last device buffer, **set `buf[0..3]=FF FF FF FF`**, edit
the byte(s) below, recompute the CRC over the edited payload, write to `ff04`.

| function | edits | value |
|---|---|---|
| **Power on** | `buf[6]` | `2` if already running, else `4` (turn-on) |
| **Power off** | `buf[6]` | `3` |
| **Pause** | `buf[6]` | `1` (paused/idle) |
| **Resume** | `buf[6]` | `2` (running) |
| **Set mode** | `buf[10]` | **`1`=cooling, `2`=heating, `3`=wind, `4`=AI** (send codes; device echoes AI as `6`). Refused only while off (`buf[6]==3`). |
| **Temp up / down** | `buf[12]` | `± 1`, clamped to the mode range. Requires running; fan mode has no target temp. |
| **Wind level** | `buf[13]` | `0–100`, from the drag ratio |
| **Mode option** | `buf[14]` | `1`=Silent, `2`=Hot Pack, `3`=Cooling First, `4`=Low Power; **`0xFF` to clear** (§5b) |

So, e.g., to **turn cooling on at 22 °C, wind 41**, take the current state, set
`buf[0..3]=FF FF FF FF, buf[6]=2, buf[10]=1, buf[12]=22, buf[13]=41`, wrap in the §3
frame, and write to **`ff04`** as a Write Command.

> ⚠ A command that keeps the device's own `02 00 00 00` header (instead of
> `FF FF FF FF`) is ACKed by the stack and ignored by the firmware — which looks
> exactly like a broken write path. If commands do nothing, check `payload[0..3]`
> **before** you go debugging ATT opcodes, characteristics, or MTU.

### 5a. Payload[9] — flags byte (light + sound toggles)  *(confirmed live)*

`payload[9]` holds the **light and "voice" toggle flags**, sent in the **ordinary
16-byte state frame** with the `FF` header — *not* a separate config frame (§5c). On
the LH-Aice3 Lite the "voice" flag actually controls the **button/beep sound** (no
speech on this model), so the `android/` app labels it **"Sound."** Captured values:

| high nibble (light) | low nibble (sound) | `payload[9]` |
|---|---|---|
| off (`2`) | off (`2`) | `0x22` (baseline) |
| off (`2`) | **on (`1`)** | `0x21` |
| **on (`1`)** | off (`2`) | `0x12` |
| **on (`1`)** | **on (`1`)** | `0x11` |

So light on → high nibble `1`, off → `2`; sound on → low nibble `1`, off → `2`. The
decompiled settings controller (`changeLight @0x7eb424` / `changeVoice`) matches:

| bits | field | values | method |
|---|---|---|---|
| 4 (0x10) | **light ON** | set → light on | `changeLight`: ON → `(buf[9] & 0x0F) \| 0x10` |
| 5 (0x20) | **light OFF** | set → light off | `changeLight`: OFF → `(buf[9] & 0x0F) \| 0x20` |
| 0 (0x01) | **voice ON** | set → voice prompt on | `changeVoice`: ON → `(buf[9] & 0xF0) \| 0x01` |
| 1 (0x02) | **voice OFF** | set → voice prompt off | `changeVoice`: OFF → `(buf[9] & 0xF0) \| 0x02` |

> **Wind level is NOT in byte[9]** — it's in **byte[13]** (see payload table).
> The earlier nibble-encoding hypothesis was wrong: `handleWindSpeechOnTapUp
> @0x7d14c8` writes `buf[13]` (`strb w0,[x3,#0x24]`, 0x24=0x17+13), not `buf[9]`.
> The capture values 41/60/80/90/100 in byte[13] are the wind/fan levels.
>
Capture evidence: `payload[9]=0x22` = `0x20` (light-off) `| 0x02` (voice-off) in the
ordinary state frame. The earlier "separate 32-byte config buffer" reading is
retracted — see §5c.

### 5b. Payload[14] — mode options selector  *(corrected from live capture)*

`payload[14]` selects one of four **mutually exclusive** options (they share the
single byte). Available whenever the device is powered (running or paused);
refused only when off.

| option | i18n key | `buf[14]` value | notes |
|---|---|---|---|
| **Silent** | `muteMode` (静音模式) | **`0x01`** | standalone — does not change the mode |
| **Hot pack** | `stupeMode` (热敷模式) | **`0x02`** | device switches to **heating** on its own |
| **Cooling first** | `coolingMode` (降温优先) | **`0x03`** | device switches to **cooling** on its own |
| **Low power** | `enduranceMode` (续航优先) | **`0x04`** | works in any mode |
| *(none)* | — | **`0xFF`** | clears the active option |

To turn an option on, write its value; to turn it off, write `0xFF`. Only one is
active at a time.

> ⚠ **The old encoding here was wrong** (`ON=0xFE`, `OFF=0x02/0x04/0x06/0x08`) and
> actively harmful: writing Silent's supposed off-code `0x02` is really **Hot Pack**,
> so "turn Silent off" made the device start **heating**. The values above are
> captured from the official app's own `data:` log (2026-07-10): e.g. Silent on →
> `…,1,26,23,24,1,1` (`buf[14]=1`); Low Power → `…,1,26,16,100,4,1` (`buf[14]=4`);
> Silent off in heating → `…,2,26,40,0,255,1` (`buf[14]=0xFF`).

### 5c. Voice / light — no separate config frame  *(resolved)*

Light and sound ride the **ordinary 16-byte state frame** — edit `buf[9]` (§5a) and
send it with the `FF FF FF FF` header like any other command. Captured live: e.g.
sound-on is `00 CC … FF FF FF FF … 0x21 …`, a normal 20-byte state frame. The old
claim of a **separate 32-byte config frame** (`field_37`) was a misread of the
`0xFF`-header (`field_7b`) branch — the header that prefixes *every* command (§5, ⚑),
not a distinct packet. **Retracted and confirmed:** there is no config frame.

---

## 6. Notification parsing (device → app)

Subscribe to `ff03` (write `01 00` to its CCCD). Each notification is a 20-byte
frame per §3. To parse:

1. Check `frame[0]==0x00`, `frame[1]==0xCC`.
2. *(optional)* verify `crc16arc(payload16 ++ lhKey1) == frame[2] | frame[3]<<8`.
3. Read fields from the 16-byte payload per §5 (power@[6], mode@[10], temp@[12], etc.).
   Device frames carry the status header `payload[0..3] = 02 00 00 00`; remember AI
   echoes as mode `6` (you send `4`). Mirror the payload verbatim when building the
   next command, changing only `payload[0..3]` to `FF FF FF FF` plus your one edit.

> The app's `fromUint8List` does **not** validate the CRC itself; validation (if
> any) is upstream. Parsing by position is sufficient.

**Controller behaviour — the device is authoritative.** Its echo is the source of
truth and may differ from what you sent: PID/AI picks its own target and wind
(§5 byte 12); Hot Pack/Cooling First change the mode; temperatures clamp to the
mode range. A controller must **adopt each echo**, not cling to its optimistic
value — otherwise it desyncs (e.g. shows your 22 °C while the device sits at the
18 °C PID chose). The *only* value worth holding locally is one the user is
actively dragging (the wind slider), and only until the drag settles, so a lagging
echo doesn't yank it back. The device sends a frame whenever state changes,
including charge-plug and battery-level events, but is otherwise quiet — don't rely
on periodic polls.

---

## 8. Open questions / unverified

**Resolved since the first draft** (via the `android/` app + a frida-instrumented
build of the official app, 2026-07-10):

- **`FF FF FF FF` command header** — the command/status discriminator (§5 ⚑); the
  single fact that gates a working controller.
- **Write path** — `ff04` accepts *both* a Write Command (`0x52`) and a Write Request
  (`0x12`) once the header is right, so **Web Bluetooth drives the device** (§2, §7);
  the "monitor only" verdict is retracted.
- **Mode** send codes `1/2/3/4`, AI echoes `6` (§5 byte 10); AI = PID.
- **Mode options** `1/2/3/4`/`0xFF`, mutually exclusive (§5b).
- **Power/pause** `1`=paused, `2`=running, `3`=off, `4`=turn-on (§5 byte 6).
- **Temp** cooling 16–30, heating 40–50, wind = none; wire is always °C; PID picks its
  own target (§5 byte 12). **Unit** `[15]` display-only, `1`=°C/`2`=°F.
- **Battery** `[8]` (coarse, `bars=raw−2`) + **charge** `[7]` (`1`/`3`) (§5).
- **Light/Sound** at `[9]`, in the state frame — no config frame (§5a/§5c).
- **Controller rule:** the device is authoritative; adopt its echoes (§6).

The **Android app in `android/` is the reference implementation.**

Still open:

1. **Wind level range/max** (`buf[13]`). Values 0–100 seen; whether `0` = fan off
   and the exact min need confirmation.
2. **AI target-temp range.** Cooling (16–30) and heating (40–50) confirmed; AI's
   bounds are not — the app kept AI at 16–30 as a placeholder.
3. ~~Voice/light frame~~ **Resolved** — light/sound ride the ordinary state frame
   at `buf[9]`; nibble codes captured (§5a). No config frame (§5c).
4. **Battery gauge endpoints.** `payload[8]` is the gauge (`4`=2 bars, `5`=3 bars →
   `bars = raw−2`) and `payload[7]` is the charge state (`1`=battery, `3`=charging) —
   confirmed live. Empty (`2`) and full (`6`) are extrapolated from two points; confirm
   at 1 bar / full.
5. **Unused payload bytes** (`4`,`5`) — still constant (`0x00`, `0x01`); likely LED/timers.
6. **`fff1`/`fff2` in app code** — appears stale for this hardware; investigate if a
   second AICE3-Lite revision exists.
