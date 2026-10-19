# Cloud Mail architecture and compatibility audit

Audit performed against the checked-out `bstbst0/cloud-mail` repository on 2026-10-13. Sources examined: root AGENTS.md and READMEs, Worker fetch/email/scheduled entrypoints, all Hono route modules and authentication middleware, entity mappings and incremental database initialization, mail/account/user/login/passkey/permission/attachment/storage/Resend services, Email Routing intake, Vue request/Axios wrappers and mail detail/composer views, Vitest/Workers/Wrangler settings. The following describes confirmed code behavior, not assumptions from API names.

## Existing system

One Worker serves Vue static assets and handles `/api/*` by stripping `/api` before Hono dispatch. Email Routing calls the same Worker email handler. Mail is parsed with postal-mime, inserted in D1 in saving/deleted state, attachments uploaded, then transitioned to received/normal. A scheduled task completes interrupted receive state, resets send counters and verification records, and removes unbound OAuth users. Vue release output is `mail-worker/dist`; new native app must reuse this data plane.

D1 entities: user (password hash/salt, role type, status, soft deletion, send counters); account (address, display name, owning user, all-receive preference, order); email (owning user/account, sender/recipient JSON, subject/text/HTML, threading IDs, receive/send type, provider status, unread, soft deletion); attachments (owning user/account/mail plus object key and metadata); star (user/mail association). role/perm/role_perm implement RBAC. Other entities hold settings, registration keys, verification records, OAuth bindings and passkeys. Schema evolves through `/init/:secret` versioned functions; it is not a conventional SQL migration chain. New additive SQL migration must run after current initialization and must never auto-run in production HTTP requests.

Storage chooses configured S3 first, then bound R2, else KV. Attachment keys are content-hash-derived and shared across mail copies; ownership therefore belongs to an **attachment association**, not the object key alone. Previously `getObj` only read R2 despite broader writes. Static backgrounds are intentionally public. Attachments need private authorized reads across all three storage implementations. Configured public bucket domains remain an independent operator-controlled exposure.

## Confirmed legacy contracts

All JSON responses use `{code,message,data}`. `result.ok` emits data:null for absent result. Most errors are HTTP 200 with business code (default BizError code 501); Vue reads envelope code and handles 401/403/502. Native v1 uses real HTTP error statuses with the same envelope. Authorization is raw JWT, not Bearer. Initial repository password verification used single salted SHA256. The updated helper accepts existing hashes and uses versioned PBKDF2-SHA256 (100000 Cloudflare-supported iterations) plus fresh salt for new/reset credentials; no silent existing-hash rewrite or password reset is required. Rollback must retain the dual-format verifier after new credentials are written.

`POST /login` accepts `{email,password}`, returns data:{token}. Legacy JWT payload `{userId,token,iat}` has no exp; UUID session IDs are in user-scoped KV auth-info tokens, 30-day KV TTL sliding-refreshed by daily access. Logout removes current UUID, leaving other devices valid. Existing legacy JWTs must continue working. New native sessions use fixed 30-day JWT exp and D1 records checked on each call, avoiding KV propagation delay for revocation; native scope is distinct from cursor JWT scope. Password/ban/delete updates revoke native sessions atomically.

`GET /account/list` takes accountId,size,lastSort (max30) and keyset-sorts by sort DESC/account_id ASC. `GET /email/list` takes accountId,allReceive,emailId,timeSort,size,type, returns `{list,total,latestEmail}`. Mail list includes complete body fields and attachment metadata. type0=inbox/type1=sent; **unread0=unread, unread1=read**. `GET /email/latest` only sees incoming IDs, so cannot reconcile read/star/delete/status/ownership. `PUT /email/read` body `{emailIds:[Number]}` sets unread1. `DELETE /email/delete?emailIds=1,2` soft deletes. `POST /star/add` body `{emailId}`, DELETE cancel query emailId, GET list query emailId,size returns {list}. `GET /email/attList?emailId` filters attachment association userId.

Legacy send request `{accountId,name,sendType,emailId,receiveEmail:[String],text,content,subject,attachments:[{filename,type,content}]}`; reply requires sendType:'reply' and emailId. Resend configured per sender domain. All internal recipients use existing direct D1 delivery; mixed/outside recipients use Resend. Sender authorization/domain/role send quotas remain enforced. Previously CC/BCC were not forwarded or persisted by send; v1 extends the same service compatibly. Internal received copies now strip BCC.

## Security findings and changes

