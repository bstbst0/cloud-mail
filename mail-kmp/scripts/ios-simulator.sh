#!/usr/bin/env bash
# Source this helper from the mail-kmp directory on a disposable macOS runner.
boot_cloud_mail_simulator() {
  mkdir -p composeApp/build/ios-smoke
  if [[ -z "${CLOUD_MAIL_IOS_SIMULATOR:-}" ]]; then
    xcrun simctl list devices available --json > composeApp/build/ios-smoke/devices.json
    CLOUD_MAIL_IOS_SIMULATOR=$(python3 - composeApp/build/ios-smoke/devices.json <<'PY'
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
  fi
  export CLOUD_MAIL_IOS_SIMULATOR
  xcrun simctl boot "$CLOUD_MAIL_IOS_SIMULATOR" || true
  xcrun simctl bootstatus "$CLOUD_MAIL_IOS_SIMULATOR" -b
}
