import { describe, expect, it } from 'vitest';
import {
  FlowDefinitionDetailsSchema,
  FlowDefinitionHistoryEntrySchema,
} from './flowDefinition';

describe('FlowDefinition schemas', () => {
  it('parses flow definition details with blueprint metadata', () => {
    const details = FlowDefinitionDetailsSchema.parse({
      id: '22222222-1111-4111-8aaa-999999999999',
      name: 'typed-flow',
      version: 3,
      status: 'DRAFT',
      active: false,
      description: 'Typed flow',
      updatedBy: 'tester',
      updatedAt: '2024-07-15T10:00:00Z',
      definition: {
        schemaVersion: 2,
        metadata: {
          title: 'Typed Flow',
          description: 'Blueprint with schema version 2',
        },
        startStepId: 'start',
        syncOnly: true,
        steps: [
          {
            id: 'start',
            name: 'Start',
            agentVersionId: 'aaaaaaaa-1111-4111-8aaa-333333333333',
            prompt: 'Begin',
            memoryReads: [],
            memoryWrites: [],
            transitions: {},
            maxAttempts: 1,
          },
        ],
      },
    });

    expect(details.definition.schemaVersion).toBe(2);
    expect(details.definition.steps).toHaveLength(1);
  });

  it('parses history entries with blueprint schema version', () => {
    const history = FlowDefinitionHistoryEntrySchema.parse({
      id: 7,
      version: 2,
      status: 'PUBLISHED',
      blueprintSchemaVersion: 2,
      definition: {
        schemaVersion: 2,
        metadata: {
          title: 'v2 Blueprint',
        },
        startStepId: 'initial',
        syncOnly: true,
        steps: [
          {
            id: 'initial',
            name: 'Initial Step',
            agentVersionId: 'bbbbbbbb-1111-4111-8aaa-444444444444',
            prompt: 'Init',
            memoryReads: [],
            memoryWrites: [],
            transitions: {},
            maxAttempts: 1,
          },
        ],
      },
    });

    expect(history.blueprintSchemaVersion).toBe(2);
  });
});
