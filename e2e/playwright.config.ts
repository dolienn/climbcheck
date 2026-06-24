import { defineConfig } from '@playwright/test';

/**
 * Stack brought up by run.sh (ports do not collide with the dev stack 4200/8081/5433):
 *   Riot mock  → http://localhost:9099
 *   backend    → http://localhost:8082
 *   frontend   → http://localhost:4201
 */
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:4201',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }]
});
