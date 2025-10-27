import {
  FlowDefinitionDraft,
  FlowDefinitionDraftSchema,
  FlowDefinitionSchema,
  parseMemoryReads,
  parseMemoryWrites,
  parseTransitions,
} from './types/flowDefinition';

export type FlowStepForm = {
  id: string;
  name: string;
  agentVersionId: string;
  prompt: string;
  temperature?: number | null;
  topP?: number | null;
  maxTokensOverride?: number | null;
  memoryReadsText: string;
  memoryWritesText: string;
  transitionsText: string;
  maxAttempts: number;
};

export type FlowDefinitionFormState = {
  title: string;
  startStepId: string;
  syncOnly: boolean;
  steps: FlowStepForm[];
  draft: FlowDefinitionDraft;
};

const cloneDraft = <T>(value: T): T => JSON.parse(JSON.stringify(value ?? {}));

const toPrettyJson = (value: unknown): string => {
  if (value == null) {
    return '';
  }
  return JSON.stringify(value, null, 2);
};

const emptyDraft: FlowDefinitionDraft = FlowDefinitionDraftSchema.parse({
  title: '',
  startStepId: '',
  syncOnly: true,
  steps: [],
});

export const createEmptyFlowDefinitionForm = (): FlowDefinitionFormState => ({
  title: '',
  startStepId: '',
  syncOnly: true,
  steps: [],
  draft: cloneDraft(emptyDraft),
});

export function parseFlowDefinition(definition: unknown): FlowDefinitionFormState {
  let draft: FlowDefinitionDraft;
  const parsed = FlowDefinitionDraftSchema.safeParse(definition);
  if (parsed.success) {
    draft = cloneDraft(parsed.data);
  } else {
    draft = cloneDraft(emptyDraft);
  }

  const steps: FlowStepForm[] = (draft.steps ?? []).map((step) => {
    const overrides = step.overrides ?? {};
    return {
      id: typeof step.id === 'string' ? step.id : '',
      name: typeof step.name === 'string' ? step.name : '',
      agentVersionId: typeof step.agentVersionId === 'string' ? step.agentVersionId : '',
      prompt: typeof step.prompt === 'string' ? step.prompt : '',
      temperature:
        typeof overrides.temperature === 'number' ? overrides.temperature : undefined,
      topP: typeof overrides.topP === 'number' ? overrides.topP : undefined,
      maxTokensOverride:
        typeof overrides.maxTokens === 'number' ? overrides.maxTokens : undefined,
      memoryReadsText: toPrettyJson(step.memoryReads),
      memoryWritesText: toPrettyJson(step.memoryWrites),
      transitionsText: toPrettyJson(step.transitions),
      maxAttempts:
        typeof step.maxAttempts === 'number' && Number.isFinite(step.maxAttempts)
          ? Math.max(1, Math.trunc(step.maxAttempts))
          : 1,
    };
  });

  return {
    title: typeof draft.title === 'string' ? draft.title : '',
    startStepId: typeof draft.startStepId === 'string' ? draft.startStepId : '',
    syncOnly: draft.syncOnly !== false,
    steps,
    draft,
  };
}

export function buildFlowDefinition(form: FlowDefinitionFormState): Record<string, unknown> {
  if (!form.steps.length) {
    throw new Error('Добавьте хотя бы один шаг перед сохранением');
  }

  const draft = cloneDraft(form.draft);
  draft.title = form.title;
  draft.startStepId = form.startStepId || form.steps[0]?.id || '';
  draft.syncOnly = form.syncOnly !== false;

  draft.steps = form.steps.map((step) => {
    if (!step.id.trim()) {
      throw new Error('У каждого шага должен быть заполнен идентификатор');
    }
    if (!step.agentVersionId.trim()) {
      throw new Error(`Для шага "${step.id}" необходимо выбрать опубликованного агента`);
    }

    const overrides: Record<string, number> = {};
    if (typeof step.temperature === 'number') {
      overrides.temperature = step.temperature;
    }
    if (typeof step.topP === 'number') {
      overrides.topP = step.topP;
    }
    if (typeof step.maxTokensOverride === 'number') {
      overrides.maxTokens = step.maxTokensOverride;
    }

    const memoryReads = parseMemoryReads('Memory Reads', step.memoryReadsText);
    const memoryWrites = parseMemoryWrites('Memory Writes', step.memoryWritesText);
    const transitions = parseTransitions('Transitions', step.transitionsText);

    const definition = {
      id: step.id.trim(),
      name: step.name.trim(),
      agentVersionId: step.agentVersionId.trim(),
      prompt: step.prompt,
      overrides: Object.keys(overrides).length ? overrides : undefined,
      memoryReads: memoryReads ?? [],
      memoryWrites: memoryWrites ?? [],
      transitions: transitions ?? {},
      maxAttempts: Math.max(1, Math.trunc(step.maxAttempts)),
    };
    return definition;
  });

  return FlowDefinitionSchema.parse(draft);
}
