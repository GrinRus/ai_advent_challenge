import { z } from 'zod';
import type { ZodSchema } from 'zod';
import {
  FlowLaunchPayloadSchema,
  parseFlowStartResponse,
  parseFlowStatusResponse,
} from './types/flow';
import type { FlowLaunchPayload, FlowStartResponse, FlowStatusResponse } from './types/flow';
import {
  AgentDefinitionDetailsSchema,
  AgentDefinitionSummarySchema,
  AgentVersionSchema,
} from './types/agent';
import type {
  AgentCapability,
  AgentDefinitionDetails,
  AgentDefinitionSummary,
  AgentInvocationOptionsPayload,
  AgentVersion,
} from './types/agent';
import {
  FlowDefinitionDetailsSchema,
  FlowDefinitionHistoryEntrySchema,
  FlowDefinitionSummarySchema,
} from './types/flowDefinition';
import type {
  FlowDefinitionDetails,
  FlowDefinitionDocument,
  FlowDefinitionHistoryEntry,
  FlowDefinitionSummary,
  FlowStepOverrides,
} from './types/flowDefinition';
import { FlowLaunchPreviewSchema } from './types/flowLaunch';
import type { FlowLaunchPreview } from './types/flowLaunch';
import {
  FlowInteractionItemSchema,
  FlowInteractionListSchema,
} from './types/flowInteraction';
import type {
  FlowInteractionItem,
  FlowInteractionList,
  FlowInteractionResponseSource,
  FlowInteractionResponseSummary,
} from './types/flowInteraction';
import type { JsonValue } from './types/json';
import { buildActiveProfileHeaders } from './profileStore';
import {
  DevLinkResponseSchema,
  ProfileAdminPageSchema,
  ProfileAuditEntrySchema,
  ProfileDocumentSchema,
  type DevProfileLinkResponse,
  type ProfileAdminPage,
  type ProfileAuditEntry,
  type ProfileUpdatePayload,
  type UserProfileDocument,
} from './profileTypes';

export type {
  FlowEvent,
  FlowStartResponse,
  FlowState,
  FlowStatusResponse,
  FlowTelemetrySnapshot,
} from './types/flow';
export type {
  AgentCapability,
  AgentCapabilityPayload,
  AgentDefinitionDetails,
  AgentDefinitionSummary,
  AgentInvocationOptionsPayload,
  AgentInvocationOptionsType,
  AgentVersion,
} from './types/agent';
export type {
  FlowDefinitionDetails,
  FlowDefinitionHistoryEntry,
  FlowDefinitionSummary,
} from './types/flowDefinition';
export type {
  FlowLaunchPreview,
  FlowLaunchStep,
  FlowLaunchCostEstimate,
  FlowLaunchPricing,
} from './types/flowLaunch';
export type {
  FlowInteractionResponseSource,
  FlowInteractionStatus,
  FlowInteractionType,
  FlowInteractionResponseSummary,
  FlowSuggestedAction,
  FlowSuggestedActionFilter,
  FlowSuggestedActions,
} from './types/flowInteraction';
export type { JsonValue } from './types/json';
export type {
  UserProfileDocument,
  ProfileUpdatePayload,
  ProfileAuditEntry,
  DevProfileLinkResponse,
  ProfileAdminPage,
} from './profileTypes';

export type AdminRole = z.infer<typeof AdminRoleSchema>;

export type McpToolSummary = {
  code: string;
  displayName?: string | null;
  description?: string | null;
  mcpToolName?: string | null;
  schemaVersion: number;
  available: boolean;
};

export type McpServerSummary = {
  id: string;
  displayName?: string | null;
  description?: string | null;
  tags: string[];
  status: 'UP' | 'DOWN' | 'UNKNOWN';
  securityPolicy?: string | null;
  tools: McpToolSummary[];
};

export type McpCatalogResponse = {
  servers: McpServerSummary[];
};

export type McpHealthEvent = {
  serverId: string;
  status: 'UP' | 'DOWN' | 'UNKNOWN';
  toolCount: number;
  availableTools: string[];
  unavailableTools: string[];
  timestamp: string;
};

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? '/api';
export const CHAT_STREAM_URL = `${API_BASE_URL}/llm/chat/stream`;
export const CHAT_SYNC_URL = `${API_BASE_URL}/llm/chat/sync`;
export const CHAT_STRUCTURED_SYNC_URL = `${API_BASE_URL}/llm/chat/sync/structured`;


export type HelpResponse = {
  message: string;
};

export type ChatProvidersResponse = {
  defaultProvider: string;
  providers: Array<{
    id: string;
    displayName?: string;
    type?: string;
    defaultModel?: string;
    temperature?: number;
    topP?: number;
    maxTokens?: number;
    models: Array<{
      id: string;
      displayName?: string;
      tier?: string;
      inputPer1KTokens?: number;
      outputPer1KTokens?: number;
      contextWindow?: number;
      maxOutputTokens?: number;
      syncEnabled?: boolean;
      streamingEnabled?: boolean;
      structuredEnabled?: boolean;
      currency?: string;
    }>;
  }>;
};

const AgentDefinitionSummaryListSchema = z.array(AgentDefinitionSummarySchema);
const FlowDefinitionSummaryListSchema = z.array(FlowDefinitionSummarySchema);
const FlowDefinitionHistoryListSchema = z.array(FlowDefinitionHistoryEntrySchema);
const AdminRoleSchema = z.object({
  id: z.string().uuid(),
  code: z.string(),
  displayName: z.string(),
  description: z.string().optional().nullable(),
});
const AdminRoleListSchema = z.array(AdminRoleSchema);
const ProfileAuditEntryListSchema = z.array(ProfileAuditEntrySchema);

