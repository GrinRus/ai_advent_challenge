import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ChangeEvent } from 'react';
import type {
  FlowDefinitionDetails,
  FlowDefinitionHistoryEntry,
  FlowDefinitionSummary,
} from '../lib/apiClient';
import {
  createFlowDefinition,
  fetchAgentCatalog,
  fetchFlowDefinition,
  fetchFlowDefinitionHistory,
  fetchFlowDefinitions,
  publishFlowDefinition,
  updateFlowDefinition,
} from '../lib/apiClient';
import type {
  FlowDefinitionFormState,
  FlowLaunchParameterForm,
  FlowSharedChannelForm,
  FlowStepForm,
} from '../lib/flowDefinitionForm';
import {
  buildFlowDefinition,
  createEmptyFlowDefinitionForm,
  createEmptyLaunchParameterForm,
  createEmptySharedChannelForm,
  createEmptyStepForm,
  parseFlowDefinition,
} from '../lib/flowDefinitionForm';
import { MemoryWriteModeSchema } from '../lib/types/flowDefinition';
import { FlowInteractionTypeSchema } from '../lib/types/flowInteraction';
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

type ApplyFormUpdate = (updater: (prev: FlowDefinitionFormState) => FlowDefinitionFormState) => void;

const interactionOptions = ['', ...FlowInteractionTypeSchema.options];
const memoryWriteModes = MemoryWriteModeSchema.options;

