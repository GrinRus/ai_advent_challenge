import { describe, expect, it } from 'vitest';
import { buildFlowDefinition, parseFlowDefinition } from './flowDefinitionForm';
import type { FlowDefinitionFormState } from './flowDefinitionForm';
import type { FlowDefinitionDraft } from './types/flowDefinition';

describe('flowDefinitionForm adapters', () => {
  it('keeps passthrough metadata and step overrides when parsing/building', () => {
    const form: FlowDefinitionFormState = {
      title: 'Customer onboarding',
      description: 'Typed flow',
      tags: 'sales,onboarding',
      startStepId: 'collect-profile',
      syncOnly: true,
      launchParameters: [
        {
          name: 'company',
          label: 'Company',
          type: 'string',
          required: true,
          description: 'Target company',
          schemaText: '{"type":"string"}',
          defaultValueText: '"Acme"',
        },
      ],
      sharedChannels: [
        {
          id: 'shared',
          retentionVersions: '3',
          retentionDays: '14',
        },
      ],
      draft: {
        schemaVersion: 2,
        title: 'Customer onboarding',
        startStepId: 'collect-profile',
        syncOnly: true,
        launchParameters: [],
        memory: { sharedChannels: [] },
        steps: [],
        metadata: { version: 3 },
      } as FlowDefinitionDraft,
      steps: [
        {
          id: 'collect-profile',
          name: 'Collect profile',
          agentVersionId: 'aaaaaaaa-1111-4111-8aaa-222222222222',
          prompt: 'Ask about customer profile',
          overrides: { temperature: '0.1', topP: '', maxTokens: '' },
          interaction: {
            type: '',
            title: '',
            description: '',
            payloadSchemaText: '',
            suggestedActionsText: '',
            dueInMinutes: '',
          },
          memoryReads: [{ channel: 'shared', limit: '5' }],
          memoryWrites: [
            { channel: 'shared', mode: 'AGENT_OUTPUT', payloadText: '' },
          ],
          transitions: {
            onSuccessNext: 'summary',
            onSuccessComplete: false,
            onFailureNext: '',
            onFailureFail: false,
          },
          maxAttempts: '2',
        },
      ],
    };

    const built = buildFlowDefinition(form);
    const reparsed = parseFlowDefinition(built);

    expect(reparsed.title).toBe('Customer onboarding');
    expect(reparsed.steps).toHaveLength(1);
    expect(reparsed.launchParameters).toHaveLength(1);
    expect(reparsed.sharedChannels).toHaveLength(1);
    expect(reparsed.draft.metadata).toMatchObject({
      version: 3,
      title: 'Customer onboarding',
      description: 'Typed flow',
      tags: ['sales', 'onboarding'],
    });
  });

  it('throws when step JSON fields contain invalid structures', () => {
    const formState: FlowDefinitionFormState = {
      title: 'Broken flow',
      description: '',
      tags: '',
      startStepId: 'step-1',
      syncOnly: true,
      launchParameters: [],
      sharedChannels: [],
      draft: {
        schemaVersion: 2,
        title: 'Broken flow',
        startStepId: 'step-1',
        syncOnly: true,
        launchParameters: [],
        memory: { sharedChannels: [] },
        steps: [],
      },
      steps: [
        {
          id: 'step-1',
          name: 'Broken step',
          agentVersionId: 'bbbbbbbb-1111-2222-3333-444444444444',
          prompt: '',
          overrides: { temperature: '', topP: '', maxTokens: '' },
          interaction: {
            type: '',
            title: '',
            description: '',
            payloadSchemaText: '',
            suggestedActionsText: '',
            dueInMinutes: '',
          },
          memoryReads: [{ channel: 'shared', limit: 'not-number' }],
          memoryWrites: [],
          transitions: {
            onSuccessNext: '',
            onSuccessComplete: false,
            onFailureNext: '',
            onFailureFail: false,
          },
          maxAttempts: '1',
        },
      ],
    };

    expect(() => buildFlowDefinition(formState)).toThrow(/memory read/);
  });
});
