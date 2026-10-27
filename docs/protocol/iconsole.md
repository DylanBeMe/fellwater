# iConsole rower protocol (BoQun serial-over-BLE)

**Confirmed by observation on hardware:** the R60/R80 console appears in a BLE scan
as `i-Console+0082`, advertising **no service UUIDs**. It is
`ICONSOLE_IROWING_BLE` (technoId 93, `btleIconsoleRowingMachine`) in Kinomap's
protocol table — **not** FTMS. Anything FTMS-based cannot talk to it.

Source of everything below: `jadx_out/` (their app), plus one live scan.

## Why static RE mostly stops here

The vendor SDK is `fly.serialport.uart.*` (BoQun iConsole): three classes
(`BoQun`, `SerialPort`, `BoQunLog`, `a`) plus interfaces `oyq`/`ubb`/`oe7` that
decompile to empty stubs, and native methods on `SerialPort`
(`open`/`close`/`read`/`write` over `FileDescriptor`). The transport and framing
live in native code or fully obfuscated classes, so the **GATT table of the
console itself is the missing evidence**.

Their app also ships only three readable model classes:
`com.boqun.iconsole.sdk.rower.RowerSportData` / `RowerConfigInfo`
(fields obfuscated: `b..i`) and `treadmill.TreadmillConfigInfo`.

## Frame decode `[APP]`

From `defpackage/y1r.java` (implements `pyq`, the frame plugin base). A rower
sport-data frame is recognised by:

```java
boolean d(int len, byte command) { return command == 0xD1 && len == 20; }
```

So: command `0xD1`, total payload length 20 bytes. Then, with `bArr` the frame
and all multi-byte reads **big-endian** (`pyq.f(byte...)` shifts
`(b & 0xFF) << (remaining * 8)`):

| field | bytes | width | goes to `RowerSportData` |
|---|---|---|---|
| — | `bArr[0..2]` | 3 | header, not read by the parser |
| A | `bArr[3..4]` | 2 | `b` (long) |
| B | `bArr[5]` | 1 | `c` (int) |
| C | `bArr[6..8]` | 3 | `d` |
| D | `bArr[9..10]` | 2 | `e` |
| E | `bArr[11..13]` | 3 | `f` |
| F | `bArr[14..15]` | 2 | `g` |
| G | `bArr[16..17]` | 2 | `h` |
| H | `bArr[18]` | 1 | `i` |
| — | `bArr[19]` | 1 | checksum-ish, not read |

Then `defpackage/w1r.java` hands the decoded record to the equipment layer:

```java
me8Var.L0((short) g, f, -1.0f, i, c, h, (short) e);
```

Seven arguments, in that order, with one constant `-1.0f` placeholder.

### Field semantics — inferred, not proven

Ordering the arguments against how a rower console reports data, and against the
`RowerSportData` widths (2×3-byte fields suit distance and elapsed time; 1-byte
`c` and `i` suit stroke rate / resistance):

| arg | source | likely meaning | confidence |
|---|---|---|---|
| 1 | `g` (2 B) | speed or stroke rate (short cast) | low |
| 2 | `f` (3 B) | **distance**, metres | medium |
| 3 | constant `-1.0f` | unused placeholder | — |
| 4 | `i` (1 B) | resistance level or stroke rate | low |
| 5 | `c` (1 B) | **stroke rate**, spm | low |
| 6 | `h` (2 B) | **elapsed time**, seconds | medium |
| 7 | `e` (2 B) | calories or pace | low |

Do not build on this table yet. One capture of real `0xD1` frames while rowing at
two known stroke rates and a known elapsed time settles every row in under a
minute.

## What is still needed

## Hardware results (2026-10-27, measured on the real console)

### GATT table — `i-Console+0082`, `E8:5D:86:xx:xx:xx`, advertises **no** service UUIDs

| service | characteristic | properties |
|---|---|---|
| `1800` GAP | `2a00`, `2a01` | read |
| `180A` Device Info | `2a29`, `2a24`, `2a25`, `2a27`, `2a26`, `2a28`, `2a23`, `2a2a` | read |
| `49535343-5d82-6099-9348-7aac4d5fbc51` | `49535343-026e-3a9b-954c-97daef17e26e` | write, notify |
| `49535343-c9d0-cc83-a44a-6fe238d06d33` | **`49535343-aca3-481c-91ec-d85e28a60318`** | write, notify |
| `49535343-fe7d-4ae5-8fa9-9fafd205e455` | `49535343-1e4d-4bd9-ba61-23c647249616` | write, write-nr, notify |
| | `49535343-8841-43f4-a8d4-ecbe34729bb3` | write, write-nr |
| | `49535343-4c8a-39b3-2f49-511cff073b7e` | write, notify |

