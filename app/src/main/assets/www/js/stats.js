/**
 * Training maths. Deliberately DOM-free and dependency-free so `node --test`
 * can import it directly from the assets directory — that's what lets this
 * project have real unit tests with no build step and no test framework.
 *
 * Weights are stored in kilograms throughout; `unit` only ever affects display.
 */

export const KG_PER_LB = 0.45359237;

export function toKg(value, unit) {
  const n = Number(value);
  if (!Number.isFinite(n)) return 0;
  return unit === 'lb' ? n * KG_PER_LB : n;
}

export function fromKg(kg, unit) {
  const n = Number(kg);
  if (!Number.isFinite(n)) return 0;
  return unit === 'lb' ? n / KG_PER_LB : n;
}

/** Rounds to the nearest half unit — the smallest increment most gyms can load. */
export function roundForDisplay(value) {
  return Math.round(Number(value) * 2) / 2;
}

/**
 * Epley estimated one-rep max: w x (1 + reps/30).
 *
 * The formula degrades badly past about 12 reps, where it starts predicting
 * maxes nobody could actually lift, so high-rep sets are excluded from e1RM
 * charts rather than silently skewing them upward.
 */
export const E1RM_MAX_REPS = 12;

export function epley1RM(weight, reps) {
  const w = Number(weight);
  const r = Math.trunc(Number(reps));
  if (!(w > 0) || !(r > 0)) return 0;
  if (r === 1) return w;
  if (r > E1RM_MAX_REPS) return 0;
  return w * (1 + r / 30);
}

export function isCountedSet(set) {
  return !!set && !set.deletedAt && set.completed === true && set.kind !== 'warmup';
}

/** Tonnage for one set: weight x reps. Warm-ups don't count toward volume. */
export function setVolume(set) {
  if (!isCountedSet(set)) return 0;
  const w = Number(set.weight);
  const r = Math.trunc(Number(set.reps));
  if (!(w > 0) || !(r > 0)) return 0;
  return w * r;
}

export function sessionVolume(sets) {
  return (sets || []).reduce((total, set) => total + setVolume(set), 0);
}

// ---- Date bucketing ------------------------------------------------------
// All bucketing is done in local time: a 9pm Sunday session belongs to the day
// the lifter thinks they trained, not to whatever UTC says.

export function localDayKey(ts) {
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return '';
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

/** Monday 00:00 local time of the week containing `ts`. */
export function startOfIsoWeek(ts) {
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return NaN;
  d.setHours(0, 0, 0, 0);
  // getDay() is 0=Sunday; shift so Monday is 0.
  const offset = (d.getDay() + 6) % 7;
  d.setDate(d.getDate() - offset);
  return d.getTime();
}

/** ISO-8601 week label, e.g. "2026-W31". Sorts correctly as a string. */
export function isoWeekKey(ts) {
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return '';
  d.setHours(0, 0, 0, 0);
  // Shift to the Thursday of this week; the ISO year is whatever year that
  // Thursday falls in, which is what makes the year boundary come out right.
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7) + 3);
  const isoYear = d.getFullYear();
  const firstThursday = new Date(isoYear, 0, 4);
  firstThursday.setHours(0, 0, 0, 0);
  firstThursday.setDate(firstThursday.getDate() - ((firstThursday.getDay() + 6) % 7) + 3);
  const week = 1 + Math.round((d.getTime() - firstThursday.getTime()) / (7 * 86400000));
  return `${isoYear}-W${String(week).padStart(2, '0')}`;
}

// ---- Aggregations --------------------------------------------------------

/**
 * Total tonnage per ISO week.
 * @returns {Array<{week: string, weekStart: number, volume: number}>} ascending by week
 */
export function weeklyVolume(sets) {
  const buckets = new Map();
  for (const set of sets || []) {
    const volume = setVolume(set);
    if (volume <= 0) continue;
    const key = isoWeekKey(set.performedAt);
    if (!key) continue;
    const bucket = buckets.get(key) || { week: key, weekStart: startOfIsoWeek(set.performedAt), volume: 0 };
    bucket.volume += volume;
    buckets.set(key, bucket);
  }
  return [...buckets.values()].sort((a, b) => a.week.localeCompare(b.week));
}

/**
 * Counts working sets per muscle group per week. A set targeting several
 * groups counts once for each — the question being answered is "did I train
 * legs this week", not "how do I divide credit between quads and glutes".
 * @returns {Array<{week: string, weekStart: number, groups: Object<string, number>}>}
 */
export function setsPerMuscleGroup(sets, exercisesById) {
  const lookup = exercisesById instanceof Map ? exercisesById : new Map(Object.entries(exercisesById || {}));
  const buckets = new Map();
  for (const set of sets || []) {
    if (!isCountedSet(set)) continue;
    const key = isoWeekKey(set.performedAt);
    if (!key) continue;
    const exercise = lookup.get(set.exerciseId);
    const groups = (exercise && exercise.muscleGroups) || ['other'];
    const bucket = buckets.get(key) || { week: key, weekStart: startOfIsoWeek(set.performedAt), groups: {} };
    for (const group of groups.length ? groups : ['other']) {
      bucket.groups[group] = (bucket.groups[group] || 0) + 1;
    }
    buckets.set(key, bucket);
  }
  return [...buckets.values()].sort((a, b) => a.week.localeCompare(b.week));
}

