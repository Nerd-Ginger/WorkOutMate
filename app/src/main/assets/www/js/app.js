/**
 * Application shell: in-memory store, navigation stack, and boot.
 *
 * The whole database is small enough to keep in memory (a few years of
 * training is tens of thousands of rows at most), so screens read from `store`
 * synchronously and write through to IndexedDB. That keeps the UI code free of
 * async plumbing without giving up durability.
 */

import * as db from './db.js';
import * as bridge from './bridge.js';
import { STORE_NAMES } from './merge.js';
import { makeExercise } from './model.js';
import { el, clear, qs, toast } from './ui/dom.js';

import { renderHome, renderSession } from './ui/train.js';
import { renderRoutines, renderRoutineEdit, renderExercises } from './ui/routines.js';
import { renderBuilder } from './ui/builder.js';
import { renderProgress } from './ui/progress.js';
import { renderBody } from './ui/body.js';
import { renderSettings } from './ui/settings.js';

export const store = {
  settings: { units: 'kg', restDefaultSec: 120, lastBackupAt: 0 },
  exercises: [],
  routines: [],
  sessions: [],
  sets: [],
  measurements: [],
  prs: [],
};

export function units() {
  return store.settings.units === 'lb' ? 'lb' : 'kg';
}

export function exerciseById(id) {
  return store.exercises.find((e) => e.id === id) || null;
}

export function exerciseName(id) {
  const exercise = exerciseById(id);
  return exercise ? exercise.name : 'Unknown exercise';
}

export function liveExercises() {
  return store.exercises
    .filter((e) => !e.deletedAt)
    .sort((a, b) => a.name.localeCompare(b.name));
}

export function liveRoutines() {
  return store.routines
    .filter((r) => !r.deletedAt && !r.archived)
    .sort((a, b) => b.updatedAt - a.updatedAt);
}

/** The one session that has been started but not finished, if any. */
export function activeSession() {
  return store.sessions.find((s) => !s.deletedAt && !s.finishedAt) || null;
}

export async function reload(names = STORE_NAMES) {
  for (const name of names) {
    if (name === 'settings') {
      store.settings = { units: 'kg', restDefaultSec: 120, lastBackupAt: 0, ...(await db.getSettings()) };
    } else {
      store[name] = await db.getAll(name);
    }
  }
}

// ---- Navigation ----------------------------------------------------------

const SCREENS = {
  home: renderHome,
  session: renderSession,
  routines: renderRoutines,
  routineEdit: renderRoutineEdit,
  exercises: renderExercises,
  builder: renderBuilder,
  progress: renderProgress,
  body: renderBody,
  settings: renderSettings,
};

const TABS = [
  { route: 'home', label: 'Train', icon: '\u{1F3CB}' },
  { route: 'routines', label: 'Routines', icon: '\u{1F4CB}' },
  { route: 'progress', label: 'Progress', icon: '\u{1F4C8}' },
  { route: 'body', label: 'Body', icon: '\u{1F4CF}' },
  { route: 'settings', label: 'Settings', icon: '\u{2699}' },
];

const TAB_ROUTES = new Set(TABS.map((t) => t.route));

let stack = [{ name: 'home', params: {} }];

export function currentRoute() {
  return stack[stack.length - 1];
}

export function navigate(name, params = {}) {
  // Selecting a tab resets to a single entry so back never walks sideways
  // through the tab bar — it exits the app, which is what Android users expect.
  if (TAB_ROUTES.has(name)) stack = [{ name, params }];
  else stack.push({ name, params });
  render();
}

export function replace(name, params = {}) {
  stack[stack.length - 1] = { name, params };
  render();
}

/** @returns {boolean} whether the press was consumed */
export function back() {
  if (stack.length <= 1) return false;
  stack.pop();
  render();
  return true;
}

