import { describe, expect, it } from 'vitest';
import { buildFlowDefinition, parseFlowDefinition } from './flowDefinitionForm';
import type { FlowDefinitionFormState } from './flowDefinitionForm';
import type { FlowDefinitionDraft } from './types/flowDefinition';

describe('flowDefinitionForm adapters', () => {
  it('keeps passthrough metadata and step overrides when parsing/building', () => {
    const form: FlowDefinitionFormState = {
      title: 'Customer onboarding',
      startStepId: 'collect-profile',
      syncOnly: true,
      draft: {
        title: 'Customer onboarding',
        startStepId: 'collect-profile',
        syncOnly: true,
        steps: [],
        metadata: { version: 3 },
      } as FlowDefinitionDraft,
      steps: [
        {
          id: 'collect-profile',
          name: 'Collect profile',
          agentVersionId: 'aaaaaaaa-1111-4111-8aaa-222222222222',
          prompt: 'Ask about customer profile',
          temperature: 0.1,
          topP: null,
          maxTokensOverride: null,
          memoryReadsText: '[{"channel":"shared","limit":5}]',
          memoryWritesText: '[{"channel":"shared","mode":"AGENT_OUTPUT"}]',
          transitionsText: '{"onSuccess":{"next":"summary"}}',
          maxAttempts: 2,
        },
      ],
    };

    const built = buildFlowDefinition(form);
    const reparsed = parseFlowDefinition(built);

    expect(reparsed.title).toBe('Customer onboarding');
    expect(reparsed.steps).toHaveLength(1);
    expect(reparsed.draft.metadata).toEqual({ version: 3 });
  });

  it('throws when step JSON fields contain invalid structures', () => {
    const formState: FlowDefinitionFormState = {
      title: 'Broken flow',
      startStepId: 'step-1',
      syncOnly: true,
      draft: {
        title: 'Broken flow',
        startStepId: 'step-1',
        syncOnly: true,
        steps: [],
      },
      steps: [
        {
          id: 'step-1',
          name: 'Broken step',
          agentVersionId: 'bbbbbbbb-1111-2222-3333-444444444444',
          prompt: '',
          temperature: null,
          topP: null,
          maxTokensOverride: null,
          memoryReadsText: '{bad json}',
          memoryWritesText: '',
          transitionsText: '',
          maxAttempts: 1,
        },
      ],
    };

    expect(() => buildFlowDefinition(formState)).toThrow(/Memory Reads/);
  });
});
