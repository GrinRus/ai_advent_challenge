import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { UserProfileDocument } from './profileTypes';

const DEFAULT_NAMESPACE = 'web';
const DEFAULT_REFERENCE = 'demo';
const DEFAULT_CHANNEL = 'web';
const HISTORY_LIMIT = 10;

export type ProfileHistoryEntry = {
  timestamp: string;
  source: string;
  channel?: string | null;
  version?: number;
  message?: string;
};

type ProfileStoreState = {
  namespace: string;
  reference: string;
  channel: string;
  profile: UserProfileDocument | null;
  etag?: string;
  isLoading: boolean;
  error?: string;
  lastFetchedAt?: string;
  history: ProfileHistoryEntry[];
  devToken?: string;
  setNamespace: (value: string) => void;
  setReference: (value: string) => void;
  setChannel: (value: string) => void;
  setDevToken: (value?: string) => void;
  applyProfileSnapshot: (
    profile: UserProfileDocument,
    options?: { etag?: string; source?: string; channel?: string | null },
  ) => void;
  recordHistory: (entry: ProfileHistoryEntry) => void;
};

const sanitizeHandle = (value: string, fallback: string) => {
  if (!value) {
    return fallback;
  }
  return value.trim().toLowerCase() || fallback;
};

const initialDevToken = import.meta.env.VITE_PROFILE_DEV_TOKEN?.trim();

export const useProfileStore = create<ProfileStoreState>()(
  persist(
    (set, get) => ({
      namespace: DEFAULT_NAMESPACE,
      reference: DEFAULT_REFERENCE,
      channel: DEFAULT_CHANNEL,
      profile: null,
      etag: undefined,
      isLoading: false,
      error: undefined,
      lastFetchedAt: undefined,
      history: [],
      devToken: initialDevToken || undefined,
      setNamespace: (value) =>
        set({ namespace: sanitizeHandle(value, DEFAULT_NAMESPACE) }),
      setReference: (value) =>
        set({ reference: sanitizeHandle(value, DEFAULT_REFERENCE) }),
      setChannel: (value) =>
        set({ channel: sanitizeHandle(value, DEFAULT_CHANNEL) }),
      setDevToken: (value) => set({ devToken: value?.trim() || undefined }),
      applyProfileSnapshot: (profile, options) => {
        const timestamp = new Date().toISOString();
        set((state) => ({
          profile,
          etag: options?.etag ?? state.etag,
          isLoading: false,
          error: undefined,
          lastFetchedAt: timestamp,
          history: [
            {
              timestamp,
              source: options?.source ?? 'profile:update',
              channel: options?.channel ?? state.channel,
              version: profile.version,
              message: `v${profile.version} · ${profile.updatedAt}`,
            },
            ...state.history,
          ].slice(0, HISTORY_LIMIT),
        }));
      },
      recordHistory: (entry) =>
        set((state) => ({ history: [entry, ...state.history].slice(0, HISTORY_LIMIT) })),
    }),
    {
      name: 'profile-store',
      partialize: (state) => ({
        namespace: state.namespace,
        reference: state.reference,
        channel: state.channel,
        devToken: state.devToken,
      }),
    },
  ),
);

export const buildProfileKey = (namespace: string, reference: string) =>
  `${sanitizeHandle(namespace, DEFAULT_NAMESPACE)}:${sanitizeHandle(reference, DEFAULT_REFERENCE)}`;

export const getActiveProfileSelection = () => {
  const state = useProfileStore.getState();
  return {
    namespace: state.namespace,
    reference: state.reference,
    channel: state.channel,
    profileKey: buildProfileKey(state.namespace, state.reference),
    devToken: state.devToken,
  };
};

export const buildActiveProfileHeaders = (
  init?: HeadersInit,
  options?: { channelOverride?: string; ifMatch?: string },
): Headers => {
  const { profileKey, channel, devToken } = getActiveProfileSelection();
  const headers = new Headers(init);
  headers.set('X-Profile-Key', profileKey);
  const effectiveChannel = options?.channelOverride ?? channel;
  if (effectiveChannel) {
    headers.set('X-Profile-Channel', effectiveChannel);
  }
  if (devToken) {
    headers.set('X-Profile-Auth', devToken);
  }
  if (options?.ifMatch) {
    headers.set('If-Match', options.ifMatch);
  }
  return headers;
};
