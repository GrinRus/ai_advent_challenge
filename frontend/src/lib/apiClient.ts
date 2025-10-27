import { z } from 'zod';
import type { ZodSchema } from 'zod';
import {
  FlowLaunchParameters,
  FlowSharedContext,
  FlowStartResponse,
  FlowStatusResponse,
  parseFlowStartResponse,
  parseFlowStatusResponse,
} from './types/flow';
import {
  AgentDefinitionDetailsSchema,
  AgentDefinitionSummarySchema,
  AgentVersionSchema,
} from './types/agent';
import type {
  AgentCapabilityPayload,
  AgentDefinitionDetails,
  AgentDefinitionSummary,
  AgentDefaultOptions,
  AgentVersion,
} from './types/agent';
import {
  FlowDefinitionDetailsSchema,
  FlowDefinitionHistoryEntrySchema,
  FlowDefinitionSummarySchema,
} from './types/flowDefinition';
import type {
  FlowDefinitionDetails,
  FlowDefinitionHistoryEntry,
  FlowDefinitionSummary,
} from './types/flowDefinition';
import { FlowLaunchPreviewSchema } from './types/flowLaunch';
import type { FlowLaunchPreview } from './types/flowLaunch';
import {
  FlowInteractionItemSchema,
  FlowInteractionListSchema,
} from './types/flowInteraction';
import type {
  FlowInteractionItem,
  FlowInteractionList,
  FlowInteractionResponseSource,
  FlowInteractionResponseSummary,
} from './types/flowInteraction';
import type { JsonValue } from './types/json';

export type { FlowEvent, FlowStartResponse, FlowState, FlowStatusResponse } from './types/flow';
export type {
  AgentCapability,
  AgentCapabilityPayload,
  AgentDefinitionDetails,
  AgentDefinitionSummary,
  AgentDefaultOptions,
  AgentVersion,
} from './types/agent';
export type {
  FlowDefinitionDetails,
  FlowDefinitionHistoryEntry,
  FlowDefinitionSummary,
} from './types/flowDefinition';
export type {
  FlowLaunchPreview,
  FlowLaunchStep,
  FlowLaunchCostEstimate,
  FlowLaunchPricing,
} from './types/flowLaunch';
export type {
  FlowInteractionResponseSource,
  FlowInteractionStatus,
  FlowInteractionType,
  FlowInteractionResponseSummary,
  FlowSuggestedAction,
  FlowSuggestedActionFilter,
  FlowSuggestedActions,
} from './types/flowInteraction';
export type { JsonValue } from './types/json';

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? '/api';
export const CHAT_STREAM_URL = `${API_BASE_URL}/llm/chat/stream`;
export const CHAT_SYNC_URL = `${API_BASE_URL}/llm/chat/sync`;
export const CHAT_STRUCTURED_SYNC_URL = `${API_BASE_URL}/llm/chat/sync/structured`;

export type HelpResponse = {
  message: string;
};

export type ChatProvidersResponse = {
  defaultProvider: string;
  providers: Array<{
    id: string;
    displayName?: string;
    type?: string;
    defaultModel?: string;
    temperature?: number;
    topP?: number;
    maxTokens?: number;
    models: Array<{
      id: string;
      displayName?: string;
      tier?: string;
      inputPer1KTokens?: number;
      outputPer1KTokens?: number;
      contextWindow?: number;
      maxOutputTokens?: number;
      syncEnabled?: boolean;
      streamingEnabled?: boolean;
      structuredEnabled?: boolean;
      currency?: string;
    }>;
  }>;
};

const AgentDefinitionSummaryListSchema = z.array(AgentDefinitionSummarySchema);
const FlowDefinitionSummaryListSchema = z.array(FlowDefinitionSummarySchema);
const FlowDefinitionHistoryListSchema = z.array(FlowDefinitionHistoryEntrySchema);

