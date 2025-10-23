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
  temperature?: string;
  topP?: string;
  maxTokens?: string;
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
  const [temperature, setTemperature] = useState('');
  const [topP, setTopP] = useState('');
  const [maxTokens, setMaxTokens] = useState('');
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
        const published = data.filter(
          (definition) => definition.active && definition.status === 'PUBLISHED',
        );
        if (published.length === 0) {
          setDefinitions([]);
          setDefinitionsError('Нет опубликованных активных определений для запуска.');
        } else {
          setDefinitions(published);
        }
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
    const overrides: {
      temperature?: number;
      topP?: number;
      maxTokens?: number;
    } = {};

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

    const temperatureTrimmed = temperature.trim();
    if (temperatureTrimmed) {
      const numeric = Number(temperatureTrimmed);
      if (Number.isNaN(numeric) || numeric < 0 || numeric > 2) {
        nextErrors.temperature = 'Температура должна быть в диапазоне 0…2';
      } else {
        overrides.temperature = Number.parseFloat(numeric.toFixed(4));
      }
    }

    const topPTrimmed = topP.trim();
    if (topPTrimmed) {
      const numeric = Number(topPTrimmed);
      if (Number.isNaN(numeric) || numeric <= 0 || numeric > 1) {
        nextErrors.topP = 'topP должно быть в диапазоне (0;1]';
      } else {
        overrides.topP = Number.parseFloat(numeric.toFixed(4));
      }
    }

    const maxTokensTrimmed = maxTokens.trim();
    if (maxTokensTrimmed) {
      const numeric = Number(maxTokensTrimmed);
      if (!Number.isInteger(numeric) || numeric <= 0) {
        nextErrors.maxTokens = 'maxTokens должно быть положительным целым числом';
      } else {
        overrides.maxTokens = numeric;
      }
    }

    if (Object.keys(nextErrors).length > 0) {
      setFormErrors(nextErrors);
      return;
    }

    setFormErrors({});
    setStartError(null);

    try {
      setIsStarting(true);
      const overridesPayload = Object.keys(overrides).length > 0 ? overrides : undefined;
      const payload =
        parsedParameters !== undefined ||
        parsedSharedContext !== undefined ||
        overridesPayload !== undefined
          ? {
              parameters: parsedParameters,
              sharedContext: parsedSharedContext,
              overrides: overridesPayload,
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
  }, [navigate, parameters, parseJsonInput, selectedDefinitionId, sharedContext, temperature, topP, maxTokens]);

  const totalEstimate = preview?.totalEstimate;

  const parametersPreview = useMemo(() => {
    const trimmed = parameters.trim();
    if (!trimmed) {
      return { value: undefined, error: null as string | null };
    }
    try {
      return { value: JSON.parse(trimmed), error: null as string | null };
    } catch (error) {
      return {
        value: undefined,
        error: error instanceof Error ? error.message : 'Некорректный JSON.',
      };
    }
  }, [parameters]);

  const sharedContextPreview = useMemo(() => {
    const trimmed = sharedContext.trim();
    if (!trimmed) {
      return { value: undefined, error: null as string | null };
    }
    try {
      return { value: JSON.parse(trimmed), error: null as string | null };
    } catch (error) {
      return {
        value: undefined,
        error: error instanceof Error ? error.message : 'Некорректный JSON.',
      };
    }
  }, [sharedContext]);

  const overridesPreview = useMemo(() => {
    const warnings: string[] = [];
    const result: {
      temperature?: number;
      topP?: number;
      maxTokens?: number;
    } = {};

    if (temperature.trim()) {
      const numeric = Number(temperature.trim());
      if (Number.isNaN(numeric) || numeric < 0 || numeric > 2) {
        warnings.push('Температура должна быть от 0 до 2. Значение не будет отправлено.');
      } else {
        result.temperature = Number.parseFloat(numeric.toFixed(4));
      }
    }

    if (topP.trim()) {
      const numeric = Number(topP.trim());
      if (Number.isNaN(numeric) || numeric <= 0 || numeric > 1) {
        warnings.push('topP должно быть в диапазоне (0;1]. Значение не будет отправлено.');
      } else {
        result.topP = Number.parseFloat(numeric.toFixed(4));
      }
    }

    if (maxTokens.trim()) {
      const numeric = Number(maxTokens.trim());
      if (!Number.isInteger(numeric) || numeric <= 0) {
        warnings.push('maxTokens должно быть положительным целым числом. Значение не будет отправлено.');
      } else {
        result.maxTokens = numeric;
      }
    }

    return {
      value: Object.keys(result).length > 0 ? result : undefined,
      warnings,
    };
  }, [maxTokens, temperature, topP]);

  const launchPayloadPreview = useMemo(() => {
    if (parametersPreview.error || sharedContextPreview.error) {
      return null;
    }
    const payload: Record<string, unknown> = {};
    if (parametersPreview.value !== undefined) {
      payload.parameters = parametersPreview.value;
    }
    if (sharedContextPreview.value !== undefined) {
      payload.sharedContext = sharedContextPreview.value;
    }
    if (overridesPreview.value) {
      payload.overrides = overridesPreview.value;
    }
    if (Object.keys(payload).length === 0) {
      return '{}';
    }
    return JSON.stringify(payload, null, 2);
  }, [overridesPreview, parametersPreview, sharedContextPreview]);

  const previewWarnings = useMemo(() => {
    const warnings: string[] = [];
    if (parametersPreview.error) {
      warnings.push(`Parameters: ${parametersPreview.error}`);
    }
    if (sharedContextPreview.error) {
      warnings.push(`Shared context: ${sharedContextPreview.error}`);
    }
    warnings.push(...overridesPreview.warnings);
    return warnings;
  }, [overridesPreview, parametersPreview, sharedContextPreview]);

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
          <div className="flow-launch__overrides">
            <label>
              Temperature (0…2)
              <input
                type="number"
                min={0}
                max={2}
                step={0.01}
                value={temperature}
                onChange={(event) => setTemperature(event.target.value)}
                placeholder="0.2"
              />
              {formErrors.temperature && (
                <span className="flow-launch__field-error">{formErrors.temperature}</span>
              )}
            </label>
            <label>
              topP (0…1]
              <input
                type="number"
                min={0}
                max={1}
                step={0.01}
                value={topP}
                onChange={(event) => setTopP(event.target.value)}
                placeholder="0.9"
              />
              {formErrors.topP && (
                <span className="flow-launch__field-error">{formErrors.topP}</span>
              )}
            </label>
            <label>
              maxTokens
              <input
                type="number"
                min={1}
                step={1}
                value={maxTokens}
                onChange={(event) => setMaxTokens(event.target.value)}
                placeholder="512"
              />
              {formErrors.maxTokens && (
                <span className="flow-launch__field-error">{formErrors.maxTokens}</span>
              )}
            </label>
          </div>
        </div>
        {previewWarnings.length > 0 && (
          <div className="flow-launch__warning" role="alert">
            <strong>Проверьте данные перед запуском:</strong>
            <ul>
              {previewWarnings.map((warning, index) => (
                <li key={`warning-${index}`}>{warning}</li>
              ))}
            </ul>
          </div>
        )}
        {launchPayloadPreview && (
          <div className="flow-launch__payload-preview">
            <header>Итоговый payload</header>
            <pre>{launchPayloadPreview}</pre>
          </div>
        )}
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
