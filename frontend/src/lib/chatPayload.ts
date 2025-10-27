import type { ChatSyncRequest } from './apiClient';

export type SamplingKey = 'temperature' | 'topP' | 'maxTokens';

export type SamplingOverrides = Partial<Record<SamplingKey, number>>;

export type SamplingDefaults = Partial<Record<SamplingKey, number | null>>;

export type SamplingSnapshot = Record<SamplingKey, number | null>;

export type BuildChatPayloadParams = {
  message: string;
  sessionId?: string | null;
  provider?: string | null;
  model?: string | null;
  overrides?: SamplingOverrides | null;
  defaults?: SamplingDefaults | null;
  mode?: 'default' | 'research';
};

export type BuildChatPayloadResult = {
  payload: ChatSyncRequest;
  effectiveOptions: SamplingSnapshot;
  hasOverrides: boolean;
};

const SAMPLING_KEYS: SamplingKey[] = ['temperature', 'topP', 'maxTokens'];

const isNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);

export function buildChatPayload({
  message,
  sessionId,
  provider,
  model,
  overrides,
  defaults,
  mode,
}: BuildChatPayloadParams): BuildChatPayloadResult {
  const basePayload: ChatSyncRequest = { message };

  if (sessionId) {
    basePayload.sessionId = sessionId;
  }
  if (provider) {
    basePayload.provider = provider;
  }
  if (model) {
    basePayload.model = model;
  }
  if (mode && mode !== 'default') {
    basePayload.mode = mode;
  }

  const resolvedOverrides: Record<string, number> = {};
  let hasOverrides = false;

  for (const key of SAMPLING_KEYS) {
    const overrideValue = overrides?.[key];
    if (isNumber(overrideValue)) {
      resolvedOverrides[key] = overrideValue;
      hasOverrides = true;
    }
  }

  if (hasOverrides) {
    basePayload.options = resolvedOverrides;
  }

  const effectiveOptions = SAMPLING_KEYS.reduce<SamplingSnapshot>(
    (accumulator, key) => {
      if (isNumber(resolvedOverrides[key])) {
        accumulator[key] = resolvedOverrides[key];
      } else {
        const defaultValue = defaults?.[key];
        accumulator[key] = isNumber(defaultValue) ? defaultValue : null;
      }
      return accumulator;
    },
    { temperature: null, topP: null, maxTokens: null },
  );

  return {
    payload: basePayload,
    effectiveOptions,
    hasOverrides,
  };
}

export function diffOverridesAgainstDefaults(
  overrides: SamplingOverrides,
  defaults: SamplingDefaults | null,
): SamplingOverrides {
  const result: SamplingOverrides = {};

  for (const key of SAMPLING_KEYS) {
    const overrideValue = overrides[key];
    if (!isNumber(overrideValue)) {
      continue;
    }

    const defaultValue = defaults?.[key];
    if (isNumber(defaultValue) && Math.abs(overrideValue - defaultValue) < 1e-9) {
      continue;
    }

    result[key] = overrideValue;
  }

  return result;
}

export function hasOverrides(overrides: SamplingOverrides): boolean {
  return Object.values(overrides).some((value) => isNumber(value));
}

export const SAMPLING_RANGE: Record<
  SamplingKey,
  { min: number; max: number; step: number }
> = {
  temperature: { min: 0, max: 2, step: 0.01 },
  topP: { min: 0, max: 1, step: 0.01 },
  maxTokens: { min: 1, max: 4096, step: 1 },
};
