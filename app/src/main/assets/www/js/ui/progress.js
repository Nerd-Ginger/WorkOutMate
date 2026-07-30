/**
 * Progress charts — the reason the app exists. Everything here reads from the
 * pure aggregation functions in `stats.js` and renders through the hand-rolled
 * SVG helpers in `charts.js`.
 */

import { liveExercises, render, store, units } from '../app.js';
import {
  barChart,
  calendarHeatmap,
  lineChart,
  stackedBarChart,
} from '../charts.js';
import {
  e1rmSeries,
  fromKg,
  isoWeekKey,
  setsPerMuscleGroup,
  topSetSeries,
  weeklyVolume,
  workoutFrequency,
} from '../stats.js';
import { el, emptyState, field, formatNumber, section, select } from './dom.js';

let selectedExerciseId = null;

export function renderProgress(view) {
  const liveSets = store.sets.filter((s) => !s.deletedAt);
  const hasData = liveSets.some((s) => s.completed);

  if (!hasData) {
    view.appendChild(
      section('Progress', emptyState('Log a workout and your charts will start filling in here.'))
    );
    return;
  }

  view.appendChild(renderExerciseSection(liveSets));
  view.appendChild(renderVolumeSection(liveSets));
  view.appendChild(renderMuscleGroupSection(liveSets));
  view.appendChild(renderFrequencySection());
}

// ---- Per-exercise strength ----------------------------------------------

function renderExerciseSection(liveSets) {
  // Only offer exercises that actually have logged sets — a picker full of
  // empty charts is noise.
  const trained = new Set(liveSets.filter((s) => s.completed).map((s) => s.exerciseId));
  const options = liveExercises()
    .filter((e) => trained.has(e.id))
    .map((e) => ({ value: e.id, label: e.name }));

  if (!options.length) {
    return section('Strength over time', emptyState('No completed sets yet.'));
  }

  if (!selectedExerciseId || !trained.has(selectedExerciseId)) {
    selectedExerciseId = options[0].value;
  }

  const unit = units();
  const e1rm = e1rmSeries(liveSets, selectedExerciseId).map((p) => ({
    ...p,
    value: fromKg(p.value, unit),
    weight: fromKg(p.weight, unit),
  }));
  const topSet = topSetSeries(liveSets, selectedExerciseId).map((p) => ({
    ...p,
    value: fromKg(p.value, unit),
  }));

  const chartHost = el('div', { class: 'chart-host' });

  const picker = select(options, selectedExerciseId, (e) => {
    selectedExerciseId = e.target.value;
    render();
  });

  const container = section('Strength over time', [
    field('Exercise', picker),
    chartHost,
    el('p', {
      class: 'muted',
      text: 'Estimated 1RM uses the Epley formula and ignores sets above 12 reps, where the estimate stops being meaningful.',
    }),
  ]);

  lineChart(chartHost, {
    series: [
      { label: `Estimated 1RM (${unit})`, points: e1rm },
      { label: `Heaviest set (${unit})`, points: topSet },
    ],
    includeZero: false,
    formatValue: (v) => `${formatNumber(v, 1)} ${unit}`,
    emptyMessage: 'No sets in a rep range that supports a 1RM estimate yet.',
  });

  return container;
}

// ---- Weekly volume -------------------------------------------------------

function renderVolumeSection(liveSets) {
  const unit = units();
  const weekly = weeklyVolume(liveSets).slice(-12);
  const chartHost = el('div', { class: 'chart-host' });

  const container = section('Weekly volume', [
    chartHost,
    el('p', { class: 'muted', text: 'Total tonnage (weight × reps) across working sets. Warm-ups are excluded.' }),
  ]);

  barChart(chartHost, {
    bars: weekly.map((entry) => ({
      label: shortWeek(entry.weekStart),
      value: fromKg(entry.volume, unit),
    })),
    formatValue: (v) => `${formatNumber(v)} ${unit}`,
  });

  return container;
}

// ---- Sets per muscle group ----------------------------------------------

function renderMuscleGroupSection(liveSets) {
  const exercisesById = new Map(store.exercises.map((e) => [e.id, e]));
  const weekly = setsPerMuscleGroup(liveSets, exercisesById).slice(-12);

  // Order categories by total volume of sets so the legend leads with the
  // groups that actually dominate the training.
  const totals = new Map();
  for (const week of weekly) {
    for (const [group, count] of Object.entries(week.groups)) {
      totals.set(group, (totals.get(group) || 0) + count);
    }
  }
  const categories = [...totals.entries()].sort((a, b) => b[1] - a[1]).map(([group]) => group);

  const chartHost = el('div', { class: 'chart-host' });
  const container = section('Sets per muscle group', [
    chartHost,
    el('p', { class: 'muted', text: 'Working sets per week. An exercise counts once for every group it trains.' }),
  ]);

  stackedBarChart(chartHost, {
    bars: weekly.map((entry) => ({ label: shortWeek(entry.weekStart), values: entry.groups })),
    categories,
  });

  return container;
}

// ---- Frequency -----------------------------------------------------------

function renderFrequencySection() {
  const counts = workoutFrequency(store.sessions.filter((s) => !s.deletedAt));
  const chartHost = el('div', { class: 'chart-host chart-host-scroll' });

  const finished = store.sessions.filter((s) => !s.deletedAt && s.finishedAt);
  const thisWeek = isoWeekKey(Date.now());
  const sessionsThisWeek = finished.filter((s) => isoWeekKey(s.startedAt) === thisWeek).length;

  const container = section('Training frequency', [
    el('div', { class: 'stat-row' }, [
      el('div', { class: 'stat' }, [
        el('span', { class: 'stat-value', text: String(finished.length) }),
        el('span', { class: 'stat-label', text: 'Sessions logged' }),
      ]),
      el('div', { class: 'stat' }, [
        el('span', { class: 'stat-value', text: String(sessionsThisWeek) }),
        el('span', { class: 'stat-label', text: 'This week' }),
      ]),
      el('div', { class: 'stat' }, [
        el('span', { class: 'stat-value', text: String(countStreak(counts)) }),
        el('span', { class: 'stat-label', text: 'Week streak' }),
      ]),
    ]),
    chartHost,
  ]);

  calendarHeatmap(chartHost, { counts, weeks: 26 });
  return container;
}

/** Consecutive weeks, counting back from this one, with at least one session. */
function countStreak(counts) {
  const weeks = new Set();
  for (const [day, count] of counts) {
    if (count > 0) weeks.add(isoWeekKey(Date.parse(`${day}T12:00:00`)));
  }

  let streak = 0;
  const cursor = new Date();
  for (let i = 0; i < 520; i += 1) {
    const key = isoWeekKey(cursor.getTime());
    if (!weeks.has(key)) {
      // The current week not being trained yet shouldn't break a live streak.
      if (i > 0) break;
    } else {
      streak += 1;
    }
    cursor.setDate(cursor.getDate() - 7);
  }
  return streak;
}

function shortWeek(weekStart) {
  return new Date(weekStart).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}
