/**
 * The core loop: start a session from a routine, log what you actually did,
 * finish it.
 */

import {
  activeSession,
  bridge,
  db,
  exerciseName,
  liveExercises,
  liveRoutines,
  navigate,
  reload,
  render,
  replace,
  store,
  units,
} from '../app.js';

import {
  makeSession,
  makeSet,
  softDelete,
  touch,
  SET_KINDS,
} from '../model.js';

import {
  detectPrs,
  fromKg,
  lastPerformance,
  sessionVolume,
  toKg,
} from '../stats.js';

import {
  button,
  confirmDialog,
  el,
  emptyState,
  formatClock,
  formatDate,
  formatDuration,
  formatNumber,
  formatRepRange,
  formatWeight,
  section,
  select,
  toast,
} from './dom.js';

// ---- Home ----------------------------------------------------------------

export function renderHome(view) {
  const active = activeSession();

  if (active) {
    view.appendChild(
      section('Workout in progress', [
        el('p', { text: `${active.routineName} — started ${formatDate(active.startedAt)}` }),
        el('div', { class: 'row-actions' }, [
          button('Resume', () => navigate('session', { sessionId: active.id }), { variant: 'btn-primary' }),
        ]),
      ])
    );
  }

  const routines = liveRoutines();
  view.appendChild(
    section(
      'Start a workout',
      routines.length
        ? el('ul', { class: 'list' }, routines.map((routine) =>
            el('li', { class: 'list-row' }, [
              el('div', { class: 'list-main' }, [
                el('strong', { text: routine.name }),
                el('span', { class: 'muted', text: describeRoutine(routine) }),
              ]),
              button('Start', () => startSession(routine), { variant: 'btn-primary btn-small' }),
            ])
          ))
        : emptyState(
            'No routines yet. Build one yourself, or have Claude write one for you.',
            el('div', { class: 'row-actions' }, [
              button('New routine', () => navigate('routineEdit', {}), { variant: 'btn-primary' }),
              button('Build me a routine', () => navigate('builder', {})),
            ])
          ),
      button('Empty workout', () => startSession(null))
    )
  );

  const recent = store.sessions
    .filter((s) => !s.deletedAt && s.finishedAt)
    .sort((a, b) => b.startedAt - a.startedAt)
    .slice(0, 10);

  view.appendChild(
    section(
      'Recent sessions',
      recent.length
        ? el('ul', { class: 'list' }, recent.map((session) => {
            const sets = store.sets.filter((s) => s.sessionId === session.id && !s.deletedAt);
            return el('li', { class: 'list-row' }, [
              el('div', { class: 'list-main' }, [
                el('strong', { text: session.routineName }),
                el('span', {
                  class: 'muted',
                  text: `${formatDate(session.startedAt)} · ${formatDuration(session.finishedAt - session.startedAt)} · ${formatNumber(displayVolume(sets))} ${units()} volume`,
                }),
              ]),
              button('View', () => navigate('session', { sessionId: session.id }), { variant: 'btn-small' }),
            ]);
          }))
        : emptyState('Nothing logged yet. Your finished sessions will appear here.')
    )
  );
}

function describeRoutine(routine) {
  const exercises = routine.blocks.reduce((n, block) => n + block.items.length, 0);
  const supersets = routine.blocks.filter((b) => b.type === 'superset').length;
  const parts = [`${exercises} exercise${exercises === 1 ? '' : 's'}`];
  if (supersets) parts.push(`${supersets} superset${supersets === 1 ? '' : 's'}`);
  return parts.join(' · ');
}

function displayVolume(sets) {
  return fromKg(sessionVolume(sets), units());
}

// ---- Starting a session --------------------------------------------------

async function startSession(routine) {
  if (activeSession()) {
    toast('Finish the workout in progress first.', { tone: 'error' });
    return;
  }

  const session = makeSession({
    routineId: routine ? routine.id : null,
    routineName: routine ? routine.name : 'Empty workout',
  });

  // Pre-create the planned sets so the session has structure immediately and
  // survives the process being killed mid-workout.
  const sets = [];
  let order = 0;
  if (routine) {
    for (const block of routine.blocks) {
      for (const item of block.items) {
        for (let i = 0; i < (item.targetSets || 1); i += 1) {
          sets.push(
            makeSet({
              sessionId: session.id,
              exerciseId: item.exerciseId,
              blockId: block.blockId,
              order: (order += 1),
              unit: units(),
              targetRepsMin: item.targetRepsMin,
              targetRepsMax: item.targetRepsMax,
              targetRpe: item.targetRpe,
            })
          );
        }
      }
    }
  }

  await db.putMany({ sessions: [session], sets });
  await reload(['sessions', 'sets']);
  bridge.keepScreenOn(true);
  navigate('session', { sessionId: session.id });
}

