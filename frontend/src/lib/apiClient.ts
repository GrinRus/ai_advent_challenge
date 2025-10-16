export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? '/api';
export const CHAT_STREAM_URL = `${API_BASE_URL}/llm/chat/stream`;

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
