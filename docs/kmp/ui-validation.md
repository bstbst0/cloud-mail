# Native UI validation

This record separates images from executed interaction assertions. All mailbox images use an isolated test account and synthetic mail. No production mail server, account or external recipient is involved.

## Packaged Linux application

The generated DEB was extracted and its bundled executable launched on an Xorg dummy display. The screenshot below is from the actual Compose application, not a WebView or a design mockup. Inspection found aligned fields, readable contrast and a visible primary action at 800 × 600. The login form scrolls when content exceeds the window height.

![Actual packaged Linux login](screenshots/linux-packaged-login.png)

The restricted test host required writable Skiko/cache directories and a test-only Fontconfig configuration selecting DejaVu Sans TrueType fonts. Its default WOFF2 font previously triggered a native Skia crash before assertions. No platform-specific test override is shipped in the client.

## Automated fixtures

`UiSmokeTest.kt` uses a real MailViewModel, in-memory SQLDelight and Ktor MockEngine. It explicitly sizes its Compose desktop test scenes at 320 × 640, 420 × 780 and 1280 × 900, and includes login, inbox, detail, reply composition, settings, light/dark, 150% type, offline/retry and keyboard commands.

The first complete native UI run executed all seven fixtures: six passed and the expanded-layout assertion found two “YOUR ADDRESSES” nodes. The closed modal drawer was still composed beside the permanent expanded sidebar. The app now composes drawer content only below the expanded breakpoint, removing the duplicated navigation from that layout. A rerun is pending; the original failure is retained in this record.

Actual captures inspected from that run:

| Capture | Inspection |
| --- | --- |
| [Compact inbox](screenshots/inbox-compact-light.png) | 420 × 780; sender, subject, timestamp, star and Compose remain distinct and readable. |
| [Message detail](screenshots/message-detail-compact.png) | Metadata and body fit, and reply actions wrap into available width. |
| [Reply composer](screenshots/composer-compact.png) | From/To/subject/editor and pinned save/discard actions remain reachable. |
| [Dark settings](screenshots/settings-compact-dark.png) | Separate dark surfaces, readable labels and visibly selected theme. |
| [150% dark composer](screenshots/composer-compact-dark-large-font.png) | 320 × 640; the title wraps, Send uses its own row, body scrolls and footer actions wrap. |

The large-type settings fixture also scrolled to Sign out and its assertion passed. The keyboard fixture passed Ctrl+K search, Escape dismissal and Ctrl+N composition. Offline cached mail remained visible and Retry restored the online state in the real ViewModel fixture. These tests do not validate OS screen-reader behavior, touch gestures on a device or real server delivery.
