import {
  FLOW_BLUEPRINT_SCHEMA_VERSION,
  FlowDefinitionDraftSchema,
  FlowDefinitionSchema,
  MemoryWriteModeSchema,
} from './types/flowDefinition';
import { FlowInteractionTypeSchema } from './types/flowInteraction';
import { JsonValueSchema } from './types/json';
import type {
  FlowDefinitionDraft,
  FlowDefinitionDocument,
  FlowInteractionConfig,
  FlowStepDefinition,
  FlowStepOverrides,
  MemoryWriteConfig,
  MemoryWriteMode,
} from './types/flowDefinition';
import type { JsonValue } from './types/json';

export type FlowLaunchParameterForm = {
  name: string;
  label: string;
  type: string;
  required: boolean;
  description: string;
  schemaText: string;
  defaultValueText: string;
};

export type FlowSharedChannelForm = {
  id: string;
  retentionVersions: string;
  retentionDays: string;
};

export type FlowMemoryReadForm = {
  channel: string;
  limit: string;
};

export type FlowMemoryWriteForm = {
  channel: string;
  mode: MemoryWriteMode;
  payloadText: string;
};

export type FlowStepOverrideForm = {
  temperature: string;
  topP: string;
  maxTokens: string;
};

export type FlowStepInteractionForm = {
  type: string;
  title: string;
  description: string;
  payloadSchemaText: string;
  suggestedActionsText: string;
  dueInMinutes: string;
};

export type FlowStepTransitionsForm = {
  onSuccessNext: string;
  onSuccessComplete: boolean;
  onFailureNext: string;
  onFailureFail: boolean;
};

export type FlowStepForm = {
  id: string;
  name: string;
  agentVersionId: string;
  prompt: string;
  overrides: FlowStepOverrideForm;
  interaction: FlowStepInteractionForm;
  memoryReads: FlowMemoryReadForm[];
  memoryWrites: FlowMemoryWriteForm[];
  transitions: FlowStepTransitionsForm;
  maxAttempts: string;
};

export type FlowDefinitionFormState = {
  title: string;
  description: string;
  tags: string;
  startStepId: string;
  syncOnly: boolean;
  launchParameters: FlowLaunchParameterForm[];
  sharedChannels: FlowSharedChannelForm[];
  steps: FlowStepForm[];
  draft: FlowDefinitionDraft;
};

const cloneDraft = <T>(value: T): T => JSON.parse(JSON.stringify(value ?? {}));

const emptyDraft: FlowDefinitionDraft = FlowDefinitionDraftSchema.parse({
  schemaVersion: FLOW_BLUEPRINT_SCHEMA_VERSION,
  title: '',
  startStepId: '',
  syncOnly: true,
  steps: [],
  metadata: {},
  launchParameters: [],
  memory: { sharedChannels: [] },
});

export const createEmptyLaunchParameterForm = (): FlowLaunchParameterForm => ({
  name: '',
  label: '',
  type: 'string',
  required: true,
  description: '',
  schemaText: '',
  defaultValueText: '',
});

export const createEmptySharedChannelForm = (): FlowSharedChannelForm => ({
  id: '',
  retentionVersions: '',
  retentionDays: '',
});

export const createEmptyStepForm = (): FlowStepForm => ({
  id: '',
  name: '',
  agentVersionId: '',
  prompt: '',
  overrides: {
    temperature: '',
    topP: '',
    maxTokens: '',
  },
  interaction: {
    type: '',
    title: '',
    description: '',
    payloadSchemaText: '',
    suggestedActionsText: '',
    dueInMinutes: '',
  },
  memoryReads: [],
  memoryWrites: [],
  transitions: {
    onSuccessNext: '',
    onSuccessComplete: false,
    onFailureNext: '',
    onFailureFail: false,
  },
  maxAttempts: '1',
});

export const createEmptyFlowDefinitionForm = (): FlowDefinitionFormState => ({
  title: '',
  description: '',
  tags: '',
  startStepId: '',
  syncOnly: true,
  launchParameters: [],
  sharedChannels: [],
  steps: [],
  draft: cloneDraft(emptyDraft),
});

