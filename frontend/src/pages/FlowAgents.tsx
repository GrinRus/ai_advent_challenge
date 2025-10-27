import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  AgentCapability,
  AgentDefinitionDetails,
  AgentDefinitionPayload,
  AgentDefinitionSummary,
  AgentInvocationOptionsPayload,
  AgentVersionPayload,
  AgentVersionResponse,
  AgentVersionPublishPayload,
  ChatProvidersResponse,
} from '../lib/apiClient';
import {
  AgentCapabilityPayloadSchema,
} from '../lib/types/agent';
import {
  AgentCostProfileSchema,
  AgentInvocationOptionsInputSchema,
  AgentToolBindingSchema,
  AgentToolExecutionModeSchema,
} from '../lib/types/agentInvocation';
import type { AgentToolExecutionMode } from '../lib/types/agentInvocation';
import { JsonValueSchema } from '../lib/types/json';
import { parseJsonField } from '../lib/utils/json';
import {
  createAgentDefinition,
  createAgentVersion,
  deprecateAgentVersion,
  fetchAgentDefinition,
  fetchAgentDefinitions,
  fetchChatProviders,
  invalidateAgentCatalogCache,
  publishAgentVersion,
  updateAgentDefinition,
} from '../lib/apiClient';
import './FlowAgents.css';

type CapabilityDraft = {
  capability: string;
  payloadText: string;
};

type VersionFormState = {
  providerId: string;
  modelId: string;
  systemPrompt: string;
  temperature: string;
  topP: string;
  maxTokens: string;
  syncOnly: boolean;
  toolBindings: ToolBindingForm[];
  costProfile: CostProfileForm;
  capabilities: CapabilityDraft[];
};

type ToolBindingForm = {
  toolCode: string;
  schemaVersion: string;
  executionMode: AgentToolExecutionMode;
  requestOverrides: string;
  responseExpectations: string;
};

type CostProfileForm = {
  inputPer1KTokens: string;
  outputPer1KTokens: string;
  latencyFee: string;
  fixedFee: string;
  currency: string;
};

const defaultVersionForm = (): VersionFormState => ({
  providerId: '',
  modelId: '',
  systemPrompt: '',
  temperature: '',
  topP: '',
  maxTokens: '',
  syncOnly: true,
  toolBindings: [
    {
      toolCode: '',
      schemaVersion: '',
      executionMode: 'AUTO' as AgentToolExecutionMode,
      requestOverrides: '',
      responseExpectations: '',
    },
  ],
  costProfile: {
    inputPer1KTokens: '',
    outputPer1KTokens: '',
    latencyFee: '',
    fixedFee: '',
    currency: 'USD',
  },
  capabilities: [],
});

