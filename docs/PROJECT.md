# WorkOutMate — project brief

What this app is for and how we'd know it worked. Written after a first
implementation pass, from the original brief and the decisions taken since.

> **Status: draft, unconfirmed.** Statements marked **[stated]** are the owner's
> own words. Statements marked **[inferred]** are my reading and have not been
> confirmed — correct anything wrong. Open questions are collected at the end,
> and several of them are load-bearing.

---

## The intent, in one line

> "routines can have target reps but overall this is ment to map out effort and
> chart progress" **[stated]**

Everything else follows from that sentence. This is not a workout *planner* and
not a coaching app — it is an instrument for recording effort accurately and
seeing what it added up to. Where a feature helps you see change over time it
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

- **"find"** — if people struggle to work out *what* to train, then "Build me a
  routine" is not a convenience bolted onto a logger; it is close to the front
  door. That is a bigger claim than the current build makes, and it is flagged
  as **D5** below rather than assumed.
- **"maintain"** — adherence is part of the problem, not just recording. The
  current app is deliberately passive: no notifications, no streaks, no
  nudging. That was the right call for a single informed user and is an open
  question for this wider audience. Also **D5**.

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

Feature scope selected for v1:

| Group | Included |
|---|---|
| Core loop + charts | Exercise library, routine builder with targets, session logging, previous-session reference, backup export/import, estimated 1RM / weekly volume / frequency charts |
| Rest timer + PR detection | Auto-start rest between sets; automatic best-e1RM, best-weight, best-volume records |
| Bodyweight + measurements | Separate log and chart |
| Supersets, RPE, warm-up sets | Richer set metadata; supersets in the routine builder |

## Desired outcomes

**[inferred — needs confirmation, see Q4]** A good version of this means:

1. Logging a set during a workout is fast enough that you actually do it, rather
   than writing it on your phone's notepad and never transcribing it.
2. After a few months, the charts answer "am I actually getting stronger?"
   without you having to do arithmetic.
3. You can change phones, or wipe the app, without losing your training history.
4. You never think about the app between workouts — no notifications to
   dismiss, no feed, no streak guilt.

Point 4 is now in open tension with "anyone else who struggles to … maintain
them". Helping someone stick to a programme usually means *some* prompting, and
this app currently does none. Both positions are defensible; they are not both
true at once. See **D5**.

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
- Automated progression or auto-regulated deloads — the app records what you
  did, it doesn't decide what you should do next
- Nutrition or calorie tracking
- iOS

## Success criteria

**[inferred — needs confirmation, see Q4]** Proposed, in rough priority order:

1. **It doesn't lose data.** A logged set is still there after a force-stop, an
   app update, and a backup/restore cycle. This is the one that matters; a
   tracker that loses history has no reason to exist.
2. **It survives a real workout.** A full session can be logged one-handed,
   between sets, without fighting the interface.
3. **The charts get used.** They're consulted when deciding what to lift, not
   just admired once.
4. **It stays out of the way.** No maintenance, no accounts to re-auth, no
   surprises.

## Open decisions

These are genuinely unresolved and are recorded here rather than settled
quietly. The first two were decided during implementation *without* sign-off and
should be treated as provisional.

### D1 — HTML in a WebView, or fully native? **(provisional)**

The original brief asked for both an APK and a single HTML page. That was later
changed to "drop web version, only do an android build" **[stated]**, alongside
selecting a "native WebView wrapper" for packaging — which by definition renders
HTML.

**Taken as:** no separate web deliverable, but HTML/CSS/JS remains the UI *inside*
the APK. **Not confirmed.** If the intent was fully native (Compose + Room), the
UI and storage layers would need rewriting; the data model, Kotlin shell, and
routine-builder contract would survive.

### D2 — Where the Claude API key lives **(provisional)**

Never answered. **Taken as:** the HTTP call is made from Kotlin, the key is held
in Keystore-backed storage, and the web layer can request that a call be made
but can never read the key back. A copy/paste path that needs no key and no
network is always available alongside it.

### D3 — Is the web version dropped or deferred?

"skip the web html completely now" **[stated]** — the "now" reads as deferral
rather than cancellation, but that hasn't been confirmed. It matters: the
current architecture keeps a web build cheap, and a fully native rewrite would
close that door permanently.

### D4 — Distribution

Undecided. Currently debug-signed APKs from CI, suitable for sideloading. Play
Store release would require signing keys, a privacy policy, store assets, and a
target-API commitment.

### D5 — How far does "find the right workouts and maintain them" go?

Raised by the audience answer, not yet decided. The app as built is a logger
with an optional routine generator and no adherence features at all. Taking the
sentence at full strength would change its centre of gravity:

- **Finding** — does "Build me a routine" become the primary entry point (open
  the app, answer nine questions, get a programme), rather than something you
  reach for once? Does the app need built-in starter programmes for people with
  no key and no appetite for copy/paste?
- **Maintaining** — does the app do anything to keep you coming back? Scheduled
  reminders, a "you're due for Day B" prompt, and streak or consistency views
  are all standard in this category and all currently absent by design.

**Nothing has been built for either.** Recorded here for a decision rather than
inferred, because it would meaningfully change what the app is.

---

## Questions I still need answered

1. **Web version: dropped or deferred?** (D3)
2. **How far does the "find and maintain" goal go?** (D5) — this is the biggest
   open question in the document; the answer decides whether the app stays a
   logger or grows into something that also coaches adherence.
3. **What does "done" look like?** Is there a moment where you'd stop building
   and just use it, or is this an ongoing project? It decides whether to invest
   in polish or in extensibility.
4. **kg or lb?** Both are supported and switchable; this only affects the
   default.
5. **Do the "anyone else" users get onboarding?** Exercise explanations, an RPE
   primer, a guided first routine — none of it exists. Cheap to add later,
   awkward to retrofit if the audience answer is taken seriously now.
