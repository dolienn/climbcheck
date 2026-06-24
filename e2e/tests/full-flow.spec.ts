import { expect, test } from '@playwright/test';

/**
 * Full e2e flow (backend + frontend + Riot mock):
 *  create dashboard → add player (EUNE by default) → check the ranking.
 */
test('full flow: create dashboard → add player → check ranking', async ({ page }) => {
  // 1. Landing → CTA "Create Dashboard" leads to /create
  await page.goto('/');
  await expect(page.getByRole('link', { name: 'Create Dashboard' })).toBeVisible();
  await page.getByRole('link', { name: 'Create Dashboard' }).click();
  await page.waitForURL(/\/create/);

  // 2. Create dashboard → redirect to /dashboard/{token}
  await page.getByRole('button', { name: 'Create Dashboard' }).click();
  await page.waitForURL(/\/dashboard\/[0-9a-f-]+/);

  // 3. Open the add-player modal
  await page.getByRole('button', { name: 'Add Player' }).click();
  const summonerInput = page.getByPlaceholder('e.g. Rekkles#EUW');
  await expect(summonerInput).toBeVisible();

  // Region defaults to EUNE (as requested)
  await expect(page.locator('select[name="region"]')).toHaveValue('EUNE');

  // 4. Add the player — the Riot mock returns PLATINUM I, 52 LP, 22W/20L
  await summonerInput.fill('TestPlayer#TST');
  await page.getByRole('button', { name: 'Add Summoner' }).click();

  // 5. Toast confirms the addition
  await expect(page.getByText('Added TestPlayer#TST to the leaderboard!')).toBeVisible();

  // 6. The ranking shows the player with league-v4 data
  await expect(page.getByText('TestPlayer').first()).toBeVisible();
  await expect(page.getByText('PLATINUM I').first()).toBeVisible();
  await expect(page.getByText(/52 LP/).first()).toBeVisible();

  // 7. Remove the player — two-step confirmation (1st click arms, 2nd removes)
  const removeBtn = page.locator('.remove-btn').first();
  await removeBtn.click();
  await expect(removeBtn).toHaveClass(/remove-armed/);
  await removeBtn.click();

  // 8. Toast confirms the removal and the ranking is empty
  await expect(page.getByText('TestPlayer removed from the dashboard')).toBeVisible();
  await expect(page.locator('.row-main')).toHaveCount(0);
});

test('demo: /demo shows a sample ranking and the player modal (no backend)', async ({ page }) => {
  // The demo is a fully client-side route with a mock service — works without backend and Riot API.
  await page.goto('/demo');
  await expect(page.getByText('LIVE DEMO')).toBeVisible();
  await expect(page.getByText('Faker#HideOnBush').first()).toBeVisible();

  // Clicking a player opens the modal with matches (mock data)
  await page.getByText('Faker#HideOnBush').first().click();
  await expect(page.locator('.modal')).toBeVisible();
  await expect(page.getByText('Recent Matches')).toBeVisible();
  await expect(page.getByText('VICTORY').first()).toBeVisible();
});

const BACKEND_URL = process.env.E2E_BACKEND_URL || 'http://localhost:8082';

test('viewer without an admin token does not see management buttons and gets 401', async ({ page, request }) => {
  // 1. Create a dashboard via API → we get token + adminToken
  const created = await request.post(`${BACKEND_URL}/api/dashboards`);
  expect(created.status()).toBe(201);
  const { token, adminToken } = await created.json();
  expect(token).toBeTruthy();
  expect(adminToken).toBeTruthy();

  // 2. Viewer (no localStorage, link only) sees the ranking, but no buttons
  await page.goto(`/dashboard/${token}`);
  await expect(page.getByText('No players found matching current filters.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add Player' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Manage Link' })).toHaveCount(0);

  // 3. Mutation without X-Admin-Token → 401
  const mutation = await request.post(
    `${BACKEND_URL}/api/dashboards/${token}/players`,
    {
      data: { region: 'EUNE', gameName: 'Test', tagLine: 'TST' }
    }
  );
  expect(mutation.status()).toBe(401);

  // 4. Mutation with X-Admin-Token → add attempt (Riot mock returns data) → 201
  const authorized = await request.post(
    `${BACKEND_URL}/api/dashboards/${token}/players`,
    {
      headers: { 'X-Admin-Token': adminToken },
      data: { region: 'EUNE', gameName: 'Test', tagLine: 'TST' }
    }
  );
  expect(authorized.status()).toBe(201);

  // 5. GET of the dashboard does NOT expose the adminToken of a new dashboard
  const view = await request.get(`${BACKEND_URL}/api/dashboards/${token}`);
  const body = await view.json();
  expect(body.adminToken).toBeNull();
});

test('Riot ID format validation shows a readable error', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: 'Create Dashboard' }).click();
  await page.waitForURL(/\/create/);
  await page.getByRole('button', { name: 'Create Dashboard' }).click();
  await page.waitForURL(/\/dashboard\/[0-9a-f-]+/);

  await page.getByRole('button', { name: 'Add Player' }).click();
  await page.getByPlaceholder('e.g. Rekkles#EUW').fill('x');
  await page.getByRole('button', { name: 'Add Summoner' }).click();

  await expect(page.getByText('Invalid gameName: 3-16 characters allowed (letters, digits, space, _ . -)')).toBeVisible();
});