// ---- Session logger ------------------------------------------------------

let restRemaining = 0;
let restUnsubscribe = null;

export function renderSession(view, { sessionId }) {
  const session = store.sessions.find((s) => s.id === sessionId);
  if (!session) {
    view.appendChild(emptyState('That session no longer exists.'));
    return;
  }

  const readOnly = !!session.finishedAt;
  const sets = store.sets
    .filter((s) => s.sessionId === sessionId && !s.deletedAt)
    .sort((a, b) => a.order - b.order);

  view.appendChild(renderSessionHeader(session, sets, readOnly));

  if (!readOnly) view.appendChild(renderRestBar());

  const blocks = groupIntoBlocks(sets, session);
  if (!blocks.length) {
    view.appendChild(
      emptyState(
        'No exercises in this session yet.',
        readOnly ? null : button('Add an exercise', () => addExercise(session), { variant: 'btn-primary' })
      )
    );
  } else {
    for (const block of blocks) view.appendChild(renderBlock(session, block, readOnly));
  }

  if (!readOnly) {
    view.appendChild(
      el('div', { class: 'row-actions row-actions-stack' }, [
        button('Add an exercise', () => addExercise(session)),
        button('Finish workout', () => finishSession(session), { variant: 'btn-primary' }),
        button('Discard workout', () => discardSession(session), { variant: 'btn-danger btn-ghost' }),
      ])
    );
  }

  // The rest bar is driven by Kotlin ticks; re-subscribing per render keeps a
  // single live listener no matter how often the screen redraws.
  if (restUnsubscribe) restUnsubscribe();
  if (!readOnly) {
    const offTick = bridge.on('rest-tick', ({ remainingSec }) => {
      restRemaining = remainingSec;
      updateRestBar();
    });
    const offDone = bridge.on('rest-done', () => {
      restRemaining = 0;
      updateRestBar();
      toast('Rest over — next set.');
    });
    const offCancel = bridge.on('rest-cancelled', () => {
      restRemaining = 0;
      updateRestBar();
    });
    restUnsubscribe = () => {
      offTick();
      offDone();
      offCancel();
    };
  } else {
    restUnsubscribe = null;
  }

  // The bar is rebuilt on every render, so paint the live countdown into the
  // fresh node rather than waiting up to a second for the next tick.
  updateRestBar();
}

function renderSessionHeader(session, sets, readOnly) {
  const completed = sets.filter((s) => s.completed).length;
  const volume = displayVolume(sets);

  return section(session.routineName, [
    el('div', { class: 'stat-row' }, [
      stat('Sets done', `${completed}/${sets.length}`),
      stat('Volume', `${formatNumber(volume)} ${units()}`),
      stat(
        readOnly ? 'Duration' : 'Started',
        readOnly ? formatDuration(session.finishedAt - session.startedAt) : formatDate(session.startedAt)
      ),
    ]),
    session.notes ? el('p', { class: 'muted', text: session.notes }) : null,
  ]);
}

function stat(label, value) {
  return el('div', { class: 'stat' }, [
    el('span', { class: 'stat-value', text: value }),
    el('span', { class: 'stat-label', text: label }),
  ]);
}

function renderRestBar() {
  return el('div', { id: 'rest-bar', class: 'rest-bar rest-bar-idle' }, [
    el('span', { id: 'rest-text', text: 'Rest timer idle' }),
    el('div', { class: 'row-actions' }, [
      button('+30s', () => bridge.startRest(Math.max(0, restRemaining) + 30), { variant: 'btn-small' }),
      button('Skip', () => bridge.cancelRest(), { variant: 'btn-small btn-ghost' }),
    ]),
  ]);
}

function updateRestBar() {
  const bar = document.getElementById('rest-bar');
  const text = document.getElementById('rest-text');
  if (!bar || !text) return;
  if (restRemaining > 0) {
    bar.className = 'rest-bar rest-bar-active';
    text.textContent = `Resting — ${formatClock(restRemaining)}`;
  } else {
    bar.className = 'rest-bar rest-bar-idle';
    text.textContent = 'Rest timer idle';
  }
}

