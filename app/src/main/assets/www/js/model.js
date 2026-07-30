/**
 * Record factories and identity.
 *
 * Every record carries `updatedAt` and a nullable `deletedAt`. Deletes are
 * soft, which is what lets a merge-import distinguish "this record is new to
 * me" from "I deleted this deliberately" — a hard delete would be silently
 * undone by restoring any older backup.
 */

export function now() {
  return Date.now();
}

/**
 * `crypto.randomUUID` needs a secure origin, which the WebView's virtual https
 * origin provides. The fallback covers older WebView builds where it is
 * missing; it is a standard v4 layout built from `getRandomValues`.
 */
export function newId() {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  if (c && typeof c.getRandomValues === 'function') {
    const bytes = c.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
    bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10
    const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  // Last resort: still unique enough for a single-device app.
  return `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function base(overrides = {}) {
  const stamp = now();
  return { id: newId(), createdAt: stamp, updatedAt: stamp, deletedAt: null, ...overrides };
}

export function makeExercise(fields = {}) {
  return base({
    name: '',
    muscleGroups: [],
    equipment: 'other',
    isCustom: true,
    notes: '',
    ...fields,
  });
}

export function makeRoutine(fields = {}) {
  return base({
    name: '',
    notes: '',
    folder: '',
    archived: false,
    blocks: [],
    ...fields,
  });
}

export function makeBlock(fields = {}) {
  return {
    blockId: newId(),
    type: 'single',
    restSec: 120,
    items: [],
    ...fields,
  };
}

export function makeBlockItem(fields = {}) {
  return {
    exerciseId: '',
    targetSets: 3,
    targetRepsMin: 8,
    targetRepsMax: 12,
    targetWeight: null,
    targetRpe: null,
    notes: '',
    ...fields,
  };
}

export function makeSession(fields = {}) {
  return base({
    routineId: null,
    routineName: 'Workout',
    startedAt: now(),
    finishedAt: null,
    notes: '',
    bodyweightAtSession: null,
    ...fields,
  });
}

export function makeSet(fields = {}) {
  return base({
    sessionId: '',
    exerciseId: '',
    blockId: '',
    order: 0,
    kind: 'working', // working | warmup | drop | failure
    weight: null,
    unit: 'kg',
    reps: null,
    rpe: null,
    completed: false,
    isPr: null,
    notes: '',
    performedAt: now(),
    ...fields,
  });
}

export function makeMeasurement(fields = {}) {
  return base({
    type: 'bodyweight',
    value: null,
    unit: 'kg',
    measuredAt: now(),
    notes: '',
    ...fields,
  });
}

/** Marks a record edited. Every write should go through this. */
export function touch(record, changes = {}) {
  return { ...record, ...changes, updatedAt: now() };
}

/** Soft delete — the record stays so merges can see the deletion happened. */
export function softDelete(record) {
  const stamp = now();
  return { ...record, deletedAt: stamp, updatedAt: stamp };
}

export const MUSCLE_GROUPS = [
  'chest', 'back', 'shoulders', 'biceps', 'triceps', 'forearms',
  'quads', 'hamstrings', 'glutes', 'calves', 'core', 'cardio', 'other',
];

export const EQUIPMENT = [
  'barbell', 'dumbbell', 'machine', 'cable', 'bodyweight', 'kettlebell', 'band', 'other',
];

export const SET_KINDS = [
  { value: 'working', label: 'Working' },
  { value: 'warmup', label: 'Warm-up' },
  { value: 'drop', label: 'Drop set' },
  { value: 'failure', label: 'To failure' },
];

export const MEASUREMENT_TYPES = [
  { value: 'bodyweight', label: 'Bodyweight', unit: 'kg' },
  { value: 'waist', label: 'Waist', unit: 'cm' },
  { value: 'chest', label: 'Chest', unit: 'cm' },
  { value: 'hips', label: 'Hips', unit: 'cm' },
  { value: 'thigh', label: 'Thigh', unit: 'cm' },
  { value: 'arm', label: 'Arm', unit: 'cm' },
  { value: 'bodyfat', label: 'Body fat', unit: '%' },
];
