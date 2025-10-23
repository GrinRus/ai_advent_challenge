import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  fetchFlowDefinitions,
  fetchFlowLaunchPreview,
  startFlow,
  type FlowDefinitionSummary,
  type FlowLaunchPreview,
  type FlowLaunchStep,
} from '../lib/apiClient';
import './FlowLaunch.css';

type FormErrors = {
  parameters?: string;
  sharedContext?: string;
  start?: string;
};

const formatTokens = (value?: number | null) =>
  value != null ? value.toLocaleString('ru-RU') : '—';

const formatCost = (value?: number | null, currency?: string | null) => {
  if (value == null) {
    return '—';
  }
  const formatted = value >= 0.01 ? value.toFixed(2) : value.toFixed(4);
  return `${formatted} ${currency ?? 'USD'}`;
};

const hasOverrides = (overrides?: { temperature?: number | null; topP?: number | null; maxTokens?: number | null } | null) =>
  Boolean(
    overrides &&
      (overrides.temperature != null ||
        overrides.topP != null ||
        overrides.maxTokens != null),
  );

const FlowLaunchPage = () => {
  const [definitions, setDefinitions] = useState<FlowDefinitionSummary[]>([]);
  const [definitionsError, setDefinitionsError] = useState<string | null>(null);
  const [isDefinitionsLoading, setIsDefinitionsLoading] = useState(false);

  const [selectedDefinitionId, setSelectedDefinitionId] = useState<string>('');
  const [preview, setPreview] = useState<FlowLaunchPreview | null>(null);
  const [isPreviewLoading, setIsPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  const [parameters, setParameters] = useState('{\n  \n}');
  const [sharedContext, setSharedContext] = useState('');
  const [formErrors, setFormErrors] = useState<FormErrors>({});
  const [startError, setStartError] = useState<string | null>(null);
  const [isStarting, setIsStarting] = useState(false);

  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;
    const loadDefinitions = async () => {
      try {
        setDefinitionsError(null);
        setIsDefinitionsLoading(true);
        const data = await fetchFlowDefinitions();
        if (cancelled) {
          return;
        }
        setDefinitions(data);
      } catch (error) {
        if (cancelled) {
          return;
        }
        const message =
          error instanceof Error
            ? error.message
            : 'Не удалось загрузить список определений.';
        setDefinitionsError(message);
        setDefinitions([]);
      } finally {
        if (!cancelled) {
          setIsDefinitionsLoading(false);
        }
      }
    };

    loadDefinitions();

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (definitions.length === 0) {
      setSelectedDefinitionId('');
      return;
    }
    const requestedId = searchParams.get('definitionId');
    const normalized =
      requestedId && definitions.some((item) => item.id === requestedId)
        ? requestedId
        : definitions[0].id;
    if (normalized !== selectedDefinitionId) {
      setSelectedDefinitionId(normalized);
    }
  }, [definitions, searchParams, selectedDefinitionId]);

  useEffect(() => {
    if (!selectedDefinitionId) {
      setPreview(null);
      setPreviewError(null);
      return;
    }

    let cancelled = false;
    const loadPreview = async () => {
      try {
        setPreviewError(null);
        setIsPreviewLoading(true);
        const data = await fetchFlowLaunchPreview(selectedDefinitionId);
        if (cancelled) {
          return;
        }
        setPreview(data);
      } catch (error) {
        if (cancelled) {
          return;
        }
        const message =
          error instanceof Error
            ? error.message
            : 'Не удалось загрузить данные шаблона.';
        setPreviewError(message);
        setPreview(null);
      } finally {
        if (!cancelled) {
          setIsPreviewLoading(false);
        }
      }
    };

    loadPreview();

    return () => {
      cancelled = true;
    };
  }, [selectedDefinitionId]);

  const handleDefinitionChange = useCallback(
    (value: string) => {
      setSelectedDefinitionId(value);
      const nextParams = new URLSearchParams(searchParams);
      if (value) {
        nextParams.set('definitionId', value);
      } else {
        nextParams.delete('definitionId');
      }
      setSearchParams(nextParams);
    },
    [searchParams, setSearchParams],
  );

  const parseJsonInput = useCallback((value: string) => {
    const trimmed = value.trim();
    if (!trimmed) {
      return undefined;
    }
    return JSON.parse(trimmed);
  }, []);

  const handleStart = useCallback(async () => {
    if (!selectedDefinitionId) {
      setFormErrors({ start: 'Сначала выберите шаблон флоу' });
      return;
    }

    const nextErrors: FormErrors = {};
    let parsedParameters: unknown | undefined;
    let parsedSharedContext: unknown | undefined;

    try {
      parsedParameters = parseJsonInput(parameters);
    } catch (error) {
      nextErrors.parameters =
        error instanceof Error ? error.message : 'Некорректный JSON.';
    }

    try {
      parsedSharedContext = parseJsonInput(sharedContext);
    } catch (error) {
      nextErrors.sharedContext =
        error instanceof Error ? error.message : 'Некорректный JSON.';
    }

    if (Object.keys(nextErrors).length > 0) {
      setFormErrors(nextErrors);
      return;
    }

    setFormErrors({});
    setStartError(null);

    try {
      setIsStarting(true);
      const payload =
        parsedParameters !== undefined || parsedSharedContext !== undefined
          ? {
              parameters: parsedParameters,
              sharedContext: parsedSharedContext,
            }
          : undefined;

      const response = await startFlow(selectedDefinitionId, payload);
      navigate(`/flows/sessions/${response.sessionId}`);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Не удалось запустить флоу.';
      setStartError(message);
    } finally {
      setIsStarting(false);
    }
  }, [navigate, parameters, parseJsonInput, selectedDefinitionId, sharedContext]);

  const totalEstimate = preview?.totalEstimate;

  const steps: FlowLaunchStep[] = useMemo(
    () => preview?.steps ?? [],
    [preview?.steps],
  );

  return (
    <div className="flow-launch">
      <div className="flow-launch__header">
        <div>
          <h2>Запуск флоу</h2>
          <p>
            Выберите опубликованный шаблон, изучите шаги и оценку стоимости, затем передайте
            параметры перед стартом.
          </p>
        </div>
      </div>

      <section className="flow-launch__panel">
        <h3>Шаблон</h3>
        <div className="flow-launch__field-grid">
          <label>
            Определение
            <select
              value={selectedDefinitionId}
              onChange={(event) => handleDefinitionChange(event.target.value)}
              disabled={isDefinitionsLoading || definitions.length === 0}
            >
              {definitions.length === 0 ? (
                <option value="">
                  {isDefinitionsLoading ? 'Загрузка...' : 'Нет определений'}
                </option>
              ) : null}
              {definitions.map((definition) => (
                <option key={definition.id} value={definition.id}>
                  {definition.name} · v{definition.version}
                </option>
              ))}
            </select>
          </label>
          {preview?.description && (
            <div className="flow-launch__description">{preview.description}</div>
          )}
        </div>
        {definitionsError && (
          <div className="flow-launch__error">{definitionsError}</div>
        )}
        <div className="flow-launch__estimate-summary">
          <div>
            <span className="flow-launch__metric-label">Оценка токенов</span>
            <span className="flow-launch__metric-value">
              {formatTokens(totalEstimate?.totalTokens)}
            </span>
          </div>
          <div>
            <span className="flow-launch__metric-label">Оценка стоимости</span>
            <span className="flow-launch__metric-value">
              {formatCost(totalEstimate?.totalCost, totalEstimate?.currency)}
            </span>
          </div>
          <div>
            <span className="flow-launch__metric-label">Шагов</span>
            <span className="flow-launch__metric-value">{steps.length}</span>
          </div>
        </div>
      </section>

      <section className="flow-launch__panel">
        <h3>Шаги флоу</h3>
        {previewError && (
          <div className="flow-launch__error flow-launch__error--inline">
            {previewError}
          </div>
        )}
        {isPreviewLoading ? (
          <div className="flow-launch__steps flow-launch__steps--loading" aria-busy="true">
            {[0, 1, 2].map((idx) => (
              <div key={idx} className="flow-launch-step flow-launch-step--skeleton">
                <div className="skeleton skeleton-line skeleton-line--wide" />
                <div className="skeleton skeleton-line" />
                <div className="skeleton skeleton-block" />
              </div>
            ))}
          </div>
        ) : steps.length === 0 ? (
          <p className="flow-launch__empty">Шаблон не содержит шагов.</p>
        ) : (
          <div className="flow-launch__steps">
            {steps.map((step) => {
              const isStart = preview?.startStepId === step.id;
              return (
                <article
                  key={step.id}
                  className={`flow-launch-step${isStart ? ' flow-launch-step--start' : ''}`}
                >
                  <header className="flow-launch-step__header">
                    <div>
                      <span className="flow-launch-step__name">{step.name ?? step.id}</span>
                      {isStart && <span className="flow-launch-step__badge">Стартовый шаг</span>}
                    </div>
                    <div className="flow-launch-step__metrics">
                      <span>{formatTokens(step.estimate?.totalTokens)} токенов</span>
                      <span>
                        {formatCost(step.estimate?.totalCost, step.estimate?.currency)}
                      </span>
                    </div>
                  </header>
                  <div className="flow-launch-step__agent">
                    <div>
                      <strong>Агент:</strong>{' '}
                      {step.agent.agentDisplayName ?? step.agent.agentIdentifier ?? '—'} · v
                      {step.agent.agentVersionNumber}
                    </div>
                    <div>
                      <strong>Модель:</strong>{' '}
                      {step.agent.modelDisplayName ?? step.agent.modelId}
                    </div>
                    <div>
                      <strong>Провайдер:</strong>{' '}
                      {step.agent.providerDisplayName ?? step.agent.providerId} (
                      {step.agent.providerType.toLowerCase()})
                    </div>
                    <div>
                      <strong>Метрики:</strong>{' '}
                      контекст {step.agent.modelContextWindow ?? '—'} · макс. вывод{' '}
                      {step.agent.modelMaxOutputTokens ?? '—'}
                    </div>
                    <div>
                      <strong>Стоимость /1K:</strong>{' '}
                      {formatCost(
                        step.agent.pricing.inputPer1KTokens,
                        step.agent.pricing.currency,
                      )}{' '}
                      in ·{' '}
                      {formatCost(
                        step.agent.pricing.outputPer1KTokens,
                        step.agent.pricing.currency,
                      )}{' '}
                      out
                    </div>
                    <div>
                      <strong>Макс. попыток:</strong> {step.maxAttempts}
                    </div>
                  </div>

                  {hasOverrides(step.overrides) && (
                    <div className="flow-launch-step__section">
                      <strong>Overrides:</strong>
                      <ul className="flow-launch-step__list">
                        {step.overrides?.temperature != null && (
                          <li>temperature: {step.overrides.temperature}</li>
                        )}
                        {step.overrides?.topP != null && <li>topP: {step.overrides.topP}</li>}
                        {step.overrides?.maxTokens != null && (
                          <li>maxTokens: {step.overrides.maxTokens}</li>
                        )}
                      </ul>
                    </div>
                  )}

                  {step.memoryReads.length > 0 && (
                    <div className="flow-launch-step__section">
                      <strong>Memory reads:</strong>
                      <ul className="flow-launch-step__list">
                        {step.memoryReads.map((read) => (
                          <li key={`${step.id}-read-${read.channel}`}>
                            {read.channel} · limit {read.limit}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {step.memoryWrites.length > 0 && (
                    <div className="flow-launch-step__section">
                      <strong>Memory writes:</strong>
                      <ul className="flow-launch-step__list">
                        {step.memoryWrites.map((write, index) => (
                          <li key={`${step.id}-write-${index}`}>
                            {write.channel} · mode {write.mode}
                            {write.payload != null && (
                              <pre className="flow-launch-step__payload">
                                {JSON.stringify(write.payload, null, 2)}
                              </pre>
                            )}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {step.prompt && (
                    <details className="flow-launch-step__prompt">
                      <summary>Prompt</summary>
                      <pre>{step.prompt}</pre>
                    </details>
                  )}
                </article>
              );
            })}
          </div>
        )}
      </section>

      <section className="flow-launch__panel">
        <h3>Входные данные</h3>
        <div className="flow-launch__field-grid">
          <label>
            Parameters (JSON)
            <textarea
              value={parameters}
              onChange={(event) => setParameters(event.target.value)}
              placeholder={'{\n  "input": "value"\n}'}
            />
            {formErrors.parameters && (
              <span className="flow-launch__field-error">{formErrors.parameters}</span>
            )}
          </label>
          <label>
            Shared context (JSON)
            <textarea
              value={sharedContext}
              onChange={(event) => setSharedContext(event.target.value)}
              placeholder={'{\n  "notes": "value"\n}'}
            />
            {formErrors.sharedContext && (
              <span className="flow-launch__field-error">{formErrors.sharedContext}</span>
            )}
          </label>
        </div>
        {formErrors.start && <div className="flow-launch__error">{formErrors.start}</div>}
        {startError && <div className="flow-launch__error">{startError}</div>}
        <div className="flow-launch__actions">
          <button
            type="button"
            className="primary-btn"
            onClick={handleStart}
            disabled={!selectedDefinitionId || isStarting}
          >
            {isStarting ? 'Запуск...' : 'Запустить флоу'}
          </button>
          <button
            type="button"
            className="secondary-btn"
            onClick={() => navigate('/flows/sessions')}
          >
            Перейти к сессиям
          </button>
        </div>
      </section>
    </div>
  );
};

export default FlowLaunchPage;
