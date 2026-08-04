# WorkOutMate v2 — ground-up rewrite as Kotlin Multiplatform

## Context

v1 exists and works: an Android WebView shell (887 lines Kotlin) hosting a
vanilla-JS UI (~5,300 lines) in `assets/www`, with 72 unit tests, 31 Playwright
checks, and green CI producing a debug APK. Nothing is wrong with it
mechanically. Two things are wrong with it as a product.

**First, the brief moved.** The original intent was *"map out effort and chart
progress"* — an instrument. The stated audience is now "me, and anyone else who
struggles to **find** the right workouts and **maintain** them". v1 does neither.
It has no notion of a programme, no notion of what you should train today, no
reminders, no adherence feedback, and no progression. The routine builder is a
side feature you visit once. So the app is being re-centred: **finding a
programme becomes the front door, and keeping you on it becomes a first-class
job.**

**Second, the stack is being changed by decision.** The UI is HTML in a WebView;
the owner wants that gone, rebuilt as Kotlin Multiplatform + Compose
Multiplatform, Android-only for now with iOS structurally possible later.

This is therefore a rewrite, not a refactor. ~5,300 lines of JS/HTML/CSS and 103
tests are discarded; the Kotlin adapters, the data model's design, the backup
contract, the exercise seed data and CI survive in some form.

### Decisions taken (all confirmed with the owner)

| # | Decision |
|---|---|
| D1 | **Kotlin Multiplatform + Compose Multiplatform.** Resolves the old provisional D1. HTML/JS deleted. |
| D2 | **iOS: don't pay for it now.** Core is real KMP from day one; the iOS target is simply not enabled. No Apple hardware needed for Android. |
| D3 | **Find is the front door.** First run leads with getting you a programme. |
| D4 | **Maintain = all four:** what's due today · reminders · consistency feedback · progressive programme. |
| D5 | **Progression suggests, the user decides.** Full ruleset, always overridable, every suggestion carries a one-line reason. |
| D6 | **Scheduling supports both** weekday-anchored and rotation, chosen per programme. |
| D7 | **One big v1**, not staged delivery. |
| D8 | **No legacy data to preserve** — v1 was never used in anger. A v1 backup importer is still built, cheaply, as insurance. |
| D9 | **Default units: pounds.** Still stored as kg internally, converted for display. |
| D10 | **Audience: the owner alone for now.** No onboarding for strangers, no Play Store work in v1. |

### The environment constraint that shapes the architecture

This container **cannot build Android**. `maven.google.com` 301-redirects to
`dl.google.com`, which the network gateway refuses with a hard 403; every mirror
(Aliyun, Huawei, JitPack) is blocked too; no Android SDK is installed. Verified
further: **no Compose target compiles here at all**, not even desktop, because
Compose transitively needs `androidx.collection`/`androidx.annotation`, which are
Google-Maven-only. **Room is likewise unreachable.**

What *does* resolve from Maven Central and the plugin portal: Gradle 8.14.3, the
Kotlin Multiplatform plugin, kotlinx-serialization, kotlinx-datetime,
kotlinx-coroutines, **SQLDelight including its JDBC/SQLite driver**, and Ktor.

Two consequences, both load-bearing:

1. **SQLDelight, not Room.** Not a preference — Room cannot be resolved here at
   all, while SQLDelight compiles, generates and runs against real SQLite in this
   container. Its SQL is compile-time checked, which matters enormously when you
   cannot run the app.
2. **Maximise the green zone.** Every line that can live in a pure-Kotlin,
   AGP-free module gets locally compiled and unit-tested in seconds. Everything
   else is blind.

**Mitigation confirmed:** the owner will run Claude Code on their home PC, where
the Android SDK and Google Maven are available. Compose work is sequenced to
land there, so the blind zone is a scheduling constraint rather than a permanent
handicap.

---

## Architecture

### Two Gradle builds, composite-wired