export function parseFlowDefinition(definition: unknown): FlowDefinitionFormState {
  const parsedDraft = FlowDefinitionDraftSchema.safeParse(definition);
  const draft: FlowDefinitionDraft = parsedDraft.success
    ? cloneDraft(parsedDraft.data)
    : cloneDraft(emptyDraft);

  if (
    typeof draft.schemaVersion !== 'number' ||
    !Number.isInteger(draft.schemaVersion) ||
    draft.schemaVersion <= 0
  ) {
    (draft as any).schemaVersion = FLOW_BLUEPRINT_SCHEMA_VERSION;
  }

  const metadata = (draft as any).metadata ?? {};
  const launchParameters: FlowLaunchParameterForm[] =
    Array.isArray((draft as any).launchParameters) &&
    (draft as any).launchParameters.every((param: unknown) => typeof param === 'object')
      ? (draft as any).launchParameters.map(
          (param: any): FlowLaunchParameterForm => ({
            name: param?.name ?? '',
            label: param?.label ?? '',
            type: param?.type ?? 'string',
            required: Boolean(param?.required),
            description: param?.description ?? '',
            schemaText:
              param?.schema !== undefined ? JSON.stringify(param.schema, null, 2) : '',
            defaultValueText:
              param?.defaultValue !== undefined
                ? JSON.stringify(param.defaultValue, null, 2)
                : '',
          }),
        )
      : [];

  const sharedChannels: FlowSharedChannelForm[] =
    Array.isArray((draft as any)?.memory?.sharedChannels)
      ? (draft as any).memory.sharedChannels.map(
          (channel: any): FlowSharedChannelForm => ({
            id: channel?.id ?? '',
            retentionVersions:
              channel?.retentionVersions !== undefined
                ? String(channel.retentionVersions)
                : '',
            retentionDays:
              channel?.retentionDays !== undefined
                ? String(channel.retentionDays)
                : '',
          }),
        )
      : [];

  const steps: FlowStepForm[] = (draft.steps ?? []).map((step) => ({
    id: typeof step.id === 'string' ? step.id : '',
    name: typeof step.name === 'string' ? step.name : '',
    agentVersionId: typeof step.agentVersionId === 'string' ? step.agentVersionId : '',
    prompt: typeof step.prompt === 'string' ? step.prompt : '',
    overrides: {
      temperature:
        step.overrides && typeof step.overrides.temperature === 'number'
          ? String(step.overrides.temperature)
          : '',
      topP:
        step.overrides && typeof step.overrides.topP === 'number'
          ? String(step.overrides.topP)
          : '',
      maxTokens:
        step.overrides && typeof step.overrides.maxTokens === 'number'
          ? String(step.overrides.maxTokens)
          : '',
    },
    interaction: {
      type: step.interaction?.type ?? '',
      title: step.interaction?.title ?? '',
      description: step.interaction?.description ?? '',
      payloadSchemaText:
        step.interaction?.payloadSchema !== undefined
          ? JSON.stringify(step.interaction.payloadSchema, null, 2)
          : '',
      suggestedActionsText:
        step.interaction?.suggestedActions !== undefined
          ? JSON.stringify(step.interaction.suggestedActions, null, 2)
          : '',
      dueInMinutes:
        step.interaction?.dueInMinutes !== undefined
          ? String(step.interaction.dueInMinutes)
          : '',
    },
    memoryReads: Array.isArray(step.memoryReads)
      ? step.memoryReads.map(
          (read): FlowMemoryReadForm => ({
            channel: read?.channel ?? '',
            limit: read?.limit !== undefined ? String(read.limit) : '',
          }),
        )
      : [],
    memoryWrites: Array.isArray(step.memoryWrites)
      ? step.memoryWrites.map(
          (write): FlowMemoryWriteForm => ({
            channel: write?.channel ?? '',
            mode: (write?.mode as MemoryWriteMode | undefined) ?? 'AGENT_OUTPUT',
            payloadText:
              write?.payload !== undefined ? JSON.stringify(write.payload, null, 2) : '',
          }),
        )
      : [],
    transitions: {
      onSuccessNext: step.transitions?.onSuccess?.next ?? '',
      onSuccessComplete: Boolean(step.transitions?.onSuccess?.complete),
      onFailureNext: step.transitions?.onFailure?.next ?? '',
      onFailureFail: Boolean(step.transitions?.onFailure?.fail),
    },
    maxAttempts:
      step.maxAttempts !== undefined ? String(step.maxAttempts) : '1',
  }));

  const tagsArray =
    Array.isArray(metadata.tags) && metadata.tags.length
      ? metadata.tags
      : Array.isArray(metadata.tags)
        ? []
        : [];
  const tags = tagsArray.length ? tagsArray.join(', ') : '';

  return {
    title: metadata.title ?? draft.title ?? '',
    description: metadata.description ?? '',
    tags,
    startStepId:
      typeof draft.startStepId === 'string' && draft.startStepId
        ? draft.startStepId
        : steps[0]?.id ?? '',
    syncOnly: draft.syncOnly !== false,
    launchParameters,
    sharedChannels,
    steps,
    draft,
  };
}

