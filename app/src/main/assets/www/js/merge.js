/**
 * Backup envelope validation and merge semantics for "Save progress".
 *
 * DOM-free and dependency-free so `node --test` can import it directly. The
 * merge rules are the part most worth testing: getting them wrong silently
 * resurrects deleted records or discards a session, and neither failure is
 * visible until much later.
 */

export const BACKUP_SCHEMA = 'workoutmate.backup.v1';

/** Store name -> key path. `settings` is keyed by name, everything else by id. */
export const STORE_KEYS = {
  exercises: 'id',
  routines: 'id',
  sessions: 'id',
  sets: 'id',
  measurements: 'id',
  prs: 'id',
  settings: 'key',
};

export const STORE_NAMES = Object.keys(STORE_KEYS);

function isPlainObject(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/**
 * @returns {{ok: boolean, errors: string[], backup: Object|null}}
 */
export function validateBackup(input) {
  const errors = [];

  let raw = input;
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw);
    } catch (e) {
      return { ok: false, errors: [`That file isn't valid JSON: ${e.message}`], backup: null };
    }
  }

  if (!isPlainObject(raw)) {
    return { ok: false, errors: ['Expected a JSON object at the top level.'], backup: null };
  }
  if (raw.schema !== BACKUP_SCHEMA) {
    return {
      ok: false,
      errors: [
        `This doesn't look like a WorkOutMate backup (expected "${BACKUP_SCHEMA}", got ${JSON.stringify(raw.schema)}).`,
      ],
      backup: null,
    };
  }
  if (!isPlainObject(raw.stores)) {
    return { ok: false, errors: ['"stores" is missing or is not an object.'], backup: null };
  }

  const stores = {};
  for (const name of STORE_NAMES) {
    const rows = raw.stores[name];
    if (rows === undefined) {
      // A backup written by an older version may predate a store; treat it as
      // empty rather than refusing the whole restore.
      stores[name] = [];
      continue;
    }
    if (!Array.isArray(rows)) {
      errors.push(`stores.${name} must be an array.`);
      continue;
    }
    const key = STORE_KEYS[name];
    const kept = [];
    rows.forEach((row, i) => {
      if (!isPlainObject(row)) {
        errors.push(`stores.${name}[${i}] must be an object.`);
        return;
      }
      if (typeof row[key] !== 'string' || !row[key]) {
        errors.push(`stores.${name}[${i}] is missing a "${key}".`);
        return;
      }
      kept.push(row);
    });
    stores[name] = kept;
  }

  if (errors.length) return { ok: false, errors, backup: null };

  return {
    ok: true,
    errors: [],
    backup: {
      schema: BACKUP_SCHEMA,
      appVersion: typeof raw.appVersion === 'string' ? raw.appVersion : 'unknown',
      exportedAt: Number(raw.exportedAt) || 0,
      dbVersion: Number(raw.dbVersion) || 1,
      stores,
    },
  };
}

/** Records without a timestamp sort oldest, so a timestamped row always wins. */
function stamp(row) {
  const n = Number(row && row.updatedAt);
  return Number.isFinite(n) ? n : 0;
}

/**
 * Merges one store's rows, newest `updatedAt` wins.
 *
 * A soft-deleted row is an ordinary row for merge purposes: if the incoming
 * copy is newer and carries `deletedAt`, the deletion wins. That is what stops
 * restoring an old backup from resurrecting everything you have since deleted.
 *
 * @returns {{rows: Array, added: number, updated: number, unchanged: number}}
 */
export function mergeRows(currentRows, incomingRows, keyPath = 'id') {
  const merged = new Map();
  for (const row of currentRows || []) {
    const key = row && row[keyPath];
    if (typeof key === 'string' && key) merged.set(key, row);
  }

  let added = 0;
  let updated = 0;
  let unchanged = 0;

  for (const row of incomingRows || []) {
    const key = row && row[keyPath];
    if (typeof key !== 'string' || !key) continue;

    const existing = merged.get(key);
    if (!existing) {
      merged.set(key, row);
      added += 1;
    } else if (stamp(row) > stamp(existing)) {
      merged.set(key, row);
      updated += 1;
    } else {
      unchanged += 1;
    }
  }

  return { rows: [...merged.values()], added, updated, unchanged };
}

/**
 * Merges a whole validated backup over the current database contents.
 * @returns {{stores: Object, stats: Object}}
 */
export function mergeBackup(currentStores, incomingBackup) {
  const stores = {};
  const stats = {};
  for (const name of STORE_NAMES) {
    const result = mergeRows(
      (currentStores && currentStores[name]) || [],
      (incomingBackup && incomingBackup.stores && incomingBackup.stores[name]) || [],
      STORE_KEYS[name]
    );
    stores[name] = result.rows;
    stats[name] = { added: result.added, updated: result.updated, unchanged: result.unchanged };
  }
  return { stores, stats };
}

/** Row counts per store, for the "what am I about to import" preview. */
export function summariseStores(stores) {
  const summary = {};
  let total = 0;
  for (const name of STORE_NAMES) {
    const rows = (stores && stores[name]) || [];
    const live = rows.filter((row) => !row.deletedAt).length;
    summary[name] = live;
    total += live;
  }
  summary.total = total;
  return summary;
}

export function buildBackup({ stores, appVersion, exportedAt, dbVersion }) {
  const payload = {};
  for (const name of STORE_NAMES) payload[name] = (stores && stores[name]) || [];
  return {
    schema: BACKUP_SCHEMA,
    appVersion: appVersion || 'unknown',
    exportedAt: exportedAt || 0,
    dbVersion: dbVersion || 1,
    stores: payload,
  };
}

/**
 * Flat CSV of every logged set, for people who would rather work in a
 * spreadsheet. Deliberately denormalised — one row per set with the session
 * and exercise names inlined, so it stands alone without lookups.
 */
export function setsToCsv({ sets, sessions, exercises, unit = 'kg' }) {
  const sessionById = new Map((sessions || []).map((s) => [s.id, s]));
  const exerciseById = new Map((exercises || []).map((e) => [e.id, e]));

  const header = [
    'date', 'session', 'exercise', 'set_kind', 'weight', 'unit', 'reps', 'rpe', 'completed', 'notes',
  ];

  const rows = (sets || [])
    .filter((set) => !set.deletedAt)
    .sort((a, b) => a.performedAt - b.performedAt)
    .map((set) => {
      const session = sessionById.get(set.sessionId);
      const exercise = exerciseById.get(set.exerciseId);
      return [
        new Date(set.performedAt).toISOString(),
        (session && session.routineName) || '',
        (exercise && exercise.name) || '',
        set.kind || 'working',
        set.weight ?? '',
        unit,
        set.reps ?? '',
        set.rpe ?? '',
        set.completed ? 'yes' : 'no',
        set.notes || '',
      ];
    });

  return [header, ...rows].map((row) => row.map(csvCell).join(',')).join('\n');
}

function csvCell(value) {
  const text = value === null || value === undefined ? '' : String(value);
  // Quote whenever the cell could otherwise break the row, and double any
  // embedded quotes per RFC 4180.
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}
