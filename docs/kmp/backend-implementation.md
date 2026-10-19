# Native backend implementation status

Implemented in the existing Worker, without changing DNS/MX, deployment, production data or secrets:

- v1 API contract, DTO normalization and raw-JWT adapter compatibility.
- Fixed-expiry D1 native sessions, per-device revoke/list, password/ban/delete revocation triggers, login/search/sync/send resource rate limits. Legacy login adds a compatible KV account/IP throttle, with eventual-consistency limitation; native login uses atomic D1 counters.
- Existing mail/account models reused; server search scans authorized full subject/sender/recipient/body data with filters and keyset pages.
- Atomic D1 event triggers and initialization seed, signed user/epoch cursor, bounded cycle watermark, pagination, authoritative current-state/tombstone replay.
- Transactional operation ledger read/unread/star/delete/restore; ownership predicates checked inside mutation transaction, not only earlier reads.
- Durable send claims/fingerprints, Resend provider idempotency, CC/BCC forwarding and persistence, authorized reply threading, internal BCC isolation, atomic sent-message linkage and conservative uncertain outcome recovery. Native send quotas reserve atomically to avoid concurrent limit overspend; uncertain outcomes retain reservation and require manual reconciliation.
- Auth route prefix/test bypass removed; star ownership bug fixed; private owner-authorized KV/R2/S3 downloads and inline attachment bytes, sanitized filenames, resource limits, safe download headers; password reset length bug fixed; dual-format legacy/PBKDF2 password verification and stronger new/reset hashes; sensitive ORM parameter/domain logging disabled.
- Native inline image DTOs normalize authorized CID and local object URLs (including percent-encoded Unicode keys) to numeric attachment references. Inline send budgets use actual downloaded object bytes, so stale size metadata cannot bypass the aggregate attachment limit. JWT expiry rejects explicitly zero, null or nonnumeric values while preserving legacy tokens with no expiry claim.

Validation is performed by `npm test` against isolated Cloudflare Workers D1/KV/R2 and Resend test doubles in `test/app-v1.spec.js`. Actual counts/results are recorded by QA in implementation-report; source availability is not evidence of successful tests. Worker deployment/migration and real external delivery remain operator release gates.

## Verification rerun (2026-10-18 Asia/Taipei)

Executed `WRANGLER_LOG_PATH=/tmp/cloud-mail-worker.log XDG_CONFIG_HOME=/tmp/cloudmail-config npm test` with local network permission for the isolated Workers runtime: **3 test files, 33 tests passed**, exit 0, Vitest 3.0.9. Includes 28 native API/integration cases, 2 credential compatibility cases and 3 existing Worker cases. `git diff --check` passed. All recipients are synthetic and Resend calls are intercepted; no production bindings, remote migration, deployment or external delivery was used.

Initial rerun exposed a pagination fixture still assuming 20 records despite the intentional 10-event memory bound. Updated the fixture to verify all 127 remote IDs across 13 pages. Added Unicode object-key/CID DTO and private inline provider-payload/ownership/actual-size regressions. The Unicode denial fixture initially expected HTTP 404 on legacy `/oss`; corrected it to verify the unchanged HTTP 200/business-code 404 compatibility contract. Explicit zero/null/string JWT expiries are now rejected, with missing expiry still accepted for legacy tokens.

Installed local workerd supports compatibility date 2025-03-10 and warns that the configured 2025-06-04 falls back during tests. The intentionally rejected oversized-object stream also emits a non-failing `pump canceled` diagnostic. Staging on the actual configured Workers compatibility version and real provider delivery remain unverified release gates. R2 integration is exercised; S3 remote credentials and actual S3 behavior have not been exercised here.

Limitations: D1 ledger retains all events/actions/send records until a reviewed retention/reset protocol; native message query uses indexed owner/folder keysets plus LIKE body search rather than optional FTS. Provider interruption before persistence can yield uncertain send; it is deliberately not auto-resubmitted. Private attachment enforcement cannot override a public bucket/custom-domain configuration. Legacy no-exp sliding JWT sessions remain accepted as required for transition; native session lifecycle is strong but does not force all Web users to reauthenticate. Existing webhook signature/URL-secret initializer/Telegram capability routes need operator-reviewed follow-up beyond native paths. Polling/background capability and encrypted local storage belong to KMP platform code.
