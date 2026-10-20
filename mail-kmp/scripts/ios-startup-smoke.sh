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
  run_timed 20 xcrun simctl spawn "$device" log show --last 1m --style compact --predicate 'process == "Cloud Mail"' > "$evidence/startup.log" 2>&1 || true
}
trap collect EXIT
# Unsigned simulator builds need an application identity for real Keychain use.
# This ad-hoc identity is for disposable CI simulators, not distribution signing.
cat > "$evidence/app.entitlements" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>application-identifier</key><string>com.cloudmail.app</string>
<key>keychain-access-groups</key><array><string>com.cloudmail.app</string></array>
</dict></plist>
PLIST
printf 'Sign simulator app identity\n'
run_timed 60 codesign --force --sign - --entitlements "$evidence/app.entitlements" "$app"
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
