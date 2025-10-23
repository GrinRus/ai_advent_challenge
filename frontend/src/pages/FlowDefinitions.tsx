import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  FlowDefinitionSummary,
  FlowDefinitionDetails,
  FlowDefinitionHistoryEntry,
} from '../lib/apiClient';
import {
  fetchFlowDefinitions,
  fetchFlowDefinition,
  fetchFlowDefinitionHistory,
  createFlowDefinition,
  updateFlowDefinition,
  publishFlowDefinition,
  fetchAgentCatalog,
} from '../lib/apiClient';
import type { FlowDefinitionFormState, FlowStepForm } from '../lib/flowDefinitionForm';
import {
  buildFlowDefinition,
  createEmptyFlowDefinitionForm,
  parseFlowDefinition,
} from '../lib/flowDefinitionForm';
import './FlowDefinitions.css';

type AgentOption = {
  versionId: string;
  label: string;
  definitionId: string;
  definitionName: string;
  providerId: string;
  modelId: string;
  systemPrompt?: string;
};

const createEmptyStep = (): FlowStepForm => ({
  id: '',
  name: '',
  agentVersionId: '',
  prompt: '',
  temperature: null,
  topP: null,
  maxTokensOverride: null,
  memoryReadsText: '',
  memoryWritesText: '',
  transitionsText: '',
  maxAttempts: 1,
});

