import test from 'node:test';
import assert from 'node:assert/strict';

import {
  formatTick,
  linearScale,
  niceStep,
  niceTicks,
  valueDomain,
} from '../app/src/main/assets/www/js/scale.js';

test('linearScale maps domain endpoints onto range endpoints', () => {
  const scale = linearScale({ domain: [0, 100], range: [0, 200] });
  assert.equal(scale(0), 0);
  assert.equal(scale(50), 100);
  assert.equal(scale(100), 200);
});

test('linearScale supports an inverted range, as SVG y-axes need', () => {
  const y = linearScale({ domain: [0, 10], range: [300, 0] });
  assert.equal(y(0), 300);
  assert.equal(y(10), 0);
  assert.equal(y(5), 150);
});

test('linearScale clamps values outside the domain', () => {
  const scale = linearScale({ domain: [0, 10], range: [0, 100] });
  assert.equal(scale(-5), 0);
  assert.equal(scale(15), 100);
});

test('linearScale survives a zero-width domain (a single data point)', () => {
  const scale = linearScale({ domain: [42, 42], range: [0, 100] });
  assert.equal(scale(42), 50);
  assert.ok(Number.isFinite(scale(0)));
});

test('niceStep rounds up to 1, 2, 5 or 10 times a power of ten', () => {
  assert.equal(niceStep(0.9), 1);
  assert.equal(niceStep(1.5), 2);
  assert.equal(niceStep(3), 5);
  assert.equal(niceStep(7), 10);
  assert.equal(niceStep(23), 50);
});

test('niceTicks spans the requested range on round numbers', () => {
  const ticks = niceTicks(0, 100, 5);
  assert.ok(ticks[0] <= 0);
  assert.ok(ticks[ticks.length - 1] >= 100);
  assert.ok(ticks.every((t) => Number.isFinite(t)));
});

test('niceTicks is free of floating-point noise', () => {
  for (const tick of niceTicks(0, 1, 5)) {
    assert.equal(String(tick).length < 8, true, `tick ${tick} carries representation noise`);
  }
});

test('niceTicks manufactures a band around a flat series', () => {
  const ticks = niceTicks(100, 100, 5);
  assert.ok(ticks.length >= 2);
  assert.ok(ticks[0] < 100);
  assert.ok(ticks[ticks.length - 1] > 100);
});

test('niceTicks copes with a reversed range and bad input', () => {
  const reversed = niceTicks(100, 0, 5);
  assert.ok(reversed[0] < reversed[reversed.length - 1]);
  assert.deepEqual(niceTicks(NaN, 10), [0, 1]);
});

test('valueDomain anchors at zero for volume-style charts', () => {
  const [lo] = valueDomain([300, 500, 400], { includeZero: true });
  assert.equal(lo, 0);
});

test('valueDomain can skip zero so a narrow e1RM band stays readable', () => {
  const [lo, hi] = valueDomain([100, 105, 110], { includeZero: false });
  assert.ok(lo > 50, 'expected the domain to hug the data, not start near zero');
  assert.ok(hi > 110);
});

test('valueDomain handles an empty series', () => {
  assert.deepEqual(valueDomain([]), [0, 1]);
});

test('formatTick keeps labels short', () => {
  assert.equal(formatTick(100), '100');
  assert.equal(formatTick(12.5), '12.5');
  assert.equal(formatTick(12500), '12.5k');
  assert.equal(formatTick(0.30000000000000004), '0.3');
});
