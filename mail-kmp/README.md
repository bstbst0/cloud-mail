# Cloud Mail multiplatform client

Cloud Mail uses Compose Multiplatform for Android, iOS and JVM desktop (Windows, macOS and Linux). It shares existing Worker/D1/R2/Resend data through the additive `/api/app/v1` API. The Vue client remains independent.

## Development

Install JDK 21 (a complete **JDK**, not a JRE), Android SDK 35/build-tools 35, and Xcode on macOS for iOS. Gradle emits JVM 17 bytecode. Configure Android SDK via `ANDROID_HOME` or a private `local.properties` (`sdk.dir=/path/to/android-sdk`).

```sh
./gradlew :composeApp:desktopTest
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:run
./gradlew :composeApp:packageDistributionForCurrentOS
```

A default endpoint can be embedded with `-PcloudMailServerUrl=https://mail.example.com`; users may edit it on the login screen. Local development HTTP requires the explicit flag `-PcloudMailAllowLocalHttp=true` when building Android Debug or running Gradle Desktop `:composeApp:run`. Only localhost, 127.0.0.1 and Android emulator host 10.0.2.2 are accepted. Android release and packaged Desktop apps do not inherit the development HTTP bypass. iOS reads the optional `CLOUD_MAIL_SERVER_URL` Xcode build setting and keeps HTTPS enforced.

Windows uses `gradlew.bat`. Desktop installers are host-specific; build DMG on macOS, MSI on Windows, and DEB on Linux. Android APK is `composeApp/build/outputs/apk/debug/composeApp-debug.apk`. For iOS open `iosApp/CloudMail.xcodeproj`, scheme `CloudMail`; frameworks are built by the Xcode integration script. Framework-only check: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` on macOS.

Sign in with your existing account and the HTTPS URL of the Worker hosting the reviewed app API migration. Passwords are never saved. Production runtime rejects HTTP; the adapter permits explicit localhost HTTP only when enabled by tests/development code. No Resend credentials belong in the app.

## Architecture

`commonMain/com/cloudmail/app` holds serialized models, the Ktor API adapter, encrypted SQLDelight storage, repository synchronization and `MailViewModel`. `presentation` holds adaptive Material 3 Compose UI. Platform source sets implement secure storage, authenticated encryption, network engines, safe mail rendering, files and notifications. Manual constructor injection supports tests without duplicate platform business logic.

Each SQL entry is keyed by server and user ID and encrypted before storage. A sync page and cursor commit in one SQL transaction. Full resets preserve drafts and queued intentions. Actions carry stable operation IDs and a persisted creation sequence, replay in order, and overlay authoritative server state until acknowledged. Removed accounts erase their cached content. Tombstones clear bodies and attachment metadata. Logout cancels ongoing authenticated work, attempts server revocation, wipes local content and the token. Session expiry requires reauthentication.

Send operations are persisted before the first request and never automatically retried. A lost response locks the draft for status lookup. Definitive rejection restores an editable draft. Pending or uncertain sends require checking the same operation ID, including after app restart.

Android uses Keystore; iOS uses Keychain. Desktop uses the available OS credential store (DPAPI on Windows, Keychain on macOS, Secret Service on Linux). When secure persistence is unavailable, desktop uses a clearly disclosed memory-only session and cache: no insecure token file is created. See `docs/kmp/platform-security.md` and the implementation report for platform verification status.

HTML rendering disables scripts, storage, application token access and arbitrary navigation. Remote resources are blocked by default. Desktop provides inert textual HTML fallback, with the original plain text mode also available. Attachment downloads are bounded at 25 MiB while streaming. Outgoing attachments are bounded per file and in aggregate; picker permissions are scoped to user-selected files.

## Reproducible local tests

Tests use Ktor `MockEngine`, isolated generated fixtures and in-memory SQLDelight SQLite. They never contact a production server or send external mail. `commonTest` checks serialization, URL validation and state mapping; `desktopTest` verifies transactional cursors, account isolation, ordered offline replay, cross-device convergence, paginated search, uncertain send recovery, view-model flows and real Compose UI interaction/screenshots. UI tests require a graphical display (Linux: `xvfb-run ./gradlew :composeApp:desktopTest` or another virtual X server).

Run existing Worker tests and Web builds from their own directories before release. Apply the reviewed additive D1 migration only to an isolated database during development. Production deployment, migration and real sending are separate operator-controlled steps.

Dependency versions are centralized in `gradle/libs.versions.toml`: Kotlin 2.2.20, Compose 1.9.1, AGP 8.11.2, Ktor 3.3.0, SQLDelight 2.1.0 and Lifecycle 2.9.4. These are stable releases available from Google/Maven Central. Dependency checksums are recorded in `gradle/verification-metadata.xml`; any update requires reviewing the resolved artifacts and regenerate checksums intentionally. Official compatibility references: [Compose compatibility](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html), [Kotlin releases](https://kotlinlang.org/docs/releases.html), [AGP 8.11](https://developer.android.com/build/releases/past-releases/agp-8-11-0-release-notes).
