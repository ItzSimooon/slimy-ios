#!/usr/bin/env bash
# Takes screenshots of the home screen, every sheet and both full-screen
# covers, in light and dark, on a booted simulator with a clean status bar.
# Needs a Debug simulator build in build/ (Debug, because screenshot mode
# only exists there; see App/Core/ScreenshotMode.swift).
# Files: <out>/<light|dark>-<screen>.png
# Usage: tools/ci_screenshots.sh <simulator udid> [out dir]
set -euo pipefail

udid="$1"
out="${2:-screenshots}"
app="build/Build/Products/Debug-iphonesimulator/Slimy.app"
bundle="de.hyprnode.slimy"
settle="${SCREENSHOT_WAIT:-5}"

sheets=(kitchen shop journal friends settings chat gift)
covers=(arcade bath)

if [ ! -d "$app" ]; then
  echo "::error::$app is missing. Build the Debug simulator app first."
  exit 1
fi

mkdir -p "$out"
xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b

status_bar() {
  xcrun simctl status_bar "$udid" override \
    --time 9:41 --batteryLevel 100 --batteryState charged \
    --cellularMode active --cellularBars 4 --dataNetwork wifi --wifiMode active --wifiBars 3
}

stop_app() {
  xcrun simctl terminate "$udid" "$bundle" >/dev/null 2>&1 || true
}

# shoot <appearance> <screen> [extra launch arguments...]
shoot() {
  local appearance="$1" screen="$2"
  shift 2
  stop_app
  xcrun simctl launch "$udid" "$bundle" -SlimyScreenshot "$@" >/dev/null
  sleep "$settle"
  xcrun simctl io "$udid" screenshot --type=png "$out/$appearance-$screen.png" >/dev/null
  echo "Saved $out/$appearance-$screen.png"
  stop_app
}

status_bar
xcrun simctl install "$udid" "$app"

# First launch: HomeView creates the demo slime; caches warm up.
xcrun simctl ui "$udid" appearance light
xcrun simctl launch "$udid" "$bundle" -SlimyScreenshot >/dev/null
sleep 10
stop_app

for appearance in light dark; do
  xcrun simctl ui "$udid" appearance "$appearance"
  status_bar
  sleep 2
  shoot "$appearance" home
  for sheet in "${sheets[@]}"; do
    shoot "$appearance" "$sheet" -SlimySheet "$sheet"
  done
  for cover in "${covers[@]}"; do
    shoot "$appearance" "$cover" -SlimyCover "$cover"
  done
done

ls -l "$out"
