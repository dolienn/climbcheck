// Screenshot live /demo (frontend already running on 4200) → docs/screenshots/demo.png
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = join(root, 'docs', 'screenshots');
const outFile = join(outDir, 'demo.png');

const base = process.env.E2E_BASE_URL || 'http://localhost:4200';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });

await page.goto(`${base}/demo`, { waitUntil: 'networkidle' });
// Wait for the LP chart lines + leaderboard rows to render (demo data loads async via delay).
await page.waitForSelector('.row-main, .leaderboard-row', { timeout: 15_000 }).catch(() => {});
await page.waitForSelector('svg', { timeout: 15_000 }).catch(() => {});
await page.waitForTimeout(1200);

mkdirSync(outDir, { recursive: true });
// Viewport shot (1440×900 @2x) — podium + leaderboard + top of the chart.
await page.screenshot({ path: outFile });
console.log(`Screenshot saved: ${outFile}`);

await browser.close();
