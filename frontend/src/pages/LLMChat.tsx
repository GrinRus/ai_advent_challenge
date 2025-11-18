import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ChangeEvent, FormEvent, KeyboardEvent } from 'react';
import { NavLink } from 'react-router-dom';
import ActiveProfileBanner from '../components/ActiveProfileBanner';
import {
  API_BASE_URL,
  CHAT_STREAM_URL,
  fetchChatProviders,
  fetchMcpCatalog,
  fetchSessionUsage,
  parseMcpHealthEvent,
  requestSync,
  requestStructuredSync,
  type ChatProvidersResponse,
  type ChatSyncResponse,
  type McpToolSummary,
  type McpCatalogResponse,
  type McpHealthEvent,
  type McpServerSummary,
  type SessionUsageResponse,
  type SessionUsageTotals,
  type StructuredSyncResponse,
  type StructuredSyncUsageStats,
  type UsageCostDetails,
} from '../lib/apiClient';
import { buildActiveProfileHeaders } from '../lib/profileStore';
import {
  SAMPLING_RANGE,
  buildChatPayload,
  diffOverridesAgainstDefaults,
  hasOverrides as hasSamplingOverrides,
  type SamplingDefaults,
  type SamplingKey,
  type SamplingOverrides,
  type SamplingSnapshot,
} from '../lib/chatPayload';
import './LLMChat.css';

const computeTotalsFromMessages = (
  items: ChatMessage[],
): SessionUsageTotals | undefined => {
  let promptTokens = 0;
  let completionTokens = 0;
  let totalTokens = 0;
  let hasPrompt = false;
  let hasCompletion = false;
  let hasTotal = false;

  let inputCost = 0;
  let outputCost = 0;
  let hasInputCost = false;
  let hasOutputCost = false;
  const currencies = new Set<string>();

  items.forEach((message) => {
    if (message.role !== 'assistant') {
      return;
    }
    const usage = message.usage;
    if (usage?.promptTokens != null) {
      promptTokens += usage.promptTokens;
      hasPrompt = true;
    }
    if (usage?.completionTokens != null) {
      completionTokens += usage.completionTokens;
      hasCompletion = true;
    }
    if (usage?.totalTokens != null) {
      totalTokens += usage.totalTokens;
      hasTotal = true;
    }

    const cost = message.cost;
    if (cost?.input != null) {
      inputCost += cost.input;
      hasInputCost = true;
    }
    if (cost?.output != null) {
      outputCost += cost.output;
      hasOutputCost = true;
    }
    if (cost?.currency) {
      currencies.add(cost.currency);
    }
  });

  const resolvedTotalTokens = hasTotal
    ? totalTokens
    : hasPrompt || hasCompletion
    ? promptTokens + completionTokens
    : undefined;

  const usageTotals: StructuredSyncUsageStats | undefined =
    hasPrompt || hasCompletion || hasTotal
      ? {
          promptTokens: hasPrompt ? promptTokens : undefined,
          completionTokens: hasCompletion ? completionTokens : undefined,
          totalTokens: resolvedTotalTokens,
        }
      : undefined;

  const hasAnyCost = hasInputCost || hasOutputCost;
  const totalCostValue =
    (hasInputCost ? inputCost : 0) + (hasOutputCost ? outputCost : 0);
  const costTotals: UsageCostDetails | undefined = hasAnyCost
    ? {
        input: hasInputCost ? Number(inputCost.toFixed(8)) : undefined,
        output: hasOutputCost ? Number(outputCost.toFixed(8)) : undefined,
        total: Number(totalCostValue.toFixed(8)),
        currency:
          currencies.size === 1 ? Array.from(currencies)[0] : undefined,
      }
    : undefined;

  if (!usageTotals && !costTotals) {
    return undefined;
  }

  return { usage: usageTotals, cost: costTotals };
};

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  status?: 'streaming' | 'complete';
  provider?: string;
  model?: string;
  mode?: 'stream' | 'sync' | 'structured';
  tools?: string[] | null;
  structured?: StructuredSyncResponse | null;
  sync?: ChatSyncResponse | null;
  relatedMessageId?: string;
  options?: SamplingSnapshot | null;
  usage?: StructuredSyncUsageStats | null;
  cost?: UsageCostDetails | null;
  latencyMs?: number | null;
  timestamp?: string | null;
  usageSource?: string | null;
};

type StreamPayload = {
  sessionId?: string;
  type?: 'session' | 'token' | 'complete' | 'error';
  content?: string | null;
  newSession?: boolean;
  provider?: string | null;
  model?: string | null;
   tools?: string[] | null;
  usage?: StructuredSyncUsageStats | null;
  cost?: UsageCostDetails | null;
  usageSource?: string | null;
};

type DrainResult = {
  buffer: string;
  stop: boolean;
};

type ActiveTab = 'stream' | 'sync' | 'structured';

const SAMPLING_KEYS: SamplingKey[] = ['temperature', 'topP', 'maxTokens'];

const DEFAULT_SAMPLING_DISPLAY: Record<SamplingKey, number> = {
  temperature: 0.7,
  topP: 1,
  maxTokens: 1024,
};

const SAMPLING_LABELS: Record<SamplingKey, string> = {
  temperature: 'Temp',
  topP: 'TopP',
  maxTokens: 'Max',
};

const clamp = (value: number, min: number, max: number) =>
  Math.min(Math.max(value, min), max);

const approxEqual = (first?: number | null, second?: number | null) => {
  if (first == null || second == null) {
    return false;
  }
  return Math.abs(first - second) < 1e-9;
};

const formatOptionValue = (key: SamplingKey, value?: number | null) => {
  if (value == null || Number.isNaN(value)) {
    return '—';
  }
  if (key === 'maxTokens') {
    return Math.round(value).toString();
  }
  return value.toFixed(2).replace(/\.0+$/, '').replace(/\.([1-9])0$/, '.$1');
};

const formatSamplingSummary = (options?: SamplingSnapshot | null) =>
  SAMPLING_KEYS.map((key) => `${SAMPLING_LABELS[key]} ${formatOptionValue(key, options?.[key])}`).join(' · ');

const computeDisplayValues = (defaults: SamplingDefaults | null) => ({
  temperature:
      defaults?.temperature != null
        ? defaults.temperature
        : DEFAULT_SAMPLING_DISPLAY.temperature,
  topP:
      defaults?.topP != null
        ? defaults.topP
        : DEFAULT_SAMPLING_DISPLAY.topP,
  maxTokens:
      defaults?.maxTokens != null
        ? defaults.maxTokens
        : DEFAULT_SAMPLING_DISPLAY.maxTokens,
});

const formatConfidence = (value?: number | null) => {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '—';
  }
  return `${Math.round(value * 100)}%`;
};

const formatTimestamp = (iso?: string | null) => {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString();
};

const formatTokens = (value?: number | null) => {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '—';
  }
  return value.toLocaleString();
};

const formatCost = (value?: number | null) => {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '—';
  }
  const absolute = Math.abs(value);
  if (absolute >= 1) {
    return value.toFixed(2);
  }
  if (absolute >= 0.01) {
    return value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
  }
  return value.toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
};

const formatPricePer1K = (value?: number | null, currency = 'USD') => {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '—';
  }
  const absolute = Math.abs(value);
  let formatted: string;
  if (absolute >= 1) {
    formatted = value.toFixed(2);
  } else if (absolute >= 0.01) {
    formatted = value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
  } else {
    formatted = value.toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
  }
  return `$${formatted}${currency && currency !== 'USD' ? ` ${currency}` : ''}`;
};

const formatProviderType = (type?: string | null) => {
  if (!type) {
    return '—';
  }
  const normalized = type.replace(/_/g, ' ').toLowerCase();
  return normalized.charAt(0).toUpperCase() + normalized.slice(1);
};

const generateId = () =>
  typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `id-${Date.now()}-${Math.random().toString(16).slice(2)}`;

const MODEL_SEGMENTS: Record<
  string,
  { segmentId: string; label: string; order: number; description?: string }
> = {
  'openai:gpt-5-nano': {
    segmentId: 'economy',
    label: 'Economy',
    order: 1,
    description: 'минимальная стоимость, ограниченный вывод',
  },
  'openai:gpt-4o-mini': {
    segmentId: 'value',
    label: 'Value',
    order: 2,
    description: 'баланс цена/качество для ежедневного чата',
  },
  'openai:gpt-5': {
    segmentId: 'flagship',
    label: 'Flagship',
    order: 3,
    description: 'флагманское качество, повышенная цена',
  },
  'zhipu:[glm-4-32b-0414-128k]': {
    segmentId: 'alt',
    label: 'Alt · Sync only',
    order: 4,
    description: 'ZhiPu flat pricing, только sync, 128K контекст',
  },
};

const SEGMENT_PRIORITY: Record<string, number> = {
  economy: 10,
  budget: 20,
  value: 30,
  standard: 40,
  pro: 50,
  flagship: 60,
  alt: 70,
  other: 80,
};

type ProviderModel = ChatProvidersResponse['providers'][number]['models'][number];

const capitalize = (value?: string | null) => {
  if (!value) {
    return '';
  }
  return value.charAt(0).toUpperCase() + value.slice(1);
};

const describeUsageSource = (source?: string | null) => {
  if (!source) {
    return null;
  }
  const normalized = source.toLowerCase();
  switch (normalized) {
    case 'native':
      return { label: 'провайдер', variant: 'native' as const };
    case 'fallback':
      return { label: 'fallback (jtokkit)', variant: 'fallback' as const };
    default:
      return { label: normalized, variant: 'unknown' as const };
  }
};

const formatContextWindow = (value?: number | null) => {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (value >= 1000 && value % 1000 === 0) {
    return `${value / 1000}K ctx`;
  }
  return `${value.toLocaleString()} ctx`;
};

const formatMaxOutputTokens = (value?: number | null) => {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (value >= 1000 && value % 1000 === 0) {
    return `max ${value / 1000}K out`;
  }
  return `max ${value.toLocaleString()} out`;
};

const getSegmentMeta = (
  providerId: string | undefined,
  model: ProviderModel | null | undefined,
) => {
  if (!providerId || !model) {
    return undefined;
  }
  return MODEL_SEGMENTS[`${providerId}:${model.id}`];
};

const buildModelOptionLabel = (
  providerId: string,
  model: ProviderModel,
) => {
  const segmentMeta = getSegmentMeta(providerId, model);
  const baseName = model.displayName ?? model.id;
  const currency = model.currency ?? 'USD';
  const pricePart =
    model.inputPer1KTokens != null || model.outputPer1KTokens != null
      ? `${formatPricePer1K(model.inputPer1KTokens, currency)}/${formatPricePer1K(model.outputPer1KTokens, currency)}`
      : undefined;
  const contextPart = formatContextWindow(model.contextWindow);
  const maxOutputPart = formatMaxOutputTokens(model.maxOutputTokens);
  const streamingPart = model.streamingEnabled === false ? 'sync only' : undefined;

  const descriptor =
    segmentMeta?.label ?? (model.tier ? capitalize(model.tier) : undefined);

  const details = [descriptor, pricePart, contextPart, maxOutputPart, streamingPart]
    .filter(Boolean)
    .join(' · ');

  return details ? `${baseName} · ${details}` : baseName;
};

const MCP_POLL_INTERVAL_MS = 20_000;
const MCP_SSE_RETRY_DELAY_MS = 30_000;

type McpHealthSnapshot = {
  status: McpServerSummary['status'];
  timestamp: string;
  availableTools: string[];
  unavailableTools: string[];
};

