/**
 * Validation for the routine plan produced by "Build me a routine".
 *
 * Two paths feed into this and only one of them is constrained by the API's
 * structured-output schema: when the user pastes JSON they generated in Claude
 * themselves, this is the only thing standing between a malformed plan and the
 * database. So it re-checks shape as well as ranges, and imports are all or
 * nothing — a plan with one bad exercise is rejected whole rather than
 * half-written.
 *
 * DOM-free and dependency-free so `node --test` can import it directly.
 */

export const PLAN_SCHEMA = 'workoutmate.routine-plan.v1';

export const LIMITS = {
  routines: { min: 1, max: 14 },
  blocksPerRoutine: { min: 1, max: 30 },
  itemsPerBlock: { min: 1, max: 6 },
  targetSets: { min: 1, max: 20 },
  reps: { min: 1, max: 100 },
  restSec: { min: 0, max: 900 },
  rpe: { min: 1, max: 10 },
  nameLength: 120,
  notesLength: 2000,
};

const BLOCK_TYPES = new Set(['single', 'superset']);

function isPlainObject(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function cleanString(value, maxLength) {
  if (typeof value !== 'string') return '';
  return value.trim().slice(0, maxLength);
}

function checkInt(value, { min, max }, path, errors, { required = true, fallback = null } = {}) {
  if (value === null || value === undefined || value === '') {
    if (required) errors.push(`${path} is required`);
    return fallback;
  }
  const n = Number(value);
  if (!Number.isFinite(n)) {
    errors.push(`${path} must be a number, got ${JSON.stringify(value)}`);
    return fallback;
  }
  const rounded = Math.round(n);
  if (rounded < min || rounded > max) {
    errors.push(`${path} must be between ${min} and ${max}, got ${rounded}`);
    return fallback;
  }
  return rounded;
}

/**
 * @returns {{ok: boolean, errors: string[], plan: Object|null}}
 *   `plan` is a normalised copy — trimmed strings, numbers coerced, defaults
 *   filled in — and is only non-null when `ok` is true.
 */
export function validatePlan(input) {
  const errors = [];

  let raw = input;
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(stripCodeFence(raw));
    } catch (e) {
      return { ok: false, errors: [`That isn't valid JSON: ${e.message}`], plan: null };
    }
  }

  if (!isPlainObject(raw)) {
    return { ok: false, errors: ['Expected a JSON object at the top level.'], plan: null };
  }

  if (raw.schema !== PLAN_SCHEMA) {
    errors.push(`Expected "schema" to be "${PLAN_SCHEMA}", got ${JSON.stringify(raw.schema)}`);
  }

  const plan = {
    schema: PLAN_SCHEMA,
    programName: cleanString(raw.programName, LIMITS.nameLength) || 'Generated program',
    summary: cleanString(raw.summary, LIMITS.notesLength),
    progressionNotes: cleanString(raw.progressionNotes, LIMITS.notesLength),
    routines: [],
  };

  if (!Array.isArray(raw.routines)) {
    errors.push('"routines" must be an array.');
    return { ok: false, errors, plan: null };
  }
  if (raw.routines.length < LIMITS.routines.min || raw.routines.length > LIMITS.routines.max) {
    errors.push(
      `"routines" must contain between ${LIMITS.routines.min} and ${LIMITS.routines.max} entries, got ${raw.routines.length}`
    );
  }

  raw.routines.forEach((rawRoutine, ri) => {
    const at = `routines[${ri}]`;
    if (!isPlainObject(rawRoutine)) {
      errors.push(`${at} must be an object.`);
      return;
    }

    const routine = {
      name: cleanString(rawRoutine.name, LIMITS.nameLength) || `Day ${ri + 1}`,
      notes: cleanString(rawRoutine.notes, LIMITS.notesLength),
      blocks: [],
    };

    if (!Array.isArray(rawRoutine.blocks)) {
      errors.push(`${at}.blocks must be an array.`);
      return;
    }
    if (
      rawRoutine.blocks.length < LIMITS.blocksPerRoutine.min ||
      rawRoutine.blocks.length > LIMITS.blocksPerRoutine.max
    ) {
      errors.push(
        `${at}.blocks must contain between ${LIMITS.blocksPerRoutine.min} and ${LIMITS.blocksPerRoutine.max} entries, got ${rawRoutine.blocks.length}`
      );
    }

    rawRoutine.blocks.forEach((rawBlock, bi) => {
      const blockAt = `${at}.blocks[${bi}]`;
      if (!isPlainObject(rawBlock)) {
        errors.push(`${blockAt} must be an object.`);
        return;
      }

      const type = typeof rawBlock.type === 'string' ? rawBlock.type.trim() : '';
      if (!BLOCK_TYPES.has(type)) {
        errors.push(`${blockAt}.type must be "single" or "superset", got ${JSON.stringify(rawBlock.type)}`);
      }

      const block = {
        type: BLOCK_TYPES.has(type) ? type : 'single',
        restSec: checkInt(rawBlock.restSec, LIMITS.restSec, `${blockAt}.restSec`, errors, {
          required: false,
          fallback: 120,
        }) ?? 120,
        items: [],
      };

      if (!Array.isArray(rawBlock.items)) {
        errors.push(`${blockAt}.items must be an array.`);
        return;
      }
      if (
        rawBlock.items.length < LIMITS.itemsPerBlock.min ||
        rawBlock.items.length > LIMITS.itemsPerBlock.max
      ) {
        errors.push(
          `${blockAt}.items must contain between ${LIMITS.itemsPerBlock.min} and ${LIMITS.itemsPerBlock.max} entries, got ${rawBlock.items.length}`
        );
      }
      // A single-exercise block with several items is a superset that was
      // mislabelled; trust the item count over the label rather than rejecting.
      if (block.type === 'single' && rawBlock.items.length > 1) {
        block.type = 'superset';
      }

      rawBlock.items.forEach((rawItem, ii) => {
        const itemAt = `${blockAt}.items[${ii}]`;
        if (!isPlainObject(rawItem)) {
          errors.push(`${itemAt} must be an object.`);
          return;
        }

        const exerciseName = cleanString(rawItem.exerciseName, LIMITS.nameLength);
        if (!exerciseName) {
          errors.push(`${itemAt}.exerciseName is required.`);
        }

        const repsMin = checkInt(rawItem.targetRepsMin, LIMITS.reps, `${itemAt}.targetRepsMin`, errors);
        const repsMaxRaw = rawItem.targetRepsMax ?? rawItem.targetRepsMin;
        const repsMax = checkInt(repsMaxRaw, LIMITS.reps, `${itemAt}.targetRepsMax`, errors);
        if (repsMin !== null && repsMax !== null && repsMax < repsMin) {
          errors.push(`${itemAt}.targetRepsMax (${repsMax}) is less than targetRepsMin (${repsMin})`);
        }

        block.items.push({
          exerciseName,
          muscleGroups: normaliseGroups(rawItem.muscleGroups),
          equipment: cleanString(rawItem.equipment, 40).toLowerCase() || 'other',
          targetSets: checkInt(rawItem.targetSets, LIMITS.targetSets, `${itemAt}.targetSets`, errors),
          targetRepsMin: repsMin,
          targetRepsMax: repsMax,
          targetRpe: checkInt(rawItem.targetRpe, LIMITS.rpe, `${itemAt}.targetRpe`, errors, {
            required: false,
            fallback: null,
          }),
          notes: cleanString(rawItem.notes, LIMITS.notesLength),
        });
      });

      routine.blocks.push(block);
    });

    plan.routines.push(routine);
  });

  return errors.length ? { ok: false, errors, plan: null } : { ok: true, errors: [], plan };
}

function normaliseGroups(value) {
  if (!Array.isArray(value)) return [];
  const seen = new Set();
  for (const entry of value) {
    const group = cleanString(entry, 40).toLowerCase();
    if (group) seen.add(group);
  }
  return [...seen];
}

/**
 * Models often wrap JSON in a ```json fence even when asked not to, and users
 * pasting from a chat window bring the fence along. Strip it rather than
 * making someone hand-edit a response that is otherwise perfectly good.
 */
export function stripCodeFence(text) {
  const trimmed = String(text || '').trim();
  const fence = /^```(?:json|JSON)?\s*\n([\s\S]*?)\n?```$/;
  const match = trimmed.match(fence);
  if (match) return match[1].trim();

  // Some replies add a sentence before or after the object. Fall back to the
  // outermost brace pair.
  const first = trimmed.indexOf('{');
  const last = trimmed.lastIndexOf('}');
  if (first > 0 && last > first) return trimmed.slice(first, last + 1);
  return trimmed;
}

/** Case- and punctuation-insensitive key for matching exercise names. */
export function normaliseExerciseName(name) {
  return String(name || '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}
