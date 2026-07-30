/**
 * Hand-rolled SVG charts.
 *
 * There is no chart library here on purpose: the app ships with zero
 * third-party runtime code. Everything is built on the pure scale maths in
 * `scale.js`, and each chart draws into a fixed viewBox that CSS scales to the
 * container, so they stay readable on any screen width without measuring.
 */

import { formatTick, linearScale, niceTicks, valueDomain } from './scale.js';

const NS = 'http://www.w3.org/2000/svg';

const VIEW = { width: 640, height: 320 };
const PAD = { top: 16, right: 16, bottom: 40, left: 52 };

/** Colour-blind-safe series palette, also used by the muscle-group legend. */
export const SERIES_COLORS = [
  '#F0603A', '#4C9BE8', '#3FB98C', '#E8B84C', '#A87CE0', '#E8788F', '#54C2CE', '#9AA6B2',
];

export function colorFor(index) {
  return SERIES_COLORS[index % SERIES_COLORS.length];
}

function svgEl(name, attrs = {}) {
  const el = document.createElementNS(NS, name);
  for (const [key, value] of Object.entries(attrs)) {
    if (value !== null && value !== undefined) el.setAttribute(key, String(value));
  }
  return el;
}

function createCanvas() {
  const svg = svgEl('svg', {
    viewBox: `0 0 ${VIEW.width} ${VIEW.height}`,
    preserveAspectRatio: 'xMidYMid meet',
    class: 'chart',
    role: 'img',
  });
  return svg;
}

function plotArea() {
  return {
    x0: PAD.left,
    x1: VIEW.width - PAD.right,
    y0: VIEW.height - PAD.bottom,
    y1: PAD.top,
  };
}

function drawEmpty(container, message) {
  container.replaceChildren();
  const empty = document.createElement('p');
  empty.className = 'chart-empty';
  empty.textContent = message;
  container.appendChild(empty);
}

function drawYAxis(svg, area, ticks, yScale, formatValue) {
  for (const tick of ticks) {
    const y = yScale(tick);
    svg.appendChild(
      svgEl('line', { x1: area.x0, x2: area.x1, y1: y, y2: y, class: 'chart-gridline' })
    );
    const label = svgEl('text', { x: area.x0 - 8, y: y + 4, class: 'chart-axis-label chart-axis-y' });
    label.textContent = formatValue ? formatValue(tick) : formatTick(tick);
    svg.appendChild(label);
  }
}

function drawXLabels(svg, area, labels) {
  // Thin the labels out so they never collide on a narrow screen.
  const maxLabels = 6;
  const step = Math.max(1, Math.ceil(labels.length / maxLabels));
  labels.forEach((entry, i) => {
    if (i % step !== 0 && i !== labels.length - 1) return;
    const label = svgEl('text', {
      x: entry.x,
      y: area.y0 + 20,
      class: 'chart-axis-label chart-axis-x',
    });
    label.textContent = entry.text;
    svg.appendChild(label);
  });
}

/**
 * Time-series line chart. `series` is a list of `{label, points:[{t, value}]}`.
 * `includeZero` is off by default because a lifter's e1RM band is narrow and
 * anchoring at zero would flatten it into a straight line.
 */
