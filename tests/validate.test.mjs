import test from 'node:test';
import assert from 'node:assert/strict';

import {
  PLAN_SCHEMA,
  normaliseExerciseName,
  stripCodeFence,
  validatePlan,
} from '../app/src/main/assets/www/js/validate.js';

function validPlan(overrides = {}) {
  return {
    schema: PLAN_SCHEMA,
    programName: 'Upper/Lower',
    summary: 'A four-day split.',
    progressionNotes: 'Add 2.5kg when you hit the top of the rep range.',
    routines: [
      {
        name: 'Day A — Upper',
        notes: '',
        blocks: [
          {
            type: 'single',
            restSec: 150,
            items: [
              {
                exerciseName: 'Barbell Bench Press',
                muscleGroups: ['chest', 'triceps'],
                equipment: 'barbell',
                targetSets: 4,
                targetRepsMin: 6,
                targetRepsMax: 8,
                targetRpe: 8,
                notes: '',
              },
            ],
          },
        ],
      },
    ],
    ...overrides,
  };
}

test('a well-formed plan validates and is normalised', () => {
  const { ok, errors, plan } = validatePlan(validPlan());
  assert.equal(ok, true, errors.join('; '));
  assert.equal(plan.routines[0].blocks[0].items[0].exerciseName, 'Barbell Bench Press');
  assert.equal(plan.routines[0].blocks[0].restSec, 150);
});

test('a plan supplied as a JSON string is parsed', () => {
  const { ok } = validatePlan(JSON.stringify(validPlan()));
  assert.equal(ok, true);
});

test('the wrong schema string is rejected', () => {
  const { ok, errors } = validatePlan(validPlan({ schema: 'something.else.v1' }));
  assert.equal(ok, false);
  assert.ok(errors.some((e) => e.includes('schema')));
});

test('malformed JSON reports a parse error rather than throwing', () => {
  const { ok, errors, plan } = validatePlan('{ not json at all ');
  assert.equal(ok, false);
  assert.equal(plan, null);
  assert.ok(errors[0].toLowerCase().includes('json'));
});

test('a non-object top level is rejected', () => {
  assert.equal(validatePlan('[]').ok, false);
  assert.equal(validatePlan('null').ok, false);
  assert.equal(validatePlan(42).ok, false);
});

test('out-of-range set counts are rejected with a useful message', () => {
  const plan = validPlan();
  plan.routines[0].blocks[0].items[0].targetSets = 99;
  const { ok, errors } = validatePlan(plan);
  assert.equal(ok, false);
  assert.ok(errors.some((e) => e.includes('targetSets')));
});

test('a rep range running backwards is rejected', () => {
  const plan = validPlan();
  plan.routines[0].blocks[0].items[0].targetRepsMin = 12;
  plan.routines[0].blocks[0].items[0].targetRepsMax = 6;
  const { ok, errors } = validatePlan(plan);
  assert.equal(ok, false);
  assert.ok(errors.some((e) => e.includes('targetRepsMax')));
});

test('a missing exercise name is rejected', () => {
  const plan = validPlan();
  delete plan.routines[0].blocks[0].items[0].exerciseName;
  const { ok, errors } = validatePlan(plan);
  assert.equal(ok, false);
  assert.ok(errors.some((e) => e.includes('exerciseName')));
});

test('validation is all-or-nothing — no partial plan is returned', () => {
  const plan = validPlan();
  plan.routines[0].blocks[0].items[0].targetSets = -3;
  const result = validatePlan(plan);
  assert.equal(result.ok, false);
  assert.equal(result.plan, null);
});

test('every bad field is reported, not just the first', () => {
  const plan = validPlan();
  plan.routines[0].blocks[0].items[0].targetSets = 99;
  plan.routines[0].blocks[0].items[0].targetRepsMin = 500;
  const { errors } = validatePlan(plan);
  assert.ok(errors.length >= 2, `expected several errors, got ${errors.length}`);
});

test('a superset survives validation as a multi-item block', () => {
  const plan = validPlan();
  plan.routines[0].blocks[0].type = 'superset';
  plan.routines[0].blocks[0].items.push({
    exerciseName: 'Barbell Row',
    muscleGroups: ['back'],
    equipment: 'barbell',
    targetSets: 4,
    targetRepsMin: 6,
    targetRepsMax: 8,
  });
  const { ok, plan: normalised } = validatePlan(plan);
  assert.equal(ok, true);
  assert.equal(normalised.routines[0].blocks[0].items.length, 2);
  assert.equal(normalised.routines[0].blocks[0].type, 'superset');
});

test('a multi-item block mislabelled "single" is corrected rather than rejected', () => {
  const plan = validPlan();
  plan.routines[0].blocks[0].items.push({
    exerciseName: 'Barbell Row',
    targetSets: 4,
    targetRepsMin: 6,
    targetRepsMax: 8,
  });
  const { ok, plan: normalised } = validatePlan(plan);
  assert.equal(ok, true);
  assert.equal(normalised.routines[0].blocks[0].type, 'superset');
});

test('an omitted rest interval falls back to a sensible default', () => {
  const plan = validPlan();
  delete plan.routines[0].blocks[0].restSec;
  const { ok, plan: normalised } = validatePlan(plan);
  assert.equal(ok, true);
  assert.equal(normalised.routines[0].blocks[0].restSec, 120);
});

test('targetRepsMax defaults to targetRepsMin for a fixed-rep prescription', () => {
  const plan = validPlan();
  delete plan.routines[0].blocks[0].items[0].targetRepsMax;
  plan.routines[0].blocks[0].items[0].targetRepsMin = 5;
  const { ok, plan: normalised } = validatePlan(plan);
  assert.equal(ok, true);
  assert.equal(normalised.routines[0].blocks[0].items[0].targetRepsMax, 5);
});

test('stripCodeFence unwraps a fenced JSON block', () => {
  const fenced = '```json\n{"a":1}\n```';
  assert.equal(stripCodeFence(fenced), '{"a":1}');
});

test('stripCodeFence salvages JSON surrounded by chatter', () => {
  const chatty = 'Sure! Here you go:\n{"a":1}\nHope that helps.';
  assert.equal(stripCodeFence(chatty), '{"a":1}');
});

test('a fenced plan validates end to end', () => {
  const fenced = '```json\n' + JSON.stringify(validPlan()) + '\n```';
  assert.equal(validatePlan(fenced).ok, true);
});

test('normaliseExerciseName ignores case and punctuation', () => {
  assert.equal(normaliseExerciseName('Barbell Bench Press'), 'barbell bench press');
  assert.equal(
    normaliseExerciseName('Barbell  Bench-Press!'),
    normaliseExerciseName('barbell bench press')
  );
});
