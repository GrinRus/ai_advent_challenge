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
