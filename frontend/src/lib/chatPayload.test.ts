import { describe, expect, it } from 'vitest';
import {
  SAMPLING_RANGE,
  buildChatPayload,
  diffOverridesAgainstDefaults,
  hasOverrides,
  type SamplingDefaults,
  type SamplingOverrides,
} from './chatPayload';

describe('buildChatPayload', () => {
  it('includes sampling overrides in payload and effective snapshot when provided', () => {
    const overrides: SamplingOverrides = {
      temperature: 0.3,
      topP: 0.85,
      maxTokens: 640,
    };
    const defaults: SamplingDefaults = {
      temperature: 0.7,
      topP: 1,
      maxTokens: 1024,
    };

    const { payload, effectiveOptions, hasOverrides: overridesFlag } = buildChatPayload({
      message: 'Tune sampling',
      sessionId: 'session-1',
      provider: 'openai',
      model: 'gpt-4o-mini',
      overrides,
      defaults,
    });

    expect(payload).toMatchObject({
      message: 'Tune sampling',
      sessionId: 'session-1',
      provider: 'openai',
      model: 'gpt-4o-mini',
      options: overrides,
    });
    expect(effectiveOptions).toEqual(overrides);
    expect(overridesFlag).toBe(true);
  });

  it('omits options when overrides are empty and falls back to defaults for summary', () => {
    const defaults: SamplingDefaults = {
      temperature: 0.6,
      topP: 0.92,
      maxTokens: 2048,
    };

    const { payload, effectiveOptions, hasOverrides: overridesFlag } = buildChatPayload({
      message: 'Use defaults',
      overrides: {},
      defaults,
    });

    expect(payload).toMatchObject({ message: 'Use defaults' });
    expect(payload).not.toHaveProperty('options');
    expect(effectiveOptions).toEqual({
      temperature: defaults.temperature,
      topP: defaults.topP,
      maxTokens: defaults.maxTokens,
    });
    expect(overridesFlag).toBe(false);
  });

  it('drops overrides that equal provider defaults via diffOverridesAgainstDefaults', () => {
    const overrides: SamplingOverrides = {
      temperature: SAMPLING_RANGE.temperature.max,
      topP: 0.9,
    };
    const defaults: SamplingDefaults = {
      temperature: SAMPLING_RANGE.temperature.max,
      topP: 0.8,
      maxTokens: 1024,
    };

    const normalized = diffOverridesAgainstDefaults(overrides, defaults);
    expect(normalized).toEqual({ topP: 0.9 });
    expect(hasOverrides(normalized)).toBe(true);
  });
});
