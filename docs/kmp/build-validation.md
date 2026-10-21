# Native build validation

Local validation uses an isolated Linux x86_64 workspace, a complete JDK 21 at `/workspace/full-jdk21`, Gradle 8.14.3 wrapper, Android SDK/build tools 35, and Xorg dummy display `:99`. Test fixtures do not send real email or contact production. iOS, Windows and macOS require their native CI runners; Linux results do not establish those platform builds.

## Current hosted checkpoint — 2026-10-21

[Run 39531990119](https://github.com/bstbst0/cloud-mail/actions/runs/39531990119), functional source `32a62a4`, completed **nine successful jobs**: Worker/Web, Android, Android API 35 emulator startup, Linux/Windows/macOS Desktop, Intel iOS runtime, ARM64 simulator framework and ARM64 physical-device framework/unsigned host.

The actual iOS native JUnit artifact `12316327734` was downloaded and parsed: Contract 5, Rich text 6, Security 3; **14 tests, 0 failures, 0 errors, 0 skips**. Security tests execute actual Security/CommonCrypto APIs, covering Keychain create/update/delete, empty values, authenticated encryption round trip, distinct IVs and ciphertext/IV/tag tampering. The second Gradle invocation reported `linkDebugTestIosX64 UP-TO-DATE`; the full native step finished in 2m 22s and the cases ran in seconds. The fixed cache selection addresses the previously logged fingerprint change without excluding any compilation or test task.

The independent physical-device job built the ARM64 framework and unsigned Xcode `iphoneos` app; artifact `12315773642` contains those compilation products. It cannot be installed or distributed without normal operator-provided signing. Simulator runtime used Xcode 16.4/iOS 18.5 with an isolated embedded app identity and real native Compose login screenshot; no mailbox credentials were entered. Current screenshot and scope are in [UI validation](ui-validation.md).

Other review artifacts in that run include debug APK `12315507942`, Android emulator evidence `12315553191`, and Linux/Windows/macOS distributions `12316475104` / `12316445234` / `12315987967`. Installer builds do not certify signed installation, native vault consent, attachment handoff or background delivery on physical machines. The remaining sections preserve the local reproduction and chronological failure history; earlier pending statements describe their original checkpoints.

## Reproduction

From `mail-kmp`:

```sh
PATH=/workspace/fakeroot-local/usr/bin:$PATH \
FONTCONFIG_FILE=/workspace/cloud-mail-fonts.conf \
JAVA_TOOL_OPTIONS="-Dskiko.data.path=/workspace/skiko-cache -Djava.io.tmpdir=/workspace/java-tmp" \
JAVA_HOME=/workspace/full-jdk21 DISPLAY=:99 \
GRADLE_USER_HOME=/workspace/gradle-home \
ANDROID_HOME=/workspace/android-sdk ANDROID_USER_HOME=/workspace/android-user \
./gradlew --no-daemon --dependency-verification=strict \
  -Pandroid.aapt2FromMavenOverride=/workspace/android-sdk/build-tools/35.0.0/aapt2 \
  :composeApp:desktopTest :composeApp:assembleDebug \
  :composeApp:testDebugUnitTest :composeApp:lintDebug \
  :composeApp:packageDistributionForCurrentOS
```

The aapt2 override selects the already installed SDK binary in this constrained environment; CI uses AGP's regular resolved binary. The display must be accessible to test JVMs. A complete JDK including `jlink`, `jmods` and `jpackage` is required for packaging.

## Investigated failures and repairs

- Earlier dependency resolution was blocked by temporary proxy HTTP 503 responses. Once Maven Central recovered, previously missing JVM test dependencies resolved.
- Strict dependency verification correctly rejected eleven newly resolved test artifacts without checksums. Metadata was explicitly regenerated; ten affected official Maven Central test artifacts were independently downloaded again and matched the recorded SHA-256 digests. Subsequent runs use strict verification. Gradle wrapper distribution SHA-256 is pinned to the official checksum.
- A build started before the new debug manifest placeholder was configured failed manifest merging. The Gradle Android configuration now supplies the placeholder, false by default and release; subsequent manifest processing passed.
- Compose Desktop test compilation exposed the `waitUntil` timeout-description overload. Tests use named `timeoutMillis`, preserving the intended 10-second timeout.
- A complete downloaded vendor JDK initially lacked the environment proxy root CA. Missing `truth:1.0.1` resolution failed with PKIX certificate errors. Importing the provided environment CA into this workspace-only JDK trust store restored authenticated TLS without disabling verification.
- Windows x64/macOS x64/macOS ARM64 Skiko runtime JAR/POM metadata was added using official Maven Central bytes, independently checked against their published SHA-1 digests; platform compilation remains unverified on Linux.
- Environment restart removed active processes and `/tmp` logs. Validation resumed using persistent workspace logs and a recreated dummy display.

- The first actual Desktop test execution found failing URL-validation, oversized-attachment and uncertain-send regressions. A virtual-time logout race test was stuck running recurring sync jobs; its executor was terminated (exit 143), and the failures were sent to the owning engineer. This interrupted run is explicitly not counted as a passing suite.
- Android APK assembly succeeded on current source, but Android unit-test compilation exposed an ActivityResultLauncher Kotlin property override mismatch; the fixture now correctly overrides `val contract`, and both lifecycle tests passed.

## Latest results

Current-source Android Debug APK assembly and Android Lint have completed: lint reports 0 errors and 36 warnings (mostly dependency version suggestions and catalog/style notices before final catalog cleanup). The later Android unit run passed all 19 tests after URL-validation repair. The subsequent Desktop run executed 45 tests with 2 failures: oversized attachment error mapping and duplicate expanded navigation semantics. Its real HTTP Worker integration test passed. Both remaining failures have been repaired, including replacing eager attachment loading with a bounded streaming request; subsequent current-source outcomes are recorded below.

Linux DEB packaging completed with strict dependency verification. An actual extracted launcher exposed missing `java.sql.DriverManager` in the minimized runtime; explicitly declaring JDBC/security runtime modules repaired startup. The corrected packaged app launched on Xorg with a visible 800×600 login window; `/workspace/cloud-mail-packaged-login.png` records the actual runtime. Fontconfig and Skiko caches were redirected to writable workspace directories because this sandbox prevents writes to the usual home caches; a TTF-only test font configuration avoids the host OpenAI WOFF2 font's Skia native crash. These are environment settings, not substituted UI or disabled tests.

On 2026-10-20 the strict Linux run passed the complete Desktop suite: 46 discovered tests, 45 passed, 0 failures and 1 skipped opt-in HTTP integration test (its fixture URL was deliberately absent in that invocation). The same run passed all 19 Android unit tests and assembled the Debug APK. Desktop results include 16 repository tests, 8 ViewModel tests, 5 contract tests, 3 desktop security tests, 6 rich-text tests and all 7 real Compose UI tests. The previous invocation with the isolated Worker URL explicitly set passed the actual HTTP integration test; the final targeted HTTP run will be recorded below.

Full-suite JUnit evidence was preserved in `/workspace/cloud-mail-validation-evidence/desktop-full` and `/workspace/cloud-mail-validation-evidence/android` before targeted reruns. The complete command, including strict verification, Android lint/APK and Linux DEB packaging, finished `BUILD SUCCESSFUL in 2m 30s` (68 actionable tasks: 24 executed, 44 up-to-date). The targeted current-source follow-up also passed all 8 tests with 0 failures and 0 skips: 7 UI tests including the final expanded Search action, plus the real HTTP Worker integration test with `CLOUD_MAIL_E2E_URL=http://127.0.0.1:8789` explicitly enabled. This uses the production Hono application with isolated in-memory D1/KV/R2 and a mocked provider; no external mail is sent. Evidence is preserved in `/workspace/cloud-mail-validation-evidence/desktop-ui-e2e`. The final installer/APK refresh completed `BUILD SUCCESSFUL in 1m 54s` (52 actionable tasks, 10 executed). A final targeted actual-file persistence test also passed (`BUILD SUCCESSFUL in 23s`): the real desktop authenticated cipher and on-disk SQLite retained the offline body, HTML, attachment metadata, draft attachments, cursor and ordered pending action across close/reopen, isolated other owners, and persisted logout clearing. Its XML is preserved under `/workspace/cloud-mail-validation-evidence/desktop-persistence`.

## Final local artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `mail-kmp/composeApp/build/outputs/apk/debug/composeApp-debug.apk` | 20,063,804 | `4887087bf8432b432dc316eaeaaa2084e2ba276b819785e4e923bd77f477747e` |
| `mail-kmp/composeApp/build/compose/binaries/main/deb/cloud-mail_1.0.0_amd64.deb` | 107,730,940 | `a65a4b1f5db17d8a78a05b9e8d63b4a980a1ba4aeef7005bc67a2cebaeb9141d` |

`apksigner verify --verbose` verified the final APK's v2 signature with one signer. The final DEB was extracted with `dpkg-deb -x`; its bundled native launcher was started with its bundled Java runtime on Xorg. The Cloud Mail login window appeared at 800×600 using software rendering. The log includes a failed GL-context attempt before Skiko falls back; this is not a claim that GL initialization succeeded. `/workspace/cloud-mail-packaged-login-final.png` captures that final executable. The dummy server falls back to software rendering. Signing checks and Linux startup do not establish Android device installation or other OS behavior.

## Verification limits

No Android emulator/device, macOS/Xcode, iOS simulator, Windows host or macOS desktop was available for local execution. Their native CI checks and device QA remain required, and the release report separately tracks observed CI outcomes. No production deployment, migration, mailbox deletion or external provider send was performed. Debug builds/installers are development artifacts, not a signed production release.

Durable session logs are `/workspace/cloud-mail-build-oct20.log` (complete suite), `/workspace/cloud-mail-build-release-check.log` (final UI/HTTP and package refresh), `/workspace/cloud-mail-build-persistence.log` (actual database reopen), and `/workspace/cloud-mail-packaged-launch-final.log` (final packaged executable). These workspace paths are local evidence; GitHub Actions uploads its own JUnit/build/installer artifacts. Rebuilding changes installer timestamps and therefore may change artifact hashes.

Hosted iOS CI later reached `compileKotlinIosSimulatorArm64` dependency preparation and identified missing strict verification metadata for the Kotlin/Native macOS ARM64 compiler archive. Both ARM64 (249,041,451 bytes) and x64 (249,663,665 bytes) official Maven Central 2.2.20 archives were fully streamed, SHA-256 calculated, and independently matched against their published SHA-1 before recording their checksums. This fixes dependency verification; it does not establish iOS source compilation.

Final review additionally identified an ownership-revocation/offline-restore conflict: a stale queued restore could overlay an account-zero tombstone. The cache merge now purges revoked account intentions before overlay/reset, keeps minimal hidden tombstones, and preserves legitimate Undo only for still-authorized account mappings. Two new regression tests cover revocation with/without cursor reset and ordinary deletion Undo across repeated tombstones; targeted RepositoryTest (18 cases) and disk-persistence test (1 case) passed with zero skips. The final search-loading-state group passed all 9 ViewModel and 7 UI cases. The latest APK and DEB were rebuilt successfully in `/workspace/cloud-mail-build-search-final.log` (1m 55s); the hashes above identify these refreshed artifacts. Their APK v2 signature was reverified, and the extracted current bundled Linux executable was launched again after environment restoration (`/workspace/cloud-mail-packaged-launch-current.log`, `/workspace/cloud-mail-packaged-current.png`).


## Hosted Android install/startup verification

[Run 39346174056](https://github.com/bstbst0/cloud-mail/actions/runs/39346174056), runtime commit `0dee16d`, passed the disposable API 35 x86_64 emulator job. It installed the actual debug APK, launched `MainActivity`, checked the native UIAutomator tree for Server URL and Sign in, confirmed the app process remained alive, and uploaded screenshot/XML/runtime logs as artifact `12231348064`. The runtime error log was empty. The actual screenshot was retrieved and visually inspected; it is preserved as `screenshots/android-api35-startup.png`. This proves installation and native startup, not a device-level mail send or permission/background test.


Desktop packaging now includes the original Cloud Mail icon in PNG/ICO/ICNS form. Windows uses per-user installation, Start-menu/desktop shortcuts and a stable upgrade UUID; the window uses the same branded classpath image. Linux strict offline packaging passed after this asset change (`cloud-mail-branding-build.log`); the new configuration including booted-simulator test mode passed Gradle configuration validation (`cloud-mail-final-config.log`). Windows/macOS icon/shortcut installer changes subsequently passed hosted packaging (see current checkpoint above). These changes do not alter mail-domain behavior.


The final branded DEB was extracted to `/workspace/cloud-mail-branded-current`. Its log explicitly records `Cannot create Linux GL context` and fallback to another renderer; hardware GL initialization did **not** pass on the dummy server. The resulting software-rendered 800×600 sign-in form was visually inspected in `/workspace/cloud-mail-branded-window.png`. X11 window `0x400007` had `_NET_WM_PID=2015`, matching the running branded launcher process, so this image is evidence for the new executable rather than an older window. This establishes successful software-rendered startup, not an exception-free log or hardware-accelerated rendering. The table identifies this branded DEB. An automatic review initially rejected imprecise exception-free startup wording; the wording was corrected and matched process/window evidence was obtained before retrying the commit.
