import test from 'node:test';
import assert from 'node:assert/strict';

import {
  BACKUP_SCHEMA,
  buildBackup,
  mergeBackup,
  mergeRows,
  setsToCsv,
  summariseStores,
  validateBackup,
} from '../app/src/main/assets/www/js/merge.js';

function backup(stores = {}) {
  return buildBackup({ stores, appVersion: '1.0.0', exportedAt: 1000, dbVersion: 1 });
}

test('a well-formed backup validates', () => {
  const { ok, errors, backup: parsed } = validateBackup(
    backup({ exercises: [{ id: 'e1', name: 'Squat', updatedAt: 5 }] })
  );
  assert.equal(ok, true, errors.join('; '));
  assert.equal(parsed.stores.exercises.length, 1);
});

test('a backup supplied as a JSON string is parsed', () => {
  const { ok } = validateBackup(JSON.stringify(backup()));
  assert.equal(ok, true);
});

test('a foreign file is rejected before anything is written', () => {
  const { ok, errors } = validateBackup(JSON.stringify({ schema: 'someone.elses.app', stores: {} }));
  assert.equal(ok, false);
  assert.ok(errors[0].includes('WorkOutMate'));
});

test('malformed JSON reports a parse error rather than throwing', () => {
  const { ok, errors } = validateBackup('}{');
  assert.equal(ok, false);
  assert.ok(errors[0].toLowerCase().includes('json'));
});

test('rows missing their key are rejected', () => {
  const bad = backup({ exercises: [{ name: 'No id here' }] });
  const { ok, errors } = validateBackup(bad);
  assert.equal(ok, false);
  assert.ok(errors.some((e) => e.includes('"id"')));
});

test('settings rows are keyed by name, not id', () => {
  const { ok } = validateBackup(backup({ settings: [{ key: 'units', value: 'kg' }] }));
  assert.equal(ok, true);
});

test('a store absent from an older backup is treated as empty', () => {
  const raw = { schema: BACKUP_SCHEMA, exportedAt: 1, dbVersion: 1, stores: { exercises: [] } };
  const { ok, backup: parsed } = validateBackup(raw);
  assert.equal(ok, true);
  assert.deepEqual(parsed.stores.measurements, []);
});

test('mergeRows keeps the newer copy of a record', () => {
  const { rows, updated } = mergeRows(
    [{ id: 'a', name: 'Old', updatedAt: 100 }],
    [{ id: 'a', name: 'New', updatedAt: 200 }]
  );
  assert.equal(rows.length, 1);
  assert.equal(rows[0].name, 'New');
  assert.equal(updated, 1);
});

test('mergeRows keeps the local copy when the incoming one is older', () => {
  const { rows, unchanged } = mergeRows(
    [{ id: 'a', name: 'Current', updatedAt: 500 }],
    [{ id: 'a', name: 'Stale', updatedAt: 100 }]
  );
  assert.equal(rows[0].name, 'Current');
  assert.equal(unchanged, 1);
});

test('mergeRows adds records the local database has never seen', () => {
  const { rows, added } = mergeRows([{ id: 'a', updatedAt: 1 }], [{ id: 'b', updatedAt: 1 }]);
  assert.equal(rows.length, 2);
  assert.equal(added, 1);
});

test('restoring an old backup does not resurrect records deleted since', () => {
  // The local copy is a newer soft delete; the backup predates the deletion.
  const { rows } = mergeRows(
    [{ id: 'a', name: 'Squat', updatedAt: 900, deletedAt: 900 }],
    [{ id: 'a', name: 'Squat', updatedAt: 100, deletedAt: null }]
  );
  assert.equal(rows.length, 1);
  assert.equal(rows[0].deletedAt, 900, 'the deletion should have survived the merge');
});

test('a deletion recorded in the backup propagates when it is the newer edit', () => {
  const { rows } = mergeRows(
    [{ id: 'a', updatedAt: 100, deletedAt: null }],
    [{ id: 'a', updatedAt: 900, deletedAt: 900 }]
  );
  assert.equal(rows[0].deletedAt, 900);
});