- Public route exclusions previously used startsWith, and `/test*` bypassed all authentication. Replaced with exact method/path or narrow route-shape matches; removed test exemption (test-api has no actual routes in current checkout).
- Reply loaded original email without owner verification. Now requires current user ownership before deriving threading headers.
- Star add used `!email.userId === userId`, which did not enforce ownership. Corrected comparison; star listing also verifies mail owner.
- `/api/oss/*` required login but read any R2 key, while `/attachments/*` publicly read KV. Both now require owned attachment association and live parent email/account, or explicitly authorized all-email reader; public backgrounds remain available.
- Native IDs/operations/recipients/body/attachment counts, aggregate sizes and filenames are validated. Response content is forced download with nosniff/private no-store/CSP sandbox. S3 reads use trusted configuration, never client paths. Existing internal inline image references require owner association and are encoded from private bytes rather than fetched by Resend URL.
- JWT verifies exact three segments, HS256/typ header and exp boundary. Cursor JWT scope cannot authenticate. Native diagnostics never print tokens/passwords/mail bodies. Versioned password verifier supports existing salted SHA256 and stronger PBKDF2 for new/reset passwords. Removed legacy role-domain console output. Corrected password reset's numeric comparison bug.
- Email HTML was previously rendered inside a same-origin Shadow DOM and external images loaded by default. Integration owns Vue sanitizer+sandbox rendering and platform isolated native renderers; plaintext remains safe fallback.

Residual considerations: legacy webhook authenticates no provider signature; Telegram links are bearer capabilities; `/init/:secret` uses URL secret; public bucket policy cannot be secured by Worker alone; legacy sessions retain original sliding/no-exp transition behavior. These require reviewed deployment/configuration decisions; no broad backend replacement is justified. Native send provider outcomes can be uncertain after connection loss; never blindly resend.

## Sync and compatibility design

Add `app_change_log` with monotonic sequence plus SQLite triggers on email/star/attachments/account and native session revocation on user updates. Every legacy write/receive/status mutation atomically records events. Initial seed emits existing mail IDs once. Signed cursors bind v1, epoch, user, position and bounded cycle watermark. Replay returns current authoritative DTO (not stale historical bodies), deduplicated within page, with physical/soft-deletion and transfer tombstones. New changes past the watermark arrive next cycle. Current complete account list replaces local accounts every page. DB commit of client page+cursor is required; offline operations survive cursor reset. Append-only ledger is deliberate until cursor retention protocol is reviewed.

Native read/star/delete actions reserve operation identity and mutate state in a single D1 batch. Duplicate ID+payload has no new mutation; different payload409. Server acceptance order resolves cross-device conflicts, with final remote state authoritative. Send uses a D1 unique user+operation reservation and SHA256 payload fingerprint before provider call. Resend idempotency key is user scoped. Message linkage is persisted atomically with sent-copy insert. Replay never invokes provider again. Status endpoint exposes sent/pending/uncertain/failed without assuming a timed-out delivery failed.

No MX/DNS/Email Routing changes, second Worker/database, or Resend client secret. Existing route responses preserved. Private attachment routes require coordinated Vue Blob fetch update and browser cache refresh. Native v1 requires reviewed staging migration before deployment.

## Implementation choices and dependencies

Shared native stack: Kotlin2.2.20, Compose Multiplatform1.9.1, Material3, Ktor3.3.0, coroutines1.10.2, serialization1.9.0, SQLDelight2.1.0, lifecycle2.9.4, AGP8.11.2 version catalog. JVM Desktop, Android and iOS framework targets share domain/repository/ViewModel/state flow. Manual dependency composition avoids unnecessary container abstraction. Stable navigation/state routing preferred over immature Navigation3 multiplatform APIs. Platform file selection/token vault/HTML renderer boundaries keep platform APIs out of commonMain.

Dependencies: audit/contract → additive backend+KMP foundation → login/account/mail actions → composer/attachments → persistent local cache/offline queue/ledger sync → platform background/notifications → adaptive polish → isolated provider/D1/KMP tests and build gates. Worker contract fixtures use synthetic users/mail only; external send is mocked. Existing Web release build remains a regression gate. Actual platform build outcomes and remaining limitations are recorded in implementation-report rather than implied from source existence.

## Complete route inventory

All paths below are Worker-local; public origin adds `/api`. Permissions remain in security.js/perm service and service owner checks. Administrative routes are not exposed as native UI features.