function validateWithSchema<T>(payload: unknown, schema: ZodSchema<T>, context: string): T {
  const parsed = schema.safeParse(payload);
  if (!parsed.success) {
    throw new Error(
      `${context}: формат ответа не соответствует ожиданиям (${parsed.error.message})`,
    );
  }
  return parsed.data;
}

async function parseJsonResponse<T>(
  response: Response,
  schema: ZodSchema<T>,
  errorPrefix: string,
): Promise<T> {
  const rawText = await response.text();
  if (!response.ok) {
    throw new Error(
      `${errorPrefix} (status ${response.status}): ${
        rawText || response.statusText
      }`,
    );
  }

  let payload: unknown = null;
  try {
    payload = rawText ? JSON.parse(rawText) : null;
  } catch (error) {
    throw new Error(`${errorPrefix}: не удалось разобрать JSON (${(error as Error).message})`);
  }

  return validateWithSchema(payload, schema, errorPrefix);
}

function tryParseJson(raw: string): unknown | undefined {
  if (!raw) {
    return undefined;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
}

function buildProfileHeaders(
  profileKey: string,
  channel?: string,
  ifMatch?: string,
  devToken?: string,
): HeadersInit {
  const headers: HeadersInit = {
    'X-Profile-Key': profileKey,
  };
  if (channel) {
    headers['X-Profile-Channel'] = channel;
  }
  if (ifMatch) {
    headers['If-Match'] = ifMatch;
  }
  if (devToken) {
    headers['X-Profile-Auth'] = devToken;
  }
  return headers;
}

export async function fetchProfileDocument(
  namespace: string,
  reference: string,
  profileKey: string,
  channel?: string,
  devToken?: string,
): Promise<{ profile: UserProfileDocument; etag?: string }> {
  const response = await fetch(`${API_BASE_URL}/profile/${namespace}/${reference}`, {
    headers: buildProfileHeaders(profileKey, channel, undefined, devToken),
  });
  const raw = await response.text();
  if (!response.ok) {
    throw new Error(raw || `Не удалось загрузить профиль (status ${response.status})`);
  }
  const payload = raw ? JSON.parse(raw) : {};
  const profile = ProfileDocumentSchema.parse(payload);
  const etag = response.headers.get('ETag') ?? undefined;
  return { profile, etag };
}

export async function fetchProfileAudit(
  namespace: string,
  reference: string,
  profileKey: string,
  channel?: string,
  devToken?: string,
): Promise<ProfileAuditEntry[]> {
  const response = await fetch(
    `${API_BASE_URL}/profile/${namespace}/${reference}/audit`,
    {
      headers: buildProfileHeaders(profileKey, channel, undefined, devToken),
    },
  );
  return parseJsonResponse(
    response,
    ProfileAuditEntryListSchema,
    'Не удалось загрузить историю профиля',
  );
}

export async function createDevProfileLink(
  namespace: string,
  reference: string,
  profileKey: string,
  channel?: string,
  devToken?: string,
): Promise<DevProfileLinkResponse> {
  const response = await fetch(
    `${API_BASE_URL}/profile/${namespace}/${reference}/dev-link`,
    {
      method: 'POST',
      headers: buildProfileHeaders(profileKey, channel, undefined, devToken),
    },
  );
  return parseJsonResponse(
    response,
    DevLinkResponseSchema,
    'Не удалось создать dev-link для профиля',
  );
}

export async function fetchAdminProfiles(params: {
  namespace?: string;
  reference?: string;
  page?: number;
  size?: number;
}): Promise<ProfileAdminPage> {
  const { namespace, reference, page = 0, size = 20 } = params;
  const query = new URLSearchParams();
  if (namespace) {
    query.set('namespace', namespace);
  }
  if (reference) {
    query.set('reference', reference);
  }
  query.set('page', String(page));
  query.set('size', String(size));
  const response = await fetch(
    `${API_BASE_URL}/admin/roles/profiles?${query.toString()}`,
    {
      headers: buildActiveProfileHeaders(),
    },
  );
  return parseJsonResponse(
    response,
    ProfileAdminPageSchema,
    'Не удалось загрузить список профилей',
  );
}

export async function fetchAdminRoles(): Promise<AdminRole[]> {
  const response = await fetch(`${API_BASE_URL}/admin/roles`, {
    headers: buildActiveProfileHeaders(),
  });
  return parseJsonResponse(response, AdminRoleListSchema, 'Не удалось загрузить роли');
}

export async function assignProfileRole(profileId: string, roleCode: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/admin/roles/${profileId}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildActiveProfileHeaders(),
    },
    body: JSON.stringify({ roleCode }),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || 'Не удалось выдать роль');
  }
}