A Microchip/ISSC **Transparent UART** bridge: the console's serial port bridged to
BLE. Nothing here is a fitness profile.

### Which channel answers — measured

All four notify-channel CCCD writes returned `status=0`. Replies came from exactly
one channel:

| channel | result |
|---|---|
| `026e` | silent |
| **`aca3`** | replies `03 f0` to every frame |
| `1e4d` | silent |
| `8841` | silent |
| `4c8a` | silent |

### The reply is a fixed ack — measured

Every write to `aca3` draws exactly one 2-byte notification, always `03 f0`:

- one-shot handshake: 5 frames → 5 replies, all `03 f0`
- 250 ms polling of `0xA1`: 52 polls → 52 replies, all `03 f0`
- sweep of **all six** known commands (`A1`, `A2`, `A3`, `A7`, `B0:01`, `B0:00`),
  400 ms apart: 39 attempts → 39 replies, all `03 f0`

Our bytes reach the console and something answers, but the command content makes no
difference. Every write was ack'd at the ATT layer with `status=0`, so this is not
a link failure or a range problem.

Two readings remain, not yet distinguishable:

1. `03 f0` is a generic ack and the console streams only inside a session its own
   UI starts — so poll/response is the wrong model.
2. `03 f0` is a **rejection**: our frames are malformed for this firmware (bad
   checksum or wrong header), so nothing is ever accepted.

## Field mapping — solved against their own display

Method: screenshots of Tunturi Routes taken at the exact byte offset of a captured
`B2` frame (`tools/live_shots.sh` writes a log-size marker per shot, so each frame
ties to the reading on screen). Four independent samples:

| screen | frame bytes |
|---|---|
| 17 W / 32 spm / 117 strokes / 1020 m | `i17=18  i9=31  i13=59` |
| 93 W / 32 spm / 133 strokes / 1190 m | `i17=94  i9=33  i13=68` |
| 35 W / 30 spm / 126 strokes / 1100 m | `i17=36  i9=31  i13=63` |
| idle | `i17=1  i9=1  i13=57` |

- **`i17` = power, watts** — within 1 W on every sample.
- **`i9` = stroke rate, spm**.
- **`i13` = the console's stroke counter**; their app displays ~2x it.
- **Distance is not transmitted.** Their app derives it at ~8.7 m per *displayed*
  stroke. Any distance we show is an estimate on the same assumption, not measured.
- **Elapsed time is unreliable** in bytes 11-12; it matches neither timer.
- `i7` wraps at 100 (not strokes); the 3-byte `02 02 xx` / `07 02 xx` notifications
  are per-frame sequence counters, exactly one per `B2`.

## How "rowing against others" works (APK analysis)

Server-mediated, not faked:

- `GET multiplayers`, `GET multiplayers/events/{event}`, `POST multiplayers/events`,
  `POST multiplayers/events/{session}` (`defpackage/epf.java`).
- `MultiplayerEvent`: `author`, `participants` (`UserObject` list), `room_id`,
  `players_current_count`, `players_max_count`, `start_time`, `video`,
  `equipment_type`, `distance`.
- On start: `rtcEngine.joinChannel(null, roomId, id, channelMediaOptions)` — Agora RTC.

But metrics are not relayed:

- No `sendStreamMessage`/`onStreamMessage` in `com.kinomap` — Agora data channel unused.
- No per-participant distance/speed/pace/progress fields; no multiplayer polling timer
  in the play activity package.
- Rankings come from separate endpoints (`me/challenge`, `campaigns/paris2024/leaderboard`).

Separately, **ghosts** (`enableGhosts`, default on) replay *recorded* sessions as a
marker (`ghost_circle_color`) — local stored data, not opponents.

**Implication:** live racing cannot be reproduced client-side. It needs our own
backend holding per-participant metrics; the console only produces local numbers.

