# Wave 12 — End-to-End Journey (Agent Constructor ➝ Flow Builder ➝ Launch)

## Roles

- **Agent Designer** — curates provider/model choices, crafts prompt templates, defines memory/retry/advisor policies, and publishes reusable agents.
- **Flow Orchestrator** — assembles flow blueprints from published agents, configures launch parameters, simulations, and publishes flows.
- **Operations Lead** — launches and monitors flows in production, handles manual approvals, monitors cost/latency, and coordinates rollbacks when needed.
- **Observer (Optional)** — Stakeholders with read-only access to review configurations and audit history.

## Journey Overview

| Phase | Primary Actor | Goal | Key Outputs |
|-------|---------------|------|-------------|
| 1. Agent Design | Agent Designer | Create & validate agent configuration in constructor | Draft agent version with `AgentInvocationOptions` + tool bindings |
| 2. Agent Publication | Agent Designer | Review, QA, and publish agent version | Published agent version visible to flow builder |
| 3. Flow Assembly | Flow Orchestrator | Compose flow blueprint using published agents | Draft flow definition with typed blueprint |
| 4. Flow Publication | Flow Orchestrator | Validate, simulate, and publish flow | Active flow definition |
| 5. Launch / Operations | Operations Lead | Launch flow, monitor progress/cost, manage incidents | Flow sessions, telemetry, approvals, incident logs |

### Phase Details

#### 1. Agent Design

1. Agent Designer opens **Agent Constructor** wizard.
2. Select provider & model from catalog (fetched from `/api/agents/constructor/providers`);
   wizard auto-loads default invocation options.
3. Configure prompt: template, system prompt, variables, generation defaults.
4. Configure memory policy (retention, summarization) & retry policy (attempts, backoff).
5. Toggle advisor settings (telemetry, audit, routing).
6. Attach tool bindings:
   - Browse catalog (`/constructor/tools`), select schema version.
   - Provide execution mode, optional request overrides, response expectations.
7. Review cost profile (static inputs + derived cost preview).
8. Run **Validate** (`POST /constructor/validate`):
   - Returns normalized options and validation issues (blocking / warnings).
   - UI surfaces warnings, blocks submission on errors.
9. Optional **Preview** (`POST /constructor/preview`):
   - Shows diff vs baseline, cost estimate, tool coverage notes, latency implications.
10. Save agent version draft:
    - `POST /api/agents/definitions/{id}/versions` with normalized options.
11. Repeat edits until draft ready.

**Error States**

- Missing provider/model → inline error, disable save.
- Tool schema mismatch → warning (non-blocking) but flagged for review.
- Cost profile missing currency → error.

**Dependencies**

- Tool catalog tables populated with schema metadata.
- Providers configured in `ChatProvidersProperties`.

#### 2. Agent Publication

1. Designer reviews draft details (system prompt, invocation options, tools).
2. Optionally adjusts capabilities metadata.
3. Triggers **Publish** (`POST /api/agents/versions/{id}/publish`).
4. System validates:
   - Draft status required.
   - Definition auto-activates if previously inactive.
5. On success, version marked `PUBLISHED`, timestamp recorded.

**Error States**

- Attempt to publish already deprecated version → 409.
- Validation fails post-change (e.g., provider removed) → 422 with message; designer must fix configuration.

#### 3. Flow Assembly

1. Orchestrator opens flow builder.
2. Creates draft flow definition (`POST /api/flows/definitions`):
   - Adds metadata, launch parameters (typed fields), shared memory configuration.
3. Adds steps:
   - Choose published agent version; builder fetches invocation options for context.
   - Configure prompts, overrides, transitions, memory reads/writes.
4. Visual panels show:
   - Launch parameters schema.
   - Step interactions (HITL, suggested actions).
   - Cost preview (estimated tokens/cost per step).
5. Draft saved iteratively.
6. Builder runs structural validation (client) and may call future API step validator (once implemented in API v2 task).

**Error States**

- Missing start step → validation highlight.
- Using unpublished agent version → block save/publish.
- Transition to unknown step → compile validation error.

#### 4. Flow Publication

1. Orchestrator runs **Launch Preview** (existing `FlowLaunchPreviewService`) to check cost & step coverage.
2. Draft is promoted via `POST /api/flows/definitions/{id}/publish`.
3. Snapshots stored in history for rollback.

**Dependencies**

- All steps reference published, active agents.
- Launch parameters typed & validated.

#### 5. Launch / Operations

1. Ops launches flow session via `POST /api/flows/{definitionId}/start` with parameters & overrides.
2. Flow orchestration runs; manual interactions handled via Flow Interaction UI.
3. Telemetry dashboards visualize session metrics, cost, latency.
4. Incident flows:
   - Manual approvals/responses.
   - Pause/resume/cancel commands.
   - Retry with updated overrides if needed.
5. Post-run: analyze events, update agent/flow if issues identified.

## Dependencies & Touchpoints

| Component | Notes |
|-----------|-------|
| Tool Catalog | Must be up-to-date; wizard relies on `tool_definition` / `tool_schema_version`. |
| Provider Config (`ChatProvidersProperties`) | Required for provider/model lists & validation. |
| Validation schemas | JSON Schema for interaction payloads, tool bindings, etc., stored server-side. |
| Telemetry + Cost Estimation | Used in preview responses; requires token usage estimator fallbacks. |

## Happy Path Summary

1. Designer creates & publishes agent version via constructor wizard.
2. Orchestrator selects published agents to build flow blueprint, runs preview, publishes flow.
3. Ops launches flow, monitors results, handles manual interactions, reviews telemetry.

## Error & Recovery Matrix

| Phase | Error | Handling |
|-------|-------|----------|
| Agent Validation | Missing provider/model | Inline error, block save |
| Agent Preview | Tool unavailable | Warning + coverage detail |
| Flow Assembly | Broken transitions | Compiler throws, UI surfaces error |
| Flow Launch | Unpublished agent | Flow start API returns 422, user must re-publish agent |
| Operations | Manual request timeout | FlowInteractionService auto-expire + job enqueue, alert via telemetry |

