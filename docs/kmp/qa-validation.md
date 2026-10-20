# QA evidence and reproducible checks

Initial verification: 2026-10-13. Acceptance/CI review updated 2026-10-20. Environment: isolated Linux workspace, Node 24.19.0, plus the explicitly identified hosted GitHub Actions jobs below. No production credentials, deployed databases, object buckets, DNS/MX, or external mail recipients were used. Current native build results belong in [build-validation.md](build-validation.md); the final cross-component result belongs in `implementation-report.md`. Checked-in tests and CI configuration alone are not passing results.

## Worker integration suite

`mail-worker/test/app-v1.spec.js` exercises the actual Hono routes through the actual Worker `fetch` entry point. `@cloudflare/vitest-pool-workers` executes real local Workers D1, KV, and R2 bindings. It applies the reviewed migration with the official `readD1Migrations` / `applyD1Migrations` helpers. Synthetic credentials are hashed by the application's password implementation. Outbound Resend requests are intercepted with `cloudflare:test` `fetchMock`; every unmocked external request is denied.

The test scenarios cover:

- Password login, fixed 30-day JWT expiry, raw Authorization syntax, invalid password, expired credentials, device-specific logout, password-change and administrative-ban revocation, and login rate limits.
- Legacy salted SHA-256 verifier compatibility, independently salted versioned PBKDF2 password writes and wrong-password rejection.
- Legacy session compatibility, exact anonymous route boundaries, role-gated account/send/delete access, cross-user message/reply/action/cursor isolation.
- Numeric attachment access, R2 payload download, forced download disposition and MIME protection, cross-user rejection, deleted parents, the formerly public `/attachments/*` bypass, a real 25 MiB + 1 byte oversized object with underreported metadata, and malicious filename/header suppression.
- Initial synchronization of 127 messages, completion at a fixed watermark while new mail arrives, signed/forged/epoch-invalid cursor recovery, and no duplicate stable remote IDs. Current v1 responses cap pages at ten events, giving thirteen pages for this fixture; the earlier twenty-event implementation completed in seven.
- Real SQL writes representing existing Web changes: read/unread, star, attachment addition, account transfer, message transfer, physical message purge and physical account deletion. Old owners receive body-free tombstones.
- Idempotent read/star/delete/restore actions, ledger persistence, replay with changed payload returning 409, and concurrent identical/conflicting operations.
- Server-side body search, user isolation, sent/attachment/read filters, pagination, invalid query handling.
- Concurrent send claim, provider idempotency header, CC/BCC forwarding, reply threading headers, one sent copy, unchanged replay, changed-body rejection, uncertain provider response, no blind replay, internal delivery, BCC privacy, attachment duplication with per-owner metadata, and atomic quota reservation.

Commands used in this sandbox:

```sh
cd mail-worker
XDG_CONFIG_HOME=/tmp/cloud-mail-config XDG_CACHE_HOME=/tmp/cloud-mail-cache npm test
```

The sandbox grants local network access for the Workers runtime's loopback service sockets. This is unrelated to production network access. On a normal workstation, `npm test` is sufficient after dependencies and Web assets are installed.

Observed runs: original baseline 3/3 passed; integrated suite 19/19 passed; expanded suite 25/25 passed; quota/rate-limit/legacy-data expansion 28/28 passed; final attachment-resource/password-compatibility suite 31/31 passed (26 app tests, 2 credential tests and 3 original tests). These tests verify backend and provider simulation, not an installed Android/iOS/Desktop app using a deployed server.

The 2026-10-19 14:21 UTC Worker checkpoint passed 35/35 tests. The subsequent completed verification passed **36/36 tests across three files**, also confirmed by hosted CI on commit `c8b1c6062c30adbad842560c36633aaa78e545f2`. See [backend-implementation.md](backend-implementation.md). This includes authorized inline-image bytes/DTOs, Unicode attachment keys decoded exactly once, deleted inline parents, CRLF Message-ID rejection, equality between the provider's outgoing Message-ID header and the persisted sent copy, and long/literal/Unicode search queries. Later source changes require a fresh result; historical counts must not be silently carried forward.

