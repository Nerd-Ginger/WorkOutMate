import test from 'node:test';
import assert from 'node:assert/strict';

import {
  E1RM_MAX_REPS,
  detectPrs,
  e1rmSeries,
  epley1RM,
  fromKg,
  isoWeekKey,
  lastPerformance,
  localDayKey,
  prKey,
  sessionVolume,
  setVolume,
  setsPerMuscleGroup,
  startOfIsoWeek,
  toKg,
  weeklyVolume,
  workoutFrequency,
} from '../app/src/main/assets/www/js/stats.js';

/** Minimal set factory — every field the stats code actually reads. */
function set(overrides = {}) {
  return {
    id: overrides.id || Math.random().toString(36).slice(2),
    sessionId: 's1',
    exerciseId: 'bench',
    kind: 'working',
    completed: true,
    weight: 100,
    reps: 5,
    performedAt: Date.parse('2026-07-29T10:00:00'),
    deletedAt: null,
    ...overrides,
  };
}

test('epley1RM returns the weight itself for a single', () => {
  assert.equal(epley1RM(100, 1), 100);
});

test('epley1RM applies w x (1 + reps/30)', () => {
  assert.ok(Math.abs(epley1RM(100, 5) - 116.6667) < 0.001);
  assert.ok(Math.abs(epley1RM(60, 10) - 80) < 0.001);
});

test('epley1RM refuses rep counts where the formula stops being meaningful', () => {
  assert.ok(epley1RM(50, E1RM_MAX_REPS) > 0);
  assert.equal(epley1RM(50, E1RM_MAX_REPS + 1), 0);
});

test('epley1RM rejects nonsense input rather than producing a number', () => {
  assert.equal(epley1RM(0, 5), 0);
  assert.equal(epley1RM(100, 0), 0);
  assert.equal(epley1RM(-100, 5), 0);
  assert.equal(epley1RM('heavy', 5), 0);
  assert.equal(epley1RM(undefined, undefined), 0);
});

test('unit conversion round-trips', () => {
  const kg = toKg(225, 'lb');
  assert.ok(Math.abs(kg - 102.058) < 0.01);
  assert.ok(Math.abs(fromKg(kg, 'lb') - 225) < 0.0001);
  assert.equal(toKg(100, 'kg'), 100);
});

test('warm-up and incomplete sets contribute no volume', () => {
  assert.equal(setVolume(set({ weight: 100, reps: 5 })), 500);
  assert.equal(setVolume(set({ kind: 'warmup' })), 0);
  assert.equal(setVolume(set({ completed: false })), 0);
  assert.equal(setVolume(set({ deletedAt: Date.now() })), 0);
});

test('sessionVolume sums only the counted sets', () => {
  const sets = [
    set({ weight: 100, reps: 5 }),
    set({ weight: 60, reps: 10 }),
    set({ weight: 40, reps: 10, kind: 'warmup' }),
  ];
  assert.equal(sessionVolume(sets), 500 + 600);
});

test('isoWeekKey handles the year boundary the ISO way', () => {
  // 2027-01-01 is a Friday, so it belongs to ISO week 53 of 2026.
  assert.equal(isoWeekKey(Date.parse('2027-01-01T12:00:00')), '2026-W53');
  // 2026-01-01 is a Thursday, so it is week 1 of 2026.
  assert.equal(isoWeekKey(Date.parse('2026-01-01T12:00:00')), '2026-W01');
});

test('isoWeekKey groups a Monday and the following Sunday together', () => {
  const monday = Date.parse('2026-07-27T06:00:00');
  const sunday = Date.parse('2026-08-02T22:00:00');
  assert.equal(isoWeekKey(monday), isoWeekKey(sunday));
});

test('startOfIsoWeek snaps back to Monday midnight', () => {
  const start = new Date(startOfIsoWeek(Date.parse('2026-07-30T17:45:00')));
  assert.equal(start.getDay(), 1);
  assert.equal(start.getHours(), 0);
  assert.equal(start.getMinutes(), 0);
});

test('localDayKey uses local time, not UTC', () => {
  // A late-evening session belongs to the day the lifter trained.
  assert.equal(localDayKey(Date.parse('2026-07-30T23:30:00')), '2026-07-30');
});

test('weeklyVolume buckets and sorts by week', () => {
  const result = weeklyVolume([
    set({ performedAt: Date.parse('2026-07-27T10:00:00'), weight: 100, reps: 5 }),
    set({ performedAt: Date.parse('2026-08-02T10:00:00'), weight: 100, reps: 5 }),
    set({ performedAt: Date.parse('2026-08-04T10:00:00'), weight: 50, reps: 10 }),
  ]);
  assert.equal(result.length, 2);
  assert.equal(result[0].volume, 1000);
  assert.equal(result[1].volume, 500);
  assert.ok(result[0].week < result[1].week);
});

