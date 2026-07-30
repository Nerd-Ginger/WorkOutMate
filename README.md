# WorkOutMate

An offline Android workout tracker. Create a routine, log what you actually lifted against
it, and watch the numbers move.

No account, no server, no tracking. Everything lives on the device — which is why exporting
a backup is a first-class feature rather than a buried setting.

## What it does

- **Routines** — ordered exercises with target sets, rep ranges and RPE. A superset is just
  a block with more than one exercise in it.
- **Session logging** — start from a routine, log weight × reps per set, mark warm-ups,
  record RPE. What you did last time for that exercise is shown while you enter the next one.
- **Rest timer** — starts when you complete a set, keeps running with the screen off, and
  finishes with a tone and a vibration.
- **Personal records** — best estimated 1RM, best weight, and best session volume, detected
  automatically when you finish.
- **Progress charts** — estimated 1RM over time per exercise, weekly tonnage, sets per
  muscle group per week, and a training-frequency heatmap. Hand-drawn SVG, no chart library.
- **Bodyweight and measurements** — tracked separately, with their own chart.
- **Save progress** — export a JSON backup anywhere on the device, and import it back with
  either *replace everything* or *merge*.
- **Build me a routine** — answer a fixed set of questions and have Claude write a
  programme that imports straight into the app.

## Building it

```sh
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and an Android SDK with API 36. CI builds the APK on every push and attaches
it as an artifact.

## Testing

```sh
node --test tests/*.test.mjs   # pure logic: no dependencies, no framework
node tests/browser-smoke.mjs   # the real UI in Chromium
```

The first suite covers the maths and the rules — estimated 1RM, volume, ISO-week bucketing,
PR detection, backup merge semantics, and routine-plan validation. Those modules
(`stats.js`, `merge.js`, `validate.js`, `scale.js`) are kept strictly DOM-free precisely so
Node can import them straight out of the assets directory: real unit tests, zero
dependencies, no build step.

The second drives the actual app in a browser — IndexedDB persistence across a reload, the
session logger, chart rendering, and a full backup round trip.

Neither can touch the Kotlin shell. `docs/SMOKE_CHECKLIST.md` covers what only a device can
prove.

## How it's put together

A single-Activity Android app wrapping a WebView. The UI is HTML, CSS and JavaScript in
`app/src/main/assets/www`; the Kotlin around it handles the things a web page cannot.

```
app/src/main/java/com/nerdginger/workoutmate/
  MainActivity.kt   WebView setup, asset loader, back handling
  NativeBridge.kt   the only JS↔Kotlin door
  BackupIo.kt       file save/open via the Storage Access Framework
  RestTimer.kt      countdown, notification, tone and vibration
  ClaudeClient.kt   the Anthropic API call
  SecureStore.kt    Keystore-backed API key storage

app/src/main/assets/www/
  js/stats.js       training maths            ← unit tested
  js/merge.js       backup validate + merge   ← unit tested
  js/validate.js    routine-plan validation   ← unit tested
  js/scale.js       chart scales and ticks    ← unit tested
  js/db.js          IndexedDB schema and migrations
  js/charts.js      hand-rolled SVG charts
  js/skill.js       the routine-builder prompt and import
  js/ui/            one module per screen
```

### Why a virtual `https://` origin

The WebView serves the app through `WebViewAssetLoader` rather than from `file://`. On a
`file://` origin every page gets an opaque origin: IndexedDB is unreliable to unavailable
and `crypto.randomUUID` doesn't exist. Over the virtual origin, storage behaves exactly as
it would in a normal browser. This is load-bearing — if it regresses, data stops persisting.

### Why the Claude call lives in Kotlin

The API key never reaches the web layer. The page can ask *whether* a key exists and ask for
a request to be *made* with it, but can never read it. The key sits in Keystore-backed
`EncryptedSharedPreferences`, and because the request is an ordinary HTTP call rather than a
browser `fetch`, there is no CORS to satisfy.

If a device's keystore is broken, the app says so and steers you to the copy/paste builder
instead of falling back to storing the key in the clear.

### Data model

IndexedDB, schema v1. Every record carries `updatedAt` and a nullable `deletedAt`; deletes
are soft. That is what lets a merge-import tell "this record is new to me" apart from "I
deleted this on purpose" — with hard deletes, restoring any older backup would quietly bring
everything back.

Weights are stored in kilograms throughout and converted only for display, so switching
units never alters a logged number.

## Build me a routine

Two paths to the same JSON contract, defined in
`app/src/main/assets/www/skills/build-me-a-routine.v1.json`:

- **With an API key** — the request goes out through Kotlin, and the reply is pinned to the
  schema by the API's structured-output support.
- **Without one** — the app renders the identical prompt to copy into Claude yourself, then
  validates whatever you paste back. No key, no network.

Either way the result is validated before anything is written, you see a preview of what
will be created, and an import is all or nothing. The same script is checked in as a Claude
Code skill under `.claude/skills/build-me-a-routine/`, reading the same JSON file so the two
cannot drift.

## Known limitations

- The rest timer is tied to the Activity, so if Android kills the process mid-rest the alert
  is lost. `AlarmManager` is the fix if this proves annoying in practice.
- Automatic snapshots survive the app's storage being corrupted, but not uninstalling —
  they complement exports rather than replacing them.
- Debug builds only for now; release signing isn't set up.

## Not included, deliberately

No cloud sync or accounts, no social feed, no exercise demo videos, no wearables or health
APIs, no automated progression, and no iOS build.
