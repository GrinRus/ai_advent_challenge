import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  fetchFlowSnapshot,
  pollFlowStatus,
  sendFlowControlCommand,
  startFlow,
} from '../lib/apiClient';
import type {
  FlowControlCommand,
  FlowEvent,
  FlowStatusResponse,
  FlowState,
} from '../lib/apiClient';
import './FlowSessions.css';

type PollState = {
  nextSince?: number;
  stateVersion?: number;
};

const FLOW_POLL_TIMEOUT_MS = 5000;

const humanReadableStatus = (status?: string) => {
  if (!status) return 'неизвестно';
  const localized: Record<string, string> = {
    PENDING: 'ожидание',
    RUNNING: 'выполняется',
    PAUSED: 'пауза',
    COMPLETED: 'завершён',
    FAILED: 'ошибка',
    CANCELLED: 'отменён',
    ABORTED: 'прерван',
  };
  return localized[status] ?? status.toLowerCase();
};

const formatDateTime = (value?: string | null) =>
  value ? new Date(value).toLocaleString() : '—';

const FlowSessionsPage = () => {
  const [flowId, setFlowId] = useState('');
  const [parameters, setParameters] = useState('');
  const [sharedContext, setSharedContext] = useState('');
  const [sessionId, setSessionId] = useState<string | undefined>();
  const [status, setStatus] = useState<FlowState | null>(null);
  const [events, setEvents] = useState<FlowEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isPolling, setIsPolling] = useState(false);
  const [isStarting, setIsStarting] = useState(false);
  const [isSnapshotLoading, setIsSnapshotLoading] = useState(false);
  const pollRef = useRef<PollState>({});
  const abortRef = useRef(false);
  const routeSyncGuardRef = useRef(false);
  const navigate = useNavigate();
  const { sessionId: routeSessionId } = useParams<{ sessionId?: string }>();
  const [retryStepId, setRetryStepId] = useState('');

  const resetSessionData = useCallback((initial?: FlowStatusResponse) => {
    if (initial) {
      setStatus(initial.state);
      setEvents(initial.events ?? []);
      pollRef.current = {
        nextSince: initial.nextSinceEventId,
        stateVersion: initial.state.stateVersion,
      };
    } else {
      setStatus(null);
      setEvents([]);
      pollRef.current = {};
    }
  }, []);

  const appendEvents = useCallback((incoming: FlowEvent[]) => {
    if (!incoming.length) {
      return;
    }
    setEvents((prev) => {
      const existing = new Map<number, FlowEvent>();
      prev.forEach((evt) => existing.set(evt.eventId, evt));
      incoming.forEach((evt) => existing.set(evt.eventId, evt));
      return Array.from(existing.values()).sort((a, b) => a.eventId - b.eventId);
    });
  }, []);

  useEffect(() => {
    if (routeSyncGuardRef.current) {
      routeSyncGuardRef.current = false;
      return;
    }

    if (routeSessionId && routeSessionId !== sessionId) {
      setSessionId(routeSessionId);
      resetSessionData();
    } else if (!routeSessionId && sessionId) {
      setSessionId(undefined);
      resetSessionData();
    }
  }, [resetSessionData, routeSessionId, sessionId]);

  const parseJson = useCallback((value: string): unknown | undefined => {
    const trimmed = value.trim();
    if (!trimmed) {
      return undefined;
    }
    try {
      return JSON.parse(trimmed);
    } catch (e) {
      throw new Error('Некорректный JSON: ' + (e as Error).message);
    }
  }, []);

  const handleStart = useCallback(async () => {
    if (!flowId.trim()) {
      setError('Укажите идентификатор флоу');
      return;
    }

    try {
      setIsStarting(true);
      setError(null);
      const payload = {
        parameters: parseJson(parameters),
        sharedContext: parseJson(sharedContext),
      };
      const response = await startFlow(flowId.trim(), payload);
      routeSyncGuardRef.current = true;
      navigate(`/flows/sessions/${response.sessionId}`);
      setSessionId(response.sessionId);
      setStatus({
        sessionId: response.sessionId,
        status: response.status,
        stateVersion: 0,
        currentMemoryVersion: 0,
        currentStepId: null,
        startedAt: response.startedAt ?? null,
        completedAt: null,
        flowDefinitionId: flowId.trim(),
        flowDefinitionVersion: 0,
      });
      setEvents([]);
      pollRef.current = {};
      setIsPolling(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setIsStarting(false);
    }
  }, [flowId, navigate, parameters, parseJson, sharedContext]);

  const loadSnapshot = useCallback(async () => {
    if (!sessionId) {
      setError('Нет активной сессии');
      return;
    }
    try {
      setError(null);
      setIsSnapshotLoading(true);
      const snapshot = await fetchFlowSnapshot(sessionId);
      resetSessionData(snapshot);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setIsSnapshotLoading(false);
    }
  }, [resetSessionData, sessionId]);

  useEffect(() => {
    if (!isPolling || !sessionId) {
      return undefined;
    }

    abortRef.current = false;

    const poll = async () => {
      while (!abortRef.current) {
        try {
          const response = await pollFlowStatus(sessionId, {
            sinceEventId: pollRef.current.nextSince,
            stateVersion: pollRef.current.stateVersion,
            timeoutMs: FLOW_POLL_TIMEOUT_MS,
          });

          if (abortRef.current) {
            return;
          }

          if (response) {
            setStatus(response.state);
            appendEvents(response.events);
            pollRef.current = {
              nextSince: response.nextSinceEventId,
              stateVersion: response.state.stateVersion,
            };

            if (response.state.status?.endsWith('ED')) {
              setIsPolling(false);
              break;
            }
          }
        } catch (e) {
          setError((e as Error).message);
          setIsPolling(false);
          return;
        }
      }
    };

    poll();

    return () => {
      abortRef.current = true;
    };
  }, [appendEvents, isPolling, sessionId]);

  const handleControl = useCallback(
    async (command: FlowControlCommand) => {
      if (!sessionId) {
        setError('Сначала выберите/запустите сессию');
        return;
      }
      try {
        setError(null);
        await sendFlowControlCommand(sessionId, command, {
          stepExecutionId: retryStepId || undefined,
        });
        await loadSnapshot();
      } catch (e) {
        setError((e as Error).message);
      }
    },
    [loadSnapshot, retryStepId, sessionId],
  );

  const latestStepId = useMemo(() => {
    const reversed = [...events].reverse();
    const executionEvent = reversed.find((evt) =>
      evt.type?.toLowerCase().startsWith('step_'),
    );
    return executionEvent?.payload && typeof executionEvent.payload === 'object'
      ? (executionEvent.payload as { stepId?: string }).stepId ?? undefined
      : undefined;
  }, [events]);

  useEffect(() => {
    if (!retryStepId && latestStepId) {
      setRetryStepId(latestStepId);
    }
  }, [latestStepId, retryStepId]);

  const hasSession = Boolean(sessionId);
  const showStatusSkeleton =
    hasSession && (isStarting || isSnapshotLoading || (isPolling && !status));
  const showEventSkeleton = hasSession && isPolling && events.length === 0;

  return (
    <div className="flows-page">
      <h2>Оркестрация флоу</h2>

      <section className="flow-section">
        <h3>Запуск флоу</h3>
        <label className="flow-label">
          Flow ID
          <input
            type="text"
            value={flowId}
            onChange={(e) => setFlowId(e.target.value)}
            placeholder="UUID флоу"
          />
        </label>
        <label className="flow-label">
          Parameters (JSON)
          <textarea
            value={parameters}
            onChange={(e) => setParameters(e.target.value)}
            placeholder={'{\n  "input": "value"\n}'}
          />
        </label>
        <label className="flow-label">
          Shared context (JSON)
          <textarea
            value={sharedContext}
            onChange={(e) => setSharedContext(e.target.value)}
            placeholder={'{\n  "notes": "value"\n}'}
          />
        </label>
        <button
          type="button"
          className="primary-btn"
          onClick={handleStart}
          disabled={isStarting}
        >
          {isStarting ? 'Запуск...' : 'Запустить флоу'}
        </button>
      </section>

      <section className="flow-section">
        <h3>Мониторинг</h3>
        <label className="flow-label">
          Session ID
          <input
            type="text"
            value={sessionId ?? ''}
            onChange={(e) => {
              const value = e.target.value.trim();
              setSessionId(value || undefined);
              resetSessionData();
              routeSyncGuardRef.current = true;
              if (value) {
                navigate(`/flows/sessions/${value}`);
              } else {
                navigate('/flows/sessions');
              }
            }}
            placeholder="UUID запущенной сессии"
          />
        </label>
        <div className="flow-actions">
          <button type="button" onClick={loadSnapshot} disabled={!sessionId}>
            Загрузить snapshot
          </button>
          <button
            type="button"
            onClick={() => setIsPolling(true)}
            disabled={!sessionId || isPolling}
          >
            Начать long-poll
          </button>
          <button
            type="button"
            onClick={() => {
              abortRef.current = true;
              setIsPolling(false);
            }}
            disabled={!isPolling}
          >
            Остановить poll
          </button>
        </div>

        {!hasSession ? (
          <p className="flow-empty flow-empty--inline">
            Укажите Session ID или запустите новый флоу, чтобы увидеть статус выполнения.
          </p>
        ) : showStatusSkeleton ? (
          <div className="flow-status-card flow-status-card--skeleton" aria-busy="true">
            <div className="skeleton skeleton-line skeleton-line--wide" />
            <div className="skeleton skeleton-line" />
            <div className="skeleton skeleton-line skeleton-line--short" />
            <div className="skeleton skeleton-line" />
            <div className="skeleton skeleton-line skeleton-line--short" />
          </div>
        ) : status ? (
          <div className="flow-status-card">
            <div>
              <strong>Статус:</strong> {humanReadableStatus(status.status)}
            </div>
            <div>
              <strong>Текущий шаг:</strong> {status.currentStepId ?? '—'}
            </div>
            <div>
              <strong>Версия состояния:</strong> {status.stateVersion}
            </div>
            <div>
              <strong>Старт:</strong> {formatDateTime(status.startedAt)}
            </div>
            <div>
              <strong>Завершение:</strong> {formatDateTime(status.completedAt)}
            </div>
          </div>
        ) : (
          <p className="flow-empty flow-empty--inline">
            Статус появится после первого обновления. Используйте snapshot или long-poll, чтобы получить свежие данные.
          </p>
        )}

        <div className="flow-control">
          <h4>Управление</h4>
          <div className="flow-control-buttons">
            <button
              type="button"
              onClick={() => handleControl('pause')}
              disabled={!sessionId}
            >
              Пауза
            </button>
            <button
              type="button"
              onClick={() => handleControl('resume')}
              disabled={!sessionId}
            >
              Возобновить
            </button>
            <button
              type="button"
              onClick={() => handleControl('cancel')}
              disabled={!sessionId}
              className="danger-btn"
            >
              Отменить
            </button>
          </div>
          <div className="retry-row">
            <label>
              retryStep ID
              <input
                type="text"
                value={retryStepId}
                onChange={(e) => setRetryStepId(e.target.value)}
                placeholder="UUID step execution"
              />
            </label>
            <button
              type="button"
              onClick={() => handleControl('retryStep')}
              disabled={!sessionId || !retryStepId.trim()}
            >
              Повторить шаг
            </button>
          </div>
        </div>
      </section>

      <section className="flow-section">
        <h3>События</h3>
        {!hasSession ? (
          <p className="flow-empty">Чтобы просматривать события, выберите активную сессию</p>
        ) : showEventSkeleton ? (
          <ul className="flow-events flow-events--skeleton" aria-busy="true">
            {[0, 1, 2].map((idx) => (
              <li key={idx} className="flow-event-item flow-event-item--skeleton">
                <div className="skeleton skeleton-line skeleton-line--wide" />
                <div className="skeleton skeleton-line skeleton-line--short" />
                <div className="skeleton skeleton-block" />
              </li>
            ))}
          </ul>
        ) : events.length === 0 ? (
          <p className="flow-empty">Событий пока нет — дождитесь результатов long-poll или выполните snapshot.</p>
        ) : (
          <ul className="flow-events">
            {events.map((event) => (
              <li key={event.eventId} className="flow-event-item">
                <div className="event-header">
                  <span className="event-type">{event.type}</span>
                  <span className="event-timestamp">
                    {formatDateTime(event.createdAt)}
                  </span>
                </div>
                <div className="event-meta">
                  <span>
                    Статус: {event.status ?? '—'}
                  </span>
                  {event.cost != null && (
                    <span>Стоимость: {event.cost.toFixed(6)}</span>
                  )}
                  {event.tokensPrompt != null && (
                    <span>Токены prompt: {event.tokensPrompt}</span>
                  )}
                  {event.tokensCompletion != null && (
                    <span>Токены completion: {event.tokensCompletion}</span>
                  )}
                </div>
                {event.payload != null && (
                  <details>
                    <summary>Payload</summary>
                    <pre>{JSON.stringify(event.payload, null, 2)}</pre>
                  </details>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {error && <div className="flow-error">{error}</div>}
    </div>
  );
};

export default FlowSessionsPage;
