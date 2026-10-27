# FTMS rower protocol reference

Source of truth for the SIG-defined parts: Bluetooth SIG *Fitness Machine Service*
specification. Everything marked `[SIG]` is standard and device-independent.
Everything marked `[TBD]` must be validated against a real R60/R80 GATT dump —
we have no device on hand yet.

No part of this doc comes from decompiled Tunturi/Kinomap code unless marked
`[APP]`. `[APP]` rows are observations from `jadx_out/`.

## Service map `[SIG]`

Service `0x1826` Fitness Machine Service:

| Char | Name | Props | Use |
|---|---|---|---|
| `0x2acc` | Fitness Machine Feature | read | capability flags, read once after connect |
| `0x2ad1` | Rower Data | notify | live metrics, ~1 Hz |
| `0x2ad9` | Fitness Machine Control Point | write, indicate | request control, set targets |
| `0x2ada` | Fitness Machine Status | indicate | machine-side state changes |
| `0x2ad6` | Supported Resistance Level Range | read | min/max/increment for resistance |
| `0x2ad7` | Supported Heart Rate Range | read | min/max/increment bpm |
| `0x2ad8` | Supported Power Range | read | min/max/increment W |
| `0x2ad4`/`0x2ad5` | Supported Speed / Inclination Range | read | treadmills only, ignore |

CCCD `0x2902` must be written `0x0001` (notify) / `0x0002` (indicate) to enable
`0x2ad1`, `0x2ad9` and `0x2ada` before any data arrives. MTU 23 is enough:
Rower Data is ~20 bytes worst case.

## Rower Data `0x2ad1` `[SIG]`

Little-endian. `uint16 flags`, then only the fields whose flag bit is set, in
this exact order. Fields are contiguous — you cannot index into the frame
without walking the flags.

| bit | field | type | unit / resolution |
|---|---|---|---|
| 0 | More Data | — | 0 = last frame of this record |
| 1 | Average Stroke Rate Present | — | |
| 2 | Total Distance Present | — | |
| 3 | Instantaneous Pace Present | — | |
| 4 | Average Pace Present | — | |
| 5 | Instantaneous Power Present | — | |
| 6 | Average Power Present | — | |
| 7 | Resistance Level Present | — | |
| 8 | Total Energy Present | — | |
| 9 | Energy Per Hour Present | — | |
| 10 | Energy Per Minute Present | — | |
| 11 | Heart Rate Present | — | |
| 12 | Metabolic Equivalent Present | — | |
| 13 | Elapsed Time Present | — | |
| 14 | Remaining Time Present | — | |
| 15 | reserved | — | |

Payload order after flags:

| # | name | type | resolution |
|---|---|---|---|
| 1 | Stroke Rate | uint8 | 0.5 strokes/min → `raw / 2.0` |
| 2 | Stroke Count | uint16 | strokes |
| 3 | Average Stroke Rate | uint8 | 0.5 strokes/min |
| 4 | Total Distance | uint24 | metres |
| 5 | Instantaneous Pace | uint16 | 0.01 s per 500 m → `12000 = 2:00.00/500m` |
| 6 | Average Pace | uint16 | 0.01 s per 500 m |
| 7 | Instantaneous Power | sint16 | watts |
| 8 | Average Power | sint16 | watts |
| 9 | Resistance Level | sint16 | unitless (0x2ad6 gives the real range) |
| 10 | Total Energy | uint16 | kcal |
| 11 | Energy Per Hour | uint16 | kcal/h |
| 12 | Energy Per Minute | uint8 | kcal/min |
| 13 | Heart Rate | uint8 | bpm |
| 14 | Metabolic Equivalent | uint8 | 0.1 MET → `raw / 10.0` |
| 15 | Elapsed Time | uint16 | seconds |
| 16 | Remaining Time | uint16 | seconds |

Pace caveat `[TBD]`: some consoles report instant pace as 0 or as
speed-derived values. Handle `0` as "no pace" rather than 0 s/500m.

## Control Point `0x2ad9` `[SIG]`

Opcode byte first, then opcode-specific parameters, little-endian.

| opcode | name | params |
|---|---|---|
| `0x00` | Request Control | none — **must do first** |
| `0x01` | Reset | none |
| `0x02` | Set Target Speed | uint16, 0.01 km/h |
| `0x03` | Set Target Inclination | sint16, 0.1 % |
| `0x04` | Set Target Resistance Level | sint16, 0.1 → `raw=100` means level 10.0 |
| `0x05` | Set Target Power | sint16, watts |
| `0x06` | Set Target Heart Rate | uint8, bpm |
| `0x07` | Start or Resume | none |
| `0x08` | Stop or Pause | uint8: `0x01` stop, `0x02` pause |
| `0x09` | Set Targeted Cadence | uint16, 0.5 strokes/min |
| `0x80` | Response Code | indication, see below |

Response indication is always 3 bytes: `0x80`, `request_opcode`, `result`:

