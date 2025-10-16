import { expect, test } from '@playwright/test';

const providersFixture = {
  defaultProvider: 'openai',
  providers: [
    {
      id: 'openai',
      displayName: 'OpenAI',
      type: 'OPENAI',
      defaultModel: 'gpt-4o-mini',
      temperature: 0.2,
      topP: 0.9,
      maxTokens: 1024,
      models: [
        {
          id: 'gpt-4o-mini',
          displayName: 'GPT-4o Mini',
          tier: 'budget',
        },
      ],
    },
  ],
};

const structuredResponseFixture = {
  requestId: '11111111-2222-3333-4444-555555555555',
  status: 'success',
  provider: {
    type: 'OPENAI',
    model: 'gpt-4o-mini',
  },
  answer: {
    summary: 'Собрали план по внедрению фичи.',
    items: [
      {
        title: 'Аналитика запуска',
        details: 'Подготовьте метрики, определите KPI и SLA для команды.',
        tags: ['analytics', 'priority'],
      },
      {
        title: 'Релиз и коммуникации',
        details: 'Согласуйте планы с UX и маркетингом, обновите документацию.',
        tags: ['release', 'docs'],
      },
    ],
    confidence: 0.78,
  },
  usage: {
    promptTokens: 150,
    completionTokens: 220,
    totalTokens: 370,
  },
  latencyMs: 980,
  timestamp: '2024-12-18T12:00:00Z',
};

test('structured chat request renders structured response', async ({ page }) => {
  await page.route('**/api/llm/providers', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(providersFixture),
    });
  });

  await page.route('**/api/llm/chat/sync', async (route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>;
    expect(body).toMatchObject({
      message: 'Сформируй план запуска',
      provider: 'openai',
      model: 'gpt-4o-mini',
    });

    await route.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'x-session-id': 'test-session-id',
        'x-new-session': 'true',
      },
      body: JSON.stringify(structuredResponseFixture),
    });
  });

  await page.goto('/llm-chat');

  await page.getByTestId('tab-structured').click();

  const textarea = page.locator('#llm-chat-structured-input');
  await textarea.fill('Сформируй план запуска');

  await page.getByRole('button', { name: /Отправ/ }).click();

  await expect(
    page.getByTestId('structured-summary-text').first(),
  ).toHaveText(structuredResponseFixture.answer.summary);

  const firstItem = page.getByTestId('structured-item').first();
  await expect(firstItem.locator('.structured-item-title')).toHaveText(
    structuredResponseFixture.answer.items[0].title,
  );
  await expect(firstItem.locator('.structured-item-details')).toHaveText(
    structuredResponseFixture.answer.items[0].details,
  );

  await expect(
    page.getByText('Создан новый диалог (OpenAI · GPT-4o Mini)'),
  ).toBeVisible();
  await expect(page.getByText('Prompt')).toBeVisible();
  await expect(page.getByText('Total')).toBeVisible();

  await expect(
    page.locator('.structured-request-preview').first(),
  ).toContainText('Сформируй план запуска');

  await page.getByTestId('tab-stream').click();
  await expect(page.getByTestId('structured-response-card').first()).toBeVisible();
});
