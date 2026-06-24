// Mobile responsiveness check — measurements of key elements.
// Usage: node e2e/mobile-check.mjs <url>
import { chromium, devices } from '@playwright/test';

const url = process.argv[2] || 'http://localhost:4200/dashboard/38fd8cb4-4f57-45ed-8358-0a93f0a4dc38';

const browser = await chromium.launch();
const context = await browser.newContext({
  ...devices['iPhone 14'],
  viewport: { width: 390, height: 844 }
});
const page = await context.newPage();

const errors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') errors.push(msg.text().slice(0, 200));
});

await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 });
await page.waitForTimeout(1500);

const metrics = await page.evaluate(() => {
  const doc = document.documentElement;
  const rect = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { w: Math.round(r.width), left: Math.round(r.left), right: Math.round(r.right), vw: window.innerWidth };
  };
  const overflowX = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    return el.scrollWidth > el.clientWidth + 2 ? { sw: el.scrollWidth, cw: el.clientWidth } : null;
  };
  return {
    viewport: window.innerWidth,
    horizontalOverflow: doc.scrollWidth > doc.clientWidth + 2,
    header: rect('app-header'),
    search: rect('.search-input'),
    tabs: rect('.region-tabs'),
    podiumStage: rect('.podium .stage'),
    podiumSlots: [...document.querySelectorAll('.podium .slot')].map((s) => {
      const r = s.getBoundingClientRect();
      return { w: Math.round(r.width) };
    }),
    board: rect('.board'),
    firstRow: rect('.row'),
    cellPlayer: rect('.cell-player'),
    cellChange: rect('.cell-change'),
    rowMainOverflow: overflowX('.row-main'),
    statsOverflow: overflowX('.cell-stats'),
    chartCard: rect('.chart-card'),
    chartWrap: rect('.chart-wrap'),
    addBtn: rect('.header-actions, app-header button'),
    pagePadding: getComputedStyle(document.querySelector('.page') || document.body).paddingLeft
  };
});

console.log('=== MOBILE METRICS (390px) ===');
console.log(JSON.stringify(metrics, null, 2));
console.log('=== CONSOLE ERRORS ===');
console.log(errors.length ? errors.join('\n') : 'none');

await page.screenshot({ path: '/tmp/climbcheck-mobile-full.png', fullPage: true });
await page.locator('.board').first().scrollIntoViewIfNeeded();
await page.waitForTimeout(400);
await page.screenshot({ path: '/tmp/climbcheck-mobile-board.png' });
await browser.close();
console.log('Screenshots: /tmp/climbcheck-mobile-*.png');
