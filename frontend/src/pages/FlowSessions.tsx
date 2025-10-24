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
  FlowTelemetrySnapshot,
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
    WAITING_STEP_APPROVAL: 'ожидает подтверждения шага',
  };
  return localized[status] ?? status.toLowerCase();
};

const formatDateTime = (value?: string | null) =>
  value ? new Date(value).toLocaleString() : '—';

const formatTokens = (value?: number | null) =>
  value != null ? value.toLocaleString('ru-RU') : '—';

const formatCost = (value?: number | null, currency?: string) => {
  if (value == null) {
    return '—';
  }
  const formatted = value >= 0.01 ? value.toFixed(2) : value.toFixed(4);
  return `${formatted} ${currency ?? 'USD'}`;
};

const eventLabels: Record<string, string> = {
  FLOW_STARTED: 'Флоу запущен',
  FLOW_COMPLETED: 'Флоу завершён',
  FLOW_FAILED: 'Флоу завершился с ошибкой',
  FLOW_PAUSED: 'Флоу на паузе',
  FLOW_RESUMED: 'Флоу возобновлён',
  FLOW_CANCELLED: 'Флоу отменён',
  STEP_STARTED: 'Шаг запущен',
  STEP_COMPLETED: 'Шаг выполнен',
  STEP_FAILED: 'Шаг завершился ошибкой',
  STEP_SKIPPED: 'Шаг пропущен',
  STEP_WAITING_APPROVAL: 'Ожидание подтверждения шага',
  STEP_RETRY_SCHEDULED: 'Назначен повтор шага',
};

const eventAccent = (type?: string) => {
  if (!type) {
    return 'neutral';
  }
  if (type.includes('FAILED')) {
    return 'danger';
  }
  if (type.includes('COMPLETED')) {
    return 'success';
  }
  if (type.includes('WAITING')) {
    return 'warning';
  }
  if (type.includes('PAUSED')) {
    return 'warning';
  }
  if (type.includes('RETRY')) {
    return 'info';
  }
  return 'neutral';
};

