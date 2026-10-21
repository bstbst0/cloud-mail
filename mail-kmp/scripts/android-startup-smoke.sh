#!/usr/bin/env bash
# Run only on a disposable emulator. No mailbox, credentials or remote API is used.
set -euo pipefail
apk=${1:?Pass the current debug APK path}
evidence=mail-kmp/composeApp/build/android-smoke
mkdir -p "$evidence"
collect() {
  adb logcat -d -s AndroidRuntime:E > "$evidence/android-runtime.log" || true
  adb exec-out screencap -p > "$evidence/login.png" || true
}
trap collect EXIT
adb install -r "$apk"
adb logcat -c
adb shell am start -W -n com.cloudmail.app/.MainActivity
for attempt in {1..20}; do
  adb shell uiautomator dump /sdcard/cloud-mail-startup.xml >/dev/null 2>&1 || true
  adb pull /sdcard/cloud-mail-startup.xml "$evidence/login.xml" >/dev/null 2>&1 || true
  if [[ -f "$evidence/login.xml" ]] && grep -Fq 'Server URL' "$evidence/login.xml" && grep -Fq 'Sign in' "$evidence/login.xml"; then
    adb shell pidof com.cloudmail.app >/dev/null
    printf 'Installed APK launched and rendered the native sign-in form.\n'
    exit 0
  fi
  sleep 2
done
printf 'Native sign-in form did not become available.\n' >&2
exit 1
