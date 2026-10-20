#!/usr/bin/env bash
# Disposable hosted simulator only; never signs in or contacts a mailbox.
set -euo pipefail
app=${1:?Pass the simulator .app path}
evidence=composeApp/build/ios-smoke
mkdir -p "$evidence"
xcrun simctl list devices available --json > "$evidence/devices.json"
device=$(python3 - "$evidence/devices.json" <<'PY'
import json, re, sys
with open(sys.argv[1]) as stream:
    devices = json.load(stream)['devices']
for runtime, values in devices.items():
    if '.iOS-' not in runtime:
        continue
    for device in values:
        if device.get('isAvailable') and device['name'].startswith('iPhone'):
            identifier = device['udid']
            if re.fullmatch(r'[0-9A-Fa-f-]{36}', identifier):
                print(identifier)
                sys.exit(0)
sys.exit('No available iPhone simulator runtime')
PY
)
collect() {
  xcrun simctl io "$device" screenshot "$evidence/login.png" || true
  xcrun simctl spawn "$device" log show --last 1m --style compact --predicate 'process == "Cloud Mail"' > "$evidence/startup.log" 2>&1 || true
  xcrun simctl shutdown "$device" || true
}
trap collect EXIT
xcrun simctl boot "$device" || true
xcrun simctl bootstatus "$device" -b
xcrun simctl install "$device" "$app"
xcrun simctl launch "$device" com.cloudmail.app
sleep 8
xcrun simctl spawn "$device" launchctl list | awk '/com[.]cloudmail[.]app/ && $1 ~ /^[0-9]+$/ {running=1} END {exit !running}'
printf 'Installed simulator app remains running; screenshot captured for inspection.\n'
