# Wave 45 — Graph AST & IDE Navigation

## Ключевые изменения
- Весь GitHub RAG теперь строит граф кода на Tree-sitter 0.25.6: `TreeSitterParser` работает как Spring-бин, сам подгружает запросы и fallback-ы, а `AstFileContextFactory` использует только проверенный нативный режим.
- `GraphSyncService` синхронизирует узлы/рёбра в Neo4j, а `RepoRagSearchService` добавляет `graph_neighbors` и `graph_path` к топовым чанкам (модуль `graph.lens`). MCP получил `repo.code_graph_neighbors`, `repo.code_graph_definition`, `repo.code_graph_path`.
- CI дополнен job `backend-mcp-graph-alert`, который пишет в summary/PR комментарий, если графовый smoke (`treeSitterVerify` + Neo4j) упал.
- Документация обновлена: сценарий «контроллер → сервис → репозиторий» в `docs/guides/mcp-operators.md` и runbook `docs/runbooks/graph-troubleshooting.md` с чек-листом.

## Как использовать
1. Убедитесь, что namespace проиндексирован и граф готов:
   ```jsonc
   {
     "tool": "repo.rag_index_status",
     "args": {"repoOwner": "owner", "repoName": "demo"}
   }
   ```
   Значения `graphReady=true`, `graphSchemaVersion>=1` и отсутствие `graph_sync_error` означают готовность IDE-линзы.
2. Найдите стартовый символ через `repo.rag_search` и сохраните `symbol_fqn` из `matches[].metadata`.
3. Постройте окружающий контекст:
   ```jsonc
   {
     "tool": "repo.code_graph_neighbors",
     "args": {
       "namespace": "repo:owner/demo",
       "symbolFqn": "com.demo.api.Controller#handle",
       "direction": "OUTGOING",
       "relation": "CALLS",
       "limit": 6
     }
   }
   ```
   Ответ покажет соседей, файлы и строки — аналог перехода по references.
4. Чтобы показать полный путь «контроллер → сервис → репозиторий», вызовите `repo.code_graph_path` с `sourceFqn`/`targetFqn`. MCP вернёт `nodes[]/edges[]`, которые можно процитировать пользователю или визуализировать в UI.
5. Если граф недоступен, новый runbook `docs/runbooks/graph-troubleshooting.md` описывает быструю диагностику (`treeSitterVerify`, `RepoRagSearchServiceGraphIntegrationTest`, очистка Neo4j, проверка нативных библиотек).