const FlowDefinitions = () => {
  const [definitions, setDefinitions] = useState<FlowDefinitionSummary[]>([]);
  const [selectedDefinitionId, setSelectedDefinitionId] = useState<string | null>(null);
  const [details, setDetails] = useState<FlowDefinitionDetails | null>(null);
  const [history, setHistory] = useState<FlowDefinitionHistoryEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isLoadingList, setIsLoadingList] = useState(false);
  const [isLoadingDefinition, setIsLoadingDefinition] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isPublishing, setIsPublishing] = useState(false);

  const [formState, setFormState] = useState<FlowDefinitionFormState | null>(null);
  const [selectedStepIndex, setSelectedStepIndex] = useState(0);
  const [formError, setFormError] = useState<string | null>(null);
  const [editedDescription, setEditedDescription] = useState('');
  const [updatedBy, setUpdatedBy] = useState('');
  const [changeNotes, setChangeNotes] = useState('');

  const [agentOptions, setAgentOptions] = useState<AgentOption[]>([]);
  const [agentLoading, setAgentLoading] = useState(false);

  const [newDefinitionName, setNewDefinitionName] = useState('');
  const [newDefinitionDescription, setNewDefinitionDescription] = useState('');
  const [newDefinitionAuthor, setNewDefinitionAuthor] = useState('');
  const [newDefinitionChangeNotes, setNewDefinitionChangeNotes] = useState('');
  const [newDefinitionForm, setNewDefinitionForm] = useState<FlowDefinitionFormState>(
    createEmptyFlowDefinitionForm(),
  );
  const [newSelectedStepIndex, setNewSelectedStepIndex] = useState(0);
  const [creatingDefinition, setCreatingDefinition] = useState(false);

  const agentOptionMap = useMemo(
    () => new Map(agentOptions.map((option) => [option.versionId, option])),
    [agentOptions],
  );

  const loadAgents = useCallback(async () => {
    setAgentLoading(true);
    try {
      const catalog = await fetchAgentCatalog(true);
      const options = catalog
        .filter((definition) => definition.active)
        .flatMap((definition) =>
          definition.versions
            .filter((version) => version.status === 'PUBLISHED')
            .map<AgentOption>((version) => ({
              versionId: version.id,
              definitionId: definition.id,
              definitionName: definition.displayName,
              label: `${definition.displayName} · v${version.version} (${version.modelId})`,
              providerId: version.providerId,
              modelId: version.modelId,
              systemPrompt: version.systemPrompt ?? undefined,
            })),
        );
      setAgentOptions(options);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setAgentLoading(false);
    }
  }, []);

  const loadDefinitions = useCallback(async () => {
    setIsLoadingList(true);
    setError(null);
    try {
      const data = await fetchFlowDefinitions();
      setDefinitions(data);
      if (!selectedDefinitionId && data.length) {
        setSelectedDefinitionId(data[0].id);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setIsLoadingList(false);
    }
  }, [selectedDefinitionId]);

  const loadDefinition = useCallback(
    async (definitionId: string) => {
      setIsLoadingDefinition(true);
      setError(null);
      try {
        const [definitionDetails, definitionHistory] = await Promise.all([
          fetchFlowDefinition(definitionId),
          fetchFlowDefinitionHistory(definitionId),
        ]);
        setDetails(definitionDetails);
        setHistory(definitionHistory);
      } catch (err) {
        setError((err as Error).message);
      } finally {
        setIsLoadingDefinition(false);
      }
    },
    [],
  );

  useEffect(() => {
    loadDefinitions();
    loadAgents();
  }, [loadDefinitions, loadAgents]);

  useEffect(() => {
    if (selectedDefinitionId) {
      loadDefinition(selectedDefinitionId);
    } else {
      setDetails(null);
      setHistory([]);
    }
  }, [selectedDefinitionId, loadDefinition]);

  useEffect(() => {
    if (!details) {
      setFormState(null);
      setSelectedStepIndex(0);
      setEditedDescription('');
      setUpdatedBy('');
      setChangeNotes('');
      return;
    }

    const parsed = parseFlowDefinition(details.definition);
    if (!parsed.startStepId && parsed.steps.length) {
      parsed.startStepId = parsed.steps[0].id;
    }
    setFormState(parsed);
    setSelectedStepIndex(0);
    setEditedDescription(details.description ?? '');
    setUpdatedBy(details.updatedBy ?? '');
    setChangeNotes('');
  }, [details]);

  const selectedStep = formState?.steps[selectedStepIndex];

  const updateStep = (index: number, updates: Partial<FlowStepForm>) => {
    setFormState((prev) => {
      if (!prev) {
        return prev;
      }
      const steps = [...prev.steps];
      const current = steps[index];
      if (!current) {
        return prev;
      }
      const nextStep = { ...current, ...updates };

      if (updates.id && prev.startStepId === current.id) {
        prev.startStepId = updates.id;
      }
      steps[index] = nextStep;
      return { ...prev, steps: steps };
    });
  };

  const removeStep = (index: number) => {
    setFormState((prev) => {
      if (!prev) {
        return prev;
      }
      const steps = prev.steps.filter((_, idx) => idx !== index);
      const nextStartId =
        steps.find((step) => step.id === prev.startStepId)?.id ?? steps[0]?.id ?? '';
      return {
        ...prev,
        steps,
        startStepId: nextStartId,
      };
    });
    setSelectedStepIndex((prevIdx) => Math.max(0, prevIdx - 1));
  };

  const addStep = () => {
    setFormState((prev) => {
      const base = prev ?? createEmptyFlowDefinitionForm();
      const steps = [...base.steps, createEmptyStep()];
      return {
        ...base,
        steps,
        startStepId: base.startStepId || steps[0]?.id || '',
      };
    });
    setSelectedStepIndex(formState?.steps.length ?? 0);
  };

  const moveStep = (index: number, delta: number) => {
    setFormState((prev) => {
      if (!prev) {
        return prev;
      }
      const steps = [...prev.steps];
      const targetIndex = index + delta;
      if (targetIndex < 0 || targetIndex >= steps.length) {
        return prev;
      }
      [steps[index], steps[targetIndex]] = [steps[targetIndex], steps[index]];
      return { ...prev, steps };
    });
    setSelectedStepIndex((prevIdx) => prevIdx + delta);
  };

  const handleSave = async () => {
    if (!details || !formState) {
      return;
    }
    if (!updatedBy.trim()) {
      setFormError('Укажите, кто вносит изменения');
      return;
    }
    setFormError(null);
    setIsSaving(true);
    try {
      const definitionPayload = buildFlowDefinition(formState);
      await updateFlowDefinition(details.id, {
        description: editedDescription,
        updatedBy: updatedBy.trim(),
        changeNotes: changeNotes.trim() || undefined,
        definition: definitionPayload,
      });
      await loadDefinition(details.id);
      await loadDefinitions();
    } catch (err) {
      setFormError((err as Error).message);
    } finally {
      setIsSaving(false);
    }
  };

  const handlePublish = async () => {
    if (!details) {
      return;
    }
    if (!updatedBy.trim()) {
      setFormError('Укажите, кто публикует определение');
      return;
    }
    setIsPublishing(true);
    setFormError(null);
    try {
      await publishFlowDefinition(details.id, {
        updatedBy: updatedBy.trim(),
        changeNotes: changeNotes.trim() || undefined,
      });
      await loadDefinition(details.id);
      await loadDefinitions();
    } catch (err) {
      setFormError((err as Error).message);
    } finally {
      setIsPublishing(false);
    }
  };

  const renderStepRow = (step: FlowStepForm, index: number) => {
    const agent = agentOptionMap.get(step.agentVersionId);
    const hasAgent = Boolean(agent);
    return (
      <tr
        key={`${step.id || 'step'}-${index}`}
        className={index === selectedStepIndex ? 'active' : undefined}
        onClick={() => setSelectedStepIndex(index)}
      >
        <td>{step.id || <em>не задан</em>}</td>
        <td>{step.name || '—'}</td>
        <td className={!hasAgent ? 'warning' : undefined}>
          {agent ? agent.label : 'Агент не найден'}
        </td>
        <td>{step.maxAttempts}</td>
        <td className="flow-definitions__row-actions">
          <button
            type="button"
            className="ghost"
            onClick={(event) => {
              event.stopPropagation();
              moveStep(index, -1);
            }}
            disabled={index === 0}
          >
            ↑
          </button>
          <button
            type="button"
            className="ghost"
            onClick={(event) => {
              event.stopPropagation();
              moveStep(index, 1);
            }}
            disabled={formState?.steps.length ? index >= formState.steps.length - 1 : true}
          >
            ↓
          </button>
          <button
            type="button"
            className="ghost"
            onClick={(event) => {
              event.stopPropagation();
              removeStep(index);
            }}
          >
            ×
          </button>
        </td>
      </tr>
    );
  };

  const handleNewDefinitionStepUpdate = (
    index: number,
    updates: Partial<FlowStepForm>,
  ) => {
    setNewDefinitionForm((prev) => {
      const steps = [...prev.steps];
      const current = steps[index];
      if (!current) {
        return prev;
      }
      const nextStep = { ...current, ...updates };
      steps[index] = nextStep;
      let startStepId = prev.startStepId;
      if (updates.id && prev.startStepId === current.id) {
        startStepId = updates.id;
      }
      return { ...prev, steps, startStepId };
    });
  };

  const handleCreateStepAdd = () => {
    setNewDefinitionForm((prev) => {
      const steps = [...prev.steps, createEmptyStep()];
      return {
        ...prev,
        steps,
        startStepId: prev.startStepId || steps[0]?.id || '',
      };
    });
    setNewSelectedStepIndex(newDefinitionForm.steps.length);
  };

  const handleCreateDefinition = async () => {
    if (!newDefinitionName.trim()) {
      setFormError('Введите название нового флоу');
      return;
    }
    if (!newDefinitionAuthor.trim()) {
      setFormError('Укажите автора (updatedBy) для создания черновика');
      return;
    }
    if (!newDefinitionForm.steps.length) {
      setFormError('Добавьте хотя бы один шаг перед созданием флоу');
      return;
    }
    setFormError(null);
    setCreatingDefinition(true);
    try {
      const body = buildFlowDefinition(newDefinitionForm);
      const created = await createFlowDefinition({
        name: newDefinitionName.trim(),
        description: newDefinitionDescription.trim() || undefined,
        updatedBy: newDefinitionAuthor.trim(),
        changeNotes: newDefinitionChangeNotes.trim() || undefined,
        definition: body,
      });
      await loadDefinitions();
      setSelectedDefinitionId(created.id);
      setNewDefinitionForm(createEmptyFlowDefinitionForm());
      setNewDefinitionName('');
      setNewDefinitionDescription('');
      setNewDefinitionAuthor('');
      setNewDefinitionChangeNotes('');
    } catch (err) {
      setFormError((err as Error).message);
    } finally {
      setCreatingDefinition(false);
    }
  };

  return (
    <div className="flow-definitions">
      <header className="flow-definitions__header">
        <div>
          <h2>Определения флоу</h2>
          <p className="flow-definitions__subtitle">
            Управляйте шагами мультиагентных сценариев и публикуйте готовые шаблоны
          </p>
        </div>
        <div className="flow-definitions__header-actions">
          <button
            type="button"
            className="ghost"
            onClick={loadDefinitions}
            disabled={isLoadingList}
          >
            Обновить список
          </button>
          <button
            type="button"
            className="ghost"
            onClick={loadAgents}
            disabled={agentLoading}
          >
            Обновить агентов
          </button>
        </div>
      </header>

      {error && <div className="flow-definitions__error">{error}</div>}
      {formError && <div className="flow-definitions__error">{formError}</div>}

      <div className="flow-definitions__layout">
        <aside className="flow-definitions__sidebar">
          <h3>Черновики и публикации</h3>
          {isLoadingList ? (
            <div className="flow-definitions__placeholder">Загрузка…</div>
          ) : (
            <ul>
              {definitions.map((definition) => (
                <li
                  key={definition.id}
                  className={
                    definition.id === selectedDefinitionId ? 'active flow-definitions__item' : 'flow-definitions__item'
                  }
                >
                  <button
                    type="button"
                    onClick={() => setSelectedDefinitionId(definition.id)}
                  >
                    <span className="definition-name">{definition.name}</span>
                    <span className="definition-meta">
                      v{definition.version} • {definition.status}
                    </span>
                    <span className="definition-meta">
                      Обновлён: {definition.updatedAt ?? '—'}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </aside>

        <section className="flow-definitions__content">
          <div className="flow-definitions__create">
            <h3>Создать новое определение</h3>
            <div className="field-grid">
              <label>
                Название
                <input
                  value={newDefinitionName}
                  onChange={(event) => setNewDefinitionName(event.target.value)}
                  placeholder="internal-flow-id"
                />
              </label>
              <label>
                Автор (updatedBy)
                <input
                  value={newDefinitionAuthor}
                  onChange={(event) => setNewDefinitionAuthor(event.target.value)}
                  placeholder="user:email"
                />
              </label>
              <label>
                Description
                <input
                  value={newDefinitionDescription}
                  onChange={(event) => setNewDefinitionDescription(event.target.value)}
                  placeholder="краткое описание"
                />
              </label>
              <label>
                Change notes
                <input
                  value={newDefinitionChangeNotes}
                  onChange={(event) => setNewDefinitionChangeNotes(event.target.value)}
                  placeholder="изменения для истории"
                />
              </label>
            </div>

            <div className="flow-definitions__steps">
              <div className="flow-definitions__steps-header">
                <h4>Шаги нового флоу</h4>
                <button type="button" className="ghost" onClick={handleCreateStepAdd}>
                  Добавить шаг
                </button>
              </div>
              <div className="flow-definitions__steps-table">
                <table>
                  <thead>
                    <tr>
                      <th>ID</th>
                      <th>Название</th>
                      <th>Агент</th>
                      <th>Попытки</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {newDefinitionForm.steps.map((step, index) => {
                      const agent = agentOptionMap.get(step.agentVersionId);
                      return (
                        <tr
                          key={`new-${index}`}
                          className={index === newSelectedStepIndex ? 'active' : undefined}
                          onClick={() => setNewSelectedStepIndex(index)}
                        >
                          <td>{step.id || <em>—</em>}</td>
                          <td>{step.name || '—'}</td>
                          <td className={!agent ? 'warning' : undefined}>
                            {agent ? agent.label : 'Агент не выбран'}
                          </td>
                          <td>{step.maxAttempts}</td>
                          <td className="flow-definitions__row-actions">
                            <button
                              type="button"
                              className="ghost"
                              onClick={(event) => {
                                event.stopPropagation();
                                setNewDefinitionForm((prev) => {
                                  const steps = prev.steps.filter((_, idx) => idx !== index);
                                  const startStepId =
                                    steps.find((step) => step.id === prev.startStepId)?.id ??
                                    steps[0]?.id ??
                                    '';
                                  return { ...prev, steps, startStepId };
                                });
                                setNewSelectedStepIndex((prevIdx) =>
                                  Math.max(0, prevIdx - 1),
                                );
                              }}
                            >
                              ×
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                    {!newDefinitionForm.steps.length && (
                      <tr>
                        <td colSpan={5}>
                          <span className="flow-definitions__placeholder">
                            Шаги ещё не добавлены
                          </span>
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>

              {newDefinitionForm.steps[newSelectedStepIndex] && (
                <div className="flow-definitions__step-editor">
                  <h5>Редактор шага</h5>
                  <div className="field-grid">
                    <label>
                      Step ID
                      <input
                        value={newDefinitionForm.steps[newSelectedStepIndex].id}
                        onChange={(event) =>
                          handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                            id: event.target.value,
                          })
                        }
                      />
                    </label>
                    <label>
                      Название
                      <input
                        value={newDefinitionForm.steps[newSelectedStepIndex].name}
                        onChange={(event) =>
                          handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                            name: event.target.value,
                          })
                        }
                      />
                    </label>
                    <label>
                      Агент
                      <select
                        value={
                          newDefinitionForm.steps[newSelectedStepIndex].agentVersionId || ''
                        }
                        onChange={(event) =>
                          handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                            agentVersionId: event.target.value,
                          })
                        }
                      >
                        <option value="">Выберите опубликованного агента</option>
                        {agentOptions.map((option) => (
                          <option key={option.versionId} value={option.versionId}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Попытки
                      <input
                        type="number"
                        min={1}
                        value={newDefinitionForm.steps[newSelectedStepIndex].maxAttempts}
                        onChange={(event) =>
                          handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                            maxAttempts: Number(event.target.value),
                          })
                        }
                      />
                    </label>
                  </div>
                  <label className="full-width">
                    Prompt
                    <textarea
                      value={newDefinitionForm.steps[newSelectedStepIndex].prompt}
                      onChange={(event) =>
                        handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                          prompt: event.target.value,
                        })
                      }
                    />
                  </label>
                  <div className="field-grid">
                    <label>
                      Temperature
                      <input
                        type="number"
                        step="0.01"
                        min={0}
                        max={2}
                        value={newDefinitionForm.steps[newSelectedStepIndex].temperature ?? ''}
                        onChange={(event) =>
                          handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                            temperature: event.target.value
                              ? Number(event.target.value)
                              : null,
                          })
                        }
                      />
                    </label>
                    <label>
                      Top P
                      <input
                        type="number"
                        step="0.01"
                        min={0}
                        max={1}
                        value={newDefinitionForm.steps[newSelectedStepIndex].topP ?? ''}
                        onChange={(event) =>
                          handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                            topP: event.target.value ? Number(event.target.value) : null,
                          })
                        }
                      />
                    </label>
                    <label>
                      Max tokens
                      <input
                        type="number"
                        min={1}
                        value={
                          newDefinitionForm.steps[newSelectedStepIndex].maxTokensOverride ??
                          ''
                        }
                        onChange={(event) =>
                          handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                            maxTokensOverride: event.target.value
                              ? Number(event.target.value)
                              : null,
                          })
                        }
                      />
                    </label>
                  </div>
                  <label className="full-width">
                    Memory reads (JSON)
                    <textarea
                      value={newDefinitionForm.steps[newSelectedStepIndex].memoryReadsText}
                      onChange={(event) =>
                        handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                          memoryReadsText: event.target.value,
                        })
                      }
                      placeholder='[{"channel":"context","limit":5}]'
                    />
                  </label>
                  <label className="full-width">
                    Memory writes (JSON)
                    <textarea
                      value={newDefinitionForm.steps[newSelectedStepIndex].memoryWritesText}
                      onChange={(event) =>
                        handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                          memoryWritesText: event.target.value,
                        })
                      }
                      placeholder='[{"channel":"context","mode":"AGENT_OUTPUT"}]'
                    />
                  </label>
                  <label className="full-width">
                    Transitions (JSON)
                    <textarea
                      value={newDefinitionForm.steps[newSelectedStepIndex].transitionsText}
                      onChange={(event) =>
                        handleNewDefinitionStepUpdate(newSelectedStepIndex, {
                          transitionsText: event.target.value,
                        })
                      }
                      placeholder='{"onSuccess":{"next":"step-2"}}'
                    />
                  </label>
                </div>
              )}
            </div>
            <button type="button" onClick={handleCreateDefinition} disabled={creatingDefinition}>
              {creatingDefinition ? 'Создание…' : 'Создать черновик'}
            </button>
          </div>

          <div className="flow-definitions__editor">
            {isLoadingDefinition && (
              <div className="flow-definitions__placeholder">Загрузка определения…</div>
            )}
            {!isLoadingDefinition && !formState && (
              <div className="flow-definitions__placeholder">
                Выберите определение из списка слева
              </div>
            )}
            {!isLoadingDefinition && formState && details && (
              <>
                <div className="editor-header">
                  <div>
                    <h3>{details.name}</h3>
                    <div className="definition-meta">
                      v{details.version} •{' '}
                      <span
                        className={`status-badge status-${details.status.toLowerCase()}`}
                      >
                        {details.status}
                      </span>{' '}
                      • {details.active ? 'активно' : 'неактивно'}
                    </div>
                  </div>
                  <div className="editor-header__actions">
                    <label>
                      Updated by
                      <input
                        value={updatedBy}
                        onChange={(event) => setUpdatedBy(event.target.value)}
                        placeholder="user:email"
                      />
                    </label>
                    <label>
                      Change notes
                      <input
                        value={changeNotes}
                        onChange={(event) => setChangeNotes(event.target.value)}
                        placeholder="Что изменилось?"
                      />
                    </label>
                    <button
                      type="button"
                      className="primary"
                      onClick={handleSave}
                      disabled={isSaving}
                    >
                      {isSaving ? 'Сохранение…' : 'Сохранить'}
                    </button>
                    <button
                      type="button"
                      onClick={handlePublish}
                      disabled={isPublishing}
                    >
                      {isPublishing ? 'Публикация…' : 'Опубликовать'}
                    </button>
                  </div>
                </div>

                <div className="field-grid">
                  <label>
                    Title
                    <input
                      value={formState.title}
                      onChange={(event) =>
                        setFormState((prev) =>
                          prev ? { ...prev, title: event.target.value } : prev,
                        )
                      }
                    />
                  </label>
                  <label>
                    Start step
                    <select
                      value={formState.startStepId}
                      onChange={(event) =>
                        setFormState((prev) =>
                          prev ? { ...prev, startStepId: event.target.value } : prev,
                        )
                      }
                    >
                      {formState.steps.map((step) => (
                        <option key={step.id || step.name} value={step.id}>
                          {step.id || step.name || '(без имени)'}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Description
                    <input
                      value={editedDescription}
                      onChange={(event) => setEditedDescription(event.target.value)}
                    />
                  </label>
                </div>

                <div className="flow-definitions__steps">
                  <div className="flow-definitions__steps-header">
                    <h4>Шаги</h4>
                    <button type="button" className="ghost" onClick={addStep}>
                      Добавить шаг
                    </button>
                  </div>
                  <div className="flow-definitions__steps-table">
                    <table>
                      <thead>
                        <tr>
                          <th>ID</th>
                          <th>Название</th>
                          <th>Агент</th>
                          <th>Попытки</th>
                          <th />
                        </tr>
                      </thead>
                      <tbody>
                        {formState.steps.map((step, index) => renderStepRow(step, index))}
                      </tbody>
                    </table>
                  </div>
                </div>

                {selectedStep && (
                  <div className="flow-definitions__step-editor">
                    <h4>Редактор шага</h4>
                    <div className="field-grid">
                      <label>
                        Step ID
                        <input
                          value={selectedStep.id}
                          onChange={(event) =>
                            updateStep(selectedStepIndex, { id: event.target.value })
                          }
                        />
                      </label>
                      <label>
                        Название
                        <input
                          value={selectedStep.name}
                          onChange={(event) =>
                            updateStep(selectedStepIndex, { name: event.target.value })
                          }
                        />
                      </label>
                      <label>
                        Агент
                        <select
                          value={selectedStep.agentVersionId}
                          onChange={(event) =>
                            updateStep(selectedStepIndex, {
                              agentVersionId: event.target.value,
                            })
                          }
                        >
                          <option value="">Выберите опубликованного агента</option>
                          {agentOptions.map((option) => (
                            <option key={option.versionId} value={option.versionId}>
                              {option.label}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label>
                        Попытки
                        <input
                          type="number"
                          min={1}
                          value={selectedStep.maxAttempts}
                          onChange={(event) =>
                            updateStep(selectedStepIndex, {
                              maxAttempts: Number(event.target.value),
                            })
                          }
                        />
                      </label>
                    </div>
                    <label className="full-width">
                      Prompt
                      <textarea
                        value={selectedStep.prompt}
                        onChange={(event) =>
                          updateStep(selectedStepIndex, { prompt: event.target.value })
                        }
                      />
                    </label>
                    <div className="field-grid">
                      <label>
                        Temperature
                        <input
                          type="number"
                          step="0.01"
                          min={0}
                          max={2}
                          value={selectedStep.temperature ?? ''}
                          onChange={(event) =>
                            updateStep(selectedStepIndex, {
                              temperature: event.target.value
                                ? Number(event.target.value)
                                : null,
                            })
                          }
                        />
                      </label>
                      <label>
                        Top P
                        <input
                          type="number"
                          step="0.01"
                          min={0}
                          max={1}
                          value={selectedStep.topP ?? ''}
                          onChange={(event) =>
                            updateStep(selectedStepIndex, {
                              topP: event.target.value ? Number(event.target.value) : null,
                            })
                          }
                        />
                      </label>
                      <label>
                        Max tokens
                        <input
                          type="number"
                          min={1}
                          value={selectedStep.maxTokensOverride ?? ''}
                          onChange={(event) =>
                            updateStep(selectedStepIndex, {
                              maxTokensOverride: event.target.value
                                ? Number(event.target.value)
                                : null,
                            })
                          }
                        />
                      </label>
                    </div>
                    <label className="full-width">
                      Memory reads (JSON)
                      <textarea
                        value={selectedStep.memoryReadsText}
                        onChange={(event) =>
                          updateStep(selectedStepIndex, {
                            memoryReadsText: event.target.value,
                          })
                        }
                      />
                    </label>
                    <label className="full-width">
                      Memory writes (JSON)
                      <textarea
                        value={selectedStep.memoryWritesText}
                        onChange={(event) =>
                          updateStep(selectedStepIndex, {
                            memoryWritesText: event.target.value,
                          })
                        }
                      />
                    </label>
                    <label className="full-width">
                      Transitions (JSON)
                      <textarea
                        value={selectedStep.transitionsText}
                        onChange={(event) =>
                          updateStep(selectedStepIndex, {
                            transitionsText: event.target.value,
                          })
                        }
                      />
                    </label>
                  </div>
                )}
              </>
            )}
          </div>

          {!!history.length && (
            <div className="history-panel">
              <h3>История изменений</h3>
              <ul>
                {history.map((entry) => (
                  <li key={entry.id}>
                    <div className="history-header">
                      <span>v{entry.version} • {entry.status}</span>
                      <span>{entry.createdAt}</span>
                    </div>
                    <div className="history-meta">
                      <span>Автор: {entry.createdBy ?? '—'}</span>
                      <span>Заметка: {entry.changeNotes ?? '—'}</span>
                    </div>
                    <details>
                      <summary>Показать JSON</summary>
                      <pre>{JSON.stringify(entry.definition, null, 2)}</pre>
                    </details>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      </div>
    </div>
  );
};

export default FlowDefinitions;
