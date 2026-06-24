// Responsiveness check at 320/360/390/768px — overflow, header, podium, rows.
import { chromium } from '@playwright/test';

const url = process.argv[2] || 'http://localhost:4200/dashboard/38fd8cb4-4f57-45ed-8358-0a93f0a4dc38';

const browser = await chromium.launch();
for (const w of [320, 360, 390, 768]) {
  const context = await browser.newContext({ viewport: { width: w, height: 844 } });
  const page = await context.newPage();
  const errors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text().slice(0, 120));
  });
  await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1200);

  const m = await page.evaluate(() => {
    const doc = document.documentElement;
    const actions = document.querySelector('app-header .header-actions, app-header nav, app-header .actions, app-header header > div');
    const btns = actions ? [...actions.querySelectorAll('button')].map((b) => Math.round(b.getBoundingClientRect().width)) : null;
    const stage = document.querySelector('.podium .stage');
    const firstSlot = document.querySelector('.podium .slot-first');
    const boardRows = [...document.querySelectorAll('.row-main')].slice(0, 2).map((r) => {
      const d = getComputedStyle(r).flexDirection;
      const cells = [...r.children]
        .filter((c) => c.className.includes('cell-'))
        .map((c) => ({ c: c.className.slice(0, 18), w: Math.round(c.getBoundingClientRect().width) }));
      return { d, cells };
    });
    return {
      overflow: doc.scrollWidth > doc.clientWidth + 2,
      docScrollW: doc.scrollWidth,
      actionsBtns: btns,
      stageDir: stage ? getComputedStyle(stage).flexDirection : null,
      firstSlotOrder: firstSlot ? getComputedStyle(firstSlot).order : null,
      boardRows
    };
  });
  console.log(w + 'px:', JSON.stringify(m));
  console.log(w + 'px errors:', errors.length ? errors[0] : 'none');
  await context.close();
}
await browser.close();
