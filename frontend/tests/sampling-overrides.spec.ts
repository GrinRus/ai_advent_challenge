import { expect, test } from '@playwright/test';

const providersFixture = {
  defaultProvider: 'openai',
  providers: [
    {
      id: 'openai',
      displayName: 'OpenAI',
      type: 'OPENAI',
      defaultModel: 'gpt-4o-mini',
      temperature: 0.45,
      topP: 0.88,
      maxTokens: 1600,
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

const structuredResponses = [
  {
    answer: {
      summary: 'Первый ответ с кастомными параметрами.',
      items: [],
    },
  },
  {
    answer: {
      summary: 'Ответ с дефолтными параметрами.',
      items: [],
    },
  },
];

test('sampling overrides are sent and can be reset to defaults', async ({ page }) => {
  await page.route('**/api/llm/providers', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(providersFixture),
    });
  });


  let syncCallCount = 0;

  await page.route('**/api/llm/chat/sync**', async (route) => {
    syncCallCount += 1;
    const body = route.request().postDataJSON() as Record<string, unknown>;

    if (syncCallCount === 1) {
      expect(body).toMatchObject({
        message: 'Запрос с overrides',
        provider: 'openai',
        model: 'gpt-4o-mini',
        options: {
          temperature: 0.3,
          topP: 0.7,
          maxTokens: 600,
        },
      });
    } else {
      expect(body).toMatchObject({
        message: 'Запрос по умолчанию',
        provider: 'openai',
        model: 'gpt-4o-mini',
      });
      expect(body).not.toHaveProperty('options');
    }

    await route.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'x-session-id': 'session-id',
        'x-new-session': syncCallCount === 1 ? 'true' : 'false',
      },
      body: JSON.stringify({
        requestId: '11111111-2222-3333-4444-555555555555',
        status: 'success',
        provider: {
          type: 'OPENAI',
          model: 'gpt-4o-mini',
        },
        answer: structuredResponses[syncCallCount - 1].answer,
      }),
    });
  });

  await page.goto('/llm-chat');
  await page.getByTestId('tab-structured').click();

  const temperatureInput = page.locator('#llm-chat-temperature');
  const topPInput = page.locator('#llm-chat-topP');
  const maxTokensInput = page.locator('#llm-chat-maxTokens');

  await temperatureInput.fill('0.3');
  await topPInput.fill('0.7');
  await maxTokensInput.fill('600');

  await page.locator('#llm-chat-structured-input').fill('Запрос с overrides');
  await page.getByRole('button', { name: /Отправ/ }).click();

  await expect(page.getByText(structuredResponses[0].answer.summary)).toBeVisible();
  await expect(page.getByTestId('structured-options').last()).toHaveText(
    'Temp 0.3 · TopP 0.7 · Max 600',
  );

  const resetButton = page.getByRole('button', { name: 'Сбросить к дефолтам' });
  await expect(resetButton).toBeEnabled();
  await resetButton.click();

  await expect(temperatureInput).toHaveValue('0.45');
  await expect(topPInput).toHaveValue('0.88');
  await expect(maxTokensInput).toHaveValue('1600');

  await page.locator('#llm-chat-structured-input').fill('Запрос по умолчанию');
  await page.getByRole('button', { name: /Отправ/ }).click();

  await expect(page.getByText(structuredResponses[1].answer.summary)).toBeVisible();
  await expect(page.getByTestId('structured-options').last()).toHaveText(
    'Temp 0.45 · TopP 0.88 · Max 1600',
  );

  expect(syncCallCount).toBe(2);
});
