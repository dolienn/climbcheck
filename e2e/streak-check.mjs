// Streak badge verification on the live stack (localhost:4200).
// Usage: node e2e/streak-check.mjs <dashboard-url>
import { chromium } from '@playwright/test';

const url = process.argv[2] || 'http://localhost:4200/dashboard/38fd8cb4-4f57-45ed-8358-0a93f0a4dc38';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const errors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') errors.push(msg.text().slice(0, 150));
});

await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 });
// the streak eager-loader runs every 600ms per player — give it time
await page.waitForTimeout(25000);

const badges = await page.evaluate(() =>
  [...document.querySelectorAll('.streak-badge')].map((b) => ({
    text: b.textContent.trim(),
    cls: b.className.includes('streak-win') ? 'win' : b.className.includes('streak-loss') ? 'loss' : '?'
  }))
);
console.log('BADGE:', JSON.stringify(badges, null, 2));
console.log('console errors:', errors.length ? errors[0] : 'none');
await browser.close();
