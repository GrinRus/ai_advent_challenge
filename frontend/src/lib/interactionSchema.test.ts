import { describe, expect, it } from 'vitest';
import {
  extractInteractionSchema,
  buildSubmissionPayload,
  computeSuggestedActionUpdates,
  type InteractionFormField,
} from './interactionSchema';

describe('interaction schema helpers', () => {
  it('extracts fields from complex schema', () => {
    const schema = {
      type: 'object',
      required: ['reason', 'count'],
      properties: {
        reason: { type: 'string', title: 'Причина' },
        details: { type: 'string', format: 'textarea' },
        choice: { type: 'string', enum: ['a', 'b'] },
        confirm: { type: 'boolean', format: 'toggle' },
        count: { type: 'number' },
        due: { type: 'string', format: 'date-time' },
      },
    };

    const { fields, initialValues } = extractInteractionSchema(schema);
    expect(fields).toHaveLength(6);
    const controlMap = Object.fromEntries(fields.map((field) => [field.name, field.control]));
    expect(controlMap).toMatchObject({
      reason: 'text',
      details: 'textarea',
      choice: 'select',
      confirm: 'toggle',
      count: 'number',
      due: 'datetime',
    });
    expect(initialValues.reason).toBe('');
    expect(initialValues.count).toBe('');
    expect(initialValues.confirm).toBe(false);
  });

  it('handles primitives schema as single field', () => {
    const schema = { type: 'boolean' };
    const { fields } = extractInteractionSchema(schema);
    expect(fields).toHaveLength(1);
    expect(fields[0].control).toBe('checkbox');
  });

  it('maps array enum to multiselect', () => {
    const schema = { type: 'array', items: { type: 'string', enum: ['opt1', 'opt2'] } };
    const { fields } = extractInteractionSchema(schema);
    expect(fields[0].control).toBe('multiselect');
    expect(fields[0].enumValues).toEqual(['opt1', 'opt2']);
  });

  it('builds submission payload with correct coercions', () => {
    const fields: InteractionFormField[] = [
      { name: 'reason', label: 'Reason', control: 'text', required: true },
      { name: 'count', label: 'Count', control: 'number', required: false },
      { name: 'flags', label: 'Flags', control: 'multiselect', required: false },
      { name: 'meta', label: 'Meta', control: 'json', required: false },
    ];

    const payload = buildSubmissionPayload(fields, {
      reason: 'approve',
      count: '5',
      flags: ['alpha', 'beta'],
      meta: '{"track":true}',
    });

    expect(payload).toEqual({
      reason: 'approve',
      count: 5,
      flags: ['alpha', 'beta'],
      meta: { track: true },
    });
  });

  it('returns undefined for empty submission', () => {
    const fields: InteractionFormField[] = [
      { name: 'reason', label: 'Reason', control: 'text', required: true },
    ];
    expect(buildSubmissionPayload(fields, { reason: '' })).toBeUndefined();
  });

  it('throws on invalid numbers or JSON', () => {
    const fields: InteractionFormField[] = [
      { name: 'count', label: 'Count', control: 'number', required: true },
    ];
    expect(() => buildSubmissionPayload(fields, { count: 'abc' })).toThrow();

    const jsonField: InteractionFormField[] = [
      { name: 'config', label: 'Config', control: 'json', required: false },
    ];
    expect(() => buildSubmissionPayload(jsonField, { config: '{oops}' })).toThrow();
  });

  it('computes suggested action updates for object payloads', () => {
    const fields: InteractionFormField[] = [
      { name: 'reason', label: 'Reason', control: 'text', required: true },
      { name: 'count', label: 'Count', control: 'number', required: false },
    ];

    const { updates, appliedFields } = computeSuggestedActionUpdates(fields, {
      reason: 'approve',
      count: 3,
      ignored: 'value',
    });

    expect(updates).toEqual({ reason: 'approve', count: '3' });
    expect(appliedFields).toEqual(['reason', 'count']);
  });

  it('computes suggested action update for scalar payload', () => {
    const fields: InteractionFormField[] = [
      { name: 'decision', label: 'Decision', control: 'select', required: true },
    ];

    const { updates, appliedFields } = computeSuggestedActionUpdates(fields, 'approve');

    expect(updates).toEqual({ decision: 'approve' });
    expect(appliedFields).toEqual(['decision']);
  });

  it('returns empty updates when payload cannot be mapped', () => {
    const fields: InteractionFormField[] = [
      { name: 'comment', label: 'Comment', control: 'text', required: false },
      { name: 'due', label: 'Due', control: 'datetime', required: false },
    ];

    const { updates, appliedFields } = computeSuggestedActionUpdates(fields, {
      unknown: 'value',
    });

    expect(updates).toEqual({});
    expect(appliedFields).toHaveLength(0);
  });
});