export async function revokeProfileRole(profileId: string, roleCode: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/admin/roles/${profileId}/${roleCode}`, {
    method: 'DELETE',
    headers: buildActiveProfileHeaders(),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || 'Не удалось снять роль');
  }
}

export async function updateProfileDocument(
  namespace: string,
  reference: string,
  profileKey: string,
  payload: ProfileUpdatePayload,
  options?: { channel?: string; ifMatch?: string; devToken?: string },
): Promise<{ profile: UserProfileDocument; etag?: string }> {
  const response = await fetch(`${API_BASE_URL}/profile/${namespace}/${reference}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...buildProfileHeaders(
        profileKey,
        options?.channel,
        options?.ifMatch,
        options?.devToken,
      ),
    },
    body: JSON.stringify(payload),
  });
  const raw = await response.text();
  if (!response.ok) {
    throw new Error(raw || `Не удалось обновить профиль (status ${response.status})`);
  }
  const data = ProfileDocumentSchema.parse(JSON.parse(raw));
  return { profile: data, etag: response.headers.get('ETag') ?? undefined };
}

function parseJsonBodyOrThrow(raw: string, context: string): unknown {
  if (!raw) {
    return {};
  }
  const parsed = tryParseJson(raw);
  if (parsed === undefined) {
    throw new Error(`${context}: не удалось разобрать JSON ответа`);
  }
  return parsed;
}

export function parseMcpHealthEvent(payload: unknown): McpHealthEvent {
  return validateWithSchema(
    payload,
    McpHealthEventSchema,
    'Некорректный формат события MCP health',
  );
}

export type AgentCapabilityResponse = AgentCapability;
export type AgentVersionResponse = AgentVersion;

export type AgentDefinitionPayload = {
  identifier: string;
  displayName: string;
  description?: string;
  active?: boolean;
  createdBy?: string;
  updatedBy?: string;
};

export type AgentDefinitionStatusPayload = {
  active: boolean;
  updatedBy: string;
};

export type AgentVersionPayload = {
  providerType?: string;
  providerId: string;
  modelId: string;
  systemPrompt: string;
  invocationOptions: AgentInvocationOptionsPayload;
  syncOnly?: boolean;
  maxTokens?: number;
  createdBy: string;
  capabilities?: AgentCapability[];
};

export type AgentVersionPublishPayload = {
  updatedBy: string;
  capabilities?: AgentCapability[];
};

export type AgentVersionUpdatePayload = {
  systemPrompt: string;
  invocationOptions: AgentInvocationOptionsPayload;
  syncOnly?: boolean;
  maxTokens?: number;
  updatedBy: string;
  capabilities?: AgentCapability[];
};

export type ChatSyncResponse = {
  requestId?: string;
  content?: string;
  provider?: StructuredSyncProvider | null;
  tools?: string[];
  structured?: StructuredSyncResponse | null;
  usage?: StructuredSyncUsageStats | null;
  cost?: UsageCostDetails | null;
  latencyMs?: number | null;
  timestamp?: string | null;
};

export type ChatSyncRequest = {
  sessionId?: string;
  message: string;
  provider?: string;
  model?: string;
  mode?: string;
  requestedToolCodes?: string[];
  options?: {
    temperature?: number;
    topP?: number;
    maxTokens?: number;
  };
};

export type StructuredSyncProvider = {
  type?: string | null;
  model?: string | null;
};

export type StructuredSyncItem = {
  title?: string | null;
  details?: string | null;
  tags?: string[] | null;
};

export type StructuredSyncAnswer = {
  summary?: string | null;
  items?: StructuredSyncItem[] | null;
  confidence?: number | null;
};

export type StructuredSyncUsageStats = {
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
};

export type UsageCostDetails = {
  input?: number | null;
  output?: number | null;
  total?: number | null;
  currency?: string | null;
};

export type StructuredSyncResponse = {
  requestId?: string;
  status?: string;
  provider?: StructuredSyncProvider | null;
  answer?: StructuredSyncAnswer | null;
  tools?: string[] | null;
  usage?: StructuredSyncUsageStats | null;
  cost?: UsageCostDetails | null;
  latencyMs?: number | null;
  timestamp?: string | null;
};

const StructuredSyncProviderSchema = z.object({
  type: z.string().nullable().optional(),
  model: z.string().nullable().optional(),
});

const StructuredSyncUsageStatsSchema = z
  .object({
    promptTokens: z.number().int().nonnegative().nullable().optional(),
    completionTokens: z.number().int().nonnegative().nullable().optional(),
    totalTokens: z.number().int().nonnegative().nullable().optional(),
  })
  .partial();

const UsageCostDetailsSchema = z
  .object({
    input: z.number().nullable().optional(),
    output: z.number().nullable().optional(),
    total: z.number().nullable().optional(),
    currency: z.string().nullable().optional(),
  })
  .partial();

const StructuredSyncItemSchema = z.object({
  title: z.string().nullable().optional(),
  details: z.string().nullable().optional(),
  tags: z.array(z.string()).optional(),
});

const StructuredSyncAnswerSchema = z.object({
  summary: z.string().nullable().optional(),
  items: z.array(StructuredSyncItemSchema).optional(),
  confidence: z.number().nullable().optional(),
});

const McpToolSchema = z.object({
  code: z.string(),
  displayName: z.string().nullable().optional(),
  description: z.string().nullable().optional(),
  mcpToolName: z.string().nullable().optional(),
  schemaVersion: z.number(),
  available: z.boolean(),
});

const McpServerSchema = z.object({
  id: z.string(),
  displayName: z.string().nullable().optional(),
  description: z.string().nullable().optional(),
  tags: z.array(z.string()),
  status: z.enum(['UP', 'DOWN', 'UNKNOWN']),
  securityPolicy: z.string().nullable().optional(),
  tools: z.array(McpToolSchema),
});

const McpCatalogSchema = z.object({
  servers: z.array(McpServerSchema),
});

