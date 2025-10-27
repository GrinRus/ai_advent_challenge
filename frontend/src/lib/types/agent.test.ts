import { describe, expect, it } from 'vitest';
import { AgentInvocationOptionsInputSchema } from './agentInvocation';

describe('AgentInvocationOptionsInputSchema', () => {
  it('parses minimal payload', () => {
    const parsed = AgentInvocationOptionsInputSchema.parse({
      provider: {
        id: 'openai',
        modelId: 'gpt-4o-mini',
        mode: 'SYNC',
      },
    });
    expect(parsed.provider.id).toBe('openai');
    expect(parsed.provider.modelId).toBe('gpt-4o-mini');
    expect(parsed.prompt?.generation).toBeUndefined();
  });

  it('parses prompt generation defaults', () => {
    const parsed = AgentInvocationOptionsInputSchema.parse({
      provider: {
        id: 'openai',
        modelId: 'gpt-4o-mini',
      },
      prompt: {
        system: 'You are helpful',
        generation: {
          temperature: 0.7,
          topP: 0.8,
          maxOutputTokens: 1024,
        },
      },
    });
    expect(parsed.prompt?.generation?.temperature).toBe(0.7);
    expect(parsed.prompt?.generation?.maxOutputTokens).toBe(1024);
  });

  it('rejects invalid tooling payload', () => {
    expect(() =>
      AgentInvocationOptionsInputSchema.parse({
        provider: {
          id: 'openai',
          modelId: 'gpt-4o-mini',
        },
        tooling: {
          bindings: [
            {
              schemaVersion: 'invalid',
            },
          ],
        },
      }),
    ).toThrow();
  });
});