test('setsPerMuscleGroup credits every group a movement trains', () => {
  const exercises = new Map([
    ['bench', { id: 'bench', muscleGroups: ['chest', 'triceps'] }],
    ['squat', { id: 'squat', muscleGroups: ['quads'] }],
  ]);
  const result = setsPerMuscleGroup(
    [
      set({ exerciseId: 'bench' }),
      set({ exerciseId: 'bench' }),
      set({ exerciseId: 'squat' }),
      set({ exerciseId: 'squat', kind: 'warmup' }),
    ],
    exercises
  );
  assert.equal(result.length, 1);
  assert.deepEqual(result[0].groups, { chest: 2, triceps: 2, quads: 1 });
});

test('setsPerMuscleGroup files unknown exercises under "other"', () => {
  const result = setsPerMuscleGroup([set({ exerciseId: 'mystery' })], new Map());
  assert.deepEqual(result[0].groups, { other: 1 });
});

test('e1rmSeries keeps the best set per session, ordered by time', () => {
  const series = e1rmSeries(
    [
      set({ sessionId: 'a', weight: 100, reps: 5, performedAt: 1000 }),
      set({ sessionId: 'a', weight: 110, reps: 3, performedAt: 1500 }),
      set({ sessionId: 'b', weight: 105, reps: 5, performedAt: 5000 }),
    ],
    'bench'
  );
  assert.equal(series.length, 2);
  assert.equal(series[0].weight, 110);
  assert.ok(series[0].t < series[1].t);
});

test('workoutFrequency counts only finished sessions', () => {
  const counts = workoutFrequency([
    { id: 'a', startedAt: Date.parse('2026-07-30T10:00:00'), finishedAt: 1 },
    { id: 'b', startedAt: Date.parse('2026-07-30T18:00:00'), finishedAt: 1 },
    { id: 'c', startedAt: Date.parse('2026-07-31T10:00:00'), finishedAt: null },
  ]);
  assert.equal(counts.get('2026-07-30'), 2);
  assert.equal(counts.has('2026-07-31'), false);
});

test('detectPrs reports a first-ever session as records across all kinds', () => {
  const records = detectPrs([set({ weight: 100, reps: 5 })], new Map());
  const kinds = records.map((r) => r.kind).sort();
  assert.deepEqual(kinds, ['e1rm', 'volume', 'weight']);
});

test('detectPrs does not award a record for matching a previous best', () => {
  const bests = new Map([
    [prKey('bench', 'weight'), { value: 100 }],
    [prKey('bench', 'e1rm'), { value: 116.67 }],
    [prKey('bench', 'volume'), { value: 500 }],
  ]);
  const records = detectPrs([set({ weight: 100, reps: 5 })], bests);
  assert.equal(records.length, 0);
});

test('detectPrs awards a weight record when the bar goes up', () => {
  const bests = new Map([
    [prKey('bench', 'weight'), { value: 100 }],
    [prKey('bench', 'e1rm'), { value: 999 }],
    [prKey('bench', 'volume'), { value: 999999 }],
  ]);
  const records = detectPrs([set({ weight: 102.5, reps: 3 })], bests);
  assert.equal(records.length, 1);
  assert.equal(records[0].kind, 'weight');
  assert.equal(records[0].value, 102.5);
});

test('detectPrs ignores warm-ups when deciding records', () => {
  const bests = new Map([
    [prKey('bench', 'weight'), { value: 100 }],
    [prKey('bench', 'e1rm'), { value: 999 }],
    [prKey('bench', 'volume'), { value: 999999 }],
  ]);
  const records = detectPrs([set({ weight: 200, reps: 1, kind: 'warmup' })], bests);
  assert.equal(records.length, 0);
});

test('detectPrs sums session volume across sets of the same exercise', () => {
  const bests = new Map([
    [prKey('bench', 'weight'), { value: 999 }],
    [prKey('bench', 'e1rm'), { value: 999 }],
    [prKey('bench', 'volume'), { value: 900 }],
  ]);
  const records = detectPrs(
    [set({ weight: 100, reps: 5 }), set({ weight: 100, reps: 5 })],
    bests
  );
  assert.equal(records.length, 1);
  assert.equal(records[0].kind, 'volume');
  assert.equal(records[0].value, 1000);
});

test('lastPerformance skips the session in progress', () => {
  const sets = [
    set({ id: 'old', sessionId: 'prev', weight: 95, reps: 5, performedAt: 1000 }),
    set({ id: 'new', sessionId: 'current', weight: 100, reps: 5, performedAt: 9000 }),
  ];
  const previous = lastPerformance(sets, 'bench', 'current');
  assert.equal(previous.sessionId, 'prev');
  assert.equal(previous.sets[0].weight, 95);
});

test('lastPerformance returns null the first time an exercise is performed', () => {
  assert.equal(lastPerformance([], 'bench', 'current'), null);
});
