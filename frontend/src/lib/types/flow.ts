import { z } from 'zod';
import { JsonObjectSchema, JsonValueSchema } from './json';

export const FlowLaunchParametersSchema = JsonObjectSchema.default({});
export type FlowLaunchParameters = z.infer<typeof FlowLaunchParametersSchema>;

export const FlowSharedContextSchema = z
  .object({
    initial: JsonValueSchema.optional(),
    current: JsonValueSchema.optional(),
    steps: JsonObjectSchema.optional(),
    lastOutput: JsonValueSchema.optional(),
    lastStepId: z.string().nullable().optional(),
    version: z.number().optional(),
  })
  .passthrough()
  .default({});
export type FlowSharedContext = z.infer<typeof FlowSharedContextSchema>;

export const FlowEventPayloadSchema = z
  .object({
    data: JsonValueSchema.optional(),
    context: z
      .object({
        launchParameters: FlowLaunchParametersSchema.optional(),
        launchOverrides: JsonObjectSchema.optional(),
      })
      .passthrough()
      .optional(),
    step: z
      .object({
        stepExecutionId: z.string().optional(),
        stepId: z.string().optional(),
        stepName: z.string().optional(),
        attempt: z.number().optional(),
        status: z.string().optional(),
        prompt: z.string().optional(),
        agentVersion: z
          .object({
            id: z.string().optional(),
            version: z.number().optional(),
            providerId: z.string().optional(),
            modelId: z.string().optional(),
            systemPrompt: z.string().optional(),
          })
          .passthrough()
          .optional(),
      })
      .passthrough()
      .optional(),
  })
  .passthrough();
export type FlowEventPayload = z.infer<typeof FlowEventPayloadSchema>;

export const FlowEventSchema = z.object({
  eventId: z.number(),
  type: z.string(),
  status: z.string().nullable().optional(),
  traceId: z.string().nullable().optional(),
  spanId: z.string().nullable().optional(),
  cost: z.number().nullable().optional(),
  tokensPrompt: z.number().nullable().optional(),
  tokensCompletion: z.number().nullable().optional(),
  createdAt: z.string().nullable().optional(),
  payload: FlowEventPayloadSchema.nullish(),
});
export type FlowEvent = z.infer<typeof FlowEventSchema>;

export const FlowStateSchema = z.object({
  sessionId: z.string(),
  status: z.string(),
  currentStepId: z.string().nullable().optional(),
  stateVersion: z.number(),
  currentMemoryVersion: z.number(),
  startedAt: z.string().nullable().optional(),
  completedAt: z.string().nullable().optional(),
  flowDefinitionId: z.string(),
  flowDefinitionVersion: z.number(),
  sharedContext: FlowSharedContextSchema.nullish(),
});
export type FlowState = z.infer<typeof FlowStateSchema>;

export const FlowStatusResponseSchema = z.object({
  state: FlowStateSchema,
  events: z.array(FlowEventSchema),
  nextSinceEventId: z.number(),
  telemetry: JsonValueSchema.nullable().optional(),
});
export type FlowStatusResponse = z.infer<typeof FlowStatusResponseSchema>;

export const FlowStartResponseSchema = z.object({
  sessionId: z.string(),
  status: z.string(),
  startedAt: z.string().nullable().optional(),
  launchParameters: FlowLaunchParametersSchema.nullish(),
  sharedContext: FlowSharedContextSchema.nullish(),
  overrides: z
    .object({
      temperature: z.number().nullable().optional(),
      topP: z.number().nullable().optional(),
      maxTokens: z.number().nullable().optional(),
    })
    .nullish(),
  chatSessionId: z.string().nullable().optional(),
});
export type FlowStartResponse = z.infer<typeof FlowStartResponseSchema>;

export function parseFlowStatusResponse(data: unknown): FlowStatusResponse {
  return FlowStatusResponseSchema.parse(data);
}

export function parseFlowStartResponse(data: unknown): FlowStartResponse {
  return FlowStartResponseSchema.parse(data);
}
