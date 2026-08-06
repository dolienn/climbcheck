// Landing page mobile check — overflow and mockup/podium layout at multiple widths.
// Usage: node e2e/landing-mobile-check.mjs <url>
import { chromium } from '@playwright/test';

const url = process.argv[2] || 'http://localhost:4200/';

const browser = await chromium.launch();
for (const w of [320, 360, 390, 768]) {
  const context = await browser.newContext({ viewport: { width: w, height: 700 } });
  const page = await context.newPage();
  const errors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text().slice(0, 120));
  });
  await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1200);

  const m = await page.evaluate(() => {
    const doc = document.documentElement;
    const rect = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { w: Math.round(r.width), left: Math.round(r.left), right: Math.round(r.right), vw: window.innerWidth };
    };
    const podium = document.querySelector('.mock-podium');
    const podiumDir = podium ? getComputedStyle(podium).flexDirection : null;
    const pods = [...document.querySelectorAll('.mock-pod')].map((p) => {
      const r = p.getBoundingClientRect();
      return { w: Math.round(r.width), left: Math.round(r.left), right: Math.round(r.right) };
    });
    const chips = [...document.querySelectorAll('.mock-chip')].map((c) => {
      const r = c.getBoundingClientRect();
      return { cls: c.className.split(' ').pop(), left: Math.round(r.left), right: Math.round(r.right), off: r.left < 0 || r.right > window.innerWidth };
    });
    const rows = [...document.querySelectorAll('.mock-row')].map((r) => {
      const rr = r.getBoundingClientRect();
      return { w: Math.round(rr.width), left: Math.round(rr.left), right: Math.round(rr.right), off: rr.left < 0 || rr.right > window.innerWidth };
    });
    const ctaRow = document.querySelector('.final-cta .cta-row');
    return {
      overflow: doc.scrollWidth > doc.clientWidth + 2,
      docScrollW: doc.scrollWidth,
      heroInner: rect('.hero-inner'),
      heroVisual: rect('.hero-visual'),
      mock: rect('.mock-window'),
      podiumDir,
      pods,
      chips,
      rows,
      ctaDir: ctaRow ? getComputedStyle(ctaRow).flexDirection : null,
      ctaBtns: [...document.querySelectorAll('.final-cta .btn-primary, .final-cta .btn-demo')].map((b) => Math.round(b.getBoundingClientRect().width))
    };
  });

  console.log('=== ' + w + 'px ===');
  console.log(JSON.stringify(m, null, 2));
  console.log(w + 'px errors:', errors.length ? errors[0] : 'none');

  if (w === 320) {
    await page.screenshot({ path: '/tmp/landing-320-top.png' });
    await page.locator('.mock-window').scrollIntoViewIfNeeded();
    await page.waitForTimeout(400);
    await page.screenshot({ path: '/tmp/landing-320-mock.png' });
  }
}
await browser.close();
console.log('done');
