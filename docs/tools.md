# MCP Tools Cheat Sheet (excerpt)

## Coding MCP

| Tool | Назначение | Основные параметры |
|------|------------|-------------------|
| `coding.generate_patch` | Генерирует diff через Claude CLI (GLM). Патч сохраняется в `PatchRegistry`, можно запускать review/apply/dry-run. | `workspaceId`, `instructions`, `targetPaths`, `forbiddenPaths`, `contextFiles[]`. Инструкции ≤ 4000 символов, diff ≤ 256 КБ. |
| `coding.generate_artifact` | Упрощённый генератор файлов на GPT-4o Mini. Возвращает JSON операций, применяет изменения и отдаёт `git diff`. Использует общие `OPENAI_*` переменные. | `workspaceId`, `instructions`, `targetPaths`, `forbiddenPaths`, `contextFiles[]`, `operationsLimit`. Ограничения по умолчанию: ≤ 8 файлов, ≤ 2000 строк и ≤ 200 КБ каждый. |
| `coding.review_patch` | Быстрое LLM-ревью (риски, тесты, миграции) поверх ранее созданного патча. | `workspaceId`, `patchId`, опционально `focus[]`. |
| `coding.apply_patch_preview` | Dry-run: `git apply --check`, временное применение diff, `git diff --stat`, откат + опциональный gradle-runner. | `workspaceId`, `patchId`, `commands[]`, `dryRun`, `timeout`. |
| `coding.list_patches` | Список активных патчей (метаданные, статус dry-run). | `workspaceId`. |
| `coding.discard_patch` | Удаляет патч из in-memory реестра. | `workspaceId`, `patchId`. |

> Совет: для быстрой генерации boilerplate используйте `coding.generate_artifact`, а для сложных правок, требующих ручного dry-run/review, оставайтесь на `coding.generate_patch`.

## GitHub RAG — Graph tools (Wave 45)

| Tool | Назначение | Основные параметры |
|------|------------|-------------------|
| `repo.code_graph_neighbors` | Получить соседей символа из Neo4j (CALLS/IMPLEMENTS/READS_FIELD/USES_TYPE). | `namespace`, `symbolFqn`, `direction` (OUTGOING/INCOMING/BOTH), `relation` (опц.), `limit` (по умолчанию 16). Возвращает `nodes[]` (fqn, file, kind, lines) и `edges[]` (from, to, relation, chunkHash, chunkIndex). |
| `repo.code_graph_definition` | Найти определение символа. | `namespace`, `symbolFqn`. Возвращает file/kind/visibility/lines. |
| `repo.code_graph_path` | Кратчайший путь между двумя символами (по умолчанию до 4 ребер). | `namespace`, `sourceFqn`, `targetFqn`, `relation` (опц.), `maxDepth`. Возвращает `nodes[]`/`edges[]` для визуализации или пояснений. |

> Требования: `GITHUB_RAG_GRAPH_ENABLED=true`, доступ к Neo4j (`GITHUB_RAG_GRAPH_URI/USERNAME/PASSWORD`). Если граф не включён, инструменты вернут ошибку.

**Graph lens в поисковой выдаче**
- `repo.rag_search` и `repo.rag_search_global` автоматически добавляют в `matches[].metadata.graph_neighbors` список `nodes[]/edges[]`, если включён граф и у чанка есть `symbol_fqn` с `ast_version >= RepoRagIndexService.AST_VERSION`.
- Модуль `graph.lens` появляется в `appliedModules`, чтобы UI мог подсветить использование графа.
- Если граф недоступен или символ без AST, результаты вернутся как раньше без `graph_neighbors`.
