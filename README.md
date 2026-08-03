# WorkOutMate

An offline Android workout tracker. Get a programme, know what you're training today, log
what you actually lifted, and watch the numbers move.

No account, no server, no tracking. Everything lives on the device — which is why exporting
a backup is a first-class feature rather than a buried setting.

> **Status: v2 in progress.** The app is being rebuilt from a WebView-hosted HTML UI into
> Kotlin Multiplatform + Compose. The `core` module is real and tested; the Compose UI is
> being built out. See `docs/PROJECT.md` for what the app is *for*, and the plan for how it
> gets there.

## What it does

- **Find a programme** — pick a proven starter programme, or answer a fixed set of questions
  and have Claude write one. This is the front door, not a side feature.
- **Know what's due today** — routines are scheduled by weekday, by rotation, or against a
  weekly target. The home screen answers "what am I training today" and tells you what you
  missed.
- **Routines** — ordered blocks of exercises with target sets, rep ranges and RPE. A superset
  is just a block with more than one exercise in it.
- **Session logging** — log weight × reps per set, mark warm-ups, record RPE. Each set
  prefills with a suggestion and the reason for it, which you can always override.
- **Rest timer** — starts when you complete a set, survives the screen going off, and
  finishes with a tone and a vibration.
- **Personal records** — best estimated 1RM, best weight and best session volume, detected
  automatically.
- **Consistency** — sessions per week against your target, week streaks, and a
  training-frequency heatmap.
- **Progress charts** — estimated 1RM over time per exercise, weekly tonnage, sets per muscle
  group per week. Drawn directly, no chart library.
- **Bodyweight and measurements** — tracked separately, with their own chart.
- **Save progress** — export a JSON backup anywhere on the device and import it back, either
  *replace everything* or *merge*.

## How it's put together

Two Gradle builds, and the split is the most important structural decision in the project.

```
core/     ← a SEPARATE Gradle build. No Android Gradle Plugin, ever.
            Domain, database, engines, presenters, navigation.
app/      ← the Android app: Compose UI and the platform adapters.
```

`core` is Kotlin Multiplatform with a JVM target and no Android dependency of any kind. That
means it compiles and runs its whole test suite — against a real SQLite engine — with no
Android SDK, no emulator and no Google Maven access:

```sh
./gradlew -p core check      # seconds, offline
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
```

The Android build needs JDK 17+ and an SDK with API 36. CI runs both and attaches the APK.

### Why `core` is a separate build, not a module

Gradle configures every project in a build on every invocation. If `core` were a module
alongside `:app`, running a single unit test would still apply the Android Gradle Plugin —
so `core` would be untestable anywhere the Android SDK or Google's Maven is unavailable.

The second reason is worth as much: `core/settings.gradle.kts` declares **only** Maven
Central. Adding an androidx dependency to `core` therefore fails immediately with a clean
"not found" rather than resolving in CI and quietly making the module untestable. The rule
that core stays platform-free is enforced by the build, not by discipline.

The practical consequence is that as much logic as possible lives in `core`. Scheduling,
progression, statistics, validation, backup merging, chart geometry and every screen's state
are all there and all unit-tested. The Compose layer is deliberately dumb — no conditional
logic beyond rendering, every branch a field on a state object.

### Ports, not platform code

`core` defines interfaces — `Clock`, `SecretStore`, `HttpPoster`, `DocumentIo`, `Notifier`,
`AlarmScheduler` — and `app` implements them. Tests substitute fakes, which is what keeps
every consumer of a platform capability locally testable.

### Why SQLDelight

Its SQL is checked at build time and its JDBC driver runs the suite against real SQLite with
no emulator. That is not a small convenience: it is the difference between a data layer that
is tested and one that is not.

It earns its keep. The first schema used `ON CONFLICT ... DO UPDATE` and was rejected at
build time — upsert needs SQLite 3.24, which reached Android at API 30, while `minSdk 26`
ships 3.18. It caught SQL that would have compiled fine and crashed on older phones.

### Data model

Every record carries `updatedAt` and a nullable `deletedAt`; deletes are soft. That is what
lets a merge-import tell "this record is new to me" apart from "I deleted this on purpose" —
with hard deletes, restoring any older backup would quietly bring everything back.

One rule governs the rest: **the session log is the only source of truth.** Rotation
position, streaks, consistency, suggestions and personal records are all pure functions of
it, never stored mutable state. Anything derived and stored would desynchronise the moment a
backup was merged — and merging is a first-class operation here.

Weights are stored in kilograms throughout and converted only for display, so switching
units never alters a logged number. Pounds are merely the default.

### Why the Claude call lives in Kotlin

The API key never reaches the UI layer. The app can ask *whether* a key exists and ask for a
request to be *made* with it, but can never read it back. The key sits in Keystore-backed
storage, and because the request is an ordinary HTTP call there is no CORS to satisfy.

If a device's keystore is broken, the app says so and steers you to the copy/paste builder
rather than falling back to storing the key in the clear.

## Build me a routine

Two paths to the same JSON contract, defined in
`core/src/jvmMain/resources/content/build-me-a-routine.v1.json`:

- **With an API key** — the request goes out through Kotlin, pinned to the schema by the
  API's structured-output support.
- **Without one** — the app renders the identical prompt to copy into Claude yourself, then
  validates whatever you paste back. No key, no network. This is the primary path.

Either way the result is validated before anything is written, you see a preview of what
will be created, and an import is all or nothing. The same script is checked in as a Claude
Code skill under `.claude/skills/build-me-a-routine/`, reading the same JSON file so the two
cannot drift.

## Known limitations

- Reminders are best-effort. They use `AlarmManager` with a time window rather than exact
  alarms, and OEM battery managers can still delay them. The home screen is always the
  source of truth for what's due.
- Automatic snapshots survive the app's storage being corrupted, but not uninstalling — they
  complement exports rather than replacing them.
- Debug builds only for now; release signing isn't set up.

## Not included, deliberately

No cloud sync or accounts, no social feed, no exercise demo videos, no wearables or health
APIs, no nutrition tracking, and no iOS build yet — though `core` is structured so that
adding one is additive rather than a rewrite.
