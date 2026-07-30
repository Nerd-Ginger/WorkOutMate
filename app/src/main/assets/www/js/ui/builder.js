/**
 * "Build me a routine".
 *
 * Two paths to the same JSON contract. With an API key saved, the request goes
 * out through Kotlin and the reply is pinned to the schema by the API's
 * structured-output support. Without one — or with no network at all — the app
 * renders the exact same prompt for the user to run in Claude themselves and
 * paste back. The no-key path is always available, so the feature never hard
 * depends on a key.
 */

import { bridge, db, reload, render, replace, store } from '../app.js';
import {
  buildApiRequest,
  buildCopyPastePrompt,
  buildRetryRequest,
  loadSkill,
  parseAndPrepare,
} from '../skill.js';
import {
  button,
  clear,
  el,
  errorList,
  field,
  formatRepRange,
  section,
  select,
  toast,
} from './dom.js';

let skill = null;
let answers = {};
let phase = 'questions';
let lastRaw = '';
let prepared = null;
let lastErrors = [];
let busy = false;

export function renderBuilder(view) {
  if (!skill) {
    view.appendChild(el('p', { class: 'empty', text: 'Loading the routine builder…' }));
    loadSkill().then(
      (loaded) => {
        skill = loaded;
        if (!Object.keys(answers).length) answers = defaultAnswers(loaded);
        render();
      },
      (error) => {
        clear(view);
        view.appendChild(
          section('Routine builder unavailable', [el('p', { text: error.message })])
        );
      }
    );
    return;
  }

  if (phase === 'preview' && prepared) {
    view.appendChild(renderPreview());
    return;
  }

  view.appendChild(renderQuestions());
  if (lastErrors.length) view.appendChild(renderErrors());
  view.appendChild(renderOutputPaste());
}

function defaultAnswers(loaded) {
  const defaults = {};
  for (const question of loaded.questions) {
    if (question.type === 'multi') defaults[question.id] = [];
    else if (question.type === 'text') defaults[question.id] = '';
    else defaults[question.id] = question.options?.[0]?.value ?? '';
  }
  // Match whatever unit the rest of the app is already using.
  if (defaults.units !== undefined) defaults.units = store.settings.units || 'kg';
  return defaults;
}

// ---- Question form -------------------------------------------------------

function renderQuestions() {
  const controls = skill.questions.map((question) => {
    if (question.type === 'text') {
      return field(
        question.label,
        el('textarea', {
          class: 'input',
          rows: 2,
          placeholder: question.placeholder || '',
          value: answers[question.id] || '',
          onInput: (e) => { answers[question.id] = e.target.value; },
        })
      );
    }

    if (question.type === 'multi') {
      const boxes = question.options.map((option) =>
        el('label', { class: 'check' }, [
          el('input', {
            type: 'checkbox',
            value: option.value,
            checked: (answers[question.id] || []).includes(option.value),
            onChange: (e) => {
              const current = new Set(answers[question.id] || []);
              if (e.target.checked) current.add(option.value);
              else current.delete(option.value);
              // Preserve the skill's declared option order so the rendered
              // prompt is identical for the same set of choices.
              answers[question.id] = question.options
                .map((o) => o.value)
                .filter((v) => current.has(v));
            },
          }),
          el('span', { text: option.label }),
        ])
      );
      return el('fieldset', { class: 'checks' }, [
        el('legend', { text: question.label }),
        ...boxes,
      ]);
    }

    return field(
      question.label,
      select(
        question.options.map((o) => ({ value: o.value, label: o.label })),
        answers[question.id],
        (e) => { answers[question.id] = e.target.value; }
      )
    );
  });

  const canCallApi = bridge.hasApiKey();

  return section('Build me a routine', [
    el('p', {
      class: 'muted',
      text: 'The same questions every time, so the same answers always produce the same request.',
    }),
    ...controls,
    el('div', { class: 'row-actions row-actions-stack' }, [
      button(
        canCallApi ? 'Generate with Claude' : 'Generate with Claude (needs an API key)',
        generateViaApi,
        { variant: 'btn-primary', disabled: !canCallApi || busy }
      ),
      button('Copy the prompt instead', copyPrompt),
      !canCallApi
        ? el('p', {
            class: 'muted',
            text: bridge.isNative
              ? 'Add a key in Settings to generate in-app, or copy the prompt, run it in Claude, and paste the reply below.'
              : 'The in-app call needs the Android app. Copy the prompt, run it in Claude, and paste the reply below.',
          })
        : null,
    ]),
  ]);
}

function missingRequired() {
  return skill.questions
    .filter((q) => q.required)
    .filter((q) => {
      const value = answers[q.id];
      return q.type === 'multi' ? !(value || []).length : !value;
    })
    .map((q) => q.label);
}

async function copyPrompt() {
  const missing = missingRequired();
  if (missing.length) {
    toast(`Answer these first: ${missing.join(', ')}`, { tone: 'error' });
    return;
  }

  const text = buildCopyPastePrompt(skill, answers);
  try {
    await navigator.clipboard.writeText(text);
    toast('Prompt copied. Paste it into Claude, then paste the reply below.');
  } catch {
    // Clipboard access can be refused; showing the text is a fine fallback.
    showPromptFallback(text);
  }
}