```
/                          ← Android build (root)
  settings.gradle.kts      includeBuild("core"); repos google() + mavenCentral()
  build.gradle.kts         AGP + kotlin-multiplatform + compose plugins, apply false
  gradle/libs.versions.toml   single version source, read by BOTH builds
  app/                     ← :app — the blind zone
/core/                     ← SEPARATE Gradle build. No AGP, ever.
  settings.gradle.kts      repos mavenCentral() + gradlePluginPortal() ONLY
  src/commonMain/kotlin    domain, repos, presenters, engines, navigator
  src/commonMain/sqldelight   .sq schema + queries
  src/commonTest/kotlin    the whole test suite
  src/jvmMain/resources    exercises.seed.json, starter programmes
```

Local loop: `./gradlew -p core check` — compiles core, runs every test against
real in-memory SQLite, verifies migrations. Seconds, offline.

**A single build does not work here**, and the failure is at configuration time
before any task runs: Gradle configures every project on every invocation, so
`:core:jvmTest` would still configure `:app`, apply AGP, and hit the 403. Even an
`apply false` AGP alias in the root `plugins {}` block resolves the plugin marker
and fails. `--configure-on-demand` is deprecated and unreliable with AGP.

The separate `core` build has a second benefit worth as much as the first: its
`settings.gradle.kts` declares **only** Maven Central, making it structurally
impossible to add an unbuildable androidx dependency to the green zone. The
repository declaration is the guardrail.

*Fallback if composite substitution misbehaves in Phase 0:* a single build with
`if (System.getenv("ANDROID_HOME") != null) include(":app")`. Simpler, loses the
guardrail. Decide this in Phase 0, never later.

### Dependencies — deliberately few

`core`: SQLDelight 2.0.2 · kotlinx-serialization-json · kotlinx-datetime ·
kotlinx-coroutines-core. That is the complete list.

`:app`: AGP 8.11.1 (**hold this version — CI is already green on it**) ·
Compose Multiplatform · `androidx.security:security-crypto` (isolated behind a
port; it is alpha and deprecated upstream) · nothing else.

Explicitly rejected, with reasons:

- **Koin / any DI framework** — its failure mode is a runtime crash on a missing
  binding, which is the worst possible failure in a layer we cannot run locally.
  Manual constructor injection through one ~40-line `AppGraph` turns those into
  compile errors.
- **Ktor** — the entire network surface is one POST. v1's `ClaudeClient.kt`
  already proves ~60 lines of `HttpURLConnection` covers it including gzip and
  error mapping. Behind an `HttpPoster` interface, request building and response
  parsing are 100% locally testable, which is all a MockEngine would have bought.
- **WorkManager** — 15-minute minimum, Doze-batched, and it transitively pulls
  Room. A gym reminder that arrives at 22:40 is worse than none.
- **Any navigation library** — `androidx.navigation`'s multiplatform port is
  alpha; Voyager and Decompose put navigation state in a layer that cannot be
  tested here. A hand-rolled `Navigator` is ~150 lines in `commonMain` and is
  fully unit-tested.
- **`androidx.lifecycle.ViewModel`** — would drag androidx into the presenter
  layer and evict every presenter from the green zone. Presenters are held in a
  `PresenterStore` on the app-scoped graph instead, and the app locks to portrait
  (correct anyway for a one-handed gym logger).
- **Any chart library** — none is both KMP-ready and stable. Geometry is computed
  in core (exact, unit-tested); Compose `Canvas` only draws.

### Ports, not `expect`/`actual`

With only a `jvm()` target, `core` has no `androidMain`, so `expect`/`actual`
literally cannot express an Android implementation from this container.
Interfaces in `commonMain` with adapters in `:app` are also trivially fakeable,
so every consumer stays locally testable.

`Clock` · `TimeZoneProvider` · `IdGenerator` · `SecretStore` · `HttpPoster` ·
`DocumentIo` · `SnapshotStore` · `Notifier` · `AlarmScheduler` · `Feedback` ·
`AppInfo`. The `SqlDriver` is injected directly — `AndroidSqliteDriver` in the
app, `JdbcSqliteDriver(IN_MEMORY)` in tests.

v1's `SecureStore.kt`, `ClaudeClient.kt` and `BackupIo.kt` move across almost
verbatim as the `SecretStore`, `HttpPoster` and `SnapshotStore` adapters. They
are already correct and already carry the reasoning for their edge cases.

**Security invariant preserved unchanged:** the Anthropic key lives in
Keystore-backed storage, the HTTP call is made from Kotlin, and no port exposes a
getter for the key. `SecretStore` offers presence, set, and clear only.

