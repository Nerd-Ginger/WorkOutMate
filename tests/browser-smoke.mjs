/**
 * End-to-end smoke test for the app layer, driven in Chromium.
 *
 * The Kotlin shell can't be exercised here, but everything above it can:
 * IndexedDB schema and migrations, the routine builder, the session logger, PR
 * detection, charts, and backup export/import. Crucially it covers the
 * reload-and-still-there check, which is the failure mode that would hurt most
 * in the real app.
 *
 * Run with:  node tests/browser-smoke.mjs
 */

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync, readdirSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';

const require = createRequire(import.meta.url);

/**
 * Playwright may be a local dependency (CI, after `npx playwright install`) or
 * a global install (this dev container). Try the normal resolution first and
 * fall back to the global module root.
 */
function loadPlaywright() {
  try {
    return require('playwright');
  } catch {
    const globalRoot =
      process.env.NPM_GLOBAL_ROOT ||
      (() => {
        try {
          return execSync('npm root -g', { encoding: 'utf8' }).trim();
        } catch {
          return '';
        }
      })();
    if (!globalRoot) throw new Error('Playwright is not installed. Run: npx playwright install chromium');
    return require(join(globalRoot, 'playwright'));
  }
}

const { chromium } = loadPlaywright();

const ROOT = fileURLToPath(new URL('../app/src/main/assets/www/', import.meta.url));

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
};

let failures = 0;
let checks = 0;