function validateWithSchema<T>(payload: unknown, schema: ZodSchema<T>, context: string): T {
  const parsed = schema.safeParse(payload);
  if (!parsed.success) {
    throw new Error(
      `${context}: формат ответа не соответствует ожиданиям (${parsed.error.message})`,
    );
  }
  return parsed.data;
}

async function parseJsonResponse<T>(
  response: Response,
  schema: ZodSchema<T>,
  errorPrefix: string,
): Promise<T> {
  const rawText = await response.text();
  if (!response.ok) {
    throw new Error(
      `${errorPrefix} (status ${response.status}): ${
        rawText || response.statusText
      }`,
    );
  }

  let payload: unknown = null;
  try {
    payload = rawText ? JSON.parse(rawText) : null;
  } catch (error) {
    throw new Error(`${errorPrefix}: не удалось разобрать JSON (${(error as Error).message})`);
  }

  return validateWithSchema(payload, schema, errorPrefix);
}

function tryParseJson(raw: string): unknown | undefined {
  if (!raw) {
    return undefined;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
}

function parseJsonBodyOrThrow(raw: string, context: string): unknown {
  if (!raw) {
    return {};
  }
  const parsed = tryParseJson(raw);
  if (parsed === undefined) {
    throw new Error(`${context}: не удалось разобрать JSON ответа`);
  }
  return parsed;
}

export type AgentCapabilityResponse = AgentCapability;
export type AgentVersionResponse = AgentVersion;

export type AgentDefinitionPayload = {
  identifier: string;
  displayName: string;
  description?: string;
  active?: boolean;
  createdBy?: string;
  updatedBy?: string;
};

export type AgentDefinitionStatusPayload = {
  active: boolean;
  updatedBy: string;
};

export type AgentVersionPayload = {
  providerType?: string;
  providerId: string;
  modelId: string;
  systemPrompt: string;
  defaultOptions?: AgentDefaultOptions;
  toolBindings?: JsonValue;
  costProfile?: JsonValue;
  syncOnly?: boolean;
  maxTokens?: number;
  createdBy: string;
  capabilities?: AgentCapabilityPayload[];
};

export type AgentVersionPublishPayload = {
  updatedBy: string;
  capabilities?: AgentCapabilityPayload[];
};

export type ChatSyncResponse = {
  requestId?: string;
  content?: string;
  provider?: StructuredSyncProvider;
  usage?: StructuredSyncUsageStats;
  cost?: UsageCostDetails;
  latencyMs?: number;
  timestamp?: string;
};

export type ChatSyncRequest = {
  sessionId?: string;
  message: string;
  provider?: string;
  model?: string;
  options?: {
    temperature?: number;
    topP?: number;
    maxTokens?: number;
  };
};

export type StructuredSyncProvider = {
  type?: string;
  model?: string;
};

export type StructuredSyncItem = {
  title?: string;
  details?: string;
  tags?: string[];
};

export type StructuredSyncAnswer = {
  summary?: string;
  items?: StructuredSyncItem[];
  confidence?: number;
};

export type StructuredSyncUsageStats = {
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
};

export type UsageCostDetails = {
  input?: number;
  output?: number;
  total?: number;
  currency?: string;
};

export type StructuredSyncResponse = {
  requestId?: string;
  status?: string;
  provider?: StructuredSyncProvider;
  answer?: StructuredSyncAnswer;
  usage?: StructuredSyncUsageStats;
  cost?: UsageCostDetails;
  latencyMs?: number;
  timestamp?: string;
};

const StructuredSyncProviderSchema = z.object({
  type: z.string().nullable().optional(),
  model: z.string().nullable().optional(),
});

const StructuredSyncUsageStatsSchema = z
  .object({
    promptTokens: z.number().int().nonnegative().nullable().optional(),
    completionTokens: z.number().int().nonnegative().nullable().optional(),
    totalTokens: z.number().int().nonnegative().nullable().optional(),
  })
  .partial();

const UsageCostDetailsSchema = z
  .object({
    input: z.number().nullable().optional(),
    output: z.number().nullable().optional(),
    total: z.number().nullable().optional(),
    currency: z.string().nullable().optional(),
  })
  .partial();

const StructuredSyncItemSchema = z.object({
  title: z.string().nullable().optional(),
  details: z.string().nullable().optional(),
  tags: z.array(z.string()).optional(),
});

const StructuredSyncAnswerSchema = z.object({
  summary: z.string().nullable().optional(),
  items: z.array(StructuredSyncItemSchema).optional(),
  confidence: z.number().nullable().optional(),
});

const ChatSyncResponseSchema = z.object({
  requestId: z.string().optional(),
  content: z.string().optional(),
  provider: StructuredSyncProviderSchema.optional(),
  usage: StructuredSyncUsageStatsSchema.nullish(),
  cost: UsageCostDetailsSchema.nullish(),
  latencyMs: z.number().nullable().optional(),
  timestamp: z.string().optional(),
});

const StructuredSyncResponseSchema = z.object({
  requestId: z.string().optional(),
  status: z.string().optional(),
  provider: StructuredSyncProviderSchema.optional(),
  answer: StructuredSyncAnswerSchema.optional(),
  usage: StructuredSyncUsageStatsSchema.nullish(),
  cost: UsageCostDetailsSchema.nullish(),
  latencyMs: z.number().nullable().optional(),
  timestamp: z.string().optional(),
});

export type FlowTelemetrySnapshot = {
  stepsCompleted: number;
  stepsFailed: number;
  retriesScheduled: number;
  totalCostUsd: number;
  promptTokens: number;
  completionTokens: number;
  startedAt?: string | null;
  lastUpdated?: string | null;
  completedAt?: string | null;
  status?: string;
};

export type FlowInteractionResponseSummaryDto = FlowInteractionResponseSummary;
export type FlowInteractionItemDto = FlowInteractionItem;
export type FlowInteractionListResponse = FlowInteractionList;

export type FlowInteractionRespondPayload = {
  chatSessionId: string;
  respondedBy?: string;
  source?: FlowInteractionResponseSource;
  payload?: JsonValue;
};

export type FlowInteractionAutoResolvePayload = {
  payload?: JsonValue;
  source?: Extract<FlowInteractionResponseSource, 'AUTO_POLICY' | 'SYSTEM'>;
  respondedBy?: string;
};

export type FlowControlCommand = 'pause' | 'resume' | 'cancel' | 'retryStep';

export type ChatRequestOverridesDto = {
  temperature?: number | null;
  topP?: number | null;
  maxTokens?: number | null;
};

export type SessionUsageMessage = {
  messageId?: string;
  sequenceNumber?: number;
  role?: string;
  provider?: string;
  model?: string;
  usage?: StructuredSyncUsageStats | null;
  cost?: UsageCostDetails | null;
  createdAt?: string;
};

export type SessionUsageTotals = {
  usage?: StructuredSyncUsageStats | null;
  cost?: UsageCostDetails | null;
};

export type SessionUsageResponse = {
  sessionId: string;
  messages: SessionUsageMessage[];
  totals?: SessionUsageTotals | null;
};

export type StructuredSyncCallResult = {
  body: StructuredSyncResponse;
  sessionId?: string;
  newSession?: boolean;
};

export type SyncCallResult = {
  body: ChatSyncResponse;
  sessionId?: string;
  newSession?: boolean;
};

export async function fetchHelp(): Promise<HelpResponse> {
  const response = await fetch(`${API_BASE_URL}/help`, {
    headers: {
      Accept: 'application/json',
    },
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(
      `Failed to load help message (status ${response.status}): ${errorText}`,
    );
  }

  return response.json() as Promise<HelpResponse>;
}

export async function fetchChatProviders(): Promise<ChatProvidersResponse> {
  const response = await fetch(`${API_BASE_URL}/llm/providers`, {
    headers: {
      Accept: 'application/json',
    },
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(
      `Failed to load chat providers (status ${response.status}): ${errorText}`,
    );
  }

  return response.json() as Promise<ChatProvidersResponse>;
}

type CacheRecord<T> = {
  data: T;
  fetchedAt: number;
};

const AGENT_CACHE_TTL_MS = 30_000;

let agentDefinitionsCache: CacheRecord<AgentDefinitionSummary[]> | null = null;
let agentDefinitionsPromise: Promise<AgentDefinitionSummary[]> | null = null;
const agentDefinitionDetailsCache = new Map<string, CacheRecord<AgentDefinitionDetails>>();
const agentDefinitionDetailsPromises = new Map<string, Promise<AgentDefinitionDetails>>();

const isCacheFresh = (cachedAt: number) => Date.now() - cachedAt < AGENT_CACHE_TTL_MS;

export function invalidateAgentCatalogCache() {
  agentDefinitionsCache = null;
  agentDefinitionsPromise = null;
  agentDefinitionDetailsCache.clear();
  agentDefinitionDetailsPromises.clear();
}

export async function fetchAgentDefinitions(
  forceRefresh = false,
): Promise<AgentDefinitionSummary[]> {
  if (!forceRefresh && agentDefinitionsCache && isCacheFresh(agentDefinitionsCache.fetchedAt)) {
    return agentDefinitionsCache.data;
  }
  if (!forceRefresh && agentDefinitionsPromise) {
    return agentDefinitionsPromise;
  }

  const request = (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/agents/definitions`, {
        headers: { Accept: 'application/json' },
      });
      const data = await parseJsonResponse(
        response,
        AgentDefinitionSummaryListSchema,
        'Не удалось получить список агентов',
      );
      agentDefinitionsCache = { data, fetchedAt: Date.now() };
      agentDefinitionsPromise = null;
      return data;
    } catch (error) {
      agentDefinitionsPromise = null;
      throw error;
    }
  })();

  if (!forceRefresh) {
    agentDefinitionsPromise = request;
  }

  return request;
}

export async function fetchAgentDefinition(
  id: string,
  options: { forceRefresh?: boolean } = {},
): Promise<AgentDefinitionDetails> {
  const { forceRefresh = false } = options;

  const cached = agentDefinitionDetailsCache.get(id);
  if (!forceRefresh && cached && isCacheFresh(cached.fetchedAt)) {
    return cached.data;
  }

  if (!forceRefresh && agentDefinitionDetailsPromises.has(id)) {
    return agentDefinitionDetailsPromises.get(id)!;
  }

  const request = (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/agents/definitions/${id}`, {
        headers: { Accept: 'application/json' },
      });
      const data = await parseJsonResponse(
        response,
        AgentDefinitionDetailsSchema,
        'Не удалось получить информацию об агенте',
      );
      agentDefinitionDetailsCache.set(id, { data, fetchedAt: Date.now() });
      agentDefinitionDetailsPromises.delete(id);
      return data;
    } catch (error) {
      agentDefinitionDetailsPromises.delete(id);
      throw error;
    }
  })();

  if (!forceRefresh) {
    agentDefinitionDetailsPromises.set(id, request);
  }

  return request;
}

