/**
 * Settings, and the "Save progress" backup flow.
 *
 * All data lives on this device, so an export is the only thing standing
 * between a wiped app and a lost training history. Import offers two modes
 * deliberately: replace-all for "new phone", merge for "I restored an old
 * backup but have kept training since".
 */

import { bridge, db, reload, render, store } from '../app.js';
import {
  buildBackup,
  mergeBackup,
  setsToCsv,
  summariseStores,
  validateBackup,
} from '../merge.js';
import {
  button,
  confirmDialog,
  el,
  emptyState,
  errorList,
  field,
  formatDateTime,
  section,
  select,
  toast,
} from './dom.js';

export function renderSettings(view) {
  view.appendChild(renderBackupSection());
  view.appendChild(renderPreferencesSection());
  view.appendChild(renderClaudeSection());
  view.appendChild(renderSnapshotSection());
  view.appendChild(renderAboutSection());
}

// ---- Backup --------------------------------------------------------------

function renderBackupSection() {
  const last = Number(store.settings.lastBackupAt) || 0;

  return section('Save progress', [
    el('p', {
      text: 'Everything is stored on this device. Export a backup regularly — clearing app data or uninstalling wipes your history.',
    }),
    el('p', {
      class: last ? 'muted' : 'warn',
      text: last ? `Last backup: ${formatDateTime(last)}` : 'You have never saved a backup.',
    }),
    el('div', { class: 'row-actions row-actions-stack' }, [
      button('Export backup', exportBackup, { variant: 'btn-primary' }),
      button('Import backup', importBackup),
      button('Export sets as CSV', exportCsv, { variant: 'btn-ghost' }),
    ]),
  ]);
}

async function exportBackup() {
  try {
    const stores = await db.exportStores();
    const payload = buildBackup({
      stores,
      appVersion: bridge.appVersion(),
      exportedAt: Date.now(),
      dbVersion: db.DB_VERSION,
    });
    const name = `workoutmate-backup-${new Date().toISOString().slice(0, 10)}.json`;
    await bridge.saveFile(JSON.stringify(payload), name);

    await db.setSetting('lastBackupAt', Date.now());
    await reload(['settings']);
    toast('Backup saved.');
    render();
  } catch (error) {
    if (String(error.message) === 'cancelled') return;
    toast(`Export failed: ${error.message}`, { tone: 'error' });
  }
}

async function exportCsv() {
  try {
    const csv = setsToCsv({
      sets: store.sets,
      sessions: store.sessions,
      exercises: store.exercises,
      unit: store.settings.units || 'kg',
    });
    const name = `workoutmate-sets-${new Date().toISOString().slice(0, 10)}.csv`;
    await bridge.saveFile(csv, name);
    toast('CSV saved.');
  } catch (error) {
    if (String(error.message) === 'cancelled') return;
    toast(`Export failed: ${error.message}`, { tone: 'error' });
  }
}

async function importBackup() {
  let text;
  try {
    text = await bridge.openFile();
  } catch (error) {
    if (String(error.message) === 'cancelled') return;
    toast(`Could not read that file: ${error.message}`, { tone: 'error' });
    return;
  }

  const { ok, errors, backup } = validateBackup(text);
  if (!ok) {
    showImportErrors(errors);
    return;
  }

  const incoming = summariseStores(backup.stores);
  const mode = await chooseImportMode(backup, incoming);
  if (!mode) return;

  if (mode === 'replace') {
    const confirmed = await confirmDialog({
      title: 'Replace everything?',
      message: 'Your current data will be deleted and replaced with the contents of this backup. This cannot be undone.',
      confirmLabel: 'Replace everything',
      danger: true,
    });
    if (!confirmed) return;
    await db.replaceAll(backup.stores);
  } else {
    const current = await db.exportStores();
    const { stores } = mergeBackup(current, backup);
    await db.replaceAll(stores);
  }

  await reload();
  toast(mode === 'replace' ? 'Backup restored.' : 'Backup merged.');
  render();
}

function showImportErrors(errors) {
  const overlay = el('div', { class: 'overlay', onClick: (e) => { if (e.target === overlay) overlay.remove(); } }, [
    el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' }, [
      el('h2', { text: 'That file could not be imported' }),
      el('p', { text: 'Nothing has been changed.' }),
      errorList(errors),
      el('div', { class: 'modal-actions' }, [button('Close', () => overlay.remove())]),
    ]),
  ]);
  document.body.appendChild(overlay);
}

