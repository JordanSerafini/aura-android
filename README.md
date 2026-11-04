# aura-android

Android and Wear OS client for a personal AI orchestrator ("Aura") that runs on the author's own computer. The phone app is a chat front end plus a bridge that lets the orchestrator read some phone state and perform a limited set of actions on the phone, always under local safeguards. A companion Wear OS app acts as a voice remote through the phone.

**The server side is not included.** The orchestrator, its WebSocket bridge and the web UI that the chat tab loads are private and are not part of this repository. Without a compatible server the apps build and start, but they have nothing to connect to. This repository is published as a code sample of the client side: native Android services, a small protocol layer, and the tests around them.

Status: personal project, used daily by its author on one phone and one watch. It is a snapshot of a private repository, published without its history, and refreshed from time to time (last refresh: November 2026). It has not been released on any app store.

## What the apps do

Phone app (`phone/`):

- **Chat tab.** A WebView that loads the web UI served by your own server. On first launch it pairs itself by passing the device token in the URL fragment.
- **Persistent connection.** A foreground service keeps a WebSocket open to the server (`hello` message with the device token, then JSON messages), restarts at boot, and has a reconnection watchdog based on inexact alarms for when the screen is off.
- **Phone actions requested by the server.** The native executor handles these action names: SMS send and list, call (dial), call log, contacts search, notification list/reply/dismiss/open, messaging apps (WhatsApp, Messenger, Telegram, Signal, SMS), calendar list and add, alarm and timer, location, device status, app usage, volume, Do Not Disturb, ringer mode, flashlight, find my phone, media control, open app / URL, navigation, clipboard, e-mail compose, text to speech, camera snapshot, screen read, UI gestures through the accessibility service, and commands forwarded to the watch (notify, vibrate, heart rate, steps, battery).
- **Safeguards on the device.** Outgoing actions can require an explicit confirmation (the app can only tighten what the server asks for, never relax it). A pause mode (apps only, or everything) is applied locally even when offline. UI gestures are restricted to a user-editable allow list of apps, with a hard-coded deny list (banking, payment, password managers, authenticators, system settings, the store). A journal screen records what was sent, filtered or blocked.
- **Triggers sent by the phone.** Missed call, incoming call, notification from chosen apps, entering or leaving a zone, low battery, charger. Each type can be turned off, and only types for which the server has an active rule are sent.
- **Voice.** A hands-free "Talk" conversation screen with voice activity detection and barge-in, a quick-settings tile, a launcher shortcut, and an implementation of the Android assistant role. Barge-in needs a slightly longer stretch of voice than before and ignores the first frames of playback, and an option keeps the mic muted while Aura speaks (tap the orb to interrupt instead).
- **Share target.** Text, links, images and PDFs can be shared to the app from other apps.
- **Notifications.** Live Update style progress notification while the orchestrator works on a request, grouped missed-call notifications with a "call back" action that opens the dialer, and call cards. Finished tasks get their own channel; the server can batch them (one notification replaces the individual ones) and mark them silent at night, and every category the server uses has a readable title.
- **Home screen widgets.** A small widget (items waiting for a decision, unread notifications, a mic button) and an "Aura HUD" widget in three sizes that shows a status feed pushed by the server: alerts, next appointment, to-do items with Done buttons, system state, running tasks. The same feed can also appear in the persistent notification, as an Android 16 Live Update for a serious alert or a close appointment, in a quick-settings tile and on the watch. Each surface can be turned off.
- **Native screens.** A Device screen grouped in sections (pause, permissions, HUD, automations, journal, watch, settings), a first-launch assistant that asks for permissions one step at a time, and a bottom bar that mirrors the web UI's own (Chat, Notifications, To do, More). The Device screen opens from the web UI when the page offers a button for it; otherwise the app adds a small round button over the page, and a launcher shortcut leads there too. Light and dark themes follow the system, or the choice made in the web UI.
- **Shared design tokens.** Colours, sizes and radii come from the web UI's stylesheet through `phone/scripts/sync-theme.mjs`, which writes the TypeScript, Kotlin and XML versions; a test checks that the three agree and that text and controls keep WCAG AA contrast. Fonts: Space Grotesk and Geist Mono (SIL OFL 1.1, licences next to the files). App icon: an orb drawn in SVG (`phone/assets/icon/`) and rendered by `phone/scripts/render-icons.cjs`.
- **Helpers for the web UI.** Copy and share sheet for a reply, semantic haptics, and a "what I copied" hint: the app tells the web UI how old a text copied elsewhere is, and reads its content only when asked. Only the configured web UI can use these; a payment detail (IBAN, card, crypto address) is never copied this way, and a full pause blocks both copy and read.
- **Launcher shortcuts** to the Device screen, to the web UI's to-do and reminders views, and to the Talk screen.
- **Network changes.** When the default network changes (Wi-Fi, mobile data, VPN) the WebSocket is recycled at once instead of waiting for a dead connection to time out.