export async function fetchAgentCatalog(forceRefresh = false): Promise<AgentDefinitionDetails[]> {
  const summaries = await fetchAgentDefinitions(forceRefresh);
  return Promise.all(
    summaries.map((summary) => fetchAgentDefinition(summary.id, { forceRefresh })),
  );
}

export async function createAgentDefinition(
  payload: AgentDefinitionPayload,
): Promise<AgentDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/agents/definitions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentDefinitionDetailsSchema,
    'Не удалось создать определение агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function updateAgentDefinition(
  id: string,
  payload: AgentDefinitionPayload,
): Promise<AgentDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/agents/definitions/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentDefinitionDetailsSchema,
    'Не удалось обновить определение агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function updateAgentDefinitionStatus(
  id: string,
  payload: AgentDefinitionStatusPayload,
): Promise<AgentDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/agents/definitions/${id}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentDefinitionDetailsSchema,
    'Не удалось изменить статус агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function createAgentVersion(
  definitionId: string,
  payload: AgentVersionPayload,
): Promise<AgentVersionResponse> {
  const response = await fetch(`${API_BASE_URL}/agents/definitions/${definitionId}/versions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentVersionSchema,
    'Не удалось создать версию агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function publishAgentVersion(
  versionId: string,
  payload: AgentVersionPublishPayload,
): Promise<AgentVersionResponse> {
  const response = await fetch(`${API_BASE_URL}/agents/versions/${versionId}/publish`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentVersionSchema,
    'Не удалось опубликовать версию агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function deprecateAgentVersion(
  versionId: string,
): Promise<AgentVersionResponse> {
  const response = await fetch(`${API_BASE_URL}/agents/versions/${versionId}/deprecate`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
    },
  });
  const data = await parseJsonResponse(
    response,
    AgentVersionSchema,
    'Не удалось депрецировать версию агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function requestSync(
  payload: ChatSyncRequest,
): Promise<SyncCallResult> {
  const response = await fetch(CHAT_SYNC_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });

  const raw = await response.text();

  if (!response.ok) {
    let message = `Не удалось получить ответ (статус ${response.status})`;
    const parsed = tryParseJson(raw);
    if (parsed && typeof parsed === 'object' && 'message' in parsed) {
      message = String((parsed as { message?: unknown }).message);
    } else if (raw) {
      message = raw;
    }
    throw new Error(message);
  }

  const parsedBody = parseJsonBodyOrThrow(raw, 'Не удалось обработать ответ sync API');
  const body = validateWithSchema(
    parsedBody,
    ChatSyncResponseSchema,
    'Не удалось обработать ответ sync API',
  );
  const sessionId = response.headers.get('X-Session-Id') ?? undefined;
  const newSessionHeader = response.headers.get('X-New-Session');
  const newSession = newSessionHeader
    ? newSessionHeader.toLowerCase() === 'true'
    : undefined;

  return { body, sessionId, newSession };
}

export async function requestStructuredSync(
  payload: ChatSyncRequest,
): Promise<StructuredSyncCallResult> {
  const response = await fetch(CHAT_STRUCTURED_SYNC_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });

  const raw = await response.text();

  if (!response.ok) {
    let message = `Не удалось получить структурированный ответ (статус ${response.status})`;
    const parsed = tryParseJson(raw);
    if (parsed && typeof parsed === 'object' && 'message' in parsed) {
      message = String((parsed as { message?: unknown }).message);
    } else if (raw) {
      message = raw;
    }

    throw new Error(message);
  }

  const parsedBody = parseJsonBodyOrThrow(
    raw,
    'Не удалось обработать структурированный ответ',
  );
  const body = validateWithSchema(
    parsedBody,
    StructuredSyncResponseSchema,
    'Не удалось обработать структурированный ответ',
  );
  const sessionId = response.headers.get('X-Session-Id') ?? undefined;
  const newSessionHeader = response.headers.get('X-New-Session');
  const newSession = newSessionHeader
    ? newSessionHeader.toLowerCase() === 'true'
    : undefined;

  return { body, sessionId, newSession };
}

export async function startFlow(
  flowDefinitionId: string,
  payload?: {
    parameters?: FlowLaunchParameters;
    sharedContext?: FlowSharedContext;
    overrides?: ChatRequestOverridesDto | null;
  },
): Promise<FlowStartResponse> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${flowDefinitionId}/start`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(payload ?? {}),
    },
  );

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось запустить флоу (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }

  const raw = await response.json();
  return parseFlowStartResponse(raw);
}

