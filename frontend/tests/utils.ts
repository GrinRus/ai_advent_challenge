import type { Page } from '@playwright/test';

export const defaultProfileDocument = {
  profileId: '22222222-2222-4222-8222-222222222222',
  namespace: 'web',
  reference: 'demo',
  displayName: 'Demo Persona',
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
};

export async function mockProfileApi(page: Page): Promise<void> {
  await page.route('**/api/profile/web/demo**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        headers: {
          'content-type': 'application/json',
          ETag: `W/"${defaultProfileDocument.version}"`,
        },
        body: JSON.stringify(defaultProfileDocument),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        ETag: `W/"${defaultProfileDocument.version}"`,
      },
      body: JSON.stringify(defaultProfileDocument),
    });
  });

  await page.route('**/api/profile/web/demo/audit**', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify([]),
    });
  });

  await page.route('**/api/profile/web/demo/dev-link**', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        code: 'DEV-LINK',
        profileId: defaultProfileDocument.profileId,
        namespace: defaultProfileDocument.namespace,
        reference: defaultProfileDocument.reference,
        channel: 'telegram',
        expiresAt: '2025-01-01T01:00:00Z',
      }),
    });
  });
}