## Web compatibility checks

The existing `npm run build` release build completed successfully using the existing Vue/Vite pipeline, emitting the SPA and PWA assets to `mail-worker/dist`. Browser security tests added by integration use real headless Chromium and synthetic hostile HTML. Run:

```sh
cd mail-vue
pnpm install --frozen-lockfile
pnpm exec playwright-core install --with-deps chromium
CHROMIUM_PATH=/path/to/chromium npm test
npm run build
```

The CI workflow selects the Playwright-downloaded Chromium executable. On this workspace, the installed system browser is `/usr/bin/chromium`. Browser tests inspect actual script and tracker suppression, remote-image consent, and safe quoting; they do not merely compare sanitizer source code.

The current browser suite additionally tests that raw Authorization is sent only to the configured API, encoded Unicode filenames remain usable, and attachment streams cancel at the byte limit without trusting Content-Length. Its current-run count and the final release-build outcome must be recorded by the runner after execution.

## Native acceptance evidence

| Scope | Implemented validation | Evidence boundary |
|---|---|---|
| DTOs, URL validation, rich-text formatting | Shared Kotlin tests | Check the current test report; source existence is insufficient. |
| Cache, ordered offline actions, reset, isolation and pagination | SQLDelight/JVM repository tests | Uses isolated transport fixtures and a real local SQLite driver. |
| Login/read/reply/send/logout and uncertain-send state | ViewModel tests | No production credentials, provider or device OS dialogs. |
| Compact/expanded, light/dark, large text, loading/error/offline and shortcuts | Real Compose Desktop tests and PNG capture | Requires a working graphical display. A successful application compilation does not establish UI correctness. |
| Native client to the actual Worker | `WorkerHttpIntegrationTest` using CIO and `test/http-fixture.mjs` | Opt-in localhost integration; no deployed server or external delivery. |
| Platform encryption and lifecycle | Desktop/Android tests; iOS tests on macOS CI | JVM tests cannot establish Android Keystore or iOS Keychain behavior on devices. |

The real HTTP test deliberately skips when `CLOUD_MAIL_E2E_URL` is absent. Linux Desktop CI now starts the isolated Worker fixture, checks its authenticated route returns 401, and sets the exact accepted URL before running the test. A failed fixture startup fails the job instead of silently reducing coverage. The fixture's outbound service denies external network requests.

```sh
# From the repository root, after installing mail-worker dependencies:
node mail-worker/test/http-fixture.mjs
# In a second terminal:
cd mail-kmp
CLOUD_MAIL_E2E_URL=http://127.0.0.1:8789 \
  ./gradlew --dependency-verification=strict :composeApp:desktopTest \
  --tests com.cloudmail.app.WorkerHttpIntegrationTest
```

This combined test covers real native serialization/raw-token injection, login, account isolation, reading, attachments, disconnected queue preservation, reconnect/cross-client convergence, internal send/reply and deduplication, delete tombstones, logout/revocation and cache removal. It does not prove actual Resend delivery, installed-device interactions or every layout.

The backend runner separately completed a real HTTP Python smoke test on the isolated Miniflare fixture: two-user login, sync/read, internal send with attachment, idempotent replay, recipient search/download and logout followed by 401 all passed. That result establishes the fixture/Worker HTTP path; only an executed `WorkerHttpIntegrationTest` establishes the Kotlin CIO/repository path. Fixture setup is documented in `mail-worker/test/HTTP-FIXTURE.md`.

## Problems found and fixed during verification

