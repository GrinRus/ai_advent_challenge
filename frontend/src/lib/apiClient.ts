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

export type AgentCapabilityResponse = {
  capability: string;
  payload?: unknown;
};

export type AgentVersionResponse = {
  id: string;
  version: number;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  providerType?: string;
  providerId: string;
  modelId: string;
  systemPrompt?: string;
  defaultOptions?: unknown;
  toolBindings?: unknown;
  costProfile?: unknown;
  syncOnly: boolean;
  maxTokens?: number;
  createdBy?: string;
  updatedBy?: string;
  createdAt?: string;
  publishedAt?: string;
  capabilities: AgentCapabilityResponse[];
};

export type AgentDefinitionSummary = {
  id: string;
  identifier: string;
  displayName: string;
  description?: string;
  active: boolean;
  createdBy?: string;
  updatedBy?: string;
  createdAt?: string;
  updatedAt?: string;
  latestVersion?: number | null;
  latestPublishedVersion?: number | null;
  latestPublishedAt?: string | null;
};

export type AgentDefinitionDetails = {
  id: string;
  identifier: string;
  displayName: string;
  description?: string;
  active: boolean;
  createdBy?: string;
  updatedBy?: string;
  createdAt?: string;
  updatedAt?: string;
  versions: AgentVersionResponse[];
};

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

export type AgentCapabilityPayload = {
  capability: string;
  payload?: unknown;
};

