# Graph & Tree-sitter Troubleshooting Checklist

## 1. Быстрые проверки
1. `repo.rag_index_status`: `ready=true`, `graphReady=true`, `graphSchemaVersion>=1`, `graph_sync_error=null`. Если `graphReady=false`, повторите `github.repository_fetch`.
2. Логи `GraphSyncService`: Cypher-ошибки, превышение батчей, таймауты Testcontainers. При ошибке повторите sync; namespace не будет помечен `graphReady`.
3. CI: выполните `./gradlew test --tests "com.aiadvent.mcp.backend.github.rag.RepoRagSearchServiceGraphIntegrationTest"` и `./gradlew test --tests "com.aiadvent.mcp.backend.github.rag.ast.AstFileContextFactoryNativeModeTest"`.

## 2. Tree-sitter health
1. `./gradlew treeSitterVerify` — убеждаемся, что `treesitter/<os>/<arch>` заполнен.
2. Для macOS/Windows запускать backend c `--enable-native-access=ALL-UNNAMED`.
3. Отсутствующие грамматики → `git submodule update --init --recursive backend-mcp/treesitter` и `./gradlew treeSitterBuild`.

## 3. Neo4j recovery
1. Остановите `github-mcp`, очистите Neo4j (или используйте новую базу/DB).
2. Повторите `github.repository_fetch` для нужного namespace.
3. Проверяйте `graph_nodes_total`/`graph_edges_total{relation}` метрики.

## 4. Сигналы для операторов
1. `backend-mcp-graph-alert` в CI оставит комментарий/summary, если графовый smoke упал.
2. `repo.rag_search` добавляет `neighbor.graph-disabled` в `warnings[]`, если граф недоступен.
3. Временно можно переключить профиль на `neighborStrategy=LINEAR`/`PARENT_SYMBOL`.

## 5. Когда привлекать devops
- Ошибки `libjava-tree-sitter`/`UnsatisfiedLinkError` → проверьте наличие библиотек в Docker-образе.
- Повторяющиеся `graph_sync_failure_total` или рост `graph_lookup_throttled_total` → граф перегружен, возможно нужен отдельный Neo4j кластер.