1. Initial `pnpm install` tried writing outside sandbox home directories. Moving only task-specific XDG/PNPM caches into `/tmp` made installation possible.
2. pnpm's dependency lifecycle policy required explicit named build permissions. The package-local `pnpm-workspace.yaml` files allow only the toolchain's `esbuild`, `workerd`, `sharp`, Vue compatibility and file-watcher lifecycle scripts. Frozen locks remain mandatory.
3. Original Worker tests could not start until Vue generated the configured asset directory. CI runs Web regression build before Worker tests.
4. `db.exec(migration)` splits SQL by lines and rejects comments/multiline triggers. Tests now use Cloudflare's migration parser and transactional migration helper, so the actual trigger syntax is validated.
5. Legacy `email.unread=1` means read. Fixtures were corrected against observed production semantics; app DTOs use unambiguous `read:Boolean`.
6. An action ownership check before an awaited transaction was insufficient during account transfer. Runtime mutations and operation insertion now recheck active-account ownership inside the D1 batch.
7. Concurrent native sends could otherwise spend a role quota twice. Quota reservation is now guarded atomically before contacting the provider, and tested with two parallel sends at a one-message allowance.
8. Existing ORM/domain diagnostics risked exposing mail data and identifiers. Runtime ORM SQL parameter logging and the domain comparison diagnostic were disabled.
9. A subsequent logger edit left a closing parenthesis inside a JavaScript comment; the test startup rejected invalid syntax. The expression was repaired and the full 28-test passing run verified the fix.
10. Attachment download headers trusted metadata size instead of actual storage size, risking truncated downloads. Headers now use the actual size or omit it. The actual-object size limit test also exposed an unclosed R2 stream on rejection; cancellation before returning 413 restored isolated storage cleanup. The final 31-test run passed without test-runner unhandled errors.

## CI and unverified environments

`.github/workflows/kmp.yml` includes Android APK/unit tests and lint, Linux/Windows/macOS desktop tests and packaging, macOS iOS framework/native tests and unsigned simulator app build, Worker integration tests, Web build and browser tests, Gradle wrapper validation, strict dependency verification, JavaScript syntax checks and static whitespace/conflict-marker checks. Linux desktop UI tests run under Xvfb; screenshots and test reports are uploaded as CI evidence. The YAML parser accepted the workflow, all current Worker source files passed `node --check`, and `git diff --check` passed. Creating a workflow is not evidence that a hosted run passed.

### Actual hosted CI checkpoint

