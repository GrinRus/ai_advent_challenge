import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  AgentCapabilityPayload,
  AgentDefinitionDetails,
  AgentDefinitionPayload,
  AgentDefinitionSummary,
  AgentVersionPayload,
  AgentVersionResponse,
  AgentVersionPublishPayload,
  ChatProvidersResponse,
} from '../lib/apiClient';
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
  defaultOptions: string;
  toolBindings: string;
  costProfile: string;
  capabilities: CapabilityDraft[];
};

const defaultVersionForm = (): VersionFormState => ({
  providerId: '',
  modelId: '',
  systemPrompt: '',
  temperature: '',
  topP: '',
  maxTokens: '',
  syncOnly: true,
  defaultOptions: '',
  toolBindings: '',
  costProfile: '',
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
  const modelOptions = currentProvider?.models ?? [];

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

  const parseOptionalJson = (label: string, value: string): unknown => {
    if (!value.trim()) {
      return undefined;
    }
    try {
      return JSON.parse(value);
    } catch (error) {
      throw new Error(`Поле "${label}" содержит некорректный JSON: ${(error as Error).message}`);
    }
  };

  const buildCapabilitiesPayload = (drafts: CapabilityDraft[]): AgentCapabilityPayload[] => {
    return drafts
      .filter((item) => item.capability.trim())
      .map((item) => ({
        capability: item.capability.trim(),
        payload: parseOptionalJson(`Payload для ${item.capability}`, item.payloadText),
      }));
  };

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
      const payload: AgentVersionPayload = {
        providerId: versionForm.providerId,
        modelId: versionForm.modelId,
        systemPrompt: versionForm.systemPrompt,
        createdBy: operatorId.trim(),
        syncOnly: versionForm.syncOnly,
        maxTokens: versionForm.maxTokens ? Number(versionForm.maxTokens) : undefined,
        capabilities: buildCapabilitiesPayload(versionForm.capabilities),
        defaultOptions: parseOptionalJson('Default options', versionForm.defaultOptions),
        toolBindings: parseOptionalJson('Tool bindings', versionForm.toolBindings),
        costProfile: parseOptionalJson('Cost profile', versionForm.costProfile),
      };
      if (versionForm.temperature) {
        payload.defaultOptions = {
          ...(payload.defaultOptions as Record<string, unknown> | undefined),
          temperature: Number(versionForm.temperature),
        };
      }
      if (versionForm.topP) {
        payload.defaultOptions = {
          ...(payload.defaultOptions as Record<string, unknown> | undefined),
          topP: Number(versionForm.topP),
        };
      }
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
            className="ghost"
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
                                className="ghost"
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
                  <div className="flow-agents__grid">
                    <label>
                      Default options (JSON)
                      <textarea
                        value={versionForm.defaultOptions}
                        onChange={(event) =>
                          setVersionForm((prev) => ({
                            ...prev,
                            defaultOptions: event.target.value,
                          }))
                        }
                        placeholder={'{"temperature":0.2}'}
                      />
                    </label>
                    <label>
                      Tool bindings (JSON)
                      <textarea
                        value={versionForm.toolBindings}
                        onChange={(event) =>
                          setVersionForm((prev) => ({
                            ...prev,
                            toolBindings: event.target.value,
                          }))
                        }
                        placeholder={'{"tools":[]}'}
                      />
                    </label>
                    <label>
                      Cost profile (JSON)
                      <textarea
                        value={versionForm.costProfile}
                        onChange={(event) =>
                          setVersionForm((prev) => ({
                            ...prev,
                            costProfile: event.target.value,
                          }))
                        }
                        placeholder={'{"inputPer1K":0.001}'}
                      />
                    </label>
                  </div>
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
                        className="ghost"
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
                            placeholder={'{"foo":"bar"}'}
                          />
                        </label>
                        <button
                          type="button"
                          className="ghost"
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