export function render() {
  const route = currentRoute();
  const view = qs('#view');
  clear(view);
  view.scrollTop = 0;

  const screen = SCREENS[route.name];
  if (!screen) {
    view.appendChild(el('p', { class: 'empty', text: `Unknown screen: ${route.name}` }));
    return;
  }

  try {
    screen(view, route.params);
  } catch (error) {
    console.error('render failed', error);
    view.appendChild(
      el('div', { class: 'card' }, [
        el('h2', { text: 'Something went wrong drawing this screen' }),
        el('p', { text: String(error && error.message ? error.message : error) }),
      ])
    );
  }

  renderTabs();
}

function renderTabs() {
  const nav = qs('#tabs');
  clear(nav);
  const active = currentRoute().name;
  for (const tab of TABS) {
    nav.appendChild(
      el('button', {
        class: `tab${tab.route === active ? ' tab-active' : ''}`,
        type: 'button',
        onClick: () => navigate(tab.route),
      }, [
        el('span', { class: 'tab-icon', text: tab.icon, 'aria-hidden': 'true' }),
        el('span', { class: 'tab-label', text: tab.label }),
      ])
    );
  }
}

// ---- Boot ----------------------------------------------------------------

/**
 * Populates the exercise library on first run. Seeded entries are marked
 * `isCustom: false` so the library can distinguish them from a user's own.
 */
async function seedExercisesIfEmpty() {
  if (store.exercises.length) return;
  const response = await fetch('data/exercises.seed.json');
  if (!response.ok) return;
  const seed = await response.json();
  const records = (seed.exercises || []).map((entry) =>
    makeExercise({
      name: entry.name,
      muscleGroups: entry.muscleGroups || [],
      equipment: entry.equipment || 'other',
      isCustom: false,
    })
  );
  await db.putAll('exercises', records);
  store.exercises = records;
}

function showFatalError(message) {
  const view = qs('#view') || document.body;
  clear(view);
  view.appendChild(
    el('div', { class: 'card' }, [
      el('h2', { text: 'WorkOutMate could not start' }),
      el('p', { text: message }),
      el('p', {
        class: 'muted',
        text: 'Storage is unavailable, so nothing can be saved. If this keeps happening, reinstalling and restoring a backup should fix it.',
      }),
    ])
  );
}

export async function boot() {
  // The back button belongs to the page first: it closes a modal or pops a
  // screen, and only exits the app when there is nothing left to go back to.
  globalThis.WM = {
    onBackPressed() {
      const overlay = qs('.overlay');
      if (overlay) {
        overlay.remove();
        return true;
      }
      return back();
    },
    // Exposed for the smoke test to await a known-ready state.
    ready: false,
  };

  try {
    await db.openDb();
    await reload();
    await seedExercisesIfEmpty();
  } catch (error) {
    console.error('boot failed', error);
    showFatalError(String(error && error.message ? error.message : error));
    return;
  }

  // Resume straight into a workout that was interrupted — by a phone call, a
  // crash, or Android reclaiming the process mid-session.
  const active = activeSession();
  if (active) stack = [{ name: 'session', params: { sessionId: active.id } }];

  render();
  maybeNagAboutBackup();
  globalThis.WM.ready = true;
}

const BACKUP_NAG_DAYS = 14;

function maybeNagAboutBackup() {
  if (!store.sessions.some((s) => s.finishedAt)) return;
  const last = Number(store.settings.lastBackupAt) || 0;
  const age = Date.now() - last;
  if (last && age < BACKUP_NAG_DAYS * 86400000) return;

  const banner = qs('#banner');
  clear(banner);
  banner.appendChild(
    el('div', { class: 'banner' }, [
      el('span', {
        text: last
          ? `Last backup was ${Math.floor(age / 86400000)} days ago.`
          : 'You have training logged but have never saved a backup.',
      }),
      el('button', {
        class: 'btn btn-small',
        type: 'button',
        text: 'Save progress',
        onClick: () => navigate('settings'),
      }),
      el('button', {
        class: 'btn btn-small btn-ghost',
        type: 'button',
        text: 'Dismiss',
        'aria-label': 'Dismiss backup reminder',
        onClick: () => clear(banner),
      }),
    ])
  );
}

export { bridge, db, toast };