export async function fetchFlowSnapshot(
  sessionId: string,
): Promise<FlowStatusResponse> {
  const response = await fetch(`${API_BASE_URL}/flows/${sessionId}/snapshot`, {
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось загрузить статус флоу (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }

  const raw = await response.json();
  return parseFlowStatusResponse(raw);
}

export async function pollFlowStatus(
  sessionId: string,
  params: {
    sinceEventId?: number;
    stateVersion?: number;
    timeoutMs?: number;
  } = {},
): Promise<FlowStatusResponse | null> {
  const query = new URLSearchParams();
  if (params.sinceEventId != null) {
    query.set('sinceEventId', String(params.sinceEventId));
  }
  if (params.stateVersion != null) {
    query.set('stateVersion', String(params.stateVersion));
  }
  if (params.timeoutMs != null) {
    query.set('timeout', String(params.timeoutMs));
  }

  const url = `${API_BASE_URL}/flows/${sessionId}${
    query.toString() ? `?${query.toString()}` : ''
  }`;

  const response = await fetch(url, { headers: { Accept: 'application/json' } });

  if (response.status === 204) {
    return null;
  }

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Ошибка при опросе статуса (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }

  const raw = await response.json();
  return parseFlowStatusResponse(raw);
}

export async function fetchFlowInteractions(
  sessionId: string,
): Promise<FlowInteractionListResponse> {
  const response = await fetch(`${API_BASE_URL}/flows/${sessionId}/interactions`, {
    headers: { Accept: 'application/json' },
  });

  return parseJsonResponse(
    response,
    FlowInteractionListSchema,
    'Не удалось загрузить интерактивные запросы',
  );
}

export async function respondToFlowInteraction(
  sessionId: string,
  requestId: string,
  payload: FlowInteractionRespondPayload,
): Promise<FlowInteractionItemDto> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${sessionId}/interactions/${requestId}/respond`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Chat-Session-Id': payload.chatSessionId,
      },
      body: JSON.stringify(payload),
    },
  );

  return parseJsonResponse(
    response,
    FlowInteractionItemSchema,
    'Не удалось отправить ответ пользователя',
  );
}

export async function skipFlowInteraction(
  sessionId: string,
  requestId: string,
  payload: FlowInteractionRespondPayload,
): Promise<FlowInteractionItemDto> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${sessionId}/interactions/${requestId}/skip`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Chat-Session-Id': payload.chatSessionId,
      },
      body: JSON.stringify(payload),
    },
  );

  return parseJsonResponse(
    response,
    FlowInteractionItemSchema,
    'Не удалось пропустить запрос',
  );
}

