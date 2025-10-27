import { z } from 'zod';
import { JsonValueSchema } from './json';
import { FlowInteractionTypeSchema } from './flowInteraction';

export const MemoryReadSchema = z.object({
  channel: z.string().min(1, 'Укажите канал памяти'),
  limit: z.number().int().positive().default(10),
});
export type MemoryReadConfig = z.infer<typeof MemoryReadSchema>;

export const MemoryWriteModeSchema = z.enum(['AGENT_OUTPUT', 'USER_INPUT', 'STATIC']);
export type MemoryWriteMode = z.infer<typeof MemoryWriteModeSchema>;

export const MemoryWriteSchema = z.object({
  channel: z.string().min(1, 'Укажите канал памяти'),
  mode: MemoryWriteModeSchema.optional(),
  payload: JsonValueSchema.optional(),
});
export type MemoryWriteConfig = z.infer<typeof MemoryWriteSchema>;

export const FlowDefinitionTransitionsSchema = z.object({
  onSuccess: z
    .object({
      next: z.string().optional(),
      complete: z.boolean().optional(),
    })
    .optional(),
  onFailure: z
    .object({
      next: z.string().optional(),
      fail: z.boolean().optional(),
    })
    .optional(),
});
export type FlowStepTransitionsDraft = z.infer<typeof FlowDefinitionTransitionsSchema>;

export const ChatRequestOverridesSchema = z
  .object({
    temperature: z.number().gte(0).lte(2).nullable().optional(),
    topP: z.number().gt(0).lte(1).nullable().optional(),
    maxTokens: z.number().int().positive().nullable().optional(),
  })
  .partial();
export type FlowStepOverrides = z.infer<typeof ChatRequestOverridesSchema>;

export const FlowInteractionConfigSchema = z.object({
  type: FlowInteractionTypeSchema.optional(),
  title: z.string().nullable().optional(),
  description: z.string().nullable().optional(),
  payloadSchema: JsonValueSchema.nullish(),
  suggestedActions: JsonValueSchema.nullish(),
  dueInMinutes: z.number().int().positive().nullable().optional(),
});
export type FlowInteractionConfig = z.infer<typeof FlowInteractionConfigSchema>;

export const FlowStepDefinitionSchema = z
  .object({
    id: z.string().min(1, 'Укажите идентификатор шага'),
    name: z.string().optional().default(''),
    agentVersionId: z.string().uuid(),
    prompt: z.string().optional().default(''),
    overrides: ChatRequestOverridesSchema.optional(),
    interaction: FlowInteractionConfigSchema.nullish(),
    memoryReads: z.array(MemoryReadSchema).default([]),
    memoryWrites: z.array(MemoryWriteSchema).default([]),
    transitions: FlowDefinitionTransitionsSchema.optional(),
    maxAttempts: z.number().int().min(1).default(1),
  })
  .passthrough();
export type FlowStepDefinition = z.infer<typeof FlowStepDefinitionSchema>;

const FlowDefinitionBaseSchema = z
  .object({
    title: z.string().optional().default(''),
    startStepId: z.string().optional(),
    syncOnly: z.boolean().optional().default(true),
    steps: z.array(FlowStepDefinitionSchema),
  })
  .passthrough();

export const FlowDefinitionDraftSchema = FlowDefinitionBaseSchema;
export type FlowDefinitionDraft = z.infer<typeof FlowDefinitionDraftSchema>;

export const FlowDefinitionSchema = FlowDefinitionBaseSchema.extend({
  steps: FlowDefinitionBaseSchema.shape.steps.min(1),
});
export type FlowDefinitionDocument = z.infer<typeof FlowDefinitionSchema>;

export const FlowDefinitionSummarySchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  version: z.number().int(),
  status: z.string(),
  active: z.boolean(),
  description: z.string().nullable().optional(),
  updatedBy: z.string().nullable().optional(),
  updatedAt: z.string().nullable().optional(),
  publishedAt: z.string().nullable().optional(),
});
export type FlowDefinitionSummary = z.infer<typeof FlowDefinitionSummarySchema>;

export const FlowDefinitionDetailsSchema = FlowDefinitionSummarySchema.extend({
  definition: FlowDefinitionSchema,
  createdAt: z.string().nullable().optional(),
});
export type FlowDefinitionDetails = z.infer<typeof FlowDefinitionDetailsSchema>;

export const FlowDefinitionHistoryEntrySchema = z.object({
  id: z.number().int(),
  version: z.number().int(),
  status: z.string(),
  definition: FlowDefinitionSchema,
  changeNotes: z.string().nullable().optional(),
  createdBy: z.string().nullable().optional(),
  createdAt: z.string().nullable().optional(),
});
export type FlowDefinitionHistoryEntry = z.infer<typeof FlowDefinitionHistoryEntrySchema>;

export function parseMemoryReads(label: string, value: string): MemoryReadConfig[] | undefined {
  const trimmed = value.trim();
  if (!trimmed) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(trimmed);
    return z.array(MemoryReadSchema).parse(parsed);
  } catch (error) {
    const message = error instanceof Error ? error.message : 'некорректный JSON';
    throw new Error(`Поле "${label}" содержит некорректное значение: ${message}`);
  }
}

export function parseMemoryWrites(
  label: string,
  value: string,
): MemoryWriteConfig[] | undefined {
  const trimmed = value.trim();
  if (!trimmed) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(trimmed);
    return z.array(MemoryWriteSchema).parse(parsed);
  } catch (error) {
    const message = error instanceof Error ? error.message : 'некорректный JSON';
    throw new Error(`Поле "${label}" содержит некорректное значение: ${message}`);
  }
}

export function parseTransitions(
  label: string,
  value: string,
): FlowStepTransitionsDraft | undefined {
  const trimmed = value.trim();
  if (!trimmed) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(trimmed);
    return FlowDefinitionTransitionsSchema.parse(parsed);
  } catch (error) {
    const message = error instanceof Error ? error.message : 'некорректный JSON';
    throw new Error(`Поле "${label}" содержит некорректное значение: ${message}`);
  }
}