function chooseImportMode(backup, incoming) {
  return new Promise((resolve) => {
    const close = (value) => {
      overlay.remove();
      resolve(value);
    };

    const overlay = el('div', { class: 'overlay', onClick: (e) => { if (e.target === overlay) close(null); } }, [
      el('div', { class: 'modal modal-wide', role: 'dialog', 'aria-modal': 'true' }, [
        el('h2', { text: 'Import backup' }),
        el('p', {
          text: `From ${backup.exportedAt ? formatDateTime(backup.exportedAt) : 'an unknown date'} (app version ${backup.appVersion}).`,
        }),
        el('ul', { class: 'sub-list' }, [
          el('li', { text: `${incoming.sessions} sessions` }),
          el('li', { text: `${incoming.sets} sets` }),
          el('li', { text: `${incoming.routines} routines` }),
          el('li', { text: `${incoming.exercises} exercises` }),
          el('li', { text: `${incoming.measurements} measurements` }),
        ]),
        el('h3', { text: 'Merge' }),
        el('p', {
          class: 'muted',
          text: 'Combines the backup with what is on this device. Where both have the same record, the more recently edited one wins, and anything you deleted stays deleted.',
        }),
        el('h3', { text: 'Replace everything' }),
        el('p', {
          class: 'muted',
          text: 'Wipes this device and restores the backup exactly. Use this on a new phone.',
        }),
        el('div', { class: 'modal-actions' }, [
          button('Cancel', () => close(null)),
          button('Replace everything', () => close('replace'), { variant: 'btn-danger' }),
          button('Merge', () => close('merge'), { variant: 'btn-primary' }),
        ]),
      ]),
    ]);
    document.body.appendChild(overlay);
  });
}

// ---- Preferences ---------------------------------------------------------

function renderPreferencesSection() {
  return section('Preferences', [
    field(
      'Units',
      select(
        [
          { value: 'kg', label: 'Kilograms' },
          { value: 'lb', label: 'Pounds' },
        ],
        store.settings.units,
        async (e) => {
          await db.setSetting('units', e.target.value);
          await reload(['settings']);
          toast('Units updated.');
          render();
        }
      ),
      'Weights are stored in kilograms and converted for display, so switching never alters your logged numbers.'
    ),
    field(
      'Default rest (seconds)',
      el('input', {
        class: 'input input-num',
        type: 'number',
        min: '0',
        max: '900',
        step: '15',
        value: store.settings.restDefaultSec,
        onChange: async (e) => {
          const value = Math.max(0, Math.min(900, Number(e.target.value) || 0));
          await db.setSetting('restDefaultSec', value);
          await reload(['settings']);
          toast('Default rest updated.');
        },
      }),
      'Used when a routine does not specify its own rest interval.'
    ),
  ]);
}

// ---- Claude --------------------------------------------------------------