const normalizeToolCode = (code: string | null | undefined) =>
  code ? code.trim().toLowerCase() : '';

const formatMcpStatusLabel = (status: McpServerSummary['status']) => {
  switch (status) {
    case 'UP':
      return 'В работе';
    case 'DOWN':
      return 'Недоступен';
    default:
      return 'Неизвестно';
  }
};

const STATUS_BADGE_CLASS: Record<McpServerSummary['status'], string> = {
  UP: 'status-up',
  DOWN: 'status-down',
  UNKNOWN: 'status-unknown',
};

type ToolDescriptor = {
  code: string;
  label: string;
  serverLabel?: string;
  status: McpServerSummary['status'];
  available: boolean;
};

const parseStreamPayload = (rawEvent: string): StreamPayload | null => {
  const lines = rawEvent.split('\n');
  let declaredEvent: string | undefined;
  const dataLines: string[] = [];

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();
    if (!line || line.startsWith(':')) {
      continue;
    }

    if (line.startsWith('event:')) {
      declaredEvent = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim());
    }
  }

  if (dataLines.length === 0) {
    return null;
  }

  try {
    const payload = JSON.parse(dataLines.join('\n')) as StreamPayload;
    if (declaredEvent && !payload.type) {
      payload.type = declaredEvent as StreamPayload['type'];
    }
    return payload;
  } catch {
    return null;
  }
};

const drainSseBuffer = (
  buffer: string,
  onEvent: (payload: StreamPayload) => boolean | void,
): DrainResult => {
  let working = buffer;
  let stop = false;

  while (true) {
    const boundary = working.indexOf('\n\n');
    if (boundary === -1) {
      break;
    }

    const rawEvent = working.slice(0, boundary);
    working = working.slice(boundary + 2);

    const payload = parseStreamPayload(rawEvent);
    if (!payload || !payload.type) {
      continue;
    }

    const shouldStop = onEvent(payload);
    if (shouldStop) {
      stop = true;
      break;
    }
  }

  return { buffer: working, stop };
};

type SyncResponseCardProps = {
  message: ChatMessage;
  providerLabel?: string;
  modelLabel?: string;
  optionsLabel?: string;
  toolDescriptors?: ToolDescriptor[];
};

const SyncResponseCard = ({
  message,
  providerLabel = '—',
  modelLabel = '—',
  optionsLabel,
  toolDescriptors = [],
}: SyncResponseCardProps) => {
  const derivedTotal =
    message.cost?.total ??
    (message.cost
      ? (message.cost.input ?? 0) + (message.cost.output ?? 0)
      : null);
  const currencySuffix = message.cost?.currency ? ` ${message.cost.currency}` : '';

  return (
    <div className="structured-response-card" data-testid="sync-response-card">
      <div className="structured-summary">
        <div className="structured-summary-header">
          <h3>Синхронный ответ</h3>
        </div>
        <p data-testid="sync-summary-text">
          {message.content || 'Модель вернула пустой ответ.'}
        </p>
        {optionsLabel && (
          <span className="structured-options" data-testid="sync-options">
            {optionsLabel}
          </span>
        )}
        {toolDescriptors.length > 0 && (
          <div className="structured-tools">
            {toolDescriptors.map((tool) => (
              <span
                key={`${tool.code}-${tool.serverLabel ?? 'sync'}`}
                className={`llm-chat-tool-badge ${STATUS_BADGE_CLASS[tool.status]}`}
              >
                <span className="llm-chat-tool-badge-icon" aria-hidden="true">
                  🔧
                </span>
                <span className="llm-chat-tool-badge-label">{tool.label}</span>
                {tool.serverLabel && (
                  <span className="llm-chat-tool-badge-server">{tool.serverLabel}</span>
                )}
              </span>
            ))}
          </div>
        )}
      </div>

      <div className="structured-metadata">
        <div className="structured-metadata-group">
          <span className="structured-label">Провайдер</span>
          <span className="structured-value">{providerLabel || '—'}</span>
        </div>
        <div className="structured-metadata-group">
          <span className="structured-label">Модель</span>
          <span className="structured-value">{modelLabel || '—'}</span>
        </div>
        <div className="structured-metadata-group">
          <span className="structured-label">Задержка</span>
          <span className="structured-value">
            {message.latencyMs != null ? `${message.latencyMs} мс` : '—'}
          </span>
        </div>
        <div className="structured-metadata-group">
          <span className="structured-label">Время</span>
          <span className="structured-value">
            {formatTimestamp(message.timestamp ?? undefined)}
          </span>
        </div>
        {message.cost && (
          <div className="structured-metadata-group">
            <span className="structured-label">Стоимость</span>
            <span className="structured-value">
              {formatCost(message.cost.input)} / {formatCost(message.cost.output)} /{' '}
              {formatCost(derivedTotal)}
              {currencySuffix}
            </span>
          </div>
        )}
      </div>

      {message.usage && (
        <div className="structured-usage">
          <div className="structured-usage-metric">
            <span className="structured-label">Prompt</span>
            <span className="structured-value">
              {formatTokens(message.usage.promptTokens)}
            </span>
          </div>
          <div className="structured-usage-metric">
            <span className="structured-label">Completion</span>
            <span className="structured-value">
              {formatTokens(message.usage.completionTokens)}
            </span>
          </div>
          <div className="structured-usage-metric">
            <span className="structured-label">Total</span>
            <span className="structured-value">
              {formatTokens(message.usage.totalTokens)}
            </span>
          </div>
        </div>
      )}
    </div>
  );
};

type StructuredResponseCardProps = {
  response: StructuredSyncResponse;
  providerLabel?: string;
  modelLabel?: string;
  highlight?: boolean;
  onSelect?: () => void;
  optionsLabel?: string;
  toolDescriptors?: ToolDescriptor[];
};