---

## Site map

> **Superseded for views.** The design prototype in `Black and orange palette
> views.zip` is now the source of truth for what screens exist and what they
> look like. Where it and the map below disagree, the prototype wins.
>
> The differences are real, not cosmetic. The prototype ships **six** tabs —
> Today · Routines · Charts · Exercises · Body · Data — against the five here:
> Exercise library is promoted to top level rather than living under Plan, and
> Data replaces Settings, which follows from backup being a primary feature
> rather than a settings row. It also has no separate first-run screen, no
> session summary and no programme/schedule editor; those are in this map
> because the v2 brief asks for them, and they still need designing.
>
> This section stays because it is the *behavioural* spec — what each screen has
> to answer — and because the gaps above are the list of what the prototype does
> not yet cover.

15 screens. **Bold = new in v2.** Five tabs: Today · Plan · Progress · Body ·
Settings.

### First run — the front door (new)

| # | Screen | Contents |
|---|---|---|
| 1 | **First run** | The only screen with no tab bar. Four routes, in this order: **Build me a programme** (the 9-question flow) · **Start from a proven programme** (starter library) · **Import a backup** · *Just let me log* (skips straight to Today with no programme). Sets `firstRun.done`. |
| 2 | **Starter programmes** | Browse ~6 built-in programmes (5×5, Upper/Lower, PPL, Full Body 3×, Push/Pull, Bodyweight). Each shows days per week, session length, equipment needed, a one-line "who it's for". Preview → adopt. Needs no API key and no network. |

### Today tab

| # | Screen | Contents |
|---|---|---|
| 3 | **Today** (replaces Train) | Answers "what am I training today". States: **Due** (routine name + block summary + big Start) · **Rest day** (next session date) · **Missed** (what was skipped, with Do it now / Skip) · **Behind target** (flexible programmes) · **In progress** (resume) · **No programme** → renders the Find front door inline. Below: a consistency strip (this week vs target, week streak, last 7 days as dots) and recent sessions. |
| 4 | Session logger | Blocks → exercises → set rows. Per set: weight, reps, RPE, kind, complete. Superset blocks show "alternate between these". Sticky rest bar. **New: each set prefills with a suggestion plus its one-line reason** ("+2.5 kg — you hit 12 on every set last time"), always overridable. Previous-session reference per exercise. Add exercise mid-session; add/remove sets. |
| 5 | **Session summary** | Post-finish: duration, total volume, PRs won, suggestion acceptance, notes. Currently just a toast — worth a real screen because it is the moment the app proves it was worth logging. |

### Plan tab

| # | Screen | Contents |
|---|---|---|
| 6 | **Programmes** | List of programmes with the active one pinned. Create · adopt a starter · build with Claude. |
| 7 | **Programme detail / schedule** | Name, notes, source. **Schedule editor**: weekday mode (drop routines onto Mon–Sun) or rotation mode (ordered A/B/C + minimum gap days) or flexible (sessions/week target). Set active. Archive. |
| 8 | Routines | Routines belonging to a programme, plus loose ones. Create, duplicate, archive. |
| 9 | Routine editor | Blocks with drag-order; a block with >1 item is a superset. Per item: exercise, target sets, rep range, target weight, target RPE, notes. **New: per-item progression rule** (double / linear / reps-only / none) and increment override. Per block: rest seconds. |
| 10 | Exercise library | 82 seeded + custom. Search, filter by muscle group and equipment. Add/edit/delete custom. |
| 11 | **Exercise detail** | Per-exercise history, PRs, e1RM chart, all-time best, last performance. Currently only reachable as a dropdown on Progress. |

### Find

| # | Screen | Contents |
|---|---|---|
| 12 | Build me a routine | The fixed 9-question form — unchanged contract, same versioned skill asset, same determinism guarantee. Two paths: generate with Claude (needs key) or copy the prompt and paste the reply (no key, no network). |
| 13 | Plan preview | Programme name, summary, progression notes, "3 routines · 12 exercises matched · 4 will be created", day-by-day breakdown, **and the proposed schedule**. Import or discard. All-or-nothing. |

### Progress · Body · Settings