/** Regroups the flat set list back into the blocks the routine described. */
function groupIntoBlocks(sets, session) {
  const routine = store.routines.find((r) => r.id === session.routineId);
  const byBlock = new Map();

  for (const set of sets) {
    const key = set.blockId || `solo-${set.exerciseId}`;
    if (!byBlock.has(key)) byBlock.set(key, { blockId: key, exercises: new Map(), restSec: null, type: 'single' });
    const block = byBlock.get(key);
    if (!block.exercises.has(set.exerciseId)) block.exercises.set(set.exerciseId, []);
    block.exercises.get(set.exerciseId).push(set);
  }

  for (const block of byBlock.values()) {
    const source = routine && routine.blocks.find((b) => b.blockId === block.blockId);
    block.restSec = source ? source.restSec : store.settings.restDefaultSec;
    block.type = block.exercises.size > 1 ? 'superset' : 'single';
    block.plan = source || null;
  }

  return [...byBlock.values()];
}

function renderBlock(session, block, readOnly) {
  const isSuperset = block.type === 'superset';
  const children = [];

  if (isSuperset) {
    children.push(el('p', { class: 'superset-tag', text: 'Superset — alternate between these' }));
  }

  for (const [exerciseId, sets] of block.exercises) {
    children.push(renderExerciseGroup(session, block, exerciseId, sets, readOnly));
  }

  if (!readOnly) {
    children.push(
      el('div', { class: 'row-actions' }, [
        button('Rest now', () => bridge.startRest(block.restSec || store.settings.restDefaultSec), {
          variant: 'btn-small btn-ghost',
        }),
      ])
    );
  }

  return el('section', { class: `card block${isSuperset ? ' block-superset' : ''}` }, children);
}

function renderExerciseGroup(session, block, exerciseId, sets, readOnly) {
  const planItem = block.plan && block.plan.items.find((i) => i.exerciseId === exerciseId);
  const previous = lastPerformance(store.sets, exerciseId, session.id);

  const head = el('div', { class: 'exercise-head' }, [
    el('h3', { text: exerciseName(exerciseId) }),
    planItem
      ? el('span', {
          class: 'muted',
          text: `Target ${planItem.targetSets} × ${formatRepRange(planItem)}${planItem.targetRpe ? ` @ RPE ${planItem.targetRpe}` : ''}`,
        })
      : null,
    previous
      ? el('span', {
          class: 'muted previous',
          text: `Last time: ${previous.sets
            .map((s) => `${formatWeight(s.weight, units())} × ${s.reps}`)
            .join(', ')}`,
        })
      : el('span', { class: 'muted previous', text: 'First time logging this' }),
  ]);

  const rows = sets.map((set, index) => renderSetRow(session, block, set, index, readOnly));

  const actions = readOnly
    ? null
    : el('div', { class: 'row-actions' }, [
        button('Add set', () => addSet(session, block, exerciseId, sets), { variant: 'btn-small' }),
      ]);

  return el('div', { class: 'exercise-group' }, [head, el('div', { class: 'set-list' }, rows), actions]);
}

function renderSetRow(session, block, set, index, readOnly) {
  const unit = units();

  if (readOnly) {
    return el('div', { class: `set-row${set.completed ? '' : ' set-row-skipped'}` }, [
      el('span', { class: 'set-index', text: String(index + 1) }),
      el('span', { class: 'set-kind-tag', text: kindLabel(set.kind) }),
      el('span', { text: `${formatWeight(set.weight, unit)} × ${set.reps ?? '—'}` }),
      set.rpe ? el('span', { class: 'muted', text: `RPE ${set.rpe}` }) : null,
      set.isPr ? el('span', { class: 'pr-badge', text: 'PR' }) : null,
    ]);
  }

  const weightInput = el('input', {
    class: 'input input-num',
    type: 'number',
    inputmode: 'decimal',
    step: '0.5',
    min: '0',
    placeholder: unit,
    value: set.weight === null || set.weight === undefined ? '' : String(Number(fromKg(set.weight, unit).toFixed(2))),
    'aria-label': `Weight for set ${index + 1}`,
    onChange: (e) => updateSet(set, { weight: parseWeight(e.target.value, unit) }),
  });

  const repsInput = el('input', {
    class: 'input input-num',
    type: 'number',
    inputmode: 'numeric',
    step: '1',
    min: '0',
    placeholder: 'reps',
    value: set.reps ?? '',
    'aria-label': `Reps for set ${index + 1}`,
    onChange: (e) => updateSet(set, { reps: parseInteger(e.target.value) }),
  });

  const rpeInput = el('input', {
    class: 'input input-num input-narrow',
    type: 'number',
    inputmode: 'numeric',
    step: '0.5',
    min: '1',
    max: '10',
    placeholder: 'RPE',
    value: set.rpe ?? '',
    'aria-label': `RPE for set ${index + 1}`,
    onChange: (e) => updateSet(set, { rpe: parseNumber(e.target.value) }),
  });

  const kindSelect = select(
    SET_KINDS.map((k) => ({ value: k.value, label: k.label })),
    set.kind,
    (e) => updateSet(set, { kind: e.target.value })
  );
  kindSelect.classList.add('input-kind');
  kindSelect.setAttribute('aria-label', `Set type for set ${index + 1}`);

  const done = el('input', {
    type: 'checkbox',
    class: 'set-done',
    checked: set.completed,
    'aria-label': `Mark set ${index + 1} complete`,
    onChange: (e) => completeSet(set, block, e.target.checked),
  });

  return el('div', { class: `set-row${set.completed ? ' set-row-done' : ''}` }, [
    el('span', { class: 'set-index', text: String(index + 1) }),
    kindSelect,
    weightInput,
    repsInput,
    rpeInput,
    done,
    button('×', () => removeSet(set), { variant: 'btn-small btn-ghost btn-icon' }),
  ]);
}

