---
name: build-me-a-routine
description: Generate a WorkOutMate training programme from a fixed question script, returning JSON that imports directly into the app. Use when the user wants a workout routine built, or wants to test or change the app's routine-generation prompt.
---

# Build me a routine

This is the Claude Code side of the app's "Build me a routine" feature. It exists so a
routine can be generated from a terminal — for testing prompt changes, or for a user who
would rather not paste an API key into their phone.

## The script is data, not prose

**`app/src/main/assets/www/skills/build-me-a-routine.v1.json` is the single source of
truth.** It holds the question list, the system prompt, and the JSON Schema for the reply.
The app reads that file at runtime and this skill reads the same file, so the two cannot
drift.

Read it before doing anything else:

```
app/src/main/assets/www/skills/build-me-a-routine.v1.json
```

## How to run it

1. Read the JSON asset above.
2. Ask the user every question in `questions`, **in order, without rewording them**. That
   consistency is the point: the same answers must always produce the same prompt, whether
   they came through the app or through here. Questions with `required: false` may be
   skipped.
3. Build the reply using the `systemPrompt` as your instructions and the answers as input.
4. Return a single JSON object conforming to `outputSchema`, with no commentary around it.

The app pins the API to that schema with structured outputs. You have no such guarantee,
so check your own output against it before returning.

## Getting the result into the app

Give the user the raw JSON. In the app: **Routines → Build me a routine → Paste a reply →
Check and preview**. The app validates it, shows what will be created — including how many
new exercises it will add to the library — and only writes anything after they confirm. An
import is all or nothing.

If validation fails, the app lists exactly which fields were wrong; fix those and hand back
a corrected object.

## Changing the script

Edit the JSON asset, never this file. Two rules:

- **Bump the version** — filename, `version` field, and the `schema` string — whenever the
  output shape changes. Old plans should keep validating against the version they were
  written for.
- **Keep `validate.js` in step.** The JSON Schema constrains shape; `validate.js` enforces
  the ranges that JSON Schema cannot express (set counts, rep ranges, rest intervals) and
  is the *only* line of defence on the copy/paste path. Its tests are in
  `tests/validate.test.mjs`.
