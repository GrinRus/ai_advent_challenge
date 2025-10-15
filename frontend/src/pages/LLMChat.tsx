import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { CHAT_STREAM_URL } from '../lib/apiClient';
import './LLMChat.css';

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  status?: 'streaming' | 'complete';
};

type StreamPayload = {
  sessionId?: string;
  type?: 'session' | 'token' | 'complete' | 'error';
  content?: string | null;
  newSession?: boolean;
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
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const messagesContainerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (messagesContainerRef.current) {
      messagesContainerRef.current.scrollTop =
        messagesContainerRef.current.scrollHeight;
    }
  }, [messages]);

  const resetChat = () => {
    if (isStreaming) {
      return;
    }

    setSessionId(null);
    setMessages([]);
    setError(null);
    setInfo(null);
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
    };
    setMessages((prev) => [...prev, userMessage]);

    const controller = new AbortController();
    abortControllerRef.current = controller;

    const payload: Record<string, unknown> = { message };
    if (sessionId) {
      payload.sessionId = sessionId;
    }

    let assistantMessageId: string | null = null;
    let aborted = false;

    const appendAssistantChunk = (chunk: string) => {
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
          });
          return next;
        }

        const current = next[index];
        next[index] = {
          ...current,
          content: current.content + chunk,
        };
        return next;
      });
    };

    const finalizeAssistantMessage = (fullContent?: string | null) => {
      if (!assistantMessageId) {
        assistantMessageId = generateId();
        setMessages((prev) => [
          ...prev,
          {
            id: assistantMessageId as string,
            role: 'assistant',
            content: fullContent ?? '',
            status: 'complete',
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
          });
          return next;
        }

        const current = next[index];
        next[index] = {
          ...current,
          content: fullContent ?? current.content,
          status: 'complete',
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
              setInfo('Создан новый диалог');
            }
          }
          return false;
        }
        case 'token': {
          if (event.content) {
            appendAssistantChunk(event.content);
          }
          return false;
        }
        case 'complete': {
          finalizeAssistantMessage(event.content);
          return false;
        }
        case 'error': {
          finalizeAssistantMessage();
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
          <h2>LLM Chat</h2>
          {sessionId && (
            <span className="llm-chat-session">
              Сессия: <code>{sessionId}</code>
            </span>
          )}
        </div>

        <div ref={messagesContainerRef} className="llm-chat-messages">
          {messages.length === 0 ? (
            <div className="llm-chat-empty">
              Введите сообщение, чтобы начать диалог с моделью.
            </div>
          ) : (
            messages.map((message) => (
              <div
                key={message.id}
                className={`llm-chat-message ${message.role}`}
              >
                <div className="llm-chat-bubble">{message.content}</div>
                {message.status === 'streaming' && (
                  <span className="llm-chat-message-status">
                    Модель отвечает…
                  </span>
                )}
              </div>
            ))
          )}
        </div>

        {(error || info || isStreaming) && (
          <div className="llm-chat-status">
            {error && <span className="llm-chat-error">{error}</span>}
            {!error && info && <span className="llm-chat-info">{info}</span>}
            {!error && !info && isStreaming && (
              <span className="llm-chat-info">Получаем ответ от модели…</span>
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
              disabled={isStreaming || !input.trim()}
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
