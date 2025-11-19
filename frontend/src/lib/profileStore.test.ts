import { afterEach, describe, expect, it } from 'vitest';
import { buildActiveProfileHeaders, buildProfileKey, useProfileStore } from './profileStore';

const resetStore = () =>
  useProfileStore.setState(
    {
      namespace: 'web',
      reference: 'demo',
      channel: 'web',
      profile: null,
      etag: undefined,
      isLoading: false,
      error: undefined,
      auditEvents: [],
      isAuditLoading: false,
      auditError: undefined,
      devToken: undefined,
    },
    true,
  );

describe('profile store helpers', () => {
  afterEach(() => {
    resetStore();
  });

  it('normalizes profile keys', () => {
    expect(buildProfileKey(' Web ', 'DemoUser ')).toBe('web:demouser');
  });

  it('buildActiveProfileHeaders includes channel and dev token', () => {
    useProfileStore.setState((state) => ({
      ...state,
      namespace: 'ops',
      reference: 'Alice',
      channel: 'telegram',
      devToken: 'dev-123',
    }));
    const headers = buildActiveProfileHeaders(undefined, { ifMatch: 'W/"5"' });

    expect(headers.get('X-Profile-Key')).toBe('ops:alice');
    expect(headers.get('X-Profile-Channel')).toBe('telegram');
    expect(headers.get('X-Profile-Auth')).toBe('dev-123');
    expect(headers.get('If-Match')).toBe('W/"5"');
  });

  it('respects channel override when building headers', () => {
    useProfileStore.setState((state) => ({
      ...state,
      namespace: 'research',
      reference: 'bob',
      channel: 'web',
      devToken: undefined,
    }));

    const headers = buildActiveProfileHeaders(new Headers(), { channelOverride: 'cli' });

    expect(headers.get('X-Profile-Key')).toBe('research:bob');
    expect(headers.get('X-Profile-Channel')).toBe('cli');
    expect(headers.get('X-Profile-Auth')).toBeNull();
  });
});
