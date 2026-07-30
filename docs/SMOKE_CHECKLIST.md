# On-device smoke checklist

The automated tests cover everything above the Kotlin shell (`node --test tests/*.test.mjs`
and `node tests/browser-smoke.mjs`). This list covers what only a real device can prove —
the WebView bridge, the storage layer under Android's lifecycle, and the file pickers.

Run it against a debug APK before any release.

## 1. Storage survives the app being killed

**The single most important check.** Everything else is replaceable; training history is not.

1. Create a routine and log a workout with at least one completed set.
2. Force-stop the app from Android Settings (not just swipe it away).
3. Reopen it.

- [ ] The session is still listed under **Recent sessions**, with its weights and reps intact.
- [ ] Charts on **Progress** still show it.

If this fails, the WebView is not serving from the virtual `https://` origin and IndexedDB
is not persisting — check `WebViewAssetLoader` in `MainActivity` before looking anywhere else.

## 2. An interrupted workout resumes

1. Start a workout and complete one set.
2. Force-stop the app mid-session.
3. Reopen it.

- [ ] It opens straight back into the session, not the home screen.
- [ ] The completed set is still marked done.

## 3. Backup export and import

1. **Settings → Export backup.** Choose Downloads.
   - [ ] The file picker appears (this is the Storage Access Framework — `<a download>` does
         nothing in a WebView, so its absence means the bridge is broken).
   - [ ] `workoutmate-backup-YYYY-MM-DD.json` exists in Downloads and is not empty.
2. Clear the app's data from Android Settings, reopen, then **Settings → Import backup**,
   pick that file, choose **Replace everything**.
   - [ ] Sessions, sets, routines and measurements all return.

## 4. Merge does not resurrect deleted records

1. Export a backup.
2. Delete a routine, then log a new session.
3. Import the backup with **Merge**.

- [ ] The deleted routine stays deleted.
- [ ] The session logged after the export is still there.
- [ ] The import summary matches what actually changed.

## 5. Rest timer with the screen off

1. Start a workout, set rest to about 30 seconds, complete a set.
2. Lock the screen and wait.

- [ ] A notification shows the countdown.
- [ ] A tone and vibration fire when it reaches zero.
- [ ] Tapping the notification returns to the session.

*Known limitation:* the timer is tied to the Activity, so if Android kills the process
mid-rest the alert is lost. Note it if it happens often — that's the trigger for moving to
`AlarmManager`.

## 6. Personal records

1. Log a set heavier than anything previously recorded for that exercise.
2. Finish the workout.

- [ ] A "New PR" message names the right exercise.
- [ ] Reopening the finished session shows a **PR** badge on that set.
- [ ] Repeating the *same* weight later does **not** award another PR (ties don't count).

## 7. Build me a routine — copy/paste path

With no API key saved:

1. **Routines → Build me a routine**, answer the questions, tap **Copy the prompt instead**.
2. Paste it into Claude, then paste the JSON reply back and tap **Check and preview**.

- [ ] The preview lists the days and says how many new exercises will be created.
- [ ] Nothing is written until **Import this programme** is tapped.
- [ ] After importing, the routines appear and start correctly.

## 8. Malformed input is refused

1. Paste obvious rubbish (`{"nope": true}`) into the reply box and tap **Check and preview**.

- [ ] It reports specific validation errors.
- [ ] It says nothing was saved — and nothing was.
- [ ] Importing a non-WorkOutMate JSON file as a backup is refused the same way.

## 9. Build me a routine — API path

With a key saved in **Settings**:

- [ ] **Generate with Claude** returns a programme.
- [ ] Turning the network off produces a readable error, not a hang or a crash.
- [ ] After removing the key, the button is disabled and the copy/paste path still works.

## 10. General

- [ ] The back button closes dialogs and steps back through screens before exiting.
- [ ] Rotating the device mid-session keeps the entered values.
- [ ] Switching units in Settings changes displayed weights without altering logged numbers
      (100kg reads as 220.5lb, and switching back reads 100kg again).
- [ ] The screen stays awake during a workout.