export function buildFlowDefinition(form: FlowDefinitionFormState): FlowDefinitionDocument {
  if (!form.steps.length) {
    throw new Error('Добавьте хотя бы один шаг перед сохранением');
  }

  const draft = cloneDraft(form.draft);
  (draft as any).schemaVersion = FLOW_BLUEPRINT_SCHEMA_VERSION;
  draft.title = form.title;
  draft.startStepId = form.startStepId || form.steps[0]?.id || '';
  draft.syncOnly = form.syncOnly !== false;

  const metadata = {
    ...(draft as any).metadata,
    title: form.title || undefined,
    description: form.description || undefined,
    tags:
      form.tags
        .split(',')
        .map((tag) => tag.trim())
        .filter(Boolean) || undefined,
  };
  (draft as any).metadata = metadata;

  const normalizeNumber = (label: string, value: string, opts?: { integer?: boolean }) => {
    const trimmed = value.trim();
    if (!trimmed) {
      return undefined;
    }
    const numberValue = Number(trimmed);
    if (!Number.isFinite(numberValue)) {
      throw new Error(`${label}: некорректное число`);
    }
    if (opts?.integer && !Number.isInteger(numberValue)) {
      throw new Error(`${label}: значение должно быть целым`);
    }
    return numberValue;
  };

  const parseJsonField = (label: string, value: string): JsonValue | undefined => {
    const trimmed = value.trim();
    if (!trimmed) {
      return undefined;
    }
    try {
      const parsed = JSON.parse(trimmed);
      const validation = JsonValueSchema.safeParse(parsed);
      if (!validation.success) {
        const issueMessage =
          validation.error.issues.map((issue) => issue.message).join('; ') ||
          'значение должно быть корректным JSON';
        throw new Error(issueMessage);
      }
      return validation.data;
    } catch (error) {
      const message = error instanceof Error ? error.message : 'некорректный JSON';
      throw new Error(`${label}: ${message}`);
    }
  };

  (draft as any).launchParameters = form.launchParameters.map((param, index) => ({
    name: param.name.trim(),
    label: param.label.trim() || undefined,
    type: param.type.trim() || 'string',
    required: Boolean(param.required),
    description: param.description.trim() || undefined,
    schema: parseJsonField(`Launch parameter #${index + 1} schema`, param.schemaText),
    defaultValue: parseJsonField(
      `Launch parameter #${index + 1} default value`,
      param.defaultValueText,
    ),
  }));

  (draft as any).memory = {
    sharedChannels: form.sharedChannels.map((channel, index) => ({
      id: channel.id.trim(),
      retentionVersions: normalizeNumber(
        `Shared channel #${index + 1} retentionVersions`,
        channel.retentionVersions,
        { integer: true },
      ),
      retentionDays: normalizeNumber(
        `Shared channel #${index + 1} retentionDays`,
        channel.retentionDays,
        { integer: true },
      ),
    })),
  };

  draft.steps = form.steps.map<FlowStepDefinition>((step, index) => {
    if (!step.id.trim()) {
      throw new Error(`Шаг #${index + 1}: укажите идентификатор`);
    }
    if (!step.agentVersionId.trim()) {
      throw new Error(`Шаг "${step.id}": необходимо выбрать опубликованного агента`);
    }

    const overrides: FlowStepOverrides = {};
    const temperature = normalizeNumber(
      `Шаг "${step.id}" temperature`,
      step.overrides.temperature,
    );
    if (temperature !== undefined) {
      overrides.temperature = temperature;
    }

    const topP = normalizeNumber(`Шаг "${step.id}" topP`, step.overrides.topP);
    if (topP !== undefined) {
      overrides.topP = topP;
    }

    const maxTokens = normalizeNumber(
      `Шаг "${step.id}" maxTokens`,
      step.overrides.maxTokens,
      { integer: true },
    );
    if (maxTokens !== undefined) {
      overrides.maxTokens = maxTokens;
    }

    const memoryReads = step.memoryReads
      .map((read, readIndex) => {
        if (!read.channel.trim() && !read.limit.trim()) {
          return undefined;
        }
        if (!read.channel.trim()) {
          throw new Error(`Шаг "${step.id}" memory read #${readIndex + 1}: укажите канал`);
        }
        const limit = normalizeNumber(
          `Шаг "${step.id}" memory read #${readIndex + 1} limit`,
          read.limit,
          { integer: true },
        );
        if (limit === undefined) {
          throw new Error(
            `Шаг "${step.id}" memory read #${readIndex + 1}: укажите целое положительное число`,
          );
        }
        return {
          channel: read.channel.trim(),
          limit,
        };
      })
      .filter(Boolean) as Array<{ channel: string; limit: number }>;

    const memoryWrites = step.memoryWrites
      .map<MemoryWriteConfig | undefined>((write, writeIndex) => {
        const channel = write.channel.trim();
        const payloadText = write.payloadText.trim();
        if (!channel && !payloadText) {
          return undefined;
        }
        if (!channel) {
          throw new Error(`Шаг "${step.id}" memory write #${writeIndex + 1}: укажите канал`);
        }
        const mode = MemoryWriteModeSchema.parse(write.mode ?? 'AGENT_OUTPUT');
        const payload = parseJsonField(
          `Шаг "${step.id}" memory write #${writeIndex + 1} payload`,
          write.payloadText,
        );
        return payload !== undefined
          ? {
              channel,
              mode,
              payload,
            }
          : {
              channel,
              mode,
            };
      })
      .filter((write): write is MemoryWriteConfig => Boolean(write));

    const interactionTypeInput = step.interaction.type.trim();
    let interactionType: FlowInteractionConfig['type'] | undefined;
    if (interactionTypeInput) {
      const parsedType = FlowInteractionTypeSchema.safeParse(interactionTypeInput);
      if (!parsedType.success) {
        const allowed = FlowInteractionTypeSchema.options.join(', ');
        throw new Error(
          `Шаг "${step.id}" interaction type: допускаются значения ${allowed}`,
        );
      }
      interactionType = parsedType.data;
    }

    const interactionTitle = step.interaction.title.trim();
    const interactionDescription = step.interaction.description.trim();
    const interactionPayloadSchema = parseJsonField(
      `Шаг "${step.id}" interaction payloadSchema`,
      step.interaction.payloadSchemaText,
    );
    const interactionSuggestedActions = parseJsonField(
      `Шаг "${step.id}" interaction suggestedActions`,
      step.interaction.suggestedActionsText,
    );
    const interactionDueInMinutes = normalizeNumber(
      `Шаг "${step.id}" interaction dueInMinutes`,
      step.interaction.dueInMinutes,
      { integer: true },
    );

    const hasInteraction =
      Boolean(interactionTypeInput) ||
      Boolean(interactionTitle) ||
      Boolean(interactionDescription) ||
      Boolean(step.interaction.payloadSchemaText.trim()) ||
      Boolean(step.interaction.suggestedActionsText.trim()) ||
      Boolean(step.interaction.dueInMinutes.trim());

    const interaction: FlowInteractionConfig | undefined = hasInteraction
      ? {
          type: interactionType,
          title: interactionTitle || undefined,
          description: interactionDescription || undefined,
          payloadSchema: interactionPayloadSchema,
          suggestedActions: interactionSuggestedActions,
          dueInMinutes: interactionDueInMinutes,
        }
      : undefined;

    const transitions = {
      onSuccess:
        step.transitions.onSuccessNext || step.transitions.onSuccessComplete
          ? {
              next: step.transitions.onSuccessNext || undefined,
              complete: step.transitions.onSuccessComplete || undefined,
            }
          : undefined,
      onFailure:
        step.transitions.onFailureNext || step.transitions.onFailureFail
          ? {
              next: step.transitions.onFailureNext || undefined,
              fail: step.transitions.onFailureFail || undefined,
            }
          : undefined,
    };

    const maxAttempts =
      normalizeNumber(`Шаг "${step.id}" maxAttempts`, step.maxAttempts, {
        integer: true,
      }) ?? 1;

    const definition: FlowStepDefinition = {
      id: step.id.trim(),
      name: step.name.trim(),
      agentVersionId: step.agentVersionId.trim(),
      prompt: step.prompt,
      overrides: Object.keys(overrides).length ? overrides : undefined,
      interaction,
      memoryReads,
      memoryWrites,
      transitions,
      maxAttempts,
    };

    return definition;
  });

  return FlowDefinitionSchema.parse(draft);
}