/**
 * One point per session: the best estimated 1RM achieved for an exercise.
 * This is the headline "am I getting stronger" series.
 */
export function e1rmSeries(sets, exerciseId) {
  const bySession = new Map();
  for (const set of sets || []) {
    if (!isCountedSet(set) || set.exerciseId !== exerciseId) continue;
    const value = epley1RM(set.weight, set.reps);
    if (value <= 0) continue;
    const existing = bySession.get(set.sessionId);
    if (!existing || value > existing.value) {
      bySession.set(set.sessionId, { t: set.performedAt, value, weight: set.weight, reps: set.reps });
    }
  }
  return [...bySession.values()].sort((a, b) => a.t - b.t);
}

/** One point per session: the heaviest working set, regardless of reps. */
export function topSetSeries(sets, exerciseId) {
  const bySession = new Map();
  for (const set of sets || []) {
    if (!isCountedSet(set) || set.exerciseId !== exerciseId) continue;
    const weight = Number(set.weight);
    if (!(weight > 0)) continue;
    const existing = bySession.get(set.sessionId);
    if (!existing || weight > existing.value) {
      bySession.set(set.sessionId, { t: set.performedAt, value: weight, reps: set.reps });
    }
  }
  return [...bySession.values()].sort((a, b) => a.t - b.t);
}

/**
 * Per-day workout counts for the calendar heatmap.
 * @returns {Map<string, number>} keyed by local day, e.g. "2026-07-30"
 */
export function workoutFrequency(sessions) {
  const counts = new Map();
  for (const session of sessions || []) {
    if (!session || session.deletedAt || !session.finishedAt) continue;
    const key = localDayKey(session.startedAt);
    if (!key) continue;
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return counts;
}

// ---- Personal records ----------------------------------------------------

export const PR_KINDS = ['e1rm', 'weight', 'volume'];

export function prKey(exerciseId, kind) {
  return `${exerciseId}:${kind}`;
}

/**
 * Compares a finished session's sets against the current bests and returns the
 * records that were beaten.
 *
 * `currentBests` maps `${exerciseId}:${kind}` to a record with a numeric
 * `value`. Ties do not count as records — you have to actually beat it.
 *
 * @returns {Array<Object>} new PR records, ready to be written to the store
 */
export function detectPrs(sets, currentBests) {
  const bests = currentBests instanceof Map ? currentBests : new Map(Object.entries(currentBests || {}));
  const candidates = new Map();

  for (const set of sets || []) {
    if (!isCountedSet(set)) continue;
    const weight = Number(set.weight);
    const reps = Math.trunc(Number(set.reps));
    if (!(weight > 0) || !(reps > 0)) continue;

    const entry = candidates.get(set.exerciseId) || {
      exerciseId: set.exerciseId,
      sessionId: set.sessionId,
      e1rm: null,
      weight: null,
      volume: 0,
      volumeAt: set.performedAt,
      volumeSetId: set.id,
    };

    const e1rm = epley1RM(weight, reps);
    if (e1rm > 0 && (!entry.e1rm || e1rm > entry.e1rm.value)) {
      entry.e1rm = { value: e1rm, weight, reps, setId: set.id, achievedAt: set.performedAt };
    }
    if (!entry.weight || weight > entry.weight.value) {
      entry.weight = { value: weight, weight, reps, setId: set.id, achievedAt: set.performedAt };
    }
    entry.volume += weight * reps;
    candidates.set(set.exerciseId, entry);
  }

  const records = [];
  for (const entry of candidates.values()) {
    const check = (kind, candidate) => {
      if (!candidate || !(candidate.value > 0)) return;
      const previous = bests.get(prKey(entry.exerciseId, kind));
      if (previous && Number(previous.value) >= candidate.value) return;
      records.push({
        id: prKey(entry.exerciseId, kind),
        exerciseId: entry.exerciseId,
        kind,
        value: candidate.value,
        weight: candidate.weight ?? null,
        reps: candidate.reps ?? null,
        setId: candidate.setId ?? null,
        sessionId: entry.sessionId,
        achievedAt: candidate.achievedAt,
      });
    };

    check('e1rm', entry.e1rm);
    check('weight', entry.weight);
    check('volume', {
      value: entry.volume,
      weight: null,
      reps: null,
      setId: entry.volumeSetId,
      achievedAt: entry.volumeAt,
    });
  }
  return records;
}

/**
 * The reference line shown while logging: what you did for this exercise last
 * time. Returns null the first time you ever perform a movement.
 */
export function lastPerformance(sets, exerciseId, excludeSessionId) {
  let latest = null;
  for (const set of sets || []) {
    if (!isCountedSet(set) || set.exerciseId !== exerciseId) continue;
    if (excludeSessionId && set.sessionId === excludeSessionId) continue;
    if (!latest || set.performedAt > latest.performedAt) latest = set;
  }
  if (!latest) return null;
  const sameSession = (sets || []).filter(
    (s) => isCountedSet(s) && s.exerciseId === exerciseId && s.sessionId === latest.sessionId
  );
  return {
    sessionId: latest.sessionId,
    performedAt: latest.performedAt,
    sets: sameSession.map((s) => ({ weight: s.weight, reps: s.reps })),
    bestE1rm: Math.max(0, ...sameSession.map((s) => epley1RM(s.weight, s.reps))),
  };
}