const McpHealthEventSchema = z.object({
  serverId: z.string(),
  status: z.enum(['UP', 'DOWN', 'UNKNOWN']),
  toolCount: z.number(),
  availableTools: z.array(z.string()),
  unavailableTools: z.array(z.string()),
  timestamp: z.string(),
});

const StructuredSyncResponseSchema = z.object({
  requestId: z.string().optional(),
  status: z.string().optional(),
  provider: StructuredSyncProviderSchema.optional(),
  answer: StructuredSyncAnswerSchema.optional(),
  tools: z.array(z.string()).optional(),
  usage: StructuredSyncUsageStatsSchema.nullish(),
  cost: UsageCostDetailsSchema.nullish(),
  latencyMs: z.number().nullable().optional(),
  timestamp: z.string().optional(),
});

const ChatSyncResponseSchema = z.object({
  requestId: z.string().optional(),
  content: z.string().optional(),
  provider: StructuredSyncProviderSchema.optional(),
  tools: z.array(z.string()).optional(),
  structured: StructuredSyncResponseSchema.nullish(),
  usage: StructuredSyncUsageStatsSchema.nullish(),
  cost: UsageCostDetailsSchema.nullish(),
  latencyMs: z.number().nullable().optional(),
  timestamp: z.string().optional(),
});

type ProviderSchemaInput = z.infer<typeof StructuredSyncProviderSchema>;
type StructuredSyncResponseInput = z.infer<typeof StructuredSyncResponseSchema>;
type UsageSchemaInput = z.infer<typeof StructuredSyncUsageStatsSchema> | null | undefined;
type CostSchemaInput = z.infer<typeof UsageCostDetailsSchema> | null | undefined;
type AnswerSchemaInput = z.infer<typeof StructuredSyncAnswerSchema> | null | undefined;

function normalizeStructuredProvider(
  provider?: ProviderSchemaInput | StructuredSyncProvider | null,
): StructuredSyncProvider | undefined {
  if (!provider) {
    return undefined;
  }
  const normalized: StructuredSyncProvider = {};
  if (provider.type != null) {
    normalized.type = provider.type;
  }
  if (provider.model != null) {
    normalized.model = provider.model;
  }
  return Object.keys(normalized).length > 0 ? normalized : undefined;
}

function normalizeUsage(usage?: UsageSchemaInput): StructuredSyncUsageStats | undefined {
  if (!usage) {
    return undefined;
  }
  const normalized: StructuredSyncUsageStats = {};
  if (usage.promptTokens != null) {
    normalized.promptTokens = usage.promptTokens;
  }
  if (usage.completionTokens != null) {
    normalized.completionTokens = usage.completionTokens;
  }
  if (usage.totalTokens != null) {
    normalized.totalTokens = usage.totalTokens;
  }
  return Object.keys(normalized).length > 0 ? normalized : undefined;
}

function normalizeCost(cost?: CostSchemaInput): UsageCostDetails | undefined {
  if (!cost) {
    return undefined;
  }
  const normalized: UsageCostDetails = {};
  if (cost.input != null) {
    normalized.input = cost.input;
  }
  if (cost.output != null) {
    normalized.output = cost.output;
  }
  if (cost.total != null) {
    normalized.total = cost.total;
  }
  if (cost.currency != null) {
    normalized.currency = cost.currency;
  }
  return Object.keys(normalized).length > 0 ? normalized : undefined;
}

function normalizeStructuredResponse(
  response?: StructuredSyncResponseInput | StructuredSyncResponse | null,
): StructuredSyncResponse | undefined {
  if (!response) {
    return undefined;
  }
  const normalized: StructuredSyncResponse = {};
  if (response.requestId != null) {
    normalized.requestId = response.requestId;
  }
  if (response.status != null) {
    normalized.status = response.status;
  }
  const provider = normalizeStructuredProvider(response.provider);
  if (provider) {
    normalized.provider = provider;
  }
  const answer = normalizeStructuredAnswer(response.answer);
  if (answer) {
    normalized.answer = answer;
  }
  if (response.tools && response.tools.length > 0) {
    normalized.tools = [...response.tools];
  }
  const usage = normalizeUsage(response.usage ?? null);
  if (usage) {
    normalized.usage = usage;
  }
  const cost = normalizeCost(response.cost ?? null);
  if (cost) {
    normalized.cost = cost;
  }
  if (response.latencyMs != null) {
    normalized.latencyMs = response.latencyMs;
  }
  if (response.timestamp != null) {
    normalized.timestamp = response.timestamp;
  }
  return normalized;
}

function normalizeStructuredAnswer(
  answer?: AnswerSchemaInput | StructuredSyncAnswer | null,
): StructuredSyncAnswer | undefined {
  if (!answer) {
    return undefined;
  }
  const normalized: StructuredSyncAnswer = {};
  if (answer.summary != null) {
    normalized.summary = answer.summary;
  }
  if (answer.items && answer.items.length > 0) {
    normalized.items = answer.items.map((item) => {
      const normalizedItem: StructuredSyncItem = {};
      if (item.title != null) {
        normalizedItem.title = item.title;
      }
      if (item.details != null) {
        normalizedItem.details = item.details;
      }
      if (item.tags && item.tags.length > 0) {
        normalizedItem.tags = item.tags;
      }
      return normalizedItem;
    });
  }
  if (answer.confidence != null) {
    normalized.confidence = answer.confidence;
  }
  return Object.keys(normalized).length > 0 ? normalized : undefined;
}

