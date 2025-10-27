# Wave 12 — UI Kit / Wireframes Outline

## 1. Agent Constructor Wizard

### Layout
- **Wizard shell** with progress steps: `Provider ➝ Prompt ➝ Memory & Retry ➝ Advisors ➝ Tools ➝ Cost ➝ Review`.
- Sidebar summary showing current selections (provider, model, cost totals).
- Primary action buttons: `Back / Next` with `Validate`/`Preview` CTA on Review step.

### Core Components
- **Provider Selector**
  - Dropdowns for provider + model (searchable).
  - Pill badges for capabilities (sync/stream/structured) fetched from `/constructor/providers`.
  - Info panel: pricing, context window, usage policies.
- **Prompt Editor**
  - Text area for system prompt.
  - Table for variables: name, description, type (string/number/object), required toggle.
  - Sliders/inputs for generation defaults (temperature, topP, max tokens) with inline hints.
- **Memory Policy**
  - Tag selector for memory channels (shared/conversation/custom).
  - Numeric inputs for retention days, max entries.
  - Dropdown for summarization strategy (None, Tail + Summary, Summary only).
  - Radio buttons for overflow action (Trim oldest vs Reject new).
- **Retry Policy**
  - Inputs: max attempts, initial delay, multiplier (with toggle fixed vs exponential).
  - Optional fields: timeout, overall deadline, jitter.
  - Checklist of retryable HTTP statuses.
- **Advisor Settings**
  - Toggle cards for Telemetry / Audit / Routing.
  - For Audit: nested options like PII redaction, data residency note.
- **Tool Bindings**
  - Tool catalog list with search filters (tags/capabilities).
  - Detail drawer showing description, schema version info, MCP metadata.
  - Form for execution mode + JSON editor (request overrides).
  - Validation messages for schema mismatch.
- **Cost Profile**
  - Numeric inputs for input/output cost per 1K tokens, latency fee, fixed fee, currency dropdown.
  - Calculated cost preview (using sample prompt tokens).
- **Review & Preview**
  - Summary cards for each section with “Edit” links.
  - Diff viewer versus baseline (if editing existing agent).
  - Validation results (errors top, warnings inline).
  - Cost & latency estimate card.
  - Tool coverage table (tool code, availability status, notes).

### States
- Validating… spinner + disable navigation.
- Errors: highlight fields, show messages from validate API.
- Warnings: yellow banners with ability to continue.
- Read-only mode for published versions (no editing).

## 2. Flow Builder

### Layout
- **Two-pane workspace**:
  - Left sidebar: Step list (draggable), launch parameters, shared memory overview.
  - Main canvas: Selected step detail tabs (Prompt, Overrides, Memory, Transitions, HITL).
- Header with flow metadata (name, version, status, last updated) and actions (`Save Draft`, `Preview Cost`, `Publish`, `History`).

### Core Components
- **Launch Parameters Form**
  - Table layout: name, label, type, required, default, validation rules (JSON schema).
  - Add/remove with inline validation (name required, unique).
- **Shared Memory Config**
  - Toggles for shared vs isolated channels.
  - Summary of last updates (read-only).
- **Step Detail Tabs**
  - **Prompt**: text editor with preview of resolved variables.
  - **Overrides**: per-step sampling overrides (temperature/topP/max tokens) with fallback info.
  - **Memory**:
    - Reads: list of channels + limits.
    - Writes: list of channels, mode, optional payload templates.
  - **Transitions**:
    - On success: next step dropdown, complete toggle.
    - On failure: next step dropdown, fail toggle.
    - Future: conditional transitions (placeholder).
  - **HITL (Input Form)**:
    - Interaction type selector.
    - JSON schema editor with preview (using same validator as backend).
    - Suggested actions table (label, payload, type).
- **Cost Preview**
  - Summary of estimated tokens/cost per step and total.
  - Breakdown: prompt tokens, completion tokens, cost, currency.
- **History & Diff**
  - Modal listing previous versions with change notes.
  - Diff viewer comparing blueprint JSON (use JSON diff highlighter).

### States
- Draft vs Published badges.
- Validation errors overlay (missing start step, broken transitions).
- Loading states while fetching agents, preview data.
- Conflict state if published agent becomes inactive (warning on step).

## 3. Shared UX Elements

- **Validation Toasts** for backend errors (422) with direct links to offending fields.
- **Undo/Redo** buttons for form changes (store local history of edits; highlight limit).
- **RBAC indicators**:
  - Read-only banner when user lacks edit rights.
  - Warnings for operations requiring higher roles (publish).
- **Collaboration presence** (future): show avatars of active editors (placeholder).

## 4. Wireframe References (suggested)

- Wizard step layout (desktop).
- Flow builder main canvas with step detail tabs.
- Cost preview modal.
- Tool catalog selection drawer.
- History/diff modal.

## 5. Integration Notes

- All form components must map to typed DTOs.
- JSON editors should reuse the same schema validation backend uses.
- For multi-step forms, persist draft state locally to avoid losing progress.

