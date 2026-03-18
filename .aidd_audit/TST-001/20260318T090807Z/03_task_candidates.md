# Task candidates (catalog-constrained)

1. `FS-RBAC-03` — Live RBAC enforcement + admin role operations
- Fit: High
- Why: Existing `ProfileAdminController`, `RoleAssignmentService` usage, and `AdminRoles.tsx` provide implementation base; backlog has open RBAC enforcement items.
- Risk: Medium (cross-cutting enforcement and session role refresh).

2. `FS-GRAPH-05` — Code graph IDE navigation (outline + anchors + target path)
- Fit: Medium
- Why: Backlog has explicit open graph payload/outline/path tasks; architecture already contains graph modules.
- Risk: High (large backend graph contract + frontend visualization changes).

3. `FS-ID-04` — VK OAuth + Telegram Login Widget profile linking
- Fit: Medium
- Why: Backlog has explicit open wave for VK/Telegram auth linking and profile attach.
- Risk: High (security-sensitive OAuth/hash validation + external provider flows).

4. `FS-GA-01` — GitHub Analysis Flow production-ready UX + canonical payload
- Fit: Medium-Low
- Why: GitHub MCP and flow foundations exist, but tracked repo currently lacks clear dedicated `github-analysis-flow` controller/DTO baseline in current HEAD.
- Risk: High (new orchestration + payload + UI stream progress in one scope).
