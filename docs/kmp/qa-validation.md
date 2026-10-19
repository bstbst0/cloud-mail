# QA evidence and reproducible checks

Date: 2026-10-13. Environment: isolated Linux workspace, Node 24.19.0. No production credentials, deployed databases, object buckets, DNS/MX, or external mail recipients were used.

## Worker integration suite

`mail-worker/test/app-v1.spec.js` exercises the actual Hono routes through the actual Worker `fetch` entry point. `@cloudflare/vitest-pool-workers` executes real local Workers D1, KV, and R2 bindings. It applies the reviewed migration with the official `readD1Migrations` / `applyD1Migrations` helpers. Synthetic credentials are hashed by the application's password implementation. Outbound Resend requests are intercepted with `cloudflare:test` `fetchMock`; every unmocked external request is denied.

The test scenarios cover:

- Password login, fixed 30-day JWT expiry, raw Authorization syntax, invalid password, expired credentials, device-specific logout, password-change and administrative-ban revocation, and login rate limits.
- Legacy salted SHA-256 verifier compatibility, independently salted versioned PBKDF2 password writes and wrong-password rejection.
- Legacy session compatibility, exact anonymous route boundaries, role-gated account/send/delete access, cross-user message/reply/action/cursor isolation.
- Numeric attachment access, R2 payload download, forced download disposition and MIME protection, cross-user rejection, deleted parents, the formerly public `/attachments/*` bypass, a real 25 MiB + 1 byte oversized object with underreported metadata, and malicious filename/header suppression.
- Initial synchronization of 127 messages in seven bounded pages, completion at a fixed watermark while new mail arrives, signed/forged/epoch-invalid cursor recovery, and no duplicate stable remote IDs.
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

This Linux workspace cannot prove an Xcode/macOS build or Windows installation. Android, desktop compilation/UI and shared Kotlin test evidence are recorded by the agents that run those commands in the final implementation report. Real Resend delivery, platform credential stores on each OS, mobile permission dialogs, APNs/FCM, app signing, accessibility with a screen reader and cross-device use against a deployed migration require the release checks below.

## Release checks

- Review additive migration and rollback procedure; back up the production D1 and apply only with explicit deployment authorization.
- Disable public attachment R2/S3 custom-domain exposure; authenticated app routes cannot protect independently public object URLs.
- Run hosted platform CI on the exact commit and review dependency checksums before signing artifacts.
- Verify a private test deployment with two synthetic users and two independent clients, including logout/cache removal, offline queue restore and uncertain send UI.
- Run platform file picker, download, open/share, safe HTML and image-consent checks on Android, iOS, Windows, macOS and Linux.
- Enable and verify platform background scheduling/notification permissions using synthetic received mail; no duplicate notifications after resync.
- Sign release binaries with operator-provided secrets outside the repository. FCM/APNs credentials are optional and must not block core mail usage.
