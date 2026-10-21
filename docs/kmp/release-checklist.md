# Cloud Mail native release checklist

Automated checkpoint: functional source `32a62a4` passed all nine hosted jobs in [run 39531990119](https://github.com/bstbst0/cloud-mail/actions/runs/39531990119), including iOS 14/14 native tests and unsigned physical-device compilation. The remaining checkboxes are release checks; green CI does not complete signed installation, manual device workflows or private staging validation.


Reviewed 2026-10-19. This is an operator acceptance checklist, not a release approval or evidence that its unchecked items passed. Attach results for the exact release commit and distinguish implemented code from executed validation. Use [implementation report](implementation-report.md), [build results](build-validation.md), [QA evidence](qa-validation.md) and [migration/rollback plan](backend-migration.md).

## Source and build integrity

- [ ] Freeze the commit, preserve reviewable history, and run `git diff --check` and Worker syntax checks.
- [ ] Run Worker tests, browser security tests and the existing Vue release build on that commit. Record counts, command exit codes and failures repaired.
- [ ] Run Android unit tests/lint/APK assembly, Desktop common/repository/ViewModel/UI tests and native packaging, and iOS simulator tests/framework/Xcode app build. A compilation or old APK does not substitute for a current test result.
- [ ] Review strict Gradle verification metadata for every platform; require Windows/macOS Skiko runtime and iOS KLIB checksums. Keep the official wrapper distribution checksum and wrapper validation enabled.
- [ ] Run Linux native-to-Worker HTTP integration with the isolated fixture; confirm the test executed rather than being skipped for missing `CLOUD_MAIL_E2E_URL`.
- [ ] Inspect UI screenshots for light/dark, compact/expanded, loading/error/empty/offline and large text. Manually check focus, keyboard shortcuts, hover/context menu, swipe confirmation/Undo, TalkBack and VoiceOver.
- [ ] Include the original Cloud Mail MIT notice and complete applicable dependency license/notice texts in release packages. The About dialog must describe the notices actually shipped.
- [ ] Review package contents for credentials, test fixtures, private mail, diagnostic tokens, `local.properties`, local signing keys and source-only development flags.

## Backend compatibility and migration

- [ ] Back up the existing D1 and rehearse `0001_app_v1.sql` against a private staging copy of the current schema. Check initial seed, transaction triggers, ledger growth and indexes without duplicating message rows.
- [ ] Verify existing Vue login, password verification, account list, received/sent lists, stars, read/unread, deletion, reply, attachments and Webhook status updates with old and new sessions.
- [ ] Verify new app JWT expiry, per-device revocation, role enforcement and native revocation after password/status changes. Preserve the dual-format password verifier in the rollback branch.
- [ ] Make attachment buckets/custom domains private before exposing app clients. Test R2, KV or the operator's actual S3 configuration through authenticated routes, including inline images. Worker authorization cannot secure an independently public object URL.
- [ ] Release compatible Web assets with the private attachment route change and check stale cached Web/PWA bundles update safely.
- [ ] Review rollback: disable new clients, roll back application code first, preserve mail and send-operation evidence, and only then consider removing additive `app_*` objects. Never clear original mail/account/user tables or alter MX/DNS as rollback.
- [ ] Obtain separate operator authorization before any production deployment, remote D1 migration, credential change, storage permission change or real test delivery. Development has not authorized these production actions.

## Account and synchronization acceptance

- [ ] On each supported OS, install/start the artifact, configure an HTTPS server and sign in with an existing synthetic staging account. Confirm owned addresses only and actionable invalid-login/session-expiry states.
- [ ] With at least two clients plus Vue, read and mark read/unread, star/unstar, delete/Undo and search beyond the first page. Confirm both account isolation and eventual convergence.
- [ ] Synchronize more than 100 messages with concurrent arrivals, page retries, signed-cursor reset and legacy Web changes. Check transfers and hard deletion remove old-owner bodies and attachments.
- [ ] Disconnect, restart, read cached mail, restore drafts and queue several ordered actions; reconnect and verify the final server state. Check rejected actions retain a retry or reconcile path.
- [ ] Revoke a session while foreground/background synchronization or file work is running. Ensure old responses cannot recreate sensitive data under a new login.
- [ ] Sign out online and offline. Check local sensitive rows, token, temporary files, delivered notifications and background tasks are removed. If offline server revocation fails, verify the app discloses that limitation.

## Send, attachments and safe reading

- [ ] Send and reply/reply-all/forward with To/CC/BCC, multiple owned From addresses, plain/rich text and multiple attachments. Verify receiver BCC privacy and actual provider threading headers, including replies from Sent.
- [ ] Interrupt the provider response, restart the app and check the same operation ID. Repeated taps and status checks must not send duplicates. Pending/uncertain sends stay locked; definitive failure permits explicit editing/retry.
- [ ] Download/open/share Unicode-named attachments; cancel file pickers, rotate/recreate activities, deny access and test exact/over-limit sizes. Check external apps receive scoped URLs rather than app credentials.
- [ ] Inspect hostile HTML on Android WebView and iOS WKWebView: no scripts, arbitrary schemes, in-app navigation, token access or remote trackers by default. Enable remote images deliberately and verify the setting's effect. Check safe Desktop text fallback and offline inline images.
- [ ] Use an isolated provider credential and explicitly approved synthetic recipients for real delivery validation. Mocked/internal delivery does not prove Resend acceptance, deliverability or DNS/MX setup.

## Platform release gates

| Platform | Required checks before claiming support |
|---|---|
| Android | Install current debug/release APK; verify Keystore, permission denial, edge-to-edge/insets, rotation, predictive/system back, file-provider grants, WorkManager scheduling, notification tap and deduplication on an emulator/device. |
| iOS | Build on ARM64 macOS/Xcode; execute native crypto/Keychain tests, then app install and Safe Area/file/share/WKWebView checks. Test BGAppRefresh expiration and notification deep links. Configure a signing team/profile outside source. |
| Windows | Strict build and MSI installation; DPAPI persistence, file chooser/open/share fallback, window resizing, keyboard/focus, secure logout and uninstall behavior. |
| macOS Desktop | Strict build and DMG installation; Keychain consent/persistence, file handoff, keyboard/focus, distribution signing/notarization and cleanup. |
| Linux Desktop | Strict build and DEB installation; Secret Service availability/unlock and disclosed volatile fallback, file opening, display scaling, keyboard/focus and cleanup. |

APNs/FCM push, passkey login, cross-device drafts, native Desktop notifications and synchronization after Desktop exit are not implemented. Do not include them in release feature claims. OS polling is subject to system scheduling and cannot promise immediate delivery. No hosted workflow or unsupported operating-system build is considered passed merely because this checklist or a workflow exists.
