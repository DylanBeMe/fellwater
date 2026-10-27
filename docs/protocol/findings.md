# Tunturi Routes 4.7.2 — reverse engineering notes

Target: `Tunturi+Routes_4.7.2_APKPure.xapk`
- base APK `com.kinomap.tunturiroutes.apk`, sha256 `fca11d6e875b0fb5c78532a6141db51a8d5c2ef514ebe3d5c1ad71cad92768ad`
- 5 DEX, 182,589 unique strings
- ABI split `config.arm64_v8a.apk` supplies native libs

## How their app picks a protocol `[APP]`

Two layers, and only one of them is in the APK:

1. **Protocol enum**, local. `com/kinomap/equipment/a.java` holds entries like
   `f("BLE_FTMS_ROWING_MACHINE", ordinal=69, technoId=72,
   sdkName="btleFitnessMachineRowingMachine", …, serviceUuid="00001826-…")`.
   That trailing field is a service UUID — inferred, not proven: it is populated
   only on the FTMS entries and null on every vendor-specific one, which matches a
   scan filter. Either way, generic FTMS rowers are matched by **advertised service,
   not by device name** — no model allowlist, nothing Tunturi-specific.
2. **Equipment catalog**, server-side. `com/kinomap/equipment/EquipmentType` and
   `Protocol` are Gson models (`@SerializedName` on `id`, `name`, `type`,
   `machine_type`, `protocols[]`, `levels[]`, `min_level`, `max_level`,
   `max_speed`), fetched via `GET equipments?protocols[]&manufacturers[]&
machineTypes[]&types[]` (`defpackage/mo8.java`) and registered with
   `POST me/register/equipment` carrying `protocol`, `machine_name`,
   `real_brand`, `real_model`.

That is why no `R60`/`R80` string exists in the APK: the Tunturi machine list is
server data. The client only needs to know the *protocol*.

Rowing protocol entries (name, technoId, internal sdk name):

| entry | technoId | sdk name |
|---|---|---|
| `BLE_FTMS_ROWING_MACHINE` | 72 | `btleFitnessMachineRowingMachine` |
| `BLE_FTMS_ROWING_MACHINE_SKI` | 120 | `btleFitnessMachineRowingMachineSki` |
| `BLE_PAFERS_ROWING_MACHINE` | 47 | `btlePafersRowingMachine` |
| `BH_ROWING_MACHINE` | 30 | `btPafersRowingMachine` |
| `KETTLER_ROWING_MACHINE` | 28 | `btKettlerRowingMachine` |
| `BLE_DELIGHTECH_ROWING_MACHINE` | 71 | `btleDelightechRowingMachine` |
| `BLE_HAMMER_ROWING_MACHINE` | 103 | `btleHammerRowingMachine` |
| `BLE_DOMYOS_ROWING_MACHINE` | 78 | `btleDomyosRowingMachine` |
| `BLE_EHEALTH_ROWING_MACHINE` | 89 | `btleEHealthRowingMachine` |
| `BLE_FITSHOW_ROWING_MACHINE` | 102 | `btleFitShowRowingMachine` |
| `BLE_FITNESS_DATA_ROWING_MACHINE` | 91 | `btleFitnessDataRowingMachine` |
| `BLE_FITNESS_DATA_ROWING_MODIFIED` | 116 | `btleFitnessDataModifiedRowingMachine` |
| `TFT_ROWING` | 113 | `embeddedChangYowRowingMachine` |
| `DYACO_EMBEDDED_ROWING` | 145 | `embeddedDyacoRowing` |
| `SHUA_ROWING` | 158 | `embeddedShuaRowing` |
| `WATER_ROWER_USB` | 140 | `usbWaterRowerRowingMachine` |
| `ICONSOLE_IROWING_BLE` | 93 | `btleIconsoleRowingMachine` |

## What it actually is

White-label **Kinomap** client. `KinomapTrainingApps:TunturiRoutes_release`,
package `com.kinomap.tunturiroutes`, activity `com.kinomap.tunturiroutes.MainActivity`.
Native Android (Compose M3), not hybrid. `assets/js/*` is Highcharts for in-app charts only.

Third-party native SDKs (`lib/arm64-v8a/`):
- `libcom_activetheoryinc_sdk_lib_BitGymCardio.so` — BitGym cardio video courses
- `libagora-rtc-sdk.so` + agora codec/AI libs — live A/V, used for multiplayer/RTC
- `libmapbox-maps.so`, `libmapbox-common.so` — maps
- `libsw_sdk.so`, `libnative-lib.so`, `libsigner.so`

Brand identity: `Tunturi|first-degree-fitness-fluid-rower|tests` is a
`brand|product-family|env` composite → Tunturi is First Degree Fitness, and the
rowing product line is their **fluid (water) rower**. No `R60`/`R80` model string
appears anywhere in the DEX.

## BLE layer — observations

BLE is a generic multi-vendor fitness SDK: `com.appdevice.domyos.ftms.*`
(AppDevice FTMS SDK, also used by Decathlon/Domyos). 98 distinct UUID literals.

