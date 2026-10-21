# Isolated Cloudflare validation — 2026-10-21

## Scope and isolation

Verified the current native client against the real Cloudflare Workers, D1, KV and R2 services using **new resources only**, then deleted those resources. No production deployment, production migration, production data export/copy, DNS/MX/Email Routing changes, production secret changes, or external test email occurred.

| Resource | Temporary identifier | Final state |
|---|---|---|
| Worker | `cloud-mail-kmp-stage-20261021-0305` | Deleted (API HTTP 200) |
| D1 | `085f9819-de66-4b0f-8228-aa7b9a3a73e8` | Deleted (API HTTP 200) |
| KV | `7dbf726859154d6c954e00a149bc8a50` | Deleted (API HTTP 200) |
| R2 | `cloud-mail-kmp-stage-20261021-0305` | Two synthetic objects removed; bucket deleted (API HTTP 200) |

The temporary workers.dev endpoint is no longer available. The uploaded Worker used compatibility date `2025-06-04`, matching the repository configuration. Its bundle SHA-256 was `53d99d79ceebf1dc6d4c112b53eaa48851a5d113b4f1eba7d473bf8b63ab555c`.

The test entry imports the actual `src/index.js`, original database initialization/upgrade path and official migration parser. It adds a random secret header gate, run identity check and four-hour expiry, disables observability, exports only `fetch`, and replaces outbound `fetch` before modules execute. The deployed binding list was checked against the newly created D1/KV/R2 IDs. No provider/service binding, production secret, cron or email handler was configured. R2 managed public access was verified disabled. Credentials were kept in a private file outside Git; no credentials or real mail bodies are included here.

## Executed results

| Check | Actual result |
|---|---|
| Local bundle and Wrangler deploy dry-run | Passed; dry-run deployed nothing |
| Local staging harness with independent outbound denial | Passed: access gate, original schema initialization, migration and repeat migration |
| Real Cloudflare ungated request | HTTP 403 |
| Real Cloudflare gated identity and attempted outbound fetch | Correct run identity; outbound request blocked before network access |
| Original schema initialization on fresh remote D1 | Passed; two synthetic users, three synthetic messages, one R2 attachment |
| Official app migration and repeat migration | Passed; three messages/three seed events both times, no duplicate seed events |
| Actual KMP `WorkerHttpIntegrationTest` over HTTPS | **1 test, 0 failures, 0 errors, 0 skips**, 48.668 seconds, started 05:55:31 UTC |
| Legacy Web API compatibility | Passed: legacy login, read, star, cancel, delete; native cursor sees state updates and deletion tombstone |
| Protected routes | Passed: unauthenticated test route, login-prefix lookalike and message route rejected |
| Session revocation | Passed: legacy and native sessions independently revoked |
| Cross-user resource isolation | Passed: native message and attachment HTTP 404; legacy R2 key business-error code 404 |
| Full Linux desktop suite including Compose UI and localhost HTTP integration | **50/50 tests**, zero failures/errors/skips; passed with documented font configuration |
| Worker regression | **36/36 tests**, three test files, passed |
| Localhost KMP HTTP regression after staging extension | Passed, actual test executed; mutable-fixture Gradle cache bypass also verified |

The native HTTP test uses production `ApiClient`, `MailRepository` and local store implementations with synthetic platform ports. It verifies login/account isolation, initial sync, body and attachment download, cross-user detail rejection, deliberately disconnected transport, optimistic read/star actions, persistent pending queue and replay, second-device convergence, internal send with attachment and repeated-operation idempotency, recipient download, reply, server search, delete/tombstone convergence, server logout rejection and local store clearing. It is an integration test, not a GUI interaction test.

## Failures found and resolved

1. A fresh remote D1 contains Cloudflare's `_cf_KV` system table. The bootstrap empty-database guard initially returned 409. Excluding only that known system table fixed the guard; the database was inspected and contained no application tables before initialization. Local and remote initialization/migration then passed.
2. Direct network sockets are unavailable in this execution environment. The initial Node/CIO attempts failed with connection refusal. Node's environment proxy support and a **test-only**, staging-scoped CIO proxy resolved connectivity. No production client transport was changed.
3. Gradle initially reused a previous HTTP test result after changing fixture environment variables. That run was not counted as execution. The test was explicitly rerun, and opt-in HTTP tests now disable up-to-date/build-cache reuse. The updated Gradle configuration was executed successfully against the local fixture.
4. The first legacy attachment isolation assertion expected HTTP 404. Legacy APIs intentionally preserve HTTP 200 with a business-error envelope. The corrected assertion checks envelope code 404; no attachment bytes were exposed.

5. An expanded local UI regression initially crashed Skiko because the documented writable Fontconfig configuration was omitted. Repeating with `FONTCONFIG_FILE=/workspace/cloud-mail-fonts.conf` passed the complete 50-test suite. No test was removed or disabled.

## Reproduce safely

See `mail-worker/test/staging/README.md`. Create another uniquely named set of empty resources; the deleted identifiers above must not be reused as existing bindings. Keep credentials outside the repository. The staging harness must never become a production entrypoint or be added to the main deployment workflow. No production source file was changed by this validation work.

## Not verified by this run

- Real Resend/provider delivery, provider timeouts, external recipients, SMTP/Email Routing delivery, DNS, or production credentials. Internal synthetic delivery and operation idempotency were verified; outbound requests were deliberately blocked.
- A migration against a copy of the actual production schema/data, production volumes, load, latency targets or production backup/rollback. This rehearsal uses the repository's original initialized schema/upgrade path plus synthetic records.
- Android/iOS/Desktop GUI against this gated endpoint, signed distribution, physical-device native permissions, background delivery or notifications. Earlier platform CI evidence remains documented separately in `build-validation.md`.
- Public access through any existing production R2/S3 domain. Only the newly created staging bucket was inspected.

These are release gates, not completed checks. The existing PR remains open with auto-merge disabled; production remains untouched.
