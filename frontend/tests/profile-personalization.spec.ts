import { expect, test } from '@playwright/test';

const buildProfileDocument = (overrides: Partial<Record<string, unknown>> = {}) => ({
  profileId: '11111111-1111-4111-8111-111111111111',
  namespace: 'web',
  reference: 'demo',
  displayName: 'Demo User',
  locale: 'en',
  timezone: 'UTC',
  communicationMode: 'TEXT',
  habits: [],
  antiPatterns: [],
  workHours: {},
  metadata: {},
  identities: [],
  channels: [],
  roles: ['user'],
  updatedAt: '2025-01-01T00:00:00Z',
  version: 1,
  ...overrides,
});

test.describe('Personalization workflow', () => {
  test('saves profile and shows active banner/dev-link', async ({ page }) => {
    let profileDocument = buildProfileDocument();
let lastUpdatePayload: Record<string, unknown> | undefined;

    await page.route('**/api/profile/web/demo', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          headers: {
            'content-type': 'application/json',
            ETag: `W/"${profileDocument.version}"`,
          },
          body: JSON.stringify(profileDocument),
        });
        return;
      }

      const payload = route.request().postDataJSON();
      lastUpdatePayload = payload;
      profileDocument = buildProfileDocument({
        displayName: payload.displayName,
        locale: payload.locale,
        timezone: payload.timezone,
        habits: payload.habits,
        antiPatterns: payload.antiPatterns,
        updatedAt: '2025-02-02T00:00:00Z',
        version: profileDocument.version + 1,
      });

      await route.fulfill({
        status: 200,
        headers: {
          'content-type': 'application/json',
          ETag: `W/"${profileDocument.version}"`,
        },
        body: JSON.stringify(profileDocument),
      });
    });

    await page.route('**/api/profile/web/demo/audit', async (route) => {
      await route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify([
          {
            id: 'audit-1',
            eventType: 'profile_updated',
            source: 'profile_api',
            channel: 'web',
            metadata: { version: 2 },
            createdAt: '2025-02-02T00:00:00Z',
          },
        ]),
      });
    });

    await page.route('**/api/profile/web/demo/dev-link', async (route) => {
      await route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          code: 'DEVLINK01',
          profileId: profileDocument.profileId,
          namespace: 'web',
          reference: 'demo',
          channel: 'telegram',
          expiresAt: '2025-02-02T01:00:00Z',
        }),
      });
    });

    await page.goto('/profile');

    await page.getByRole('button', { name: /Загрузить/i }).click();
    await expect(page.getByLabel('Display name')).toHaveValue('Demo User');

    const devTokenPanel = page.locator('.dev-token-panel');
    await devTokenPanel.getByLabel('Dev token').fill('e2e-token');
    await devTokenPanel.getByRole('button', { name: /Сохранить token/i }).click();

    await page.getByLabel('Display name').fill('E2E Persona');
    await page.getByLabel('Locale').fill('ru');
    await page.getByLabel('Timezone').fill('Europe/Moscow');
    await page.getByLabel('Habits').fill('prefers summaries');
    await page.getByRole('button', { name: /^Сохранить$/i }).click();

    await expect(page.locator('.profile-status')).toHaveText(/Профиль сохранён/i);
    expect(lastUpdatePayload.displayName).toBe('E2E Persona');

    await page.getByRole('button', { name: 'Создать dev-link' }).click();
    await expect(page.locator('.profile-dev-link-result')).toContainText('DEVLINK01');

    await page.goto('/llm-chat');
    await expect(page.getByText('Активный профиль')).toBeVisible();
    await expect(page.getByText(/E2E Persona/)).toBeVisible();
    await expect(
      page.getByText('Dev session активна', { exact: false }),
    ).toBeVisible();
  });
});
