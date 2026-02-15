# AGENTS.md (backend-mcp)

## Scope
- Applies only to `/Users/griogrii_riabov/grigorii_projects/ai_advent_challenge/backend-mcp/**`.
- Overrides repository root rules for backend-mcp-specific decisions.

## Architecture Anchors
- Single Gradle codebase with profile-based MCP servers via `application-*.yaml`.
- Critical domains: `github/rag`, `coding`, `notes`, `analysis`, `docker`.
- Tree-sitter integration and submodules under `backend-mcp/treesitter`.

## Fast Checks
- Run targeted tests:
  - `./gradlew test --tests "<affected test class pattern>"`
- Run Tree-sitter verification when touching AST/tree-sitter/RAG graph code:
  - `./gradlew treeSitterVerify`
- Run focused RAG tests when touching graph/RAG pipeline:
  - `./gradlew test --tests "com.aiadvent.mcp.backend.github.rag.*"`

## Change Rules
- Profile config edits in `application-*.yaml` must remain consistent with tool availability and endpoint contracts.
- RAG/graph behavior changes must preserve guardrails and input sanitizer behavior.
- AST-related changes must account for native loading and fallback/degraded paths.

## Do Not
- Do not modify vendored Tree-sitter grammars in `backend-mcp/treesitter/**` unless explicitly requested.
- Do not commit temporary workspace artifacts or generated native libraries.
