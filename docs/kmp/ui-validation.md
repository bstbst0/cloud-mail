# Native UI validation

This record separates images from executed interaction assertions. All mailbox images use an isolated test account and synthetic mail. No production mail server, account or external recipient is involved.

## Packaged Linux application

The generated DEB was extracted and its bundled executable launched on an Xorg dummy display. The screenshot below is from the actual Compose application, not a WebView or a design mockup. Inspection found aligned fields, readable contrast and a visible primary action at 800 × 600. The login form scrolls when content exceeds the window height.

![Actual packaged Linux login](screenshots/linux-packaged-login.png)

The restricted test host required writable Skiko/cache directories and a test-only Fontconfig configuration selecting DejaVu Sans TrueType fonts. Its default WOFF2 font previously triggered a native Skia crash before assertions. No platform-specific test override is shipped in the client.

## Automated fixtures

`UiSmokeTest.kt` uses a real MailViewModel, in-memory SQLDelight and Ktor MockEngine. It explicitly sizes its Compose desktop test scenes at 320 × 640, 420 × 780 and 1280 × 900, and includes login, inbox, detail, reply composition, settings, light/dark, 150% type, offline/retry and keyboard commands.

Execution is still being validated. Fixture source and screenshots alone do not establish passed assertions. Final executed results will be recorded here after the test run completes.
