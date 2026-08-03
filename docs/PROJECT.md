# WorkOutMate — project brief

What this app is for and how we'd know it worked. Written after a first
implementation pass, from the original brief and the decisions taken since.

> **Status: current as of the v2 rewrite.** Statements marked **[stated]** are
> the owner's own words; **[inferred]** ones are a reading that has not been
> confirmed. `docs/PLAN.md` is the implementation roadmap — this file is *why*,
> that file is *how*.

---

## The intent, in one line

> "routines can have target reps but overall this is ment to map out effort and
> chart progress" **[stated]**

This was the founding sentence and it still governs the *logging* half of the
app. It is no longer the whole story: the audience answer below widened the job
to include helping someone **find** a programme and **maintain** it, which is a
larger claim than "instrument". Both are true now, and where they conflict —
see the note under Desired outcomes — the conflict is called out rather than
resolved silently.

This is still not a coaching app: it suggests, it does not decide. And the
original test still applies — where a feature helps you see change over time it
belongs; where it doesn't, it probably doesn't.

Two consequences worth stating explicitly, because they drove real decisions:

- **Charting is a feature, not a reporting afterthought.** If logging were the
  whole point, a notes app would do.
- **The log has to be trustworthy.** A chart built on data that silently lost a
  set is worse than no chart. This is why the write path is defensive and why
  backup is treated as core rather than as a settings-screen extra.

## Why this exists

Three reasons, in the owner's words **[stated]**:

1. **"I want to build my own."** Wanting to own the thing is a legitimate
   first-order reason and it is deliberately listed first. It means the project
   is not competing with Hevy or Strong on features, and "app X already does
   this" is not, by itself, an argument against building it here.
2. **"I want to get in shape."** The app has a job to do for a real person with
   a real goal. That is the test any feature has to pass.
3. **"I don't care to give my data to other people either."** Data stays on the
   device. No account, no server, no analytics.

Note what is *not* here: there is no list of grievances with existing apps
driving this. That matters, because it means the brief should not be written as
"the tracker that finally gets X right" — reason 1 and reason 3 are the whole
of it. The market research done during planning simply confirms the shape is
viable: FitNotes is fully offline, free forever and no-account, and people rely
on it for years. So "no server" is a sound product decision rather than a
sacrifice.

## Who it's for

> "me, and anyone else who struggles to find the right workouts and maintain
> them" **[stated]**

So: the owner first, but not only the owner. Two words in that sentence carry
real weight and should be read deliberately —

- **"find"** — if people struggle to work out *what* to train, then getting you
  a programme is not a convenience bolted onto a logger; it is the front door.
  **Acted on:** first run leads with it, and there are built-in starter
  programmes for people who won't touch an API key.
- **"maintain"** — adherence is part of the problem, not just recording. v1 was
  deliberately passive: no notifications, no streaks, no nudging. **Acted on:**
  scheduling, "what's due today", one reminder a day and consistency feedback.
  This is the single biggest change from v1, and it was a reversal of the
  original design rather than an extension of it.

The immediate practical consequence: **anyone who isn't the owner cannot be
asked for an Anthropic API key.** That effectively settles the routine-builder
UX — the copy/paste path is the primary route and the API-key path is the
power-user shortcut, not the other way round.

Beyond that, the app currently assumes an informed user: no onboarding, no
empty-state hand-holding beyond a sentence, no exercise demonstrations, and
terms like RPE used without explanation. Defensible for the owner; a real gap
for "anyone else". Not yet addressed.

## What it does

Stated requirements, in the owner's framing:

- **Create a routine, then log against it.** "you create the work out routine,
  and then log against it what you did in a session" **[stated]**
- **Routines carry targets.** "routines can have target reps" **[stated]**
- **Local storage with an explicit backup.** "uses the index DB and a local
  'save progress' option to ensure they have a backup if the cache goes bad"
  **[stated]** — note the motivation: protection against *storage loss*, not
  against device loss or for sync.
- **No server.** "things that can be done without a server backend" **[stated]**
- **No sensors.** "We are not tying in things like sensors or anything like
  that, purely a tracking app" **[stated]**
- **Build me a routine.** "a section called 'build me a routine' where we
  connect to claude api and build a routine based off of the chat" **[stated]**
- **A fixed question script for that feature.** "maybe that needs to be a skill
  we hit so it always asks the same question" **[stated]** — this is why the
  question list lives in a versioned JSON asset rather than in prose: the same
  answers must always produce the same request.

Added in v2, from the "find … and maintain them" audience answer:

- **Finding is the front door.** First run leads with getting you a programme —
  a built-in starter or one Claude writes — rather than an empty logger.
- **What's due today.** Programmes schedule routines by weekday, by rotation, or
  against a weekly target, and the home screen answers the question directly.
- **Reminders**, best-effort, on training days and after a missed session.
- **Consistency feedback** — sessions against target, week streaks, heatmap.
- **Progression suggestions** — every set prefills with a suggested next step
  and a one-line reason. Always overridable. See D5.

Feature scope:

| Group | Included |
|---|---|
| Core loop + charts | Exercise library, routine builder with targets, session logging, previous-session reference, backup export/import, estimated 1RM / weekly volume / frequency charts |
| Rest timer + PR detection | Auto-start rest between sets; automatic best-e1RM, best-weight, best-volume records |
| Bodyweight + measurements | Separate log and chart |
| Supersets, RPE, warm-up sets | Richer set metadata; supersets in the routine builder |
| **Programmes and scheduling** | Starter library, weekday/rotation/flexible scheduling, "what's due today", missed-session handling |
| **Adherence** | Reminders, consistency strip, week streaks |
| **Progression** | Per-item progression rules, suggested next set with rationale |

