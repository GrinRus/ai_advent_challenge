import { describe, expect, it } from 'vitest';
import { FlowLaunchPreviewSchema } from './flowLaunch';

const basePreviewPayload = {
  definitionId: '11111111-1111-4111-8aaa-111111111111',
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
        agentVersionId: 'aaaaaaaa-1111-4111-8aaa-222222222222',
        agentVersionNumber: 5,
        agentDefinitionId: 'bbbbbbbb-1111-4111-8aaa-333333333333',
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
      memoryReads: [{ channel: 'shared', limit: 5 }],
      memoryWrites: [{ channel: 'shared', mode: 'AGENT_OUTPUT', payload: null }],
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
} satisfies Record<string, unknown>;

describe('FlowLaunchPreviewSchema', () => {
  it('parses a valid launch preview payload', () => {
    const parsed = FlowLaunchPreviewSchema.parse(basePreviewPayload);
    expect(parsed.steps).toHaveLength(1);
    expect(parsed.steps[0]?.agent.pricing.currency).toBe('USD');
  });

  it('rejects payloads without steps', () => {
    expect(() =>
      FlowLaunchPreviewSchema.parse({
        ...basePreviewPayload,
        steps: [],
      }),
    ).toThrow(/steps/);
  });
});
