#!/usr/bin/env bash
# Disposable hosted simulator only; never signs in or contacts a mailbox.
set -euo pipefail
app=${1:?Pass the simulator .app path}
evidence=composeApp/build/ios-smoke
mkdir -p "$evidence"
script_dir=$(cd "$(dirname "$0")" && pwd)
run_timed() { python3 "$script_dir/run-with-timeout.py" "$@"; }
source "$script_dir/ios-simulator.sh"
boot_cloud_mail_simulator
device=$CLOUD_MAIL_IOS_SIMULATOR
collect() {
  printf 'Collect bounded simulator evidence\n'
  if [[ ! -f "$evidence/login.png" ]]; then
    run_timed 30 xcrun simctl io "$device" screenshot "$evidence/login.png" || true
  fi
  run_timed 20 xcrun simctl spawn "$device" log show --last 1m --style compact --predicate 'process == "Cloud Mail" OR process == "amfid" OR process == "launchd_sim" OR process == "runningboardd"' > "$evidence/startup.log" 2>&1 || true
}
trap collect EXIT
# Xcode embeds simulator entitlements and signs only the host-side executable.
# Do not attach iOS entitlements to a macOS signature with manual codesign.
printf 'Verify simulator app signature\n'
run_timed 30 codesign --verify --strict --verbose=2 "$app"
run_timed 30 codesign --display --verbose=4 "$app"
run_timed 30 xcrun segedit "$app/Cloud Mail" -extract __TEXT __entitlements "$evidence/app-simulated.entitlements"
python3 - "$evidence/app-simulated.entitlements" <<'PYIDENTITY'
import plistlib, sys
with open(sys.argv[1], 'rb') as source:
    identity = plistlib.load(source)
if identity.get('application-identifier') != 'com.cloudmail.app' or identity.get('keychain-access-groups') != ['com.cloudmail.app']:
    raise SystemExit('App executable has an unexpected simulator identity')
PYIDENTITY
printf 'Install simulator app\n'
run_timed 180 xcrun simctl install "$device" "$app"
printf 'Launch simulator app\n'
run_timed 90 xcrun simctl launch "$device" com.cloudmail.app
sleep 8
printf 'Verify app process\n'
run_timed 30 xcrun simctl spawn "$device" launchctl list | awk '/com[.]cloudmail[.]app/ && $1 ~ /^[0-9]+$/ {running=1} END {exit !running}'
printf 'Capture actual app screen\n'
run_timed 30 xcrun simctl io "$device" screenshot "$evidence/login.png"
printf 'Installed simulator app remains running; screenshot captured for inspection.\n'
