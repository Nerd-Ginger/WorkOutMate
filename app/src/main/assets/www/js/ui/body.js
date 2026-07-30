/**
 * Bodyweight and measurements — a separate log from the lifting one, with its
 * own chart.
 */

import { db, reload, render, store, units } from '../app.js';
import { lineChart } from '../charts.js';
import { MEASUREMENT_TYPES, makeMeasurement, softDelete } from '../model.js';
import { fromKg, toKg } from '../stats.js';
import {
  button,
  confirmDialog,
  el,
  emptyState,
  field,
  formatDate,
  formatNumber,
  section,
  select,
  toast,
} from './dom.js';

let selectedType = 'bodyweight';

export function renderBody(view) {
  view.appendChild(renderEntryForm());
  view.appendChild(renderChartSection());
  view.appendChild(renderHistory());
}

function typeMeta(type) {
  return MEASUREMENT_TYPES.find((t) => t.value === type) || MEASUREMENT_TYPES[0];
}

/** Bodyweight follows the app's weight unit; girths are always cm. */
function unitFor(type) {
  return type === 'bodyweight' ? units() : typeMeta(type).unit;
}

function renderEntryForm() {
  const typeSelect = select(
    MEASUREMENT_TYPES.map((t) => ({ value: t.value, label: t.label })),
    selectedType,
    (e) => {
      selectedType = e.target.value;
      render();
    }
  );

  const valueInput = el('input', {
    class: 'input input-num',
    type: 'number',
    inputmode: 'decimal',
    step: '0.1',
    min: '0',
    placeholder: unitFor(selectedType),
  });

  const dateInput = el('input', {
    class: 'input',
    type: 'date',
    value: new Date().toISOString().slice(0, 10),
  });

  return section('Record a measurement', [
    field('What', typeSelect),
    field(`Value (${unitFor(selectedType)})`, valueInput),
    field('Date', dateInput),
    el('div', { class: 'row-actions' }, [
      button('Save', async () => {
        const raw = Number(valueInput.value);
        if (!Number.isFinite(raw) || raw <= 0) {
          toast('Enter a value first.', { tone: 'error' });
          return;
        }
        // Bodyweight is normalised to kg like every other weight in the app;
        // girths and percentages are stored exactly as entered.
        const stored = selectedType === 'bodyweight' ? toKg(raw, units()) : raw;

        const measuredAt = dateInput.value
          ? new Date(`${dateInput.value}T12:00:00`).getTime()
          : Date.now();

        await db.put('measurements', makeMeasurement({
          type: selectedType,
          value: stored,
          unit: selectedType === 'bodyweight' ? 'kg' : typeMeta(selectedType).unit,
          measuredAt,
        }));
        await reload(['measurements']);
        valueInput.value = '';
        toast('Saved.');
        render();
      }, { variant: 'btn-primary' }),
    ]),
  ]);
}

function entriesFor(type) {
  return store.measurements
    .filter((m) => !m.deletedAt && m.type === type)
    .sort((a, b) => a.measuredAt - b.measuredAt);
}

function displayValue(entry) {
  return entry.type === 'bodyweight' ? fromKg(entry.value, units()) : Number(entry.value);
}

function renderChartSection() {
  const entries = entriesFor(selectedType);
  const meta = typeMeta(selectedType);
  const unit = unitFor(selectedType);
  const chartHost = el('div', { class: 'chart-host' });

  const container = section(`${meta.label} over time`, [chartHost]);

  lineChart(chartHost, {
    series: [
      {
        label: `${meta.label} (${unit})`,
        points: entries.map((entry) => ({ t: entry.measuredAt, value: displayValue(entry) })),
      },
    ],
    includeZero: false,
    formatValue: (v) => `${formatNumber(v, 1)} ${unit}`,
    emptyMessage: `No ${meta.label.toLowerCase()} entries yet.`,
  });

  return container;
}

function renderHistory() {
  const entries = entriesFor(selectedType).slice().reverse();
  const meta = typeMeta(selectedType);
  const unit = unitFor(selectedType);

  return section(
    'History',
    entries.length
      ? el('ul', { class: 'list' }, entries.map((entry) =>
          el('li', { class: 'list-row' }, [
            el('div', { class: 'list-main' }, [
              el('strong', { text: `${formatNumber(displayValue(entry), 1)} ${unit}` }),
              el('span', { class: 'muted', text: formatDate(entry.measuredAt) }),
            ]),
            button('Delete', async () => {
              const confirmed = await confirmDialog({
                title: 'Delete entry?',
                message: `This ${meta.label.toLowerCase()} entry will be removed.`,
                confirmLabel: 'Delete',
                danger: true,
              });
              if (!confirmed) return;
              await db.put('measurements', softDelete(entry));
              await reload(['measurements']);
              render();
            }, { variant: 'btn-small btn-ghost btn-danger' }),
          ])
        ))
      : emptyState(`No ${meta.label.toLowerCase()} entries yet.`)
  );
}
