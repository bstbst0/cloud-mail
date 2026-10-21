# Production backend release — 2026-10-21

User explicitly authorized production backup and deployment after isolated Cloudflare verification. Source deployed: `712c51a98790f8bba03cfe594e28994d000cca3f`. No PR merge or main-branch deployment workflow was triggered.

## Backup and recovery

- D1 `cloud-mail` (`9ef79abb-810c-466c-a04b-11b444c1b324`) full schema/data export downloaded before mutation.
- Private backup: `/workspace/cloud-mail-production-private/pre-kmp-backup.sql`, outside Git, private directory. **Contains sensitive production records; do not publish or commit.** The verified private cloud copy below also survives this execution workspace; an independently encrypted off-account copy is optional additional recovery protection.
- Durable private R2 copy: bucket `cloud-mail-private-backup-20261021-0614`, object `pre-kmp-backup.sql.gz.b64`. Created specifically for backups, managed public access disabled, no custom domains. Not attached to the mail Worker. **Retained for recovery, not deleted with test resources.** Uploaded size 2,111,260 bytes; object ETag `9cc298f6c4dbdd5b8186e5831eb789e9` matches local encoded-file MD5. Download through the authenticated Cloudflare dashboard/API, base64-decode then gunzip to recover the SQL; verify the SQL SHA-256 below. R2 default encryption at rest applies; this is not an independently client-encrypted backup.
- Backup bytes: **7,708,040**; SHA-256: `e325c8b056b03fd98a3f41aafeeab2c250b6594fb2876aa61e01639a78051ffd`.
- Restored into a private local SQLite database; `PRAGMA integrity_check` returned `ok`. Applied the reviewed migration twice to the restored copy; integrity remained `ok`, initial event count 380. No production mail content was printed or used in send tests.
- D1 export completed after retrying initial API errors. The separate Time Travel bookmark request returned Cloudflare HTTP 500; do not claim a separately recorded Time Travel recovery point. The downloaded export was the verified backup.
- Previous deployed Worker version: `51dbb253-ba4a-4d5f-ae94-29612de13fe3`. Current release: **`3faf2afd-0cab-420e-96d8-204463557b16`**, deployment **`ae60a7f6-7f2c-47c2-9a88-9f8e6e0a14ef`**, 100% at **2026-10-21 14:14:19 Asia/Taipei (06:14:19 UTC)**.
- Prefer application rollback before database rollback. The additive app tables/triggers may remain if reverting code. Follow `backend-migration.md`; account for the password-verifier compatibility window and subsequent real user actions. Do not blindly restore this snapshot over new mail or send-operation evidence. The old version also restores old static assets.

## Applied changes

- Applied `mail-worker/migrations/0001_app_v1.sql` via the D1 API: **29 statements succeeded**, no API errors. Original 380 emails retained; 380 initial change events; 14 `app_*` triggers present.
- Deployed the actual `src/index.js` bundle, without the staging wrapper/network blockade. Handler list verified: `fetch`, `email`, `scheduled`.
- Built and uploaded the compatible Vue assets together with the backend (200 manifest entries; 192 missing assets uploaded in three groups). This preserves authenticated Web attachment download compatibility with the hardened Worker routes. SPA fallback and worker-first routing retained.
- Existing bindings inherited, including original JWT/OAuth configuration, admin/domain values, D1/KV/R2 identities. No credential rotation, password reset or mass session invalidation performed.
- Existing compatibility date `2025-06-04`, daily cron `0 16 * * *`, custom domain and disabled workers.dev endpoint retained. Query-string redaction enabled for observability. No MX/DNS/Email Routing changes or modifications to objects in the existing production mail bucket performed. Only the separate private backup bucket received a new backup object.

## Checks actually executed

- Backup restoration, integrity checks and migration replay on the private restored database: passed.
- Web build: passed. Web Chromium tests: **6/6 passed**, zero failures/skips. Initial browser path was missing; corrected to `/usr/bin/chromium` before counting results.
- Existing deployment-source CI: Android, Android emulator smoke, Worker/Web, Linux/Windows/macOS desktop and ARM64 iOS framework/device jobs passed at release inspection. Intel iOS runtime CI was still running; the earlier all-nine-job checkpoint remains documented separately. Do not claim this newest CI run fully finished until inspected.
- Verified deployed resource IDs, current 100% version and unchanged cron through Cloudflare API.
- A short-lived, random-secret-gated, ten-minute-expiry verifier Worker used a service binding to the deployed Worker for fixed unauthenticated checks. It exposed no arbitrary forwarding path, credentials or mailbox data. Results:

  | Request | Observed |
  |---|---|
  | Web `/` | HTTP 200, `text/html` |
  | Native `/api/app/v1/accounts`, no session | HTTP 401 / envelope 401 |
  | Legacy `/api/my/loginUserInfo`, no session | Legacy HTTP 200 / envelope 401 |
  | `/api/test`, no session | Legacy HTTP 200 / envelope 401 |
  | Native `/api/app/v1/login`, invalid empty payload | HTTP 400 / envelope 400 |

  The verifier was deleted after use (API success). It did not authenticate as a real user, read private mail, send mail, or invoke email/scheduled handlers.
- Production external mail delivery and an authenticated production Android flow were **not tested**. Synthetic native end-to-end and backend regression evidence is in `staging-validation.md`.

## Remaining blockers for Android use / release security

1. **The existing custom domain `https://mail.20090408.xyz` is behind Cloudflare Access.** Anonymous requests receive HTTP 302 to the Access login page before reaching the Worker. The current native password login does not implement the Access browser/cookie authentication layer. Backend deployment alone does not make Android login possible through this domain. Access policy was deliberately retained; workers.dev was not enabled as a bypass. Resolve by adding native Access authentication support or an explicitly approved API access-policy design.
2. **Existing R2 custom domain `r2.20090408.xyz` is enabled.** The R2-managed public domain is disabled, but the custom domain may provide object access independently of Worker authorization (subject to its existing edge policies, which this check did not assess). No objects were fetched to test exposure. Close/secure that route before claiming private attachment isolation across the whole deployment; bucket/domain policy was not silently changed as part of code deployment.
3. Actual user login, mail read/send, signed Android installation, physical-device permissions and live Resend delivery remain operator/user acceptance checks. Do not send test email to external users without authorization.

The original isolated-only restriction was superseded specifically for the authorized backup/migration/deployment. The staging report remains a historical record of its isolated run.

## Deployment automation caution

This release used the Cloudflare API with inherited existing bindings, not the older GitHub deployment workflow. That older workflow performs initialization, does not apply the native migration, and renders configuration from repository secrets/variables. Do not rerun it as a rollback or merge this PR expecting an equivalent release without reviewing its configuration/secrets handling. No automatic merge was enabled.
