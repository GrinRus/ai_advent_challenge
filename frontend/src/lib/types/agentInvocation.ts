import { z } from 'zod';
import { JsonValueSchema } from './json';

export const AgentInvocationModeSchema = z.enum(['SYNC', 'STREAM', 'STRUCTURED']);
export type AgentInvocationMode = z.infer<typeof AgentInvocationModeSchema>;

export const AgentInvocationProviderSchema = z
  .object({
    type: z.string().nullable().optional(),
    id: z.string(),
    modelId: z.string(),
    mode: AgentInvocationModeSchema.optional(),
  })
  .strict();
export type AgentInvocationProvider = z.infer<typeof AgentInvocationProviderSchema>;

export const AgentPromptVariableSchema = z
  .object({
    name: z.string(),
    required: z.boolean().optional(),
    description: z.string().nullable().optional(),
    schema: JsonValueSchema.nullish(),
  })
  .strict();
export type AgentPromptVariable = z.infer<typeof AgentPromptVariableSchema>;

export const AgentPromptGenerationSchema = z
  .object({
    temperature: z.number().nullable().optional(),
    topP: z.number().nullable().optional(),
    maxOutputTokens: z.number().int().nullable().optional(),
  })
  .partial()
  .strict();
export type AgentPromptGeneration = z.infer<typeof AgentPromptGenerationSchema>;

export const AgentInvocationPromptSchema = z
  .object({
    templateId: z.string().nullable().optional(),
    system: z.string().nullable().optional(),
    variables: z.array(AgentPromptVariableSchema).optional(),
    generation: AgentPromptGenerationSchema.optional(),
  })
  .partial()
  .strict();
export type AgentInvocationPrompt = z.infer<typeof AgentInvocationPromptSchema>;

export const AgentMemoryPolicySchema = z
  .object({
    channels: z.array(z.string()).optional(),
    retentionDays: z.number().int().nullable().optional(),
    maxEntries: z.number().int().nullable().optional(),
    summarizationStrategy: z.string().nullable().optional(),
    overflowAction: z.string().nullable().optional(),
  })
  .partial()
  .strict();
export type AgentMemoryPolicy = z.infer<typeof AgentMemoryPolicySchema>;

export const AgentRetryPolicySchema = z
  .object({
    maxAttempts: z.number().int().nullable().optional(),
    initialDelayMs: z.number().int().nullable().optional(),
    multiplier: z.number().nullable().optional(),
    retryableStatuses: z.array(z.number().int()).optional(),
    timeoutMs: z.number().int().nullable().optional(),
    overallDeadlineMs: z.number().int().nullable().optional(),
    jitterMs: z.number().int().nullable().optional(),
  })
  .partial()
  .strict();
export type AgentRetryPolicy = z.infer<typeof AgentRetryPolicySchema>;

export const AgentAdvisorToggleSchema = z
  .object({
    enabled: z.boolean().optional(),
  })
  .partial()
  .strict();
export type AgentAdvisorToggle = z.infer<typeof AgentAdvisorToggleSchema>;

export const AgentAuditSettingsSchema = z
  .object({
    enabled: z.boolean().optional(),
    redactPii: z.boolean().optional(),
  })
  .partial()
  .strict();
export type AgentAuditSettings = z.infer<typeof AgentAuditSettingsSchema>;

export const AgentRoutingSettingsSchema = z
  .object({
    enabled: z.boolean().optional(),
    parameters: JsonValueSchema.nullish(),
  })
  .partial()
  .strict();
export type AgentRoutingSettings = z.infer<typeof AgentRoutingSettingsSchema>;

export const AgentAdvisorSettingsSchema = z
  .object({
    telemetry: AgentAdvisorToggleSchema.optional(),
    audit: AgentAuditSettingsSchema.optional(),
    routing: AgentRoutingSettingsSchema.optional(),
  })
  .partial()
  .strict();
export type AgentAdvisorSettings = z.infer<typeof AgentAdvisorSettingsSchema>;

export const AgentToolExecutionModeSchema = z.enum(['AUTO', 'MANUAL', 'MANDATORY']);
export type AgentToolExecutionMode = z.infer<typeof AgentToolExecutionModeSchema>;

export const AgentToolBindingSchema = z
  .object({
    toolCode: z.string().min(1).optional(),
    schemaVersion: z.number().int().nullable().optional(),
    executionMode: AgentToolExecutionModeSchema.optional(),
    requestOverrides: JsonValueSchema.nullish(),
    responseExpectations: JsonValueSchema.nullish(),
  })
  .partial()
  .strict();
export type AgentToolBinding = z.infer<typeof AgentToolBindingSchema>;

export const AgentToolingSchema = z
  .object({
    bindings: z.array(AgentToolBindingSchema).optional(),
  })
  .partial()
  .strict();
export type AgentTooling = z.infer<typeof AgentToolingSchema>;

export const AgentCostProfileSchema = z
  .object({
    inputPer1KTokens: z.number().nullable().optional(),
    outputPer1KTokens: z.number().nullable().optional(),
    latencyFee: z.number().nullable().optional(),
    fixedFee: z.number().nullable().optional(),
    currency: z.string().nullable().optional(),
  })
  .partial()
  .strict();
export type AgentCostProfile = z.infer<typeof AgentCostProfileSchema>;

export const AgentInvocationOptionsSchema = z
  .object({
    provider: AgentInvocationProviderSchema.optional(),
    prompt: AgentInvocationPromptSchema.optional(),
    memoryPolicy: AgentMemoryPolicySchema.optional(),
    retryPolicy: AgentRetryPolicySchema.optional(),
    advisorSettings: AgentAdvisorSettingsSchema.optional(),
    tooling: AgentToolingSchema.optional(),
    costProfile: AgentCostProfileSchema.optional(),
  })
  .partial()
  .strict();
export type AgentInvocationOptions = z.infer<typeof AgentInvocationOptionsSchema>;

export const AgentInvocationOptionsInputSchema = AgentInvocationOptionsSchema.extend({
  provider: AgentInvocationProviderSchema,
});
export type AgentInvocationOptionsInput = z.infer<typeof AgentInvocationOptionsInputSchema>;
