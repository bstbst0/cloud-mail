# Native platform security and integration

## Android

The Application injects an application Context; an Activity never leaks into shared business code. The AES-256 key is generated inside Android Keystore. Tokens, server configuration and mailbox payloads use versioned AES-GCM with a unique 96-bit nonce and 128-bit authentication tag. No password is persisted. Android backup and cleartext traffic are disabled. The database stores encrypted payloads and logical owner keys; logout removes that owner's rows and the token, cancels periodic work and clears shared attachment files. Metadata such as owner keys and SQLite row IDs is not encrypted. A hardware-backed key depends on the device; the application does not claim every Keystore is hardware-backed.

Attachments use Activity Result `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`. No all-files or storage permission is requested. Each stream and the aggregate selection are bounded to 10 MiB, with at most ten attachments. Names are normalized. Open/share creates a FileProvider URI restricted to a private attachment directory and grants temporary read permission. Downloads use the system destination picker. Picker continuations survive Activity configuration changes through a process-scoped coordinator; process death cancels the operation and the persisted composer remains available. Logout increments an attachment-operation generation and cancels pending pick/save continuations. Captured background stream reads check this generation. A process-wide session lock serializes temporary file creation, FileProvider handoff, notification publishing and logout cleanup, preventing an in-flight attachment operation from recreating sensitive temporary files after logout. Explicit exports already saved to a user-selected destination remain user-owned files.

WorkManager runs constrained periodic sync at a minimum 15-minute interval, without a foreground service. Android 13+ asks notification permission once after login. Notifications carry immutable PendingIntents with message IDs and no tokens. Message bodies are never notifications; lock-screen content is private. Delivered remote IDs are recorded in encrypted storage to suppress duplicates. System scheduling, battery policy and denied permission can delay notifications. APNs/FCM push is not required for core mail.

HTML uses an isolated native WebView with JavaScript, storage, file/content access, popups, mixed content and navigation disabled. No JavaScript interface is registered. A restrictive CSP comes before the email markup and blocks scripts, forms, connections, frames, objects, media, external fonts and external styles. Images are limited to data URIs by default; explicit consent enables HTTPS images. WebView has no access to the API token. Native email links are intentionally not auto-opened.

## iOS

A SwiftUI host embeds the Compose UIViewController. Secrets are Keychain generic-password items scoped to the application service, accessible after first unlock on the same device. A 64-byte random Keychain secret is split into separate AES-256 and HMAC-SHA256 keys. Payload encryption is CBC with a random 128-bit IV and PKCS#7 padding, followed by encrypt-then-MAC; the MAC is checked in constant time before decryption. This uses Apple CommonCrypto (`platform.CoreCrypto`) and `SecRandomCopyBytes`. The local SQLDelight database contains encrypted payloads; logical owner identifiers remain visible. Keys are never written to preferences.

The native document picker uses security-scoped URLs, bounds data before reading and releases access. The export picker saves downloads; UIActivityViewController shares them; UIDocumentInteractionController previews/opens compatible documents. Temporary files use iOS data protection and are cleared at logout. Logout cancels outstanding document-picker continuations and dismisses retained picker, preview and share controllers, and cancels pending BGAppRefresh requests. Pick and export coordinators cannot both own an active document picker. These UIKit operations run on the main dispatcher.

WKWebView has a nonpersistent data store, no script or message handler, disabled JavaScript at both configuration layers, the same restrictive CSP and a navigation delegate that cancels links. Remote images are blocked until explicitly enabled. WKWebView never receives the API token.

BGAppRefresh integrates an OS-scheduled polling request and cancellation on expiration. A notification opened before the first mailbox page arrives retains its requested message ID until the cache contains that message. Android WorkManager and iOS BGAppRefresh compare the captured secure session before cache merges and publishing notifications; 401 responses or expired background sessions clear the same session and its owner cache, using the shared repository sync lock. Cancellation propagates on Android rather than being treated as a transient network failure. Native local notifications have generic content and stable owner/message IDs. Neither APNs credentials nor permanent background execution are required. iOS controls whether and when background tasks run. Xcode/macOS compilation and simulator/device checks remain required; this Linux workspace cannot verify Apple APIs or runtime behavior.

## Desktop boundary

Desktop adapters are documented by their owner in the implementation report. Machines without a verified OS credential store use an explicit volatile session and in-memory cache. A safe text rendering fallback is required where a secure HTML engine is unavailable.

## Release checks

Verify Keystore/Keychain restoration, token expiration, logout cache deletion, attachment picker cancellation/rotation, exported content URI grants, notification permission denial and message deep links on devices. Try hostile HTML with script, SVG, iframe, form, file URLs and remote tracking images; default rendering must make zero remote image requests. Test background task expiration and foreground/background concurrent sync. Sign release binaries using platform-specific credentials outside this repository. FCM/APNs integration is optional and has not been configured.

## Build configuration

Android uses `BuildConfig.DEFAULT_SERVER_URL` for an optional default endpoint. Local HTTP requires a debug build with explicit `-PcloudMailAllowLocalHttp=true`; only its debug manifest can enable cleartext traffic, and the adapter still restricts permitted local endpoints. Release uses HTTPS. iOS reads the `CLOUD_MAIL_SERVER_URL` Info.plist key populated by an Xcode build setting (empty by default); App Transport Security blocks arbitrary cleartext traffic. No server credentials are build settings.

## Verification in this workspace

Android unit tests include AES-GCM Unicode/binary/empty round trips, distinct nonces, tampered nonce/body/tag rejection, wrong-key rejection, malformed envelopes, safe filenames/MIME values and pending document-picker cancellation at logout/activity finish. Actual test/build results are recorded in the implementation report after the integration runner completes. iOS crypto/Keychain tests are checked in and scheduled on the macOS simulator CI; they have not executed in this Linux environment. Manual picker, external-app handoff, notification delivery and Apple UI behavior still require device/simulator checks.
