# Cloud Mail KMP implementation report

Integration checkpoint: 2026-10-20 (Asia/Taipei). Review is available in [Draft PR #19](https://github.com/bstbst0/cloud-mail/pull/19). Local verification is complete; hosted platform verification is still being resolved. It is not a claim that all release acceptance criteria have passed. No production deployment, migration, external test mail, DNS/MX change or production-data mutation has been performed.

## Delivered source

- `mail-kmp`: shared Compose Multiplatform UI and domain/data implementation for Android, iOS and JVM desktop. Version catalog and Gradle wrapper pin stable toolchain versions; platform source sets isolate native dependencies.
- Ktor raw-JWT adapter for the existing Worker, server configuration, fixed-expiry native login/logout, isolated encrypted SQLDelight cache, initial/incremental sync, persistent cursor, ordered optimistic offline actions, account isolation, tombstones and reset recovery.
- Adaptive compact/list-detail/three-column mail workspace, inbox/sent/starred/address filtering, full server search and offline filters, mail detail, read/unread/star/delete/confirmation/Undo, keyboard shortcuts and context actions.
- Plain text and isolated native HTML readers with scripts/navigation disabled and external images blocked by default. Desktop uses a complete inert textual HTML fallback. Inline images use owner-authorized numeric attachments and bounded encrypted cache hydration.
- Rich-text composer, From/To/CC/BCC, reply/reply-all/forward, multiple attachments with count/size limits, local draft restoration and durable send operation IDs. Uncertain sends use status lookup rather than automatic resending.
- Android Keystore, system-scoped file picker, FileProvider open/share, background WorkManager polling, notifications and message deep links. iOS Keychain, file/share adapters, WKWebView and system background scheduling source plus an Xcode host. Desktop OS vault adapters and a disclosed memory-only fallback when no secure vault is available.
- Settings for theme, dynamic color, font scale, gestures, sender, sync and reading preferences; bundled license text in the app's About dialog.
- Existing Vue mail rendering and attachment access updated to use isolated HTML and authorized downloads; existing Vue architecture retained.

## Backend and migration

The additive `/api/app/v1` contract includes login/logout, accounts, sync, message detail/search, actions, send/status, attachment access and device sessions. Existing route/envelope/raw Authorization conventions remain compatible. See [API contract](api-contract.md) and [architecture audit](architecture-audit.md).

`mail-worker/migrations/0001_app_v1.sql` adds sessions, rate counters, operation ledgers, synchronization epoch/events/indexes and triggers on existing mail/account/star/attachment writes. Initial seed and trigger events share a monotonically increasing sequence; fixed cycle watermarks avoid depending on mail IDs or timestamps. A page and its cursor commit atomically in the client. Ownership is rechecked inside backend mutation batches. Physical deletion/account transfer yields body-free tombstones for previous owners.

The migration has been exercised only against isolated local D1. Deployment and rollback instructions are in [backend migration](backend-migration.md). The previous release cannot safely be restored blindly after stronger password hashes are written; retain the dual password verifier during rollback. Change/event/operation retention is intentionally append-only until a separately reviewed compaction/reset policy exists.

## Security and compatibility

Native sessions have fixed expiry and D1 revocation; legacy no-exp JWT/KV Web sessions remain supported during transition. New/reset passwords use versioned PBKDF2 while legacy hashes still verify. Exact anonymous route boundaries replace prefix exemptions; test routes and per-user mail/reply/star/attachment ownership are checked. Download sizes and names are bounded and protected with safe headers. Resend keys stay exclusively on the server. See [platform security](platform-security.md) for encryption/vault/logout and renderer details.

Public R2/S3 bucket or custom-domain exposure must be closed by the operator; authenticated routes cannot revoke independently public URLs. Exported user-selected files are user-owned and are not deleted at logout. Private cached/shared temporary data is cleared. Desktop portable sharing exports a file and offers its path/file manager rather than claiming a universal native share sheet.

## Verification status

| Item | Evidence at this checkpoint | Status |
| --- | --- | --- |
| Worker routes, D1/KV/R2, mocked provider | 36 passing isolated tests; long-search, threading, resource limits and Retry-After regressions included | Implemented and verified locally and in hosted Worker CI |
| Vue build | Release build and 6 real Chromium security tests passed again; hosted Worker/Web job also passed | Implemented and verified |
| Shared/desktop Kotlin | Shared/Desktop tests passed across full and targeted runs, including Repository 18, ViewModel 9, Contract 5, UI 7, Security 3, Rich text 6, HTTP E2E 1 and disk persistence 1: 50 unique cases | Implemented and verified on Linux JVM |
| Android | 19 unit tests passed, lint 0 errors, current debug APK assembled and apksigner verified v2 signature | Build/unit/lint verified; actual API 35 emulator install and native startup passed |
| Linux desktop | Strict DEB packaging passed; extracted bundled executable launched and login screenshot inspected | Implemented, packaged and startup verified; installation/desktop vault integration remains a release check |
| Worker ↔ native HTTP end-to-end | Actual CIO-to-Worker test passed, including offline queue, second device, internal send/attachment/reply, idempotency and logout | Implemented and verified with isolated D1/KV/R2 |
| iOS | Actual Apple source and simulator framework build passed at ff8f498. Native suite ran 14 cases: 11 passed, 3 Keychain cases failed with errSecNotAvailable in standalone mode. Booted-simulator harness correction is awaiting rerun | Source/framework verified; Keychain tests and Xcode/app startup remain under verification |
| Windows/macOS desktop | Actual Windows and macOS hosted Desktop jobs passed, including UI/tests and native installer packaging | Build/test/package verified in CI; signed installation and OS integration manual checks remain |
| Hosted GitHub Actions | Current ff8f498 run 39454388269 passed Worker/Web, Android, Linux (50/50), Windows, macOS Desktop and Android emulator startup. iOS compiled and linked, then exposed a Keychain test-harness failure | Six jobs passed; iOS native harness/app startup still pending |

Detailed commands, failure/fix history and final APK/DEB hashes are in [QA validation](qa-validation.md) and [build validation](build-validation.md). Seven sanitized screenshots and actual interaction assertions are documented in [UI validation](ui-validation.md). Tests use synthetic mail. Provider tests intercept external calls; the HTTP fixture blocks all outbound Worker network and sends only between its synthetic local users. No live delivery claim is made.

## Reproduce native HTTP end-to-end verification

Install locked Worker dependencies and start `cd mail-worker && node test/http-fixture.mjs`. In another terminal:

```sh
cd mail-kmp
CLOUD_MAIL_E2E_URL=http://127.0.0.1:8789 ./gradlew --dependency-verification=strict :composeApp:desktopTest --tests '*WorkerHttpIntegrationTest'
```

The fixture uses only in-memory D1/KV/R2 and synthetic credentials; stop/restart resets it. The test refuses any other endpoint. It covers actual HTTP login, isolation, initial sync, body/attachment access, offline optimistic queue and reconnect replay, a second device, idempotent internal send with attachment, reply, search, deletion and device-specific logout. Normal unit runs mark this opt-in test skipped unless the fixture address is set.

## Not implemented / optional integrations

- Native Passkey login, APNs/FCM delivery and cross-device server drafts are not implemented. Password login, system-permitted polling and encrypted local drafts provide the core paths.
- Signed store releases, notarization and operator credentials are not provided. CI creates unsigned/debug review artifacts.
- A native desktop HTML browser engine and universal desktop share sheet are not provided; the documented secure text/export alternatives are used.

## Outstanding release verification and risks

Run the exact committed code through all hosted platform jobs and the [release checklist](release-checklist.md), including installed-app file permissions, native vaults, notification timing, accessibility, large mailboxes and a private staging Worker with two clients. Real Resend/S3, APNs/FCM and production compatibility-date behavior have not been verified. The local workerd currently falls back to compatibility date 2025-03-10. Windows/macOS/iOS runtime behavior cannot be certified from this Linux workspace.

Local test failures have been repaired and revalidated. The PR remains draft while hosted platform checks and device/staging release gates are outstanding. Android API 35 emulator installation and startup actually passed in run 39346174056; device mail workflows and native permission/background behavior remain release checks. iOS source/framework compilation passed; its Keychain integration tests need the booted Simulator security service. The harness correction and app startup check await hosted rerun.