export type AgentVersionPayload = {
  providerType?: string;
  providerId: string;
  modelId: string;
  systemPrompt: string;
  defaultOptions?: unknown;
  toolBindings?: unknown;
  costProfile?: unknown;
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

export type FlowStartResponse = {
  sessionId: string;
  status: string;
  startedAt?: string;
};

export type FlowState = {
  sessionId: string;
  status: string;
  currentStepId?: string | null;
  stateVersion: number;
  currentMemoryVersion: number;
  startedAt?: string | null;
  completedAt?: string | null;
  flowDefinitionId: string;
  flowDefinitionVersion: number;
};

export type FlowEvent = {
  eventId: number;
  type: string;
  status?: string | null;
  traceId?: string | null;
  spanId?: string | null;
  cost?: number | null;
  tokensPrompt?: number | null;
  tokensCompletion?: number | null;
  createdAt?: string | null;
  payload?: unknown;
};

export type FlowStatusResponse = {
  state: FlowState;
  events: FlowEvent[];
  nextSinceEventId: number;
};

export type FlowControlCommand = 'pause' | 'resume' | 'cancel' | 'retryStep';

export type FlowDefinitionSummary = {
  id: string;
  name: string;
  version: number;
  status: string;
  active: boolean;
  description?: string | null;
  updatedBy?: string | null;
  updatedAt?: string;
  publishedAt?: string | null;
};

export type FlowDefinitionDetails = FlowDefinitionSummary & {
  definition: unknown;
  createdAt?: string;
};

export type ChatRequestOverridesDto = {
  temperature?: number | null;
  topP?: number | null;
  maxTokens?: number | null;
};

export type FlowLaunchPricing = {
  inputPer1KTokens?: number | null;
  outputPer1KTokens?: number | null;
  currency?: string | null;
};

export type FlowLaunchCostEstimate = {
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  inputCost?: number | null;
  outputCost?: number | null;
  totalCost?: number | null;
  currency?: string | null;
};

export type FlowLaunchAgent = {
  agentVersionId: string;
  agentVersionNumber: number;
  agentDefinitionId?: string | null;
  agentIdentifier?: string | null;
  agentDisplayName?: string | null;
  providerType: string;
  providerId: string;
  providerDisplayName?: string | null;
  modelId: string;
  modelDisplayName?: string | null;
  modelContextWindow?: number | null;
  modelMaxOutputTokens?: number | null;
  syncOnly: boolean;
  maxTokens?: number | null;
  defaultOptions?: unknown | null;
  costProfile?: unknown | null;
  pricing: FlowLaunchPricing;
};

export type FlowLaunchMemoryRead = {
  channel: string;
  limit: number;
};

export type FlowLaunchMemoryWrite = {
  channel: string;
  mode: string;
  payload?: unknown | null;
};

export type FlowLaunchTransitions = {
  onSuccess?: string | null;
  completeOnSuccess: boolean;
  onFailure?: string | null;
  failFlowOnFailure: boolean;
};

export type FlowLaunchStep = {
  id: string;
  name?: string | null;
  prompt?: string | null;
  agent: FlowLaunchAgent;
  overrides?: ChatRequestOverridesDto | null;
  memoryReads: FlowLaunchMemoryRead[];
  memoryWrites: FlowLaunchMemoryWrite[];
  transitions: FlowLaunchTransitions;
  maxAttempts: number;
  estimate: FlowLaunchCostEstimate;
};

export type FlowLaunchPreview = {
  definitionId: string;
  definitionName: string;
  definitionVersion: number;
  description?: string | null;
  startStepId?: string | null;
  steps: FlowLaunchStep[];
  totalEstimate: FlowLaunchCostEstimate;
};

export type FlowDefinitionHistoryEntry = {
  id: number;
  version: number;
  status: string;
  definition: unknown;
  changeNotes?: string | null;
  createdBy?: string | null;
  createdAt?: string;
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

async function handleJsonResponse<T>(response: Response, errorPrefix: string): Promise<T> {
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${errorPrefix} (status ${response.status}): ${text || response.statusText}`);
  }
  return (await response.json()) as T;
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

  const request = fetch(`${API_BASE_URL}/agents/definitions`, {
    headers: { Accept: 'application/json' },
  })
    .then((response) =>
      handleJsonResponse<AgentDefinitionSummary[]>(
        response,
        'Не удалось получить список агентов',
      ),
    )
    .then((data) => {
      agentDefinitionsCache = { data, fetchedAt: Date.now() };
      agentDefinitionsPromise = null;
      return data;
    })
    .catch((error) => {
      agentDefinitionsPromise = null;
      throw error;
    });

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

  const request = fetch(`${API_BASE_URL}/agents/definitions/${id}`, {
    headers: { Accept: 'application/json' },
  })
    .then((response) =>
      handleJsonResponse<AgentDefinitionDetails>(
        response,
        'Не удалось получить информацию об агенте',
      ),
    )
    .then((data) => {
      agentDefinitionDetailsCache.set(id, { data, fetchedAt: Date.now() });
      agentDefinitionDetailsPromises.delete(id);
      return data;
    })
    .catch((error) => {
      agentDefinitionDetailsPromises.delete(id);
      throw error;
    });

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
  const data = await handleJsonResponse<AgentDefinitionDetails>(
    response,
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
  const data = await handleJsonResponse<AgentDefinitionDetails>(
    response,
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
  const data = await handleJsonResponse<AgentDefinitionDetails>(
    response,
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
  const data = await handleJsonResponse<AgentVersionResponse>(
    response,
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
  const data = await handleJsonResponse<AgentVersionResponse>(
    response,
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
  const data = await handleJsonResponse<AgentVersionResponse>(
    response,
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
  const parseJson = () => (raw ? JSON.parse(raw) : {});

  if (!response.ok) {
    let message = `Не удалось получить ответ (статус ${response.status})`;
    try {
      const parsed = parseJson();
      if (parsed && typeof parsed === 'object' && 'message' in parsed) {
        message = String((parsed as { message: unknown }).message);
      } else if (raw) {
        message = raw;
      }
    } catch {
      if (raw) {
        message = raw;
      }
    }

    throw new Error(message);
  }

  const body = parseJson() as ChatSyncResponse;
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
  const parseJson = () => (raw ? JSON.parse(raw) : {});

  if (!response.ok) {
    let message = `Не удалось получить структурированный ответ (статус ${response.status})`;
    try {
      const parsed = parseJson();
      if (parsed && typeof parsed === 'object' && 'message' in parsed) {
        message = String((parsed as { message: unknown }).message);
      } else if (raw) {
        message = raw;
      }
    } catch {
      if (raw) {
        message = raw;
      }
    }

    throw new Error(message);
  }

  const body = parseJson() as StructuredSyncResponse;
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
    parameters?: unknown;
    sharedContext?: unknown;
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

  return (await response.json()) as FlowStartResponse;
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

  return (await response.json()) as FlowStatusResponse;
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

  return (await response.json()) as FlowStatusResponse;
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
  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось загрузить определения флоу (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }
  return (await response.json()) as FlowDefinitionSummary[];
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
  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось загрузить определение (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }
  return (await response.json()) as FlowDefinitionDetails;
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
  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось загрузить историю (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }
  return (await response.json()) as FlowDefinitionHistoryEntry[];
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
  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось создать определение (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }
  return (await response.json()) as FlowDefinitionDetails;
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
  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось обновить определение (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }
  return (await response.json()) as FlowDefinitionDetails;
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
  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось опубликовать определение (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }
  return (await response.json()) as FlowDefinitionDetails;
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

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось загрузить информацию о запуске (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }

  return (await response.json()) as FlowLaunchPreview;
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