| Method | Path | Route module |
|---|---|---|
| GET | `/account/list` | `account-api.js` |
| DELETE | `/account/delete` | `account-api.js` |
| POST | `/account/add` | `account-api.js` |
| PUT | `/account/setName` | `account-api.js` |
| PUT | `/account/setAllReceive` | `account-api.js` |
| PUT | `/account/setAsTop` | `account-api.js` |
| GET | `/allEmail/list` | `all-email-api.js` |
| DELETE | `/allEmail/delete` | `all-email-api.js` |
| DELETE | `/allEmail/batchDelete` | `all-email-api.js` |
| GET | `/allEmail/latest` | `all-email-api.js` |
| GET | `/analysis/echarts` | `analysis-api.js` |
| POST | `/app/v1/login` | `app-api.js` |
| DELETE | `/app/v1/logout` | `app-api.js` |
| GET | `/app/v1/accounts` | `app-api.js` |
| GET | `/app/v1/sync` | `app-api.js` |
| GET | `/app/v1/messages` | `app-api.js` |
| GET | `/app/v1/messages/:id` | `app-api.js` |
| POST | `/app/v1/actions` | `app-api.js` |
| POST | `/app/v1/send` | `app-api.js` |
| GET | `/app/v1/send/:operationId` | `app-api.js` |
| GET | `/app/v1/attachments/:id` | `app-api.js` |
| GET | `/app/v1/sessions` | `app-api.js` |
| DELETE | `/app/v1/sessions/:id` | `app-api.js` |
| GET | `/email/list` | `email-api.js` |
| GET | `/email/latest` | `email-api.js` |
| DELETE | `/email/delete` | `email-api.js` |
| GET | `/email/attList` | `email-api.js` |
| POST | `/email/send` | `email-api.js` |
| PUT | `/email/read` | `email-api.js` |
| GET | `/init/:secret` | `init-api.js` |
| POST | `/login` | `login-api.js` |
| POST | `/register` | `login-api.js` |
| DELETE | `/logout` | `login-api.js` |
| GET | `/my/loginUserInfo` | `my-api.js` |
| PUT | `/my/resetPassword` | `my-api.js` |
| DELETE | `/my/delete` | `my-api.js` |
| GET | `/my/oauth/:provider/authorize` | `my-api.js` |
| POST | `/my/oauth/:provider/callback` | `my-api.js` |
| DELETE | `/my/oauth/:provider` | `my-api.js` |
| GET | `/oauth/:provider/authorize` | `oauth-api.js` |
| POST | `/oauth/:provider/callback` | `oauth-api.js` |
| POST | `/passkey/register/options` | `passkey-api.js` |
| POST | `/passkey/register/verify` | `passkey-api.js` |
| POST | `/passkey/login/options` | `passkey-api.js` |
| POST | `/passkey/login/verify` | `passkey-api.js` |
| GET | `/passkey/list` | `passkey-api.js` |
| DELETE | `/passkey/delete/:id` | `passkey-api.js` |
| POST | `/public/genToken` | `public-api.js` |
| POST | `/public/emailList` | `public-api.js` |
| POST | `/public/addUser` | `public-api.js` |
| GET | `/oss/*` | `r2-api.js` |
| POST | `/regKey/add` | `reg-key-api.js` |
| GET | `/regKey/list` | `reg-key-api.js` |
| DELETE | `/regKey/delete` | `reg-key-api.js` |
| DELETE | `/regKey/clearNotUse` | `reg-key-api.js` |
| GET | `/regKey/history` | `reg-key-api.js` |
| POST | `/webhooks` | `resend-api.js` |
| POST | `/role/add` | `role-api.js` |
| PUT | `/role/setDefault` | `role-api.js` |
| PUT | `/role/set` | `role-api.js` |
| GET | `/role/permTree` | `role-api.js` |
| DELETE | `/role/delete` | `role-api.js` |
| GET | `/role/list` | `role-api.js` |
| GET | `/role/selectUse` | `role-api.js` |
| PUT | `/setting/set` | `setting-api.js` |
| GET | `/setting/query` | `setting-api.js` |
| GET | `/setting/websiteConfig` | `setting-api.js` |
| PUT | `/setting/setBackground` | `setting-api.js` |
| DELETE | `/setting/deleteBackground` | `setting-api.js` |
| POST | `/star/add` | `star-api.js` |
| GET | `/star/list` | `star-api.js` |
| DELETE | `/star/cancel` | `star-api.js` |
| GET | `/telegram/getEmail/:token` | `telegram-api.js` |
| DELETE | `/user/delete` | `user-api.js` |
| PUT | `/user/setPwd` | `user-api.js` |
| PUT | `/user/setStatus` | `user-api.js` |
| PUT | `/user/setType` | `user-api.js` |
| GET | `/user/list` | `user-api.js` |
| POST | `/user/add` | `user-api.js` |
| PUT | `/user/resetSendCount` | `user-api.js` |
| PUT | `/user/restore` | `user-api.js` |
| GET | `/user/allAccount` | `user-api.js` |
| DELETE | `/user/deleteAccount` | `user-api.js` |