function renderClaudeSection() {
  if (!bridge.isNative) {
    return section('Build me a routine', [
      el('p', {
        class: 'muted',
        text: 'The in-app Claude call is only available in the Android app. The copy/paste builder works everywhere.',
      }),
    ]);
  }

  if (!bridge.isSecureStoreAvailable()) {
    return section('Build me a routine', [
      el('p', {
        class: 'warn',
        text: 'Secure storage is unavailable on this device, so an API key cannot be saved safely. Use the copy/paste routine builder instead — it needs no key.',
      }),
    ]);
  }

  const hasKey = bridge.hasApiKey();
  const keyInput = el('input', {
    class: 'input',
    type: 'password',
    placeholder: hasKey ? 'A key is saved' : 'sk-ant-…',
    autocomplete: 'off',
  });

  const modelInput = el('input', {
    class: 'input',
    type: 'text',
    value: bridge.getModel(),
  });

  return section('Build me a routine', [
    el('p', {
      text: 'Optional. With an Anthropic API key saved, routines can be generated inside the app. Without one, the copy/paste builder works exactly the same way.',
    }),
    el('p', {
      class: 'muted',
      text: 'The key is held in Android\'s Keystore-backed storage and is never readable by the app\'s web layer — requests are made from native code.',
    }),
    field('API key', keyInput),
    el('div', { class: 'row-actions' }, [
      button('Save key', () => {
        const value = keyInput.value.trim();
        if (!value) {
          toast('Paste a key first.', { tone: 'error' });
          return;
        }
        if (bridge.setApiKey(value)) {
          keyInput.value = '';
          toast('Key saved.');
          render();
        } else {
          toast('Could not save the key.', { tone: 'error' });
        }
      }, { variant: 'btn-primary' }),
      hasKey
        ? button('Remove key', async () => {
            const confirmed = await confirmDialog({
              title: 'Remove API key?',
              message: 'In-app generation will stop working. The copy/paste builder will still work.',
              confirmLabel: 'Remove',
              danger: true,
            });
            if (!confirmed) return;
            bridge.clearApiKey();
            toast('Key removed.');
            render();
          }, { variant: 'btn-ghost btn-danger' })
        : null,
    ]),
    field('Model', modelInput),
    el('div', { class: 'row-actions' }, [
      button('Save model', () => {
        const value = modelInput.value.trim();
        if (!value) {
          toast('Enter a model id.', { tone: 'error' });
          return;
        }
        bridge.setModel(value);
        toast('Model updated.');
      }, { variant: 'btn-small' }),
    ]),
  ]);
}

// ---- Snapshots -----------------------------------------------------------

function renderSnapshotSection() {
  if (!bridge.isNative) return el('div');

  const snapshots = bridge.listSnapshots();

  return section('Automatic snapshots', [
    el('p', {
      class: 'muted',
      text: 'A copy is saved inside the app after every finished workout. These survive the app\'s storage being corrupted, but not uninstalling — they are a safety net, not a substitute for exporting.',
    }),
    snapshots.length
      ? el('ul', { class: 'list' }, snapshots.slice(0, 10).map((name) =>
          el('li', { class: 'list-row' }, [
            el('div', { class: 'list-main' }, [el('strong', { text: readableSnapshot(name) })]),
            button('Restore', () => restoreSnapshot(name), { variant: 'btn-small' }),
          ])
        ))
      : emptyState('No snapshots yet — finish a workout and one will appear.'),
  ]);
}

function readableSnapshot(name) {
  const stamp = name.replace(/^auto-/, '').replace(/\.json$/, '');
  // The filename is an ISO timestamp with the colons and dot swapped for
  // hyphens; put them back so Date can read it.
  const iso = stamp.replace(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2})-(\d{2})-(\d{2})-(\d{3})Z$/,
    '$1-$2-$3T$4:$5:$6.$7Z'
  );
  const parsed = Date.parse(iso);
  return Number.isNaN(parsed) ? name : formatDateTime(parsed);
}

async function restoreSnapshot(name) {
  const text = bridge.readSnapshot(name);
  if (!text) {
    toast('That snapshot could not be read.', { tone: 'error' });
    return;
  }
  const { ok, errors, backup } = validateBackup(text);
  if (!ok) {
    showImportErrors(errors);
    return;
  }
  const confirmed = await confirmDialog({
    title: 'Restore this snapshot?',
    message: 'Your current data will be replaced with the contents of the snapshot. This cannot be undone.',
    confirmLabel: 'Restore',
    danger: true,
  });
  if (!confirmed) return;

  await db.replaceAll(backup.stores);
  await reload();
  toast('Snapshot restored.');
  render();
}

// ---- About ---------------------------------------------------------------

function renderAboutSection() {
  const counts = {
    sessions: store.sessions.filter((s) => !s.deletedAt && s.finishedAt).length,
    sets: store.sets.filter((s) => !s.deletedAt && s.completed).length,
    routines: store.routines.filter((r) => !r.deletedAt).length,
    exercises: store.exercises.filter((e) => !e.deletedAt).length,
  };

  return section('About', [
    el('ul', { class: 'sub-list' }, [
      el('li', { text: `Version ${bridge.appVersion()}` }),
      el('li', { text: `${counts.sessions} sessions, ${counts.sets} sets logged` }),
      el('li', { text: `${counts.routines} routines, ${counts.exercises} exercises` }),
      el('li', { text: 'No account, no server, no tracking. Everything stays on this device.' }),
    ]),
  ]);
}
