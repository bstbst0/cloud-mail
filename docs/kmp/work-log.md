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
