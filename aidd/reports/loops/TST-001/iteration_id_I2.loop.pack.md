---
schema: aidd.loop_pack.v1
updated_at: 2026-03-18T11:29:41Z
ticket: TST-001
work_item_id: I2
work_item_key: iteration_id=I2
scope_key: iteration_id_I2
boundaries:
  allowed_paths:
    - mock/stub
    - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/filter/`
    - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/service/`
    - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/config/`
    - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/matcher/`
  forbidden_paths: []
commands_required:
  - `./gradlew :backend-mcp:test --tests "*RbacWebFilterTest*"`
  - `./gradlew :backend-mcp:test --tests "*AuditLogServiceTest*"`
tests_required:
  - `./gradlew :backend-mcp:test --tests "*RbacWebFilter*" --tests "*AuditLogService*"`
  - profile:targeted
  - filters:`RbacWebFilterTest`, `AuditLogServiceTest`
evidence_policy: RLM-first
reason_code: auto_boundary_extend_warn
---

# Loop Pack — TST-001 / iteration_id_I2

## Work item
- work_item_id: I2
- work_item_key: iteration_id=I2
- scope_key: iteration_id_I2
- goal: Implement RBAC WebFlux filter for mutation API protection and async audit logging

## Read order
- Prefer excerpt; read full tasklist/PRD/Plan/Research/Spec only if excerpt misses Goal/DoD/Boundaries/Expected paths/Size budget/Tests/Acceptance or REVISE needs context.
- Большие логи/диффы — только ссылки на отчёты

## Boundaries
- allowed_paths:
  - mock/stub
  - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/filter/`
  - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/service/`
  - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/config/`
  - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/matcher/`
- forbidden_paths:
  - []

## Commands required
- `./gradlew :backend-mcp:test --tests "*RbacWebFilterTest*"`
- `./gradlew :backend-mcp:test --tests "*AuditLogServiceTest*"`

## Tests required
- `./gradlew :backend-mcp:test --tests "*RbacWebFilter*" --tests "*AuditLogService*"`
- profile:targeted
- filters:`RbacWebFilterTest`, `AuditLogServiceTest`

## Work item excerpt
> - [ ] I2: RBAC WebFilter + Audit logging service (iteration_id: I2)
>   - Goal: Implement RBAC WebFlux filter for mutation API protection and async audit logging
>   - DoD: Filter intercepts POST/PUT/DELETE, returns 403 for unauthorized, audit log entries written, config loaded
>   - Boundaries:
>   - Expected paths:
>     - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/filter/`
>     - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/service/`
>     - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/config/`
>     - `backend-mcp/src/main/java/com/aiadvent/mcp/backend/rbac/matcher/`
>   - Size budget:
>     - max_files: 8
>     - max_loc: 800
>   - Commands:
>   - Tests:
>     - profile: targeted
>     - tasks: `./gradlew :backend-mcp:test --tests "*RbacWebFilter*" --tests "*AuditLogService*"`
>     - filters: `RbacWebFilterTest`, `AuditLogServiceTest`
>   - Acceptance mapping: AC-1 (403 responses), AC-2 (audit logging)
