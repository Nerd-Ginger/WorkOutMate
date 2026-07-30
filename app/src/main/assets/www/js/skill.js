/**
 * "Build me a routine" — the fixed question script, the prompt it renders, and
 * the import that turns Claude's answer into routines.
 *
 * The script lives in `skills/build-me-a-routine.v1.json` rather than in code
 * so it is versioned as data: the same skill version plus the same answers
 * renders a byte-identical prompt every time, whether it goes through the API
 * or gets pasted into Claude by hand. The repo's `.claude/skills/` copy points
 * at that same file so both paths stay in step.
 */

import { makeBlock, makeBlockItem, makeExercise, makeRoutine, newId } from './model.js';
import { normaliseExerciseName, validatePlan } from './validate.js';

const SKILL_URL = 'skills/build-me-a-routine.v1.json';

let cached = null;

export async function loadSkill() {
  if (cached) return cached;
  const response = await fetch(SKILL_URL);
  if (!response.ok) throw new Error(`Could not load the routine builder script (${response.status})`);
  cached = await response.json();
  return cached;
}

function labelFor(question, value) {
  const option = (question.options || []).find((o) => o.value === value);
  return option ? option.label : value;
}

/**
 * Renders the answers into the prompt body. Questions are always walked in
 * declaration order and unanswered optional questions always render the same
 * placeholder, so the output is deterministic.
 */
export function buildPrompt(skill, answers) {
  const lines = ['Build me a training programme based on these answers.', ''];

  for (const question of skill.questions) {
    const raw = answers[question.id];
    let rendered;

    if (question.type === 'multi') {
      const values = Array.isArray(raw) ? raw : [];
      rendered = values.length ? values.map((v) => labelFor(question, v)).join(', ') : 'Not specified';
    } else if (question.type === 'text') {
      rendered = String(raw || '').trim() || 'Nothing to note';
    } else {
      rendered = raw ? labelFor(question, raw) : 'Not specified';
    }

    lines.push(`${question.label} ${rendered}`);
  }

  lines.push(
    '',
    'Return a single JSON object matching the workoutmate.routine-plan.v1 schema, with no commentary.'
  );
  return lines.join('\n');
}

/** The full copy/paste text for the no-key path: instructions plus the schema. */
export function buildCopyPastePrompt(skill, answers) {
  return [
    skill.systemPrompt,
    '',
    '--- The JSON schema your reply must satisfy ---',
    JSON.stringify(skill.outputSchema, null, 2),
    '',
    '--- My answers ---',
    buildPrompt(skill, answers),
  ].join('\n');
}

/**
 * The Messages API request body. The model and API key are added on the Kotlin
 * side, so nothing secret is assembled here. `output_config.format` pins the
 * reply to the schema, which is what makes the API path far more reliable than
 * asking for JSON in prose.
 */
export function buildApiRequest(skill, answers) {
  return JSON.stringify({
    system: skill.systemPrompt,
    messages: [{ role: 'user', content: buildPrompt(skill, answers) }],
    output_config: {
      format: { type: 'json_schema', schema: skill.outputSchema },
    },
  });
}

/**
 * Turns a validated plan into records, reusing library exercises where the
 * names match and creating the rest.
 *
 * Nothing is written here — the caller shows the preview (including how many
 * exercises are about to be created) and only then commits. An import is all
 * or nothing.
 *
 * @returns {{routines: Array, newExercises: Array, matchedCount: number, plan: Object}}
 */
export function planToRecords(plan, existingExercises) {
  const byName = new Map();
  for (const exercise of existingExercises || []) {
    if (exercise.deletedAt) continue;
    byName.set(normaliseExerciseName(exercise.name), exercise);
  }

  const newExercises = [];
  let matchedCount = 0;

  const resolveExercise = (item) => {
    const key = normaliseExerciseName(item.exerciseName);
    const existing = byName.get(key);
    if (existing) {
      matchedCount += 1;
      return existing;
    }
    const created = makeExercise({
      name: item.exerciseName,
      muscleGroups: item.muscleGroups && item.muscleGroups.length ? item.muscleGroups : ['other'],
      equipment: item.equipment || 'other',
      isCustom: true,
    });
    // Register immediately so a movement repeated across days is created once.
    byName.set(key, created);
    newExercises.push(created);
    return created;
  };

  const routines = plan.routines.map((rawRoutine) =>
    makeRoutine({
      name: rawRoutine.name,
      notes: [rawRoutine.notes, plan.progressionNotes ? `Progression: ${plan.progressionNotes}` : '']
        .filter(Boolean)
        .join('\n\n'),
      folder: plan.programName || '',
      blocks: rawRoutine.blocks.map((rawBlock) =>
        makeBlock({
          blockId: newId(),
          type: rawBlock.type,
          restSec: rawBlock.restSec,
          items: rawBlock.items.map((item) =>
            makeBlockItem({
              exerciseId: resolveExercise(item).id,
              targetSets: item.targetSets,
              targetRepsMin: item.targetRepsMin,
              targetRepsMax: item.targetRepsMax,
              targetRpe: item.targetRpe,
              notes: item.notes || '',
            })
          ),
        })
      ),
    })
  );

  return { routines, newExercises, matchedCount, plan };
}

/**
 * Convenience wrapper: validate then convert.
 * @returns {{ok: boolean, errors: string[], records: Object|null}}
 */
export function parseAndPrepare(rawText, existingExercises) {
  const { ok, errors, plan } = validatePlan(rawText);
  if (!ok) return { ok: false, errors, records: null };
  return { ok: true, errors: [], records: planToRecords(plan, existingExercises) };
}

/**
 * Fed back to the model after a validation failure so it can correct itself,
 * rather than making the user hand-edit JSON.
 */
export function buildRetryRequest(skill, answers, badOutput, errors) {
  return JSON.stringify({
    system: skill.systemPrompt,
    messages: [
      { role: 'user', content: buildPrompt(skill, answers) },
      { role: 'assistant', content: badOutput },
      {
        role: 'user',
        content: [
          'That response failed validation:',
          ...errors.map((e) => `- ${e}`),
          '',
          'Return a corrected JSON object. Fix only what the errors describe; keep the rest of the programme as it was.',
        ].join('\n'),
      },
    ],
    output_config: {
      format: { type: 'json_schema', schema: skill.outputSchema },
    },
  });
}