export function lineChart(container, { series = [], includeZero = false, formatValue, emptyMessage = 'Not enough data yet.' } = {}) {
  const usable = series.filter((s) => s.points && s.points.length);
  if (!usable.length) {
    drawEmpty(container, emptyMessage);
    return;
  }

  const svg = createCanvas();
  const area = plotArea();

  const allPoints = usable.flatMap((s) => s.points);
  const times = allPoints.map((p) => p.t);
  const values = allPoints.map((p) => p.value);

  const domain = valueDomain(values, { includeZero });
  const ticks = niceTicks(domain[0], domain[1], 5);
  const yScale = linearScale({
    domain: [ticks[0], ticks[ticks.length - 1]],
    range: [area.y0, area.y1],
  });
  const xScale = linearScale({
    domain: [Math.min(...times), Math.max(...times)],
    range: [area.x0, area.x1],
  });

  drawYAxis(svg, area, ticks, yScale, formatValue);

  usable.forEach((s, index) => {
    const color = s.color || colorFor(index);
    const sorted = [...s.points].sort((a, b) => a.t - b.t);
    const d = sorted
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${xScale(p.t).toFixed(1)},${yScale(p.value).toFixed(1)}`)
      .join(' ');
    svg.appendChild(svgEl('path', { d, fill: 'none', stroke: color, 'stroke-width': 2.5, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));

    for (const point of sorted) {
      const dot = svgEl('circle', {
        cx: xScale(point.t),
        cy: yScale(point.value),
        r: 4,
        fill: color,
        class: 'chart-point',
        tabindex: '0',
      });
      const title = svgEl('title');
      const shown = formatValue ? formatValue(point.value) : formatTick(point.value);
      const detail = point.reps ? ` (${formatTick(point.weight ?? point.value)} x ${point.reps})` : '';
      title.textContent = `${new Date(point.t).toLocaleDateString()} — ${shown}${detail}`;
      dot.appendChild(title);
      svg.appendChild(dot);
    }
  });

  const sortedTimes = [...times].sort((a, b) => a - b);
  drawXLabels(
    svg,
    area,
    sortedTimes.map((t) => ({ x: xScale(t), text: shortDate(t) }))
  );

  container.replaceChildren(svg);
  if (usable.length > 1) container.appendChild(legend(usable.map((s, i) => ({ label: s.label, color: s.color || colorFor(i) }))));
}

/** Simple vertical bars — used for weekly tonnage. */
export function barChart(container, { bars = [], formatValue, emptyMessage = 'Not enough data yet.' } = {}) {
  if (!bars.length) {
    drawEmpty(container, emptyMessage);
    return;
  }

  const svg = createCanvas();
  const area = plotArea();

  const ticks = niceTicks(0, Math.max(...bars.map((b) => b.value)), 5);
  const yScale = linearScale({
    domain: [ticks[0], ticks[ticks.length - 1]],
    range: [area.y0, area.y1],
  });

  drawYAxis(svg, area, ticks, yScale, formatValue);

  const slot = (area.x1 - area.x0) / bars.length;
  const barWidth = Math.min(48, slot * 0.7);

  bars.forEach((bar, i) => {
    const cx = area.x0 + slot * (i + 0.5);
    const y = yScale(bar.value);
    const rect = svgEl('rect', {
      x: cx - barWidth / 2,
      y,
      width: barWidth,
      height: Math.max(0, area.y0 - y),
      rx: 3,
      fill: bar.color || colorFor(0),
      class: 'chart-bar',
    });
    const title = svgEl('title');
    title.textContent = `${bar.label} — ${formatValue ? formatValue(bar.value) : formatTick(bar.value)}`;
    rect.appendChild(title);
    svg.appendChild(rect);
  });

  drawXLabels(
    svg,
    area,
    bars.map((bar, i) => ({ x: area.x0 + slot * (i + 0.5), text: bar.label }))
  );

  container.replaceChildren(svg);
}

/**
 * Stacked bars — sets per muscle group per week. `bars` is
 * `[{label, values: {group: count}}]`; `categories` fixes the stacking order
 * so a group keeps the same colour from week to week.
 */
export function stackedBarChart(container, { bars = [], categories = [], emptyMessage = 'Not enough data yet.' } = {}) {
  if (!bars.length || !categories.length) {
    drawEmpty(container, emptyMessage);
    return;
  }

  const svg = createCanvas();
  const area = plotArea();

  const totals = bars.map((bar) => categories.reduce((sum, c) => sum + (bar.values[c] || 0), 0));
  const ticks = niceTicks(0, Math.max(...totals), 5);
  const yScale = linearScale({
    domain: [ticks[0], ticks[ticks.length - 1]],
    range: [area.y0, area.y1],
  });

  drawYAxis(svg, area, ticks, yScale);

  const slot = (area.x1 - area.x0) / bars.length;
  const barWidth = Math.min(48, slot * 0.7);

  bars.forEach((bar, i) => {
    const cx = area.x0 + slot * (i + 0.5);
    let runningTotal = 0;
    categories.forEach((category, ci) => {
      const value = bar.values[category] || 0;
      if (value <= 0) return;
      const yTop = yScale(runningTotal + value);
      const yBottom = yScale(runningTotal);
      const rect = svgEl('rect', {
        x: cx - barWidth / 2,
        y: yTop,
        width: barWidth,
        height: Math.max(0, yBottom - yTop),
        fill: colorFor(ci),
        class: 'chart-bar',
      });
      const title = svgEl('title');
      title.textContent = `${bar.label} — ${category}: ${value} sets`;
      rect.appendChild(title);
      svg.appendChild(rect);
      runningTotal += value;
    });
  });

  drawXLabels(
    svg,
    area,
    bars.map((bar, i) => ({ x: area.x0 + slot * (i + 0.5), text: bar.label }))
  );

  container.replaceChildren(svg);
  container.appendChild(legend(categories.map((c, i) => ({ label: c, color: colorFor(i) }))));
}

/**
 * Calendar heatmap of training days, GitHub-style: one column per week, one
 * cell per day, shaded by how many sessions were logged.
 */
export function calendarHeatmap(container, { counts = new Map(), weeks = 26 } = {}) {
  const cell = 14;
  const gap = 3;
  const labelWidth = 26;
  const topLabel = 14;
  const width = labelWidth + weeks * (cell + gap);
  const height = topLabel + 7 * (cell + gap);

  const svg = svgEl('svg', {
    viewBox: `0 0 ${width} ${height}`,
    preserveAspectRatio: 'xMinYMid meet',
    class: 'chart chart-heatmap',
    role: 'img',
  });

  // Start on the Monday `weeks-1` weeks back so the final column is this week.
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const start = new Date(today);
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7) - (weeks - 1) * 7);

  const max = Math.max(1, ...counts.values());
  const dayNames = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

  dayNames.forEach((name, dayIndex) => {
    if (dayIndex % 2 !== 0) return; // every other row keeps it uncluttered
    const label = svgEl('text', {
      x: 0,
      y: topLabel + dayIndex * (cell + gap) + cell - 3,
      class: 'chart-axis-label',
    });
    label.textContent = name;
    svg.appendChild(label);
  });

  let lastMonth = -1;
  for (let week = 0; week < weeks; week += 1) {
    for (let day = 0; day < 7; day += 1) {
      const date = new Date(start);
      date.setDate(start.getDate() + week * 7 + day);
      if (date > today) continue;

      const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
      const count = counts.get(key) || 0;
      const intensity = count === 0 ? 0 : 0.35 + 0.65 * (count / max);

      const rect = svgEl('rect', {
        x: labelWidth + week * (cell + gap),
        y: topLabel + day * (cell + gap),
        width: cell,
        height: cell,
        rx: 3,
        class: count === 0 ? 'heat-cell heat-cell-empty' : 'heat-cell',
        'fill-opacity': count === 0 ? null : intensity.toFixed(2),
      });
      const title = svgEl('title');
      title.textContent = `${date.toLocaleDateString()} — ${count === 0 ? 'rest day' : `${count} session${count > 1 ? 's' : ''}`}`;
      rect.appendChild(title);
      svg.appendChild(rect);

      if (day === 0 && date.getMonth() !== lastMonth) {
        lastMonth = date.getMonth();
        const label = svgEl('text', {
          x: labelWidth + week * (cell + gap),
          y: 9,
          class: 'chart-axis-label',
        });
        label.textContent = date.toLocaleDateString(undefined, { month: 'short' });
        svg.appendChild(label);
      }
    }
  }

  container.replaceChildren(svg);
}

function legend(entries) {
  const list = document.createElement('ul');
  list.className = 'chart-legend';
  for (const entry of entries) {
    const item = document.createElement('li');
    const swatch = document.createElement('span');
    swatch.className = 'chart-swatch';
    swatch.style.background = entry.color;
    item.append(swatch, document.createTextNode(entry.label));
    list.appendChild(item);
  }
  return list;
}

function shortDate(ts) {
  return new Date(ts).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}
