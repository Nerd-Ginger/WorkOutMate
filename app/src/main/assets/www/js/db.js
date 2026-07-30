/**
 * IndexedDB access layer.
 *
 * The WebView serves this page from a virtual https origin rather than
 * file://, which is what makes IndexedDB behave like it would in any browser —
 * on a file:// origin storage is unreliable and `crypto.randomUUID` is absent.
 */

import { STORE_NAMES } from './merge.js';

export const DB_NAME = 'workoutmate';
export const DB_VERSION = 1;

/**
 * Keyed by the version each migration upgrades *to*. `onupgradeneeded` walks
 * from oldVersion+1 to newVersion, so adding a v2 here is all a future schema
 * change needs — existing installs upgrade in place, new installs replay every
 * migration in order.
 */
const MIGRATIONS = {
  1(db) {
    const exercises = db.createObjectStore('exercises', { keyPath: 'id' });
    exercises.createIndex('by_name', 'name');
    exercises.createIndex('by_updatedAt', 'updatedAt');

    const routines = db.createObjectStore('routines', { keyPath: 'id' });
    routines.createIndex('by_updatedAt', 'updatedAt');

    const sessions = db.createObjectStore('sessions', { keyPath: 'id' });
    sessions.createIndex('by_startedAt', 'startedAt');
    sessions.createIndex('by_routineId', 'routineId');

    const sets = db.createObjectStore('sets', { keyPath: 'id' });
    sets.createIndex('by_sessionId', 'sessionId');
    sets.createIndex('by_exerciseId', 'exerciseId');
    // The workhorse index: powers both the per-exercise progress charts and
    // the "last time you did this" reference shown while logging.
    sets.createIndex('by_exercise_performedAt', ['exerciseId', 'performedAt']);

    const measurements = db.createObjectStore('measurements', { keyPath: 'id' });
    measurements.createIndex('by_type_measuredAt', ['type', 'measuredAt']);

    const prs = db.createObjectStore('prs', { keyPath: 'id' });
    prs.createIndex('by_exerciseId', 'exerciseId');

    db.createObjectStore('settings', { keyPath: 'key' });
  },
};

let dbPromise = null;

export function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) {
      reject(new Error('IndexedDB is unavailable. Storage will not work in this context.'));
      return;
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = (event) => {
      const db = request.result;
      for (let v = event.oldVersion + 1; v <= event.newVersion; v += 1) {
        const migrate = MIGRATIONS[v];
        if (migrate) migrate(db, request.transaction);
      }
    };

    request.onsuccess = () => {
      const db = request.result;
      // Another tab (or a future version of the app) asked to upgrade; close
      // so it isn't blocked waiting on this connection.
      db.onversionchange = () => db.close();
      resolve(db);
    };
    request.onerror = () => reject(request.error || new Error('Could not open the database'));
    request.onblocked = () =>
      reject(new Error('The database is open elsewhere and is blocking the upgrade.'));
  });
  return dbPromise;
}

function promisify(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

/** Resolves once the transaction commits, so callers can trust durability. */
function done(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error('Transaction aborted'));
  });
}

export async function getAll(storeName) {
  const db = await openDb();
  const tx = db.transaction(storeName, 'readonly');
  const rows = await promisify(tx.objectStore(storeName).getAll());
  return rows || [];
}

/** Live rows only — soft-deleted records exist purely so merges behave. */
export async function getLive(storeName) {
  return (await getAll(storeName)).filter((row) => !row.deletedAt);
}

export async function get(storeName, key) {
  const db = await openDb();
  const tx = db.transaction(storeName, 'readonly');
  return promisify(tx.objectStore(storeName).get(key));
}

export async function put(storeName, value) {
  const db = await openDb();
  const tx = db.transaction(storeName, 'readwrite');
  tx.objectStore(storeName).put(value);
  await done(tx);
  return value;
}

export async function putAll(storeName, values) {
  if (!values || !values.length) return;
  const db = await openDb();
  const tx = db.transaction(storeName, 'readwrite');
  const store = tx.objectStore(storeName);
  for (const value of values) store.put(value);
  await done(tx);
}

/** Writes across several stores atomically — used by session save and import. */
export async function putMany(byStore) {
  const names = Object.keys(byStore).filter((name) => (byStore[name] || []).length);
  if (!names.length) return;
  const db = await openDb();
  const tx = db.transaction(names, 'readwrite');
  for (const name of names) {
    const store = tx.objectStore(name);
    for (const value of byStore[name]) store.put(value);
  }
  await done(tx);
}

export async function getByIndex(storeName, indexName, query) {
  const db = await openDb();
  const tx = db.transaction(storeName, 'readonly');
  return (await promisify(tx.objectStore(storeName).index(indexName).getAll(query))) || [];
}

export async function replaceAll(byStore) {
  const names = Object.keys(byStore);
  if (!names.length) return;
  const db = await openDb();
  const tx = db.transaction(names, 'readwrite');
  for (const name of names) {
    const store = tx.objectStore(name);
    store.clear();
    for (const value of byStore[name] || []) store.put(value);
  }
  await done(tx);
}

/** Every store, in the shape the backup envelope and merge code expect. */
export async function exportStores() {
  const stores = {};
  for (const name of STORE_NAMES) stores[name] = await getAll(name);
  return stores;
}

// ---- Settings ------------------------------------------------------------

export async function getSetting(key, fallback = null) {
  const row = await get('settings', key);
  return row === undefined || row === null ? fallback : row.value;
}

export async function setSetting(key, value) {
  return put('settings', { key, value, updatedAt: Date.now() });
}

export async function getSettings() {
  const rows = await getAll('settings');
  const settings = {};
  for (const row of rows) settings[row.key] = row.value;
  return settings;
}

/** Test hook — lets a fresh page start from a clean database. */
export function resetConnection() {
  dbPromise = null;
}
