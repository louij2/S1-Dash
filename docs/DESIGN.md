# S1 Dash design

## Head units it targets

The Audi A1 / S1 (8X) is usually upgraded in one of three ways, and S1 Dash
runs on all of them from one APK (minSdk 28, Android 9):

```
1. MITM box behind the original fold-down screen (Android 9, keeps the MMI look)
2. CarPlay / Android box such as a Carlinkit T-Box (Android 13), plugged into (1) or a stock unit
3. Replacement 10" / 13" Android screen, landscape or portrait, or a phone mirrored to the screen
```

The UI is laid out at 480 logical pixels tall in landscape (800 wide in
portrait) and scaled to fit. Wide screens get an extra column of engine data
beside the dials; tall screens stack it under them.

## How it is built

- **UI**: plain HTML / SVG / JS in `app/src/main/assets/ui/`, shown in a
  full-screen WebView. It also runs in a desktop browser with demo data
  (`index.html?screen=perf&theme=s1&still`). Kept to ES2017, because old
  head-unit WebViews can't run newer syntax such as `?.`.
- **Data**: Kotlin sources write values into `DataHub`; the UI polls a JSON
  snapshot ten times a second through a JavaScript bridge. Values older than
  3 s are dropped, so a lost source shows `--`, not a frozen number.

## Data sources

| Want | Source | Notes |
|---|---|---|
| Haldex mode, lock, target, clutch / module temp, voltage, temp protection | OpenHaldex-C6 HTTP API over its Wi-Fi AP (default `192.168.1.1`) | `GET /api/dashboard`. Temps and voltage need "Enable Live Diagnostics" on in OpenHaldex. |
| Change Haldex mode | OpenHaldex | `POST /api/mode {"mode": n}`. OpenHaldex does the actual control. |
| Haldex fault codes | OpenHaldex | `POST /api/dtc/read`, `GET /api/dtc`, `POST /api/dtc/clear {"confirm":"CLEAR"}` |
| Engine live data | Simos18 ECU, UDS `0x22` on `0x7E0` / `0x7E8` | Identifiers and scaling from SimosTools' mode-22 list, in `EcuPids.kt` |
| Boost | Derived | (MAP `0x39C0` − ambient pressure `0x13CA`) / 100, in bar |
| Clutch slip, estimated clutch heat | Derived, see below | Engine rpm `0xF40C`, speed `0x2033`, gear `0x210F`, torque `0x437C` |
| Engine fault codes | UDS `0x19` / `0x14` | Count via `19 01 08` (single frame); full list via `19 02 0D` needs a multi-frame-capable adapter |

### Dongles

- **SimosTools dongle** (ESP32 `BLE_TO_ISOTP` bridge): BLE service `abf0`,
  write `abf1`, notify `abf2`. Every packet is an 8-byte header (`0xF1`,
  flags, rx id LE, tx id LE, length LE) plus the raw UDS payload. The bridge
  does ISO-TP, so multi-frame replies work.
- **ELM327** over Bluetooth Classic SPP, by MAC address. `ElmProtocol.kt` copes
  with what clones do, and `FakeElm` in the tests simulates them. Bluetooth LE and
  Wi-Fi ELMs can't connect. Clone ELMs mangle
  multi-frame replies, so live data asks for one identifier at a time.

`PollPlan.kt` picks the values from how fast the adapter really answers. Over 70 ms
a request, it polls the core dials (MAP, rpm, speed, gear, torque, then ambient,
temps, knock, timing and volts in turn), and fetches the rest only for the Engine
data screen, a wide screen's tiles or the logger. How long a reading stays on
screen follows how long it takes to come round again, up to 60 s, and a dropped
connection clears the values at once.

Only one app can hold a dongle. "Open SimosTools" releases it and takes it back
when S1 Dash returns to the foreground.

### Clutch estimate (manual gearbox)

The manual box has no clutch temperature sensor and no control unit, and the ECU
has no clutch temperature value, so this is **an estimate, not a reading**. All of
it lives in `ClutchModel.kt`.