export type FlowInteractionResponseSummaryDto = FlowInteractionResponseSummary;
export type FlowInteractionItemDto = FlowInteractionItem;
export type FlowInteractionListResponse = FlowInteractionList;

export type FlowInteractionRespondPayload = {
  chatSessionId: string;
  respondedBy?: string;
  source?: FlowInteractionResponseSource;
  payload?: JsonValue;
};

export type FlowInteractionAutoResolvePayload = {
  payload?: JsonValue;
  source?: Extract<FlowInteractionResponseSource, 'AUTO_POLICY' | 'SYSTEM'>;
  respondedBy?: string;
};

export type FlowControlCommand = 'pause' | 'resume' | 'cancel' | 'retryStep';

export type ChatRequestOverridesDto = FlowStepOverrides;

export type SessionUsageMessage = {
  messageId?: string;
  sequenceNumber?: number;
  role?: string;
  provider?: string;
  model?: string;
  usage?: StructuredSyncUsageStats | null;
  cost?: UsageCostDetails | null;
  createdAt?: string;
};

export type SessionUsageTotals = {
  usage?: StructuredSyncUsageStats | null;
  cost?: UsageCostDetails | null;
};

export type SessionUsageResponse = {
  sessionId: string;
  messages: SessionUsageMessage[];
  totals?: SessionUsageTotals | null;
};

export type StructuredSyncCallResult = {
  body: StructuredSyncResponse;
  sessionId?: string;
  newSession?: boolean;
};

export type SyncCallResult = {
  body: ChatSyncResponse;
  sessionId?: string;
  newSession?: boolean;
};

export async function fetchHelp(): Promise<HelpResponse> {
  const response = await fetch(`${API_BASE_URL}/help`, {
    headers: {
      Accept: 'application/json',
    },
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(
      `Failed to load help message (status ${response.status}): ${errorText}`,
    );
  }

  return response.json() as Promise<HelpResponse>;
}

export async function fetchChatProviders(): Promise<ChatProvidersResponse> {
  const response = await fetch(`${API_BASE_URL}/llm/providers`, {
    headers: buildActiveProfileHeaders({
      Accept: 'application/json',
    }),
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(
      `Failed to load chat providers (status ${response.status}): ${errorText}`,
    );
  }

  return response.json() as Promise<ChatProvidersResponse>;
}

export async function fetchMcpCatalog(): Promise<McpCatalogResponse> {
  const response = await fetch(`${API_BASE_URL}/mcp/catalog`, {
    headers: {
      Accept: 'application/json',
    },
  });
  return parseJsonResponse(
    response,
    McpCatalogSchema,
    'Не удалось загрузить каталог MCP',
  );
}

type CacheRecord<T> = {
  data: T;
  fetchedAt: number;
};

const AGENT_CACHE_TTL_MS = 30_000;

let agentDefinitionsCache: CacheRecord<AgentDefinitionSummary[]> | null = null;
let agentDefinitionsPromise: Promise<AgentDefinitionSummary[]> | null = null;
const agentDefinitionDetailsCache = new Map<string, CacheRecord<AgentDefinitionDetails>>();
const agentDefinitionDetailsPromises = new Map<string, Promise<AgentDefinitionDetails>>();

const isCacheFresh = (cachedAt: number) => Date.now() - cachedAt < AGENT_CACHE_TTL_MS;

export function invalidateAgentCatalogCache() {
  agentDefinitionsCache = null;
  agentDefinitionsPromise = null;
  agentDefinitionDetailsCache.clear();
  agentDefinitionDetailsPromises.clear();
}

export async function fetchAgentDefinitions(
  forceRefresh = false,
): Promise<AgentDefinitionSummary[]> {
  if (!forceRefresh && agentDefinitionsCache && isCacheFresh(agentDefinitionsCache.fetchedAt)) {
    return agentDefinitionsCache.data;
  }
  if (!forceRefresh && agentDefinitionsPromise) {
    return agentDefinitionsPromise;
  }

  const request = (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/agents/definitions`, {
        headers: { Accept: 'application/json' },
      });
      const data = await parseJsonResponse(
        response,
        AgentDefinitionSummaryListSchema,
        'Не удалось получить список агентов',
      );
      agentDefinitionsCache = { data, fetchedAt: Date.now() };
      agentDefinitionsPromise = null;
      return data;
    } catch (error) {
      agentDefinitionsPromise = null;
      throw error;
    }
  })();

  if (!forceRefresh) {
    agentDefinitionsPromise = request;
  }

  return request;
}

export async function fetchAgentDefinition(
  id: string,
  options: { forceRefresh?: boolean } = {},
): Promise<AgentDefinitionDetails> {
  const { forceRefresh = false } = options;

  const cached = agentDefinitionDetailsCache.get(id);
  if (!forceRefresh && cached && isCacheFresh(cached.fetchedAt)) {
    return cached.data;
  }

  if (!forceRefresh && agentDefinitionDetailsPromises.has(id)) {
    return agentDefinitionDetailsPromises.get(id)!;
  }

  const request = (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/agents/definitions/${id}`, {
        headers: { Accept: 'application/json' },
      });
      const data = await parseJsonResponse(
        response,
        AgentDefinitionDetailsSchema,
        'Не удалось получить информацию об агенте',
      );
      agentDefinitionDetailsCache.set(id, { data, fetchedAt: Date.now() });
      agentDefinitionDetailsPromises.delete(id);
      return data;
    } catch (error) {
      agentDefinitionDetailsPromises.delete(id);
      throw error;
    }
  })();

  if (!forceRefresh) {
    agentDefinitionDetailsPromises.set(id, request);
  }

  return request;
}