const StructuredResponseCard = ({
  response,
  providerLabel = '—',
  modelLabel = '—',
  highlight,
  onSelect,
  optionsLabel,
  toolDescriptors = [],
}: StructuredResponseCardProps) => {
  const className = [
    'structured-response-card',
    highlight ? 'highlight' : '',
    onSelect ? 'interactive' : '',
  ]
    .filter(Boolean)
    .join(' ');
  const prettyPayload = useMemo(
    () => JSON.stringify(response, null, 2),
    [response],
  );

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!onSelect) {
      return;
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelect();
    }
  };

  return (
    <div
      className={className}
      onClick={onSelect ?? undefined}
      onKeyDown={handleKeyDown}
      role={onSelect ? 'button' : undefined}
      tabIndex={onSelect ? 0 : undefined}
      data-testid="structured-response-card"
    >
      <div className="structured-summary">
        <div className="structured-summary-header">
          <h3>Структурированный ответ</h3>
          {response.status && (
            <span className="structured-status">
              {response.status.toUpperCase()}
            </span>
          )}
        </div>
        <p data-testid="structured-summary-text">
          {response.answer?.summary ?? 'Модель не вернула summary.'}
        </p>
        {optionsLabel && (
          <span className="structured-options" data-testid="structured-options">
            {optionsLabel}
          </span>
        )}
        {toolDescriptors.length > 0 && (
          <div className="structured-tools">
            {toolDescriptors.map((tool) => (
              <span
                key={`${tool.code}-${tool.serverLabel ?? 'structured'}`}
                className={`llm-chat-tool-badge ${STATUS_BADGE_CLASS[tool.status]}`}
              >
                <span className="llm-chat-tool-badge-icon" aria-hidden="true">
                  🔧
                </span>
                <span className="llm-chat-tool-badge-label">{tool.label}</span>
                {tool.serverLabel && (
                  <span className="llm-chat-tool-badge-server">{tool.serverLabel}</span>
                )}
              </span>
            ))}
          </div>
        )}
        {response.answer?.confidence !== undefined && (
          <span className="structured-confidence">
            Уверенность: {formatConfidence(response.answer?.confidence)}
          </span>
        )}
      </div>

      {response.answer?.items && response.answer.items.length > 0 ? (
        <div className="structured-items">
          {response.answer.items.map((item, index) => (
            <div
              key={`${item.title ?? 'item'}-${index}`}
              className="structured-item"
              data-testid="structured-item"
            >
              {item.title && (
                <h4 className="structured-item-title">{item.title}</h4>
              )}
              {item.details && (
                <p className="structured-item-details">{item.details}</p>
              )}
              {item.tags && item.tags.length > 0 && (
                <div className="structured-item-tags">
                  {item.tags.map((tag) => (
                    <span key={`${tag}-${index}`} className="structured-tag">
                      #{tag}
                    </span>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      ) : null}

      <div className="structured-metadata">
        <div className="structured-metadata-group">
          <span className="structured-label">Провайдер</span>
          <span className="structured-value">
            {formatProviderType(response.provider?.type)}
          </span>
        </div>
        <div className="structured-metadata-group">
          <span className="structured-label">Отображение</span>
          <span className="structured-value">{providerLabel || '—'}</span>
        </div>
        <div className="structured-metadata-group">
          <span className="structured-label">Модель</span>
          <span className="structured-value">{modelLabel || '—'}</span>
        </div>
        <div className="structured-metadata-group">
          <span className="structured-label">Задержка</span>
          <span className="structured-value">
            {response.latencyMs !== undefined
              ? `${response.latencyMs} мс`
              : '—'}
          </span>
        </div>
        <div className="structured-metadata-group">
          <span className="structured-label">Время</span>
          <span className="structured-value">
            {formatTimestamp(response.timestamp)}
          </span>
        </div>
      </div>

      <div className="structured-usage">
        <div className="structured-usage-metric">
          <span className="structured-label">Prompt</span>
          <span className="structured-value">
            {formatTokens(response.usage?.promptTokens)}
          </span>
        </div>
        <div className="structured-usage-metric">
          <span className="structured-label">Completion</span>
          <span className="structured-value">
            {formatTokens(response.usage?.completionTokens)}
          </span>
        </div>
        <div className="structured-usage-metric">
          <span className="structured-label">Total</span>
          <span className="structured-value">
            {formatTokens(response.usage?.totalTokens)}
          </span>
        </div>
      </div>

      <details className="structured-raw">
        <summary>Показать исходный payload</summary>
        <pre>{prettyPayload}</pre>
      </details>
    </div>
  );
};

export default function LLMChat() {
  const [activeTab, setActiveTab] = useState<ActiveTab>('stream');
  const [providerCatalog, setProviderCatalog] = useState<ChatProvidersResponse | null>(null);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const [selectedModel, setSelectedModel] = useState<string>('');
  const [interactionMode, setInteractionMode] = useState<'default' | 'research'>(
    'default',
  );
  const [isCatalogLoading, setIsCatalogLoading] = useState<boolean>(true);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [lastProvider, setLastProvider] = useState<string | null>(null);
  const [lastModel, setLastModel] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [pendingInteractions, setPendingInteractions] = useState(0);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [syncInput, setSyncInput] = useState('');
  const [isSyncLoading, setIsSyncLoading] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [syncNotice, setSyncNotice] = useState<string | null>(null);
  const [structuredInput, setStructuredInput] = useState('');
  const [activeStructuredMessageId, setActiveStructuredMessageId] = useState<string | null>(null);
  const [isStructuredLoading, setIsStructuredLoading] = useState(false);
  const [structuredError, setStructuredError] = useState<string | null>(null);
  const [structuredNotice, setStructuredNotice] = useState<string | null>(null);
  const [samplingDefaults, setSamplingDefaults] = useState<SamplingDefaults | null>(null);
  const [samplingDisplay, setSamplingDisplay] = useState<Record<SamplingKey, number>>(
    DEFAULT_SAMPLING_DISPLAY,
  );
  const [samplingOverrides, setSamplingOverrides] = useState<SamplingOverrides>({});
  const [sessionUsage, setSessionUsage] = useState<SessionUsageResponse | null>(null);
  const [isSessionUsageLoading, setIsSessionUsageLoading] = useState<boolean>(false);
  const [sessionUsageError, setSessionUsageError] = useState<string | null>(null);
  const [mcpCatalog, setMcpCatalog] = useState<McpCatalogResponse | null>(null);
  const [isMcpLoading, setIsMcpLoading] = useState<boolean>(true);
  const [mcpError, setMcpError] = useState<string | null>(null);
  const [selectedMcpServers, setSelectedMcpServers] = useState<string[]>([]);
  const [mcpHealthMap, setMcpHealthMap] = useState<Record<string, McpHealthSnapshot>>({});
  const abortControllerRef = useRef<AbortController | null>(null);
  const messagesContainerRef = useRef<HTMLDivElement | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const mcpEventSourceRef = useRef<EventSource | null>(null);
  const mcpPollTimerRef = useRef<number | null>(null);
  const mcpSseRetryRef = useRef<number | null>(null);
  const mcpToolIndex = useMemo(() => {
    if (!mcpCatalog) {
      return new Map<string, { serverId: string; serverLabel: string; tool: McpToolSummary }>();
    }
    const map = new Map<string, { serverId: string; serverLabel: string; tool: McpToolSummary }>();
    mcpCatalog.servers.forEach((server) => {
      const serverLabel = server.displayName ?? server.id;
      server.tools.forEach((tool) => {
        map.set(normalizeToolCode(tool.code), { serverId: server.id, serverLabel, tool });
      });
    });
    return map;
  }, [mcpCatalog]);

  const updateActiveTabForModel = useCallback((model: ProviderModel | null) => {
    if (!model) {
      return;
    }
    const supportsStream = model.streamingEnabled !== false;
    const supportsSync = model.syncEnabled !== false;
    const supportsStructured = model.structuredEnabled !== false;

    const currentSupported =
      (activeTab === 'stream' && supportsStream) ||
      (activeTab === 'sync' && supportsSync) ||
      (activeTab === 'structured' && supportsStructured);

    if (currentSupported) {
      return;
    }

    if (supportsStream) {
      setActiveTab('stream');
      setInfo(null);
      return;
    }
    if (supportsSync) {
      setActiveTab('sync');
      setInfo(null);
      return;
    }
    if (supportsStructured) {
      setActiveTab('structured');
      setInfo(null);
    }
  }, [activeTab]);

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<{ count?: number }>).detail;
      setPendingInteractions(Math.max(0, detail?.count ?? 0));
    };
    window.addEventListener('flow-interactions-badge', handler as EventListener);
    return () => {
      window.removeEventListener('flow-interactions-badge', handler as EventListener);
    };
  }, [updateActiveTabForModel]);

  useEffect(() => {
    let isCancelled = false;

    const loadProviders = async () => {
      try {
        setCatalogError(null);
        setIsCatalogLoading(true);

        const catalog = await fetchChatProviders();
        if (isCancelled) {
          return;
        }

        if (!catalog.providers || catalog.providers.length === 0) {
          throw new Error('Провайдеры не сконфигурированы на сервере.');
        }

        const defaultProvider =
          catalog.providers.find((provider) => provider.id === catalog.defaultProvider) ??
          catalog.providers[0];

        const defaultModel =
          defaultProvider.models.find((model) => model.id === defaultProvider.defaultModel) ??
          defaultProvider.models[0] ??
          null;

        setProviderCatalog(catalog);
        setSelectedProvider(defaultProvider.id);
        setSelectedModel(defaultModel ? defaultModel.id : '');

        const providerDefaults: SamplingDefaults = {
          temperature: defaultProvider.temperature ?? null,
          topP: defaultProvider.topP ?? null,
          maxTokens: defaultProvider.maxTokens ?? null,
        };
        setSamplingDefaults(providerDefaults);
        setSamplingDisplay(computeDisplayValues(providerDefaults));
        setSamplingOverrides({});
        updateActiveTabForModel(defaultModel);
      } catch (loadError) {
        if (isCancelled) {
          return;
        }
        const message =
          loadError instanceof Error
            ? loadError.message
            : 'Неизвестная ошибка при загрузке провайдеров.';
        setCatalogError(`Не удалось загрузить провайдеры: ${message}`);
      } finally {
        if (!isCancelled) {
          setIsCatalogLoading(false);
        }
      }
    };

    loadProviders();

    return () => {
      isCancelled = true;
    };
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    try {
      const stored = window.sessionStorage.getItem('llm-chat-mcp-selection');
      if (stored) {
        const parsed = JSON.parse(stored);
        if (Array.isArray(parsed)) {
          setSelectedMcpServers(parsed.filter((value) => typeof value === 'string'));
        }
      }
    } catch (storageError) {
      // eslint-disable-next-line no-console
      console.debug('Не удалось загрузить сохранённый выбор MCP', storageError);
    }
  }, []);

  useEffect(() => {
    if (!mcpCatalog) {
      return;
    }
    const nowIso = new Date().toISOString();
    setMcpHealthMap((_prev) => {
      const next: Record<string, McpHealthSnapshot> = {};
      mcpCatalog.servers.forEach((server) => {
        const available = server.tools
          .filter((tool) => tool.available)
          .map((tool) => tool.code);
        const unavailable = server.tools
          .filter((tool) => !tool.available)
          .map((tool) => tool.code);
        next[server.id] = {
          status: server.status,
          timestamp: nowIso,
          availableTools: available,
          unavailableTools: unavailable,
        };
      });
      return next;
    });
    setSelectedMcpServers((prev) =>
      prev.filter((serverId) =>
        mcpCatalog.servers.some((server) => server.id === serverId),
      ),
    );
  }, [mcpCatalog]);

  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    try {
      window.sessionStorage.setItem(
        'llm-chat-mcp-selection',
        JSON.stringify(selectedMcpServers),
      );
    } catch (storageError) {
      // eslint-disable-next-line no-console
      console.debug('Не удалось сохранить выбор MCP', storageError);
    }
  }, [selectedMcpServers]);

  useEffect(() => {
    let isDisposed = false;

    const closeEventSource = () => {
      if (mcpEventSourceRef.current) {
        mcpEventSourceRef.current.close();
        mcpEventSourceRef.current = null;
      }
    };

    const clearPolling = () => {
      if (mcpPollTimerRef.current != null) {
        window.clearInterval(mcpPollTimerRef.current);
        mcpPollTimerRef.current = null;
      }
    };

    const clearRetry = () => {
      if (mcpSseRetryRef.current != null) {
        window.clearTimeout(mcpSseRetryRef.current);
        mcpSseRetryRef.current = null;
      }
    };

    const applyHealthEvent = (event: McpHealthEvent) => {
      if (isDisposed) {
        return;
      }
      const timestamp = event.timestamp ?? new Date().toISOString();
      const availableSet = new Set(event.availableTools.map(normalizeToolCode));
      const unavailableSet = new Set(event.unavailableTools.map(normalizeToolCode));

      setMcpHealthMap((prev) => ({
        ...prev,
        [event.serverId]: {
          status: event.status,
          timestamp,
          availableTools: event.availableTools,
          unavailableTools: event.unavailableTools,
        },
      }));

      setMcpCatalog((prev) => {
        if (!prev) {
          return prev;
        }
        return {
          servers: prev.servers.map((server) => {
            if (server.id !== event.serverId) {
              return server;
            }
            const updatedTools = server.tools.map((tool) => {
              const normalized = normalizeToolCode(tool.code);
              if (availableSet.has(normalized)) {
                return { ...tool, available: true };
              }
              if (unavailableSet.has(normalized)) {
                return { ...tool, available: false };
              }
              return tool;
            });
            return {
              ...server,
              status: event.status,
              tools: updatedTools,
            };
          }),
        };
      });
    };

    const applyCatalogSnapshot = (catalog: McpCatalogResponse) => {
      if (isDisposed) {
        return;
      }
      setMcpCatalog(catalog);
      setIsMcpLoading(false);
      setMcpError(null);
    };

    const pollCatalog = async () => {
      try {
        const catalog = await fetchMcpCatalog();
        applyCatalogSnapshot(catalog);
      } catch (pollError) {
        if (isDisposed) {
          return;
        }
        const message =
          pollError instanceof Error
            ? pollError.message
            : 'Не удалось обновить каталог MCP.';
        setMcpError(message);
      }
    };

    const ensurePolling = () => {
      if (mcpPollTimerRef.current != null) {
        return;
      }
      pollCatalog();
      mcpPollTimerRef.current = window.setInterval(pollCatalog, MCP_POLL_INTERVAL_MS);
    };

    const connectSse = () => {
      if (typeof window === 'undefined' || typeof window.EventSource === 'undefined') {
        ensurePolling();
        return;
      }

      clearPolling();
      clearRetry();
      closeEventSource();

      const source = new EventSource(`${API_BASE_URL}/mcp/events`);
      mcpEventSourceRef.current = source;

      const handleServerEvent = (event: MessageEvent<string>) => {
        try {
          const payload = JSON.parse(event.data);
          const parsed = parseMcpHealthEvent(payload);
          applyHealthEvent(parsed);
          setMcpError(null);
        } catch (parseError) {
          // eslint-disable-next-line no-console
          console.debug('Не удалось обработать событие MCP health', parseError);
        }
      };

      source.addEventListener('mcp-server', handleServerEvent as EventListener);

      source.onerror = () => {
        if (isDisposed) {
          return;
        }
        setMcpError('Поток статусов MCP недоступен, переключаемся на polling.');
        closeEventSource();
        ensurePolling();
        if (mcpSseRetryRef.current == null) {
          mcpSseRetryRef.current = window.setTimeout(() => {
            if (!isDisposed) {
              connectSse();
            }
          }, MCP_SSE_RETRY_DELAY_MS);
        }
      };
    };

    connectSse();

    return () => {
      isDisposed = true;
      closeEventSource();
      clearPolling();
      clearRetry();
    };
  }, []);

  useEffect(() => {
    let isCancelled = false;

    const loadMcpCatalog = async () => {
      try {
        setMcpError(null);
        setIsMcpLoading(true);
        const catalog = await fetchMcpCatalog();
        if (isCancelled) {
          return;
        }
        setMcpCatalog(catalog);
      } catch (loadError) {
        if (isCancelled) {
          return;
        }
        const message =
          loadError instanceof Error
            ? loadError.message
            : 'Не удалось загрузить каталог MCP.';
        setMcpError(message);
      } finally {
        if (!isCancelled) {
          setIsMcpLoading(false);
        }
      }
    };

    loadMcpCatalog();

    return () => {
      isCancelled = true;
    };
  }, []);

  useEffect(() => {
    if (activeTab !== 'stream') {
      return;
    }
    if (messagesContainerRef.current) {
      messagesContainerRef.current.scrollTop =
        messagesContainerRef.current.scrollHeight;
    }
  }, [messages, activeTab]);

  useEffect(() => {
    sessionIdRef.current = sessionId ?? null;
  }, [sessionId]);

  useEffect(() => {
    if (selectedMcpServers.length > 0 && interactionMode !== 'research') {
      setInteractionMode('research');
    }
  }, [selectedMcpServers, interactionMode]);

  const providerOptions = providerCatalog?.providers ?? [];
  const currentProvider =
    providerOptions.find((provider) => provider.id === selectedProvider) ??
    providerOptions[0] ??
    null;
  const modelOptions = currentProvider?.models ?? [];
  const hasModelOptions = modelOptions.length > 0;
  const groupedModelOptions = useMemo(() => {
    if (!currentProvider) {
      return [] as Array<{
        key: string;
        label: string;
        order: number;
        description?: string;
        models: ProviderModel[];
      }>;
    }
    const groups = new Map<
      string,
      { key: string; label: string; order: number; description?: string; models: ProviderModel[] }
    >();
    currentProvider.models.forEach((model) => {
      const segmentMeta = getSegmentMeta(currentProvider.id, model);
      const rawSegment =
        segmentMeta?.segmentId ??
        model.tier ??
        'other';
      const label =
        segmentMeta?.label ??
        (model.tier ? capitalize(model.tier) : 'Other');
      const order =
        segmentMeta?.order ??
        SEGMENT_PRIORITY[rawSegment] ??
        SEGMENT_PRIORITY.other;
      const key = `${rawSegment}:${label}`;
      if (!groups.has(key)) {
        groups.set(key, {
          key,
          label,
          order,
          description: segmentMeta?.description,
          models: [],
        });
      }
      groups.get(key)!.models.push(model);
    });

    const sorted = Array.from(groups.values()).sort(
      (a, b) => a.order - b.order || a.label.localeCompare(b.label, 'ru'),
    );
    sorted.forEach((group) =>
      group.models.sort((a, b) =>
        (a.displayName ?? a.id).localeCompare(b.displayName ?? b.id, 'ru'),
      ),
    );
    return sorted;
  }, [currentProvider]);

  const selectedModelConfig = useMemo<ProviderModel | null>(() => {
    if (!currentProvider) {
      return null;
    }
    const explicit =
      currentProvider.models.find((model) => model.id === selectedModel) ?? null;
    if (explicit) {
      return explicit;
    }
    return currentProvider.models[0] ?? null;
  }, [currentProvider, selectedModel]);

  const selectedSegmentMeta = useMemo(
    () => getSegmentMeta(currentProvider?.id, selectedModelConfig ?? undefined),
    [currentProvider?.id, selectedModelConfig],
  );

  const streamingSupported = selectedModelConfig?.streamingEnabled !== false;
  const syncSupported = selectedModelConfig?.syncEnabled !== false;
  const structuredSupported = selectedModelConfig?.structuredEnabled !== false;
  const canSendMessage =
    Boolean(
      selectedProvider &&
        selectedModel &&
        selectedModelConfig &&
        !catalogError &&
        !isCatalogLoading,
    );

  const samplingControlsDisabled =
    isCatalogLoading || isStreaming || isSyncLoading || isStructuredLoading;

  const normalizedSamplingOverrides = useMemo(
    () => diffOverridesAgainstDefaults(samplingOverrides, samplingDefaults),
    [samplingOverrides, samplingDefaults],
  );

  const samplingOverridesActive = useMemo(
    () => hasSamplingOverrides(normalizedSamplingOverrides),
    [normalizedSamplingOverrides],
  );

  const resolvedInteractionMode = useMemo(
    () => (selectedMcpServers.length > 0 ? 'research' : interactionMode),
    [interactionMode, selectedMcpServers],
  );

  const resolvedRequestedToolCodes = useMemo(() => {
    if (!mcpCatalog || selectedMcpServers.length === 0) {
      return [] as string[];
    }
    const codes = new Set<string>();
    selectedMcpServers.forEach((serverId) => {
      const server = mcpCatalog.servers.find((item) => item.id === serverId);
      if (!server) {
        return;
      }
      server.tools.forEach((tool) => {
        if (tool.code) {
          codes.add(tool.code);
        }
      });
    });
    return Array.from(codes);
  }, [mcpCatalog, selectedMcpServers]);

  const selectedMcpToolCount = resolvedRequestedToolCodes.length;

  const mcpHint = useMemo(() => {
    if (selectedMcpServers.length === 0) {
      return null;
    }
    const offlineServers = selectedMcpServers
      .map((serverId) => {
        const catalogEntry = mcpCatalog?.servers.find((server) => server.id === serverId);
        const health = mcpHealthMap[serverId];
        const status = health?.status ?? catalogEntry?.status ?? 'UNKNOWN';
        return {
          serverId,
          serverLabel: catalogEntry?.displayName ?? catalogEntry?.id ?? serverId,
          status,
        };
      })
      .filter((entry) => entry.status !== 'UP');
    if (offlineServers.length > 0) {
      const names = offlineServers.map((entry) => entry.serverLabel).join(', ');
      return `Подсказка: выбранные MCP сервера недоступны (${names}). Проверьте статус или временно отключите их.`;
    }
    if (selectedMcpToolCount === 0) {
      return 'Подсказка: выбранные MCP сервера не предоставляют активных инструментов. Проверьте каталог или измените выбор.';
    }
    return null;
  }, [selectedMcpServers, mcpCatalog, mcpHealthMap, selectedMcpToolCount]);

  const structuredMessages = messages.filter(
    (message): message is ChatMessage & { structured: StructuredSyncResponse } =>
      message.role === 'assistant' && Boolean(message.structured),
  );

  const streamHasError = Boolean(error);
  const syncHasError = Boolean(syncError);
  const structuredHasError = Boolean(structuredError);

  const syncMessages = useMemo(
    () =>
      messages.filter(
        (message) => message.role === 'assistant' && message.mode === 'sync',
      ),
    [messages],
  );

  const activeStructuredMessage =
    structuredMessages.find(
      (message) => message.id === activeStructuredMessageId,
    ) ??
    (structuredMessages.length > 0
      ? structuredMessages[structuredMessages.length - 1]
      : null);

  const usageTotals = useMemo<SessionUsageTotals | undefined>(() => {
    if (sessionUsage?.totals) {
      return sessionUsage.totals;
    }
    return computeTotalsFromMessages(messages);
  }, [sessionUsage, messages]);

  const segmentSummaryRaw =
    selectedSegmentMeta?.label ??
    (selectedModelConfig?.tier ? capitalize(selectedModelConfig.tier) : undefined);
  const segmentSummary = segmentSummaryRaw ?? '—';

  const selectedCurrency = selectedModelConfig?.currency ?? 'USD';
  const priceInValue = selectedModelConfig
    ? formatPricePer1K(selectedModelConfig.inputPer1KTokens, selectedCurrency)
    : '—';
  const priceOutValue = selectedModelConfig
    ? formatPricePer1K(selectedModelConfig.outputPer1KTokens, selectedCurrency)
    : '—';
  const priceSummary =
    priceInValue === '—' && priceOutValue === '—'
      ? '—'
      : `${priceInValue} / ${priceOutValue} за 1K токенов`;

  const contextSummaryParts = [
    formatContextWindow(selectedModelConfig?.contextWindow),
    formatMaxOutputTokens(selectedModelConfig?.maxOutputTokens),
  ].filter(Boolean);
  const contextSummary =
    contextSummaryParts.length > 0
      ? contextSummaryParts.join(', ')
      : '—';

  const streamingLabel = 'Потоковый';
  const syncLabel = 'Синхронный';
  const structuredLabel = 'Структурированный';
  const supportedModes: string[] = [];
  if (streamingSupported) {
    supportedModes.push(streamingLabel);
  }
  if (syncSupported) {
    supportedModes.push(syncLabel);
  }
  if (structuredSupported) {
    supportedModes.push(structuredLabel);
  }
  const modesSummary = supportedModes.length > 0 ? supportedModes.join(' + ') : '—';

  const resolveProviderName = (providerId?: string | null) => {
    if (!providerId) {
      return '';
    }
    const provider =
      providerOptions.find((item) => item.id === providerId) ?? null;
    return provider?.displayName ?? providerId;
  };

  const resolveModelName = (providerId?: string | null, modelId?: string | null) => {
    if (!providerId || !modelId) {
      return '';
    }
    const provider =
      providerOptions.find((item) => item.id === providerId) ?? null;
    const model =
      provider?.models.find((item) => item.id === modelId) ?? null;
    return model?.displayName ?? modelId;
  };

  const resetSamplingToDefaults = useCallback(() => {
    const defaults = computeDisplayValues(samplingDefaults ?? null);
    setSamplingDisplay(defaults);
    setSamplingOverrides({});
  }, [samplingDefaults]);

  const refreshSessionUsage = useCallback(async (targetSessionId: string) => {
    try {
      setSessionUsageError(null);
      setIsSessionUsageLoading(true);
      const usage = await fetchSessionUsage(targetSessionId);
      if (sessionIdRef.current !== targetSessionId) {
        return;
      }
      setSessionUsage(usage);
    } catch (usageError) {
      if (sessionIdRef.current !== targetSessionId) {
        return;
      }
      const message =
        usageError instanceof Error
          ? usageError.message
          : 'Не удалось получить статистику использования.';
      setSessionUsageError(message);
    } finally {
      if (sessionIdRef.current === targetSessionId) {
        setIsSessionUsageLoading(false);
      }
    }
  }, []);

  const updateSamplingValue = useCallback(
    (key: SamplingKey, value: number) => {
      const baseRange = SAMPLING_RANGE[key];
      const dynamicMax =
        key === 'maxTokens' && selectedModelConfig?.maxOutputTokens
          ? Math.max(baseRange.max, selectedModelConfig.maxOutputTokens)
          : baseRange.max;
      const clamped = clamp(value, baseRange.min, dynamicMax);
      setSamplingDisplay((prev) => ({ ...prev, [key]: clamped }));
      setSamplingOverrides((prev) => {
        const next = { ...prev };
        const defaultValue = samplingDefaults?.[key] ?? null;
        if (approxEqual(clamped, defaultValue)) {
          delete next[key];
        } else {
          next[key] = clamped;
        }
        return next;
      });
    },
    [samplingDefaults, selectedModelConfig],
  );

  const handleSamplingSliderChange = (key: SamplingKey) =>
    (event: ChangeEvent<HTMLInputElement>) => {
      updateSamplingValue(key, Number(event.target.value));
    };

  const handleSamplingNumberChange = (key: SamplingKey) =>
    (event: ChangeEvent<HTMLInputElement>) => {
      const raw = event.target.value;
      if (raw === '') {
        const fallback = samplingDefaults?.[key] ?? DEFAULT_SAMPLING_DISPLAY[key];
        setSamplingDisplay((prev) => ({ ...prev, [key]: fallback }));
        setSamplingOverrides((prev) => {
          const next = { ...prev };
          delete next[key];
          return next;
        });
        return;
      }
      const numeric = Number(raw);
      if (Number.isNaN(numeric)) {
        return;
      }
      updateSamplingValue(key, numeric);
    };

  const toggleMcpServerSelection = useCallback((serverId: string) => {
    setSelectedMcpServers((prev) =>
      prev.includes(serverId)
        ? prev.filter((id) => id !== serverId)
        : [...prev, serverId],
    );
  }, []);

  const refreshMcpCatalog = useCallback(async () => {
    try {
      setIsMcpLoading(true);
      setMcpError(null);
      const catalog = await fetchMcpCatalog();
      setMcpCatalog(catalog);
    } catch (refreshError) {
      const message =
        refreshError instanceof Error
          ? refreshError.message
          : 'Не удалось обновить каталог MCP.';
      setMcpError(message);
    } finally {
      setIsMcpLoading(false);
    }
  }, []);

  const resolveToolDescriptors = useCallback(
    (codes?: string[] | null): ToolDescriptor[] => {
      if (!codes || codes.length === 0) {
        return [];
      }
      const descriptors: ToolDescriptor[] = [];
      const seen = new Set<string>();
      codes.forEach((code) => {
        const normalized = normalizeToolCode(code);
        if (!normalized || seen.has(normalized)) {
          return;
        }
        seen.add(normalized);
        const indexEntry = mcpToolIndex.get(normalized);
        if (!indexEntry) {
          descriptors.push({
            code,
            label: code,
            status: 'UNKNOWN',
            available: true,
          });
          return;
        }
        const health = mcpHealthMap[indexEntry.serverId];
        const availableFromHealth = health
          ? health.availableTools
              .map((toolCode) => normalizeToolCode(toolCode))
              .includes(normalized)
          : undefined;
        const available =
          availableFromHealth !== undefined
            ? availableFromHealth
            : indexEntry.tool.available;
        const status = health?.status ?? (indexEntry.tool.available ? 'UP' : 'DOWN');
        descriptors.push({
          code,
          label: indexEntry.tool.displayName ?? indexEntry.tool.code,
          serverLabel: indexEntry.serverLabel,
          status,
          available,
        });
      });
      return descriptors;
    },
    [mcpToolIndex, mcpHealthMap],
  );

  const handleProviderChange = (providerId: string) => {
    setSelectedProvider(providerId);
    setInfo(null);
    setError(null);
    setStructuredNotice(null);
    setStructuredError(null);
    setActiveStructuredMessageId(null);
    setSyncError(null);
    setSyncNotice(null);
    setSyncInput('');
    setIsSyncLoading(false);
    if (!providerCatalog) {
      setSelectedModel('');
      return;
    }

    const providerEntry =
      providerCatalog.providers.find((provider) => provider.id === providerId) ??
      null;

    if (!providerEntry) {
      setSelectedModel('');
      return;
    }

    const nextModel =
      providerEntry.models.find((model) => model.id === providerEntry.defaultModel) ??
      providerEntry.models[0] ??
      null;
    setSelectedModel(nextModel ? nextModel.id : '');

    const providerDefaults: SamplingDefaults = {
      temperature: providerEntry.temperature ?? null,
      topP: providerEntry.topP ?? null,
      maxTokens: providerEntry.maxTokens ?? null,
    };
    setSamplingDefaults(providerDefaults);
    setSamplingDisplay(computeDisplayValues(providerDefaults));
    setSamplingOverrides({});
    updateActiveTabForModel(nextModel);
  };

  const handleModelChange = (modelId: string) => {
    setSelectedModel(modelId);
    setInfo(null);
    setError(null);
    setStructuredNotice(null);
    setStructuredError(null);
    setActiveStructuredMessageId(null);
    setSyncError(null);
    setSyncNotice(null);
    setSyncInput('');
    setIsSyncLoading(false);

    if (currentProvider) {
      const selectedEntry =
        currentProvider.models.find((model) => model.id === modelId) ?? null;
      const providerDefaults: SamplingDefaults = {
        temperature: currentProvider.temperature ?? null,
        topP: currentProvider.topP ?? null,
        maxTokens: currentProvider.maxTokens ?? null,
      };
      setSamplingDefaults(providerDefaults);
      setSamplingDisplay(computeDisplayValues(providerDefaults));
      setSamplingOverrides({});
      updateActiveTabForModel(selectedEntry);
    }
  };

  const resetChat = () => {
    if (isStreaming) {
      return;
    }

    setSessionId(null);
    setMessages([]);
    setError(null);
    setInfo(null);
    setLastProvider(null);
    setLastModel(null);
    setActiveStructuredMessageId(null);
    setStructuredError(null);
    setStructuredNotice(null);
    setStructuredInput('');
    setSyncInput('');
    setSyncError(null);
    setSyncNotice(null);
    setIsSyncLoading(false);
    setSessionUsage(null);
    setSessionUsageError(null);
    setIsSessionUsageLoading(false);
    sessionIdRef.current = null;
  };

  const stopStreaming = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
  };

  const handleTabChange = (tab: ActiveTab) => {
    if (tab === activeTab) {
      return;
    }
    if (tab === 'stream' && !streamingSupported) {
      setInfo('Выбранная модель не поддерживает Streaming режим.');
      return;
    }
    if (tab === 'sync' && !syncSupported) {
      setSyncError('Выбранная модель не поддерживает Sync режим.');
      return;
    }
    if (tab === 'structured' && !structuredSupported) {
      setStructuredError('Выбранная модель не поддерживает Structured режим.');
      return;
    }
    if (tab !== 'stream' && isStreaming) {
      stopStreaming();
    }
    setActiveTab(tab);
    if (tab !== 'stream') {
      setError(null);
    }
    if (tab === 'stream') {
      setStructuredError(null);
      setStructuredNotice(null);
      setSyncError(null);
      setSyncNotice(null);
    } else if (tab === 'sync') {
      setSyncError(null);
      setSyncNotice(null);
    } else {
      setStructuredError(null);
      setStructuredNotice(null);
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = input.trim();
    if (!trimmed || isStreaming) {
      return;
    }

    if (!canSendMessage) {
      setInfo('Дождитесь готовности конфигурации перед отправкой запроса.');
      return;
    }

    if (!streamingSupported) {
      setInfo('Выбранная модель поддерживает только Structured режим.');
      return;
    }

    setInput('');
    await streamMessage(trimmed);
  };

  const handleStructuredSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = structuredInput.trim();
    if (!trimmed || isStructuredLoading) {
      return;
    }

    if (!canSendMessage) {
      setStructuredNotice('Дождитесь готовности конфигурации перед отправкой запроса.');
      return;
    }

    if (!structuredSupported) {
      setStructuredError('Выбранная модель не поддерживает Structured режим.');
      return;
    }

    setStructuredError(null);
    setStructuredNotice(null);
    setIsStructuredLoading(true);

    const requestMessageId = generateId();
    const structuredUserMessage: ChatMessage = {
      id: requestMessageId,
      role: 'user',
      content: trimmed,
      status: 'complete',
      provider: selectedProvider,
      model: selectedModel,
      mode: 'structured',
    };
    setMessages((prev) => [...prev, structuredUserMessage]);

    const { payload, effectiveOptions } = buildChatPayload({
      message: trimmed,
      sessionId,
      provider: selectedProvider,
      model: selectedModel,
      overrides: normalizedSamplingOverrides,
      defaults: samplingDefaults,
      mode: resolvedInteractionMode,
      requestedToolCodes: resolvedRequestedToolCodes,
    });
    const optionsSnapshot = effectiveOptions;

    try {
      const { body, sessionId: nextSessionId, newSession } =
        await requestStructuredSync(payload);

      if (nextSessionId) {
        setSessionId(nextSessionId);
        sessionIdRef.current = nextSessionId;
      }

      const providerLabel = resolveProviderName(selectedProvider);
      const modelLabel = resolveModelName(
        selectedProvider,
        body.provider?.model ?? selectedModel,
      );
      const details = [providerLabel, modelLabel].filter(Boolean).join(' · ');
      let notice: string | null = null;
      if (newSession) {
        notice = details
          ? `Создан новый диалог (${details})`
          : 'Создан новый диалог';
        setSessionUsage(null);
        setSessionUsageError(null);
        setIsSessionUsageLoading(false);
      }

      setStructuredNotice(
        notice ?? (details ? `Получен ответ (${details})` : 'Получен ответ.'),
      );
      const messageModel = body.provider?.model ?? selectedModel;
      const assistantMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: body.answer?.summary ?? 'Структурированный ответ.',
        status: 'complete',
        provider: selectedProvider,
        model: messageModel,
        mode: 'structured',
        structured: body,
        relatedMessageId: requestMessageId,
        options: optionsSnapshot,
        usage: body.usage ?? null,
        cost: body.cost ?? null,
        tools: body.tools ?? null,
      };
      setMessages((prev) => [...prev, assistantMessage]);
      setActiveStructuredMessageId(assistantMessage.id);
      if (selectedProvider) {
        setLastProvider(selectedProvider);
      }
      if (messageModel) {
        setLastModel(messageModel);
      }
      setStructuredInput('');
      const targetSessionId = nextSessionId ?? sessionId ?? null;
      if (targetSessionId) {
        refreshSessionUsage(targetSessionId);
      }
    } catch (syncError) {
      const message =
        syncError instanceof Error
          ? syncError.message
          : 'Не удалось получить структурированный ответ.';
      setStructuredError(message);
    } finally {
      setIsStructuredLoading(false);
    }
  };

  const handleSyncSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = syncInput.trim();
    if (!trimmed || isSyncLoading) {
      return;
    }

    if (!canSendMessage) {
      setSyncNotice('Дождитесь готовности конфигурации перед отправкой запроса.');
      return;
    }

    if (!syncSupported) {
      setSyncError('Выбранная модель не поддерживает Sync режим.');
      return;
    }

    setSyncError(null);
    setSyncNotice(null);
    setIsSyncLoading(true);

    const requestMessageId = generateId();
    const syncUserMessage: ChatMessage = {
      id: requestMessageId,
      role: 'user',
      content: trimmed,
      status: 'complete',
      provider: selectedProvider,
      model: selectedModel,
      mode: 'sync',
    };
    setMessages((prev) => [...prev, syncUserMessage]);

    const { payload, effectiveOptions } = buildChatPayload({
      message: trimmed,
      sessionId,
      provider: selectedProvider,
      model: selectedModel,
      overrides: normalizedSamplingOverrides,
      defaults: samplingDefaults,
      mode: resolvedInteractionMode,
      requestedToolCodes: resolvedRequestedToolCodes,
    });
    const optionsSnapshot = effectiveOptions;

    try {
      const { body, sessionId: nextSessionId, newSession } = await requestSync(payload);

      if (nextSessionId) {
        setSessionId(nextSessionId);
        sessionIdRef.current = nextSessionId;
      }

      if (selectedProvider) {
        setLastProvider(selectedProvider);
      }
      const fallbackModel =
        selectedModel && selectedModel.length > 0 ? selectedModel : undefined;
      const messageModel =
        body.provider?.model && body.provider.model.length > 0
          ? body.provider.model
          : fallbackModel;
      if (messageModel) {
        setLastModel(messageModel);
      }

      if (newSession) {
        setSessionUsage(null);
        setSessionUsageError(null);
        setIsSessionUsageLoading(false);
      }

      const assistantMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: body.content ?? 'Ответ пустой.',
        status: 'complete',
        provider: selectedProvider,
        model: messageModel,
        mode: 'sync',
        sync: body,
        relatedMessageId: requestMessageId,
        options: optionsSnapshot,
        usage: body.usage ?? null,
        cost: body.cost ?? null,
        latencyMs: body.latencyMs ?? null,
        timestamp: body.timestamp ?? null,
        tools: body.tools ?? null,
        structured: body.structured ?? null,
      };
      setMessages((prev) => [...prev, assistantMessage]);

      if (resolvedInteractionMode === 'research' && assistantMessage.structured) {
        setActiveStructuredMessageId(assistantMessage.id);
      }

      const providerLabel = resolveProviderName(selectedProvider);
      const modelLabel = resolveModelName(selectedProvider, messageModel);
      const details = [providerLabel, modelLabel].filter(Boolean).join(' · ');

      let notice: string | null = null;
      if (newSession) {
        notice = details
          ? `Создан новый диалог (${details})`
          : 'Создан новый диалог';
        setInfo(notice);
      } else {
        notice = details ? `Получен ответ (${details})` : 'Получен ответ.';
      }
      setSyncNotice(notice);
      setSyncInput('');

      const targetSessionId = nextSessionId ?? sessionId ?? null;
      if (targetSessionId) {
        refreshSessionUsage(targetSessionId);
      }
    } catch (syncFailure) {
      const message =
        syncFailure instanceof Error
          ? syncFailure.message
          : 'Не удалось получить ответ.';
      setSyncError(message);
    } finally {
      setIsSyncLoading(false);
    }
  };

  const streamMessage = async (message: string) => {
    if (!streamingSupported) {
      setInfo('Выбранная модель не поддерживает Streaming режим.');
      return;
    }
    setError(null);
    setInfo(null);
    setIsStreaming(true);

    const userMessage: ChatMessage = {
      id: generateId(),
      role: 'user',
      content: message,
      status: 'complete',
      provider: selectedProvider,
      model: selectedModel,
      mode: 'stream',
    };
    setMessages((prev) => [...prev, userMessage]);

    const controller = new AbortController();
    abortControllerRef.current = controller;

    const { payload, effectiveOptions } = buildChatPayload({
      message,
      sessionId,
      provider: selectedProvider,
      model: selectedModel,
      overrides: normalizedSamplingOverrides,
      defaults: samplingDefaults,
      mode: resolvedInteractionMode,
      requestedToolCodes: resolvedRequestedToolCodes,
    });
    const optionsSnapshot = effectiveOptions;

    let assistantMessageId: string | null = null;
    let streamSessionId = sessionId ?? null;
    let aborted = false;

    const appendAssistantChunk = (
      chunk: string,
      provider?: string | null,
      model?: string | null,
    ) => {
      if (!chunk) {
        return;
      }

      if (!assistantMessageId) {
        assistantMessageId = generateId();
        setMessages((prev) => [
          ...prev,
          {
            id: assistantMessageId as string,
            role: 'assistant',
            content: chunk,
            status: 'streaming',
            provider: provider ?? undefined,
            model: model ?? undefined,
            options: optionsSnapshot,
            mode: 'stream',
            tools: null,
          },
        ]);
        return;
      }

      setMessages((prev) => {
        const next = [...prev];
        const index = next.findIndex((item) => item.id === assistantMessageId);
        if (index === -1) {
          next.push({
            id: assistantMessageId as string,
            role: 'assistant',
            content: chunk,
            status: 'streaming',
            provider: provider ?? undefined,
            model: model ?? undefined,
            options: optionsSnapshot,
            mode: 'stream',
          });
          return next;
        }

        const current = next[index];
        next[index] = {
          ...current,
          content: current.content + chunk,
          provider: provider ?? current.provider,
          model: model ?? current.model,
        };
        return next;
      });
    };

    const finalizeAssistantMessage = (
      fullContent?: string | null,
      provider?: string | null,
      model?: string | null,
      usage?: StructuredSyncUsageStats | null,
      cost?: UsageCostDetails | null,
      usageSource?: string | null,
      tools?: string[] | null,
    ) => {
      const normalizedSource = usageSource === undefined ? undefined : usageSource ?? null;
      if (!assistantMessageId) {
        assistantMessageId = generateId();
        setMessages((prev) => [
          ...prev,
          {
            id: assistantMessageId as string,
            role: 'assistant',
            content: fullContent ?? '',
            status: 'complete',
            provider: provider ?? undefined,
            model: model ?? undefined,
            options: optionsSnapshot,
            usage: usage ?? null,
            cost: cost ?? null,
            mode: 'stream',
            usageSource: normalizedSource ?? null,
            tools: tools ?? null,
          },
        ]);
        return;
      }

      setMessages((prev) => {
        const next = [...prev];
        const index = next.findIndex((item) => item.id === assistantMessageId);
        if (index === -1) {
          next.push({
            id: assistantMessageId as string,
            role: 'assistant',
            content: fullContent ?? '',
            status: 'complete',
            provider: provider ?? undefined,
            model: model ?? undefined,
            options: optionsSnapshot,
            usage: usage ?? null,
            cost: cost ?? null,
            mode: 'stream',
            usageSource: normalizedSource ?? null,
          });
          return next;
        }

        const current = next[index];
        next[index] = {
          ...current,
          content: fullContent ?? current.content,
          status: 'complete',
          provider: provider ?? current.provider,
          model: model ?? current.model,
          options: current.options ?? optionsSnapshot,
          usage: usage ?? current.usage ?? null,
          cost: cost ?? current.cost ?? null,
          usageSource:
            normalizedSource !== undefined
              ? normalizedSource
              : current.usageSource ?? null,
          tools: tools ?? current.tools ?? null,
        };
        return next;
      });
    };

    const handleStreamPayload = (event: StreamPayload): boolean => {
      switch (event.type) {
        case 'session': {
          if (event.sessionId) {
            setSessionId(event.sessionId);
            sessionIdRef.current = event.sessionId ?? null;
            streamSessionId = event.sessionId ?? null;
            if (event.newSession) {
              const providerLabel = resolveProviderName(event.provider ?? undefined);
              const modelLabel = resolveModelName(
                event.provider ?? undefined,
                event.model ?? undefined,
              );
              const details =
                [providerLabel, modelLabel].filter(Boolean).join(' · ');
              setInfo(
                details
                  ? `Создан новый диалог (${details})`
                  : 'Создан новый диалог',
              );
              setSessionUsage(null);
              setSessionUsageError(null);
              setIsSessionUsageLoading(false);
            }
          }
          if (event.provider) {
            setLastProvider(event.provider);
          }
          if (event.model) {
            setLastModel(event.model);
          }
          return false;
        }
        case 'token': {
          if (event.content) {
            if (event.provider) {
              setLastProvider(event.provider);
            }
            if (event.model) {
              setLastModel(event.model);
            }
            appendAssistantChunk(event.content, event.provider, event.model);
          }
          return false;
        }
        case 'complete': {
          if (event.provider) {
            setLastProvider(event.provider);
          }
          if (event.model) {
            setLastModel(event.model);
          }
          finalizeAssistantMessage(
            event.content,
            event.provider,
            event.model,
            event.usage ?? null,
            event.cost ?? null,
            event.usageSource,
            event.tools ?? null,
          );
          const targetSessionId = streamSessionId ?? sessionId ?? null;
          if (targetSessionId) {
            refreshSessionUsage(targetSessionId);
          }
          return false;
        }
        case 'error': {
          finalizeAssistantMessage(
            undefined,
            event.provider,
            event.model,
            event.usage ?? null,
            event.cost ?? null,
            event.usageSource,
            event.tools ?? null,
          );
          if (event.provider) {
            setLastProvider(event.provider);
          }
          if (event.model) {
            setLastModel(event.model);
          }
          setError(
            event.content ??
              'Не удалось получить ответ от модели. Попробуйте ещё раз позже.',
          );
          return true;
        }
        default:
          return false;
      }
    };

    try {
      const response = await fetch(CHAT_STREAM_URL, {
        method: 'POST',
        headers: buildActiveProfileHeaders({
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        }),
        body: JSON.stringify(payload),
        signal: controller.signal,
      });

      if (!response.ok || !response.body) {
        throw new Error(
          `Ошибка при обращении к чату (статус ${response.status})`,
        );
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let stop = false;

      while (!stop) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }

        buffer += decoder.decode(value, { stream: true });
        const result = drainSseBuffer(buffer, handleStreamPayload);
        buffer = result.buffer;
        stop = result.stop;
      }

      buffer += decoder.decode();
      if (buffer) {
        drainSseBuffer(buffer, handleStreamPayload);
      }

      finalizeAssistantMessage();
    } catch (streamError) {
      if (
        streamError instanceof DOMException &&
        streamError.name === 'AbortError'
      ) {
        aborted = true;
      } else if (streamError instanceof Error) {
        setError(streamError.message);
      } else {
        setError('Неизвестная ошибка при общении с моделью.');
      }
    } finally {
      abortControllerRef.current = null;
      setIsStreaming(false);

      if (aborted) {
        finalizeAssistantMessage();
        setInfo('Поток остановлен пользователем.');
      }
    }
  };

  return (
    <div className="llm-chat">
      <ActiveProfileBanner />
      <div className="llm-chat-panel">
        <div className="llm-chat-header">
          <div className="llm-chat-header-main">
            <h2>LLM Chat</h2>
            {lastProvider && (
              <span className="llm-chat-selection">
                {resolveProviderName(lastProvider)}
                {lastModel
                  ? ` · ${resolveModelName(lastProvider, lastModel)}`
                  : ''}
              </span>
            )}
          </div>
          <div className="llm-chat-header-actions">
            {sessionId && (
              <span className="llm-chat-session">
                Сессия: <code>{sessionId}</code>
              </span>
            )}
            <NavLink
              to="/flows/sessions"
              className="llm-chat-link"
              aria-label="Открыть список сессий"
            >
              <span className="llm-chat-link__content">
                Flow Sessions
                {pendingInteractions > 0 && (
                  <span
                    className="llm-chat-link__badge"
                    aria-live="polite"
                    aria-label={`Активных запросов: ${pendingInteractions}`}
                  >
                    {pendingInteractions}
                  </span>
                )}
              </span>
            </NavLink>
            <NavLink to="/flows/launch" className="llm-chat-link" aria-label="Launch Flow UI">
              Launch Flow
            </NavLink>
          </div>
        </div>

        {(sessionId || isSessionUsageLoading || usageTotals) && (
          <div className="llm-chat-usage-panel">
            <div className="llm-chat-usage-row">
              <span className="llm-chat-usage-label">Токены (prompt / completion / total)</span>
              <span className="llm-chat-usage-value">
                {formatTokens(usageTotals?.usage?.promptTokens)} /{' '}
                {formatTokens(usageTotals?.usage?.completionTokens)} /{' '}
                {formatTokens(usageTotals?.usage?.totalTokens)}
              </span>
            </div>
            <div className="llm-chat-usage-row">
              <span className="llm-chat-usage-label">Стоимость (input / output / total)</span>
              <span className="llm-chat-usage-value">
                {(() => {
                  const cost = usageTotals?.cost;
                  if (!cost) {
                    return '—';
                  }
                  const derivedTotal =
                    cost.total ?? (cost.input ?? 0) + (cost.output ?? 0);
                  const suffix = cost.currency ? ` ${cost.currency}` : '';
                  return `${formatCost(cost.input)} / ${formatCost(cost.output)} / ${formatCost(derivedTotal)}${suffix}`;
                })()}
              </span>
            </div>
            {isSessionUsageLoading && (
              <div className="llm-chat-usage-hint">Обновляем статистику…</div>
            )}
            {sessionUsageError && (
              <div className="llm-chat-usage-error">{sessionUsageError}</div>
            )}
          </div>
        )}

        <div className="llm-chat-settings">
          <div className="llm-chat-field">
            <label className="llm-chat-label" htmlFor="llm-chat-provider">
              Провайдер
            </label>
            <select
              id="llm-chat-provider"
              className="llm-chat-select"
              value={selectedProvider}
              onChange={(event) => handleProviderChange(event.target.value)}
              disabled={
                isCatalogLoading || isStreaming || providerOptions.length === 0
              }
            >
              {providerOptions.length === 0 ? (
                <option value="">
                  {isCatalogLoading
                    ? 'Загрузка…'
                    : 'Нет доступных провайдеров'}
                </option>
              ) : null}
              {providerOptions.map((provider) => (
                <option key={provider.id} value={provider.id}>
                  {provider.displayName ?? provider.id}
                </option>
              ))}
            </select>
          </div>
          <div className="llm-chat-field">
            <label className="llm-chat-label" htmlFor="llm-chat-model">
              Модель
            </label>
            <select
              id="llm-chat-model"
              className="llm-chat-select"
              value={selectedModel}
              onChange={(event) => handleModelChange(event.target.value)}
              disabled={
                isCatalogLoading || isStreaming || !hasModelOptions
              }
            >
              {!hasModelOptions ? (
                <option value="">
                  {isCatalogLoading
                    ? 'Загрузка…'
                    : currentProvider
                    ? 'Модели недоступны'
                    : 'Выберите провайдера'}
                </option>
              ) : groupedModelOptions.length <= 1 ? (
                groupedModelOptions.flatMap((group) =>
                  group.models.map((model) => (
                    <option
                      key={model.id}
                      value={model.id}
                      title={group.description ?? undefined}
                    >
                      {buildModelOptionLabel(currentProvider!.id, model)}
                    </option>
                  )),
                )
              ) : (
                groupedModelOptions.map((group) => (
                  <optgroup key={group.key} label={group.label}>
                    {group.models.map((model) => (
                      <option
                        key={model.id}
                        value={model.id}
                        title={group.description ?? undefined}
                      >
                        {buildModelOptionLabel(currentProvider!.id, model)}
                      </option>
                    ))}
                  </optgroup>
                ))
              )}
            </select>
          </div>
          <div className="llm-chat-field">
            <label className="llm-chat-label" htmlFor="llm-chat-mode">
              Режим
            </label>
            <select
              id="llm-chat-mode"
              className="llm-chat-select"
              value={interactionMode}
              onChange={(event) =>
                setInteractionMode(event.target.value as 'default' | 'research')
              }
              disabled={isStreaming || selectedMcpServers.length > 0}
            >
              <option value="default" disabled={selectedMcpServers.length > 0}>
                Стандартный
              </option>
              <option value="research">Research (MCP инструменты)</option>
            </select>
          </div>
        </div>

        <div className="llm-chat-mcp">
          <div className="llm-chat-mcp-header">
            <div className="llm-chat-mcp-title-block">
              <span className="llm-chat-mcp-title">MCP инструменты</span>
              <span className="llm-chat-mcp-subtitle">
                Выберите сервера, которые ассистент может использовать в режиме Research
              </span>
            </div>
            <div className="llm-chat-mcp-actions">
              <span className="llm-chat-mcp-summary">
                {selectedMcpServers.length > 0
                  ? `${selectedMcpServers.length} серв. · ${selectedMcpToolCount} инструментов`
                  : 'Серверы не выбраны'}
              </span>
              <button
                type="button"
                className="llm-chat-button ghost"
                onClick={() => {
                  void refreshMcpCatalog();
                }}
                disabled={isMcpLoading}
              >
                {isMcpLoading ? 'Обновляем…' : 'Обновить'}
              </button>
            </div>
          </div>
          {mcpError && <div className="llm-chat-error">{mcpError}</div>}
          {!mcpError && isMcpLoading ? (
            <div className="llm-chat-info">Загружаем каталог MCP…</div>
          ) : null}
          {!isMcpLoading && (mcpCatalog?.servers.length ?? 0) === 0 && !mcpError ? (
            <div className="llm-chat-info">Серверы MCP не сконфигурированы.</div>
          ) : null}
          {mcpCatalog && mcpCatalog.servers.length > 0 ? (
            <div className="llm-chat-mcp-list">
              {mcpCatalog.servers.map((server) => {
                const isChecked = selectedMcpServers.includes(server.id);
                const health = mcpHealthMap[server.id];
                const status = health?.status ?? server.status;
                const availableCount =
                  health?.availableTools?.length ??
                  server.tools.filter((tool) => tool.available).length;
                const unavailableCount =
                  health?.unavailableTools?.length ??
                  server.tools.filter((tool) => !tool.available).length;
                const totalCount = server.tools.length;
                const lastUpdated = health?.timestamp ?? null;
                return (
                  <label key={server.id} className="llm-chat-mcp-server">
                    <input
                      type="checkbox"
                      className="llm-chat-mcp-checkbox"
                      checked={isChecked}
                      onChange={() => toggleMcpServerSelection(server.id)}
                      disabled={isStreaming || isSyncLoading || isStructuredLoading}
                    />
                    <div className="llm-chat-mcp-server-body">
                      <div className="llm-chat-mcp-server-top">
                        <span className="llm-chat-mcp-server-name">
                          {server.displayName ?? server.id}
                        </span>
                        <span
                          className={`llm-chat-mcp-status ${STATUS_BADGE_CLASS[status]}`}
                        >
                          {formatMcpStatusLabel(status)}
                        </span>
                      </div>
                      {server.description && (
                        <p className="llm-chat-mcp-description">{server.description}</p>
                      )}
                      <div className="llm-chat-mcp-meta">
                        <span className="llm-chat-mcp-meta-entry">
                          {availableCount}/{totalCount} доступно
                        </span>
                        {unavailableCount > 0 && (
                          <span className="llm-chat-mcp-meta-entry muted">
                            {unavailableCount} отключено
                          </span>
                        )}
                        {lastUpdated && (
                          <span className="llm-chat-mcp-meta-entry muted">
                            {formatTimestamp(lastUpdated)}
                          </span>
                        )}
                      </div>
                      {server.tags.length > 0 && (
                        <div className="llm-chat-mcp-tags">
                          {server.tags.map((tag) => (
                            <span key={tag} className="llm-chat-mcp-tag">
                              {tag}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  </label>
                );
              })}
            </div>
          ) : null}
        </div>

        {selectedModelConfig && (
          <div className="llm-chat-model-info">
            <div className="llm-chat-model-info-row">
              <span className="llm-chat-model-info-label">Сегмент</span>
              <span className="llm-chat-model-info-value">{segmentSummary}</span>
            </div>
            <div className="llm-chat-model-info-row">
              <span className="llm-chat-model-info-label">Стоимость</span>
              <span className="llm-chat-model-info-value">{priceSummary}</span>
            </div>
            <div className="llm-chat-model-info-row">
              <span className="llm-chat-model-info-label">Контекст</span>
              <span className="llm-chat-model-info-value">{contextSummary}</span>
            </div>
            <div className="llm-chat-model-info-row">
              <span className="llm-chat-model-info-label">Режимы</span>
              <span className="llm-chat-model-info-value">{modesSummary}</span>
            </div>
            {selectedSegmentMeta?.description && (
              <div className="llm-chat-model-hint">
                {selectedSegmentMeta.description}
              </div>
            )}
          </div>
        )}

        <div className="llm-chat-sampling">
          <div className="llm-chat-sampling-header">
            <span>Параметры sampling</span>
            <button
              type="button"
              className="llm-chat-button ghost"
              onClick={resetSamplingToDefaults}
              disabled={!samplingOverridesActive || samplingControlsDisabled}
            >
              Сбросить к дефолтам
            </button>
          </div>
          <div className="llm-chat-sampling-grid">
            {SAMPLING_KEYS.map((key) => {
              const baseRange = SAMPLING_RANGE[key];
              const dynamicMax =
                key === 'maxTokens' && selectedModelConfig?.maxOutputTokens
                  ? Math.max(baseRange.max, selectedModelConfig.maxOutputTokens)
                  : baseRange.max;
              const range = { ...baseRange, max: dynamicMax } as typeof baseRange;
              const sliderValue = clamp(
                samplingDisplay[key] ?? range.min,
                range.min,
                range.max,
              );
              const defaultValue = samplingDefaults?.[key] ?? null;
              const overrideIsActive = Object.prototype.hasOwnProperty.call(
                normalizedSamplingOverrides,
                key,
              );

              const numberValue =
                key === 'maxTokens'
                  ? Math.round(sliderValue)
                  : Number(sliderValue.toFixed(2));

              const defaultLabel = formatOptionValue(key, defaultValue);
              const currentLabel = formatOptionValue(key, sliderValue);

              return (
                <div key={key} className="llm-chat-sampling-field">
                  <div className="llm-chat-sampling-top">
                    <label className="llm-chat-label" htmlFor={`llm-chat-${key}`}>
                      {key === 'temperature'
                        ? 'Temperature'
                        : key === 'topP'
                        ? 'Top P'
                        : 'Max tokens'}
                    </label>
                    <span
                      className={
                        overrideIsActive
                          ? 'llm-chat-sampling-value override'
                          : 'llm-chat-sampling-value'
                      }
                    >
                      {currentLabel}
                    </span>
                  </div>
                  <input
                    type="range"
                    id={`llm-chat-${key}-slider`}
                    className="llm-chat-sampling-slider"
                    min={range.min}
                    max={range.max}
                    step={range.step}
                    value={sliderValue}
                    onChange={handleSamplingSliderChange(key)}
                    disabled={samplingControlsDisabled}
                  />
                  <div className="llm-chat-sampling-controls">
                    <input
                      type="number"
                      id={`llm-chat-${key}`}
                      className="llm-chat-sampling-number"
                      value={numberValue}
                      onChange={handleSamplingNumberChange(key)}
                      step={range.step}
                      min={range.min}
                      max={range.max}
                      disabled={samplingControlsDisabled}
                    />
                    <span className="llm-chat-sampling-default">
                      По умолчанию: {defaultLabel}
                    </span>
                    {overrideIsActive ? (
                      <span className="llm-chat-sampling-status">Переопределено</span>
                    ) : null}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div className="llm-chat-tabs" role="tablist">
          <button
            type="button"
            className={`llm-chat-tab ${activeTab === 'stream' ? 'active' : ''}`}
            onClick={() => handleTabChange('stream')}
            data-testid="tab-stream"
            disabled={!streamingSupported}
            title={
              streamingSupported
                ? undefined
                : 'Модель не поддерживает Streaming режим.'
            }
          >
            Streaming
          </button>
          <button
            type="button"
            className={`llm-chat-tab ${activeTab === 'sync' ? 'active' : ''}`}
            onClick={() => handleTabChange('sync')}
            data-testid="tab-sync"
            disabled={!syncSupported}
            title={
              syncSupported ? undefined : 'Модель не поддерживает Sync режим.'
            }
          >
            Sync
          </button>
          <button
            type="button"
            className={`llm-chat-tab ${activeTab === 'structured' ? 'active' : ''}`}
            onClick={() => handleTabChange('structured')}
            data-testid="tab-structured"
            disabled={!structuredSupported}
            title={
              structuredSupported
                ? undefined
                : 'Модель не поддерживает Structured режим.'
            }
          >
            Structured
          </button>
        </div>

        {activeTab === 'stream' ? (
          <>
            <div ref={messagesContainerRef} className="llm-chat-messages">
              {messages.length === 0 ? (
                <div className="llm-chat-empty">
                  Введите сообщение, чтобы начать диалог с моделью.
                </div>
              ) : (
                messages.map((message) => {
                  const providerLabel = message.provider
                    ? resolveProviderName(message.provider)
                    : '';
                  const modelLabel = message.model
                    ? resolveModelName(message.provider, message.model)
                    : '';
                  const samplingSummary = formatSamplingSummary(message.options);
                  const hasSamplingMeta = Boolean(message.options);
                  const hasMeta = Boolean(providerLabel || modelLabel || hasSamplingMeta);
                  const toolDescriptors = resolveToolDescriptors(message.tools);

                  return (
                    <div
                      key={message.id}
                      className={`llm-chat-message ${message.role}`}
                    >
                      {hasMeta && (
                        <div className="llm-chat-message-meta">
                      {providerLabel && (
                        <span className="llm-chat-meta-chip">
                          {providerLabel}
                        </span>
                      )}
                      {modelLabel && (
                        <span className="llm-chat-meta-chip secondary">
                          {modelLabel}
                        </span>
                      )}
                      {toolDescriptors.length > 0 && (
                        <div className="llm-chat-tool-badges" title="Инструменты MCP">
                          {toolDescriptors.map((tool) => (
                            <span
                              key={`${tool.code}-${tool.serverLabel ?? 'local'}`}
                              className={`llm-chat-tool-badge ${STATUS_BADGE_CLASS[tool.status]}`}
                              aria-label={`Инструмент ${tool.label}`}
                            >
                              <span className="llm-chat-tool-badge-icon" aria-hidden="true">
                                🔧
                              </span>
                              <span className="llm-chat-tool-badge-label">{tool.label}</span>
                              {tool.serverLabel && (
                                <span className="llm-chat-tool-badge-server">{tool.serverLabel}</span>
                              )}
                            </span>
                          ))}
                        </div>
                      )}
                          {hasSamplingMeta && (
                            <span
                              className="llm-chat-meta-chip options"
                              data-testid="sampling-meta"
                            >
                              {samplingSummary}
                            </span>
                          )}
                        </div>
                      )}
                      <div className="llm-chat-bubble">{message.content}</div>
                      {message.role === 'assistant' &&
                        (message.usage || message.cost) && (
                          <div className="llm-chat-message-usage">
                            {message.usage && (
                              <span>
                                Токены:
                                <strong>{formatTokens(message.usage.promptTokens)}</strong>
                                {' / '}
                                <strong>{formatTokens(message.usage.completionTokens)}</strong>
                                {' / '}
                                <strong>{formatTokens(message.usage.totalTokens)}</strong>
                              </span>
                            )}
                            {message.cost && (
                              <span>
                                Стоимость:
                                <strong>{formatCost(message.cost.input)}</strong>
                                {' / '}
                                <strong>{formatCost(message.cost.output)}</strong>
                                {' / '}
                                <strong>
                                  {formatCost(
                                    message.cost.total ??
                                      (message.cost.input ?? 0) +
                                        (message.cost.output ?? 0),
                                  )}
                                </strong>
                                {message.cost.currency
                                  ? ` ${message.cost.currency}`
                                  : ''}
                              </span>
                            )}
                            {(() => {
                              const descriptor = describeUsageSource(message.usageSource);
                              if (!descriptor) {
                                return null;
                              }
                              return (
                                <span className="llm-chat-usage-source">
                                  <span className="llm-chat-usage-source-label">
                                    Источник usage:
                                  </span>
                                  <span
                                    className={`llm-chat-usage-source-badge ${descriptor.variant}`}
                                  >
                                    {descriptor.label}
                                  </span>
                                </span>
                              );
                            })()}
                          </div>
                        )}
                      {message.structured ? (
                        <StructuredResponseCard
                          response={message.structured}
                          providerLabel={resolveProviderName(message.provider)}
                          modelLabel={
                            resolveModelName(message.provider, message.model) ??
                            message.structured.provider?.model ??
                            ''
                          }
                          optionsLabel={message.options ? formatSamplingSummary(message.options) : undefined}
                          onSelect={() => setActiveStructuredMessageId(message.id)}
                          highlight={message.id === activeStructuredMessage?.id}
                          toolDescriptors={resolveToolDescriptors(message.structured.tools ?? message.tools)}
                        />
                      ) : null}
                      {message.status === 'streaming' && (
                        <span className="llm-chat-message-status">
                          Модель отвечает…
                        </span>
                      )}
                    </div>
                  );
                })
              )}
            </div>

            {(catalogError ||
              error ||
              info ||
              isStreaming ||
              (isCatalogLoading && messages.length === 0)) && (
              <div className="llm-chat-status">
                {catalogError && (
                  <span className="llm-chat-error">{catalogError}</span>
                )}
                {!catalogError && error && (
                  <span className="llm-chat-error">{error}</span>
                )}
                {!catalogError && !error && info && (
                  <span className="llm-chat-info">{info}</span>
                )}
                {streamHasError && mcpHint && (
                  <span className="llm-chat-hint">{mcpHint}</span>
                )}
                {!catalogError && !error && !info && isStreaming && (
                  <span className="llm-chat-info">
                    Получаем ответ от модели…
                  </span>
                )}
                {!catalogError &&
                  !error &&
                  !info &&
                  !isStreaming &&
                  isCatalogLoading && (
                    <span className="llm-chat-info">
                      Загружаем конфигурацию чата…
                    </span>
                  )}
              </div>
            )}

            <form className="llm-chat-form" onSubmit={handleSubmit}>
              <label className="llm-chat-label" htmlFor="llm-chat-input">
                Сообщение
              </label>
              <textarea
                id="llm-chat-input"
                className="llm-chat-textarea"
                placeholder="Напишите вопрос или запрос для модели…"
                value={input}
                onChange={(event) => setInput(event.target.value)}
                disabled={isStreaming}
              />
              <div className="llm-chat-actions">
                <button
                  type="submit"
                  className="llm-chat-button primary"
                  disabled={isStreaming || !input.trim() || !canSendMessage}
                >
                  Отправить
                </button>
                <button
                  type="button"
                  className="llm-chat-button danger"
                  onClick={stopStreaming}
                  disabled={!isStreaming}
                >
                  Остановить
                </button>
                <button
                  type="button"
                  className="llm-chat-button ghost"
                  onClick={resetChat}
                  disabled={isStreaming || messages.length === 0}
                >
                  Новый диалог
                </button>
              </div>
            </form>
          </>
        ) : activeTab === 'sync' ? (
          <div className="llm-chat-sync">
            <form className="llm-chat-form" onSubmit={handleSyncSubmit}>
              <label className="llm-chat-label" htmlFor="llm-chat-sync-input">
                Запрос
              </label>
              <textarea
                id="llm-chat-sync-input"
                className="llm-chat-textarea"
                placeholder="Опишите задачу для синхронного ответа…"
                value={syncInput}
                onChange={(event) => setSyncInput(event.target.value)}
                disabled={isSyncLoading}
              />
              <div className="llm-chat-actions">
                <button
                  type="submit"
                  className="llm-chat-button primary"
                  disabled={
                    isSyncLoading ||
                    !syncInput.trim() ||
                    !canSendMessage
                  }
                >
                  {isSyncLoading ? 'Отправляем…' : 'Отправить'}
                </button>
                <button
                  type="button"
                  className="llm-chat-button ghost"
                  onClick={() => setSyncInput('')}
                  disabled={isSyncLoading || syncInput.length === 0}
                >
                  Очистить
                </button>
                <button
                  type="button"
                  className="llm-chat-button ghost"
                  onClick={resetChat}
                  disabled={
                    isSyncLoading ||
                    (messages.length === 0 && syncMessages.length === 0)
                  }
                >
                  Новый диалог
                </button>
              </div>
            </form>

            {(syncError || syncNotice || isSyncLoading) && (
              <div className="llm-chat-status">
                {syncError && (
                  <span className="llm-chat-error">{syncError}</span>
                )}
                {syncHasError && mcpHint && (
                  <span className="llm-chat-hint">{mcpHint}</span>
                )}
                {!syncError && syncNotice && (
                  <span className="llm-chat-info">{syncNotice}</span>
                )}
                {!syncError &&
                  !syncNotice &&
                  isSyncLoading && (
                    <span className="llm-chat-info">
                      Получаем синхронный ответ…
                    </span>
                  )}
              </div>
            )}

            {syncMessages.length > 0 ? (
              <div className="structured-history">
                {syncMessages.map((message) => {
                  const related =
                    message.relatedMessageId &&
                    messages.find((item) => item.id === message.relatedMessageId);
                  const providerLabel = resolveProviderName(message.provider);
                  const modelLabel = resolveModelName(
                    message.provider,
                    message.model,
                  );
                  return (
                    <div key={message.id} className="structured-history-entry">
                      {related && (
                        <div className="structured-request-preview">
                          <span className="structured-label">Запрос</span>
                          <p className="structured-value">{related.content}</p>
                        </div>
                      )}
                      <SyncResponseCard
                        message={message}
                        providerLabel={providerLabel}
                        modelLabel={modelLabel}
                        optionsLabel={
                          message.options
                            ? formatSamplingSummary(message.options)
                            : undefined
                        }
                        toolDescriptors={resolveToolDescriptors(message.tools)}
                      />
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="structured-placeholder">
                Синхронные ответы появятся здесь после отправки запроса.
              </div>
            )}
          </div>
        ) : (
          <div className="llm-chat-structured">
            <form className="llm-chat-form" onSubmit={handleStructuredSubmit}>
              <label className="llm-chat-label" htmlFor="llm-chat-structured-input">
                Запрос
              </label>
              <textarea
                id="llm-chat-structured-input"
                className="llm-chat-textarea"
                placeholder="Опишите задачу для структурированного ответа…"
                value={structuredInput}
                onChange={(event) => setStructuredInput(event.target.value)}
                disabled={isStructuredLoading}
              />
              <div className="llm-chat-actions">
                <button
                  type="submit"
                  className="llm-chat-button primary"
                  disabled={
                    isStructuredLoading ||
                    !structuredInput.trim() ||
                    !canSendMessage
                  }
                >
                  {isStructuredLoading ? 'Отправляем…' : 'Отправить'}
                </button>
                <button
                  type="button"
                  className="llm-chat-button ghost"
                  onClick={() => setStructuredInput('')}
                  disabled={isStructuredLoading || structuredInput.length === 0}
                >
                  Очистить
                </button>
                <button
                  type="button"
                  className="llm-chat-button ghost"
                  onClick={resetChat}
                  disabled={
                    isStructuredLoading ||
                    (messages.length === 0 && structuredMessages.length === 0)
                  }
                >
                  Новый диалог
                </button>
              </div>
            </form>

            {(structuredError || structuredNotice || isStructuredLoading) && (
              <div className="llm-chat-status">
                {structuredError && (
                  <span className="llm-chat-error">{structuredError}</span>
                )}
                {structuredHasError && mcpHint && (
                  <span className="llm-chat-hint">{mcpHint}</span>
                )}
                {!structuredError && structuredNotice && (
                  <span className="llm-chat-info">{structuredNotice}</span>
                )}
                {!structuredError &&
                  !structuredNotice &&
                  isStructuredLoading && (
                    <span className="llm-chat-info">
                      Получаем структурированный ответ…
                    </span>
                  )}
              </div>
            )}

            {structuredMessages.length > 0 ? (
              <div className="structured-history">
                {structuredMessages.map((message) => {
                  const related =
                    message.relatedMessageId &&
                    messages.find((item) => item.id === message.relatedMessageId);
                  const providerLabel = resolveProviderName(message.provider);
                  const modelLabel =
                    resolveModelName(message.provider, message.model) ||
                    message.structured?.provider?.model ||
                    '';
                  const isActive =
                    activeStructuredMessage?.id === message.id;
                  return (
                    <div
                      key={message.id}
                      className={`structured-history-entry${
                        isActive ? ' active' : ''
                      }`}
                    >
                      {related && (
                        <div className="structured-request-preview">
                          <span className="structured-label">Запрос</span>
                          <p className="structured-value">{related.content}</p>
                        </div>
                      )}
                      <StructuredResponseCard
                        response={message.structured}
                        providerLabel={providerLabel}
                        modelLabel={modelLabel}
                        highlight={isActive}
                        optionsLabel={
                          message.options
                            ? formatSamplingSummary(message.options)
                            : undefined
                        }
                        onSelect={() => setActiveStructuredMessageId(message.id)}
                        toolDescriptors={resolveToolDescriptors(
                          message.structured?.tools ?? message.tools,
                        )}
                      />
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="structured-placeholder">
                Структурированный ответ появится здесь после отправки запроса.
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