Watch app (`watch/`, Wear OS, Kotlin and Compose):

- Voice remote: record, send, read or hear the reply, quick reply chips, typed input.
- Tile and complication, confirmation screens for actions that need approval, a pause control.
- "Aura HUD" tile and complication fed by the phone (alert count, next appointment, items to handle).
- Approvals are queued (oldest first) and an approval that could not be delivered to the phone is reported instead of being lost silently. Status banners say when the phone or the server cannot be reached and when a pause request failed.
- Dark palette tuned for OLED screens, a lighter animation when the battery matters, and launch counters (numbers only) sent to the phone.
- Heart rate and step reading on request from the server.
- The watch talks only to the paired phone through the Wearable Data Layer; the phone relays to the server.

## Architecture

```
  Wear OS app                Phone app                                   Your server
  (watch/)                   (phone/)                                    (not included)

  voice, tile,   Data Layer  +-------------------------------+  WebSocket  +----------------+
  confirmations <----------> | aura-device (Kotlin module)   | <---------> | bridge + AI    |
  heart rate                 |  AuraService (foreground)     |  JSON msgs  | orchestrator   |
                             |  BridgeClient / watchdog      |             +-------+--------+
                             |  ActionExecutor + guards      |                     |
                             |  PhoneEvents (triggers)       |             serves  | HTTPS
                             |  Talk / Assistant / Share     |                     v
                             +---------------+---------------+             +----------------+
                                             | Expo module bridge          | web UI         |
                             +---------------v---------------+  WebView    | (chat, notes)  |
                             | React Native UI (TypeScript)  | <---------> +----------------+
                             |  screens: Aura, Device, Talk, |
                             |  first-launch assistant       |
                             +-------------------------------+
```

- `phone/` is an Expo (SDK 54, React Native 0.81) application. The UI is TypeScript. Everything that must work with the app closed lives in the local Kotlin module `phone/modules/aura-device`, not in JavaScript.
- `watch/` is a standalone Gradle project (Kotlin, Jetpack Compose for Wear OS, Wearable Data Layer, Health Services).
- Both apps use the same application id and must be signed with the same key, otherwise the Data Layer does not link them.
- Pure logic (parsing, planning, guards, state machines) is kept in Android-free Kotlin files such as `BridgeLogic.kt`, `UiGuard.kt`, `PauseLogic.kt` and `MissedLogic.kt`, and is covered by JVM unit tests.

Some source comments refer to sections of an internal protocol document (`PROTOCOL.md`). That document is not published. Comments and UI strings are mostly in French.

### Not in this repository

The private app has a few more features that are not published here, either because they depend on the author's own server and setup, or because they would need a different design to be safe in a generic build:

- a "hub" mode for a tablet left on a stand (wake word, timers, home screen);
- a floating bubble that can read the current screen on request;
- self-update of the APK from the server;
- automatic fallback between several addresses of the server (the private version ends on a plain-text address that a release build here refuses);
- a card that approves a server request in one tap, outside the confirmation floor described above;
- animated characters used as icons and mascots;
- a few screens tied to the author's own use.

## Configuration

No endpoint or credential is stored in the repository. The defaults point to `https://example.invalid` and the apps do nothing useful until you provide your own values at build time, through environment variables read by `phone/app.config.ts`:

