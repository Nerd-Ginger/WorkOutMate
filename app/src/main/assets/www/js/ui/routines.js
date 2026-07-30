/**
 * Routine list, routine builder, and the exercise library.
 *
 * A superset is not a special kind of object here — it is simply a block with
 * more than one item, which is why "add exercise to this block" is the whole
 * of the superset feature.
 */

import {
  db,
  liveExercises,
  liveRoutines,
  navigate,
  reload,
  render,
  replace,
  store,
} from '../app.js';

import {
  EQUIPMENT,
  MUSCLE_GROUPS,
  makeBlock,
  makeBlockItem,
  makeExercise,
  makeRoutine,
  softDelete,
  touch,
} from '../model.js';

import {
  button,
  confirmDialog,
  el,
  emptyState,
  field,
  section,
  select,
  toast,
} from './dom.js';

// ---- Routine list --------------------------------------------------------

export function renderRoutines(view) {
  const routines = liveRoutines();

  view.appendChild(
    section(
      'Routines',
      routines.length
        ? el('ul', { class: 'list' }, routines.map((routine) =>
            el('li', { class: 'list-row' }, [
              el('div', { class: 'list-main' }, [
                el('strong', { text: routine.name }),
                routine.folder ? el('span', { class: 'tag', text: routine.folder }) : null,
                el('span', { class: 'muted', text: summarise(routine) }),
              ]),
              el('div', { class: 'row-actions' }, [
                button('Edit', () => navigate('routineEdit', { routineId: routine.id }), { variant: 'btn-small' }),
                button('Delete', () => removeRoutine(routine), { variant: 'btn-small btn-ghost btn-danger' }),
              ]),
            ])
          ))
        : emptyState('No routines yet.'),
      null
    )
  );

  view.appendChild(
    section('Add a routine', [
      el('div', { class: 'row-actions row-actions-stack' }, [
        button('Build it myself', () => navigate('routineEdit', {}), { variant: 'btn-primary' }),
        button('Build me a routine', () => navigate('builder', {})),
        button('Exercise library', () => navigate('exercises', {})),
      ]),
    ])
  );
}

function summarise(routine) {
  const count = routine.blocks.reduce((n, b) => n + b.items.length, 0);
  const supersets = routine.blocks.filter((b) => b.type === 'superset').length;
  return [
    `${count} exercise${count === 1 ? '' : 's'}`,
    supersets ? `${supersets} superset${supersets === 1 ? '' : 's'}` : null,
  ]
    .filter(Boolean)
    .join(' · ');
}