function kindLabel(kind) {
  const match = SET_KINDS.find((k) => k.value === kind);
  return match ? match.label : kind;
}

function parseWeight(value, unit) {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0 || value === '') return null;
  return toKg(n, unit);
}

function parseInteger(value) {
  const n = Math.trunc(Number(value));
  return Number.isFinite(n) && n >= 0 && value !== '' ? n : null;
}

function parseNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) && value !== '' ? n : null;
}

/**
 * Set edits are applied to the in-memory record immediately and persisted
 * behind a per-record queue.
 *
 * Doing a read-modify-write straight against the database loses data here:
 * typing a weight and a rep count in quick succession starts two overlapping
 * read-modify-write cycles, and the second one — having read the record before
 * the first one landed — writes back a copy that still has the old value. In
 * practice one of the two fields silently reverts to null.
 *
 * Updating the store object synchronously closes that gap: every later read,
 * including the next edit and the finish handler, sees the new value straight
 * away. Chaining the writes per record keeps them in order on disk.
 */
const pendingSetWrites = new Map();

function writeSet(id, changes) {
  const current = store.sets.find((s) => s.id === id);
  if (!current) return Promise.resolve();

  Object.assign(current, changes, { updatedAt: Date.now() });
  const snapshot = { ...current };

  const previous = pendingSetWrites.get(id) || Promise.resolve();
  const next = previous
    .then(() => db.put('sets', snapshot))
    .catch((error) => {
      console.error('failed to save set', error);
      toast('That change could not be saved.', { tone: 'error' });
    });
  pendingSetWrites.set(id, next);
  return next;
}

/** Lets callers that reload from disk wait for in-flight edits to land. */
function flushSetWrites() {
  return Promise.all([...pendingSetWrites.values()]);
}

function updateSet(set, changes) {
  writeSet(set.id, changes);
  // Deliberately no re-render: the inputs already show what the user typed,
  // and redrawing would steal focus mid-entry.
}

async function completeSet(set, block, completed) {
  const current = store.sets.find((s) => s.id === set.id);
  if (!current) return;

  writeSet(set.id, {
    completed,
    performedAt: completed ? Date.now() : current.performedAt,
  });

  if (completed) {
    const rest = (block && block.restSec) || store.settings.restDefaultSec;
    if (rest > 0) bridge.startRest(rest);
  }
  render();
}

function removeSet(set) {
  const stamp = Date.now();
  writeSet(set.id, { deletedAt: stamp });
  render();
}

async function addSet(session, block, exerciseId, existing) {
  const template = existing[existing.length - 1];
  const maxOrder = Math.max(0, ...store.sets.filter((s) => s.sessionId === session.id).map((s) => s.order));
  const set = makeSet({
    sessionId: session.id,
    exerciseId,
    blockId: block.blockId.startsWith('solo-') ? '' : block.blockId,
    order: maxOrder + 1,
    unit: units(),
    // Carry the previous set's load forward — most people repeat it.
    weight: template ? template.weight : null,
    reps: template ? template.reps : null,
  });
  await flushSetWrites();
  await db.put('sets', set);
  await reload(['sets']);
  render();
}