const FlowSessionsPage = () => {
  const [flowId, setFlowId] = useState('');
  const [parameters, setParameters] = useState('');
  const [sharedContext, setSharedContext] = useState('');
  const [sessionId, setSessionId] = useState<string | undefined>();
  const [status, setStatus] = useState<FlowState | null>(null);
  const [events, setEvents] = useState<FlowEvent[]>([]);
  const [telemetry, setTelemetry] = useState<FlowTelemetrySnapshot | null>(null);
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
  const handleExportEvents = useCallback(() => {
    if (events.length === 0) {
      return;
    }
    const payload = {
      sessionId,
      generatedAt: new Date().toISOString(),
      events,
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], {
      type: 'application/json;charset=utf-8',
    });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `flow-events-${sessionId ?? 'unknown'}.json`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  }, [events, sessionId]);

  const resetSessionData = useCallback((initial?: FlowStatusResponse) => {
    if (initial) {
      setStatus(initial.state);
      setEvents(initial.events ?? []);
      setTelemetry(initial.telemetry ?? null);
      pollRef.current = {
        nextSince: initial.nextSinceEventId,
        stateVersion: initial.state.stateVersion,
      };
    } else {
      setStatus(null);
      setEvents([]);
      setTelemetry(null);
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
      setTelemetry(null);
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
            if (response.telemetry) {
              setTelemetry(response.telemetry);
            }
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

  const sharedContextText = useMemo(() => {
    if (!status?.sharedContext) {
      return '—';
    }
    try {
      return JSON.stringify(status.sharedContext, null, 2);
    } catch (error) {
      return 'Не удалось сериализовать shared context';
    }
  }, [status?.sharedContext]);

  const telemetryStats = useMemo(() => {
    if (!telemetry) {
      return null;
    }
    const totalAttempts = telemetry.stepsCompleted + telemetry.stepsFailed;
    const progressPercent = totalAttempts
      ? Math.min(100, Math.round((telemetry.stepsCompleted / totalAttempts) * 100))
      : status?.status?.startsWith('COMPLETED')
      ? 100
      : 0;
    return {
      progressPercent,
      totalAttempts,
    };
  }, [status?.status, telemetry]);

  const hasSharedContext = status?.sharedContext !== undefined && status?.sharedContext !== null;

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
            {telemetryStats && (
              <div className="flow-progress">
                <div className="flow-progress-label">
                  Выполнено шагов: {telemetry?.stepsCompleted ?? 0}
                  {telemetryStats.totalAttempts > 0 ? ` из ${telemetryStats.totalAttempts}` : ''}
                </div>
                <div className="flow-progress-bar">
                  <div
                    className="flow-progress-bar__fill"
                    style={{ width: `${telemetryStats.progressPercent}%` }}
                    aria-valuenow={telemetryStats.progressPercent}
                    aria-valuemin={0}
                    aria-valuemax={100}
                  />
                </div>
                <span className="flow-progress-percent">{telemetryStats.progressPercent}%</span>
              </div>
            )}
            {telemetry && (
              <div className="flow-telemetry">
                <header>Телеметрия</header>
                <dl>
                  <div>
                    <dt>Успешных шагов</dt>
                    <dd>{telemetry.stepsCompleted}</dd>
                  </div>
                  <div>
                    <dt>Ошибок</dt>
                    <dd>{telemetry.stepsFailed}</dd>
                  </div>
                  <div>
                    <dt>Запланированных ретраев</dt>
                    <dd>{telemetry.retriesScheduled}</dd>
                  </div>
                  <div>
                    <dt>Стоимость</dt>
                    <dd>{formatCost(telemetry.totalCostUsd, 'USD')}</dd>
                  </div>
                  <div>
                    <dt>Токены (prompt)</dt>
                    <dd>{formatTokens(telemetry.promptTokens)}</dd>
                  </div>
                  <div>
                    <dt>Токены (completion)</dt>
                    <dd>{formatTokens(telemetry.completionTokens)}</dd>
                  </div>
                  <div>
                    <dt>Обновлено</dt>
                    <dd>{formatDateTime(telemetry.lastUpdated)}</dd>
                  </div>
                </dl>
              </div>
            )}
            {hasSharedContext && (
              <details className="flow-shared-context">
                <summary>Shared context</summary>
                <pre>{sharedContextText}</pre>
              </details>
            )}
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
        <div className="flow-events-toolbar">
          <button type="button" onClick={handleExportEvents} disabled={events.length === 0}>
            Экспортировать события
          </button>
        </div>
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
          <div className="flow-timeline" role="list">
            {events.map((event, index) => {
              const accent = eventAccent(event.type);
              const label = event.type ? eventLabels[event.type] ?? event.type : 'Событие';
              const stepFromPayload =
                event.payload && typeof event.payload === 'object'
                  ? (event.payload as { stepId?: string; stepName?: string }).stepId ?? undefined
                  : undefined;
              const showConnector = index !== events.length - 1;

              return (
                <article
                  key={event.eventId}
                  className={`timeline-item timeline-item--${accent}`}
                  role="listitem"
                >
                  <div className="timeline-item__marker" aria-hidden="true">
                    <span />
                  </div>
                  <div className="timeline-item__content">
                    <header>
                      <span className="timeline-item__title">{label}</span>
                      <time className="timeline-item__time">{formatDateTime(event.createdAt)}</time>
                    </header>
                    <dl className="timeline-item__meta">
                      <div>
                        <dt>Статус</dt>
                        <dd>{event.status ?? '—'}</dd>
                      </div>
                      {stepFromPayload && (
                        <div>
                          <dt>Шаг</dt>
                          <dd>{stepFromPayload}</dd>
                        </div>
                      )}
                      {event.traceId && (
                        <div>
                          <dt>traceId</dt>
                          <dd>{event.traceId}</dd>
                        </div>
                      )}
                      {event.spanId && (
                        <div>
                          <dt>spanId</dt>
                          <dd>{event.spanId}</dd>
                        </div>
                      )}
                      {event.cost != null && (
                        <div>
                          <dt>Стоимость</dt>
                          <dd>{formatCost(event.cost, 'USD')}</dd>
                        </div>
                      )}
                      {event.tokensPrompt != null && (
                        <div>
                          <dt>Токены prompt</dt>
                          <dd>{event.tokensPrompt}</dd>
                        </div>
                      )}
                      {event.tokensCompletion != null && (
                        <div>
                          <dt>Токены completion</dt>
                          <dd>{event.tokensCompletion}</dd>
                        </div>
                      )}
                    </dl>
                    {event.payload != null && (
                      <details>
                        <summary>Payload</summary>
                        <pre>{JSON.stringify(event.payload, null, 2)}</pre>
                      </details>
                    )}
                  </div>
                  {showConnector && <div className="timeline-item__connector" aria-hidden="true" />}
                </article>
              );
            })}
          </div>
        )}
      </section>

      {error && <div className="flow-error">{error}</div>}
    </div>
  );
};

export default FlowSessionsPage;