- **Gear ratios** are learned per gear as rpm per km/h: 3 s steady, light load,
  saved across restarts. Settings can forget them after a tyre change.
- **Slip %** = (rpm − ratio × speed) / rpm.
- **Clutch load %**: torque × slip speed integrated into energy, cooling with a
  240 s time constant. 100 % = 300 kJ, about two hard launches. Neither number
  is calibrated yet.
- **"Clutch slipping"** needs 4 % slip for 1 s in 3rd or above, over 40 km/h and
  120 Nm.

Limits to check on a drive, using the logged `rpm`, `ecuSpeed`, `speed`, `gear`
and `torque` columns:

- The ECU reports no clutch pedal or neutral. Nothing counts below 3 km/h, so a
  clutch held on a hill adds no heat; slip with the pedal down reads as slip.
- SimosTools' lists disagree on the gear offset (`x` or `x + 1`) and the speed
  scale. The slip maths doesn't care about either, but the gear and km/h gates do.
- If the ECU works the gear out from rpm and speed, real slip could read as a
  lower gear and hide itself.

### ECU files (CAL slots)

Five slots on the head unit, each holding one CAL file. **S1 Dash never writes
to the ECU; SimosTools does the flash** and has the final say on the file. All
of it lives in `CalRules.kt` (decisions) and `CalFlash.kt` (files and hand-off).

1. **Before:** reads the ECU and refuses unless the engine is off, the car is
   parked, the battery is at least 12 V, the SimosTools dongle is connected, the
   slot file is unchanged and the software number's family matches. No reading
   counts as not safe. Saves the fault codes and flash record, because SimosTools
   clears codes while flashing.
2. **Hand-off:** copies the file to Downloads, reads it back to prove the copy,
   lets go of the dongle and opens SimosTools. SimosTools has no way to start a
   flash from another app, so the user picks the file and confirms there.
3. **While flashing:** the saved state keeps the ECU source off, even if Android
   kills S1 Dash, until the user taps Flash finished.
4. **After:** ECU answers, software number unchanged, a new flash record
   (`0xF15B`), any new fault codes, and 15 s of steady idle. Nothing is cleared.

A live map switch is a different thing: it chooses between maps inside the
flashed CAL. A slot replaces the whole CAL.

### Android Auto

`S1CarService.kt` is a media browser service whose items are readings and Haldex
modes. Google's Unknown sources developer option covers media apps but not apps
built with the Car App Library, which must come from a trusted source such as Play.
So the proper route needs Play, and this one must never go there. It plays no audio.

The dongle is taken only when a list is opened, because Android Auto binds on every
connection. Only Android Auto and S1 Dash itself may browse. Not yet tried on a
phone or in the Desktop Head Unit: check the icon, list refresh every 2 s, whether
it disturbs music controls, background Bluetooth, and OpenHaldex over wireless.

## Rules

1. **Read-only on the car**, apart from two things the user asks for on
   screen: changing the OpenHaldex mode, and clearing fault codes (always
   behind a confirm). No coding, adaptations or flashing.
2. **OpenHaldex is the only Haldex controller.** S1 Dash asks it to change
   mode and never talks to the Haldex itself.
3. **Polling must not keep the car awake.** The head unit sleeps with the
   ignition, and sources back off when they get no reply.

## Look and themes

OEM+: the Performance screen copies the 8X instrument cluster, and the menus
copy the RMC / MMI. A theme is a palette (dial faces, ticks, numerals,
needle, red zone, badge), so a new theme is data, not new layout code.
Themes so far: **S1 cluster** (brushed silver, S1 badge), **A1 quattro** (red
tacho face, black speedo face) and **MMI black**.

The original mockup is in [mockup/](mockup/).

## Ideas for later

- Dragy GPS over BLE: a performance timer (0-100, 100-200, 1/4 mile) and GPS
  speed in the logger.
- Import VCDS scan logs and measuring-value CSVs; use Carly / Carista
  adapters if they speak ELM327; launch shortcuts for those apps.
- A listen-only CAN tap on the head unit's harness, to read broadcast data
  without a dongle.
- Follow the head unit's day / night signal for the theme.
