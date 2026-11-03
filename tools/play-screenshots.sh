#!/usr/bin/env bash
# play-screenshots.sh: localized store screenshots for BurnPony Android.
#
# Installs the standard (Play) debug build, puts the device in a fixed
# 1080x1920 / 420 dpi frame with a clean SystemUI demo status bar (09:41,
# full battery and signal, no notifications), then for each language
# launches MainActivity with the debug-only Demo extras and captures the
# five screens with screencap.
#
# Raw captures go to
#   fastlane/metadata/android/<playLocale>/images/phoneScreenshots/<id>.png
# which is also what F-Droid shows. tools/frame_screenshots.py turns them
# into the framed Play images under fastlane/framed/android/.
#
# Usage:
#   tools/play-screenshots.sh              all nine languages
#   tools/play-screenshots.sh de ja        only these app language codes
#   SKIP_INSTALL=1 tools/play-screenshots.sh   reuse the installed build
#
# The debug build has the same applicationId as the Play release but a
# different signing key, so uninstall the store app from the device first
# (an emulator avoids that entirely). The script clears the debug app's data
# once before capturing so only demo rows show on the Sent tab.

set -euo pipefail
cd "$(dirname "$0")/.."

PKG="com.burnpony.android"
COMPONENT="$PKG/com.burnpony.app.MainActivity"
MIN_BYTES=40960

# app language code : Play locale folder
ALL_LANGS="en:en-US de:de-DE es:es-ES fr:fr-FR it:it-IT ja:ja-JP pt-BR:pt-BR ru:ru-RU zh-Hans:zh-CN"
SCREENS="1_compose 2_double_bottom 3_options 4_share 5_sent"

fail() { echo "play-screenshots: $*" >&2; exit 1; }

# Optional language filter.
LANGS=""
if [ "$#" -gt 0 ]; then
  for want in "$@"; do
    found=""
    for pair in $ALL_LANGS; do
      if [ "${pair%%:*}" = "$want" ]; then LANGS="$LANGS $pair"; found=1; fi
    done
    [ -n "$found" ] || fail "unknown language '$want' (use: en de es fr it ja pt-BR ru zh-Hans)"
  done
else
  LANGS="$ALL_LANGS"
fi

# 1. Exactly one device.
command -v adb >/dev/null 2>&1 || fail "adb not on PATH (export PATH=\"\$HOME/Library/Android/sdk/platform-tools:\$PATH\")"
count=$(adb devices | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d ' ')
[ "$count" = "1" ] || fail "need exactly one connected device or emulator, found $count"

# 2. Install the Play-flavor debug build.
if [ "${SKIP_INSTALL:-0}" != "1" ]; then
  ./gradlew :app:installStandardDebug || fail "install failed (if it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, uninstall the store build: adb uninstall $PKG)"
fi
adb shell pm clear "$PKG" >/dev/null

# 3. Fixed display, awake and unlocked. Reset on any exit.
cleanup() {
  adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
  adb shell svc power stayon false >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb shell wm size 1080x1920
adb shell wm density 420
adb shell input keyevent KEYCODE_WAKEUP
adb shell svc power stayon usb
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

# 4. SystemUI demo mode.
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
adb shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo network -e mobile show -e datatype none -e level 4
demo notifications -e visible false

launch() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$COMPONENT" --es demo_lang "$1" --es demo_screen "$2" >/dev/null
}

# 5. Capture.
for pair in $LANGS; do
  app="${pair%%:*}"
  play="${pair#*:}"
  dir="fastlane/metadata/android/$play/images/phoneScreenshots"
  mkdir -p "$dir"
  # Drop anything that is not one of the contract ids (the old 1.png..5.png).
  find "$dir" -maxdepth 1 -name '*.png' ! -name '[1-5]_*.png' -delete

  echo "== $app -> $play"
  # First launch switches the in-app language; AppCompat recreates the
  # Activity once, so give it longer.
  launch "$app" 1_compose
  sleep 5

  for screen in $SCREENS; do
    launch "$app" "$screen"
    sleep 3
    out="$dir/$screen.png"
    adb exec-out screencap -p > "$out"
    size=$(wc -c < "$out" | tr -d ' ')
    # 6. A blank frame (screen off or locked) compresses to almost nothing.
    [ "$size" -ge "$MIN_BYTES" ] || fail "$out is only $size bytes; the screen is probably off or locked"
    echo "   $screen.png  $size bytes"
  done
done

adb shell am force-stop "$PKG"
echo "Done. Check: open fastlane/metadata/android/en-US/images/phoneScreenshots"
