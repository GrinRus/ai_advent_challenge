import { z } from 'zod';
import {
  AgentCostProfileSchema,
  AgentDefaultOptionsSchema,
} from './agent';
import {
  ChatRequestOverridesSchema,
  MemoryReadSchema,
  MemoryWriteSchema,
} from './flowDefinition';

export const FlowLaunchPricingSchema = z.object({
  inputPer1KTokens: z.number().nullable().optional(),
  outputPer1KTokens: z.number().nullable().optional(),
  currency: z.string().nullable().optional(),
});
export type FlowLaunchPricing = z.infer<typeof FlowLaunchPricingSchema>;

export const FlowLaunchCostEstimateSchema = z.object({
  promptTokens: z.number().nullable().optional(),
  completionTokens: z.number().nullable().optional(),
  totalTokens: z.number().nullable().optional(),
  inputCost: z.number().nullable().optional(),
  outputCost: z.number().nullable().optional(),
  totalCost: z.number().nullable().optional(),
  currency: z.string().nullable().optional(),
});
export type FlowLaunchCostEstimate = z.infer<typeof FlowLaunchCostEstimateSchema>;

export const FlowLaunchAgentSchema = z.object({
  agentVersionId: z.string().uuid(),
  agentVersionNumber: z.number().int(),
  agentDefinitionId: z.string().uuid().nullable().optional(),
  agentIdentifier: z.string().nullable().optional(),
  agentDisplayName: z.string().nullable().optional(),
  providerType: z.string().nullable().optional(),
  providerId: z.string(),
  providerDisplayName: z.string().nullable().optional(),
  modelId: z.string(),
  modelDisplayName: z.string().nullable().optional(),
  modelContextWindow: z.number().nullable().optional(),
  modelMaxOutputTokens: z.number().nullable().optional(),
  syncOnly: z.boolean(),
  maxTokens: z.number().nullable().optional(),
  defaultOptions: AgentDefaultOptionsSchema.nullish(),
  costProfile: AgentCostProfileSchema.nullish(),
  pricing: FlowLaunchPricingSchema,
});
export type FlowLaunchAgent = z.infer<typeof FlowLaunchAgentSchema>;

export const FlowLaunchStepSchema = z.object({
  id: z.string(),
  name: z.string().nullable().optional(),
  prompt: z.string().nullable().optional(),
  agent: FlowLaunchAgentSchema,
  overrides: ChatRequestOverridesSchema.nullish(),
  memoryReads: z.array(MemoryReadSchema),
  memoryWrites: z.array(MemoryWriteSchema),
  transitions: z
    .object({
      onSuccess: z.string().nullable().optional(),
      completeOnSuccess: z.boolean(),
      onFailure: z.string().nullable().optional(),
      failFlowOnFailure: z.boolean(),
    })
    .default({
      onSuccess: null,
      completeOnSuccess: true,
      onFailure: null,
      failFlowOnFailure: true,
    }),
  maxAttempts: z.number().int().min(1),
  estimate: FlowLaunchCostEstimateSchema,
});
export type FlowLaunchStep = z.infer<typeof FlowLaunchStepSchema>;

export const FlowLaunchPreviewSchema = z.object({
  definitionId: z.string().uuid(),
  definitionName: z.string(),
  definitionVersion: z.number().int(),
  description: z.string().nullable().optional(),
  startStepId: z.string().nullable().optional(),
  steps: z.array(FlowLaunchStepSchema).min(1),
  totalEstimate: FlowLaunchCostEstimateSchema,
});
export type FlowLaunchPreview = z.infer<typeof FlowLaunchPreviewSchema>;