### Bluetooth SIG profiles present
- `0x1826` Fitness Machine Service (FTMS) — **primary path**
- `0x2ad1` Rower Data (notify)  ← the one we need
- `0x2ad2` Indoor Bike Data, `0x2ad3` Training Status
- `0x2ad4`/`0x2ad5` Supported Speed/Inclination Range
- `0x2ad6` Supported Resistance Level Range (NOT rower data — corrected)
- `0x2ad9` FTMS Control Point, `0x2ada` Machine Status, `0x2acc` Machine Feature
- `0x2ad7` supported HR range, `0x2ad8` supported power range
- `0x180d`/`0x2a37` Heart Rate, `0x1814` RSC, `0x1816` CSC, `0x1818` Cycling Power
- `0x180a` Device Info (`0x2a23` sysid, `0x2a24` model, `0x2a26` firmware, `0x2a29` manufacturer)

### Rower data model the app exposes (field constants in DEX)
`ROWER_STROKE_RATE`, `ROWER_AVERAGE_STROKE_RATE`, `ROWER_STROKE_COUNT`,
`ROWER_INSTANTANEOUS_PACE`, `ROWER_AVERAGE_PACE`, `ROWER_BRAKE_LEVEL`,
`ROWER_MAX_BRAKE_LEVEL`, `ROWER_MIN_BRAKE_LEVEL`, `ROWER_SETUP`, `ROWER_STATUS`,
`ROWER_AUX_VALUES`, `ROWER_SPORT_DATA_FLAGS`, `ROWER_FTMS_FLAGS_POS/_END_POS`.
Also generic: `ELAPSED_TIME`, `DISTANCE_TOTAL`, `POWER`, `CALORIES`, `PACE`,
plus `DISTANCE_TARGET` / `CALORIES_TARGET` / `POWER_*` rolling averages.

### Vendor protocols compiled into the SDK (partial)
- `d44bc439-…` Concept2 PM5 (`BLE_CONCEPT_2_SKI`, `BLE_CONCEPT_2_OLDER_600`)
- `ce060000-…` Wahoo, `a026e005-…` Wahoo trainer control point
- `49535343-…` Microchip transparent UART
- `6e40fecx-…` Nordic UART-style
- `e94101xx-…`, `638a10xx-…`, `72d7000x-…`, `c463xxxx-…` other vendor channels
- `0xfee8/0xfee9`, `0xffe1/0xffe4`, `0xfff0-0xfff6` generic HM-10/CC254x serial pipes

### Rower equipment types enumerated
`BLE_FTMS_ROWING_MACHINE`, `BLE_FTMS_ROWING_MACHINE_SKI`,
`BLE_PAFERS_ROWING_MACHINE`, `BLE_DELIGHTECH_ROWING_MACHINE`,
`BLE_DOMYOS_ROWING_MACHINE`, `BLE_EHEALTH_ROWING_MACHINE`,
`BLE_FITSHOW_ROWING_MACHINE`, `BLE_HAMMER_ROWING_MACHINE`,
`BLE_FITNESS_DATA_ROWING_MACHINE` (+ `_MODIFIED`, `_XCX_102_MODIFIED`),
`WATERROWER` / `WATER_ROWER_USB`, `TFT_ROWING`, `SHUA_ROWING`, `WORUI_ROWING`,
`DYACO_EMBEDDED_ROWING`, `KETTLER_ROWING_MACHINE`, `AUGLETICS_ROWING`,
`MATRIX_*`, `TECHNOGYM_*`, `ICONSOLE_IROWING_BLE`, `MOCK_ROWING_MACHINE`.

Domyos name-prefix auto-detect literals: `domyos-bike-`, `domyos-eb`,
`domyos-el-`, `domyos-row-`, `domyos-tc-`, `domyos-wr`.

## Inferences (not yet observed)

1. A Tunturi rower will be picked up either as generic `BLE_FTMS_ROWING_MACHINE`
   (service-discovery driven, name-agnostic) or as one of the proprietary rower
   types above. Which one is **unresolved** — no model string in the APK and no
   device available for a GATT dump.
2. FTMS Rower Data (`0x2ad1`/`0x2ad6`) parsing is flags-driven and device
   independent, so the parser can be written before the device is identified.
3. Structured workouts use the local `CoachingInterval` model
   (`com.kinomap.api.helper.model.CoachingInterval`) and push targets over the
   FTMS Control Point (`0x2ad9`) — the standard target-setting procedure.

## Runtime observations so far `[DEVICE]`

Confirmed from a live scan on a real Android 16 tablet: the rower appears as
`i-Console+0082` at roughly −95 dBm, advertising **no service UUIDs**. Connecting
to it yields no FTMS service, matching the static expectation for
`ICONSOLE_IROWING_BLE`. See [iConsole protocol](iconsole.md).

The client app (this repo, not Tunturi's) has been run on a real Android 16
tablet: permissions, live BLE scan, RSSI ordering, connect-timeout path, and a
self-hosted FTMS GATT service all behave. No FTMS rower has been in range, so
Rower Data and Control Point have not yet crossed the air.

Their app was never executed — everything about it here is static.

## Unknowns / blockers

- **RESOLVED 2026-10-27:** the rower's console is `i-Console+0082` — an iConsole
  (BoQun) serial-over-BLE device, `ICONSOLE_IROWING_BLE`/technoId 93. It
  advertises no service UUIDs and has no FTMS service, so the FTMS client cannot
  drive it. Protocol work continues in [iConsole protocol](iconsole.md).
- Still open: its GATT table and a capture of `0xD1` notify frames (see
  [iConsole protocol](iconsole.md)).
- Multiplayer ("race against others") is `com.kinomap.*.multiplayer.*` over Agora
  and the Kinomap account API → server-side. Not reproducible client-side; own
  backend needed.
- PB ghost race is local-only and does not need any RE.
- App signing/verify path touched by `libsigner.so`; not investigated.
