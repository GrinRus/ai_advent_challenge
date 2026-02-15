# AGENTS.md

## Scope
- This file applies to the entire repository: `/Users/griogrii_riabov/grigorii_projects/ai_advent_challenge`.
- Child `AGENTS.md` files in subdirectories override this file for their own subtree when rules conflict.

## Instruction Precedence
- Agents should load instructions from repository root down to the current working directory.
- On conflicts, the deeper `AGENTS.md` takes precedence for that scope.
- References:
  - https://openai.com/index/introducing-codex/
  - https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/src/instructions/hierarchical_agents_message.md
  - https://agents.md/

## Authoring Style
- Keep instructions high-signal and low-ambiguity.
- Prefer concrete commands and explicit constraints over generic advice.
- Only include commands that exist in this repository manifests or CI workflows.

## Repository Map
- `backend/`: Spring Boot backend (Java/Gradle).
- `frontend/`: React + Vite frontend (TypeScript/npm).
- `backend-mcp/`: Spring Boot MCP services (Java/Gradle, profile-based).
- Out of scope for dedicated AGENTS in this pass: `backend-analysis/`, `local-rag/`, `docs/`.

## Universal Workflow
1. Read related code and docs before editing.
2. Keep changes minimal, local, and scoped to the request.
3. Validate with fast checks only by default.
4. Summarize exactly what changed and what was validated.

## Global Guardrails
- Do not edit generated or vendor artifacts unless explicitly requested:
  - `**/build/**`
  - `**/dist/**`
  - `**/node_modules/**`
  - `backend-mcp/treesitter/**` vendored grammars
- Do not commit secrets from `.env` or other local secret files.
- If behavior or contracts change, update relevant docs in `docs/`.

## Fast Validation Matrix
- Changes in `backend/**`: run backend fast checks from `backend/AGENTS.md`.
- Changes in `frontend/**`: run frontend fast checks from `frontend/AGENTS.md`.
- Changes in `backend-mcp/**`: run backend-mcp fast checks from `backend-mcp/AGENTS.md`.

## Module Delegation
- Backend rules: `backend/AGENTS.md`
- Frontend rules: `frontend/AGENTS.md`
- Backend MCP rules: `backend-mcp/AGENTS.md`