| # | Screen | Contents |
|---|---|---|
| 14 | Progress | Strength over time (e1RM + top set, per exercise) · weekly volume bars · sets per muscle group (stacked) · training frequency (26-week heatmap + sessions logged / this week / week streak). |
| 15 | Body | Bodyweight and 6 measurement types: entry form, line chart, history. |
| 16 | Settings | Backup export/import (replace vs merge) · CSV export · auto-snapshots · units · default rest · **reminders (on/off, time of day, missed-session nudge)** · **suggestions on/off + loading increments** · Claude API key and model · About. |

*(16 rows, 15 screens — Settings and Backup share one screen with sections, as
today.)*

### Cross-cutting UI states every screen needs a design for

Empty · loading · error · **stale-backup banner** · **"reminders are off"
banner** (permission denied) · rest-timer sticky bar · toast · confirm dialog ·
modal exercise picker.

---

## Data model

SQLDelight over SQLite. **Governing principle, stated once:**

> The session/set log is the only source of truth. Rotation position, streaks,
> consistency, suggestions and PRs are **pure functions** of it.

Nothing derived is stored mutably. A mutable "current rotation index" or "stall
counter" desynchronises the instant a backup is merged — and merge-import is a
first-class operation here. This is also what makes the engines trivially
testable: fixture in, expected out.

### Tables

New tables: **`program`** (name, source, `schedule_kind`, `rotation_gap_days`,
`weekly_target`, `started_on`, `is_active`) and **`program_slot`** (position,
`routine_id` nullable for explicit rest slots, `weekday` 1–7).

Blocks and items become **real tables** (`routine_block`, `routine_item`) rather
than embedded JSON, for one decisive reason: **progression needs a durable
identity for a planned item.** An index into a JSON array shifts the moment you
reorder a routine, silently re-pointing history at the wrong exercise. A
`routine_item.id` does not. It also turns "sets performed against this planned
item" into one indexed query instead of parsing every routine.

The obvious objection — that JSON made the routine a clean atomic merge unit — is
answered by a rule rather than by the storage shape:

> **`routine_block`, `routine_item` and `program_slot` carry no `updated_at` /
> `deleted_at`. They are not merge units — their parent is.** When an incoming
> routine wins on `updated_at`, its children are deleted and reinserted wholesale
> in one transaction.

That reproduces v1's merge semantics exactly, and the **backup wire format keeps
blocks embedded inside the routine object**, so existing `workoutmate.backup.v1`
files still import. Storage shape and wire shape are allowed to differ; a
~60-line tested mapper is the price.

Notable columns: `session.local_date` + `tz_id` (honest history when travelling);
`exercise_set.routine_item_id` and `index_in_item` (so ramping sets 60/80/100
progress individually rather than being averaged); a **plan snapshot** on each set
(`target_reps_min/max`, `target_rpe`) so history explains itself after a routine
is edited; and `suggested_weight_kg` / `suggested_reps` / `suggestion_source` so
suggestion acceptance is derivable rather than stored.

`pr` becomes an explicitly **rebuildable cache** with a `rebuildPrs()` function
run after every import, asserted in tests as `rebuild(derived) == stored`. This
fixes a real latent bug in v1: a merge-import can bring in older sets without
recomputing PRs.

### Backup

Envelope `workoutmate.backup.v2`. **Reader accepts v1 and v2; writer emits v2.**
Wire field names stay v1-identical so one DTO layer reads both eras.

---

## Engines (all pure, all in core, all locally tested)

**`whatIsDue(program, slots, sessions, today, tz): DueToday`** — weekday mode
matches today's ISO day and flags earlier-in-week slots with no session as
*missed*, never auto-rolling them forward. Rotation mode derives position from
the most recent completed session's `program_slot_id` and checks
`daysSince >= rotation_gap_days`. Flexible mode compares sessions this week
against `weekly_target`. No active programme → the Find front door.

**Consistency** — sessions this week vs target, and `weekStreak` in **weeks, not
days**. A day-streak punishes rest days, which is wrong for lifting and would
push the owner toward junk volume.

**`suggestNext(item, history, settings): Suggestion?`** — returns weight, reps,
source and a human rationale. Default double progression:

