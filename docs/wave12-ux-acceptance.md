# Wave 12 — UX Acceptance Criteria & Testing Checklist

## 1. Agent Constructor (Wizard)

### Acceptance Criteria
1. User can progress through wizard steps only after required fields are valid.
2. Provider/model dropdown shows metadata (sync/stream/structured) matching backend catalog.
3. `Validate` action blocks navigation to Review when API returns errors; warnings display but allow continue.
4. `Preview` response shows diff, cost estimate, tool coverage matching backend payloads.
5. Tool binding selection must reflect schema version; mismatched versions raise warning.
6. Cost profile requires currency; leaving blank triggers inline error.
7. Saving draft posts normalized `AgentInvocationOptions`; publishing handles only DRAFT status.
8. Read-only mode for published versions disables inputs and hides destructive actions.

### Testing Checklist
- [ ] Mandatory fields per step (provider, model, system prompt, cost currency) show inline errors.
- [ ] Switching provider resets incompatible model/tool choices.
- [ ] Memory policy input constraints (no negative values, summarization selection) validated.
- [ ] Retry policy multiplying logic respected (timeout, attempt validation).
- [ ] Tool binding request override editor performs JSON validation.
- [ ] Advisor toggles update preview summary correctly.
- [ ] Validate API errors rendered (network failure, 422) with retry.
- [ ] Preview diff highlights changed fields (template, retry policy, cost).
- [ ] Saving offline (network down) surfaces non-blocking error with retry CTA.
- [ ] Undo/redo across steps retains state.
- [ ] Draft auto-save guards against data loss.

## 2. Flow Builder

### Acceptance Criteria
1. Launch parameters use typed inputs; flow blueprint stores `name`, `type`, `required`, `default`, `schema`.
2. Step editor prevents selection of unpublished/inactive agents.
3. Transition editor prevents linking to non-existent steps and warns on cycles.
4. Memory read/write forms enforce channel names and limit/ mode constraints.
5. Interaction schema editor validates JSON schema before save.
6. Cost preview modal pulls from `FlowLaunchPreviewService` and reflects per-step totals.
7. Publishing requires all steps to reference published agents and pass validation.
8. History modal shows change notes and diff between versions.

### Testing Checklist
- [ ] Creating new launch parameter ensures unique name, required toggle persists.
- [ ] Agent change warns if draft agent selected.
- [ ] Removing a step updates transitions (no orphan references).
- [ ] Overrides tab fallback to agent default values when blank.
- [ ] HITL configuration: invalid JSON schema surfaces error.
- [ ] Cost preview handles missing pricing by showing fallback messaging.
- [ ] Publishing blocked when validation fails (e.g., missing start step, invalid transition).
- [ ] History diff compares blueprint JSON accurately.
- [ ] Undo/redo across step list ordering and field edits.
- [ ] RBAC: read-only users see disabled inputs and “insufficient permissions” tooltip.
- [ ] Concurrent edit notification placeholder (if not implemented, ensure note in UI).

## 3. Operations / Launch Experience

### Acceptance Criteria
1. Launch form displays typed launch parameters, validations, and error states.
2. Manual approval steps display forms built from interaction schema.
3. Telemetry panel surfaces cost/latency updated per step.
4. Ops can pause/resume/cancel with confirmation dialog; state updates accordingly.

### Testing Checklist
- [ ] Launch with missing required parameter returns inline error.
- [ ] Manual interaction form rejects payload failing schema.
- [ ] Cost/usage timeline updates after each step.
- [ ] Pause prevents background job execution; resume re-enqueues.
- [ ] Cancel transitions session to CANCELLED, hides further actions.

## 4. RBAC & Collaboration

### Acceptance Criteria
1. Permissions determine visibility of create/edit/publish actions (e.g., `AgentDesigner`, `FlowOrchestrator`, `Ops` roles).
2. Attempting restricted action returns 403 and shows UI feedback.
3. Multiple users editing same definition show warning or locked state (manual for now).

### Testing Checklist
- [ ] Designer role: can edit agents, cannot publish flows.
- [ ] Orchestrator role: can edit/publish flows, read-only on agents.
- [ ] Ops role: can launch/pause/resume, read-only on constructors.
- [ ] Unauthorized publish returns 403; UI displays message.
- [ ] Simulated concurrent edit triggers warning banner.

