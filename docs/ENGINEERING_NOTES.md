# Eccles Agent 1.0 — engineering notes

## Order of preference (acting and seeing)

**Seeing (screenshots):** accessibility screenshot → MediaProjection → ADB `screencap`.
The accessibility screenshot needs no extra consent (the service is already on; it only adds a
`canTakeScreenshot` flag), shows no recording indicator and runs no persistent service, so it goes
first on Android 11+. MediaProjection (consent dialog, "Entire screen" on Android 14+, standing
notification, can be revoked by the system) is the backup and the only non-ADB option on Android 8-10.

**Acting:** every action tries the accessibility *node* first and falls back to a real touch:

| Action | First | Fallback |
|---|---|---|
| click (element) | `ACTION_CLICK` on the node or nearest clickable ancestor | gesture tap at its centre (100 ms) |
| tap (x,y from a screenshot) | `ACTION_CLICK` on the smallest clickable element under the point | gesture of the requested duration |
| long-press | `ACTION_LONG_CLICK` | gesture (400 ms+) |
| scroll | the largest scrollable's own scroll action | swipe gesture |
| type | `ACTION_SET_TEXT` | paste |
| key | global actions (back/home/recents/…) | none needed |
| swipe / drag | gesture only (no node equivalent) | none |

A node action is only trusted if the UI reacted (a window event within 1.2 s). If it was **accepted
but nothing reacted**, the action is *reported* as failed and the model re-plans from a screenshot instead
of being retried by gesture, because a blind retry could double-activate (a "like" toggled twice is
"unliked", a post sent twice). If the node simply has no click action, the gesture runs immediately.
Decision rules live in `EcclesPolicy` and are unit-tested.

**ADB is strictly last resort.** It is not constructed, probed, or given a key (which would raise
the "Allow USB debugging?" prompt) unless accessibility has already failed. A successful fallback is
labelled "via ADB (last resort)" in the timeline so it is never silent.

## Opening apps reliably (open_app)

Android 10+ silently ignores `startActivity()` from the background (it does not throw), so a plain
launch only works while Eccles's own screen is in front. `open_app` is therefore a verified ladder, each
rung confirmed by watching the foreground package:

1. **Direct launch** (explicit launcher component). Gets a long wait when our screen is in front, because
   that is when it works.
2. **Launch from the accessibility service's context** (only when we were in the background).
3. **Drive the launcher like a user:** Home, find the app's icon by label in the launcher's accessibility
   tree (opening the app drawer with a swipe and paging until the list stops changing), click it. The
   launcher starts the app, so the background-start rule never applies. The launcher is read locally
   only; nothing from it is sent to the AI.
4. **ADB `monkey`**, via the router, as the last resort.

It never double-launches (a slow cold start is re-checked before moving on), and "already open" counts as
success. Most real failures were the AI guessing a name, so:

- The AI is sent the **exact list of installed app names** on step 1 and again after any failed open. Excluded
  apps (banking etc.) are left out of that list, so their names never reach the AI.
- A name that is not found fails with "Did you mean: ..." suggestions (typo tolerant, `AppMatcher`).
- Matching ranks exact > prefix > contains, prefers the shorter label ("Facebook" over "Facebook Lite"),
  and queries under 3 characters match only exactly so "ra" can never open "Camera".

Limits: the launcher route depends on the launcher exposing icon labels to accessibility (Pixel, Samsung and
most others do; unusual launchers, icons inside folders, or a work-profile app may not be found).
Whether launching from the accessibility service's own context is exempt from the background rule varies by
device and Android version; the ladder does not depend on it.

## MediaProjection backup (new)

- `CaptureService` (foreground service, type `mediaProjection`) holds one VirtualDisplay for the whole
  session, because Android 14+ makes the projection grant single-use: it cannot be re-created per task
  without asking again. It is optional on Android 11+ (Settings) and required on Android 8-10 (setup card). The user grants it once and
  can revoke it any time from the notification or the chip. On Android 14+ the dialog is restricted to
  "Entire screen".
