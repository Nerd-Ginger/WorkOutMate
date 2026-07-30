/**
 * Chart scale maths, split out from rendering so it can be unit-tested without
 * a DOM. Everything the SVG helpers need to turn data into coordinates lives
 * here; `charts.js` only concerns itself with emitting elements.
 */

/**
 * Maps a value from `domain` onto `range`, clamped to the range.
 * A zero-width domain maps everything to the middle of the range rather than
 * dividing by zero — that's the single-data-point case, which is common early
 * on when a lifter has logged one session.
 */
export function linearScale({ domain, range }) {
  const [d0, d1] = domain;
  const [r0, r1] = range;
  const span = d1 - d0;
  if (!Number.isFinite(span) || span === 0) {
    const mid = (r0 + r1) / 2;
    return () => mid;
  }
  return (value) => {
    const t = (Number(value) - d0) / span;
    const clamped = t < 0 ? 0 : t > 1 ? 1 : t;
    return r0 + clamped * (r1 - r0);
  };
}

/**
 * Rounds a step up to the nearest 1, 2, 5 or 10 x 10^n so axis labels land on
 * numbers people read easily.
 */
export function niceStep(rawStep) {
  if (!(rawStep > 0)) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(rawStep));
  const normalised = rawStep / magnitude;
  const nice = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10;
  return nice * magnitude;
}

/**
 * Tick values spanning [min, max] on round numbers.
 * @returns {number[]} ascending, always at least two entries
 */
export function niceTicks(min, max, count = 5) {
  let lo = Number(min);
  let hi = Number(max);
  if (!Number.isFinite(lo) || !Number.isFinite(hi)) return [0, 1];
  if (lo > hi) [lo, hi] = [hi, lo];

  if (lo === hi) {
    // Flat series: manufacture a band around the value so the line isn't drawn
    // along the axis itself.
    const pad = Math.abs(lo) > 0 ? Math.abs(lo) * 0.1 : 1;
    lo -= pad;
    hi += pad;
  }

  const step = niceStep((hi - lo) / Math.max(1, count));
  const start = Math.floor(lo / step) * step;
  const end = Math.ceil(hi / step) * step;

  const ticks = [];
  // Accumulate by index rather than repeated addition so floating-point error
  // doesn't drift across a long axis.
  const steps = Math.round((end - start) / step);
  for (let i = 0; i <= steps; i += 1) {
    const value = start + i * step;
    // Re-round to kill representation noise like 0.30000000000000004.
    ticks.push(Number(value.toPrecision(12)));
  }
  return ticks.length >= 2 ? ticks : [start, start + step];
}

/**
 * Domain for a value axis. Value charts read better anchored at zero, but an
 * e1RM series that lives between 90 and 110kg would be a flat line squashed at
 * the top — so zero-anchoring is opt-out.
 */
export function valueDomain(values, { includeZero = true, padding = 0.05 } = {}) {
  const finite = (values || []).map(Number).filter(Number.isFinite);
  if (!finite.length) return [0, 1];

  let lo = Math.min(...finite);
  let hi = Math.max(...finite);
  if (includeZero) lo = Math.min(0, lo);

  if (lo === hi) {
    const pad = Math.abs(hi) > 0 ? Math.abs(hi) * 0.1 : 1;
    return [lo - pad, hi + pad];
  }

  const pad = (hi - lo) * padding;
  return [includeZero && lo === 0 ? 0 : lo - pad, hi + pad];
}

/** Formats an axis value without trailing noise ("100", "12.5", "1.2k"). */
export function formatTick(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '';
  if (Math.abs(n) >= 10000) return `${Number((n / 1000).toFixed(1))}k`;
  if (Number.isInteger(n)) return String(n);
  return String(Number(n.toFixed(2)));
}
