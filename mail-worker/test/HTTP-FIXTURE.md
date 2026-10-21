# Actual Worker HTTP fixture

Run from `mail-worker` after installing its locked dependencies:

```sh
npm run test:fixture
```

Wait for `Cloud Mail synthetic Worker ready at http://127.0.0.1:8789` before starting a native client or HTTP integration test. Override `CLOUD_MAIL_FIXTURE_PORT` for concurrent instances. Stop with Ctrl+C; each restart creates a clean ephemeral D1/KV/R2 environment. No Wrangler deployment configuration, account credentials, remote bindings or production data are loaded. Writable temporary-directory defaults are provided for Wrangler diagnostics.

The script bundles the actual `src/index.js` Worker with Wrangler's pinned esbuild and serves it through Miniflare on loopback only. It shares the synthetic current-schema definitions used by Vitest, and applies production migration SQL using official `readD1Migrations` parsing, preserving trigger bodies. Sessions, permissions, sync cursors, actions, attachments and internal delivery execute real Worker/D1/KV/R2 code. It supplies no Resend keys and blocks all outbound Worker requests. Use internal fixture recipients exclusively.

| Fixture | Value |
|---|---|
| Server origin | `http://127.0.0.1:8789` |
| Native API | `/api/app/v1` |
| Login 1 | `user1@example.com`, `fixture-password` |
| Login 2 | `user2@example.com`, `fixture-password` |
| Sender account IDs | 1 and 2 respectively |
| Initial user 1 messages | 11 and 12 |
| Initial user 2 message | 21 |
| Initial attachment | ID 1 on message 11, `fixture.txt`, `Synthetic document` (18 bytes) |

Login uses POST `/api/app/v1/login` with JSON `{email,password}`; subsequent calls set raw JWT `Authorization` with no Bearer prefix. Never print the returned token. Login or accounts calls are suitable readiness checks after the ready line. The native client must explicitly enable local HTTP for development and permit the loopback network connection in its test sandbox.

A meaningful end-to-end test logs in both users, synchronizes user 1, downloads attachment 1, applies read/star changes, sends a uniquely named message from account 1 to `user2@example.com` (optionally with a fixture attachment), syncs the recipient and confirms body and attachment content. Replay the same send operation ID and assert there is one delivered copy. Then revoke user 1's session and verify its next request returns 401. Keep operation IDs unique per test run or restart this fixture before rerunning to avoid durable idempotency replay.

The normal `npm test` suite stays independent and uses its own isolated bindings. This fixture is test-only; it must not be deployed or imported by production code. Its successful HTTP tests do not establish real Resend delivery, public bucket configuration, APNs/FCM, or production deployment readiness.
