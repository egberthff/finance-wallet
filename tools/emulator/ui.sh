#!/usr/bin/env bash
# Emulator UI driver. Usage:
#   ui.sh shot <name>            screenshot to scratchpad/shots/<name>.png
#   ui.sh tap "<text>" [n]       tap the n-th (default 1) node whose text or content-desc contains <text>
#   ui.sh tapxy X Y
#   ui.sh type "<text>"          type text (spaces allowed)
#   ui.sh key <KEYCODE>
#   ui.sh dump                   list visible texts with centers
#   ui.sh scroll                 swipe up (scroll down)
export MSYS_NO_PATHCONV=1
SER="${SER:-emulator-5554}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb -s $SER"
S="${SHOTS_DIR:-$(dirname "$0")/../../build/emulator-shots}"
mkdir -p "$S/shots"
dumpxml() {
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  $ADB exec-out cat /sdcard/ui.xml | sed 's/<node /\n<node /g'
}
center() { # reads node lines, prints "x y" of the n-th match
  local n="$2"
  grep -F -- "$1" | sed -n "${n}p" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' |
    sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' |
    awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}
case "$1" in
  shot) $ADB exec-out screencap -p > "$S/shots/$2.png"; echo "saved $2" ;;
  tap)
    xy=$(dumpxml | grep -E "(text|content-desc)=\"[^\"]*$(printf '%s' "$2" | sed 's/[]\/$*.^[]/\\&/g')" | center "" "${3:-1}")
    if [ -z "$xy" ]; then echo "NOT FOUND: $2"; exit 1; fi
    $ADB shell input tap $xy; echo "tapped $2 at $xy" ;;
  tapxy) $ADB shell input tap "$2" "$3" ;;
  type) $ADB shell input text "$(printf '%s' "$2" | sed 's/ /%s/g')" ;;
  key) $ADB shell input keyevent "$2" ;;
  scroll) $ADB shell input swipe 540 1800 540 700 400 ;;
  start) $ADB shell am start -n ph.financemanager.app/ph.financewallet.app.MainActivity >/dev/null; sleep 3 ;;
  unlock) # enters PIN 123456 by the keys' content descriptions, if the lock screen is showing
    for _ in 1 2 3 4 5 6 7 8; do
      x=$(dumpxml)
      printf '%s' "$x" | grep -q 'content-desc="5"' && break
      sleep 1
    done
    if printf '%s' "$x" | grep -q 'content-desc="5"'; then
      for d in 1 2 3 4 5 6; do
        xy=$(printf '%s\n' "$x" | grep -F "content-desc=\"$d\"" | center "" 1)
        $ADB shell input tap $xy
      done
      echo unlocked
    else echo "no lock screen"; fi ;;
  tapdesc)
    xy=$(dumpxml | grep -F "content-desc=\"$2\"" | center "" "${3:-1}")
    if [ -z "$xy" ]; then echo "NOT FOUND: $2"; exit 1; fi
    $ADB shell input tap $xy; echo "tapped desc $2" ;;
  tapexact)
    xy=$(dumpxml | grep -F "text=\"$2\"" | center "" "${3:-1}")
    if [ -z "$xy" ]; then echo "NOT FOUND: $2"; exit 1; fi
    $ADB shell input tap $xy; echo "tapped $2 at $xy" ;;
  dump)
    dumpxml | grep -o -E '(text|content-desc)="[^"]+"|bounds="[^"]*"' | paste - - 2>/dev/null |
      grep -E '^(text|content-desc)=' ;;
esac
