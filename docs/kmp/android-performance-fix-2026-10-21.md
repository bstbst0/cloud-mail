# Android mailbox performance correction — 2026-10-21

User reported severe loading/jank and intermittent crashes after signing into `https://cloud-mail.alvin980408.workers.dev`. This domain is user-enabled; no production configuration or mail data was changed for this fix. An unauthenticated native accounts request returned HTTP 401, confirming that the API is reachable without the earlier custom-domain Access redirect. No user credentials or private mail were requested or logged.

## Confirmed code paths and changes

- `viewModelScope` resumed sync/cache decoding on the UI thread. Repository sync/detail/search and cache publication now use an injectable background dispatcher; ViewModel tests inject their virtual-time dispatcher explicitly.
- Every sync page decrypted the entire mailbox to build an ownership map. Normal pages now load only the affected message IDs. Account membership changes still scan lightweight summaries so revocation and pending-action cleanup remain correct.
- Whole-mailbox queries eagerly retained encrypted payloads and full decoded HTML in the list state. Summary queries now decode rows individually and retain only a bounded text preview without HTML/inline payloads. Complete bodies remain encrypted in SQLite and are loaded for detail, reply and forward; no remote or offline content is truncated on disk.
- Android reopened the Keystore and resolved the key for every row. The platform instance now lazily retains the Keystore key handle; each operation still creates its own AES-GCM cipher and fresh IV. The key is not exported.
- Repeated refreshes previously queued full synchronization runs behind a mutex. Concurrent requests now coalesce into the current run plus at most one follow-up, so later queued intentions are not lost.
- Initial pages become visible before the complete mailbox finishes syncing, and the UI shows the number of processed updates. Native page size is five to reduce peak response allocation; cursor pagination still covers the entire mailbox. A nonadvancing cursor is rejected instead of spinning indefinitely.
- Row previews no longer run a regex across the entire message at every recomposition. Full plain-text detail uses lazy 8 KiB sections, avoiding a single very large text layout.
- Android handles `onRenderProcessGone`, removes/destroys the dead WebView and offers plain-text reading instead of accepting the engine's default application-termination behavior. This is a defensive crash-path fix; no device crash trace was available to establish that it caused the user's specific crash.
- Cache publication failures are reported rather than escaping an unhandled coroutine. Logout invalidates the generation while keeping the old data partition fixed until cancelled background jobs finish; subsequent login joins the old session before changing owners. Late cache snapshots cannot replace a newly selected folder/account/query.

## Verification

The full final command uses JDK 21, Android SDK 35, strict dependency verification, writable Fontconfig/Skiko paths and the existing isolated localhost Worker fixture:

```sh
CLOUD_MAIL_E2E_URL=http://127.0.0.1:8789 ./gradlew --no-daemon --dependency-verification=strict \
  :composeApp:desktopTest :composeApp:testDebugUnitTest :composeApp:lintDebug :composeApp:assembleDebug
```

The local constrained environment also supplies the documented aapt2 override and GUI environment from `build-validation.md`.

New regression cases:

- Populate 500 synthetic messages with substantial text/HTML; updating one message must decrypt fewer than 20 records, rather than all 500. Verify summaries retain at most 512 text characters and no HTML while full encrypted-cache bodies remain intact.
- Assert Repository encryption executes off the caller thread and page progress/cursor persist.
- Hold one HTTP sync open, request refresh 50 times, then release it: exactly two sync requests run, with loading cleared.

Existing offline queue, account revocation/reset, search filtering, session cancellation, encrypted persistence, Compose UI and actual localhost Worker HTTP integration tests remain enabled. No production mail was used in tests.

## Limits and installation

The user's physical-device ANR/crash logs are unavailable; physical-device smoothness and the exact original crash signature remain unverified. This workspace has no `/dev/kvm`, so Android emulator startup is delegated to the existing GitHub Actions job. Do not describe local JVM tests as Android device profiling. The APK must be replaced on the user's phone for client-side fixes to take effect; updating the Worker alone cannot install these fixes.

No cache schema migration or cache reset is required. Debug APKs built on different machines/runners may have different signing certificates; an incompatible-signature error must not be solved by blindly clearing data. Preserve unsent drafts and synchronize pending actions before any necessary reinstall. Signed distribution with a retained signing key remains a release requirement.

## Final executed results

Final command completed successfully: **53/53 desktop tests**, including localhost Worker HTTPS-adapter-equivalent HTTP integration, Compose UI, 500-message regression, background-dispatch assertion and refresh coalescing; **19/19 Android unit tests**; lint **0 errors / 32 warnings**; `assembleDebug` passed. No tests skipped. APK signature verification passed (v2). Downloadable workspace copy: `/workspace/cloud-mail-android-performance-fix.apk`. SHA-256: `4b730741b69d922708025927bb3930a4c51b0ef502059e16719e798242071a10`.

During verification, the background dispatcher exposed an obsolete test-scheduler assumption; ViewModel tests now inject their virtual-time dispatcher. The large-font composer UI check also needed to await asynchronous composer state and UI idle; its visibility assertions remain intact and all seven Compose UI tests passed on the final run. Initial Android resource compilation used the missing default aapt2 resolver, corrected with the documented installed-SDK override. The final APK and tests were rebuilt after the last source change.