export async function autoResolveFlowInteraction(
  sessionId: string,
  requestId: string,
  chatSessionId: string,
  payload: FlowInteractionAutoResolvePayload = {},
): Promise<FlowInteractionItemDto> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${sessionId}/interactions/${requestId}/auto`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Chat-Session-Id': chatSessionId,
      },
      body: JSON.stringify(payload),
    },
  );

  return parseJsonResponse(
    response,
    FlowInteractionItemSchema,
    'Не удалось автозавершить запрос',
  );
}

export async function expireFlowInteraction(
  sessionId: string,
  requestId: string,
  chatSessionId: string,
  payload: FlowInteractionAutoResolvePayload = {},
): Promise<FlowInteractionItemDto> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${sessionId}/interactions/${requestId}/expire`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Chat-Session-Id': chatSessionId,
      },
      body: JSON.stringify(payload),
    },
  );

  return parseJsonResponse(
    response,
    FlowInteractionItemSchema,
    'Не удалось пометить запрос как просроченный',
  );
}

export type FlowEventsStreamHandlers = {
  onFlow: (payload: FlowStatusResponse) => void;
  onHeartbeat?: (payload: string) => void;
  onError?: (event: Event) => void;
};

export function subscribeToFlowEvents(
  sessionId: string,
  handlers: FlowEventsStreamHandlers,
): EventSource {
  const source = new EventSource(
    `${API_BASE_URL}/flows/${sessionId}/events/stream`,
  );

  source.addEventListener('flow', (event) => {
    try {
      const parsed = parseFlowStatusResponse(
        JSON.parse((event as MessageEvent).data),
      );
      handlers.onFlow(parsed);
    } catch (error) {
      console.error('Не удалось разобрать событие flow', error);
      handlers.onError?.(event);
    }
  });

  source.addEventListener('heartbeat', (event) => {
    handlers.onHeartbeat?.((event as MessageEvent).data);
  });

  source.addEventListener('error', (event) => {
    handlers.onError?.(event);
  });

  return source;
}

