import { expect, test } from '@playwright/test';
import { mockProfileApi } from './utils';

const definitionsResponse = [
  {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'customer-onboarding',
    version: 2,
    status: 'PUBLISHED',
    active: true,
    description: 'Primary onboarding flow',
  },
];

const launchPreviewResponse = {
  definitionId: '11111111-1111-4111-8111-111111111111',
  definitionName: 'customer-onboarding',
  definitionVersion: 2,
  description: 'Primary onboarding flow',
  startStepId: 'collect-context',
  steps: [
    {
      id: 'collect-context',
      name: 'Collect Context',
      prompt: 'Collect customer information.',
      agent: {
        agentVersionId: 'aaaaaaaa-1111-4222-8333-444444444444',
        agentVersionNumber: 5,
        agentDefinitionId: 'bbbbbbbb-1111-4222-8333-444444444444',
        agentIdentifier: 'context-collector',
        agentDisplayName: 'Context Collector',
        providerType: 'OPENAI',
        providerId: 'openai',
        providerDisplayName: 'OpenAI',
        modelId: 'gpt-4o-mini',
        modelDisplayName: 'GPT-4o Mini',
        modelContextWindow: 128000,
        modelMaxOutputTokens: 4096,
        syncOnly: false,
        maxTokens: null,
        defaultOptions: null,
        costProfile: null,
        pricing: {
          inputPer1KTokens: 0.0025,
          outputPer1KTokens: 0.01,
          currency: 'USD',
        },
      },
      overrides: {
        temperature: 0.2,
        topP: null,
        maxTokens: 512,
      },
      memoryReads: [{ channel: 'customer-profile', limit: 5 }],
      memoryWrites: [
        { channel: 'shared-context', mode: 'AGENT_OUTPUT', payload: null },
      ],
      transitions: {
        onSuccess: 'summarise',
        completeOnSuccess: false,
        onFailure: null,
        failFlowOnFailure: true,
      },
      maxAttempts: 2,
      estimate: {
        promptTokens: 120,
        completionTokens: 512,
        totalTokens: 632,
        inputCost: 0.0003,
        outputCost: 0.00512,
        totalCost: 0.00542,
        currency: 'USD',
      },
    },
  ],
  totalEstimate: {
    promptTokens: 120,
    completionTokens: 512,
    totalTokens: 632,
    inputCost: 0.0003,
    outputCost: 0.00512,
    totalCost: 0.00542,
    currency: 'USD',
  },
};

test.describe('Flow launch workspace', () => {
  test('displays preview and starts flow', async ({ page }) => {
    await mockProfileApi(page);

    await page.route('**/api/flows/definitions**', async (route) => {
      await route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(definitionsResponse),
      });
    });

    await page.route('**/api/flows/definitions/*/launch-preview', async (route) => {
      await route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(launchPreviewResponse),
      });
    });

    let startPayload: unknown;
    await page.route('**/api/flows/**/start', async (route) => {
      startPayload = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          sessionId: '99999999-9999-4999-8999-999999999999',
          status: 'RUNNING',
          startedAt: '2025-01-01T10:00:00Z',
        }),
      });
    });

    await page.route('**/api/llm/sessions/**/usage', async (route) => {
      await route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ sessionId: 'preview', messages: [], totals: null }),
      });
    });

    await page.route('**/api/flows/*/snapshot', async (route) => {
      await route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          state: {
            sessionId: '99999999-9999-4999-8999-999999999999',
            status: 'RUNNING',
            stateVersion: 1,
            currentMemoryVersion: 0,
            currentStepId: null,
            startedAt: '2025-01-01T10:00:00Z',
            completedAt: null,
            flowDefinitionId: definitionsResponse[0].id,
            flowDefinitionVersion: 2,
          },
          events: [],
          nextSinceEventId: 0,
        }),
      });
    });

    await page.route('**/api/flows/99999999-9999-4999-8999-999999999999*', async (route) => {
      await route.fulfill({ status: 204, body: '' });
    });

    await page.goto('/flows/launch');

    await page.waitForSelector('.flow-launch-step', { state: 'visible' });

    await expect(
      page.getByRole('heading', { level: 3, name: /Шаги флоу/i }),
    ).toBeVisible();

    await expect(
      page.getByText(/Collect Context/),
    ).toBeVisible();
    await expect(
      page.getByText(/Context Collector/i),
    ).toBeVisible();

    await expect(page.locator('.flow-launch__metric-value').first()).toHaveText('632');

    await page
      .getByRole('textbox', { name: /Parameters/i })
      .fill('{ "customerId": "42" }');
    await page
      .getByRole('textbox', { name: /Shared context/i })
      .fill('{ "notes": ["vip"] }');

    await page.getByRole('button', { name: /Запустить флоу/i }).click();

    await page.waitForURL('**/flows/sessions/99999999-9999-4999-8999-999999999999');

    expect(startPayload).toEqual({
      parameters: { customerId: '42' },
      sharedContext: { notes: ['vip'] },
    });
  });
});
