# Wave 12 — Agent Constructor (Option 1 Implementation Outline)

## Scope

- Replace legacy JSON fields (`default_options`, `tool_bindings`, `cost_profile`) on `AgentVersion` with a single typed aggregate `AgentInvocationOptions`.
- Introduce catalog tables for agent tool metadata and schemas.
- Provide REST wizard endpoints for constructing/validating/previewing agent configurations.
- Drop existing agent/flow data and reseed the database with curated demo agents/tools/flows.

## Data Model

### New/Updated Tables

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `agent_version` | `id UUID` | drop `default_options`, `tool_bindings`, `cost_profile`; add `agent_invocation_options JSONB NOT NULL`. |
| `tool_definition` | `id UUID PK`, `code VARCHAR(64) UNIQUE`, `display_name`, `description`, `provider_hint`, `call_type`, `tags JSONB`, `capabilities JSONB`, `cost_hint`, `icon_url`, `default_timeout_ms`, `schema_version_id UUID FK` | Holds current metadata and links to latest schema. |
| `tool_schema_version` | `id UUID PK`, `tool_code VARCHAR(64)`, `version INT`, `request_schema JSONB`, `response_schema JSONB`, `schema_checksum VARCHAR(64)`, `examples JSONB`, `mcp_server`, `mcp_tool_name`, `transport`, `auth_scope`, `created_at TIMESTAMPTZ` | Multiple schema revisions per tool; `tool_code + version` unique. |

### AgentInvocationOptions Structure (Option B)

```json
{
  "provider": {
    "type": "OPEN_AI",
    "id": "openai",
    "modelId": "gpt-4o",
    "mode": "SYNC"
  },
  "prompt": {
    "system": "...",
    "templateId": "default-support",
    "variables": [
      { "name": "customerName", "required": true, "description": "..." }
    ]
  },
  "memoryPolicy": {
    "channels": ["shared", "conversation"],
    "retentionDays": 30,
    "maxEntries": 200,
    "summarizationStrategy": "TAIL_WITH_SUMMARY",
    "overflowAction": "TRIM_OLDEST"
  },
  "retryPolicy": {
    "maxAttempts": 3,
    "initialDelayMs": 250,
    "multiplier": 2.0,
    "retryableStatuses": [429, 500, 503],
    "timeoutMs": 30000,
    "overallDeadlineMs": 90000,
    "jitterMs": 50
  },
  "advisorSettings": {
    "telemetry": { "enabled": true },
    "audit": { "enabled": true, "redactPii": true },
    "routing": { "enabled": false }
  },
  "tooling": {
    "bindings": [
      {
        "toolCode": "perplexity_search",
        "schemaVersion": 1,
        "executionMode": "AUTO",
        "requestOverrides": {},
        "responseExpectations": {}
      }
    ]
  },
  "costProfile": {
    "inputPer1KTokens": 0.01,
    "outputPer1KTokens": 0.03,
    "currency": "USD",
    "latencyFee": 0.001,
    "fixedFee": 0.1
  }
}
```

### Seed Content (Option A)

- Tools:
  - `openai-gpt-4o`
  - `perplexity_search`
- Agents:
  - `demo-openai-chat` (OpenAI sync chat, default prompt)
  - `perplexity-research` (Perplexity-assisted agent with research tool binding)
- Flow:
  - `demo-lead-qualify` (two-step orchestration using the demo agents)

Seed delivered via Liquibase `loadData`/`sqlFile` after schema migration.

## API Surface

### Endpoints

- `GET /api/agents/constructor/providers`
- `GET /api/agents/constructor/tools`
- `GET /api/agents/constructor/policies`
- `POST /api/agents/constructor/validate`
- `POST /api/agents/constructor/preview`

### Preview Response (Option C)

```json
{
  "proposed": { ...AgentInvocationOptions },
  "diff": [
    { "path": "/provider/modelId", "old": "gpt-4", "new": "gpt-4o" }
  ],
  "costEstimate": {
    "inputTokens": 800,
    "outputTokens": 1200,
    "totalCost": 0.045,
    "currency": "USD",
    "latencyMs": 1800
  },
  "toolCoverage": [
    { "toolCode": "perplexity_search", "available": true }
  ],
  "warnings": [
    { "path": "/retryPolicy", "message": "Overall deadline < timeout * attempts" }
  ]
}
```

## Implementation Steps

1. **Migration**: Create Liquibase changeset – drop old columns, add new column, create tool tables, truncate data, load seed.
2. **Domain Model**: Add value objects for each AgentInvocationOptions block; map to `agent_invocation_options` JSONB via converters.
3. **Repository & Service Updates**: Update `AgentVersion`, `AgentInvocationService`, `AgentCatalogService` to use the new aggregate.
4. **Tool Catalog**: Implement repositories/services for `tool_definition` & `tool_schema_version`.
5. **AgentConstructorService & Controller**: Implement wizard endpoints; include validation, diff, and preview logic.
6. **Testing**: Update integration/unit tests; add seed verification tests.
7. **Docs**: Update wave backlog, architecture notes, and seed documentation.
