import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  createFlowDefinition,
  fetchFlowDefinition,
  fetchFlowDefinitionHistory,
  fetchFlowDefinitions,
  publishFlowDefinition,
  updateFlowDefinition,
  type FlowDefinitionDetails,
  type FlowDefinitionHistoryEntry,
  type FlowDefinitionSummary,
} from '../lib/apiClient';
import './FlowDefinitions.css';

const formatDateTime = (value?: string | null) =>
  value ? new Date(value).toLocaleString() : '—';

const FlowDefinitions = () => {
  const [definitions, setDefinitions] = useState<FlowDefinitionSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [details, setDetails] = useState<FlowDefinitionDetails | null>(null);
  const [history, setHistory] = useState<FlowDefinitionHistoryEntry[]>([]);
  const [editedDescription, setEditedDescription] = useState('');
  const [definitionJson, setDefinitionJson] = useState('');
  const [updatedBy, setUpdatedBy] = useState('');
  const [changeNotes, setChangeNotes] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [isPublishing, setIsPublishing] = useState(false);
  const [newDefinitionName, setNewDefinitionName] = useState('');
  const [newDefinitionDescription, setNewDefinitionDescription] = useState('');
  const [newDefinitionUpdatedBy, setNewDefinitionUpdatedBy] = useState('');
  const [newDefinitionJson, setNewDefinitionJson] = useState(() =>
    `{
  "title": "Example flow",
  "startStepId": "step-1",
  "steps": [
    {
      "id": "step-1",
      "name": "Example step",
      "agentVersionId": "00000000-0000-0000-0000-000000000000",
      "prompt": "Describe the task",
      "memoryReads": [],
      "memoryWrites": [],
      "transitions": {
        "onSuccess": { "complete": true },
        "onFailure": { "fail": true }
      }
    }
  ]
}`,
  );

  const loadDefinitions = useCallback(async () => {
    try {
      setError(null);
      const data = await fetchFlowDefinitions();
      setDefinitions(data);
      if (data.length && !selectedId) {
        setSelectedId(data[0].id);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }, [selectedId]);

  const loadDetails = useCallback(async (definitionId: string) => {
    try {
      setError(null);
      const [def, historyList] = await Promise.all([
        fetchFlowDefinition(definitionId),
        fetchFlowDefinitionHistory(definitionId),
      ]);
      setDetails(def);
      setHistory(historyList);
      setEditedDescription(def.description ?? '');
      setDefinitionJson(JSON.stringify(def.definition, null, 2));
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    loadDefinitions();
  }, [loadDefinitions]);

  useEffect(() => {
    if (selectedId) {
      loadDetails(selectedId);
    } else {
      setDetails(null);
      setHistory([]);
    }
  }, [loadDetails, selectedId]);

  const parseJson = useCallback((value: string): unknown => {
    const trimmed = value.trim();
    if (!trimmed) {
      throw new Error('JSON не может быть пустым');
    }
    return JSON.parse(trimmed);
  }, []);

  const handleSaveDraft = useCallback(async () => {
    if (!details) {
      return;
    }
    try {
      setIsSaving(true);
      setError(null);
      const definition = parseJson(definitionJson);
      const updated = await updateFlowDefinition(details.id, {
        description: editedDescription,
        updatedBy,
        changeNotes,
        definition,
      });
      setDetails(updated);
      await loadDefinitions();
      await loadDetails(updated.id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setIsSaving(false);
    }
  }, [changeNotes, definitionJson, details, editedDescription, loadDefinitions, loadDetails, parseJson, updatedBy]);

  const handlePublish = useCallback(async () => {
    if (!details) {
      return;
    }
    try {
      setIsPublishing(true);
      setError(null);
      const updated = await publishFlowDefinition(details.id, {
        updatedBy,
        changeNotes,
      });
      setDetails(updated);
      await loadDefinitions();
      await loadDetails(updated.id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setIsPublishing(false);
    }
  }, [changeNotes, details, loadDefinitions, loadDetails, updatedBy]);

  const handleCreate = useCallback(async () => {
    if (!newDefinitionName.trim()) {
      setError('Название определения обязательно');
      return;
    }
    try {
      setIsSaving(true);
      setError(null);
      const definition = parseJson(newDefinitionJson);
      const created = await createFlowDefinition({
        name: newDefinitionName.trim(),
        description: newDefinitionDescription,
        updatedBy: newDefinitionUpdatedBy,
        definition,
      });
      setNewDefinitionName('');
      setNewDefinitionDescription('');
      setNewDefinitionUpdatedBy('');
      await loadDefinitions();
      setSelectedId(created.id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setIsSaving(false);
    }
  }, [loadDefinitions, newDefinitionDescription, newDefinitionJson, newDefinitionName, newDefinitionUpdatedBy, parseJson]);

  const isDraft = details?.status === 'DRAFT';

  const historyWithIndex = useMemo(() => history.map((entry, index) => ({ ...entry, index })), [history]);

  return (
    <div className="flow-definitions">
      <h2>Flow Definitions</h2>
      {error && <div className="flow-definitions__error">{error}</div>}

      <div className="flow-definitions__layout">
        <aside className="flow-definitions__sidebar">
          <h3>Опубликованные версии</h3>
          <ul>
            {definitions.map((definition) => (
              <li
                key={definition.id}
                className={definition.id === selectedId ? 'active' : ''}
                onClick={() => setSelectedId(definition.id)}
              >
                <div className="definition-name">{definition.name}</div>
                <div className="definition-meta">
                  Версия {definition.version} · {definition.status}
                </div>
              </li>
            ))}
          </ul>
        </aside>

        <section className="flow-definitions__content">
          <div className="flow-definitions__create">
            <h3>Создать новое определение</h3>
            <div className="field-grid">
              <label>
                Название
                <input
                  type="text"
                  value={newDefinitionName}
                  onChange={(e) => setNewDefinitionName(e.target.value)}
                  placeholder="Например, customer-onboarding"
                />
              </label>
              <label>
                Описание
                <input
                  type="text"
                  value={newDefinitionDescription}
                  onChange={(e) => setNewDefinitionDescription(e.target.value)}
                  placeholder="Краткое описание"
                />
              </label>
              <label>
                Updated by
                <input
                  type="text"
                  value={newDefinitionUpdatedBy}
                  onChange={(e) => setNewDefinitionUpdatedBy(e.target.value)}
                  placeholder="Имя пользователя"
                />
              </label>
            </div>
            <label className="full-width">
              JSON определение
              <textarea
                value={newDefinitionJson}
                onChange={(e) => setNewDefinitionJson(e.target.value)}
              />
            </label>
            <button type="button" onClick={handleCreate} disabled={isSaving}>
              {isSaving ? 'Сохранение...' : 'Создать черновик'}
            </button>
          </div>

          {details && (
            <div className="flow-definitions__editor">
              <div className="editor-header">
                <h3>
                  {details.name} · версия {details.version}
                </h3>
                <div className={`status-badge status-${details.status.toLowerCase()}`}>
                  {details.status}
                </div>
              </div>
              <div className="field-grid">
                <label>
                  Описание
                  <input
                    type="text"
                    value={editedDescription}
                    onChange={(e) => setEditedDescription(e.target.value)}
                    disabled={!isDraft}
                  />
                </label>
                <label>
                  Updated by
                  <input
                    type="text"
                    value={updatedBy}
                    onChange={(e) => setUpdatedBy(e.target.value)}
                  />
                </label>
                <label>
                  Change notes
                  <input
                    type="text"
                    value={changeNotes}
                    onChange={(e) => setChangeNotes(e.target.value)}
                  />
                </label>
              </div>
              <label className="full-width">
                JSON определение
                <textarea
                  value={definitionJson}
                  onChange={(e) => setDefinitionJson(e.target.value)}
                  disabled={!isDraft}
                />
              </label>
              <div className="editor-actions">
                <button
                  type="button"
                  onClick={handleSaveDraft}
                  disabled={!isDraft || isSaving}
                >
                  {isSaving ? 'Сохранение...' : 'Сохранить черновик'}
                </button>
                <button
                  type="button"
                  className="primary"
                  onClick={handlePublish}
                  disabled={isPublishing}
                >
                  {isPublishing ? 'Публикация...' : 'Опубликовать'}
                </button>
              </div>

              <div className="definition-timestamps">
                <span>Created: {formatDateTime(details.createdAt)}</span>
                <span>Updated: {formatDateTime(details.updatedAt)}</span>
                <span>Published: {formatDateTime(details.publishedAt)}</span>
              </div>

              <div className="history-panel">
                <h4>История версий</h4>
                {historyWithIndex.length === 0 ? (
                  <p className="history-empty">История пока пустая</p>
                ) : (
                  <ul>
                    {historyWithIndex.map((entry) => (
                      <li key={entry.id}>
                        <div className="history-header">
                          <strong>Версия {entry.version}</strong>
                          <span>{formatDateTime(entry.createdAt)}</span>
                        </div>
                        <div className="history-meta">
                          <span>Статус: {entry.status}</span>
                          {entry.changeNotes && <span>Notes: {entry.changeNotes}</span>}
                          {entry.createdBy && <span>By: {entry.createdBy}</span>}
                        </div>
                        <details>
                          <summary>Посмотреть JSON</summary>
                          <pre>{JSON.stringify(entry.definition, null, 2)}</pre>
                        </details>
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