1. No history → `target_weight_kg` if set, else no suggestion.
2. All working sets completed at *W* and every set at `reps_max` → **W + increment at `reps_min`**.
3. All sets ≥ `reps_min` but not all at max → **same W, top set +1 rep**.
4. Any set below `reps_min` → **repeat W**.
5. Two consecutive stalls at the same W → **deload to W × 0.9**, rounded down to a loadable step.
6. **RPE guard** — if last top-set RPE ≥ `target_rpe + 1`, downgrade *add weight* to *add rep*.
7. **Rounding** to a loadable step: item override ?? exercise default ?? equipment default (barbell 2.5 kg, dumbbell 2.0, machine/cable 5.0, bodyweight → reps only). An increase rule may never produce a decrease.
8. **Auto-quiet** — override downward on the same item three times running and it stops suggesting increases there, and says so.

Rationale strings are built in core so they are covered by tests rather than
being blind-zone string concatenation. Hold the line at these eight rules: the
brief lists *automated progression* as a non-goal, and "suggest, I decide" is a
deliberate, narrow reversal of it. If a rule cannot be explained to the user in
one sentence, it does not ship.

**Reminders** — `reminderFor(due, consistency, today, prefs): Reminder?`, pure.
Suppress if a session was completed today, if there is no active programme, or if
one has already fired today.

### Notification scheduling: `AlarmManager`, not WorkManager

One daily check-in alarm at the user's chosen time, plus a
`BOOT_COMPLETED`/`TIMEZONE_CHANGED` receiver to reschedule.
`setWindow()`/`setAndAllowWhileIdle()` need **no permission** — we deliberately
avoid `SCHEDULE_EXACT_ALARM` (Play-policy restricted) and `setAlarmClock` (plants
a system alarm icon). A ±15–60 minute window on a training reminder is fine.

**The rest timer is upgraded**, fixing a known v1 limitation: persist the
deadline, schedule an alarm for it so it fires even if the process was killed,
and let the ongoing notification use `setUsesChronometer(true)` +
`setChronometerCountDown(true)` so the system counts down for free — replacing
v1's once-per-second `notify()` call with less code.

**Without `POST_NOTIFICATIONS` nothing breaks**: the timer still runs, tone and
vibration are not permission-gated, and Today remains the source of truth. If
reminders are enabled but permission is denied, a persistent in-app row offers to
request it — shown once, never nagged. Reminders are best-effort by contract, and
the UI says so, because OEM battery managers will delay them.

---

## Implementation order

🟢 locally verifiable · 🔴 CI/PC-blind · 📱 device-only

- **Phase 0 — Skeleton and CI proof. 🔴 Almost no code, done first.** Both builds, composite wiring, `core` with one trivial test, `:app` rendering one string *read from core*. CI split into `-p core check` and `assembleDebug`. Proves composite substitution, androidJvm↔jvm consumption, SQLDelight-in-composite and Kotlin/AGP/Compose alignment — every hard-stop risk, cheaply, before any feature code exists. **If this is not green in a day, take the single-build fallback.**
- **Phase 1 — Domain, schema, ported tests. 🟢** Write the `.sq` schema, then **port v1's 72 tests before the code they test**. Those tests are the only precise specification of this app's behaviour; any test that must change is a product decision that gets written down.
- **Phase 2 — Repositories, backup, import. 🟢** Repos, DTO wire layer, v1+v2 reader, replace and merge, `rebuildPrs()`, CSV.
- **Phase 3 — Vertical slice on device. 🔴📱** `AppGraph`, `AndroidSqliteDriver`, one screen listing the 82 seeded exercises from the real database. Proves driver, seeding from jar resources, DI and packaging — small, and it makes every later phase an increment rather than a big bang.
- **Phase 4 — Presenters, navigator, engines. 🟢 The bulk of the app.** Every screen gets a `StateFlow<XState>` + `onEvent(XEvent)` presenter. Scheduling, suggestions, chart geometry, the Anthropic request/response layer against a fake `HttpPoster`, reminder decisions. **If a phase is growing blind-zone code, it belongs here instead.**
- **Phase 5 — Compose screens. 🔴 The long blind stretch — sequenced for the home PC.** Today → Session → Plan/Routines → Find → Progress → Body → Settings. **Enforced rule: no conditional logic in a composable beyond rendering; every branch is a field on the state object.** That converts blind-zone bugs from logic bugs into layout bugs — the only kind you can fix from a screenshot.
- **Phase 6 — Adapters. 🔴📱** SAF, secure store, HTTP, notifications, alarms, rest timer, boot receiver.
- **Phase 7 — Front door and the new features wired through.** First run, starter programmes, schedule editor, consistency strip, suggestion prefills.
- **Phase 8 — Hardening. 📱** Process death, mid-session kill, R8/release config, one instrumented CI smoke test, refreshed `docs/SMOKE_CHECKLIST.md`.