function check(label, condition, detail = '') {
  checks += 1;
  if (condition) {
    console.log(`  ok  ${label}`);
  } else {
    failures += 1;
    console.error(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`);
  }
}

/**
 * Finds the Chromium that Playwright installed. Returns undefined so
 * Playwright falls back to its own resolution when nothing matches.
 */
function findChromium() {
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH || '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const candidates = readdirSync(root)
    .filter((name) => name.startsWith('chromium-'))
    .sort()
    .reverse()
    .map((name) => join(root, name, 'chrome-linux', 'chrome'));
  return candidates.find((path) => existsSync(path));
}

function startServer() {
  const server = createServer(async (req, res) => {
    try {
      const urlPath = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
      // The app ships no favicon (the launcher icon is native); answer the
      // browser's automatic request so it doesn't log a spurious 404.
      if (urlPath === '/favicon.ico') {
        res.writeHead(204).end();
        return;
      }
      const relative = normalize(urlPath === '/' ? '/index.html' : urlPath).replace(/^(\.\.[/\\])+/, '');
      const filePath = join(ROOT, relative);
      if (!filePath.startsWith(ROOT)) {
        res.writeHead(403).end('forbidden');
        return;
      }
      const body = await readFile(filePath);
      res.writeHead(200, { 'content-type': MIME[extname(filePath)] || 'application/octet-stream' });
      res.end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

async function main() {
  const { server, port } = await startServer();
  const base = `http://127.0.0.1:${port}/index.html`;

  // PLAYWRIGHT_BROWSERS_PATH holds versioned directories, so resolve the
  // binary rather than hard-coding a version that will drift.
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || findChromium(),
    args: ['--no-sandbox'],
  });

  const context = await browser.newContext({ viewport: { width: 412, height: 900 } });
  const page = await context.newPage();

  const consoleErrors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', (error) => consoleErrors.push(String(error)));

  try {
    // ---- Boot and seeding ----
    console.log('\nBoot');
    await page.goto(base);
    await page.waitForFunction(() => globalThis.WM && globalThis.WM.ready === true, { timeout: 15000 });
    check('app reports ready', true);

    const exerciseCount = await page.evaluate(async () => {
      const { store } = await import('./js/app.js');
      return store.exercises.length;
    });
    check('exercise library seeded on first run', exerciseCount > 50, `got ${exerciseCount}`);

    // ---- Build a routine with a superset, straight through the data layer ----
    console.log('\nRoutine with a superset');
    await page.evaluate(async () => {
      const { db, reload, store, render } = await import('./js/app.js');
      const { makeRoutine, makeBlock, makeBlockItem } = await import('./js/model.js');

      const bench = store.exercises.find((e) => e.name === 'Barbell Bench Press');
      const row = store.exercises.find((e) => e.name === 'Barbell Row');
      const curl = store.exercises.find((e) => e.name === 'Dumbbell Curl');

      const routine = makeRoutine({
        name: 'Smoke Day',
        blocks: [
          makeBlock({
            type: 'single',
            restSec: 150,
            items: [makeBlockItem({ exerciseId: bench.id, targetSets: 2, targetRepsMin: 5, targetRepsMax: 5 })],
          }),
          makeBlock({
            type: 'superset',
            restSec: 90,
            items: [
              makeBlockItem({ exerciseId: row.id, targetSets: 1, targetRepsMin: 8, targetRepsMax: 10 }),
              makeBlockItem({ exerciseId: curl.id, targetSets: 1, targetRepsMin: 10, targetRepsMax: 12 }),
            ],
          }),
        ],
      });
      await db.put('routines', routine);
      await reload(['routines']);
      render();
    });

    await page.getByRole('button', { name: 'Routines', exact: true }).click();
    check('routine appears in the list', await page.getByText('Smoke Day').first().isVisible());
    check(
      'superset is described in the summary',
      (await page.getByText(/1 superset/).count()) > 0
    );

    // ---- Log a session ----
    console.log('\nSession logging');
    await page.getByRole('button', { name: 'Train', exact: true }).click();
    await page.getByRole('button', { name: 'Start', exact: true }).first().click();
    await page.waitForSelector('.set-row');

    const plannedSets = await page.locator('.set-row').count();
    check('planned sets are pre-created from the routine', plannedSets === 4, `got ${plannedSets}`);
    check('superset block is marked', (await page.locator('.block-superset').count()) === 1);

    // Fill in the two bench sets and complete them.
    for (let i = 0; i < 2; i += 1) {
      const row = page.locator('.set-row').nth(i);
      await row.locator('input[type=number]').nth(0).fill('100');
      await row.locator('input[type=number]').nth(1).fill('5');
      await row.locator('input[type=checkbox]').check();
      await page.waitForTimeout(120);
    }

    const restVisible = await page.locator('.rest-bar-active').count();
    check('rest timer starts when a set is completed', restVisible === 1);

    // Regression guard: weight and reps are edited back to back, so a
    // read-modify-write against the database loses one of them. Both must
    // survive, in memory and on disk.
    const bothFieldsKept = await page.evaluate(async () => {
      const { db } = await import('./js/app.js');
      const rows = (await db.getAll('sets')).filter((s) => s.completed && !s.deletedAt);
      return rows.map((s) => ({ weight: s.weight, reps: s.reps }));
    });
    check(
      'weight and reps both persist when edited in quick succession',
      bothFieldsKept.length === 2 && bothFieldsKept.every((s) => s.weight === 100 && s.reps === 5),
      JSON.stringify(bothFieldsKept)
    );

    // The remaining superset sets.
    for (const index of [2, 3]) {
      const row = page.locator('.set-row').nth(index);
      await row.locator('input[type=number]').nth(0).fill('40');
      await row.locator('input[type=number]').nth(1).fill('10');
      await row.locator('input[type=checkbox]').check();
      await page.waitForTimeout(120);
    }

    await page.getByRole('button', { name: 'Finish workout' }).click();
    await page.waitForTimeout(400);

    const afterFinish = await page.evaluate(async () => {
      const { store } = await import('./js/app.js');
      return {
        finished: store.sessions.filter((s) => s.finishedAt && !s.deletedAt).length,
        completedSets: store.sets.filter((s) => s.completed && !s.deletedAt).length,
        prs: store.prs.filter((p) => !p.deletedAt).length,
      };
    });
    check('session was saved as finished', afterFinish.finished === 1, JSON.stringify(afterFinish));
    check('four sets were logged', afterFinish.completedSets === 4, JSON.stringify(afterFinish));
    check('first-ever session produced PRs', afterFinish.prs > 0, `${afterFinish.prs} records`);

    // ---- The check that matters: does it survive a reload? ----
    console.log('\nPersistence across a reload');
    await page.reload();
    await page.waitForFunction(() => globalThis.WM && globalThis.WM.ready === true, { timeout: 15000 });

    const afterReload = await page.evaluate(async () => {
      const { store } = await import('./js/app.js');
      return {
        sessions: store.sessions.filter((s) => s.finishedAt && !s.deletedAt).length,
        sets: store.sets.filter((s) => s.completed && !s.deletedAt).length,
        routines: store.routines.filter((r) => !r.deletedAt).length,
      };
    });
    check('session survived the reload', afterReload.sessions === 1, JSON.stringify(afterReload));
    check('sets survived the reload', afterReload.sets === 4, JSON.stringify(afterReload));
    check('routine survived the reload', afterReload.routines === 1, JSON.stringify(afterReload));

    // ---- Charts ----
    console.log('\nProgress charts');
    await page.getByRole('button', { name: 'Progress', exact: true }).click();
    await page.waitForTimeout(300);
    const svgCount = await page.locator('#view svg.chart').count();
    check('progress screen renders charts', svgCount >= 3, `${svgCount} charts`);
    check(
      'weekly volume chart drew bars',
      (await page.locator('#view rect.chart-bar').count()) > 0
    );
    check(
      'frequency heatmap drew cells',
      (await page.locator('#view rect.heat-cell').count()) > 0
    );

    // ---- Backup round trip ----
    console.log('\nBackup round trip');
    const roundTrip = await page.evaluate(async () => {
      const { db } = await import('./js/app.js');
      const { buildBackup, validateBackup, mergeBackup } = await import('./js/merge.js');

      const stores = await db.exportStores();
      const payload = buildBackup({ stores, appVersion: 'test', exportedAt: Date.now(), dbVersion: 1 });
      const text = JSON.stringify(payload);

      const parsed = validateBackup(text);
      if (!parsed.ok) return { ok: false, errors: parsed.errors };

      // Wipe, then restore from the backup alone.
      await db.replaceAll({ sessions: [], sets: [], routines: [], exercises: [], prs: [], measurements: [], settings: [] });
      const afterWipe = (await db.getAll('sessions')).length;

      await db.replaceAll(parsed.backup.stores);
      const restored = (await db.getAll('sessions')).filter((s) => s.finishedAt).length;
      const restoredSets = (await db.getAll('sets')).filter((s) => s.completed).length;

      // Merging the same backup again must be a no-op.
      const current = await db.exportStores();
      const merged = mergeBackup(current, parsed.backup);

      return {
        ok: true,
        afterWipe,
        restored,
        restoredSets,
        addedOnSecondMerge: merged.stats.sets.added,
      };
    });

    check('backup validates', roundTrip.ok, JSON.stringify(roundTrip.errors || []));
    check('wipe emptied the database', roundTrip.afterWipe === 0);
    check('restore brought the session back', roundTrip.restored === 1, JSON.stringify(roundTrip));
    check('restore brought the sets back', roundTrip.restoredSets === 4, JSON.stringify(roundTrip));
    check('re-merging the same backup adds nothing', roundTrip.addedOnSecondMerge === 0);

    // ---- Rejecting bad input ----
    console.log('\nInvalid input is refused');
    const rejection = await page.evaluate(async () => {
      const { validateBackup } = await import('./js/merge.js');
      const { validatePlan } = await import('./js/validate.js');
      return {
        foreignFile: validateBackup('{"schema":"someone.else","stores":{}}').ok,
        brokenJson: validateBackup('}{').ok,
        badPlan: validatePlan('{"schema":"workoutmate.routine-plan.v1","routines":[]}').ok,
      };
    });
    check('a foreign backup file is refused', rejection.foreignFile === false);
    check('malformed JSON is refused', rejection.brokenJson === false);
    check('an empty routine plan is refused', rejection.badPlan === false);

    // ---- Routine builder, copy/paste path ----
    console.log('\nRoutine builder (no API key)');
    const builderImport = await page.evaluate(async () => {
      const { store } = await import('./js/app.js');
      const { loadSkill, buildPrompt, parseAndPrepare } = await import('./js/skill.js');
      const skill = await loadSkill();

      const answers = {
        goal: 'hypertrophy', experience: 'intermediate', daysPerWeek: '3',
        sessionLength: '60', equipment: ['full_gym'], limitations: '',
        split: 'upper_lower', units: 'kg', extra: '',
      };
      const promptA = buildPrompt(skill, answers);
      const promptB = buildPrompt(skill, answers);

      const reply = JSON.stringify({
        schema: 'workoutmate.routine-plan.v1',
        programName: 'Test Program',
        summary: 'A test.',
        progressionNotes: 'Add weight.',
        routines: [{
          name: 'Day 1', notes: '',
          blocks: [{
            type: 'superset', restSec: 90,
            items: [
              { exerciseName: 'Barbell Bench Press', muscleGroups: ['chest'], equipment: 'barbell', targetSets: 3, targetRepsMin: 8, targetRepsMax: 10, targetRpe: 8, notes: '' },
              { exerciseName: 'Totally Invented Movement', muscleGroups: ['back'], equipment: 'cable', targetSets: 3, targetRepsMin: 8, targetRepsMax: 10, targetRpe: 8, notes: '' },
            ],
          }],
        }],
      });

      const prepared = parseAndPrepare(reply, store.exercises);
      return {
        deterministic: promptA === promptB,
        questionCount: skill.questions.length,
        ok: prepared.ok,
        errors: prepared.errors,
        matched: prepared.records ? prepared.records.matchedCount : -1,
        created: prepared.records ? prepared.records.newExercises.length : -1,
        supersetItems: prepared.records ? prepared.records.routines[0].blocks[0].items.length : -1,
      };
    });

    check('the same answers render an identical prompt', builderImport.deterministic);
    check('the skill asks its full question set', builderImport.questionCount === 9, `${builderImport.questionCount} questions`);
    check('a valid plan is accepted', builderImport.ok, JSON.stringify(builderImport.errors || []));
    check('known exercises are matched to the library', builderImport.matched === 1, `matched ${builderImport.matched}`);
    check('unknown exercises are queued for creation', builderImport.created === 1, `created ${builderImport.created}`);
    check('the superset survived the import', builderImport.supersetItems === 2);

    // ---- No unexpected console noise ----
    console.log('\nConsole');
    check('no console errors during the run', consoleErrors.length === 0, consoleErrors.join(' | '));
  } finally {
    await browser.close();
    server.close();
  }

  console.log(`\n${checks - failures}/${checks} checks passed`);
  if (failures) {
    console.error(`${failures} check(s) failed`);
    process.exit(1);
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
