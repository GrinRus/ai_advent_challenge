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
  raw: Record<string, unknown>;
};

const cloneObject = (value: unknown): Record<string, unknown> =>
  JSON.parse(JSON.stringify(value ?? {}));

const toPrettyJson = (value: unknown): string => {
  if (value == null) {
    return '';
  }
  return JSON.stringify(value, null, 2);
};

export const createEmptyFlowDefinitionForm = (): FlowDefinitionFormState => ({
  title: '',
  startStepId: '',
  syncOnly: true,
  steps: [],
  raw: {},
});

export function parseFlowDefinition(definition: unknown): FlowDefinitionFormState {
  if (definition == null || typeof definition !== 'object' || Array.isArray(definition)) {
    return createEmptyFlowDefinitionForm();
  }

  const typed = definition as Record<string, unknown>;
  const stepsSource = Array.isArray(typed.steps) ? (typed.steps as Record<string, unknown>[]) : [];

  const steps: FlowStepForm[] = stepsSource.map((step) => {
    const overrides = (step.overrides as Record<string, unknown>) ?? {};
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
    title: typeof typed.title === 'string' ? typed.title : '',
    startStepId: typeof typed.startStepId === 'string' ? typed.startStepId : '',
    syncOnly: typed.syncOnly !== false,
    steps,
    raw: cloneObject(typed),
  };
}

const parseJsonText = (label: string, value: string): unknown => {
  if (!value.trim()) {
    return undefined;
  }
  try {
    return JSON.parse(value);
  } catch (error) {
    throw new Error(`Поле "${label}" содержит некорректный JSON: ${(error as Error).message}`);
  }
};

export function buildFlowDefinition(form: FlowDefinitionFormState): Record<string, unknown> {
  if (!form.steps.length) {
    throw new Error('Добавьте хотя бы один шаг перед сохранением');
  }

  const result = cloneObject(form.raw);
  result.title = form.title;
  result.startStepId = form.startStepId || form.steps[0]?.id || '';
  result.syncOnly = form.syncOnly !== false;

  result.steps = form.steps.map((step) => {
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

    const memoryReads = parseJsonText('Memory Reads', step.memoryReadsText);
    const memoryWrites = parseJsonText('Memory Writes', step.memoryWritesText);
    const transitions = parseJsonText('Transitions', step.transitionsText);

    return {
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
  });

  return result;
}
