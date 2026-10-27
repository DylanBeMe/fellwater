#!/bin/bash
# Drive the app over ADB: start a scan, find a device by name fragment, connect,
# then dump the GATT table and raw frames from the screen.
#
# usage: capture_console.sh [name-fragment] [seconds-to-watch]
set -uo pipefail

ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
FRAG=${1:-onsole}
WATCH=${2:-60}
SERIAL=${SERIAL:-$($ADB devices | awk '/_adb-tls-connect|device$/ && !/usb:/ {print $1; exit}')}
D="dump.xml"

adbs() { $ADB ${SERIAL:+-s "$SERIAL"} "$@"; }

dump() {
  adbs shell uiautomator dump /sdcard/"$D" >/dev/null 2>&1
  adbs pull /sdcard/"$D" /tmp/"$D" >/dev/null 2>&1
}

# text -> centre coordinates of the first node containing $1
centre_of() {
  python3 - "$1" <<'EOF'
import re, sys
try:
    x = open('/tmp/dump.xml').read()
except OSError:
    sys.exit(0)
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if sys.argv[1].lower() in m.group(1).lower():
        a, b, c, d = map(int, m.groups()[1:])
        print(f"{(a + c) // 2} {(b + d) // 2}")
        break
EOF
}

texts() {
  python3 - <<'EOF'
import re
try:
    x = open('/tmp/dump.xml').read()
except OSError:
    raise SystemExit
for m in re.finditer(r'text="([^"]+)"', x):
    t = m.group(1).replace('&#10;', '\n')
    if t.strip():
        print(t)
EOF
}

adbs shell am force-stop fi.tunturi.rows >/dev/null 2>&1
adbs shell am start -n fi.tunturi.rows/.MainActivity >/dev/null 2>&1
sleep 3

dump
xy=$(centre_of "Scan") || true
[ -z "${xy:-}" ] && { echo "no Scan button found"; exit 1; }
adbs shell input tap $xy
echo "scanning, watching ${WATCH}s for '${FRAG}'..."

for _ in $(seq 1 "$WATCH"); do
  sleep 1
  dump
  xy=$(centre_of "$FRAG") || true
  [ -n "${xy:-}" ] && break
done

if [ -z "${xy:-}" ]; then
  echo "not found. devices seen:"
  texts | grep dBm || true
  exit 2
fi

echo "connecting to '$FRAG' at $xy"
adbs shell input tap $xy
# Long window: the console only emits sport-data frames while the machine is
# actually being rowed, and it needs a few strokes to get going.
sleep 75

for _ in 1 2 3 4 5; do adbs shell input swipe 1280 1200 1280 400 >/dev/null 2>&1; done
sleep 2
dump

echo "===== state ====="
texts | head -6
echo "===== GATT table ====="
texts | sed -n '/^device: /,/^Raw frames$/p'
# The on-screen card is clipped by the viewport; read the app's own log file
# instead, which has every GATT send/receive line in full.
echo "===== raw log (app files) ====="
adbs shell run-as fi.tunturi.rows cat files/rawlog.txt 2>/dev/null | tail -150
