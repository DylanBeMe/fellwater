#!/bin/bash
# While Tunturi Routes is actively rowing, capture screenshots AND the BLE log size
# at the same instants, so an on-screen pace/speed reading can be matched to the
# console frame that produced it.
#
# Screenshots are used because `uiautomator dump` fails while the video surface is
# rendering (it silently returns a stale file).
#
# usage: live_shots.sh [count] [interval_s]
set -uo pipefail

ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
SERIAL=${SERIAL:-$($ADB devices | awk 'NR>1 && $2=="device" {print $1; exit}')}
COUNT=${1:-15}
INTERVAL=${2:-4}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/captures/live"
LOG=/data/misc/bluetooth/logs/btsnoop_hci.log

mkdir -p "$OUT"
adbs() { $ADB -s "$SERIAL" "$@"; }
size() { adbs shell su -c "stat -c %s $LOG" 2>/dev/null | tr -d '\r' | tail -1; }

INDEX="$OUT/index.txt"
: > "$INDEX"

for i in $(seq 1 "$COUNT"); do
  ts=$(adbs shell date +%s 2>/dev/null | tr -d '\r' | tail -1)
  sz=$(size)
  f="$OUT/shot-$(printf %02d "$i").png"
  adbs exec-out screencap -p > "$f" 2>/dev/null
  echo "$i ${ts:-0} ${sz:-0} $(stat -c %s "$f" 2>/dev/null)" >> "$INDEX"
  echo "  shot $i at ${ts:-?} (log ${sz:-?})"
  sleep "$INTERVAL"
done

final=$(size)
echo "final log size: ${final:-?}"
cp "$INDEX" "$ROOT/captures/live-index-$(date +%H%M%S).txt"