export async function sendFlowControlCommand(
  sessionId: string,
  command: FlowControlCommand,
  payload?: { stepExecutionId?: string },
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/flows/${sessionId}/control`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify({
      command,
      stepExecutionId: payload?.stepExecutionId,
    }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Команда "${command}" завершилась ошибкой (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }
}

export async function fetchFlowDefinitions(): Promise<FlowDefinitionSummary[]> {
  const response = await fetch(`${API_BASE_URL}/flows/definitions`, {
    headers: { Accept: 'application/json' },
  });
  return parseJsonResponse(
    response,
    FlowDefinitionSummaryListSchema,
    'Не удалось загрузить определения флоу',
  );
}

export async function fetchFlowDefinition(
  definitionId: string,
): Promise<FlowDefinitionDetails> {
  const response = await fetch(
    `${API_BASE_URL}/flows/definitions/${definitionId}`,
    {
      headers: { Accept: 'application/json' },
    },
  );
  return parseJsonResponse(
    response,
    FlowDefinitionDetailsSchema,
    'Не удалось загрузить определение',
  );
}

export async function fetchFlowDefinitionHistory(
  definitionId: string,
): Promise<FlowDefinitionHistoryEntry[]> {
  const response = await fetch(
    `${API_BASE_URL}/flows/definitions/${definitionId}/history`,
    {
      headers: { Accept: 'application/json' },
    },
  );
  return parseJsonResponse(
    response,
    FlowDefinitionHistoryListSchema,
    'Не удалось загрузить историю',
  );
}

export async function createFlowDefinition(payload: {
  name: string;
  description?: string;
  updatedBy?: string;
  definition: unknown;
  changeNotes?: string;
  sourceDefinitionId?: string;
}): Promise<FlowDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/flows/definitions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  return parseJsonResponse(
    response,
    FlowDefinitionDetailsSchema,
    'Не удалось создать определение',
  );
}

export async function updateFlowDefinition(
  definitionId: string,
  payload: {
    description?: string;
    updatedBy?: string;
    changeNotes?: string;
    definition: unknown;
  },
): Promise<FlowDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/flows/definitions/${definitionId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  return parseJsonResponse(
    response,
    FlowDefinitionDetailsSchema,
    'Не удалось обновить определение',
  );
}

export async function publishFlowDefinition(
  definitionId: string,
  payload: { updatedBy?: string; changeNotes?: string },
): Promise<FlowDefinitionDetails> {
  const response = await fetch(
    `${API_BASE_URL}/flows/definitions/${definitionId}/publish`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(payload),
    },
  );
  return parseJsonResponse(
    response,
    FlowDefinitionDetailsSchema,
    'Не удалось опубликовать определение',
  );
}

export async function fetchFlowLaunchPreview(
  definitionId: string,
): Promise<FlowLaunchPreview> {
  const response = await fetch(
    `${API_BASE_URL}/flows/definitions/${definitionId}/launch-preview`,
    {
      headers: { Accept: 'application/json' },
    },
  );

  return parseJsonResponse(
    response,
    FlowLaunchPreviewSchema,
    'Не удалось загрузить информацию о запуске',
  );
}

export async function fetchSessionUsage(
  sessionId: string,
): Promise<SessionUsageResponse> {
  const response = await fetch(`${API_BASE_URL}/llm/sessions/${sessionId}/usage`, {
    headers: {
      Accept: 'application/json',
    },
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(
      message || `Не удалось загрузить статистику использования (статус ${response.status}).`,
    );
  }

  return response.json() as Promise<SessionUsageResponse>;
}