const FlowAgents = () => {
  const [operatorId, setOperatorId] = useState('ui-operator');
  const [definitions, setDefinitions] = useState<AgentDefinitionSummary[]>([]);
  const [selectedDefinitionId, setSelectedDefinitionId] = useState<string | null>(null);
  const [selectedDefinition, setSelectedDefinition] = useState<AgentDefinitionDetails | null>(
    null,
  );
  const [providers, setProviders] = useState<ChatProvidersResponse | null>(null);
  const [loadingList, setLoadingList] = useState(false);
  const [loadingDefinition, setLoadingDefinition] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [createForm, setCreateForm] = useState({
    identifier: '',
    displayName: '',
    description: '',
    active: true,
  });
  const [editForm, setEditForm] = useState({
    identifier: '',
    displayName: '',
    description: '',
    active: true,
  });
  const [versionForm, setVersionForm] = useState<VersionFormState>(defaultVersionForm);
  const [creatingDefinition, setCreatingDefinition] = useState(false);
  const [updatingDefinition, setUpdatingDefinition] = useState(false);
  const [creatingVersion, setCreatingVersion] = useState(false);
  const [publishingVersionId, setPublishingVersionId] = useState<string | null>(null);
  const [deprecatingVersionId, setDeprecatingVersionId] = useState<string | null>(null);

  const loadDefinitions = useCallback(async () => {
    setLoadingList(true);
    setError(null);
    try {
      const data = await fetchAgentDefinitions(true);
      setDefinitions(data);
      if (!selectedDefinitionId && data.length) {
        setSelectedDefinitionId(data[0].id);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoadingList(false);
    }
  }, [selectedDefinitionId]);

  const loadDefinition = useCallback(
    async (definitionId: string) => {
      setLoadingDefinition(true);
      setError(null);
      try {
        const detail = await fetchAgentDefinition(definitionId, { forceRefresh: true });
        setSelectedDefinition(detail);
        setEditForm({
          identifier: detail.identifier,
          displayName: detail.displayName,
          description: detail.description ?? '',
          active: detail.active,
        });
        setVersionForm((prev) => ({
          ...defaultVersionForm(),
          providerId: prev.providerId,
          modelId: prev.modelId,
        }));
      } catch (err) {
        setError((err as Error).message);
      } finally {
        setLoadingDefinition(false);
      }
    },
    [],
  );

  useEffect(() => {
    loadDefinitions();
  }, [loadDefinitions]);

  useEffect(() => {
    if (selectedDefinitionId) {
      loadDefinition(selectedDefinitionId);
    } else {
      setSelectedDefinition(null);
    }
  }, [selectedDefinitionId, loadDefinition]);

  useEffect(() => {
    fetchChatProviders()
      .then(setProviders)
      .catch(() => {
        // ignore provider errors in initial render; UI will show fallback.
      });
  }, []);

  const providerOptions = providers?.providers ?? [];
  const currentProvider = providerOptions.find(
    (provider) => provider.id === versionForm.providerId,
  );
  const modelOptions = useMemo(
    () => currentProvider?.models ?? [],
    [currentProvider],
  );

  useEffect(() => {
    if (currentProvider && modelOptions.length) {
      const selectedModel = modelOptions.find((model) => model.id === versionForm.modelId);
      if (!selectedModel) {
        setVersionForm((prev) => ({ ...prev, modelId: modelOptions[0].id }));
      }
    }
  }, [currentProvider, modelOptions, versionForm.modelId]);

  const handleDefinitionSelect = (definitionId: string) => {
    setSelectedDefinitionId(definitionId);
  };

  const handleCreateDefinition = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!operatorId.trim()) {
      setError('Укажите идентификатор оператора перед созданием агента');
      return;
    }
    setCreatingDefinition(true);
    setError(null);
    const payload: AgentDefinitionPayload = {
      identifier: createForm.identifier.trim(),
      displayName: createForm.displayName.trim(),
      description: createForm.description.trim() || undefined,
      active: createForm.active,
      createdBy: operatorId.trim(),
      updatedBy: operatorId.trim(),
    };
    try {
      const created = await createAgentDefinition(payload);
      invalidateAgentCatalogCache();
      await loadDefinitions();
      setSelectedDefinitionId(created.id);
      setCreateForm({
        identifier: '',
        displayName: '',
        description: '',
        active: true,
      });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setCreatingDefinition(false);
    }
  };

  const handleUpdateDefinition = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedDefinition) {
      return;
    }
    if (!operatorId.trim()) {
      setError('Укажите идентификатор оператора перед обновлением');
      return;
    }
    setUpdatingDefinition(true);
    setError(null);
    const payload: AgentDefinitionPayload = {
      identifier: editForm.identifier.trim() || selectedDefinition.identifier,
      displayName: editForm.displayName.trim(),
      description: editForm.description.trim() || undefined,
      active: editForm.active,
      updatedBy: operatorId.trim(),
    };
    try {
      await updateAgentDefinition(selectedDefinition.id, payload);
      invalidateAgentCatalogCache();
      await loadDefinitions();
      await loadDefinition(selectedDefinition.id);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setUpdatingDefinition(false);
    }
  };

  const buildCapabilitiesPayload = (drafts: CapabilityDraft[]): AgentCapability[] => {
    return drafts
      .filter((item) => item.capability.trim())
      .map((item) => {
        const payload = parseJsonField(
          `Payload для ${item.capability}`,
          item.payloadText,
          AgentCapabilityPayloadSchema,
        );
        return payload !== undefined
          ? { capability: item.capability.trim(), payload }
          : { capability: item.capability.trim() };
      });
  };

  const addToolBindingRow = () => {
    setVersionForm((prev) => ({
      ...prev,
      toolBindings: [
        ...prev.toolBindings,
        {
          toolCode: '',
          schemaVersion: '',
          executionMode: 'AUTO' as AgentToolExecutionMode,
          requestOverrides: '',
          responseExpectations: '',
        },
      ],
    }));
  };

  const updateToolBindingRow = (index: number, patch: Partial<ToolBindingForm>) => {
    setVersionForm((prev) => {
      const rows = [...prev.toolBindings];
      rows[index] = { ...rows[index], ...patch };
      return { ...prev, toolBindings: rows };
    });
  };

  const removeToolBindingRow = (index: number) => {
    setVersionForm((prev) => {
      const rows = prev.toolBindings.filter((_, idx) => idx !== index);
      return {
        ...prev,
        toolBindings: rows.length
          ? rows
          : [
              {
                toolCode: '',
                schemaVersion: '',
                executionMode: 'AUTO' as AgentToolExecutionMode,
                requestOverrides: '',
                responseExpectations: '',
              },
            ],
      };
    });
  };

  const buildToolBindings = (
    entries: ToolBindingForm[],
  ): AgentInvocationOptionsPayload['tooling'] | undefined => {
    const bindings: NonNullable<AgentInvocationOptionsPayload['tooling']>['bindings'] = [];

    entries.forEach((entry, index) => {
      const toolCode = entry.toolCode.trim();
      const schemaVersionRaw = entry.schemaVersion.trim();
      const requestOverridesRaw = entry.requestOverrides.trim();
      const responseExpectationsRaw = entry.responseExpectations.trim();

      if (!toolCode && !schemaVersionRaw && !requestOverridesRaw && !responseExpectationsRaw) {
        return;
      }

      if (!toolCode) {
        throw new Error(`Tool binding #${index + 1}: необходимо указать код инструмента`);
      }

      const candidate: Record<string, unknown> = {
        toolCode,
        executionMode: entry.executionMode,
      };

      if (schemaVersionRaw) {
        const parsedVersion = Number(schemaVersionRaw);
        if (!Number.isInteger(parsedVersion) || parsedVersion <= 0) {
          throw new Error(`Tool binding #${index + 1}: схема должна быть целым положительным числом`);
        }
        candidate.schemaVersion = parsedVersion;
      }

      if (requestOverridesRaw) {
        candidate.requestOverrides = parseJsonField(
          `Tool binding #${index + 1} (requestOverrides)`,
          requestOverridesRaw,
          JsonValueSchema,
        );
      }

      if (responseExpectationsRaw) {
        candidate.responseExpectations = parseJsonField(
          `Tool binding #${index + 1} (responseExpectations)`,
          responseExpectationsRaw,
          JsonValueSchema,
        );
      }

      const binding = AgentToolBindingSchema.parse(candidate);
      bindings.push(binding);
    });

    return bindings.length ? { bindings } : undefined;
  };

  const buildCostProfile = (
    form: CostProfileForm,
  ): AgentInvocationOptionsPayload['costProfile'] | undefined => {
    const record: Record<string, number | string | undefined> = {
      inputPer1KTokens: parseNumberInput(form.inputPer1KTokens),
      outputPer1KTokens: parseNumberInput(form.outputPer1KTokens),
      latencyFee: parseNumberInput(form.latencyFee),
      fixedFee: parseNumberInput(form.fixedFee),
      currency: form.currency.trim() || undefined,
    };

    const sanitized = Object.fromEntries(
      Object.entries(record).filter(([, value]) => value !== undefined && value !== ''),
    );

    return Object.keys(sanitized).length
      ? AgentCostProfileSchema.parse(sanitized)
      : undefined;
  };

  const parseNumberInput = (value: string): number | undefined => {
    if (!value.trim()) {
      return undefined;
    }
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  };

  type JsonValidationResult =
    | { state: 'empty' }
    | { state: 'ok'; preview: string }
    | { state: 'error'; message: string };

  const getJsonValidation = (value: string): JsonValidationResult => {
    const trimmed = value.trim();
    if (!trimmed) {
      return { state: 'empty' };
    }
    try {
      const parsed = JSON.parse(trimmed);
      return { state: 'ok', preview: JSON.stringify(parsed, null, 2) };
    } catch (error) {
      return {
        state: 'error',
        message: error instanceof Error ? error.message : 'Некорректный JSON',
      };
    }
  };

  const renderJsonFeedback = (value: string) => {
    const validation = getJsonValidation(value);
    if (validation.state === 'error') {
      return <span className="flow-agents__field-error">{validation.message}</span>;
    }
    if (validation.state === 'ok') {
      return (
        <pre className="flow-agents__json-preview">{validation.preview}</pre>
      );
    }
    return <span className="flow-agents__json-hint">Опционально</span>;
  };

  const renderToolBindingsSection = () => (
    <section className="flow-agents__kv">
      <div className="flow-agents__kv-header">
        <div>
          <h5>Tool bindings</h5>
          <p>
            Укажите инструменты, которые доступны агенту. Можно дополнительно передать
            `requestOverrides` и ожидания ответа (JSON).
          </p>
        </div>
        <button type="button" className="flow-agents__ghost" onClick={addToolBindingRow}>
          Добавить
        </button>
      </div>
      {versionForm.toolBindings.map((binding, index) => (
        <div key={`tool-${index}`} className="flow-agents__kv-item">
          <div className="flow-agents__kv-fields tool-binding-fields">
            <label>
              Tool code
              <input
                value={binding.toolCode}
                onChange={(event) =>
                  updateToolBindingRow(index, { toolCode: event.target.value })
                }
                placeholder="perplexity_search"
              />
            </label>
            <label>
              Schema version
              <input
                type="number"
                min="1"
                value={binding.schemaVersion}
                onChange={(event) =>
                  updateToolBindingRow(index, { schemaVersion: event.target.value })
                }
              />
            </label>
            <label>
              Execution mode
              <select
                value={binding.executionMode}
                onChange={(event) =>
                  updateToolBindingRow(index, {
                    executionMode: AgentToolExecutionModeSchema.parse(event.target.value),
                  })
                }
              >
                {AgentToolExecutionModeSchema.options.map((mode) => (
                  <option key={mode} value={mode}>
                    {mode}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className="tool-binding-json">
            <label>
              Request overrides (JSON)
              <textarea
                value={binding.requestOverrides}
                onChange={(event) =>
                  updateToolBindingRow(index, { requestOverrides: event.target.value })
                }
                placeholder='{"maxResults":3}'
              />
            </label>
            <div className="flow-agents__json-feedback">
              {renderJsonFeedback(binding.requestOverrides)}
            </div>
          </div>
          <div className="tool-binding-json">
            <label>
              Response expectations (JSON)
              <textarea
                value={binding.responseExpectations}
                onChange={(event) =>
                  updateToolBindingRow(index, { responseExpectations: event.target.value })
                }
                placeholder='{"fields":["title","url"]}'
              />
            </label>
            <div className="flow-agents__json-feedback">
              {renderJsonFeedback(binding.responseExpectations)}
            </div>
          </div>
          <button
            type="button"
            className="flow-agents__ghost flow-agents__kv-remove"
            onClick={() => removeToolBindingRow(index)}
          >
            Удалить
          </button>
        </div>
      ))}
    </section>
  );

  const renderCostProfileSection = () => (
    <section className="flow-agents__kv">
      <div className="flow-agents__kv-header">
        <div>
          <h5>Cost profile</h5>
          <p>Задайте индивидуальные ставки за 1K токенов и дополнительные сборы.</p>
        </div>
      </div>
      <div className="flow-agents__grid">
        <label>
          Input / 1K tokens
          <input
            type="number"
            step="0.0001"
            min="0"
            value={versionForm.costProfile.inputPer1KTokens}
            onChange={(event) =>
              setVersionForm((prev) => ({
                ...prev,
                costProfile: {
                  ...prev.costProfile,
                  inputPer1KTokens: event.target.value,
                },
              }))
            }
          />
        </label>
        <label>
          Output / 1K tokens
          <input
            type="number"
            step="0.0001"
            min="0"
            value={versionForm.costProfile.outputPer1KTokens}
            onChange={(event) =>
              setVersionForm((prev) => ({
                ...prev,
                costProfile: {
                  ...prev.costProfile,
                  outputPer1KTokens: event.target.value,
                },
              }))
            }
          />
        </label>
        <label>
          Latency fee
          <input
            type="number"
            step="0.0001"
            min="0"
            value={versionForm.costProfile.latencyFee}
            onChange={(event) =>
              setVersionForm((prev) => ({
                ...prev,
                costProfile: {
                  ...prev.costProfile,
                  latencyFee: event.target.value,
                },
              }))
            }
          />
        </label>
        <label>
          Fixed fee
          <input
            type="number"
            step="0.0001"
            min="0"
            value={versionForm.costProfile.fixedFee}
            onChange={(event) =>
              setVersionForm((prev) => ({
                ...prev,
                costProfile: {
                  ...prev.costProfile,
                  fixedFee: event.target.value,
                },
              }))
            }
          />
        </label>
        <label>
          Currency
          <input
            value={versionForm.costProfile.currency}
            onChange={(event) =>
              setVersionForm((prev) => ({
                ...prev,
                costProfile: {
                  ...prev.costProfile,
                  currency: event.target.value,
                },
              }))
            }
            placeholder="USD"
          />
        </label>
      </div>
    </section>
  );

  const handleCreateVersion = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedDefinition) {
      return;
    }
    if (!operatorId.trim()) {
      setError('Укажите идентификатор оператора перед созданием версии');
      return;
    }
    if (!versionForm.providerId || !versionForm.modelId) {
      setError('Выберите провайдера и модель');
      return;
    }
    setCreatingVersion(true);
    setError(null);
    try {
      const versionMaxTokens = parseNumberInput(versionForm.maxTokens);

      const temperatureValue = parseNumberInput(versionForm.temperature);
      const topPValue = parseNumberInput(versionForm.topP);
      const maxOutputTokens = versionMaxTokens;

      const invocationOptions = AgentInvocationOptionsInputSchema.parse({
        provider: {
          type: currentProvider?.type,
          id: versionForm.providerId,
          modelId: versionForm.modelId,
          mode: 'SYNC',
        },
        prompt: {
          system: versionForm.systemPrompt || undefined,
          generation:
            temperatureValue != null ||
            topPValue != null ||
            maxOutputTokens != null
              ? {
                  temperature: temperatureValue ?? undefined,
                  topP: topPValue ?? undefined,
                  maxOutputTokens: maxOutputTokens ?? undefined,
                }
              : undefined,
        },
        tooling: buildToolBindings(versionForm.toolBindings),
        costProfile: buildCostProfile(versionForm.costProfile),
      });

      const payload: AgentVersionPayload = {
        providerType: currentProvider?.type,
        providerId: versionForm.providerId,
        modelId: versionForm.modelId,
        systemPrompt: versionForm.systemPrompt,
        invocationOptions,
        createdBy: operatorId.trim(),
        syncOnly: versionForm.syncOnly,
        maxTokens: versionMaxTokens,
        capabilities: buildCapabilitiesPayload(versionForm.capabilities),
      };

      await createAgentVersion(selectedDefinition.id, payload);
      invalidateAgentCatalogCache();
      await loadDefinition(selectedDefinition.id);
      setVersionForm(defaultVersionForm());
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setCreatingVersion(false);
    }
  };

  const handlePublishVersion = async (version: AgentVersionResponse) => {
    if (!operatorId.trim()) {
      setError('Укажите идентификатор оператора перед публикацией версии');
      return;
    }
    setPublishingVersionId(version.id);
    setError(null);
    try {
      const payload: AgentVersionPublishPayload = {
        updatedBy: operatorId.trim(),
        capabilities: version.capabilities.map((item) => ({
          capability: item.capability,
          payload: item.payload,
        })),
      };
      await publishAgentVersion(version.id, payload);
      invalidateAgentCatalogCache();
      if (selectedDefinition) {
        await loadDefinition(selectedDefinition.id);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPublishingVersionId(null);
    }
  };

  const handleDeprecateVersion = async (version: AgentVersionResponse) => {
    setDeprecatingVersionId(version.id);
    setError(null);
    try {
      await deprecateAgentVersion(version.id);
      invalidateAgentCatalogCache();
      if (selectedDefinition) {
        await loadDefinition(selectedDefinition.id);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setDeprecatingVersionId(null);
    }
  };

  const versionRows = useMemo(() => selectedDefinition?.versions ?? [], [selectedDefinition]);

  return (
    <div className="flow-agents">
      <header className="flow-agents__header">
        <div>
          <h2>Каталог агентов</h2>
          <p className="flow-agents__subtitle">
            Управляйте определениями агентов и их версиями перед использованием во флоу
          </p>
        </div>
        <div className="flow-agents__operator">
          <label>
            Ваш идентификатор
            <input
              value={operatorId}
              onChange={(event) => setOperatorId(event.target.value)}
              placeholder="user:email"
            />
          </label>
          <button
            type="button"
            className="flow-agents__ghost"
            onClick={loadDefinitions}
            disabled={loadingList}
          >
            Обновить список
          </button>
        </div>
      </header>

      {error && <div className="flow-agents__error">{error}</div>}

      <div className="flow-agents__layout">
        <aside className="flow-agents__sidebar">
          <h3>Определения</h3>
          {loadingList ? (
            <div className="flow-agents__placeholder">Загрузка...</div>
          ) : (
            <ul>
              {definitions.map((definition) => (
                <li
                  key={definition.id}
                  className={
                    definition.id === selectedDefinitionId ? 'active flow-agents__item' : 'flow-agents__item'
                  }
                >
                  <button type="button" onClick={() => handleDefinitionSelect(definition.id)}>
                    <span className="flow-agents__item-title">{definition.displayName}</span>
                    <span className="flow-agents__item-meta">
                      {definition.identifier} •{' '}
                      {definition.active ? 'Активен' : 'Неактивен'}
                    </span>
                    <span className="flow-agents__item-meta">
                      Версии: {definition.latestVersion ?? '—'} /{' '}
                      {definition.latestPublishedVersion ?? '—'}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          <form className="flow-agents__card" onSubmit={handleCreateDefinition}>
            <h4>Создать определение</h4>
            <label>
              Identifier
              <input
                value={createForm.identifier}
                onChange={(event) =>
                  setCreateForm((prev) => ({ ...prev, identifier: event.target.value }))
                }
                required
              />
            </label>
            <label>
              Display name
              <input
                value={createForm.displayName}
                onChange={(event) =>
                  setCreateForm((prev) => ({ ...prev, displayName: event.target.value }))
                }
                required
              />
            </label>
            <label>
              Description
              <textarea
                value={createForm.description}
                onChange={(event) =>
                  setCreateForm((prev) => ({ ...prev, description: event.target.value }))
                }
              />
            </label>
            <label className="flow-agents__checkbox">
              <input
                type="checkbox"
                checked={createForm.active}
                onChange={(event) =>
                  setCreateForm((prev) => ({ ...prev, active: event.target.checked }))
                }
              />
              Активен после создания
            </label>
            <button type="submit" disabled={creatingDefinition}>
              {creatingDefinition ? 'Создание...' : 'Создать'}
            </button>
          </form>
        </aside>

        <section className="flow-agents__content">
          {loadingDefinition && (
            <div className="flow-agents__placeholder">Загрузка данных агента...</div>
          )}
          {!loadingDefinition && !selectedDefinition && (
            <div className="flow-agents__placeholder">
              Выберите определение, чтобы просмотреть детали
            </div>
          )}

          {selectedDefinition && (
            <div className="flow-agents__card flow-agents__editor">
              <header className="flow-agents__editor-header">
                <div>
                  <h3>{selectedDefinition.displayName}</h3>
                  <p className="flow-agents__editor-subtitle">
                    {selectedDefinition.identifier} •{' '}
                    {selectedDefinition.active ? 'активен' : 'неактивен'}
                  </p>
                </div>
                <div className="flow-agents__editor-meta">
                  <span>Создан: {selectedDefinition.createdAt ?? '—'}</span>
                  <span>Изменён: {selectedDefinition.updatedAt ?? '—'}</span>
                </div>
              </header>

              <form className="flow-agents__editor-form" onSubmit={handleUpdateDefinition}>
                <label>
                  Identifier
                  <input
                    value={editForm.identifier}
                    onChange={(event) =>
                      setEditForm((prev) => ({ ...prev, identifier: event.target.value }))
                    }
                  />
                </label>
                <label>
                  Display name
                  <input
                    value={editForm.displayName}
                    onChange={(event) =>
                      setEditForm((prev) => ({ ...prev, displayName: event.target.value }))
                    }
                    required
                  />
                </label>
                <label>
                  Description
                  <textarea
                    value={editForm.description}
                    onChange={(event) =>
                      setEditForm((prev) => ({ ...prev, description: event.target.value }))
                    }
                  />
                </label>
                <label className="flow-agents__checkbox">
                  <input
                    type="checkbox"
                    checked={editForm.active}
                    onChange={(event) =>
                      setEditForm((prev) => ({ ...prev, active: event.target.checked }))
                    }
                  />
                  Активен
                </label>
                <button type="submit" disabled={updatingDefinition}>
                  {updatingDefinition ? 'Сохранение...' : 'Сохранить изменения'}
                </button>
              </form>

              <section className="flow-agents__versions">
                <div className="flow-agents__versions-header">
                  <h4>Версии</h4>
                  <span>
                    Опубликовано:{' '}
                    {versionRows.filter((version) => version.status === 'PUBLISHED').length} /
                    {versionRows.length}
                  </span>
                </div>
                <div className="flow-agents__versions-table">
                  <table>
                    <thead>
                      <tr>
                        <th>Версия</th>
                        <th>Статус</th>
                        <th>Провайдер / Модель</th>
                        <th>Обновлён</th>
                        <th>Действия</th>
                      </tr>
                    </thead>
                    <tbody>
                      {versionRows.map((version) => (
                        <tr key={version.id}>
                          <td>v{version.version}</td>
                          <td>
                            <span className={`status-badge status-${version.status.toLowerCase()}`}>
                              {version.status}
                            </span>
                          </td>
                          <td className="flow-agents__nowrap">
                            {version.providerId} / {version.modelId}
                          </td>
                          <td className="flow-agents__nowrap">
                            {version.updatedBy ?? version.createdBy ?? '—'}
                          </td>
                          <td className="flow-agents__actions">
                            {version.status !== 'PUBLISHED' && (
                              <button
                                type="button"
                                onClick={() => handlePublishVersion(version)}
                                disabled={publishingVersionId === version.id}
                              >
                                {publishingVersionId === version.id ? 'Публикация...' : 'Опубликовать'}
                              </button>
                            )}
                            {version.status !== 'DEPRECATED' && (
                              <button
                                type="button"
                                className="flow-agents__ghost"
                                onClick={() => handleDeprecateVersion(version)}
                                disabled={deprecatingVersionId === version.id}
                              >
                                {deprecatingVersionId === version.id
                                  ? 'Деактивация...'
                                  : 'Депрецировать'}
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                      {!versionRows.length && (
                        <tr>
                          <td colSpan={5}>Версии ещё не созданы</td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>

                <form className="flow-agents__card flow-agents__version-form" onSubmit={handleCreateVersion}>
                  <h4>Создать версию</h4>
                  <div className="flow-agents__grid">
                    <label>
                      Провайдер
                      <select
                        value={versionForm.providerId}
                        onChange={(event) =>
                          setVersionForm((prev) => ({
                            ...prev,
                            providerId: event.target.value,
                          }))
                        }
                        required
                      >
                        <option value="" disabled>
                          Выберите провайдера
                        </option>
                        {providerOptions.map((provider) => (
                          <option key={provider.id} value={provider.id}>
                            {provider.displayName ?? provider.id}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Модель
                      <select
                        value={versionForm.modelId}
                        onChange={(event) =>
                          setVersionForm((prev) => ({
                            ...prev,
                            modelId: event.target.value,
                          }))
                        }
                        required
                        disabled={!modelOptions.length}
                      >
                        <option value="" disabled>
                          Выберите модель
                        </option>
                        {modelOptions.map((model) => (
                          <option key={model.id} value={model.id}>
                            {model.displayName ?? model.id}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Temperature
                      <input
                        type="number"
                        step="0.01"
                        min="0"
                        max="2"
                        value={versionForm.temperature}
                        onChange={(event) =>
                          setVersionForm((prev) => ({
                            ...prev,
                            temperature: event.target.value,
                          }))
                        }
                      />
                    </label>
                    <label>
                      Top P
                      <input
                        type="number"
                        step="0.01"
                        min="0"
                        max="1"
                        value={versionForm.topP}
                        onChange={(event) =>
                          setVersionForm((prev) => ({
                            ...prev,
                            topP: event.target.value,
                          }))
                        }
                      />
                    </label>
                    <label>
                      Max tokens
                      <input
                        type="number"
                        min="1"
                        value={versionForm.maxTokens}
                        onChange={(event) =>
                          setVersionForm((prev) => ({
                            ...prev,
                            maxTokens: event.target.value,
                          }))
                        }
                      />
                    </label>
                  </div>
                  <label>
                    System prompt
                    <textarea
                      required
                      value={versionForm.systemPrompt}
                      onChange={(event) =>
                        setVersionForm((prev) => ({
                          ...prev,
                          systemPrompt: event.target.value,
                        }))
                      }
                    />
                  </label>
                  {renderToolBindingsSection()}

                  {renderCostProfileSection()}
                  <label className="flow-agents__checkbox">
                    <input
                      type="checkbox"
                      checked={versionForm.syncOnly}
                      onChange={(event) =>
                        setVersionForm((prev) => ({
                          ...prev,
                          syncOnly: event.target.checked,
                        }))
                      }
                    />
                    Только синхронный режим (syncOnly)
                  </label>

                  <div className="flow-agents__capabilities">
                    <div className="flow-agents__capabilities-header">
                      <h5>Возможности</h5>
                      <button
                        type="button"
                        className="flow-agents__ghost"
                        onClick={() =>
                          setVersionForm((prev) => ({
                            ...prev,
                            capabilities: [
                              ...prev.capabilities,
                              { capability: '', payloadText: '' },
                            ],
                          }))
                        }
                      >
                        Добавить
                      </button>
                    </div>
                    {versionForm.capabilities.length === 0 && (
                      <div className="flow-agents__placeholder">Пока не добавлено</div>
                    )}
                    {versionForm.capabilities.map((item, index) => (
                      <div key={`cap-${index}`} className="flow-agents__capability-row">
                        <label>
                          Имя
                          <input
                            value={item.capability}
                            onChange={(event) => {
                              const value = event.target.value;
                              setVersionForm((prev) => {
                                const capabilities = [...prev.capabilities];
                                capabilities[index] = { ...capabilities[index], capability: value };
                                return { ...prev, capabilities };
                              });
                            }}
                          />
                        </label>
                        <label>
                          Payload (JSON)
                          <textarea
                            value={item.payloadText}
                            onChange={(event) => {
                              const value = event.target.value;
                              setVersionForm((prev) => {
                                const capabilities = [...prev.capabilities];
                                capabilities[index] = { ...capabilities[index], payloadText: value };
                                return { ...prev, capabilities };
                              });
                            }}
                            placeholder={'{"foo":"bar"'}
                          />
                        </label>
                        <div className="flow-agents__json-feedback">
                          {renderJsonFeedback(item.payloadText)}
                        </div>
                        <button
                          type="button"
                          className="flow-agents__ghost"
                          onClick={() =>
                            setVersionForm((prev) => ({
                              ...prev,
                              capabilities: prev.capabilities.filter((_, idx) => idx !== index),
                            }))
                          }
                        >
                          Удалить
                        </button>
                      </div>
                    ))}
                  </div>

                  <button type="submit" disabled={creatingVersion}>
                    {creatingVersion ? 'Создание...' : 'Создать версию'}
                  </button>
                </form>
              </section>
            </div>
          )}
        </section>
      </div>
    </div>
  );
};

export default FlowAgents;
