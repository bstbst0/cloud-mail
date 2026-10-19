# Cloud Mail native design system

The Compose client uses a quiet blue primary color, slate text, green secondary accents and separate light and dark tonal surfaces. The light canvas is `#F7F9FD`; the dark canvas is `#10151D`. Dark mode uses lighter typography and subdued filled surfaces, rather than inverted colors. Shared tokens and the theme live in `presentation/DesignSystem.kt`.

- Spacing: 4, 8, 12, 16, 24 and 32 dp. Message rows stay dense; body reading and composition get more room.
- Shapes: 8 dp small controls, 14 dp normal surfaces, 22 dp modal and navigation surfaces. Mail rows use restrained selection tint rather than separate elevated cards.
- Typography: Material 3 typography, system fonts and system font scaling. The app font preference adds a bounded scale from 85% to 150%.
- Surfaces: navigation and detail use the base surface; selected messages use primary container; unread mail uses stronger type and a readable text label in accessibility semantics.
- States: progress indicators for initial loads, sending and attachment downloads; contextual empty states; persistent offline banner; recoverable error banner with Retry; deletion confirmation plus Undo snackbar. Disabled controls indicate in-flight actions.
- Layout: compact below 720 dp shows list or detail; medium 720–1099 dp uses list and detail; expanded at 1100 dp adds 220 dp navigation and 380 dp message list. On compact screens navigation lives in a drawer.
- Motion: Material component transitions and swipe dismissal. Motion communicates state changes and never delays reading or blocks actions.
- Interaction: minimum 48 dp icon targets, labelled icons, visible selected navigation and keyboard focus. Ctrl/Cmd+K searches, Ctrl/Cmd+N composes, R replies outside text editing, Delete requests deletion, Escape closes compose/detail/settings.
- HTML mail: platform renderer is isolated from API credentials, scripts and arbitrary navigation. Plain text is always available. External images require a deliberate reader action.

The composer has portable bold, italic, underline and paragraph editing, To/CC/BCC, a real account picker and attachment management. It produces escaped HTML from text segments; raw HTML is never treated as trusted editor markup.

Drafts have a dedicated navigation entry and a local list, so unfinished replies and messages remain discoverable. Opening a draft restores its text formatting; discarding asks for confirmation. A draft with a pending or uncertain send operation becomes read-only and offers Check delivery, preserving the original idempotency operation.

Search retrieves all server result pages. Its Unread, Has attachments and inclusive UTC date-range controls are sent to the server rather than applied only to visible rows. Offline search falls back to the synchronized local dataset.

On desktop, HTML mail uses an explicitly labelled safe text renderer and external image controls are hidden. Android and iOS use their isolated native HTML engines. Login and preferences scroll at compact sizes, including enlarged text and an on-screen keyboard. The composer wraps its bottom actions when available width is limited.

Automated UI fixtures and screenshots live in `desktopTest/presentation/UiSmokeTest.kt`. They exercise real ViewModel behavior with an isolated MockEngine and in-memory SQLDelight database. The fixture covers compact and expanded layouts, light/dark, large text, loading, login errors, offline cached mail and Retry. Actual execution results are recorded separately in the implementation report; source coverage alone does not establish a passed test.

Native dynamic color is a platform capability; the shared palette remains the consistent fallback. Platform support and remaining validation are recorded in `implementation-report.md`.

Small-screen safeguards: the complete navigation panel scrolls, including Settings and long address lists. The composer moves Send to its own row below 480 dp so enlarged titles and pending-delivery labels retain space. Attachment removal targets an individual list item even when filenames match. Preference switches and default-address radios expose the whole labelled row as one accessible control; recoverable status banners announce changes politely.
