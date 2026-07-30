/**
 * Small DOM helpers. Everything is built with `document.createElement` rather
 * than innerHTML so user-entered text — exercise names, session notes, and
 * anything a model wrote — can never be parsed as markup.
 */

export function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);

  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;

    if (key === 'class') node.className = value;
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key === 'style') Object.assign(node.style, value);
    else if (key.startsWith('on') && typeof value === 'function') {
      node.addEventListener(key.slice(2).toLowerCase(), value);
    } else if (key === 'text') node.textContent = value;
    else if (key === 'html') throw new Error('Refusing to set innerHTML — build nodes instead.');
    else if (key in node && key !== 'list') node[key] = value;
    else node.setAttribute(key, String(value));
  }

  for (const child of [].concat(children)) {
    if (child === null || child === undefined || child === false) continue;
    node.appendChild(typeof child === 'string' || typeof child === 'number'
      ? document.createTextNode(String(child))
      : child);
  }
  return node;
}

export function clear(node) {
  node.replaceChildren();
  return node;
}

export function qs(selector, root = document) {
  return root.querySelector(selector);
}

// ---- Formatting ----------------------------------------------------------

export function formatWeight(kg, unit) {
  if (kg === null || kg === undefined || kg === '') return '—';
  const value = unit === 'lb' ? Number(kg) / 0.45359237 : Number(kg);
  if (!Number.isFinite(value)) return '—';
  return `${Number(value.toFixed(1))}${unit}`;
}

export function formatNumber(value, digits = 0) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString(undefined, { maximumFractionDigits: digits });
}

export function formatDate(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleDateString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  });
}

export function formatDateTime(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatDuration(ms) {
  if (!ms || ms < 0) return '—';
  const minutes = Math.round(ms / 60000);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`;
}

export function formatClock(totalSeconds) {
  const seconds = Math.max(0, Math.round(totalSeconds));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

export function formatRepRange(item) {
  if (!item) return '';
  const { targetRepsMin: min, targetRepsMax: max } = item;
  if (min && max && min !== max) return `${min}–${max}`;
  return String(min || max || '');
}

// ---- Common building blocks ---------------------------------------------

export function section(title, children, actions = null) {
  return el('section', { class: 'card' }, [
    title
      ? el('header', { class: 'card-head' }, [el('h2', { text: title }), actions])
      : null,
    ...[].concat(children),
  ]);
}

export function emptyState(message, action = null) {
  return el('div', { class: 'empty' }, [el('p', { text: message }), action]);
}

export function button(label, onClick, { variant = '', type = 'button', disabled = false } = {}) {
  return el('button', {
    class: `btn ${variant}`.trim(),
    type,
    disabled,
    onClick,
    text: label,
  });
}

export function field(labelText, control, hint = null) {
  const id = control.id || `f-${Math.random().toString(36).slice(2, 9)}`;
  control.id = id;
  return el('label', { class: 'field', for: id }, [
    el('span', { class: 'field-label', text: labelText }),
    control,
    hint ? el('span', { class: 'field-hint', text: hint }) : null,
  ]);
}

export function select(options, value, onChange, { id } = {}) {
  const node = el('select', { class: 'input', id, onChange });
  for (const option of options) {
    node.appendChild(
      el('option', {
        value: option.value,
        text: option.label,
        selected: String(option.value) === String(value),
      })
    );
  }
  return node;
}

let toastTimer = null;

/** Brief, non-blocking confirmation. Errors stay up longer than successes. */
export function toast(message, { tone = 'info' } = {}) {
  let host = qs('#toast');
  if (!host) {
    host = el('div', { id: 'toast', class: 'toast' });
    document.body.appendChild(host);
  }
  host.className = `toast toast-${tone} toast-visible`;
  host.textContent = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => host.classList.remove('toast-visible'), tone === 'error' ? 6000 : 2800);
}

/**
 * Promise-based modal. Resolves true on confirm, false on cancel — used for
 * anything destructive, since there is no undo.
 */
export function confirmDialog({ title, message, confirmLabel = 'Confirm', danger = false }) {
  return new Promise((resolve) => {
    const close = (result) => {
      overlay.remove();
      document.removeEventListener('keydown', onKey);
      resolve(result);
    };
    const onKey = (event) => {
      if (event.key === 'Escape') close(false);
    };

    const confirmBtn = button(confirmLabel, () => close(true), {
      variant: danger ? 'btn-danger' : 'btn-primary',
    });

    const overlay = el('div', { class: 'overlay', onClick: (e) => { if (e.target === overlay) close(false); } }, [
      el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' }, [
        el('h2', { text: title }),
        el('p', { text: message }),
        el('div', { class: 'modal-actions' }, [
          button('Cancel', () => close(false)),
          confirmBtn,
        ]),
      ]),
    ]);

    document.body.appendChild(overlay);
    document.addEventListener('keydown', onKey);
    confirmBtn.focus();
  });
}

/** Scrollable list of errors — used when a generated plan fails validation. */
export function errorList(errors) {
  return el('ul', { class: 'error-list' }, errors.map((e) => el('li', { text: e })));
}