---

## Verification

**Green zone — `./gradlew -p core check`, seconds, offline.** Training maths
(e1RM/Epley cap, volume, PR detection) · date bucketing including DST and
year-boundary cases · merge and validate semantics · **real SQLite query tests
via `JdbcSqliteDriver`** plus `verifyMigrations` — capability v1 never had ·
scheduling across both modes including out-of-order imports · a table-driven
fixture set covering every progression rule, rounding case, the RPE guard,
deload and auto-quiet · presenter tests with `runTest` + `StateFlow`, replacing
most of v1's 31 Playwright checks · exact chart geometry · content invariants
(every starter programme's exercise names resolve against the 82-exercise seed).

**CI.** `assembleDebug` + `lintDebug` + APK artifact. Add a
`compileDebugKotlin`-only job for WIP branches — the difference between a
90-second and a 5-minute blind loop, run hundreds of times during Phase 5. After
Phase 5, one instrumented emulator test (launch → log a set → recreate activity →
assert it survived): ~8 minutes of CI and the **only** automated coverage the UI
will ever get.

**Manual, on device.** SAF pickers · notification permission grant/deny/revoke ·
alarms with the screen off and under Doze · Keystore behaviour · back button ·
and the thing no test covers: whether it is actually usable one-handed between
sets.

---

## Risks

1. **Compose has no local verification, and there is provably no workaround here** — not even a desktop harness. *Reduced by:* sequencing Phase 5 onto the owner's home PC, keeping the UI dumb, the no-conditionals rule, and a fast compile-only CI job.
2. **One big-bang v1 with slow feedback** — the largest schedule risk, and the owner's explicit choice. *Reduced by:* front-loading every hard-stop unknown into Phases 0 and 3, which are both tiny, so a wrong assumption surfaces in hours rather than weeks.
3. **Version alignment (Kotlin ↔ Compose ↔ AGP ↔ SQLDelight) fails only at build time.** *Reduced by:* one pinned catalog, holding AGP at the version CI is already green on, and treating any bump as its own commit with its own CI run and nothing else in it.
4. **Losing behaviour the 72 tests encode.** *Reduced by:* porting them in Phase 1, before the code they test.
5. **Composite-build/KMP variant friction.** *Reduced by:* proving it in Phase 0, with two named fallbacks ready.
6. **Alarms are untestable off-device and OEM battery managers will delay them.** *Reduced by:* pure, tested decision functions; best-effort by contract; Today always the source of truth.
7. **Scope creep in the suggestion engine into a coaching product.** *Reduced by:* the eight-rule ceiling above.

**Not in v1:** iOS build · cloud sync or accounts · social · exercise demo media ·
wearables and health APIs · nutrition · Play Store release signing · onboarding
for users who aren't the owner.

---

# NEXT — the work after Phase 0

## Where things actually stand

Phase 0 is **done and green on both halves** (commit `ef67b32`). CI passed first
try: core check ✓, Android compile ✓, APK ✓, lint ✓. That cleared the two risks
that could only ever fail at build time — composite substitution (an Android
consumer resolving a JVM-target producer across build boundaries) and
Kotlin 2.1.20 / Compose Multiplatform 1.8.2 / AGP 8.11.1 alignment.

Also done: the full 10-table schema generates and compiles; the WebView layer
and its 103 tests are deleted; the exercise seed and skill contract are
preserved into `core/src/jvmMain/resources/content/`.

**Confirmed for this stretch:** screen state contracts are the priority · I pick
the starter programmes · tonight's session on the owner's PC targets *a running
app you can tap through*.

That last point sets the ordering. The bottleneck is blind Compose work, so
everything that removes decisions from that session gets done here first.

## Deliverable 1 — screen state contracts (do this first)

For each of the 15 screens in the site map, a `data class XState` plus a sealed
`XEvent`, in `core/src/commonMain/kotlin/.../presentation/`. Every variant a
screen can be in is a **field or sealed subtype**, never a condition evaluated
in a composable.

This serves two purposes at once: it is the contract tonight's Compose work
renders against, and it is a precise thing to design against — every field,
empty state and error case enumerated rather than described.

Start with `TodayState`, because it has the most variants and is the screen the
whole product pivots on:

```kotlin
sealed interface TodayState {
  data object Loading : TodayState
  data class NoProgramme(val recent: List<SessionSummary>) : TodayState   // renders Find inline
  data class Due(val slot: SlotView, val programme: String, val consistency: ConsistencyView, ...)
  data class RestDay(val nextOn: LocalDate, val nextSlot: SlotView?, ...)
  data class Missed(val missed: List<SlotView>, val alsoDueToday: SlotView?, ...)
  data class BehindTarget(val done: Int, val target: Int, val suggestion: SlotView?, ...)
  data class InProgress(val sessionId: String, val startedAt: Instant, ...)
}
```

## Deliverable 2 — domain model and the ported specification

Kotlin data classes mirroring the schema, plus the enums (`MuscleGroup`,
`Equipment`, `SetKind`, `ScheduleKind`, `ProgressionRule`, `MeasurementType`,
`PrKind`, `SuggestionSource`).

Then **port v1's 72 tests before the code they test** — 23 stats, 18 merge,
18 validate, 13 scale. They are the only precise specification this app has.
The exact v1 behaviour has been recovered from git history at `9c80088`; the
traps that matter:

- `epley1RM` truncates reps but never weight; returns the weight verbatim at
  1 rep; returns 0 above 12 reps and for any non-positive or non-numeric input.
- `isCountedSet` checks `completed === true` by identity, not truthiness.
- All in-session bests use strict `>`; the comparison against stored bests uses
  `>=` to reject, so **ties never produce a record** — "you have to actually
  beat it".
- `detectPrs` stamps every record's `sessionId` from the *first qualifying set*
  of that exercise, not from the set that set the record.
- Insertion order is observable in `detectPrs` and in weekly bucketing — use
  `LinkedHashMap`.
- ISO week and local-day bucketing must go through a local-calendar API
  (`kotlinx-datetime` with the system zone), never fixed-millisecond
  arithmetic. v1's `Math.round` existed purely to absorb DST; `WeekFields.ISO`
  reproduces the label directly and safely.
- `setsPerMuscleGroup` files an exercise under `other` by two independent
  paths: unknown exercise, *and* known exercise with an empty group list.

### A divergence already in the tree, to fix here

`roundForDisplay` in `core/.../domain/Units.kt` rounds to two decimals. **v1
rounded to the nearest 0.5**, and `docs/SMOKE_CHECKLIST.md:106` documents the
expected behaviour as "100kg reads as 220.5lb". The current Kotlin renders
`220.46`, and `UnitsTest` pins that wrong value. Fix both.

Note while fixing: 0.5 rounding is right for barbell weights and wrong for
bodyweight (82.3 kg would render 82.5). Measurements need their own precision —
v1 got away with this because measurements were formatted separately.

## Deliverable 3 — repositories and the merge rule

Row ↔ domain mapping, and the write pattern the schema's design depends on:
when a routine or programme is written, its children are **deleted and
reinserted wholesale inside one transaction**. Backup wire format keeps blocks
nested inside the routine object so v1 backups still import.

Plus `rebuildPrs()` — with the test that pins the whole reason `pr` is a cache:
`rebuild(derived) == stored` after a merge import.

## Deliverable 4 — engines

`whatIsDue` across weekday / rotation / flexible, deriving rotation position
from the session log rather than storing it. Consistency in ISO weeks.
`suggestNext` with the eight-rule ceiling. `reminderFor`. Table-driven fixtures.

## Deliverable 5 — starter programmes

Six programmes as JSON content beside the skill asset: 5×5, Upper/Lower, PPL,
Full Body 3×, Push/Pull, Bodyweight-only. Written using **only** exercises in
the 82-exercise seed library, with a content test asserting every referenced
name resolves — cheap, and it catches content drift the moment it happens.

## Content loading

`ContentSource` interface in `commonMain`, classpath implementation in
`jvmMain`. Jar resources are packaged into the APK, so the JVM implementation
serves Android too; iOS later needs its own. This keeps seeding logic in
`commonMain` where it is testable.

## Handoff to a machine that can build

Target: **a tappable app**. What must exist for that to be mechanical — the
`Navigator` (back stack, serialisable for process death), every screen's state
contract, and presenters producing real state from repositories. Then the
Compose layer is `@Composable fun XScreen(state: XState, onEvent: (XEvent) -> Unit)`
with nothing left to decide.

## Verification

`./gradlew -p core check` after every deliverable — target ≥ 72 ported
assertions plus the new engine and repository tests. Any v1 test that has to
change is a product decision and gets written down rather than quietly edited.

---

# Starting a session on a machine that can build Android

Paste this to begin. It is written to stand alone — assume the session has no
memory of how any of this came about.

```text
Work on WorkOutMate, branch: claude/workout-tracker-routine-builder-slwmjf

Read docs/PLAN.md first — it's the approved roadmap and explains why the
architecture is shaped the way it is. docs/PROJECT.md is the product brief.

CONTEXT: This is a ground-up v2 rewrite. A WebView/HTML app was deleted and
replaced with Kotlin Multiplatform + Compose Multiplatform (Android only for
now; iOS deliberately not enabled). Phase 0 is done and CI is green.

THE BUILD IS TWO SEPARATE GRADLE BUILDS. This is load-bearing, not tidiness:
  ./gradlew -p core check    # core: no Android plugin, real SQLite, seconds
  ./gradlew assembleDebug    # the Android app

`core` is a separate build so it compiles and tests with no Android SDK, and
its settings.gradle.kts declares ONLY mavenCentral so an androidx dependency
can't sneak in. Never add one to core. Never merge the builds.

WHAT COULD NOT BE DONE PREVIOUSLY: that environment blocked Google's Maven
entirely, so no Compose code was ever compiled — only CI verified it.
THIS MACHINE CAN BUILD. So:

  FIRST, before writing anything: run ./gradlew assembleDebug and confirm it
  works locally. Then install and launch it. It currently renders one screen
  proving the wiring; that's expected.

GOAL FOR THIS SESSION: a running app I can tap through. Breadth over depth —
stub data on a screen is fine if the navigation works and it feels real.

ORDER:
 1. Confirm the local build and get the current APK on my phone.
 2. Whatever core work remains per docs/PLAN.md (state contracts, domain
    model, ported tests, repositories, engines) — all verifiable locally,
    do it with `-p core check` in a tight loop.
 3. Then the Compose screens against those state contracts.

RULES THAT MATTER:
- No conditional logic in composables beyond rendering. Every branch is a
  field on the state object. Screens are:
  @Composable fun XScreen(state: XState, onEvent: (XEvent) -> Unit)
- The session log is the ONLY source of truth. Rotation position, streaks,
  consistency, suggestions and PRs are pure functions of it — never stored
  mutable state. A merge-import would desynchronise anything stored.
- Weights are stored in kg always; pounds are display-only. Default is lb.
- minSdk 26 ships SQLite 3.18 — no upsert syntax, no window functions.
- The Anthropic API key must never be readable by the UI layer.
- Port v1's 72 tests BEFORE the code they test. They're recoverable from git
  at commit 9c80088 (tests/*.test.mjs, deleted in eaec4b6) and are the only
  precise specification this app has. Any test that must change is a product
  decision — tell me, don't quietly edit it.

KNOWN BUG TO FIX: core/.../domain/Units.kt roundForDisplay rounds to two
decimals; v1 rounded to the nearest 0.5 and docs/SMOKE_CHECKLIST.md documents
"100kg reads as 220.5lb". UnitsTest currently pins the wrong value. Also
consider that 0.5 rounding is right for barbell weights and wrong for
bodyweight — measurements need their own precision.

Ask me before making product decisions. Don't create a PR unless I ask.
```