const FlowDefinitions = () => {
  const [definitions, setDefinitions] = useState<FlowDefinitionSummary[]>([]);
  const [selectedDefinitionId, setSelectedDefinitionId] = useState<string | null>(null);
  const [details, setDetails] = useState<FlowDefinitionDetails | null>(null);
  const [history, setHistory] = useState<FlowDefinitionHistoryEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [isLoadingList, setIsLoadingList] = useState(false);
  const [, setIsLoadingDefinition] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isPublishing, setIsPublishing] = useState(false);

  const [formState, setFormState] = useState<FlowDefinitionFormState | null>(null);
  const [selectedStepIndex, setSelectedStepIndex] = useState(0);

  const [agentOptions, setAgentOptions] = useState<AgentOption[]>([]);
  const [, setAgentLoading] = useState(false);

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

  const updateExistingForm: ApplyFormUpdate = useCallback(
    (updater) => {
      setFormState((prev) => (prev ? updater(prev) : prev));
    },
    [],
  );

  const updateNewForm: ApplyFormUpdate = (updater) => {
    setNewDefinitionForm((prev) => updater(prev));
  };

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
      setFormState(null);
    }
  }, [selectedDefinitionId, loadDefinition]);

  useEffect(() => {
    if (!details) {
      setFormState(null);
      setSelectedStepIndex(0);
      return;
    }
    const parsed = parseFlowDefinition(details.definition);
    if (!parsed.startStepId && parsed.steps.length) {
      parsed.startStepId = parsed.steps[0].id;
    }
    setFormState(parsed);
    setSelectedStepIndex(0);
  }, [details]);

  const addLaunchParameter = (apply: ApplyFormUpdate) =>
    apply((prev) => ({
      ...prev,
      launchParameters: [...prev.launchParameters, createEmptyLaunchParameterForm()],
    }));

  const updateLaunchParameter = (
    apply: ApplyFormUpdate,
    index: number,
    patch: Partial<FlowLaunchParameterForm>,
  ) =>
    apply((prev) => {
      const launchParameters = [...prev.launchParameters];
      launchParameters[index] = { ...launchParameters[index], ...patch };
      return { ...prev, launchParameters };
    });

  const removeLaunchParameter = (apply: ApplyFormUpdate, index: number) =>
    apply((prev) => ({
      ...prev,
      launchParameters: prev.launchParameters.filter((_, idx) => idx !== index),
    }));

  const addSharedChannel = (apply: ApplyFormUpdate) =>
    apply((prev) => ({
      ...prev,
      sharedChannels: [...prev.sharedChannels, createEmptySharedChannelForm()],
    }));

  const updateSharedChannel = (
    apply: ApplyFormUpdate,
    index: number,
    patch: Partial<FlowSharedChannelForm>,
  ) =>
    apply((prev) => {
      const sharedChannels = [...prev.sharedChannels];
      sharedChannels[index] = { ...sharedChannels[index], ...patch };
      return { ...prev, sharedChannels };
    });

  const removeSharedChannel = (apply: ApplyFormUpdate, index: number) =>
    apply((prev) => ({
      ...prev,
      sharedChannels: prev.sharedChannels.filter((_, idx) => idx !== index),
    }));

  const addStep = () => {
    updateExistingForm((prev) => {
      const steps = [...prev.steps, createEmptyStepForm()];
      return {
        ...prev,
        steps,
        startStepId: prev.startStepId || steps[0]?.id || '',
      };
    });
    setSelectedStepIndex(formState?.steps.length ?? 0);
  };

  const removeStep = (index: number) => {
    updateExistingForm((prev) => {
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

  const moveStep = (index: number, delta: number) => {
    updateExistingForm((prev) => {
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

  const updateStepAt = (
    apply: ApplyFormUpdate,
    stepIndex: number,
    updater: (prev: FlowStepForm) => FlowStepForm,
  ) =>
    apply((prev) => {
      const steps = [...prev.steps];
      const current = steps[stepIndex];
      if (!current) {
        return prev;
      }
      const nextStep = updater(current);
      steps[stepIndex] = nextStep;
      const startStepId = current.id === prev.startStepId ? nextStep.id || '' : prev.startStepId;
      return { ...prev, steps, startStepId };
    });

  const renderLaunchParametersSection = (
    form: FlowDefinitionFormState,
    apply: ApplyFormUpdate,
  ) => (
    <section className="flow-definitions__block">
      <div className="flow-definitions__block-header">
        <h4>Launch parameters</h4>
        <button type="button" className="ghost" onClick={() => addLaunchParameter(apply)}>
          Добавить
        </button>
      </div>
      {form.launchParameters.length === 0 ? (
        <div className="flow-definitions__placeholder">Параметры запуска не заданы</div>
      ) : (
        <div className="flow-definitions__grid">
          {form.launchParameters.map((parameter, index) => (
            <div key={`launch-${index}`} className="flow-definitions__parameter-card">
              <div className="flow-definitions__parameter-fields">
                <label>
                  Имя
                  <input
                    value={parameter.name}
                    onChange={(event) =>
                      updateLaunchParameter(apply, index, { name: event.target.value })
                    }
                  />
                </label>
                <label>
                  Label
                  <input
                    value={parameter.label}
                    onChange={(event) =>
                      updateLaunchParameter(apply, index, { label: event.target.value })
                    }
                  />
                </label>
                <label>
                  Тип
                  <input
                    value={parameter.type}
                    onChange={(event) =>
                      updateLaunchParameter(apply, index, { type: event.target.value })
                    }
                  />
                </label>
                <label className="flow-definitions__checkbox">
                  <input
                    type="checkbox"
                    checked={parameter.required}
                    onChange={(event) =>
                      updateLaunchParameter(apply, index, { required: event.target.checked })
                    }
                  />
                  Обязательный
                </label>
              </div>
              <label>
                Описание
                <textarea
                  value={parameter.description}
                  onChange={(event) =>
                    updateLaunchParameter(apply, index, { description: event.target.value })
                  }
                />
              </label>
              <label>
                JSON Schema
                <textarea
                  value={parameter.schemaText}
                  onChange={(event) =>
                    updateLaunchParameter(apply, index, { schemaText: event.target.value })
                  }
                  placeholder='{"type":"string"}'
                />
              </label>
              <label>
                Значение по умолчанию (JSON)
                <textarea
                  value={parameter.defaultValueText}
                  onChange={(event) =>
                    updateLaunchParameter(apply, index, { defaultValueText: event.target.value })
                  }
                  placeholder='"Acme"'
                />
              </label>
              <button
                type="button"
                className="ghost"
                onClick={() => removeLaunchParameter(apply, index)}
              >
                Удалить
              </button>
            </div>
          ))}
        </div>
      )}
    </section>
  );

  const renderSharedChannelsSection = (
    form: FlowDefinitionFormState,
    apply: ApplyFormUpdate,
  ) => (
    <section className="flow-definitions__block">
      <div className="flow-definitions__block-header">
        <h4>Shared memory channels</h4>
        <button type="button" className="ghost" onClick={() => addSharedChannel(apply)}>
          Добавить
        </button>
      </div>
      {form.sharedChannels.length === 0 ? (
        <div className="flow-definitions__placeholder">Каналы не настроены</div>
      ) : (
        <div className="flow-definitions__grid">
          {form.sharedChannels.map((channel, index) => (
            <div key={`shared-${index}`} className="flow-definitions__parameter-card">
              <label>
                Канал
                <input
                  value={channel.id}
                  onChange={(event) =>
                    updateSharedChannel(apply, index, { id: event.target.value })
                  }
                  placeholder="shared"
                />
              </label>
              <label>
                Retention versions
                <input
                  type="number"
                  min="1"
                  value={channel.retentionVersions}
                  onChange={(event) =>
                    updateSharedChannel(apply, index, { retentionVersions: event.target.value })
                  }
                />
              </label>
              <label>
                Retention days
                <input
                  type="number"
                  min="1"
                  value={channel.retentionDays}
                  onChange={(event) =>
                    updateSharedChannel(apply, index, { retentionDays: event.target.value })
                  }
                />
              </label>
              <button
                type="button"
                className="ghost"
                onClick={() => removeSharedChannel(apply, index)}
              >
                Удалить
              </button>
            </div>
          ))}
        </div>
      )}
    </section>
  );

  const renderStepEditor = (
    step: FlowStepForm,
    updateStep: (updater: (prev: FlowStepForm) => FlowStepForm) => void,
  ) => (
    <div className="flow-definitions__step-editor">
      <h5>Редактор шага</h5>
      <div className="field-grid">
        <label>
          Step ID
          <input
            value={step.id}
            onChange={(event) =>
              updateStep((prev) => ({ ...prev, id: event.target.value }))
            }
          />
        </label>
        <label>
          Название
          <input
            value={step.name}
            onChange={(event) =>
              updateStep((prev) => ({ ...prev, name: event.target.value }))
            }
          />
        </label>
        <label>
          Агент
          <select
            value={step.agentVersionId}
            onChange={(event) =>
              updateStep((prev) => ({ ...prev, agentVersionId: event.target.value }))
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
            min="1"
            value={step.maxAttempts}
            onChange={(event) =>
              updateStep((prev) => ({ ...prev, maxAttempts: event.target.value }))
            }
          />
        </label>
      </div>
      <label className="full-width">
        Prompt
        <textarea
          value={step.prompt}
          onChange={(event) =>
            updateStep((prev) => ({ ...prev, prompt: event.target.value }))
          }
        />
      </label>
      <div className="field-grid">
        <label>
          Temperature
          <input
            type="number"
            step="0.01"
            min="0"
            max="2"
            value={step.overrides.temperature}
            onChange={(event) =>
              updateStep((prev) => ({
                ...prev,
                overrides: { ...prev.overrides, temperature: event.target.value },
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
            value={step.overrides.topP}
            onChange={(event) =>
              updateStep((prev) => ({
                ...prev,
                overrides: { ...prev.overrides, topP: event.target.value },
              }))
            }
          />
        </label>
        <label>
          Max tokens
          <input
            type="number"
            min="1"
            value={step.overrides.maxTokens}
            onChange={(event) =>
              updateStep((prev) => ({
                ...prev,
                overrides: { ...prev.overrides, maxTokens: event.target.value },
              }))
            }
          />
        </label>
      </div>
      <section className="flow-definitions__sub-block">
        <div className="flow-definitions__sub-header">
          <h6>Memory reads</h6>
          <button
            type="button"
            className="ghost"
            onClick={() =>
              updateStep((prev) => ({
                ...prev,
                memoryReads: [...prev.memoryReads, { channel: '', limit: '' }],
              }))
            }
          >
            Добавить
          </button>
        </div>
        {step.memoryReads.length === 0 ? (
          <div className="flow-definitions__placeholder">Чтения не настроены</div>
        ) : (
          step.memoryReads.map((read, readIndex) => (
            <div key={`read-${readIndex}`} className="field-grid">
              <label>
                Канал
                <input
                  value={read.channel}
                  onChange={(event) =>
                    updateStep((prev) => {
                      const memoryReads = [...prev.memoryReads];
                      memoryReads[readIndex] = {
                        ...memoryReads[readIndex],
                        channel: event.target.value,
                      };
                      return { ...prev, memoryReads };
                    })
                  }
                />
              </label>
              <label>
                Limit
                <input
                  type="number"
                  min="1"
                  value={read.limit}
                  onChange={(event) =>
                    updateStep((prev) => {
                      const memoryReads = [...prev.memoryReads];
                      memoryReads[readIndex] = {
                        ...memoryReads[readIndex],
                        limit: event.target.value,
                      };
                      return { ...prev, memoryReads };
                    })
                  }
                />
              </label>
              <button
                type="button"
                className="ghost"
                onClick={() =>
                  updateStep((prev) => ({
                    ...prev,
                    memoryReads: prev.memoryReads.filter((_, idx) => idx !== readIndex),
                  }))
                }
              >
                Удалить
              </button>
            </div>
          ))
        )}
      </section>
      <section className="flow-definitions__sub-block">
        <div className="flow-definitions__sub-header">
          <h6>Memory writes</h6>
          <button
            type="button"
            className="ghost"
            onClick={() =>
              updateStep((prev) => ({
                ...prev,
                memoryWrites: [
                  ...prev.memoryWrites,
                  { channel: '', mode: 'AGENT_OUTPUT', payloadText: '' },
                ],
              }))
            }
          >
            Добавить
          </button>
        </div>
        {step.memoryWrites.length === 0 ? (
          <div className="flow-definitions__placeholder">Записи не настроены</div>
        ) : (
          step.memoryWrites.map((write, writeIndex) => (
            <div key={`write-${writeIndex}`} className="field-grid">
              <label>
                Канал
                <input
                  value={write.channel}
                  onChange={(event) =>
                    updateStep((prev) => {
                      const memoryWrites = [...prev.memoryWrites];
                      memoryWrites[writeIndex] = {
                        ...memoryWrites[writeIndex],
                        channel: event.target.value,
                      };
                      return { ...prev, memoryWrites };
                    })
                  }
                />
              </label>
              <label>
                Режим
                <select
                  value={write.mode}
                  onChange={(event) =>
                    updateStep((prev) => {
                      const memoryWrites = [...prev.memoryWrites];
                      memoryWrites[writeIndex] = {
                        ...memoryWrites[writeIndex],
                        mode: MemoryWriteModeSchema.parse(event.target.value),
                      };
                      return { ...prev, memoryWrites };
                    })
                  }
                >
                  {memoryWriteModes.map((mode) => (
                    <option key={mode} value={mode}>
                      {mode}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Payload (JSON)
                <textarea
                  value={write.payloadText}
                  onChange={(event) =>
                    updateStep((prev) => {
                      const memoryWrites = [...prev.memoryWrites];
                      memoryWrites[writeIndex] = {
                        ...memoryWrites[writeIndex],
                        payloadText: event.target.value,
                      };
                      return { ...prev, memoryWrites };
                    })
                  }
                  placeholder='{"foo":"bar"}'
                />
              </label>
              <button
                type="button"
                className="ghost"
                onClick={() =>
                  updateStep((prev) => ({
                    ...prev,
                    memoryWrites: prev.memoryWrites.filter((_, idx) => idx !== writeIndex),
                  }))
                }
              >
                Удалить
              </button>
            </div>
          ))
        )}
      </section>
      <section className="flow-definitions__sub-block">
        <h6>Взаимодействие (HITL)</h6>
        <div className="field-grid">
          <label>
            Тип
            <select
              value={step.interaction.type}
              onChange={(event) =>
                updateStep((prev) => ({
                  ...prev,
                  interaction: { ...prev.interaction, type: event.target.value },
                }))
              }
            >
              {interactionOptions.map((type) => (
                <option key={type || 'none'} value={type}>
                  {type || '—'}
                </option>
              ))}
            </select>
          </label>
          <label>
            Заголовок
            <input
              value={step.interaction.title}
              onChange={(event) =>
                updateStep((prev) => ({
                  ...prev,
                  interaction: { ...prev.interaction, title: event.target.value },
                }))
              }
            />
          </label>
          <label>
            Due (мин)
            <input
              type="number"
              min="1"
              value={step.interaction.dueInMinutes}
              onChange={(event) =>
                updateStep((prev) => ({
                  ...prev,
                  interaction: {
                    ...prev.interaction,
                    dueInMinutes: event.target.value,
                  },
                }))
              }
            />
          </label>
        </div>
        <label>
          Описание
          <textarea
            value={step.interaction.description}
            onChange={(event) =>
              updateStep((prev) => ({
                ...prev,
                interaction: {
                  ...prev.interaction,
                  description: event.target.value,
                },
              }))
            }
          />
        </label>
        <label>
          Payload schema (JSON)
          <textarea
            value={step.interaction.payloadSchemaText}
            onChange={(event) =>
              updateStep((prev) => ({
                ...prev,
                interaction: {
                  ...prev.interaction,
                  payloadSchemaText: event.target.value,
                },
              }))
            }
            placeholder='{"type":"object"}'
          />
        </label>
        <label>
          Suggested actions (JSON)
          <textarea
            value={step.interaction.suggestedActionsText}
            onChange={(event) =>
              updateStep((prev) => ({
                ...prev,
                interaction: {
                  ...prev.interaction,
                  suggestedActionsText: event.target.value,
                },
              }))
            }
            placeholder='[{"label":"Approve","value":"approve"}]'
          />
        </label>
      </section>
      <section className="flow-definitions__sub-block">
        <h6>Переходы</h6>
        <div className="field-grid">
          <label>
            On success → next step
            <input
              value={step.transitions.onSuccessNext}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                updateStep((prev) => ({
                  ...prev,
                  transitions: {
                    ...prev.transitions,
                    onSuccessNext: event.target.value,
                  },
                }))
              }
            />
          </label>
          <label className="flow-definitions__checkbox">
            <input
              type="checkbox"
              checked={step.transitions.onSuccessComplete}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                updateStep((prev) => ({
                  ...prev,
                  transitions: {
                    ...prev.transitions,
                    onSuccessComplete: event.target.checked,
                  },
                }))
              }
            />
            Завершить флоу на успехе
          </label>
        </div>
        <div className="field-grid">
          <label>
            On failure → next step
            <input
              value={step.transitions.onFailureNext}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                updateStep((prev) => ({
                  ...prev,
                  transitions: {
                    ...prev.transitions,
                    onFailureNext: event.target.value,
                  },
                }))
              }
            />
          </label>
          <label className="flow-definitions__checkbox">
            <input
              type="checkbox"
              checked={step.transitions.onFailureFail}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                updateStep((prev) => ({
                  ...prev,
                  transitions: {
                    ...prev.transitions,
                    onFailureFail: event.target.checked,
                  },
                }))
              }
            />
            Прервать флоу на ошибке
          </label>
        </div>
      </section>
    </div>
  );

  const handleSave = async () => {
    if (!details || !formState) {
      return;
    }
    if (!formState.draft.updatedBy && !details.updatedBy) {
      // in case parsed draft did not contain author
    }
    if (!formState.title.trim()) {
      setFormError('Введите название флоу (metadata.title)');
      return;
    }
    if (!formState.syncOnly && formState.syncOnly !== false) {
      // no-op for now
    }
    setFormError(null);
    setIsSaving(true);
    try {
      const definitionPayload = buildFlowDefinition(formState);
      await updateFlowDefinition(details.id, {
        description: formState.description || undefined,
        updatedBy: details.updatedBy ?? undefined,
        changeNotes: undefined,
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
    const draftMeta = formState?.draft as Record<string, unknown> | undefined;
    const draftUpdatedBy =
      typeof draftMeta?.updatedBy === 'string' && draftMeta.updatedBy.trim()
        ? draftMeta.updatedBy.trim()
        : undefined;
    const detailUpdatedBy =
      typeof details.updatedBy === 'string' && details.updatedBy.trim()
        ? details.updatedBy.trim()
        : undefined;
    const updatedBy = draftUpdatedBy ?? detailUpdatedBy ?? '';
    setFormError(null);
    setIsPublishing(true);
    try {
      await publishFlowDefinition(details.id, {
        updatedBy,
        changeNotes: undefined,
      });
      await loadDefinition(details.id);
      await loadDefinitions();
    } catch (err) {
      setFormError((err as Error).message);
    } finally {
      setIsPublishing(false);
    }
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
      setNewSelectedStepIndex(0);
    } catch (err) {
      setFormError((err as Error).message);
    } finally {
      setCreatingDefinition(false);
    }
  };

  const renderStepRow = (
    step: FlowStepForm,
    index: number,
    activeIndex: number,
    onSelect: (index: number) => void,
    onMove?: (index: number, delta: number) => void,
    onRemove?: (index: number) => void,
  ) => {
    const agent = agentOptionMap.get(step.agentVersionId);
    const hasAgent = Boolean(agent);
    return (
      <tr
        key={`${step.id || 'step'}-${index}`}
        className={index === activeIndex ? 'active' : undefined}
        onClick={() => onSelect(index)}
      >
        <td>{step.id || <em>не задан</em>}</td>
        <td>{step.name || '—'}</td>
        <td className={!hasAgent ? 'warning' : undefined}>
          {agent ? agent.label : 'Агент не найден'}
        </td>
        <td>{step.maxAttempts || '1'}</td>
        <td className="flow-definitions__row-actions">
          {onMove && (
            <>
              <button
                type="button"
                className="ghost"
                onClick={(event) => {
                  event.stopPropagation();
                  onMove(index, -1);
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
                  onMove(index, 1);
                }}
                disabled={index >= (formState?.steps.length ?? 0) - 1}
              >
                ↓
              </button>
            </>
          )}
          {onRemove && (
            <button
              type="button"
              className="ghost"
              onClick={(event) => {
                event.stopPropagation();
                onRemove(index);
              }}
            >
              ×
            </button>
          )}
        </td>
      </tr>
    );
  };

  return (
    <div className="flow-definitions">
      <header className="flow-definitions__header">
        <div>
          <h2>Flow definitions</h2>
          <p className="flow-definitions__subtitle">
            Управляйте blueprint’ами флоу и тестируйте шаги до публикации
          </p>
        </div>
        <div className="flow-definitions__actions">
          {formState && (
            <>
              <button type="button" onClick={handleSave} disabled={isSaving}>
                {isSaving ? 'Сохранение…' : 'Сохранить черновик'}
              </button>
              <button
                type="button"
                className="ghost"
                onClick={handlePublish}
                disabled={isPublishing}
              >
                {isPublishing ? 'Публикация…' : 'Опубликовать'}
              </button>
            </>
          )}
        </div>
      </header>

      {(error || formError) && (
        <div className="flow-definitions__error">{error ?? formError}</div>
      )}

      <div className="flow-definitions__layout">
        <aside className="flow-definitions__sidebar">
          <h3>Опубликованные определения</h3>
          {isLoadingList ? (
            <div className="flow-definitions__placeholder">Загрузка…</div>
          ) : (
            <ul>
              {definitions.map((definition) => (
                <li
                  key={definition.id}
                  className={
                    definition.id === selectedDefinitionId
                      ? 'active flow-definitions__item'
                      : 'flow-definitions__item'
                  }
                >
                  <button type="button" onClick={() => setSelectedDefinitionId(definition.id)}>
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
                Description
                <input
                  value={newDefinitionDescription}
                  onChange={(event) => setNewDefinitionDescription(event.target.value)}
                  placeholder="краткое описание"
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
                Change notes
                <input
                  value={newDefinitionChangeNotes}
                  onChange={(event) => setNewDefinitionChangeNotes(event.target.value)}
                  placeholder="изменения для истории"
                />
              </label>
            </div>

            {renderLaunchParametersSection(newDefinitionForm, updateNewForm)}
            {renderSharedChannelsSection(newDefinitionForm, updateNewForm)}

            <div className="flow-definitions__steps">
              <div className="flow-definitions__steps-header">
                <h4>Шаги нового флоу</h4>
                <button type="button" className="ghost" onClick={() => {
                  updateNewForm((prev) => {
                    const steps = [...prev.steps, createEmptyStepForm()];
                    return {
                      ...prev,
                      steps,
                      startStepId: prev.startStepId || steps[0]?.id || '',
                    };
                  });
                  setNewSelectedStepIndex(newDefinitionForm.steps.length);
                }}>
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
                    {newDefinitionForm.steps.map((step, index) =>
                      renderStepRow(
                        step,
                        index,
                        newSelectedStepIndex,
                        setNewSelectedStepIndex,
                        undefined,
                        (idx) => {
                          updateNewForm((prev) => {
                            const steps = prev.steps.filter((_, sidx) => sidx !== idx);
                            const startStepId =
                              steps.find((s) => s.id === prev.startStepId)?.id ??
                              steps[0]?.id ??
                              '';
                            return { ...prev, steps, startStepId };
                          });
                          setNewSelectedStepIndex((prevIdx) => Math.max(0, prevIdx - 1));
                        },
                      ),
                    )}
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

              {newDefinitionForm.steps[newSelectedStepIndex] &&
                renderStepEditor(newDefinitionForm.steps[newSelectedStepIndex], (updater) =>
                  updateStepAt(updateNewForm, newSelectedStepIndex, updater),
                )}

              <div className="flow-definitions__actions">
                <button
                  type="button"
                  onClick={handleCreateDefinition}
                  disabled={creatingDefinition}
                >
                  {creatingDefinition ? 'Создание…' : 'Создать флоу'}
                </button>
              </div>
            </div>
          </div>

          {formState && (
            <div className="flow-definitions__editor">
              <h3>Редактирование флоу</h3>
              <div className="field-grid">
                <label>
                  Название
                  <input
                    value={formState.title}
                    onChange={(event) =>
                      updateExistingForm((prev) => ({ ...prev, title: event.target.value }))
                    }
                  />
                </label>
                <label>
                  Описание
                  <input
                    value={formState.description}
                    onChange={(event) =>
                      updateExistingForm((prev) => ({
                        ...prev,
                        description: event.target.value,
                      }))
                    }
                  />
                </label>
                <label>
                  Теги (через запятую)
                  <input
                    value={formState.tags}
                    onChange={(event) =>
                      updateExistingForm((prev) => ({ ...prev, tags: event.target.value }))
                    }
                  />
                </label>
                <label>
                  Стартовый шаг
                  <select
                    value={formState.startStepId}
                    onChange={(event) =>
                      updateExistingForm((prev) => ({
                        ...prev,
                        startStepId: event.target.value,
                      }))
                    }
                  >
                    {formState.steps.map((step) => (
                      <option key={step.id || step.agentVersionId} value={step.id}>
                        {step.id || step.agentVersionId || 'step'}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              {renderLaunchParametersSection(formState, updateExistingForm)}
              {renderSharedChannelsSection(formState, updateExistingForm)}

              <div className="flow-definitions__steps">
                <div className="flow-definitions__steps-header">
                  <h4>Шаги флоу</h4>
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
                      {formState.steps.map((step, index) =>
                        renderStepRow(
                          step,
                          index,
                          selectedStepIndex,
                          setSelectedStepIndex,
                          moveStep,
                          removeStep,
                        ),
                      )}
                      {!formState.steps.length && (
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

                {formState.steps[selectedStepIndex] &&
                  renderStepEditor(
                    formState.steps[selectedStepIndex],
                    (updater) => updateStepAt(updateExistingForm, selectedStepIndex, updater),
                  )}
              </div>

              <div className="flow-definitions__history">
                <h4>История версий</h4>
                {history.length === 0 ? (
                  <div className="flow-definitions__placeholder">История пуста</div>
                ) : (
                  <ul>
                    {history.map((entry) => (
                      <li key={entry.id}>
                        v{entry.version} • {entry.status} • {entry.createdAt ?? '—'}{' '}
                        {entry.blueprintSchemaVersion ? `schema=${entry.blueprintSchemaVersion}` : ''}
                        {entry.changeNotes ? ` — ${entry.changeNotes}` : ''}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  );
};

export default FlowDefinitions;
