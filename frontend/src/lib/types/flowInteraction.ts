import { z } from 'zod';
import { JsonValueSchema } from './json';

export const FlowInteractionStatusSchema = z.enum([
  'PENDING',
  'ANSWERED',
  'EXPIRED',
  'AUTO_RESOLVED',
]);
export type FlowInteractionStatus = z.infer<typeof FlowInteractionStatusSchema>;

export const FlowInteractionTypeSchema = z.enum([
  'INPUT_FORM',
  'APPROVAL',
  'CONFIRMATION',
  'REVIEW',
  'INFORMATION',
]);
export type FlowInteractionType = z.infer<typeof FlowInteractionTypeSchema>;

export const FlowInteractionResponseSourceSchema = z.enum([
  'USER',
  'AUTO_POLICY',
  'SYSTEM',
]);
export type FlowInteractionResponseSource = z.infer<
  typeof FlowInteractionResponseSourceSchema
>;

export const FlowSuggestedActionSchema = z.object({
  id: z.string(),
  label: z.string(),
  source: z.string().optional(),
  description: z.string().nullable().optional(),
  ctaLabel: z.string().nullable().optional(),
  payload: JsonValueSchema.nullish(),
});
export type FlowSuggestedAction = z.infer<typeof FlowSuggestedActionSchema>;

export const FlowSuggestedActionFilterSchema = z.object({
  id: z.string().optional(),
  source: z.string().optional(),
  reason: z.string(),
});
export type FlowSuggestedActionFilter = z.infer<
  typeof FlowSuggestedActionFilterSchema
>;

export const FlowSuggestedActionsSchema = z.object({
  ruleBased: z.array(FlowSuggestedActionSchema),
  llm: z.array(FlowSuggestedActionSchema).optional(),
  analytics: z.array(FlowSuggestedActionSchema).optional(),
  allow: z.array(z.string()).optional(),
  filtered: z.array(FlowSuggestedActionFilterSchema).optional(),
});
export type FlowSuggestedActions = z.infer<typeof FlowSuggestedActionsSchema>;

export const FlowInteractionResponseSummarySchema = z.object({
  responseId: z.string().uuid(),
  source: FlowInteractionResponseSourceSchema,
  respondedBy: z.string().uuid().nullable().optional(),
  respondedAt: z.string().optional().nullable(),
  status: FlowInteractionStatusSchema,
  payload: JsonValueSchema.nullish(),
});
export type FlowInteractionResponseSummary = z.infer<
  typeof FlowInteractionResponseSummarySchema
>;

export const FlowInteractionItemSchema = z.object({
  requestId: z.string().uuid(),
  chatSessionId: z.string().uuid(),
  stepId: z.string(),
  status: FlowInteractionStatusSchema,
  type: FlowInteractionTypeSchema,
  title: z.string().nullable().optional(),
  description: z.string().nullable().optional(),
  payloadSchema: JsonValueSchema.nullish(),
  suggestedActions: FlowSuggestedActionsSchema.nullish(),
  createdAt: z.string(),
  updatedAt: z.string(),
  dueAt: z.string().nullable().optional(),
  response: FlowInteractionResponseSummarySchema.nullish(),
});
export type FlowInteractionItem = z.infer<typeof FlowInteractionItemSchema>;

export const FlowInteractionListSchema = z.object({
  active: z.array(FlowInteractionItemSchema),
  history: z.array(FlowInteractionItemSchema),
});
export type FlowInteractionList = z.infer<typeof FlowInteractionListSchema>;