export async function fetchAgentCatalog(forceRefresh = false): Promise<AgentDefinitionDetails[]> {
  const summaries = await fetchAgentDefinitions(forceRefresh);
  return Promise.all(
    summaries.map((summary) => fetchAgentDefinition(summary.id, { forceRefresh })),
  );
}

export async function createAgentDefinition(
  payload: AgentDefinitionPayload,
): Promise<AgentDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/agents/definitions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentDefinitionDetailsSchema,
    'Не удалось создать определение агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function updateAgentDefinition(
  id: string,
  payload: AgentDefinitionPayload,
): Promise<AgentDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/agents/definitions/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentDefinitionDetailsSchema,
    'Не удалось обновить определение агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function updateAgentDefinitionStatus(
  id: string,
  payload: AgentDefinitionStatusPayload,
): Promise<AgentDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/agents/definitions/${id}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentDefinitionDetailsSchema,
    'Не удалось изменить статус агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function createAgentVersion(
  definitionId: string,
  payload: AgentVersionPayload,
): Promise<AgentVersionResponse> {
  const response = await fetch(`${API_BASE_URL}/agents/definitions/${definitionId}/versions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentVersionSchema,
    'Не удалось создать версию агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function updateAgentVersion(
  versionId: string,
  payload: AgentVersionUpdatePayload,
): Promise<AgentVersionResponse> {
  const response = await fetch(`${API_BASE_URL}/agents/versions/${versionId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentVersionSchema,
    'Не удалось обновить версию агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function publishAgentVersion(
  versionId: string,
  payload: AgentVersionPublishPayload,
): Promise<AgentVersionResponse> {
  const response = await fetch(`${API_BASE_URL}/agents/versions/${versionId}/publish`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = await parseJsonResponse(
    response,
    AgentVersionSchema,
    'Не удалось опубликовать версию агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function deprecateAgentVersion(
  versionId: string,
): Promise<AgentVersionResponse> {
  const response = await fetch(`${API_BASE_URL}/agents/versions/${versionId}/deprecate`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
    },
  });
  const data = await parseJsonResponse(
    response,
    AgentVersionSchema,
    'Не удалось депрецировать версию агента',
  );
  invalidateAgentCatalogCache();
  return data;
}

export async function requestSync(
  payload: ChatSyncRequest,
): Promise<SyncCallResult> {
  const response = await fetch(CHAT_SYNC_URL, {
    method: 'POST',
    headers: buildActiveProfileHeaders({
      'Content-Type': 'application/json',
      Accept: 'application/json',
    }),
    body: JSON.stringify(payload),
  });

  const raw = await response.text();

  if (!response.ok) {
    let message = `Не удалось получить ответ (статус ${response.status})`;
    const parsed = tryParseJson(raw);
    if (parsed && typeof parsed === 'object' && 'message' in parsed) {
      message = String((parsed as { message?: unknown }).message);
    } else if (raw) {
      message = raw;
    }
    throw new Error(message);
  }

  const parsedBody = parseJsonBodyOrThrow(raw, 'Не удалось обработать ответ sync API');
  const body = validateWithSchema(
    parsedBody,
    ChatSyncResponseSchema,
    'Не удалось обработать ответ sync API',
  );
  const sessionId = response.headers.get('X-Session-Id') ?? undefined;
  const newSessionHeader = response.headers.get('X-New-Session');
  const newSession = newSessionHeader
    ? newSessionHeader.toLowerCase() === 'true'
    : undefined;

  const sanitizedBody: ChatSyncResponse = {
    ...body,
    provider: normalizeStructuredProvider(body.provider),
    tools: body.tools && body.tools.length > 0 ? [...body.tools] : undefined,
    structured: normalizeStructuredResponse(body.structured ?? null) ?? null,
    usage: normalizeUsage(body.usage),
    cost: normalizeCost(body.cost),
    latencyMs: body.latencyMs ?? undefined,
  };

  return { body: sanitizedBody, sessionId, newSession };
}

export async function requestStructuredSync(
  payload: ChatSyncRequest,
): Promise<StructuredSyncCallResult> {
  const response = await fetch(CHAT_STRUCTURED_SYNC_URL, {
    method: 'POST',
    headers: buildActiveProfileHeaders({
      'Content-Type': 'application/json',
      Accept: 'application/json',
    }),
    body: JSON.stringify(payload),
  });

  const raw = await response.text();

  if (!response.ok) {
    let message = `Не удалось получить структурированный ответ (статус ${response.status})`;
    const parsed = tryParseJson(raw);
    if (parsed && typeof parsed === 'object' && 'message' in parsed) {
      message = String((parsed as { message?: unknown }).message);
    } else if (raw) {
      message = raw;
    }

    throw new Error(message);
  }

  const parsedBody = parseJsonBodyOrThrow(
    raw,
    'Не удалось обработать структурированный ответ',
  );
  const body = validateWithSchema(
    parsedBody,
    StructuredSyncResponseSchema,
    'Не удалось обработать структурированный ответ',
  );
  const sessionId = response.headers.get('X-Session-Id') ?? undefined;
  const newSessionHeader = response.headers.get('X-New-Session');
  const newSession = newSessionHeader
    ? newSessionHeader.toLowerCase() === 'true'
    : undefined;

  const sanitizedBody: StructuredSyncResponse = {
    ...body,
    provider: normalizeStructuredProvider(body.provider),
    tools: body.tools && body.tools.length > 0 ? [...body.tools] : undefined,
    usage: normalizeUsage(body.usage),
    cost: normalizeCost(body.cost),
    answer: normalizeStructuredAnswer(body.answer),
    latencyMs: body.latencyMs ?? undefined,
  };

  return { body: sanitizedBody, sessionId, newSession };
}

export async function startFlow(
  flowDefinitionId: string,
  payload?: FlowLaunchPayload,
): Promise<FlowStartResponse> {
  const normalizedPayload =
    payload != null ? FlowLaunchPayloadSchema.parse(payload) : {};

  const response = await fetch(
    `${API_BASE_URL}/flows/${flowDefinitionId}/start`,
    {
      method: 'POST',
      headers: buildActiveProfileHeaders({
        'Content-Type': 'application/json',
        Accept: 'application/json',
      }),
      body: JSON.stringify(normalizedPayload),
    },
  );

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось запустить флоу (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }

  const raw = await response.json();
  return parseFlowStartResponse(raw);
}

export async function fetchFlowSnapshot(
  sessionId: string,
): Promise<FlowStatusResponse> {
  const response = await fetch(`${API_BASE_URL}/flows/${sessionId}/snapshot`, {
    headers: buildActiveProfileHeaders({ Accept: 'application/json' }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Не удалось загрузить статус флоу (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }

  const raw = await response.json();
  return parseFlowStatusResponse(raw);
}

export async function pollFlowStatus(
  sessionId: string,
  params: {
    sinceEventId?: number;
    stateVersion?: number;
    timeoutMs?: number;
  } = {},
): Promise<FlowStatusResponse | null> {
  const query = new URLSearchParams();
  if (params.sinceEventId != null) {
    query.set('sinceEventId', String(params.sinceEventId));
  }
  if (params.stateVersion != null) {
    query.set('stateVersion', String(params.stateVersion));
  }
  if (params.timeoutMs != null) {
    query.set('timeout', String(params.timeoutMs));
  }

  const url = `${API_BASE_URL}/flows/${sessionId}${
    query.toString() ? `?${query.toString()}` : ''
  }`;

  const response = await fetch(url, {
    headers: buildActiveProfileHeaders({ Accept: 'application/json' }),
  });

  if (response.status === 204) {
    return null;
  }

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Ошибка при опросе статуса (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }

  const raw = await response.json();
  return parseFlowStatusResponse(raw);
}

export async function fetchFlowInteractions(
  sessionId: string,
): Promise<FlowInteractionListResponse> {
  const response = await fetch(`${API_BASE_URL}/flows/${sessionId}/interactions`, {
    headers: buildActiveProfileHeaders({ Accept: 'application/json' }),
  });

  return parseJsonResponse(
    response,
    FlowInteractionListSchema,
    'Не удалось загрузить интерактивные запросы',
  );
}

export async function respondToFlowInteraction(
  sessionId: string,
  requestId: string,
  payload: FlowInteractionRespondPayload,
): Promise<FlowInteractionItemDto> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${sessionId}/interactions/${requestId}/respond`,
    {
      method: 'POST',
      headers: buildActiveProfileHeaders({
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Chat-Session-Id': payload.chatSessionId,
      }),
      body: JSON.stringify(payload),
    },
  );

  return parseJsonResponse(
    response,
    FlowInteractionItemSchema,
    'Не удалось отправить ответ пользователя',
  );
}

export async function skipFlowInteraction(
  sessionId: string,
  requestId: string,
  payload: FlowInteractionRespondPayload,
): Promise<FlowInteractionItemDto> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${sessionId}/interactions/${requestId}/skip`,
    {
      method: 'POST',
      headers: buildActiveProfileHeaders({
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Chat-Session-Id': payload.chatSessionId,
      }),
      body: JSON.stringify(payload),
    },
  );

  return parseJsonResponse(
    response,
    FlowInteractionItemSchema,
    'Не удалось пропустить запрос',
  );
}

export async function autoResolveFlowInteraction(
  sessionId: string,
  requestId: string,
  chatSessionId: string,
  payload: FlowInteractionAutoResolvePayload = {},
): Promise<FlowInteractionItemDto> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${sessionId}/interactions/${requestId}/auto`,
    {
      method: 'POST',
      headers: buildActiveProfileHeaders({
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Chat-Session-Id': chatSessionId,
      }),
      body: JSON.stringify(payload),
    },
  );

  return parseJsonResponse(
    response,
    FlowInteractionItemSchema,
    'Не удалось автозавершить запрос',
  );
}

export async function expireFlowInteraction(
  sessionId: string,
  requestId: string,
  chatSessionId: string,
  payload: FlowInteractionAutoResolvePayload = {},
): Promise<FlowInteractionItemDto> {
  const response = await fetch(
    `${API_BASE_URL}/flows/${sessionId}/interactions/${requestId}/expire`,
    {
      method: 'POST',
      headers: buildActiveProfileHeaders({
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Chat-Session-Id': chatSessionId,
      }),
      body: JSON.stringify(payload),
    },
  );

  return parseJsonResponse(
    response,
    FlowInteractionItemSchema,
    'Не удалось пометить запрос как просроченный',
  );
}

export type FlowEventsStreamHandlers = {
  onFlow: (payload: FlowStatusResponse) => void;
  onHeartbeat?: (payload: string) => void;
  onError?: (event: Event) => void;
};

export function subscribeToFlowEvents(
  sessionId: string,
  handlers: FlowEventsStreamHandlers,
): EventSource {
  const source = new EventSource(
    `${API_BASE_URL}/flows/${sessionId}/events/stream`,
  );

  source.addEventListener('flow', (event) => {
    try {
      const parsed = parseFlowStatusResponse(
        JSON.parse((event as MessageEvent).data),
      );
      handlers.onFlow(parsed);
    } catch (error) {
      console.error('Не удалось разобрать событие flow', error);
      handlers.onError?.(event);
    }
  });

  source.addEventListener('heartbeat', (event) => {
    handlers.onHeartbeat?.((event as MessageEvent).data);
  });

  source.addEventListener('error', (event) => {
    handlers.onError?.(event);
  });

  return source;
}

export async function sendFlowControlCommand(
  sessionId: string,
  command: FlowControlCommand,
  payload?: { stepExecutionId?: string },
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/flows/${sessionId}/control`, {
    method: 'POST',
    headers: buildActiveProfileHeaders({
      'Content-Type': 'application/json',
      Accept: 'application/json',
    }),
    body: JSON.stringify({
      command,
      stepExecutionId: payload?.stepExecutionId,
    }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Команда "${command}" завершилась ошибкой (status ${response.status}): ${
        text || response.statusText
      }`,
    );
  }
}

export async function fetchFlowDefinitions(): Promise<FlowDefinitionSummary[]> {
  const response = await fetch(`${API_BASE_URL}/flows/definitions`, {
    headers: buildActiveProfileHeaders({ Accept: 'application/json' }),
  });
  return parseJsonResponse(
    response,
    FlowDefinitionSummaryListSchema,
    'Не удалось загрузить определения флоу',
  );
}

export async function fetchFlowDefinition(
  definitionId: string,
): Promise<FlowDefinitionDetails> {
  const response = await fetch(
    `${API_BASE_URL}/flows/definitions/${definitionId}`,
    {
      headers: buildActiveProfileHeaders({ Accept: 'application/json' }),
    },
  );
  return parseJsonResponse(
    response,
    FlowDefinitionDetailsSchema,
    'Не удалось загрузить определение',
  );
}

export async function fetchFlowDefinitionHistory(
  definitionId: string,
): Promise<FlowDefinitionHistoryEntry[]> {
  const response = await fetch(
    `${API_BASE_URL}/flows/definitions/${definitionId}/history`,
    {
      headers: buildActiveProfileHeaders({ Accept: 'application/json' }),
    },
  );
  return parseJsonResponse(
    response,
    FlowDefinitionHistoryListSchema,
    'Не удалось загрузить историю',
  );
}

export async function createFlowDefinition(payload: {
  name: string;
  description?: string;
  updatedBy?: string;
  definition: FlowDefinitionDocument;
  changeNotes?: string;
  sourceDefinitionId?: string;
}): Promise<FlowDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/flows/definitions`, {
    method: 'POST',
    headers: buildActiveProfileHeaders({
      'Content-Type': 'application/json',
      Accept: 'application/json',
    }),
    body: JSON.stringify(payload),
  });
  return parseJsonResponse(
    response,
    FlowDefinitionDetailsSchema,
    'Не удалось создать определение',
  );
}

export async function updateFlowDefinition(
  definitionId: string,
  payload: {
    description?: string;
    updatedBy?: string;
    changeNotes?: string;
    definition: FlowDefinitionDocument;
  },
): Promise<FlowDefinitionDetails> {
  const response = await fetch(`${API_BASE_URL}/flows/definitions/${definitionId}`, {
    method: 'PUT',
    headers: buildActiveProfileHeaders({
      'Content-Type': 'application/json',
      Accept: 'application/json',
    }),
    body: JSON.stringify(payload),
  });
  return parseJsonResponse(
    response,
    FlowDefinitionDetailsSchema,
    'Не удалось обновить определение',
  );
}

export async function publishFlowDefinition(
  definitionId: string,
  payload: { updatedBy?: string; changeNotes?: string },
): Promise<FlowDefinitionDetails> {
  const response = await fetch(
    `${API_BASE_URL}/flows/definitions/${definitionId}/publish`,
    {
      method: 'POST',
      headers: buildActiveProfileHeaders({
        'Content-Type': 'application/json',
        Accept: 'application/json',
      }),
      body: JSON.stringify(payload),
    },
  );
  return parseJsonResponse(
    response,
    FlowDefinitionDetailsSchema,
    'Не удалось опубликовать определение',
  );
}

export async function fetchFlowLaunchPreview(
  definitionId: string,
): Promise<FlowLaunchPreview> {
  const response = await fetch(
    `${API_BASE_URL}/flows/definitions/${definitionId}/launch-preview`,
    {
      headers: buildActiveProfileHeaders({ Accept: 'application/json' }),
    },
  );

  return parseJsonResponse(
    response,
    FlowLaunchPreviewSchema,
    'Не удалось загрузить информацию о запуске',
  );
}

export async function fetchSessionUsage(
  sessionId: string,
): Promise<SessionUsageResponse> {
  const response = await fetch(`${API_BASE_URL}/llm/sessions/${sessionId}/usage`, {
    headers: buildActiveProfileHeaders({
      Accept: 'application/json',
    }),
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(
      message || `Не удалось загрузить статистику использования (статус ${response.status}).`,
    );
  }

  return response.json() as Promise<SessionUsageResponse>;
}
