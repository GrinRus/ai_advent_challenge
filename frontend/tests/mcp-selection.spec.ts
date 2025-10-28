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
          syncEnabled: true,
        },
      ],
    },
  ],
};

const mcpCatalogFixture = {
  servers: [
    {
      id: 'perplexity',
      displayName: 'Perplexity MCP',
      description: 'Поисковый сервер Perplexity.',
      tags: ['search', 'research'],
      status: 'UP',
      securityPolicy: 'external-readonly',
      tools: [
        {
          code: 'perplexity_search',
          displayName: 'Perplexity Search',
          description: 'Быстрый поиск по вебу.',
          mcpToolName: 'perplexity_search',
          schemaVersion: 1,
          available: true,
        },
        {
          code: 'perplexity_deep_research',
          displayName: 'Perplexity Deep Research',
          description: 'Глубокое исследование темы.',
          mcpToolName: 'perplexity_deep_research',
          schemaVersion: 1,
          available: true,
        },
      ],
    },
  ],
};

const syncResponseFixture = {
  requestId: '44444444-5555-6666-7777-888888888888',
  content: 'Ответ подготовлен с использованием инструментов Perplexity.',
  provider: {
    type: 'OPENAI',
    model: 'gpt-4o-mini',
  },
  tools: ['perplexity_search', 'perplexity_deep_research'],
  usage: {
    promptTokens: 60,
    completionTokens: 180,
    totalTokens: 240,
  },
  cost: {
    input: 0.0009,
    output: 0.0027,
    total: 0.0036,
    currency: 'USD',
  },
  latencyMs: 640,
  timestamp: '2025-01-15T08:00:00Z',
};

test('sync request includes selected MCP tools and renders tool badges', async ({ page }) => {
  await page.route('**/api/llm/providers', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(providersFixture),
    });
  });

  await page.route('**/api/mcp/catalog', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(mcpCatalogFixture),
    });
  });

  await page.route('**/api/mcp/events', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'text/event-stream' },
      body: '',
    });
  });

  await page.route('**/api/llm/sessions/**/usage', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sessionId: 'sync-session', messages: [], totals: null }),
    });
  });

  let syncPayload: Record<string, unknown> | null = null;
  await page.route('**/api/llm/chat/sync**', async (route) => {
    syncPayload = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'x-session-id': 'sync-session',
        'x-new-session': 'true',
      },
      body: JSON.stringify(syncResponseFixture),
    });
  });

  await page.goto('/llm-chat');

  await expect(page.locator('.llm-chat-mcp-title')).toHaveText('MCP инструменты');

  const serverToggle = page.locator('label.llm-chat-mcp-server', {
    hasText: 'Perplexity MCP',
  });
  await expect(serverToggle).toBeVisible();
  await serverToggle.click();

  await expect(page.locator('.llm-chat-mcp-summary')).toContainText('1 серв.');

  await page.getByTestId('tab-sync').click();

  await page.locator('#llm-chat-sync-input').fill('Подготовь краткий обзор обновлений.');
  await page.getByRole('button', { name: 'Отправить' }).click();

  await expect.poll(() => syncPayload).not.toBeNull();

  const requested =
    ((syncPayload?.requestedToolCodes as string[] | undefined) ?? []).slice().sort();
  expect(syncPayload?.mode).toBe('research');
  expect(requested).toEqual(['perplexity_deep_research', 'perplexity_search']);

  await expect(
    page.locator('.llm-chat-tool-badge-label', { hasText: 'Perplexity Search' }).first(),
  ).toBeVisible();
});
