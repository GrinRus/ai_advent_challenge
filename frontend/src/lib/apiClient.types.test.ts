import { describe, expect, it } from 'vitest';
import { AgentDefinitionDetailsSchema, AgentVersionSchema } from './types/agent';
import {
  FlowDefinitionDetailsSchema,
  FlowDefinitionSummarySchema,
} from './types/flowDefinition';
import { FlowLaunchPreviewSchema } from './types/flowLaunch';

const agentVersionPayload = {
  id: '11111111-1111-4111-8aaa-222222222222',
  version: 4,
  status: 'PUBLISHED',
  providerId: 'openai',
  modelId: 'gpt-4o-mini',
  syncOnly: false,
  capabilities: [
    { capability: 'SEARCH', payload: { allow: true } },
  ],
};

describe('apiClient type guards', () => {
  it('parses agent version payloads', () => {
    const parsed = AgentVersionSchema.parse(agentVersionPayload);
    expect(parsed.version).toBe(4);
    expect(parsed.capabilities[0]?.capability).toBe('SEARCH');
  });

  it('parses agent definition details with versions', () => {
    const parsed = AgentDefinitionDetailsSchema.parse({
      id: 'aaaaaaaa-1111-4111-8aaa-333333333333',
      identifier: 'support-agent',
      displayName: 'Support Agent',
      description: null,
      active: true,
      versions: [agentVersionPayload],
    });
    expect(parsed.versions).toHaveLength(1);
  });

  it('validates flow definition summary/details responses', () => {
    const summary = FlowDefinitionSummarySchema.parse({
      id: 'bbbbbbbb-1111-4111-8aaa-444444444444',
      name: 'customer-onboarding',
      version: 2,
      status: 'PUBLISHED',
      active: true,
    });
    expect(summary.name).toBe('customer-onboarding');

    const details = FlowDefinitionDetailsSchema.parse({
      ...summary,
      definition: {
        title: 'Customer onboarding',
        startStepId: 'collect-profile',
        steps: [
          {
            id: 'collect-profile',
            name: 'Collect Profile',
            agentVersionId: 'cccccccc-1111-4111-8aaa-555555555555',
            prompt: 'Collect data',
            memoryReads: [],
            memoryWrites: [],
            transitions: {},
            maxAttempts: 1,
          },
        ],
      },
    });
    expect(details.definition.steps).toHaveLength(1);
  });

  it('guards flow launch preview payloads', () => {
    const preview = FlowLaunchPreviewSchema.parse({
      definitionId: 'dddddddd-1111-4111-8aaa-666666666666',
      definitionName: 'Onboarding',
      definitionVersion: 1,
      startStepId: 'start',
      steps: [
        {
          id: 'start',
          name: 'Start',
          prompt: 'Start conversation',
          agent: {
            agentVersionId: 'eeeeeeee-1111-4111-8aaa-777777777777',
            agentVersionNumber: 1,
            providerId: 'openai',
            modelId: 'gpt-4o-mini',
            syncOnly: false,
            pricing: {
              inputPer1KTokens: 0.001,
              outputPer1KTokens: 0.002,
              currency: 'USD',
            },
            defaultOptions: null,
            costProfile: null,
          },
          memoryReads: [],
          memoryWrites: [],
          transitions: {
            onSuccess: null,
            completeOnSuccess: true,
            onFailure: null,
            failFlowOnFailure: true,
          },
          maxAttempts: 1,
          estimate: {
            totalTokens: 50,
            promptTokens: 20,
            completionTokens: 30,
            totalCost: 0.0005,
            inputCost: 0.0002,
            outputCost: 0.0003,
            currency: 'USD',
          },
        },
      ],
      totalEstimate: {
        totalTokens: 50,
        promptTokens: 20,
        completionTokens: 30,
        totalCost: 0.0005,
        inputCost: 0.0002,
        outputCost: 0.0003,
        currency: 'USD',
      },
    });

    expect(preview.steps[0]?.estimate.totalTokens).toBe(50);
  });
});
