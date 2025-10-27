import { describe, expect, it } from 'vitest';
import { AgentDefaultOptionsSchema } from './agent';

describe('AgentDefaultOptionsSchema', () => {
  it('parses a valid options payload', () => {
    const parsed = AgentDefaultOptionsSchema.parse({
      temperature: 0.6,
      topP: 0.9,
      maxTokens: 2048,
      customFlag: true,
    });
    expect(parsed.temperature).toBe(0.6);
    expect(parsed.topP).toBe(0.9);
    expect(parsed.maxTokens).toBe(2048);
    expect(parsed.customFlag).toBe(true);
  });

  it('rejects invalid ranges', () => {
    expect(() =>
      AgentDefaultOptionsSchema.parse({
        temperature: 3,
      }),
    ).toThrow();

    expect(() =>
      AgentDefaultOptionsSchema.parse({
        topP: 0,
      }),
    ).toThrow();
  });
});
