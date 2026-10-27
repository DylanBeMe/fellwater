#!/bin/bash
# Read the on-screen numbers from Tunturi Routes while simultaneously snooping BLE,
# so the console's frames can be matched to real distance/time values.
#
# Their app is the ground truth here: the console's own LCD switches off as soon as
# a device connects (observed on hardware), so it cannot be read mid-session.
#
# usage: capture_levels.sh [seconds]
set -uo pipefail

ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
SERIAL=${SERIAL:-$($ADB devices | awk 'NR>1 && $2=="device" {print $1; exit}')}
SECS=${1:-300}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/captures/ui-levels-$(date +%H%M%S).txt"
LOG=/data/misc/bluetooth/logs/btsnoop_hci.log

adbs() { $ADB -s "$SERIAL" "$@"; }
size() { adbs shell su -c "stat -c %s $LOG" 2>/dev/null | tr -d '\r' | tail -1; }

mkdir -p "$ROOT/captures"
echo "# on-screen numbers from Tunturi Routes, sampled every 2s" > "$OUT"
base=$(size)
echo "baseline log size: ${base:-?} -> $OUT"

for _ in $(seq 1 $((SECS / 2))); do
  ts=$(date +%H:%M:%S)
  adbs shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  txt=$(adbs shell cat /sdcard/ui.xml 2>/dev/null \
        | tr '<' '\n' | sed -n 's/.*text="\([^"]*\)".*/\1/p' \
        | grep -vE '^$' | tr '\n' '|')
  [ -n "$txt" ] && echo "$ts  $txt" >> "$OUT"
  sleep 2
done

final=$(size)
echo "log grew $(( ${final:-0} - ${base:-0} )) bytes"
adbs shell su -c "cp $LOG /sdcard/snoop_levels.log && chmod 666 /sdcard/snoop_levels.log" >/dev/null 2>&1
SNOOP="$ROOT/captures/btsnoop-levels-$(date +%H%M%S).log"
adbs pull /sdcard/snoop_levels.log "$SNOOP" >/dev/null 2>&1
echo "snoop -> $SNOOP"
echo "ui    -> $OUT"
