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
    expect(empty.title).toBe('');
    expect(empty.syncOnly).toBe(true);
  });

  it('parses a full definition into form state', () => {
    const source = {
      title: 'Test Flow',
      startStepId: 'step-1',
      syncOnly: true,
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
          maxAttempts: 2,
        },
      ],
      meta: { foo: 'bar' },
    };

    const form = parseFlowDefinition(source);
    expect(form.title).toBe('Test Flow');
    expect(form.startStepId).toBe('step-1');
    expect(form.steps).toHaveLength(1);
    expect(form.steps[0]).toMatchObject({
      id: 'step-1',
      agentVersionId: 'aaaaaaaa-1111-4111-8aaa-222222222222',
      temperature: 0.3,
      maxAttempts: 2,
    });
    expect(form.draft.meta).toEqual({ foo: 'bar' });
  });

  it('builds definition back from form state', () => {
    const form: FlowDefinitionFormState = {
      title: 'Flow',
      startStepId: 'step-1',
      syncOnly: true,
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
          temperature: 0.5,
          topP: 0.8,
          maxTokensOverride: 500,
          memoryReadsText: '[{"channel":"ctx","limit":3}]',
          memoryWritesText: '[]',
          transitionsText: '{"onSuccess":{"next":"step-2"}}',
          maxAttempts: 1,
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
  });

  it('throws on invalid JSON in step fields', () => {
    const form = createEmptyFlowDefinitionForm();
    form.steps.push({
      id: 'x',
      name: 'Step',
      agentVersionId: 'agent',
      prompt: '',
      temperature: null,
      topP: null,
      maxTokensOverride: null,
      memoryReadsText: '{bad json]',
      memoryWritesText: '',
      transitionsText: '',
      maxAttempts: 1,
    });

    expect(() => buildFlowDefinition(form)).toThrow(/Memory Reads/);
  });
});
