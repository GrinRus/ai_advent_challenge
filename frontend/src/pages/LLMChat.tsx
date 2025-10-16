import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  CHAT_STREAM_URL,
  fetchChatProviders,
  type ChatProvidersResponse,
} from '../lib/apiClient';
import './LLMChat.css';

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  status?: 'streaming' | 'complete';
  provider?: string;
  model?: string;
};

type StreamPayload = {
  sessionId?: string;
  type?: 'session' | 'token' | 'complete' | 'error';
  content?: string | null;
  newSession?: boolean;
  provider?: string | null;
  model?: string | null;
};

type DrainResult = {
  buffer: string;
  stop: boolean;
};

const generateId = () =>
  typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `id-${Date.now()}-${Math.random().toString(16).slice(2)}`;

const parseStreamPayload = (rawEvent: string): StreamPayload | null => {
  const lines = rawEvent.split('\n');
  let declaredEvent: string | undefined;
  const dataLines: string[] = [];

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();
    if (!line || line.startsWith(':')) {
      continue;
    }

    if (line.startsWith('event:')) {
      declaredEvent = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim());
    }
  }

  if (dataLines.length === 0) {
    return null;
  }

  try {
    const payload = JSON.parse(dataLines.join('\n')) as StreamPayload;
    if (declaredEvent && !payload.type) {
      payload.type = declaredEvent as StreamPayload['type'];
    }
    return payload;
  } catch {
    return null;
  }
};

const drainSseBuffer = (
  buffer: string,
  onEvent: (payload: StreamPayload) => boolean | void,
): DrainResult => {
  let working = buffer;
  let stop = false;

  while (true) {
    const boundary = working.indexOf('\n\n');
    if (boundary === -1) {
      break;
    }

    const rawEvent = working.slice(0, boundary);
    working = working.slice(boundary + 2);

    const payload = parseStreamPayload(rawEvent);
    if (!payload || !payload.type) {
      continue;
    }

    const shouldStop = onEvent(payload);
    if (shouldStop) {
      stop = true;
      break;
    }
  }

  return { buffer: working, stop };
};