test('a record with no timestamp never beats one that has a timestamp', () => {
  const { rows } = mergeRows([{ id: 'a', name: 'Stamped', updatedAt: 50 }], [{ id: 'a', name: 'Unstamped' }]);
  assert.equal(rows[0].name, 'Stamped');
});

test('merging is idempotent — applying the same backup twice changes nothing', () => {
  const current = { exercises: [{ id: 'a', updatedAt: 100 }] };
  const incoming = validateBackup(backup({ exercises: [{ id: 'b', updatedAt: 200 }] })).backup;

  const once = mergeBackup(current, incoming);
  const twice = mergeBackup(once.stores, incoming);
  assert.deepEqual(twice.stores.exercises, once.stores.exercises);
  assert.equal(twice.stats.exercises.added, 0);
});

test('mergeBackup reports per-store statistics', () => {
  const incoming = validateBackup(
    backup({ sets: [{ id: 's1', updatedAt: 10 }, { id: 's2', updatedAt: 10 }] })
  ).backup;
  const { stats } = mergeBackup({ sets: [{ id: 's1', updatedAt: 5 }] }, incoming);
  assert.equal(stats.sets.added, 1);
  assert.equal(stats.sets.updated, 1);
});

test('summariseStores counts live records only', () => {
  const summary = summariseStores({
    exercises: [{ id: 'a' }, { id: 'b', deletedAt: 1 }],
    sessions: [{ id: 'c' }],
  });
  assert.equal(summary.exercises, 1);
  assert.equal(summary.sessions, 1);
  assert.equal(summary.total, 2);
});

test('CSV export emits a header and one row per live set', () => {
  const csv = setsToCsv({
    sets: [
      { id: 's1', sessionId: 'w1', exerciseId: 'e1', weight: 100, reps: 5, kind: 'working', completed: true, performedAt: Date.parse('2026-07-30T10:00:00Z') },
      { id: 's2', sessionId: 'w1', exerciseId: 'e1', weight: 100, reps: 5, deletedAt: 1, performedAt: 1 },
    ],
    sessions: [{ id: 'w1', routineName: 'Upper' }],
    exercises: [{ id: 'e1', name: 'Bench Press' }],
  });
  const lines = csv.split('\n');
  assert.equal(lines.length, 2);
  assert.ok(lines[0].startsWith('date,session,exercise'));
  assert.ok(lines[1].includes('Bench Press'));
});

test('CSV export escapes commas and quotes so rows cannot break', () => {
  const csv = setsToCsv({
    sets: [{ id: 's1', sessionId: 'w1', exerciseId: 'e1', weight: 60, reps: 8, completed: true, performedAt: 1, notes: 'felt "easy", went up' }],
    sessions: [{ id: 'w1', routineName: 'Leg, day' }],
    exercises: [{ id: 'e1', name: 'Squat' }],
  });
  const [headerRow, dataRow] = csv.split('\n');
  assert.ok(dataRow.includes('"Leg, day"'));
  assert.ok(dataRow.includes('"felt ""easy"", went up"'));

  // The real test: a spreadsheet parsing this row must recover the original
  // values, with the embedded commas and quotes intact and the column count
  // unchanged.
  const cells = parseCsvRow(dataRow);
  assert.equal(cells.length, parseCsvRow(headerRow).length);
  assert.equal(cells[1], 'Leg, day');
  assert.equal(cells[9], 'felt "easy", went up');
});

/** Minimal RFC 4180 row parser, used to prove the export round-trips. */
function parseCsvRow(row) {
  const cells = [];
  let cell = '';
  let inQuotes = false;
  for (let i = 0; i < row.length; i += 1) {
    const char = row[i];
    if (inQuotes) {
      if (char === '"') {
        if (row[i + 1] === '"') {
          cell += '"';
          i += 1;
        } else {
          inQuotes = false;
        }
      } else {
        cell += char;
      }
    } else if (char === '"') {
      inQuotes = true;
    } else if (char === ',') {
      cells.push(cell);
      cell = '';
    } else {
      cell += char;
    }
  }
  cells.push(cell);
  return cells;
}
