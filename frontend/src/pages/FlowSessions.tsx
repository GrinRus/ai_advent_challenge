import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  fetchFlowSnapshot,
  fetchFlowInteractions,
  respondToFlowInteraction,
  skipFlowInteraction,
  autoResolveFlowInteraction,
  expireFlowInteraction,
  pollFlowStatus,
  sendFlowControlCommand,
  startFlow,
  subscribeToFlowEvents,
  type FlowSuggestedAction,
  type FlowSuggestedActions,
} from '../lib/apiClient';
import type {
  FlowControlCommand,
  FlowEvent,
  FlowInteractionItemDto,
  FlowStatusResponse,
  FlowState,
  FlowTelemetrySnapshot,
} from '../lib/apiClient';
import {
  FlowLaunchParametersSchema,
  FlowSharedContextSchema,
  type FlowLaunchPayload,
} from '../lib/types/flow';
import { JsonValueSchema } from '../lib/types/json';
import './FlowSessions.css';
import {
  extractInteractionSchema,
  buildSubmissionPayload,
  computeSuggestedActionUpdates,
  type InteractionFormField,
} from '../lib/interactionSchema';

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
    WAITING_USER_INPUT: 'ожидает ответа пользователя',
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
  HUMAN_INTERACTION_REQUIRED: 'Ожидает пользователя',
  HUMAN_INTERACTION_RESPONDED: 'Получен ответ пользователя',
  HUMAN_INTERACTION_AUTO_RESOLVED: 'Запрос автозавершён',
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

const interactionStatusLabel: Record<string, string> = {
  PENDING: 'ожидание ответа',
  ANSWERED: 'ответ получен',
  EXPIRED: 'просрочен',
  AUTO_RESOLVED: 'закрыт автоматически',
};

const interactionTypeLabel: Record<string, string> = {
  INPUT_FORM: 'форма ввода',
  APPROVAL: 'подтверждение',
  CONFIRMATION: 'подтверждение',
  REVIEW: 'ревью',
  INFORMATION: 'информация',
};

