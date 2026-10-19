# Native build validation

Validation uses an isolated Linux x86_64 workspace, a complete JDK 21 at `/workspace/full-jdk21`, Gradle 8.14.3 wrapper, Android SDK/build tools 35, and Xorg dummy display `:99`. Test fixtures do not send real email or contact production. iOS, Windows and macOS require their native CI runners; Linux results do not establish those platform builds.

## Reproduction

From `mail-kmp`:

```sh
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
- Android APK assembly succeeded on current source, but Android unit-test compilation exposed an ActivityResultLauncher Kotlin property override mismatch; the platform owner is fixing it before validation is repeated.

## Latest results

The full current-source strict verification run is in progress. No in-progress or historical artifact is claimed as a current passing build. Detailed outcomes, counts and artifact hashes are recorded below after completion.
