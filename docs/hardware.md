# Supported rowers

## Tunturi R60 / R80 (iConsole): verified

These consoles do **not** speak the Bluetooth fitness standard (FTMS). They are a
BoQun "iConsole" serial bridge, reverse engineered from the vendor app and Bluetooth
captures, and verified on real hardware. See [iConsole protocol](protocol/iconsole.md).

What the console sends: stroke rate, power and a stroke counter. It does **not** send
distance or pace. Like the vendor app, Fellwater estimates distance from strokes
(about 8.7 m per stroke), so distance and pace are estimates, consistent with what
the vendor app shows.

## FTMS rowers: implemented, not yet verified

Rowers that advertise the standard Fitness Machine Service (`0x1826`) use a full
FTMS client: rower data, machine features and the control point. It is tested
against the specification and a simulated rower, but has not yet met a real FTMS
rower. Report what happens if you try one. The open questions are listed in
[FTMS client](protocol/ftms.md).

## Anything else

Fellwater connects to any Bluetooth LE device and logs its traffic in debug builds,
which is how the iConsole protocol was found. [Research notes](protocol/findings.md)
has the details.