[PR #19 run 39269479721](https://github.com/bstbst0/cloud-mail/actions/runs/39269479721), commit `c8b1c6062c30adbad842560c36633aaa78e545f2`, is an observed run, not a future plan. Logs were inspected on 2026-10-20. This checkpoint predates later fixes and must not be presented as their validation.

| Job | Observed result | What the log proves |
|---|---|---|
| Worker/Web | Passed | Existing Vue release build completed; Chromium security tests 6/6 passed; Worker tests 36/36 passed; static checks passed. |
| Linux Desktop | Failed | Kotlin/native desktop compilation reached tests; 45 tests completed with two failures: oversized-attachment early rejection and expanded empty-state assertion. Packaging was gated by the failed tests. |
| Android | Failed | SDK setup succeeded after explicitly replacing the removed `tools` package. Resource processing stopped on missing strict-verification entries for the AGP aapt2 Linux JAR/POM. No hosted APK or test pass was established. |
| Windows Desktop | Failed | SQLDelight generation stopped on the missing strict-verification entry for `desktop-jvm-windows-x64-1.9.1.pom`. No Windows compilation/package pass was established. |
| iOS / macOS Desktop | Cancelled | Jobs did not produce Apple build/test logs. Cancellation is not a passing or failing native compilation. |

The preceding run on `10c849e` also exposed three missing classpath descriptor checksums and setup-android's obsolete default `tools` package. These were repaired before the `c8b1c6` checkpoint. The build owner subsequently added independently checked official Google aapt2 classifier/POM entries and Compose Desktop host POMs for the next run. Strict verification remains enabled. Android/iOS/Desktop validation reports, test XML and verification reports are uploaded even after failure so subsequent defects remain reviewable. Feature-branch push runs were removed; pull-request updates still run the matrix without duplicate push-triggered jobs.

### Completed 2026-10-20 hosted verification

[Run 39345486581](https://github.com/bstbst0/cloud-mail/actions/runs/39345486581), commit `64fd512c0bbeab87dc0b91f2de87b475e3eb1f48`, completed with five passing jobs and an iOS toolchain-verification failure. The actual test-result XML archives were downloaded and parsed, rather than inferring counts from the green job badge.

| Platform/job | Executed result | Published artifact |
|---|---|---|
| Linux x64 Desktop | **46 passed, zero failed/skipped**; real Worker HTTP integration executed; all seven Compose UI tests passed; DEB packaging passed | Desktop package artifact `12230279065`; test/screenshots artifact `12231411272` |
| Windows x64 Desktop | **45 passed, one skipped**; all seven Compose UI tests passed; MSI packaging passed | Package `12230687836`; test/screenshots `12231495650` |
| macOS ARM64 Desktop | **45 passed, one skipped**; all seven Compose UI tests passed; DMG packaging passed | Package `12231086546`; test/screenshots `12230777749` |
| Android | **19 unit tests passed**, zero failed/skipped; lint and installable debug APK build passed | APK `12231017925`; validation reports `12230373939` |
| Worker/Web | **36 Worker tests and six browser security tests passed**; existing Vue release build passed | Job logs on the linked run |
| iOS | Blocked before Kotlin source compilation by the missing strict checksum for `kotlin-native-prebuilt-2.2.20-macos-aarch64.tar.gz` | Failure/verification reports; no framework or app success claimed |

The single Windows/macOS skip is the explicitly Linux-hosted `WorkerHttpIntegrationTest`, not a skipped UI test. Each Desktop host uploaded twelve PNGs. Android lint reported zero errors and 36 non-fatal warnings, mainly dependency freshness and style; native Android Keystore/device interaction is not proven by JVM unit tests. Building an MSI/DMG/DEB is not proof of installation, signing or notarization. macOS Intel and iOS simulator/device execution remain separately unverified at this checkpoint.

For the next iOS run, the build owner downloaded the official macOS ARM64/x64 Kotlin/Native compiler archives, calculated their SHA-256 and compared them with the published SHA-1 values before adding the strict metadata entries. This is a toolchain integrity repair, not evidence that iOS source compilation or runtime tests passed. The earlier cancelled Apple jobs were explicitly reported by GitHub as not acquired after multiple attempts due to ARM64 runner capacity; the `64fd512` Apple jobs did acquire real macOS ARM64 runners.

The 2026-10-19 metadata review found missing Windows/macOS Skiko host checksums. The build runner added Windows x64, macOS x64 and macOS ARM64 JAR/POM entries from official Maven Central and checked their bytes against published digests. iOS KLIB entries are present. This repairs the identified static gap; it does not prove that every target's transitive resolution or compilation succeeds. Keep strict verification enabled and review any newly rejected artifact instead of using lenient/off modes or blanket trusted groups. See [GitHub-hosted runner architecture](https://docs.github.com/en/actions/reference/runners/github-hosted-runners): current `macos-latest` is ARM64, matching `iosSimulatorArm64`.

This Linux workspace cannot prove an Xcode/macOS build or Windows installation. Android, desktop compilation/UI and shared Kotlin test evidence are recorded by the agents that run those commands in the final implementation report. Real Resend delivery, platform credential stores on each OS, mobile permission dialogs, APNs/FCM, app signing, accessibility with a screen reader and cross-device use against a deployed migration require the release checks below.

Practical capability boundaries: Desktop uses a safe text fallback for HTML and offers share as export/path-copy/file-manager handoff. Drafts are local, not synchronized to Web. Desktop polls while running; native Desktop notifications and synchronization after application exit are not implemented. Passkey and FCM/APNs push integrations are not implemented; password login and OS-permitted mobile polling are the core paths. App-store signing, verification of packaged third-party notices and hosted multi-OS results remain release gates. Integration added bundled notice text and an iOS AppIcon catalog after the static review identified their absence; their source existence does not replace package/device inspection.

## Release checks

- Review additive migration and rollback procedure; back up the production D1 and apply only with explicit deployment authorization.
- Disable public attachment R2/S3 custom-domain exposure; authenticated app routes cannot protect independently public object URLs.
- Run hosted platform CI on the exact commit and review dependency checksums before signing artifacts.
- Verify a private test deployment with two synthetic users and two independent clients, including logout/cache removal, offline queue restore and uncertain send UI.
- Run platform file picker, download, open/share, safe HTML and image-consent checks on Android, iOS, Windows, macOS and Linux.
- Enable and verify platform background scheduling/notification permissions using synthetic received mail; no duplicate notifications after resync.
- Sign release binaries with operator-provided secrets outside the repository. FCM/APNs credentials are optional and must not block core mail usage.

## Optional formal scan capability

The `codex-security:security-diff-scan` skill was consulted for a final immutable-range review (`cc6c46e..ff8f498`). Its [SKILL.md](skill://Plugin_1e648473be9c8191a91ac3947151af55/security-diff-scan/SKILL.md) requires: “Run the `security_diff_scan` preflight … before reviewing files or creating a goal.” The referenced preflight resources could not be read through the skill provider, and no scan-start/preflight tools or local plugin launcher were available. Consequently no formal Codex Security scan, scan ID, SARIF or certification is claimed. Existing scoped manual reviews and executed authorization, renderer, session, crypto, resource-limit and race regressions remain the security evidence; production gates are unchanged.

## Current runtime commit `ff8f498`

Hosted run [39454388269](https://github.com/bstbst0/cloud-mail/actions/runs/39454388269) has passed Worker/Web, Android, Linux Desktop, Windows Desktop and Android emulator startup. Linux validation artifact `12279414565` was downloaded and its JUnit XML parsed: **50 tests, 0 failures, 0 errors, 0 skips** (Repository 18, ViewModel 9, Contract 5, UI 7, DesktopSecurity 3, RichText 6, Worker HTTP 1, disk persistence 1). Windows packaging artifact `12280111523` and current APK artifact `12280680650` were produced. Apple jobs were still queued when this checkpoint was written; their successful execution is not inferred from the other platforms.

The six compiler diagnostics from the preceding actual iOS attempt are addressed in the source: isolated about:blank navigation no longer references the unavailable enum member; secure random uses a typed pinned pointer directly; mutable notification content uses explicit Objective-C setters. The new hosted step builds to a deterministic DerivedData path, installs the resulting app on an available iPhone simulator, launches it, checks process liveness and captures a screenshot. This step remains unverified until an actual Apple runner completes it.


### Apple native execution on `ff8f498`

Actual job `118737272647` completed Kotlin/Native compilation and linked `CloudMail.framework`, then linked and executed the native test binary. All 11 shared contract/rich-text cases passed. Three unchanged security cases failed with `errSecNotAvailable (-25291)` because the default standalone simulator process had no usable Keychain service. This is recorded as a failed native test run, not a pass. Artifact `12280887959` was downloaded and its failure XML inspected.

The harness now boots a real iPhone simulator, selects its UUID explicitly and configures the verified Kotlin 2.2.20 `KotlinNativeSimulatorTest.standalone=false` API. Crypto/Keychain assertions are retained unchanged, with no ignore, skip or insecure storage fallback. CI independently attempts framework, Xcode/app startup and native tests, so a failure in one does not conceal the other available checks. macOS Desktop completed successfully in this same run.


## Hosted verification of a5cc6d2

[Run 39458557129](https://github.com/bstbst0/cloud-mail/actions/runs/39458557129) passed Worker/Web, Android, Android API 35 emulator startup, and all three Desktop test/package jobs. Linux JUnit artifact `12280509692` was downloaded and parsed: 50 tests, zero failures/errors/skips (Repository 18, ViewModel 9, Contract 5, UI 7, Desktop security 3, rich text 6, actual Worker HTTP 1, disk persistence 1). Windows/macOS installer branding and per-user Windows packaging now have hosted build evidence. This does not establish signed installation or native-vault manual acceptance. iOS results are recorded separately once its job finishes.

The iOS job in that run completed with two failures: the Xcode host reached its final link but lacked `-lsqlite3`; the 14 native cases still had three Keychain failures, now `errSecMissingEntitlement (-34018)` rather than unavailable security services. No test was removed or suppressed. The repair adds the host's SQLite link, an ad-hoc identity and isolated Keychain group to the simulator test executable, and a separate ad-hoc simulator app identity before installation. Release signing still requires the operator's Apple configuration. CI reuses the already-built framework for the Xcode host to avoid the observed second 13-minute Kotlin framework rebuild. Native `codesign`/Xcode execution of this repair awaits the next macOS run.


The corrected `105c901` run 39464590580 passed Worker/Web, Android, Windows, Linux and actual Android emulator startup. Downloaded JUnit artifacts 12285305399 / 12283484503 confirm Android 19/19 and Linux 50/50, zero failures/errors/skips. Both Apple jobs were cancelled **before runner acquisition**, with GitHub annotations “The job was not acquired by Runner of type hosted even after multiple attempts” and explicit macOS ARM64 capacity warnings. This is an infrastructure failure, not a passing or failing source/test result.

The workflow now uses the documented `macos-15-intel` pool and installed Xcode 16.4. It retains ARM64 simulator framework compilation and additionally builds/tests an Intel simulator framework using the same iosMain/iosTest code and isolated Keychain entitlements. Xcode host build explicitly selects x86_64 only for this simulator job; device and ARM64 targets remain available. Desktop macOS CI also uses the independent Intel pool. No production target or test is removed. [Official runner image catalog](https://github.com/actions/runner-images#available-images) and [installed macOS 15 tools/runtimes](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-Readme.md) were checked before selecting this fallback.

Intel dependency resolution succeeded against the configured official Google/Maven Central repositories. All 1,595 existing SHA-256 records were preserved; 189 new records were added. 128 added artifact digests additionally matched publisher Gradle-module SHA-256 values. Platform/test configurations were resolved explicitly, and a separate strict offline resolution verifies the resulting metadata; no verification bypass is used in CI. This validates dependency integrity/configuration, not Apple compilation or execution.


The earlier Xcode 26 link emitted third-party Skiko object deployment-version warnings (17.2 versus the host's declared 15.0). This is not evidence of verified iOS 15 runtime support. Before release, validate the oldest supported OS and reconcile the host/framework/vendor minimum versions, raising the deployment target if required. The simulator checks use newer OS runtimes and cannot certify the lower bound.


At `b8f4e14`, run 39467778176 passed all six non-iOS jobs, ARM64 and Intel iOS framework compilation, and the Xcode simulator app build (the SQLite linkage repair worked). The unbounded simulator startup script then remained in progress for over 10 minutes without completing; the run was cancelled to recover diagnostics. Startup and Keychain test success are **not** claimed. The follow-up adds per-command process-group timeouts and stage labels, requires a screenshot after the live-PID check, leaves the simulator running for native tests, bounds cleanup/log collection, and boots only after compilation. ARM64 compilation and Intel runtime validation now use separate parallel jobs. The Unix timeout helper's normal-exit and forced-timeout paths passed locally; shell syntax, workflow YAML and Gradle configuration are checked separately from real Apple execution.
