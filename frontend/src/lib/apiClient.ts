export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? '/api';
export const CHAT_STREAM_URL = `${API_BASE_URL}/llm/chat/stream`;
export const CHAT_SYNC_URL = `${API_BASE_URL}/llm/chat/sync`;

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
    }>;
  }>;
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

export type StructuredSyncResponse = {
  requestId?: string;
  status?: string;
  provider?: StructuredSyncProvider;
  answer?: StructuredSyncAnswer;
  usage?: StructuredSyncUsageStats;
  latencyMs?: number;
  timestamp?: string;
};

export type StructuredSyncCallResult = {
  body: StructuredSyncResponse;
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

export async function requestStructuredSync(
  payload: ChatSyncRequest,
): Promise<StructuredSyncCallResult> {
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
