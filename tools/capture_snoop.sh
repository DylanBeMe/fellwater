#!/bin/bash
# Wait for the tablet's full btsnoop log to grow (i.e. someone actually connected
# and exchanged data), then pull and decode it.
#
# usage: capture_snoop.sh [seconds]
set -uo pipefail

ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
SERIAL=${SERIAL:-$($ADB devices | awk 'NR>1 && $2=="device" {print $1; exit}')}
LIMIT=${1:-900}
LOG=/data/misc/bluetooth/logs/btsnoop_hci.log
OUT_DIR="${OUT_DIR:-$(cd "$(dirname "$0")/.." && pwd)/captures}"
TMP="$OUT_DIR/btsnoop-$(date +%Y%m%d-%H%M%S).log"
mkdir -p "$OUT_DIR"

adbs() { $ADB -s "$SERIAL" "$@"; }

size() { adbs shell su -c "stat -c %s $LOG 2>/dev/null" 2>/dev/null | tr -d '\r' | tail -1; }

start=$(size)
echo "baseline: ${start:-?} bytes, watching up to ${LIMIT}s for traffic"

prev=${start:-0}
stable=0
grew=0
for _ in $(seq 1 "$LIMIT"); do
  sleep 1
  cur=$(size)
  [ -z "$cur" ] && continue
  delta=$((cur - prev))
  total=$((cur - ${start:-0}))
  # Only a real conversation counts: noise-level growth must not end the wait.
  if [ "$total" -gt 3000 ]; then
    grew=1
    echo "log growing: $cur bytes (total +$total)"
  fi
  if [ "$grew" -eq 1 ] && [ "$delta" -le 300 ]; then
    stable=$((stable + 1))
    if [ "$stable" -ge 3 ]; then break; fi
  elif [ "$delta" -gt 300 ]; then
    stable=0
  fi
  prev=$cur
done

final=$(size)
echo "final: ${final:-?} bytes"
if [ "${final:-0}" -le "${start:-0}" ]; then
  echo "no new traffic captured"
  exit 2
fi

adbs shell su -c "cp $LOG /sdcard/snoop_full.log && chmod 666 /sdcard/snoop_full.log" >/dev/null 2>&1
adbs pull /sdcard/snoop_full.log "$TMP" >/dev/null 2>&1
ls -l "$TMP"

echo "===== decoded ====="
python3 "$(dirname "$0")/parse_btsnoop.py" "$TMP" --skip-acks | head -200
