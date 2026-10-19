# QA evidence and reproducible checks

Initial verification: 2026-10-13. Acceptance/CI review updated 2026-10-19. Environment: isolated Linux workspace, Node 24.19.0. No production credentials, deployed databases, object buckets, DNS/MX, or external mail recipients were used. Current native build results belong in [build-validation.md](build-validation.md); the final cross-component result belongs in `implementation-report.md`. Checked-in tests and CI configuration alone are not passing results.

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

Latest completed Worker verification, reported by the backend runner at 2026-10-19 14:21 UTC: **35/35 tests passed across three files, exit 0, 23.92 seconds**. See [backend-implementation.md](backend-implementation.md). This includes authorized inline-image bytes/DTOs, Unicode attachment keys decoded exactly once, deleted inline parents, CRLF Message-ID rejection, and equality between the provider's outgoing Message-ID header and the persisted sent copy. Later source changes require a fresh result; historical counts must not be silently carried forward.

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