| result | meaning |
|---|---|
| `0x01` | Success |
| `0x02` | Op Code Not Supported |
| `0x03` | Invalid Parameter |
| `0x04` | Operation Failed |
| `0x05` | Control Not Permitted (you did not Request Control first) |

Sequence that works on every FTMS machine: enable `0x2ad9` CCCD indicate →
write `0x00` → wait for `80 00 01` → write targets → write `0x07` to start.
Losing the link drops control; re-request on reconnect.

A rower that does not implement `Set Target Resistance Level` or
`Set Target Power` (check `0x2acc` target-setting flags) cannot run structured
interval workouts — that capability gate is the deciding factor for the
"structured workouts" feature.

## Fitness Machine Feature `0x2acc` `[SIG]`

8 bytes = `uint32` machineFeatures + `uint32` targetSettingFeatures. Read it,
then gate UI on:

- machineFeatures bit 7 = Resistance Level supported
- machineFeatures bit 5 = Pace supported
- machineFeatures bit 14 = Power Measurement supported
- machineFeatures bit 1 = Cadence (stroke rate) supported
- targetSettingFeatures bit 2 = Target Resistance supported
- targetSettingFeatures bit 3 = Target Power supported
- targetSettingFeatures bit 4 = Target Heart Rate supported
- targetSettingFeatures bit 16 = Targeted Cadence supported

## Confirmed against the decompiled vendor SDK `[APP]`

From `jadx_out/sources/com/appdevice/domyos/` in their app:

- `ftms/features/configs/DCFTMSRowerConfig.java` — the flag→field map matches the
  SIG table above exactly, including `MORE_DATA_NOT_PRESENT_FLAG` gating
  stroke rate + stroke count as always-present.
- `ftms/core/DCFitnessDataType.java` — their decoders, with byte sizes:

  | field | bytes | multiplier |
  |---|---|---|
  | `ROWER_STROKE_RATE` | 1 | 0.5 |
  | `ROWER_STROKE_COUNT` | 2 | 1.0 |
  | `ROWER_AVERAGE_STROKE_RATE` | 1 | 0.5 |
  | `ROWER_INSTANTANEOUS_PACE` | 2 | **1.0** |
  | `ROWER_AVERAGE_PACE` | 2 | **1.0** |
  | `TOTAL_DISTANCE` | 3 | 1.0 (normalised to km by 0.001) |
  | `RESISTANCE_LEVEL` | 2 | 1.0 |
  | `INSTANTANEOUS_POWER` | 2 | 1.0 |
  | `TOTAL_ENERGY` | 2 | 1.0 |
  | `ENERGY_PER_HOUR` | 2 | 1.0 |
  | `ENERGY_PER_MINUTE` | 1 | 1.0 |
  | `HEART_RATE` | 1 | 1.0 |
  | `ELAPSED_TIME` | 2 | 1.0 |
  | `TARGET_RESISTANCE_LEVEL` | **1** | 0.1 |

  Pace multiplier `1.0` contradicts the SIG centisecond resolution — hence the
  heuristic in `RowerData.kt`. Resolve with a real frame.
- `ftms/core/DCControlPointOpCodes.java` — observed opcode values. These agree
  with SIG for the ones a rower actually uses: `REQUEST_CONTROL=0x00`,
  `RESET=0x01`, `TARGET_SPEED=0x02`, `TARGET_INCLINATION=0x03`,
  `TARGET_RESISTANCE=0x04`, `TARGET_POWER=0x05`, `TARGET_HEART_RATE=0x06`,
  `START_RESUME=0x07`, and `STOP`/`PAUSE` share `0x08` and are disambiguated by
  the parameter byte, as SIG describes.

  Two divergences, both unresolved until we can test on hardware:
  - their `TARGET_EXPENDED_ENERGY=0x09` where SIG puts *Set Targeted Cadence*;
  - their `TARGET_CADENCE=0x14` where SIG puts *Spin Down Control*.

  So either their table is shifted by one past `0x08`, or our reading of the SIG
  ordering is off. `FtmsControl.targetCadence` sends the SIG value `0x09`; if the
  machine answers `Op Code Not Supported` (0x02), try `0x14`. The `0x80` response
  indication was not confirmed in their code — their wrapper pre-strips it, and
  the RESPONSE entry's own opcode value was not recoverable from the decompiled
  constant pool.

The target-resistance width is the only confirmed divergence: their SDK writes
`TARGET_RESISTANCE_LEVEL` as one byte with a 0.1 multiplier, while the SIG table
says sint16. Our `FtmsControl.targetResistanceLevel` follows SIG; switch to a
single byte if the machine answers `Invalid Parameter` (0x03).

## What has to be verified on real hardware `[TBD]`

1. Does the console advertise `0x1826` at all? If not, it is one of the
   proprietary rower protocols in [Findings](findings.md) and the whole approach changes.
2. Which flag bits it actually sets in Rower Data (stroke rate? resistance?
   elapsed time? or only distance + energy?).
3. Does `0x2ad9` exist and does it implement Request Control?
4. Whether units are sane — resistance level resolution varies between vendors.
5. Notification rate, and whether any frames use the More Data bit.
