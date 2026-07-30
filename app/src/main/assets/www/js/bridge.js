/**
 * The JS side of the Kotlin bridge.
 *
 * Inside the app `window.WMNative` is injected by `NativeBridge.kt`. When it is
 * absent — which is how the UI gets driven under Playwright in a desktop
 * browser — each capability falls back to a browser equivalent. The fallbacks
 * exist to make the app testable without an emulator; they are not a shipping
 * target, and anything that genuinely needs Android (the Keystore-backed API
 * key, the background-safe rest alarm) reports itself unavailable rather than
 * pretending.
 */

const native = globalThis.WMNative || null;

export const isNative = !!native;

// ---- Async call plumbing -------------------------------------------------

const pending = new Map();
let callbackSeq = 0;

globalThis.__wmNativeCallback = (callbackId, ok, payload) => {
  const entry = pending.get(callbackId);
  if (!entry) return;
  pending.delete(callbackId);
  if (ok) entry.resolve(payload);
  else entry.reject(new Error(payload || 'The operation failed.'));
};

function callNative(invoke) {
  return new Promise((resolve, reject) => {
    const callbackId = `cb-${(callbackSeq += 1)}`;
    pending.set(callbackId, { resolve, reject });
    try {
      invoke(callbackId);
    } catch (e) {
      pending.delete(callbackId);
      reject(e);
    }
  });
}

// ---- Events from Kotlin (rest timer ticks) -------------------------------

const listeners = new Map();

globalThis.__wmNativeEvent = (name, detailJson) => {
  let detail = {};
  try {
    detail = JSON.parse(detailJson || '{}');
  } catch {
    detail = {};
  }
  emit(name, detail);
};

function emit(name, detail) {
  for (const handler of listeners.get(name) || []) {
    try {
      handler(detail);
    } catch (e) {
      console.error(`listener for ${name} failed`, e);
    }
  }
}

export function on(event, handler) {
  if (!listeners.has(event)) listeners.set(event, new Set());
  listeners.get(event).add(handler);
  return () => listeners.get(event).delete(handler);
}

// ---- Files ---------------------------------------------------------------

/**
 * `<a download>` does nothing inside an Android WebView, so on device this
 * hands the bytes to the Storage Access Framework and the user picks where
 * they land. That is also why the app needs no storage permission.
 */
export async function saveFile(body, suggestedName) {
  if (native) return callNative((cb) => native.saveFile(body, suggestedName, cb));

  const blob = new Blob([body], { type: 'application/octet-stream' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = suggestedName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return suggestedName;
}

export async function openFile() {
  if (native) return callNative((cb) => native.openFile(cb));

  return new Promise((resolve, reject) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'application/json,.json';
    input.style.display = 'none';
    input.addEventListener('change', () => {
      const file = input.files && input.files[0];
      input.remove();
      if (!file) {
        reject(new Error('cancelled'));
        return;
      }
      file.text().then(resolve, reject);
    });
    document.body.appendChild(input);
    input.click();
  });
}

// ---- Local snapshots -----------------------------------------------------

export function writeSnapshot(stamp, body) {
  if (!native) return '';
  try {
    return native.writeSnapshot(stamp, body) || '';
  } catch {
    return '';
  }
}

export function listSnapshots() {
  if (!native) return [];
  try {
    return JSON.parse(native.listSnapshots() || '[]');
  } catch {
    return [];
  }
}

export function readSnapshot(name) {
  if (!native) return '';
  try {
    return native.readSnapshot(name) || '';
  } catch {
    return '';
  }
}

// ---- Rest timer ----------------------------------------------------------

let fallbackTimer = null;
let fallbackRemaining = 0;

export function startRest(seconds) {
  if (native) {
    native.startRest(seconds);
    return;
  }
  cancelRest();
  fallbackRemaining = seconds;
  emit('rest-tick', { remainingSec: fallbackRemaining });
  fallbackTimer = setInterval(() => {
    fallbackRemaining -= 1;
    if (fallbackRemaining <= 0) {
      clearInterval(fallbackTimer);
      fallbackTimer = null;
      emit('rest-done', { remainingSec: 0 });
    } else {
      emit('rest-tick', { remainingSec: fallbackRemaining });
    }
  }, 1000);
}

export function cancelRest() {
  if (native) {
    native.cancelRest();
    return;
  }
  if (fallbackTimer) {
    clearInterval(fallbackTimer);
    fallbackTimer = null;
    emit('rest-cancelled', { remainingSec: 0 });
  }
}

export function isResting() {
  if (native) {
    try {
      return native.isResting();
    } catch {
      return false;
    }
  }
  return fallbackTimer !== null;
}

export function keepScreenOn(on) {
  if (native) native.keepScreenOn(!!on);
}

// ---- API key (write-only from here) --------------------------------------

export function isSecureStoreAvailable() {
  if (!native) return false;
  try {
    return native.isSecureStoreAvailable();
  } catch {
    return false;
  }
}

export function hasApiKey() {
  if (!native) return false;
  try {
    return native.hasApiKey();
  } catch {
    return false;
  }
}

export function setApiKey(value) {
  if (!native) return false;
  return native.setApiKey(value);
}

export function clearApiKey() {
  if (!native) return false;
  return native.clearApiKey();
}

export function getModel() {
  if (!native) return 'claude-opus-5';
  try {
    return native.getModel();
  } catch {
    return 'claude-opus-5';
  }
}

export function setModel(value) {
  if (!native) return false;
  return native.setModel(value);
}

/**
 * Sends a prepared Messages API request. The key and model are added on the
 * Kotlin side and never reach this page.
 */
export async function callClaude(requestJson) {
  if (!native) {
    throw new Error(
      'The in-app Claude call needs the Android app. Use the copy/paste builder instead.'
    );
  }
  return callNative((cb) => native.callClaude(requestJson, cb));
}

export function appVersion() {
  if (!native) return 'dev';
  try {
    return native.appVersion();
  } catch {
    return 'unknown';
  }
}