function showPromptFallback(text) {
  const area = el('textarea', { class: 'input', rows: 12, readonly: true, value: text });
  const overlay = el('div', { class: 'overlay', onClick: (e) => { if (e.target === overlay) overlay.remove(); } }, [
    el('div', { class: 'modal modal-wide', role: 'dialog', 'aria-modal': 'true' }, [
      el('h2', { text: 'Copy this prompt' }),
      area,
      el('div', { class: 'modal-actions' }, [button('Close', () => overlay.remove())]),
    ]),
  ]);
  document.body.appendChild(overlay);
  area.select();
}

// ---- API path ------------------------------------------------------------

async function generateViaApi() {
  const missing = missingRequired();
  if (missing.length) {
    toast(`Answer these first: ${missing.join(', ')}`, { tone: 'error' });
    return;
  }

  busy = true;
  lastErrors = [];
  render();
  toast('Asking Claude…');

  try {
    const text = await bridge.callClaude(buildApiRequest(skill, answers));
    handleResponse(text);
  } catch (error) {
    busy = false;
    lastErrors = [error.message || String(error)];
    render();
  }
}

/** Feeds the validation errors back so the model can correct its own output. */
async function retryWithErrors() {
  busy = true;
  render();
  try {
    const text = await bridge.callClaude(buildRetryRequest(skill, answers, lastRaw, lastErrors));
    handleResponse(text);
  } catch (error) {
    busy = false;
    lastErrors = [error.message || String(error)];
    render();
  }
}

function handleResponse(text) {
  busy = false;
  lastRaw = text;
  const result = parseAndPrepare(text, store.exercises);
  if (!result.ok) {
    lastErrors = result.errors;
    phase = 'questions';
    render();
    return;
  }
  lastErrors = [];
  prepared = result.records;
  phase = 'preview';
  render();
}

// ---- Paste path ----------------------------------------------------------

function renderOutputPaste() {
  const area = el('textarea', {
    class: 'input',
    rows: 6,
    placeholder: 'Paste Claude\'s JSON reply here',
    value: lastRaw,
  });

  return section('Paste a reply', [
    el('p', {
      class: 'muted',
      text: 'Ran the prompt in Claude yourself? Paste the JSON here and it will be checked before anything is imported.',
    }),
    area,
    el('div', { class: 'row-actions' }, [
      button('Check and preview', () => {
        if (!area.value.trim()) {
          toast('Paste a reply first.', { tone: 'error' });
          return;
        }
        handleResponse(area.value);
      }, { variant: 'btn-primary' }),
    ]),
  ]);
}

function renderErrors() {
  const canRetry = bridge.hasApiKey() && !!lastRaw;
  return section('That reply could not be imported', [
    el('p', { text: 'Nothing has been saved. The problems were:' }),
    errorList(lastErrors),
    el('div', { class: 'row-actions row-actions-stack' }, [
      canRetry
        ? button('Ask Claude to fix it', retryWithErrors, { variant: 'btn-primary', disabled: busy })
        : null,
      button('Start over', () => {
        lastErrors = [];
        lastRaw = '';
        render();
      }, { variant: 'btn-ghost' }),
    ]),
  ]);
}

// ---- Preview and import --------------------------------------------------

function renderPreview() {
  const { plan, routines, newExercises, matchedCount } = prepared;

  const dayList = el('ul', { class: 'list' }, routines.map((routine, index) => {
    const source = plan.routines[index];
    const lines = source.blocks.map((block) => {
      const names = block.items
        .map((item) => `${item.exerciseName} ${item.targetSets}×${formatRepRange(item)}`)
        .join('  +  ');
      return el('li', { text: block.type === 'superset' ? `Superset: ${names}` : names });
    });
    return el('li', { class: 'list-row list-row-stack' }, [
      el('strong', { text: routine.name }),
      el('ul', { class: 'sub-list' }, lines),
    ]);
  }));

  return el('div', {}, [
    section(plan.programName, [
      plan.summary ? el('p', { text: plan.summary }) : null,
      plan.progressionNotes ? el('p', { class: 'muted', text: plan.progressionNotes }) : null,
      el('p', {
        class: 'muted',
        text: `${routines.length} routine${routines.length === 1 ? '' : 's'} · ${matchedCount} exercise${matchedCount === 1 ? '' : 's'} matched to your library · ${newExercises.length} will be created`,
      }),
      newExercises.length
        ? el('details', {}, [
            el('summary', { text: `New exercises (${newExercises.length})` }),
            el('ul', { class: 'sub-list' }, newExercises.map((e) => el('li', { text: e.name }))),
          ])
        : null,
    ]),
    section('Days', dayList),
    el('div', { class: 'row-actions row-actions-stack' }, [
      button('Import this programme', importPrepared, { variant: 'btn-primary' }),
      button('Discard', () => {
        prepared = null;
        phase = 'questions';
        render();
      }, { variant: 'btn-ghost' }),
    ]),
  ]);
}

async function importPrepared() {
  const { routines, newExercises } = prepared;
  // One transaction: either the whole programme lands or none of it does.
  await db.putMany({ exercises: newExercises, routines });
  await reload(['exercises', 'routines']);

  prepared = null;
  phase = 'questions';
  lastRaw = '';
  lastErrors = [];

  toast(`Imported ${routines.length} routine${routines.length === 1 ? '' : 's'}.`);
  replace('routines', {});
}
