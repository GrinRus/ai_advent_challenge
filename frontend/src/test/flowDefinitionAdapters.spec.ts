import { describe, expect, it } from 'vitest';
import {
  FLOW_BLUEPRINT_SCHEMA_VERSION,
  FlowDefinitionSchema,
} from '../lib/types/flowDefinition';
import {
  buildFlowDefinition,
  parseFlowDefinition,
} from '../lib/flowDefinitionForm';

describe('flow definition typed adapters', () => {
  const agentVersionId = '123e4567-e89b-12d3-a456-426614174000';

  it('normalizes missing collections for steps and memory', () => {
    const parsed = FlowDefinitionSchema.parse({
      schemaVersion: FLOW_BLUEPRINT_SCHEMA_VERSION,
      metadata: {},
      title: 'typed-demo',
      startStepId: 'step-1',
      syncOnly: true,
      launchParameters: [],
      memory: {},
      steps: [
        {
          id: 'step-1',
          name: 'Bootstrap',
          agentVersionId,
          prompt: 'Collect context',
          memoryReads: undefined,
          memoryWrites: undefined,
          transitions: {},
          maxAttempts: 1,
        },
      ],
    });

    expect(parsed.memory?.sharedChannels ?? []).toEqual([]);
    expect(parsed.steps[0].memoryReads).toEqual([]);
    expect(parsed.steps[0].memoryWrites).toEqual([]);
  });

  it('round-trips form adapters with memory retention values', () => {
    const blueprint = {
      schemaVersion: 2,
      metadata: { title: 'Retention demo', description: 'Wave 14' },
      startStepId: 'step-1',
      syncOnly: true,
      launchParameters: [],
      memory: {
        sharedChannels: [{ id: 'analytics', retentionVersions: 3, retentionDays: 14 }],
      },
      steps: [
        {
          id: 'step-1',
          name: 'Bootstrap',
          agentVersionId,
          prompt: 'Collect context',
          overrides: { temperature: 0.4 },
          memoryReads: [
            { channel: 'shared', limit: 5 },
            { channel: 'conversation', limit: 10 },
          ],
          memoryWrites: [{ channel: 'analytics', mode: 'AGENT_OUTPUT' }],
          transitions: { onSuccess: { complete: true } },
          maxAttempts: 2,
        },
      ],
    } as const;

    const formState = parseFlowDefinition(blueprint);
    expect(formState.sharedChannels).toEqual([
      { id: 'analytics', retentionVersions: '3', retentionDays: '14' },
    ]);
    expect(formState.steps[0].memoryReads).toEqual([
      { channel: 'shared', limit: '5' },
      { channel: 'conversation', limit: '10' },
    ]);

    formState.sharedChannels[0].retentionDays = '21';
    formState.steps[0].memoryReads[0].limit = '8';

    const rebuilt = buildFlowDefinition(formState);
    expect(rebuilt.schemaVersion).toBe(FLOW_BLUEPRINT_SCHEMA_VERSION);
    expect(rebuilt.memory?.sharedChannels).toEqual([
      { id: 'analytics', retentionVersions: 3, retentionDays: 21 },
    ]);
    expect(rebuilt.steps[0].memoryReads).toEqual([
      { channel: 'shared', limit: 8 },
      { channel: 'conversation', limit: 10 },
    ]);
    expect(rebuilt.steps[0].memoryWrites).toEqual([
      { channel: 'analytics', mode: 'AGENT_OUTPUT', payload: undefined },
    ]);
  });
});
