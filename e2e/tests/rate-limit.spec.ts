import { expect, test } from '@playwright/test';

/**
 * Rate limiting of our own API: many POST /api/dashboards from one IP must end
 * with a 429, Retry-After and X-RateLimit-* headers.
 *
 * The backend in e2e starts with APP_RATE_LIMIT_DASHBOARD_CREATE_MAX=3 (run.sh),
 * so the 3rd dashboard creation from this IP succeeds and the 4th is a 429.
 *
 * The test hits the backend directly (:8082) with X-Forwarded-For = a fixed, unique IP
 * — this isolates the rate-limit budget from the other e2e tests,
 * which go through the frontend proxy (without X-Forwarded-For → remoteAddr).
 */
const TEST_IP = '203.0.113.10';
const BACKEND_URL = process.env.E2E_BACKEND_URL || 'http://localhost:8082';
const LIMIT = Number(process.env.RATE_LIMIT_DASHBOARD_CREATE_MAX || 3);

test('rate limit: many POST /api/dashboards from one IP → 429 with Retry-After', async ({ request }) => {
  // 1. Requests within the limit → 201, with decreasing X-RateLimit-Remaining
  for (let i = 0; i < LIMIT; i++) {
    const response = await request.post(`${BACKEND_URL}/api/dashboards`, {
      headers: { 'X-Forwarded-For': TEST_IP }
    });
    expect(response.status(), `POST ${i + 1} should pass (within the limit)`).toBe(201);
    expect(response.headers()['x-ratelimit-limit']).toBe(String(LIMIT));
    expect(response.headers()['x-ratelimit-remaining']).toBe(String(LIMIT - i - 1));
    expect(response.headers()['x-ratelimit-reset']).toBeTruthy();
  }

  // 2. Exceeding the limit → 429 with the full header set
  const blocked = await request.post(`${BACKEND_URL}/api/dashboards`, {
    headers: { 'X-Forwarded-For': TEST_IP }
  });
  expect(blocked.status()).toBe(429);
  expect(blocked.headers()['retry-after']).toBeTruthy();
  expect(Number(blocked.headers()['retry-after'])).toBeGreaterThan(0);
  expect(blocked.headers()['x-ratelimit-limit']).toBe(String(LIMIT));
  expect(blocked.headers()['x-ratelimit-remaining']).toBe('0');
  expect(blocked.headers()['x-ratelimit-reset']).toBeTruthy();
});

test('rate limit: separate budget for another IP', async ({ request }) => {
  // Another IP has its own pool — even after TEST_IP is exhausted, a fresh IP passes
  const freshIp = '203.0.113.11';
  const response = await request.post(`${BACKEND_URL}/api/dashboards`, {
    headers: { 'X-Forwarded-For': freshIp }
  });
  expect(response.status()).toBe(201);
  expect(response.headers()['x-ratelimit-remaining']).toBe(String(LIMIT - 1));
});