async function addExercise(session) {
  const exercises = liveExercises();
  if (!exercises.length) {
    toast('The exercise library is empty.', { tone: 'error' });
    return;
  }

  const picker = select(
    exercises.map((e) => ({ value: e.id, label: e.name })),
    exercises[0].id,
    () => {}
  );

  const chosen = await new Promise((resolve) => {
    const close = (value) => {
      overlay.remove();
      resolve(value);
    };
    const overlay = el('div', { class: 'overlay', onClick: (e) => { if (e.target === overlay) close(null); } }, [
      el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' }, [
        el('h2', { text: 'Add an exercise' }),
        picker,
        el('div', { class: 'modal-actions' }, [
          button('Cancel', () => close(null)),
          button('Add', () => close(picker.value), { variant: 'btn-primary' }),
        ]),
      ]),
    ]);
    document.body.appendChild(overlay);
  });

  if (!chosen) return;

  const maxOrder = Math.max(0, ...store.sets.filter((s) => s.sessionId === session.id).map((s) => s.order));
  const sets = [1, 2, 3].map((i) =>
    makeSet({
      sessionId: session.id,
      exerciseId: chosen,
      blockId: '',
      order: maxOrder + i,
      unit: units(),
    })
  );
  await flushSetWrites();
  await db.putAll('sets', sets);
  await reload(['sets']);
  render();
}

// ---- Finishing -----------------------------------------------------------

async function finishSession(session) {
  // Make sure the last thing the user typed has landed before it is scored.
  await flushSetWrites();

  const sets = store.sets.filter((s) => s.sessionId === session.id && !s.deletedAt);
  const completed = sets.filter((s) => s.completed);

  if (!completed.length) {
    const discard = await confirmDialog({
      title: 'Nothing logged',
      message: 'No sets were marked complete. Discard this workout?',
      confirmLabel: 'Discard',
      danger: true,
    });
    if (discard) await discardSession(session, { skipConfirm: true });
    return;
  }

  // Placeholder sets that were never filled in are soft-deleted rather than
  // dropped, so a later merge doesn't reinstate them from an older backup.
  const abandoned = sets.filter((s) => !s.completed).map(softDelete);

  const bests = new Map(store.prs.filter((p) => !p.deletedAt).map((p) => [p.id, p]));
  const records = detectPrs(completed, bests);
  const prIds = new Set(records.map((r) => r.setId));

  const markedSets = completed.map((set) =>
    prIds.has(set.id) ? touch(set, { isPr: true }) : set
  );

  const finished = touch(session, { finishedAt: Date.now() });

  await db.putMany({
    sessions: [finished],
    sets: [...markedSets, ...abandoned],
    prs: records.map((r) => ({ ...r, updatedAt: Date.now(), deletedAt: null })),
  });
  await reload(['sessions', 'sets', 'prs']);

  bridge.cancelRest();
  bridge.keepScreenOn(false);
  await writeAutoSnapshot();

  if (records.length) {
    const names = [...new Set(records.map((r) => exerciseName(r.exerciseId)))];
    toast(`New PR${records.length > 1 ? 's' : ''}: ${names.join(', ')}`);
  } else {
    toast('Workout saved.');
  }

  replace('home', {});
}

async function discardSession(session, { skipConfirm = false } = {}) {
  if (!skipConfirm) {
    const confirmed = await confirmDialog({
      title: 'Discard workout?',
      message: 'Everything logged in this session will be removed. This cannot be undone.',
      confirmLabel: 'Discard',
      danger: true,
    });
    if (!confirmed) return;
  }

  await flushSetWrites();
  const sets = store.sets.filter((s) => s.sessionId === session.id).map(softDelete);
  await db.putMany({ sessions: [softDelete(session)], sets });
  await reload(['sessions', 'sets']);
  bridge.cancelRest();
  bridge.keepScreenOn(false);
  replace('home', {});
}

/**
 * Rolling local snapshot after each finished session. It survives WebView
 * storage being cleared or corrupted, though not uninstalling — which is why
 * the app still nags about real exports.
 */
async function writeAutoSnapshot() {
  try {
    const { buildBackup } = await import('../merge.js');
    const stores = await db.exportStores();
    const payload = buildBackup({
      stores,
      appVersion: bridge.appVersion(),
      exportedAt: Date.now(),
      dbVersion: db.DB_VERSION,
    });
    bridge.writeSnapshot(new Date().toISOString().replace(/[:.]/g, '-'), JSON.stringify(payload));
  } catch (error) {
    // A snapshot is a safety net, not the user's problem — never block on it.
    console.warn('snapshot failed', error);
  }
}