async function removeRoutine(routine) {
  const confirmed = await confirmDialog({
    title: 'Delete routine?',
    message: `"${routine.name}" will be removed. Sessions you already logged against it are kept.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  if (!confirmed) return;
  await db.put('routines', softDelete(routine));
  await reload(['routines']);
  render();
}

// ---- Routine editor ------------------------------------------------------

let draft = null;
let draftKey = null;

export function renderRoutineEdit(view, { routineId }) {
  const key = routineId || 'new';
  if (draftKey !== key) {
    const existing = routineId && store.routines.find((r) => r.id === routineId);
    // Deep clone so an abandoned edit leaves the stored routine untouched.
    draft = existing
      ? JSON.parse(JSON.stringify(existing))
      : makeRoutine({ name: '', blocks: [makeBlock({ items: [makeBlockItem()] })] });
    draftKey = key;
  }

  const exercises = liveExercises();
  if (!exercises.length) {
    view.appendChild(emptyState('Add some exercises to the library first.',
      button('Exercise library', () => navigate('exercises', {}), { variant: 'btn-primary' })));
    return;
  }

  view.appendChild(
    section(routineId ? 'Edit routine' : 'New routine', [
      field(
        'Name',
        el('input', {
          class: 'input',
          type: 'text',
          value: draft.name,
          placeholder: 'e.g. Day A — Upper',
          onInput: (e) => { draft.name = e.target.value; },
        })
      ),
      field(
        'Notes',
        el('textarea', {
          class: 'input',
          rows: 2,
          value: draft.notes || '',
          onInput: (e) => { draft.notes = e.target.value; },
        })
      ),
    ])
  );

  draft.blocks.forEach((block, index) => {
    view.appendChild(renderBlockEditor(block, index, exercises));
  });

  view.appendChild(
    el('div', { class: 'row-actions row-actions-stack' }, [
      button('Add exercise', () => {
        draft.blocks.push(makeBlock({ items: [makeBlockItem()] }));
        render();
      }),
      button('Save routine', saveDraft, { variant: 'btn-primary' }),
      button('Cancel', () => { draft = null; draftKey = null; navigate('routines'); }, { variant: 'btn-ghost' }),
    ])
  );
}

function renderBlockEditor(block, blockIndex, exercises) {
  const isSuperset = block.items.length > 1;
  block.type = isSuperset ? 'superset' : 'single';

  const items = block.items.map((item, itemIndex) =>
    renderItemEditor(block, item, blockIndex, itemIndex, exercises)
  );

  return el('section', { class: `card block${isSuperset ? ' block-superset' : ''}` }, [
    el('header', { class: 'card-head' }, [
      el('h2', { text: isSuperset ? `Superset ${blockIndex + 1}` : `Exercise ${blockIndex + 1}` }),
      button('Remove', () => {
        draft.blocks.splice(blockIndex, 1);
        if (!draft.blocks.length) draft.blocks.push(makeBlock({ items: [makeBlockItem()] }));
        render();
      }, { variant: 'btn-small btn-ghost' }),
    ]),
    ...items,
    field(
      'Rest between sets (seconds)',
      el('input', {
        class: 'input input-num',
        type: 'number',
        min: '0',
        max: '900',
        step: '15',
        value: block.restSec,
        onChange: (e) => { block.restSec = Math.max(0, Math.min(900, Number(e.target.value) || 0)); },
      })
    ),
    el('div', { class: 'row-actions' }, [
      button('Superset another exercise', () => {
        block.items.push(makeBlockItem());
        render();
      }, { variant: 'btn-small' }),
    ]),
  ]);
}

function renderItemEditor(block, item, blockIndex, itemIndex, exercises) {
  if (!item.exerciseId) item.exerciseId = exercises[0].id;

  return el('div', { class: 'item-editor' }, [
    block.items.length > 1
      ? el('div', { class: 'item-editor-head' }, [
          el('span', { class: 'muted', text: `Movement ${itemIndex + 1}` }),
          button('Remove', () => {
            block.items.splice(itemIndex, 1);
            render();
          }, { variant: 'btn-small btn-ghost btn-icon' }),
        ])
      : null,

    field(
      'Exercise',
      select(
        exercises.map((e) => ({ value: e.id, label: e.name })),
        item.exerciseId,
        (e) => { item.exerciseId = e.target.value; }
      )
    ),

    el('div', { class: 'field-row' }, [
      field(
        'Sets',
        el('input', {
          class: 'input input-num',
          type: 'number', min: '1', max: '20', step: '1',
          value: item.targetSets,
          onChange: (e) => { item.targetSets = clampInt(e.target.value, 1, 20, 3); },
        })
      ),
      field(
        'Reps from',
        el('input', {
          class: 'input input-num',
          type: 'number', min: '1', max: '100', step: '1',
          value: item.targetRepsMin,
          onChange: (e) => { item.targetRepsMin = clampInt(e.target.value, 1, 100, 8); },
        })
      ),
      field(
        'to',
        el('input', {
          class: 'input input-num',
          type: 'number', min: '1', max: '100', step: '1',
          value: item.targetRepsMax,
          onChange: (e) => { item.targetRepsMax = clampInt(e.target.value, 1, 100, 12); },
        })
      ),
      field(
        'RPE',
        el('input', {
          class: 'input input-num input-narrow',
          type: 'number', min: '1', max: '10', step: '0.5',
          value: item.targetRpe ?? '',
          placeholder: '—',
          onChange: (e) => {
            const n = Number(e.target.value);
            item.targetRpe = e.target.value === '' || !Number.isFinite(n) ? null : Math.min(10, Math.max(1, n));
          },
        })
      ),
    ]),
  ]);
}

function clampInt(value, min, max, fallback) {
  const n = Math.trunc(Number(value));
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

async function saveDraft() {
  const name = (draft.name || '').trim();
  if (!name) {
    toast('Give the routine a name first.', { tone: 'error' });
    return;
  }
  for (const block of draft.blocks) {
    if (block.items.some((item) => !item.exerciseId)) {
      toast('Every movement needs an exercise selected.', { tone: 'error' });
      return;
    }
    for (const item of block.items) {
      if (item.targetRepsMax < item.targetRepsMin) {
        toast('A rep range runs backwards — check the "from" and "to" values.', { tone: 'error' });
        return;
      }
    }
  }

  const record = touch(draft, { name });
  await db.put('routines', record);
  await reload(['routines']);
  draft = null;
  draftKey = null;
  toast('Routine saved.');
  replace('routines', {});
}

// ---- Exercise library ----------------------------------------------------

export function renderExercises(view) {
  const exercises = liveExercises();

  view.appendChild(
    section('Add an exercise', [renderExerciseForm()])
  );

  view.appendChild(
    section(
      `Library (${exercises.length})`,
      exercises.length
        ? el('ul', { class: 'list' }, exercises.map((exercise) =>
            el('li', { class: 'list-row' }, [
              el('div', { class: 'list-main' }, [
                el('strong', { text: exercise.name }),
                el('span', {
                  class: 'muted',
                  text: [exercise.equipment, ...(exercise.muscleGroups || [])].filter(Boolean).join(' · '),
                }),
              ]),
              exercise.isCustom
                ? button('Delete', () => removeExercise(exercise), { variant: 'btn-small btn-ghost btn-danger' })
                : el('span', { class: 'tag', text: 'built in' }),
            ])
          ))
        : emptyState('The library is empty.')
    )
  );
}

function renderExerciseForm() {
  const nameInput = el('input', { class: 'input', type: 'text', placeholder: 'e.g. Landmine Press' });
  const equipmentSelect = select(EQUIPMENT.map((v) => ({ value: v, label: v })), 'barbell', () => {});
  const groupBoxes = MUSCLE_GROUPS.map((group) =>
    el('label', { class: 'check' }, [
      el('input', { type: 'checkbox', value: group }),
      el('span', { text: group }),
    ])
  );

  return el('div', {}, [
    field('Name', nameInput),
    field('Equipment', equipmentSelect),
    el('fieldset', { class: 'checks' }, [
      el('legend', { text: 'Muscle groups' }),
      ...groupBoxes,
    ]),
    el('div', { class: 'row-actions' }, [
      button('Add to library', async () => {
        const name = nameInput.value.trim();
        if (!name) {
          toast('Give the exercise a name.', { tone: 'error' });
          return;
        }
        const groups = groupBoxes
          .map((label) => label.querySelector('input'))
          .filter((input) => input.checked)
          .map((input) => input.value);

        await db.put('exercises', makeExercise({
          name,
          equipment: equipmentSelect.value,
          muscleGroups: groups.length ? groups : ['other'],
          isCustom: true,
        }));
        await reload(['exercises']);
        toast(`Added ${name}.`);
        render();
      }, { variant: 'btn-primary' }),
    ]),
  ]);
}

async function removeExercise(exercise) {
  const used = store.sets.some((s) => s.exerciseId === exercise.id && !s.deletedAt);
  const confirmed = await confirmDialog({
    title: 'Delete exercise?',
    message: used
      ? `"${exercise.name}" appears in sessions you have logged. Those stay, but it will no longer be selectable.`
      : `"${exercise.name}" will be removed from the library.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  if (!confirmed) return;
  await db.put('exercises', softDelete(exercise));
  await reload(['exercises']);
  render();
}