const FlowSessionsPage = () => {
  const [flowId, setFlowId] = useState('');
  const [parameters, setParameters] = useState('');
  const [sharedContext, setSharedContext] = useState('');
  const [sessionId, setSessionId] = useState<string | undefined>();
  const [status, setStatus] = useState<FlowState | null>(null);
  const [events, setEvents] = useState<FlowEvent[]>([]);
  const [telemetry, setTelemetry] = useState<FlowTelemetrySnapshot | null>(null);
  const [interactions, setInteractions] = useState<FlowInteractionItemDto[]>([]);
  const [selectedInteraction, setSelectedInteraction] = useState<FlowInteractionItemDto | null>(null);
  const [interactionFields, setInteractionFields] = useState<InteractionFormField[]>([]);
  const [interactionValues, setInteractionValues] = useState<Record<string, unknown>>({});
  const [interactionErrors, setInteractionErrors] = useState<Record<string, string>>({});
  const [interactionSubmitting, setInteractionSubmitting] = useState(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);
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
  const interactionCountRef = useRef(0);
  const previousInteractionsRef = useRef<Set<string>>(new Set());
  const toastTimerRef = useRef<number | null>(null);
  const notificationRequestedRef = useRef(false);
  const interactionsInitializedRef = useRef(false);
  const showToast = useCallback((message: string) => {
    setToastMessage(message);
    if (toastTimerRef.current != null) {
      window.clearTimeout(toastTimerRef.current);
    }
    toastTimerRef.current = window.setTimeout(() => {
      setToastMessage(null);
      toastTimerRef.current = null;
    }, 4000);
  }, []);

  const showDesktopNotification = useCallback(
    (body: string) => {
      if (typeof window === 'undefined' || !('Notification' in window)) {
        return;
      }
      if (Notification.permission === 'granted') {
        new Notification('Flow Workspace', { body });
        return;
      }
      if (Notification.permission === 'default' && !notificationRequestedRef.current) {
        notificationRequestedRef.current = true;
        Notification.requestPermission().then((permission) => {
          notificationRequestedRef.current = false;
          if (permission === 'granted') {
            new Notification('Flow Workspace', { body });
          }
        });
      }
    },
    [],
  );

  const handleSelectInteraction = useCallback(
    (interaction: FlowInteractionItemDto) => {
      const extracted = extractInteractionSchema(
        interaction.payloadSchema,
        interaction.response?.payload ?? {},
      );
      setSelectedInteraction(interaction);
      setInteractionFields(extracted.fields);
      setInteractionValues(extracted.initialValues);
      setInteractionErrors({});
    },
    [],
  );

  const refreshInteractions = useCallback(async () => {
    if (!sessionId) {
      return;
    }
    try {
      const response = await fetchFlowInteractions(sessionId);
      const active = response.active;
      setInteractions(active);

      const nextIds = new Set(active.map((item) => item.requestId));
      const previousIds = previousInteractionsRef.current;
      const newlyAdded = active.filter((item) => !previousIds.has(item.requestId));
      previousInteractionsRef.current = nextIds;

      const prevCount = interactionCountRef.current;
      const newCount = active.length;
      interactionCountRef.current = newCount;

      window.dispatchEvent(
        new CustomEvent('flow-interactions-badge', { detail: { count: newCount } }),
      );

      const shouldNotify =
        interactionsInitializedRef.current && newlyAdded.length > 0 && newCount > prevCount;

      if (shouldNotify) {
        const first = newlyAdded[0];
        const label = first.title || `Шаг ${first.stepId}`;
        showToast(`Новый запрос: ${label}`);
        showDesktopNotification(`Новый запрос: ${label}`);
      }

      if (newCount === 0) {
        interactionsInitializedRef.current = true;
        setSelectedInteraction(null);
        setInteractionFields([]);
        setInteractionValues({});
        setInteractionErrors({});
        return;
      }

      const nextSelection =
          selectedInteraction
            ? active.find((item) => item.requestId === selectedInteraction.requestId)
            : active[0];

      if (nextSelection) {
        handleSelectInteraction(nextSelection);
      }

      interactionsInitializedRef.current = true;
    } catch (err) {
      console.warn('Не удалось обновить интерактивные запросы', err);
    }
  }, [
    handleSelectInteraction,
    selectedInteraction,
    sessionId,
    showDesktopNotification,
    showToast,
  ]);

  const handleFieldChange = useCallback((name: string, value: unknown) => {
    setInteractionValues((prev) => ({ ...prev, [name]: value }));
    setInteractionErrors((prev) => {
      if (!prev[name]) {
        return prev;
      }
      const next = { ...prev };
      delete next[name];
      return next;
    });
  }, []);

  const handleFileChange = useCallback(
    (name: string, files: FileList | null) => {
      if (!files || files.length === 0) {
        handleFieldChange(name, null);
        return;
      }
      const file = files[0];
      const reader = new FileReader();
      reader.onload = () => {
        handleFieldChange(name, reader.result);
      };
      reader.onerror = () => {
        console.error('Не удалось прочитать файл');
      };
      reader.readAsDataURL(file);
    },
    [handleFieldChange],
  );

  const renderInteractionField = useCallback<(field: InteractionFormField) => ReactNode>(
    (field: InteractionFormField): ReactNode => {
      const value = interactionValues[field.name];
      const error = interactionErrors[field.name];
      const commonProps = {
        id: `interaction-field-${field.name}`,
        name: field.name,
      };

      switch (field.control) {
        case 'textarea':
        case 'json':
          return (
            <label key={field.name} className="interaction-field">
              {field.label}
              <textarea
                {...commonProps}
                value={typeof value === 'string' ? value : ''}
                onChange={(event) => handleFieldChange(field.name, event.target.value)}
              />
              {error && <span className="interaction-field__error">{error}</span>}
            </label>
          );
        case 'number':
          return (
            <label key={field.name} className="interaction-field">
              {field.label}
              <input
                {...commonProps}
                type="number"
                value={typeof value === 'string' ? value : ''}
                onChange={(event) => handleFieldChange(field.name, event.target.value)}
              />
              {error && <span className="interaction-field__error">{error}</span>}
            </label>
          );
        case 'select':
          return (
            <label key={field.name} className="interaction-field">
              {field.label}
              <select
                {...commonProps}
                value={typeof value === 'string' ? value : ''}
                onChange={(event) => handleFieldChange(field.name, event.target.value)}
              >
                <option value="">—</option>
                {field.enumValues?.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
              {error && <span className="interaction-field__error">{error}</span>}
            </label>
          );
        case 'multiselect':
          return (
            <label key={field.name} className="interaction-field">
              {field.label}
              <select
                {...commonProps}
                multiple
                value={Array.isArray(value) ? (value as string[]) : []}
                onChange={(event) => {
                  const selected = Array.from(event.target.selectedOptions).map(
                    (option) => option.value,
                  );
                  handleFieldChange(field.name, selected);
                }}
              >
                {field.enumValues?.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
              {error && <span className="interaction-field__error">{error}</span>}
            </label>
          );
        case 'radio':
          return (
            <fieldset key={field.name} className="interaction-field">
              <legend>
                {field.label}
                {field.required ? ' *' : ''}
              </legend>
              <div className="interaction-field__radio-group">
                {field.enumValues?.map((option) => (
                  <label key={option} className="interaction-field__radio">
                    <input
                      type="radio"
                      name={field.name}
                      value={option}
                      checked={value === option}
                      onChange={() => handleFieldChange(field.name, option)}
                    />
                    {option}
                  </label>
                ))}
              </div>
              {error && <span className="interaction-field__error">{error}</span>}
            </fieldset>
          );
        case 'checkbox':
        case 'toggle':
          return (
            <label key={field.name} className="interaction-field interaction-field--checkbox">
              <input
                {...commonProps}
                type="checkbox"
                checked={Boolean(value)}
                onChange={(event) => handleFieldChange(field.name, event.target.checked)}
              />
              <span>{field.label}</span>
            </label>
          );
        case 'date':
        case 'datetime':
          return (
            <label key={field.name} className="interaction-field">
              {field.label}
              <input
                {...commonProps}
                type={field.control === 'date' ? 'date' : 'datetime-local'}
                value={typeof value === 'string' ? value : ''}
                onChange={(event) => handleFieldChange(field.name, event.target.value)}
              />
              {error && <span className="interaction-field__error">{error}</span>}
            </label>
          );
        case 'file':
          return (
            <label key={field.name} className="interaction-field">
              {field.label}
              <input
                {...commonProps}
                type="file"
                onChange={(event) => handleFileChange(field.name, event.target.files)}
              />
              {error && <span className="interaction-field__error">{error}</span>}
            </label>
          );
        case 'text':
        default:
          return (
            <label key={field.name} className="interaction-field">
              {field.label}
              <input
                {...commonProps}
                type="text"
                value={typeof value === 'string' ? value : ''}
                onChange={(event) => handleFieldChange(field.name, event.target.value)}
              />
              {error && <span className="interaction-field__error">{error}</span>}
            </label>
          );
      }
    },
    [handleFieldChange, handleFileChange, interactionErrors, interactionValues],
  );
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
      setInteractions([]);
      setSelectedInteraction(null);
      setInteractionFields([]);
      setInteractionValues({});
      setInteractionErrors({});
      interactionCountRef.current = 0;
      previousInteractionsRef.current = new Set();
      interactionsInitializedRef.current = false;
      window.dispatchEvent(new CustomEvent('flow-interactions-badge', { detail: { count: 0 } }));
      pollRef.current = {
        nextSince: initial.nextSinceEventId,
        stateVersion: initial.state.stateVersion,
      };
    } else {
      setStatus(null);
      setEvents([]);
      setTelemetry(null);
      setInteractions([]);
      setSelectedInteraction(null);
      setInteractionFields([]);
      setInteractionValues({});
      setInteractionErrors({});
      interactionCountRef.current = 0;
      previousInteractionsRef.current = new Set();
      interactionsInitializedRef.current = false;
      window.dispatchEvent(new CustomEvent('flow-interactions-badge', { detail: { count: 0 } }));
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

  useEffect(() => {
    return () => {
      if (toastTimerRef.current != null) {
        window.clearTimeout(toastTimerRef.current);
      }
    };
  }, []);

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
      const payload: FlowLaunchPayload = {};
      const parsedParameters = parseJson(parameters);
      if (parsedParameters !== undefined) {
        payload.parameters = FlowLaunchParametersSchema.parse(parsedParameters);
      }
      const parsedSharedContext = parseJson(sharedContext);
      if (parsedSharedContext !== undefined) {
        payload.sharedContext = FlowSharedContextSchema.parse(parsedSharedContext);
      }
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
      await refreshInteractions();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setIsSnapshotLoading(false);
    }
  }, [refreshInteractions, resetSessionData, sessionId]);

  useEffect(() => {
    if (!sessionId) {
      return undefined;
    }

    const source = subscribeToFlowEvents(sessionId, {
      onFlow: (payload) => {
        setStatus(payload.state);
        appendEvents(payload.events);
        if (payload.telemetry) {
          setTelemetry(payload.telemetry);
        }
        pollRef.current = {
          nextSince: payload.nextSinceEventId,
          stateVersion: payload.state.stateVersion,
        };

        if (payload.events.some((evt) => evt.type?.startsWith('HUMAN_INTERACTION'))) {
          refreshInteractions();
        }

        if (payload.state.status?.endsWith('ED')) {
          setIsPolling(false);
        }
      },
      onHeartbeat: () => {
        /* keep-alive */
      },
      onError: () => {
        console.warn('SSE поток закрыт, переключаемся на poll');
        setIsPolling(true);
      },
    });

    return () => {
      source.close();
    };
  }, [appendEvents, refreshInteractions, sessionId]);

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
    } catch {
      return 'Не удалось сериализовать shared context';
    }
  }, [status?.sharedContext]);

  const telemetryStats = useMemo(() => {
    if (!telemetry) {
      return null;
    }
    const stepsCompleted = telemetry.stepsCompleted ?? 0;
    const stepsFailed = telemetry.stepsFailed ?? 0;
    const totalAttempts = stepsCompleted + stepsFailed;
    const progressPercent = totalAttempts
      ? Math.min(100, Math.round((stepsCompleted / totalAttempts) * 100))
      : status?.status?.startsWith('COMPLETED')
      ? 100
      : 0;
    return {
      progressPercent,
      totalAttempts,
      stepsCompleted,
      stepsFailed,
    };
  }, [status?.status, telemetry]);

  const hasSharedContext = status?.sharedContext !== undefined && status?.sharedContext !== null;

  const suggestedActions: FlowSuggestedActions | null =
    selectedInteraction?.suggestedActions ?? null;
  const hasSuggestedActions = Boolean(
    (suggestedActions?.ruleBased?.length ?? 0) +
      (suggestedActions?.llm?.length ?? 0) +
      (suggestedActions?.analytics?.length ?? 0),
  );

  const handleApplySuggestedAction = useCallback(
    (action: FlowSuggestedAction) => {
      if (!interactionFields.length) {
        showToast('Для этого запроса нет полей для применения действия');
        return;
      }

      const { updates, appliedFields } = computeSuggestedActionUpdates(
        interactionFields,
        action.payload,
      );

      if (!appliedFields.length) {
        showToast('Не удалось сопоставить данные действия с полями формы');
        return;
      }

      setInteractionValues((prev) => ({ ...prev, ...updates }));
      setInteractionErrors((prev) => {
        if (!prev) {
          return prev;
        }
        const next = { ...prev };
        appliedFields.forEach((key) => {
          delete next[key];
        });
        return next;
      });
      showToast(`Действие «${action.label}» применено`);
    },
    [
      interactionFields,
      setInteractionErrors,
      setInteractionValues,
      showToast,
    ],
  );

  const renderSuggestedActionGroup = useCallback(
    (title: string, actions: FlowSuggestedAction[], variant: 'primary' | 'secondary') => (
      <div className={`interaction-suggested__group interaction-suggested__group--${variant}`}>
        <header className="interaction-suggested__header">
          <span>{title}</span>
          {variant === 'secondary' && (
            <span className="interaction-suggested__badge" aria-label="AI recommendation">
              AI
            </span>
          )}
        </header>
        <ul className="interaction-suggested__list">
          {actions.map((item) => (
            <li key={`${item.source}:${item.id}`}>
              <div className="interaction-action">
                <div className="interaction-action__text">
                  <div className="interaction-action__title">{item.label}</div>
                  {item.description && (
                    <p className="interaction-action__description">{item.description}</p>
                  )}
                </div>
                <div className="interaction-action__controls">
                  <button
                    type="button"
                    className="link-btn"
                    onClick={() => handleApplySuggestedAction(item)}
                  >
                    {item.ctaLabel ?? 'Применить'}
                  </button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      </div>
    ),
    [handleApplySuggestedAction],
  );

  useEffect(() => {
    if (!retryStepId && latestStepId) {
      setRetryStepId(latestStepId);
    }
  }, [latestStepId, retryStepId]);

  const hasSession = Boolean(sessionId);
  const showStatusSkeleton =
    hasSession && (isStarting || isSnapshotLoading || (isPolling && !status));
  const showEventSkeleton = hasSession && isPolling && events.length === 0;

  const isEmptyValue = (value: unknown): boolean => {
    if (value === null || value === undefined) {
      return true;
    }
    if (typeof value === 'string') {
      return value.trim() === '';
    }
    if (Array.isArray(value)) {
      return value.length === 0;
    }
    return false;
  };

  const validateInteractionForm = useCallback(() => {
    if (!interactionFields.length) {
      return {};
    }
    const errors: Record<string, string> = {};
    interactionFields.forEach((field) => {
      const value = interactionValues[field.name];
      if (field.required && isEmptyValue(value)) {
        errors[field.name] = 'Обязательное поле';
        return;
      }
      if (field.control === 'json' && typeof value === 'string' && value.trim()) {
        try {
          JSON.parse(value);
        } catch (error) {
          errors[field.name] = 'Некорректный JSON: ' + (error as Error).message;
        }
      }
      if (field.control === 'number' && value !== '' && value !== null && value !== undefined) {
        const parsed = Number(value);
        if (Number.isNaN(parsed)) {
          errors[field.name] = 'Введите корректное число';
        }
      }
    });
    setInteractionErrors(errors);
    return errors;
  }, [interactionFields, interactionValues]);

  const handleSubmitInteraction = useCallback(async () => {
    if (!sessionId || !selectedInteraction) {
      return;
    }
    const errors = validateInteractionForm();
    if (errors && Object.keys(errors).length > 0) {
      return;
    }
    try {
      setInteractionSubmitting(true);
      const payloadObject = buildSubmissionPayload(interactionFields, interactionValues);
      const payloadJson =
        payloadObject === undefined ? undefined : JsonValueSchema.parse(payloadObject);
      const response = await respondToFlowInteraction(sessionId, selectedInteraction.requestId, {
        chatSessionId: selectedInteraction.chatSessionId,
        payload: payloadJson,
      });
      setSelectedInteraction(response);
      const extracted = extractInteractionSchema(
        response.payloadSchema,
        response.response?.payload ?? {},
      );
      setInteractionFields(extracted.fields);
      setInteractionValues(extracted.initialValues);
      setInteractionErrors({});
      showToast('Ответ отправлен');
      await refreshInteractions();
    } catch (error) {
      setError((error as Error).message);
    } finally {
      setInteractionSubmitting(false);
    }
  }, [interactionFields, interactionValues, refreshInteractions, selectedInteraction, sessionId, showToast, validateInteractionForm]);

  const handleSkipInteraction = useCallback(async () => {
    if (!sessionId || !selectedInteraction) {
      return;
    }
    try {
      setInteractionSubmitting(true);
      const response = await skipFlowInteraction(sessionId, selectedInteraction.requestId, {
        chatSessionId: selectedInteraction.chatSessionId,
      });
      setSelectedInteraction(response);
      const extracted = extractInteractionSchema(
        response.payloadSchema,
        response.response?.payload ?? {},
      );
      setInteractionFields(extracted.fields);
      setInteractionValues(extracted.initialValues);
      setInteractionErrors({});
      showToast('Запрос пропущен');
      await refreshInteractions();
    } catch (error) {
      setError((error as Error).message);
    } finally {
      setInteractionSubmitting(false);
    }
  }, [refreshInteractions, selectedInteraction, sessionId, showToast]);

  const handleAutoResolveInteraction = useCallback(async () => {
    if (!sessionId || !selectedInteraction) {
      return;
    }
    try {
      setInteractionSubmitting(true);
      const response = await autoResolveFlowInteraction(
        sessionId,
        selectedInteraction.requestId,
        selectedInteraction.chatSessionId,
        {},
      );
      setSelectedInteraction(response);
      const extracted = extractInteractionSchema(
        response.payloadSchema,
        response.response?.payload ?? {},
      );
      setInteractionFields(extracted.fields);
      setInteractionValues(extracted.initialValues);
      setInteractionErrors({});
      showToast('Запрос автозавершён');
      await refreshInteractions();
    } catch (error) {
      setError((error as Error).message);
    } finally {
      setInteractionSubmitting(false);
    }
  }, [refreshInteractions, selectedInteraction, sessionId, showToast]);

  const handleExpireInteraction = useCallback(async () => {
    if (!sessionId || !selectedInteraction) {
      return;
    }
    try {
      setInteractionSubmitting(true);
      const response = await expireFlowInteraction(
        sessionId,
        selectedInteraction.requestId,
        selectedInteraction.chatSessionId,
        {},
      );
      setSelectedInteraction(response);
      const extracted = extractInteractionSchema(
        response.payloadSchema,
        response.response?.payload ?? {},
      );
      setInteractionFields(extracted.fields);
      setInteractionValues(extracted.initialValues);
      setInteractionErrors({});
      showToast('Запрос помечен как просроченный');
      await refreshInteractions();
    } catch (error) {
      setError((error as Error).message);
    } finally {
      setInteractionSubmitting(false);
    }
  }, [refreshInteractions, selectedInteraction, sessionId, showToast]);

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

      {selectedInteraction && (
        <section className="flow-section flow-interaction-panel">
          <h3>Текущий запрос</h3>
          <div className="flow-interaction-meta">
            <span>
              Статус:{' '}
              <strong>
                {interactionStatusLabel[selectedInteraction.status] ??
                  selectedInteraction.status.toLowerCase()}
              </strong>
            </span>
            <span>
              Шаг: <strong>{selectedInteraction.stepId}</strong>
            </span>
            <span>Дедлайн: {formatDateTime(selectedInteraction.dueAt)}</span>
          </div>
          {selectedInteraction.response && (
            <div className="interaction-response">
              <header>Предыдущий ответ</header>
              <div>
                Источник:{' '}
                <strong>{selectedInteraction.response.source.toLowerCase()}</strong>
              </div>
              {selectedInteraction.response.respondedBy && (
                <div>Ответил: {selectedInteraction.response.respondedBy}</div>
              )}
              {selectedInteraction.response.respondedAt && (
                <div>Время: {formatDateTime(selectedInteraction.response.respondedAt)}</div>
              )}
              {selectedInteraction.response.payload != null ? (
                <pre>
                  {JSON.stringify(selectedInteraction.response.payload, null, 2) ?? ''}
                </pre>
              ) : null}
            </div>
          )}
          {interactionFields.length === 0 ? (
            <p className="flow-empty flow-empty--inline">
              Для этого запроса не требуется ввод данных — можно пропустить или
              автозавершить.
            </p>
          ) : (
            <div className="interaction-form-grid">
              {interactionFields.map<ReactNode>((field) => renderInteractionField(field))}
            </div>
          )}
          {hasSuggestedActions ? (
            <div className="interaction-suggested">
              {suggestedActions?.ruleBased?.length
                ? renderSuggestedActionGroup('Доступные действия', suggestedActions.ruleBased, 'primary')
                : null}
              {suggestedActions?.llm?.length
                ? renderSuggestedActionGroup('Рекомендации AI', suggestedActions.llm, 'secondary')
                : null}
              {suggestedActions?.analytics?.length
                ? renderSuggestedActionGroup('Подсказки аналитики', suggestedActions.analytics, 'secondary')
                : null}
              {suggestedActions?.filtered?.length ? (
                <p className="interaction-suggested__note">
                  {`Отфильтровано ${suggestedActions.filtered.length} рекомендаций, не входящих в allowlist.`}
                </p>
              ) : null}
            </div>
          ) : null}
          <div className="interaction-actions">
            <button
              type="button"
              className="primary-btn"
              onClick={handleSubmitInteraction}
              disabled={interactionSubmitting || !sessionId}
            >
              {interactionSubmitting ? 'Отправка...' : 'Отправить ответ'}
            </button>
            <button
              type="button"
              onClick={handleSkipInteraction}
              disabled={interactionSubmitting || !sessionId}
            >
              Пропустить
            </button>
            <button
              type="button"
              onClick={handleAutoResolveInteraction}
              disabled={interactionSubmitting || !sessionId}
            >
              Авторазрешить
            </button>
            <button
              type="button"
              onClick={handleExpireInteraction}
              disabled={interactionSubmitting || !sessionId}
            >
              Истёк SLA
            </button>
          </div>
        </section>
      )}

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
                  Выполнено шагов: {telemetryStats.stepsCompleted}
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
                    <dd>{telemetry.stepsCompleted ?? 0}</dd>
                  </div>
                  <div>
                    <dt>Ошибок</dt>
                    <dd>{telemetry.stepsFailed ?? 0}</dd>
                  </div>
                  <div>
                    <dt>Запланированных ретраев</dt>
                    <dd>{telemetry.retriesScheduled ?? 0}</dd>
                  </div>
                  <div>
                    <dt>Стоимость</dt>
                    <dd>{formatCost(telemetry.totalCostUsd ?? 0, 'USD')}</dd>
                  </div>
                  <div>
                    <dt>Токены (prompt)</dt>
                    <dd>{formatTokens(telemetry.promptTokens ?? 0)}</dd>
                  </div>
                  <div>
                    <dt>Токены (completion)</dt>
                    <dd>{formatTokens(telemetry.completionTokens ?? 0)}</dd>
                  </div>
                  <div>
                    <dt>Обновлено</dt>
                    <dd>{formatDateTime(telemetry.lastUpdated)}</dd>
                  </div>
                </dl>
              </div>
            )}
            <div className="flow-interactions">
              <header>Активные запросы к пользователю</header>
              {interactions.length === 0 ? (
                <p className="flow-empty flow-empty--inline">
                  Нет запросов, ожидающих ответа.
                </p>
              ) : (
                <ul className="flow-interactions-list">
                  {interactions.map((item) => (
                    <li
                      key={item.requestId}
                      className={`flow-interactions-item${
                        selectedInteraction?.requestId === item.requestId
                          ? ' flow-interactions-item--active'
                          : ''
                      }`}
                    >
                      <div className="flow-interactions-item__title">
                        {item.title || interactionTypeLabel[item.type] || item.type}
                      </div>
                      <div className="flow-interactions-item__meta">
                        <span className={`status-tag status-tag--${item.status.toLowerCase()}`}>
                          {interactionStatusLabel[item.status] ?? item.status.toLowerCase()}
                        </span>
                        <span>
                          Шаг: <strong>{item.stepId}</strong>
                        </span>
                        <span>
                          Дедлайн: {formatDateTime(item.dueAt)}
                        </span>
                      </div>
                      {item.description && (
                        <p className="flow-interactions-item__description">{item.description}</p>
                      )}
                      <div className="flow-interactions-item__actions">
                        <button
                          type="button"
                          className="link-btn"
                          onClick={() => handleSelectInteraction(item)}
                        >
                          Открыть
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
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
      {toastMessage && (
        <div className="flow-toast" role="status" aria-live="polite">
          {toastMessage}
        </div>
      )}
    </div>
  );
};

export default FlowSessionsPage;
