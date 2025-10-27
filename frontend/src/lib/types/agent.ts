import { z } from 'zod';
import { JsonObjectSchema } from './json';

const JsonRecordSchema = JsonObjectSchema;

export const AgentDefaultOptionsSchema = z
  .object({
    temperature: z.number().gte(0).lte(2).optional(),
    topP: z.number().gt(0).lte(1).optional(),
    maxTokens: z.number().int().positive().optional(),
  })
  .passthrough();
export type AgentDefaultOptions = z.infer<typeof AgentDefaultOptionsSchema>;

export const AgentToolBindingsSchema = JsonRecordSchema;
export type AgentToolBindings = z.infer<typeof AgentToolBindingsSchema>;

export const AgentCostProfileSchema = JsonRecordSchema;
export type AgentCostProfile = z.infer<typeof AgentCostProfileSchema>;

export const AgentCapabilityPayloadSchema = JsonRecordSchema;
export type AgentCapabilityPayload = z.infer<typeof AgentCapabilityPayloadSchema>;

export const AgentCapabilitySchema = z.object({
  capability: z.string(),
  payload: AgentCapabilityPayloadSchema.nullish(),
});
export type AgentCapability = z.infer<typeof AgentCapabilitySchema>;

export const AgentVersionStatusSchema = z.enum(['DRAFT', 'PUBLISHED', 'DEPRECATED']);
export type AgentVersionStatus = z.infer<typeof AgentVersionStatusSchema>;

export const AgentVersionSchema = z.object({
  id: z.string().uuid(),
  version: z.number().int(),
  status: AgentVersionStatusSchema,
  providerType: z.string().optional().nullable(),
  providerId: z.string(),
  modelId: z.string(),
  systemPrompt: z.string().optional().nullable(),
  defaultOptions: AgentDefaultOptionsSchema.nullish(),
  toolBindings: AgentToolBindingsSchema.nullish(),
  costProfile: AgentCostProfileSchema.nullish(),
  syncOnly: z.boolean(),
  maxTokens: z.number().int().positive().nullable().optional(),
  createdBy: z.string().optional().nullable(),
  updatedBy: z.string().optional().nullable(),
  createdAt: z.string().optional().nullable(),
  publishedAt: z.string().optional().nullable(),
  capabilities: z.array(AgentCapabilitySchema),
});
export type AgentVersion = z.infer<typeof AgentVersionSchema>;

export const AgentDefinitionSummarySchema = z.object({
  id: z.string().uuid(),
  identifier: z.string(),
  displayName: z.string(),
  description: z.string().nullable().optional(),
  active: z.boolean(),
  createdBy: z.string().nullable().optional(),
  updatedBy: z.string().nullable().optional(),
  createdAt: z.string().nullable().optional(),
  updatedAt: z.string().nullable().optional(),
  latestVersion: z.number().int().nullable().optional(),
  latestPublishedVersion: z.number().int().nullable().optional(),
  latestPublishedAt: z.string().nullable().optional(),
});
export type AgentDefinitionSummary = z.infer<typeof AgentDefinitionSummarySchema>;

export const AgentDefinitionDetailsSchema = AgentDefinitionSummarySchema.extend({
  versions: z.array(AgentVersionSchema),
});
export type AgentDefinitionDetails = z.infer<typeof AgentDefinitionDetailsSchema>;