| Variable | Meaning |
|---|---|
| `AURA_MOBILE_DEVICE_TOKEN` | Device token issued by your server. It is embedded in the APK, so do not distribute an APK built with a real token. |
| `AURA_BRIDGE_URL` | WebSocket endpoint of your server (`wss://...`). |
| `AURA_PWA_URL` | URL of the web UI shown in the first tab (`https://...`). |
| `AURA_KEYSTORE_FILE`, `AURA_KEYSTORE_ALIAS`, `AURA_MOBILE_KEYSTORE_PASS` | Release signing for the phone app. |
| `AURA_KEYSTORE_PATH`, `AURA_KEYSTORE_PASS`, `AURA_KEY_ALIAS`, `AURA_KEY_PASS` | Release signing for the watch app (optional, unsigned if absent). |

`phone/.env.example` lists the names with placeholder values. The Settings tab shows the connection state and the endpoint the build was configured with; the endpoint itself is fixed at build time.

Server protocol, in short: the app opens the WebSocket, sends `hello` with the token, then exchanges JSON messages (action requests and results, phone events, context updates, chat and voice streaming). A compatible server has to implement that protocol; the message shapes can be read from the parsers and their tests (`BridgeLogic.kt`, `BridgeClient.kt`, `watch/.../protocol/`).

## Build

Prerequisites: a JDK (21 was used here), Android SDK with platform 36 and build-tools 36 (`ANDROID_HOME`), and Node (22 was used here) for the phone app.

Phone app:

```bash
cd phone
export AURA_BRIDGE_URL=wss://your-server.example/ws
export AURA_PWA_URL=https://your-server.example/app/
export AURA_MOBILE_DEVICE_TOKEN=...            # from your server
npm ci
npx tsc --noEmit
CI=1 npx expo prebuild -p android --clean --no-install   # generates the throwaway android/ project
cd android && ./gradlew assembleDebug
```

`phone/scripts/build.sh` chains these steps for a signed release build (it needs the signing variables above).

`node --test phone/scripts/sync-theme.test.mjs` checks the design tokens. The comparison with the web UI's stylesheet only runs when `AURA_PWA_CSS` points to it (the stylesheet belongs to the server); `node phone/scripts/sync-theme.mjs --css <path>` regenerates the tokens.

`phone/scripts/jvm-tests/run.sh` runs the pure Kotlin logic tests of the native module on the JVM, without `expo prebuild` or `node_modules` (it borrows the watch's Gradle wrapper, offline). Tests that touch Android classes are skipped there; the full suite stays `./gradlew :aura-device:testReleaseUnitTest` after a prebuild.

Watch app:

```bash
cd watch
cp local.properties.example local.properties    # then edit sdk.dir
./gradlew testDebugUnitTest
./gradlew assembleRelease                       # unsigned unless the signing variables are set
```

## Verification status

What was run on the sanitized snapshot, on a Linux machine with JDK 21 and Android SDK 36:

- Watch app: `./gradlew testDebugUnitTest` ran offline and passed (73 unit tests, 0 failures).
- Phone app: `tsc --noEmit` passed, `expo prebuild` generated the Android project, and `./gradlew :aura-device:testReleaseUnitTest` passed (260 unit tests in 18 classes, 0 failures, 0 skipped). That confirms the native Kotlin module compiles and its logic tests pass after the package rename. The first attempts at this were killed by the environment (the Gradle process disappeared) and succeeded when run as a separate service.
- The `assembleDebug` command for the phone app above, the signed release builds, installation on a device, and runtime behaviour against a server were not run on the snapshot.

## Limits

- Needs a private server that is not provided; there is no demo mode.
- Android only. Developed and used on one phone and one Wear OS watch, so other devices and Android versions are untested.
- Several features depend on permissions that Android restricts for apps installed outside a store (notification access, accessibility, usage access, call log, SMS). They have to be granted by hand.
- The battery cost of the reconnection watchdog is an estimate in a code comment, not a measurement.
- Interface strings and most comments are in French.
- No screenshots are included.

## License

MIT, see `LICENSE`.