const LLMChat = () => {
  const [providerCatalog, setProviderCatalog] = useState<ChatProvidersResponse | null>(null);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const [selectedModel, setSelectedModel] = useState<string>('');
  const [isCatalogLoading, setIsCatalogLoading] = useState<boolean>(true);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [lastProvider, setLastProvider] = useState<string | null>(null);
  const [lastModel, setLastModel] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const messagesContainerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let isCancelled = false;

    const loadProviders = async () => {
      try {
        setCatalogError(null);
        setIsCatalogLoading(true);

        const catalog = await fetchChatProviders();
        if (isCancelled) {
          return;
        }

        if (!catalog.providers || catalog.providers.length === 0) {
          throw new Error('Провайдеры не сконфигурированы на сервере.');
        }

        const defaultProvider =
          catalog.providers.find((provider) => provider.id === catalog.defaultProvider) ??
          catalog.providers[0];

        const defaultModel =
          defaultProvider.models.find((model) => model.id === defaultProvider.defaultModel) ??
          defaultProvider.models[0] ??
          null;

        setProviderCatalog(catalog);
        setSelectedProvider(defaultProvider.id);
        setSelectedModel(defaultModel ? defaultModel.id : '');
      } catch (loadError) {
        if (isCancelled) {
          return;
        }
        const message =
          loadError instanceof Error
            ? loadError.message
            : 'Неизвестная ошибка при загрузке провайдеров.';
        setCatalogError(`Не удалось загрузить провайдеры: ${message}`);
      } finally {
        if (!isCancelled) {
          setIsCatalogLoading(false);
        }
      }
    };

    loadProviders();

    return () => {
      isCancelled = true;
    };
  }, []);

  useEffect(() => {
    if (messagesContainerRef.current) {
      messagesContainerRef.current.scrollTop =
        messagesContainerRef.current.scrollHeight;
    }
  }, [messages]);

  const providerOptions = providerCatalog?.providers ?? [];
  const currentProvider =
    providerOptions.find((provider) => provider.id === selectedProvider) ??
    providerOptions[0] ??
    null;
  const modelOptions = currentProvider?.models ?? [];
  const canSendMessage =
    Boolean(
      selectedProvider &&
        selectedModel &&
        !catalogError &&
        !isCatalogLoading,
    );

  const resolveProviderName = (providerId?: string | null) => {
    if (!providerId) {
      return '';
    }
    const provider =
      providerOptions.find((item) => item.id === providerId) ?? null;
    return provider?.displayName ?? providerId;
  };

  const resolveModelName = (providerId?: string | null, modelId?: string | null) => {
    if (!providerId || !modelId) {
      return '';
    }
    const provider =
      providerOptions.find((item) => item.id === providerId) ?? null;
    const model =
      provider?.models.find((item) => item.id === modelId) ?? null;
    return model?.displayName ?? modelId;
  };

  const handleProviderChange = (providerId: string) => {
    setSelectedProvider(providerId);
    setInfo(null);
    setError(null);
    if (!providerCatalog) {
      setSelectedModel('');
      return;
    }

    const providerEntry =
      providerCatalog.providers.find((provider) => provider.id === providerId) ??
      null;

    if (!providerEntry) {
      setSelectedModel('');
      return;
    }

    const nextModel =
      providerEntry.models.find((model) => model.id === providerEntry.defaultModel) ??
      providerEntry.models[0] ??
      null;
    setSelectedModel(nextModel ? nextModel.id : '');
  };

  const handleModelChange = (modelId: string) => {
    setSelectedModel(modelId);
    setInfo(null);
    setError(null);
  };

  const resetChat = () => {
    if (isStreaming) {
      return;
    }

    setSessionId(null);
    setMessages([]);
    setError(null);
    setInfo(null);
    setLastProvider(null);
    setLastModel(null);
  };

  const stopStreaming = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = input.trim();
    if (!trimmed || isStreaming) {
      return;
    }

    if (!canSendMessage) {
      setInfo('Дождитесь готовности конфигурации перед отправкой запроса.');
      return;
    }

    setInput('');
    await streamMessage(trimmed);
  };

  const streamMessage = async (message: string) => {
    setError(null);
    setInfo(null);
    setIsStreaming(true);

    const userMessage: ChatMessage = {
      id: generateId(),
      role: 'user',
      content: message,
      status: 'complete',
      provider: selectedProvider,
      model: selectedModel,
    };
    setMessages((prev) => [...prev, userMessage]);

    const controller = new AbortController();
    abortControllerRef.current = controller;

    const payload: Record<string, unknown> = { message };
    if (sessionId) {
      payload.sessionId = sessionId;
    }
    if (selectedProvider) {
      payload.provider = selectedProvider;
    }
    if (selectedModel) {
      payload.model = selectedModel;
    }

    let assistantMessageId: string | null = null;
    let aborted = false;

    const appendAssistantChunk = (
      chunk: string,
      provider?: string | null,
      model?: string | null,
    ) => {
      if (!chunk) {
        return;
      }

      if (!assistantMessageId) {
        assistantMessageId = generateId();
        setMessages((prev) => [
          ...prev,
          {
            id: assistantMessageId as string,
            role: 'assistant',
            content: chunk,
            status: 'streaming',
            provider: provider ?? undefined,
            model: model ?? undefined,
          },
        ]);
        return;
      }

      setMessages((prev) => {
        const next = [...prev];
        const index = next.findIndex((item) => item.id === assistantMessageId);
        if (index === -1) {
          next.push({
            id: assistantMessageId as string,
            role: 'assistant',
            content: chunk,
            status: 'streaming',
            provider: provider ?? undefined,
            model: model ?? undefined,
          });
          return next;
        }

        const current = next[index];
        next[index] = {
          ...current,
          content: current.content + chunk,
          provider: provider ?? current.provider,
          model: model ?? current.model,
        };
        return next;
      });
    };

    const finalizeAssistantMessage = (
      fullContent?: string | null,
      provider?: string | null,
      model?: string | null,
    ) => {
      if (!assistantMessageId) {
        assistantMessageId = generateId();
        setMessages((prev) => [
          ...prev,
          {
            id: assistantMessageId as string,
            role: 'assistant',
            content: fullContent ?? '',
            status: 'complete',
            provider: provider ?? undefined,
            model: model ?? undefined,
          },
        ]);
        return;
      }

      setMessages((prev) => {
        const next = [...prev];
        const index = next.findIndex((item) => item.id === assistantMessageId);
        if (index === -1) {
          next.push({
            id: assistantMessageId as string,
            role: 'assistant',
            content: fullContent ?? '',
            status: 'complete',
            provider: provider ?? undefined,
            model: model ?? undefined,
          });
          return next;
        }

        const current = next[index];
        next[index] = {
          ...current,
          content: fullContent ?? current.content,
          status: 'complete',
          provider: provider ?? current.provider,
          model: model ?? current.model,
        };
        return next;
      });
    };

    const handleStreamPayload = (event: StreamPayload): boolean => {
      switch (event.type) {
        case 'session': {
          if (event.sessionId) {
            setSessionId(event.sessionId);
            if (event.newSession) {
              const providerLabel = resolveProviderName(event.provider ?? undefined);
              const modelLabel = resolveModelName(
                event.provider ?? undefined,
                event.model ?? undefined,
              );
              const details =
                [providerLabel, modelLabel].filter(Boolean).join(' · ');
              setInfo(
                details
                  ? `Создан новый диалог (${details})`
                  : 'Создан новый диалог',
              );
            }
          }
          if (event.provider) {
            setLastProvider(event.provider);
          }
          if (event.model) {
            setLastModel(event.model);
          }
          return false;
        }
        case 'token': {
          if (event.content) {
            if (event.provider) {
              setLastProvider(event.provider);
            }
            if (event.model) {
              setLastModel(event.model);
            }
            appendAssistantChunk(event.content, event.provider, event.model);
          }
          return false;
        }
        case 'complete': {
          if (event.provider) {
            setLastProvider(event.provider);
          }
          if (event.model) {
            setLastModel(event.model);
          }
          finalizeAssistantMessage(event.content, event.provider, event.model);
          return false;
        }
        case 'error': {
          finalizeAssistantMessage(undefined, event.provider, event.model);
          if (event.provider) {
            setLastProvider(event.provider);
          }
          if (event.model) {
            setLastModel(event.model);
          }
          setError(
            event.content ??
              'Не удалось получить ответ от модели. Попробуйте ещё раз позже.',
          );
          return true;
        }
        default:
          return false;
      }
    };

    try {
      const response = await fetch(CHAT_STREAM_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });

      if (!response.ok || !response.body) {
        throw new Error(
          `Ошибка при обращении к чату (статус ${response.status})`,
        );
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let stop = false;

      while (!stop) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }

        buffer += decoder.decode(value, { stream: true });
        const result = drainSseBuffer(buffer, handleStreamPayload);
        buffer = result.buffer;
        stop = result.stop;
      }

      buffer += decoder.decode();
      if (buffer) {
        drainSseBuffer(buffer, handleStreamPayload);
      }

      finalizeAssistantMessage();
    } catch (streamError) {
      if (
        streamError instanceof DOMException &&
        streamError.name === 'AbortError'
      ) {
        aborted = true;
      } else if (streamError instanceof Error) {
        setError(streamError.message);
      } else {
        setError('Неизвестная ошибка при общении с моделью.');
      }
    } finally {
      abortControllerRef.current = null;
      setIsStreaming(false);

      if (aborted) {
        finalizeAssistantMessage();
        setInfo('Поток остановлен пользователем.');
      }
    }
  };

  return (
    <div className="llm-chat">
      <div className="llm-chat-panel">
        <div className="llm-chat-header">
          <div className="llm-chat-header-main">
            <h2>LLM Chat</h2>
            {lastProvider && (
              <span className="llm-chat-selection">
                {resolveProviderName(lastProvider)}
                {lastModel
                  ? ` · ${resolveModelName(lastProvider, lastModel)}`
                  : ''}
              </span>
            )}
          </div>
          {sessionId && (
            <span className="llm-chat-session">
              Сессия: <code>{sessionId}</code>
            </span>
          )}
        </div>

        <div className="llm-chat-settings">
          <div className="llm-chat-field">
            <label className="llm-chat-label" htmlFor="llm-chat-provider">
              Провайдер
            </label>
            <select
              id="llm-chat-provider"
              className="llm-chat-select"
              value={selectedProvider}
              onChange={(event) => handleProviderChange(event.target.value)}
              disabled={
                isCatalogLoading || isStreaming || providerOptions.length === 0
              }
            >
              {providerOptions.length === 0 ? (
                <option value="">
                  {isCatalogLoading
                    ? 'Загрузка…'
                    : 'Нет доступных провайдеров'}
                </option>
              ) : null}
              {providerOptions.map((provider) => (
                <option key={provider.id} value={provider.id}>
                  {provider.displayName ?? provider.id}
                </option>
              ))}
            </select>
          </div>
          <div className="llm-chat-field">
            <label className="llm-chat-label" htmlFor="llm-chat-model">
              Модель
            </label>
            <select
              id="llm-chat-model"
              className="llm-chat-select"
              value={selectedModel}
              onChange={(event) => handleModelChange(event.target.value)}
              disabled={
                isCatalogLoading || isStreaming || modelOptions.length === 0
              }
            >
              {modelOptions.length === 0 ? (
                <option value="">
                  {isCatalogLoading
                    ? 'Загрузка…'
                    : currentProvider
                    ? 'Модели недоступны'
                    : 'Выберите провайдера'}
                </option>
              ) : null}
              {modelOptions.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.displayName ?? model.id}
                  {model.tier ? ` · ${model.tier}` : ''}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div ref={messagesContainerRef} className="llm-chat-messages">
          {messages.length === 0 ? (
            <div className="llm-chat-empty">
              Введите сообщение, чтобы начать диалог с моделью.
            </div>
          ) : (
            messages.map((message) => {
              const providerLabel = message.provider
                ? resolveProviderName(message.provider)
                : '';
              const modelLabel = message.model
                ? resolveModelName(message.provider, message.model)
                : '';
              const hasMeta = Boolean(providerLabel || modelLabel);

              return (
                <div
                  key={message.id}
                  className={`llm-chat-message ${message.role}`}
                >
                  {hasMeta && (
                    <div className="llm-chat-message-meta">
                      {providerLabel && (
                        <span className="llm-chat-meta-chip">
                          {providerLabel}
                        </span>
                      )}
                      {modelLabel && (
                        <span className="llm-chat-meta-chip secondary">
                          {modelLabel}
                        </span>
                      )}
                    </div>
                  )}
                  <div className="llm-chat-bubble">{message.content}</div>
                  {message.status === 'streaming' && (
                    <span className="llm-chat-message-status">
                      Модель отвечает…
                    </span>
                  )}
                </div>
              );
            })
          )}
        </div>

        {(catalogError ||
          error ||
          info ||
          isStreaming ||
          (isCatalogLoading && messages.length === 0)) && (
          <div className="llm-chat-status">
            {catalogError && (
              <span className="llm-chat-error">{catalogError}</span>
            )}
            {!catalogError && error && (
              <span className="llm-chat-error">{error}</span>
            )}
            {!catalogError && !error && info && (
              <span className="llm-chat-info">{info}</span>
            )}
            {!catalogError && !error && !info && isStreaming && (
              <span className="llm-chat-info">Получаем ответ от модели…</span>
            )}
            {!catalogError &&
              !error &&
              !info &&
              !isStreaming &&
              isCatalogLoading && (
                <span className="llm-chat-info">
                  Загружаем конфигурацию чата…
                </span>
              )}
          </div>
        )}

        <form className="llm-chat-form" onSubmit={handleSubmit}>
          <label className="llm-chat-label" htmlFor="llm-chat-input">
            Сообщение
          </label>
          <textarea
            id="llm-chat-input"
            className="llm-chat-textarea"
            placeholder="Напишите вопрос или запрос для модели…"
            value={input}
            onChange={(event) => setInput(event.target.value)}
            disabled={isStreaming}
          />
          <div className="llm-chat-actions">
            <button
              type="submit"
              className="llm-chat-button primary"
              disabled={isStreaming || !input.trim() || !canSendMessage}
            >
              Отправить
            </button>
            <button
              type="button"
              className="llm-chat-button danger"
              onClick={stopStreaming}
              disabled={!isStreaming}
            >
              Остановить
            </button>
            <button
              type="button"
              className="llm-chat-button ghost"
              onClick={resetChat}
              disabled={isStreaming || messages.length === 0}
            >
              Новый диалог
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default LLMChat;
