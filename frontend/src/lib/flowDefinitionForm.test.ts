import { describe, expect, it } from 'vitest';
import {
  buildFlowDefinition,
  createEmptyFlowDefinitionForm,
  parseFlowDefinition,
} from './flowDefinitionForm';
import type { FlowDefinitionFormState } from './flowDefinitionForm';

describe('flowDefinitionForm helpers', () => {
  it('creates an empty form by default', () => {
    const empty = createEmptyFlowDefinitionForm();
    expect(empty.steps).toHaveLength(0);
    expect(empty.launchParameters).toHaveLength(0);
    expect(empty.sharedChannels).toHaveLength(0);
    expect(empty.title).toBe('');
    expect(empty.syncOnly).toBe(true);
  });

  it('parses a full definition into form state', () => {
    const source = {
      title: 'Test Flow',
      metadata: {
        description: 'Test description',
        tags: ['alpha', 'beta'],
      },
      startStepId: 'step-1',
      syncOnly: true,
      launchParameters: [
        {
          name: 'company',
          label: 'Company',
          type: 'string',
          required: true,
          description: 'Target company',
          schema: { type: 'string' },
          defaultValue: 'Acme',
        },
      ],
      memory: {
        sharedChannels: [{ id: 'shared', retentionVersions: 2, retentionDays: 30 }],
      },
      steps: [
        {
          id: 'step-1',
          name: 'First',
          agentVersionId: 'aaaaaaaa-1111-4111-8aaa-222222222222',
          prompt: 'Do work',
          overrides: { temperature: 0.3 },
          memoryReads: [{ channel: 'context', limit: 5 }],
          memoryWrites: [{ channel: 'context', mode: 'AGENT_OUTPUT' }],
          transitions: { onSuccess: { next: 'step-2' } },
          interaction: { type: 'INPUT_FORM', title: 'Provide data' },
          maxAttempts: 2,
        },
      ],
      meta: { foo: 'bar' },
    };

    const form = parseFlowDefinition(source);
    expect(form.title).toBe('Test Flow');
    expect(form.description).toBe('Test description');
    expect(form.tags).toBe('alpha, beta');
    expect(form.startStepId).toBe('step-1');
    expect(form.steps).toHaveLength(1);
    expect(form.launchParameters).toHaveLength(1);
    expect(form.sharedChannels).toHaveLength(1);
    expect(form.steps[0].overrides.temperature).toBe('0.3');
    expect(form.steps[0].memoryReads[0]).toEqual({ channel: 'context', limit: '5' });
    expect(form.steps[0].interaction.type).toBe('INPUT_FORM');
    expect(form.steps[0].maxAttempts).toBe('2');
    expect(form.draft.meta).toEqual({ foo: 'bar' });
  });

  it('builds definition back from form state', () => {
    const form: FlowDefinitionFormState = {
      title: 'Flow',
      description: 'Typed flow',
      tags: 'alpha, beta',
      startStepId: 'step-1',
      syncOnly: true,
      launchParameters: [
        {
          name: 'company',
          label: 'Company',
          type: 'string',
          required: true,
          description: '',
          schemaText: '',
          defaultValueText: '',
        },
      ],
      sharedChannels: [
        { id: 'shared', retentionVersions: '2', retentionDays: '14' },
      ],
      draft: {
        title: 'Flow',
        startStepId: 'step-1',
        syncOnly: true,
        steps: [],
      },
      steps: [
        {
          id: 'step-1',
          name: 'First',
          agentVersionId: 'aaaaaaaa-1111-4111-8aaa-333333333333',
          prompt: 'Prompt',
          overrides: { temperature: '0.5', topP: '0.8', maxTokens: '500' },
          interaction: {
            type: '',
            title: '',
            description: '',
            payloadSchemaText: '',
            suggestedActionsText: '',
            dueInMinutes: '',
          },
          memoryReads: [{ channel: 'ctx', limit: '3' }],
          memoryWrites: [],
          transitions: {
            onSuccessNext: 'step-2',
            onSuccessComplete: false,
            onFailureNext: '',
            onFailureFail: false,
          },
          maxAttempts: '1',
        },
      ],
    };

    const built = buildFlowDefinition(form);
    expect(built.steps).toHaveLength(1);
    const [step] = built.steps;
    expect(step.overrides).toEqual({
      temperature: 0.5,
      topP: 0.8,
      maxTokens: 500,
    });
    expect(step.memoryReads).toEqual([{ channel: 'ctx', limit: 3 }]);
    expect(step.transitions).toEqual({ onSuccess: { next: 'step-2' } });
    expect((built as any).launchParameters).toHaveLength(1);
    expect((built as any).memory.sharedChannels).toHaveLength(1);
  });

  it('throws on invalid numeric fields', () => {
    const form = createEmptyFlowDefinitionForm();
    form.steps.push({
      id: 'x',
      name: 'Step',
      agentVersionId: 'agent',
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
      memoryReads: [{ channel: 'ctx', limit: 'not-number' }],
      memoryWrites: [],
      transitions: {
        onSuccessNext: '',
        onSuccessComplete: false,
        onFailureNext: '',
        onFailureFail: false,
      },
      maxAttempts: '1',
    });

    expect(() => buildFlowDefinition(form)).toThrow(/memory read/);
  });
});