- **Privacy:** the display has **no surface attached between tasks**, so no frames are produced at
  all unless a task is running. Frames are only read after the excluded-apps guard clears the app, and
  the guard re-checks after capture.
- Secure windows (`FLAG_SECURE`) arrive as solid black; those frames are rejected so the router falls
  through to the next source instead of sending a blank image.

## AI providers (new)

- **Gemini is the default**, then OpenAI, Anthropic Claude, and a *Custom
  (OpenAI-compatible)* endpoint (OpenRouter, Groq, DeepSeek, local Ollama/LM Studio).
  Chosen in Settings; model names are editable because vendors retire them.
- Providers are stateless and single-shot (`EcclesProvider`): the prompt is rebuilt every
  step, so a retried or failed request can never desynchronise a conversation.
- Transient failures (429, 5xx, network) retry with backoff in `Caller`; auth/model
  errors fail fast with an actionable message.
- **API keys** are encrypted with an AndroidKeystore AES-GCM key (`KeystoreAes`) in a
  separate file, never in the normal preferences file and never in a URL.

## Safety (new)

- **Excluded apps.** `EcclesGuard` is the single choke point. It runs before the screen
  is read, before a screenshot is captured, again *after* capture (the user may have
  switched apps), and before **every** action. Nothing from an excluded app is read,
  captured or sent to the AI. Layers: hard blocks (own screen, permission dialogs,
  package installer) → the user's allow overrides → the excluded list (pre-seeded with
  well-known banking/payment apps) → "auto-detect financial apps" heuristics for the
  long tail (bank, wallet, pay, crypto, …). The guard **fails closed**.
- **Password screens.** Password field content is never put in a snapshot. By default a
  task stops when a password field is on screen.
- **Confirmation gate.** Clicks whose label looks irreversible (send, post, delete, buy,
  …) and anything the model flags `sensitive` need an explicit Allow, from the floating
  pill or the notification. Silence for 60 s is a **Deny**.
- **Prompt-injection stance.** On-screen text is declared untrusted data in the system
  prompt; only the user's goal is followed.
- **Always stoppable.** Foreground service with a Stop action, plus a floating status pill
  (accessibility overlay, no "draw over apps" permission). Gestures pass through the pill.
- History stores only the goal, outcome and a short message — never screen content.

## UI (new)

- Material 3, light/dark, `me_task` / `u_step` / `u_result` timeline that updates steps in
  place, live status chips (accessibility / provider / mode), guided setup card, voice input
  (a command starting with "Eccles" runs immediately; anything else waits for Send),
  searchable Excluded Apps screen, task history.

## Known limits (read before relying on it)

- **MediaProjection stays running until you stop it**, so Android shows its recording indicator for
  the whole session (the app attaches the display only during tasks, but the system cannot know that).
  Android also stops a projection on its own in some situations; the app then falls back silently.
  A frame is only emitted when the screen changes, so a task starting on a perfectly static screen
  relies on the compositor delivering the first frame on attach (waits 1.5 s, then falls back).
- **ADB over TCP needs a one-time `adb tcpip 5555` from a computer** (resets on reboot) —
  or root. Wireless-debugging (TLS + pairing code) is **not** implemented.
- **Verified:** the parser, snapshot/matching logic, excluded-app rules, all three provider
  request/response shapes, coordinate mapping, and the ADB wire protocol (against a mock
  `adbd`) are covered by unit tests. The app has since been built and run on a physical Android
  device (see `docs/media`). **Not yet verified:** the ADB client against a real `adbd`.
- Android may refuse to launch an app from the background; `open_app` verifies the app
  reached the foreground and reports failure so the AI can recover.
- Accessibility screenshots need Android 11+; earlier versions need ADB for vision.
  `FLAG_SECURE` windows (many banking apps) return blank images by design.
- Apps can hide content from accessibility; the vision fallback exists for that.
- Always-on "Eccles" wake-word listening is not included (it needs a hotword engine and a
  persistent microphone service); the mic button and wake-word prefix cover voice use.
- Google Play restricts accessibility services to accessibility use cases. Distribute
  outside Play, or expect a policy review.
