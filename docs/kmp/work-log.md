# Implementation work log

## 2026-10-13 — source and execution boundary

- Cloned public `bstbst0/cloud-mail`; clean upstream working tree. Created local branch `feat/cloud-mail-kmp`.
- Read root AGENTS.md, README, Worker entry/Hono/security/routes/entity/schema initialization, login/email/star/attachment/storage services and Vue request/axios/read/compose paths.
- Read managed cloud runtime skill/network policy. Network operations require an explicitly network-enabled command sandbox; inherited proxy and certificate trust retained. No production credentials configured.
- No production DNS/MX, routing, deployment, mail deletion, external mail send or database migration authorized/performed. All migrations are source artifacts for review; tests use isolated D1/KV/R2.
- Delegated by requested ownership: backend Worker/API/audit; KMP common data/build; Compose presentation; QA Worker tests/CI; Android/iOS platform. Root owns integration, Vue compatibility/security, cross-component review and final records. No overlapping file mutation without coordination.

## Decisions

- Add normalized versioned `/api/app/v1` contract; retain raw JWT header and legacy JSON envelopes.
- New app sessions have fixed expiration/revocation in D1; preserve legacy KV sessions to avoid forced logout.
- Atomic SQLite triggers record changes from existing Web/receive/provider paths. Signed, user/epoch-bound sequence cursors, fixed cycle watermark, tombstones and paged authoritative upserts. No timestamp-only or message-ID-only sync.
- SQLite/SQLDelight is only a client cache; shared account/mail authority remains existing D1. Bodies/drafts/queues encrypted with platform key; no Resend key in app.
- New send operation reserves user-scoped durable idempotency before provider invocation; ambiguous outcomes stay uncertain. Automatic retries cannot send another email.
- Web private attachment compatibility uses authenticated API fetch to bounded Blob, never token query strings or authenticated fetch to arbitrary mail URLs.
- Replace unsafe mail Shadow DOM with allowlist + iframe sandbox without script permission + restrictive CSP. Remote images default off with explicit consent. Reply quotes escape names/text.
- Stable Kotlin 2.2.20, Compose 1.9.1, AGP 8.11.2, Gradle 8.14.3, Ktor 3.3.0, SQLDelight 2.1.0 and Lifecycle 2.9.4 are being built for actual compatibility. Material 3; avoid experimental navigation dependencies.

## Validation ledger

Each final result is recorded in implementation-report.md; running a test does not establish untested platform or live provider behavior.

- Initial browser mail security suite: 4/4 passed in system Chromium (allowlist, no preprocessing tracker requests, sandbox/CSP, remote consent, text escaping).
- Android SDK acquisition underway; Java 21 available. Linux cannot execute Xcode/iOS/macOS/Windows packaging; macOS/Windows CI must verify those platforms.


## 2026-10-19–20: Integration, real runtime validation and PR

- Preserved source on `feat/cloud-mail-kmp` in separate Worker, KMP and Web/validation commits. Created Draft PR https://github.com/bstbst0/cloud-mail/pull/19 after the operator restored GitHub write authorization. No merge, production deployment or remote migration was performed.
- Worker validation grew to 36 passing tests. Fixed actual-byte inline attachment limits, Unicode object/CID mapping, invalid JWT expiries, wire/persisted Message-ID agreement and D1's short LIKE-pattern limit. Long literal searches now use bounded owner-filtered substring predicates. Rate responses include Retry-After.
- Added a localhost-only Miniflare fixture using the actual Worker, synthetic schema and official migration parser. Outbound Worker requests are denied. Native CIO/repository flow passed actual HTTP authentication, isolation, synchronization, offline replay, two devices, internal send with attachment, idempotent replay, reply, search, deletion and revocation.
- Fixed session generation/owner races, shared-lock logout/background cleanup, secure-session invalidation before cleanup, native-host ViewModel disposal, deterministic coroutine fixtures and bounded cancellable rate-limit waiting. All 29 repository/ViewModel/contract cases passed.
- Actual attachment regression exposed eager Ktor body buffering. Changed to `prepareGet().execute` so size checks precede reading and the 25 MiB streaming limit is effective. Declared and undeclared oversized-body tests both passed.
- Repaired compact/large-type layouts, duplicate expanded navigation semantics and expanded Search access. Seven real Compose UI tests passed; sanitized screenshots were inspected and preserved in `docs/kmp/screenshots`.
- Android 19 tests and lint passed; current APK signature validated. Linux DEB was built and its bundled executable actually launched. Initial package startup exposed missing JDBC modules in the jlink runtime; explicit modules fixed it. A separate actual SQLite-file/cipher close/reopen test passed for offline content, drafts, attachments, pending actions, cursor and logout clearing.
- Environment-specific Skiko extraction/font cache paths were made writable only in test invocations. Restricted host default WOFF2 font caused a Skia native crash; a test-only TrueType font configuration restored runtime validation. Production font configuration was not changed.
- Existing Vue release build and all 6 Chromium security tests passed again. Hosted Worker/Web CI also passed. Actual native CI revealed removed Android `tools` SDK package and host-specific strict checksum gaps; repaired without relaxing verification. Apple/Windows/macOS outcomes remain tracked in the PR and QA report, not inferred from Linux.
- Added bundled license texts, iOS icon assets and a hosted disposable Android emulator startup check. Apple compilation, emulator execution, signing, real provider delivery and platform/manual release checks must be reported only after actual completion.

## 2026-10-21: Simulator signing diagnosis

- Run 39516644123 at bfb9b33 passed seven platform jobs. Both simulator frameworks, the Intel native test executable and the Swift host compiled; iOS 18.5 boot completed. App launch was rejected by SBMainWorkspace; native tests failed with LaunchdSimError/SimXPCError 162 (code-signing issue). No native test cases executed in this run. The historical 11/14 result remains historical, not a passing current Keychain result.
- Disabled Xcode's separate debug dylib output so the unsigned host has one executable for explicit simulator signing. Added explicit isolated identifiers, simulator-only get-task-allow and strict signature verification/display to both launch paths, with bounded signing commands and visible native test events. DER generation is explicit; it is already the modern codesign default and is not established as the failure cause.
- Added Compose's required CADisableMinimumFrameDurationOnPhone plist value and an early CI guard. This independently fixes a Compose startup requirement; it does not explain the earlier pre-execution launchd rejection. No entitlement check, Keychain test or strict dependency verification was disabled. Actual Apple runtime results remain pending the next hosted run.