## Desired outcomes

**[inferred]** A good version of this means:

1. Logging a set during a workout is fast enough that you actually do it, rather
   than writing it on your phone's notepad and never transcribing it.
2. After a few months, the charts answer "am I actually getting stronger?"
   without you having to do arithmetic.
3. You can change phones, or wipe the app, without losing your training history.
4. ~~You never think about the app between workouts — no notifications to
   dismiss, no feed, no streak guilt.~~ **Superseded.** This was written before
   the audience answer, and it directly contradicts helping someone *maintain* a
   programme, which usually needs some prompting. Resolved in favour of
   maintenance, but narrowly: **at most one reminder a day, suppressed entirely
   once you've trained, and never a guilt mechanic.** The home screen — not a
   notification — is always the source of truth for what's due.

   The original instinct survives as a constraint rather than a goal: the app
   gets to speak once a day and no more.

## Constraints that shaped the build

- **No server, no account, no sync.** Everything is on the device.
- **Because of that, backup is load-bearing.** Clearing app data or
  uninstalling destroys the entire history, so export/import is a primary
  feature and the app nags when a backup is stale.
- **No sensors, no wearables, no health APIs.** Manual entry only.
- **Offline by default.** The only network call in the app is the optional
  Claude request. Everything else works with the radio off — which matters in
  a basement gym.
- **Data must survive being wrong.** Deletes are soft, so restoring an older
  backup can't silently resurrect records you deliberately removed.

## Explicit non-goals

Deliberately excluded. Any of these could be revisited, but none should arrive
by accident:

- Cloud sync, accounts, multi-device
- A social feed, sharing, or leaderboards
- Exercise demonstration videos or images
- Wearables, heart rate, step counting, health-platform integration
- Nutrition or calorie tracking
- Play Store distribution (sideloaded APKs for now)
- ~~iOS~~ — still not built, but no longer excluded on principle. The core is
  structured so adding a target is additive rather than a rewrite.
- ~~Automated progression or auto-regulated deloads~~ — **narrowly reversed by
  D5.** The app now suggests a next step and shows why, but never applies one.
  The distinction that keeps this a non-goal in spirit: **it prefills a field
  you can overtype.** It never writes a number you did not accept, and it stops
  suggesting increases on a lift you keep overriding downward.

## Success criteria

**[inferred]** Proposed, in rough priority order:

1. **It doesn't lose data.** A logged set is still there after a force-stop, an
   app update, and a backup/restore cycle. This is the one that matters; a
   tracker that loses history has no reason to exist.
2. **It survives a real workout.** A full session can be logged one-handed,
   between sets, without fighting the interface.
3. **The charts get used.** They're consulted when deciding what to lift, not
   just admired once.
4. **It stays out of the way.** No maintenance, no accounts to re-auth, no
   surprises.

## Decisions

All confirmed with the owner. D1–D3 were open in the first draft and are now
settled; the rest came out of the v2 replan.

| # | Decision |
|---|---|
| **D1** | **Kotlin Multiplatform + Compose Multiplatform.** The WebView/HTML UI is deleted. Was provisional; now decided outright. |
| **D2** | **The Claude API key lives in Kotlin**, in Keystore-backed storage. No port exposes a getter — the UI can ask *whether* a key exists and ask for a call to be *made*, never read it back. A copy/paste path needing no key and no network is always available, and is the **primary** route. |
| **D3** | **No web version.** Superseded by D1 rather than deferred; a web build is no longer cheap and is not planned. |
| **D4** | **Distribution: sideloaded debug APKs from CI.** Play Store would need signing keys, a privacy policy, store assets and a target-API commitment. Not v1. |
| **D5** | **Progression suggests, the user decides.** Eight rules, all pure and tested: double progression, RPE guard, deload after two stalls, loadable-weight rounding, per-set matching, auto-quiet on repeated downward overrides. Every suggestion carries a one-line reason. If a rule can't be explained in one sentence, it doesn't ship. |
| **D6** | **Scheduling supports weekday, rotation and flexible**, chosen per programme. |
| **D7** | **One big v1**, not staged delivery. |
| **D8** | **No legacy data to preserve** — v1 was never used in anger. A v1 backup importer is built anyway, cheaply, as insurance. |
| **D9** | **Default units: pounds.** Storage is kilograms regardless. |
| **D10** | **Audience: the owner alone for now.** No onboarding for strangers in v1 — but see the open question below, because it is the one thing the audience answer implies and v1 does not deliver. |

### Architectural decisions worth knowing

Recorded in `docs/PLAN.md` in full. The three that constrain everything else:

- **`core` is a separate Gradle build with no Android plugin**, so all logic
  compiles and tests in seconds with no SDK. Its repository list declares only
  Maven Central, which makes adding an androidx dependency fail immediately
  rather than silently rotting the testable half.
- **The session log is the only source of truth.** Rotation position, streaks,
  consistency, suggestions and personal records are pure functions of it. Storing
  any of them would desynchronise the moment a backup was merged — and merging is
  a first-class operation here.
- **Weights are stored in kilograms, always.** Storing whichever unit was
  selected would make every chart and record depend on a preference the user can
  change.

## Still open

1. **Do the "anyone else" users get onboarding?** No exercise explanations, no
   RPE primer, no guided first routine. Fine for the owner; a real gap against
   the stated audience. Cheap to add, awkward to retrofit.
2. **What does "done" look like?** Whether there is a point where this stops
   being built and starts just being used decides how much to invest in polish
   versus extensibility.
