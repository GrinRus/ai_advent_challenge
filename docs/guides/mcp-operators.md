# MCP Servers Guide for Operators & Analysts

Этот документ описывает, как подключаться к внутренним MCP-серверам (Flow Ops, Agent Ops, Insight, GitHub, Notes), запускать их локально и безопасно пользоваться инструментами.

## Запуск инфраструктуры

1. Установите Docker Desktop (или совместимый движок), убедитесь, что `docker compose` доступен из CLI.
2. Склонируйте репозиторий и скопируйте `.env.example` → `.env`. Отредактируйте блок `*_MCP_*`, если хотите подключаться к staging/prod backend-у.
3. Поднимите окружение:
 ```bash
  docker compose up --build backend frontend agent-ops-mcp flow-ops-mcp insight-mcp github-mcp coding-mcp
  ```
  - `backend` — основной REST API.
  - `agent-ops-mcp`, `flow-ops-mcp`, `insight-mcp`, `github-mcp`, `notes-mcp`, `coding-mcp` — HTTP MCP-сервера (Spring Boot, streamable transport). Контейнеры слушают порт `8080`; на хост по умолчанию пробрасываются порты `7091`, `7092`, `7093`, `7094`, `7097`, `7098`.
  - Переменные `AGENT_OPS_BACKEND_BASE_URL`, `FLOW_OPS_BACKEND_BASE_URL`, `INSIGHT_BACKEND_BASE_URL`, `GITHUB_API_BASE_URL` указывают базовый URL API бэкенда/внешнего сервиса; по умолчанию — `http://backend:8080` для внутренних MCP и `https://api.github.com` для GitHub.

## Подключение backend → MCP

Backend использует `spring.ai.mcp.client.streamable-http.connections.<server>` для внутренних сервисов и `spring.ai.mcp.client.stdio.connections.perplexity` для Perplexity. Для HTTP каждый сервер описывается парой `url` + `endpoint` (по умолчанию `/mcp`).

- Backend внутри docker-compose автоматически получает адреса `http://agent-ops-mcp:8080`, `http://flow-ops-mcp:8080`, `http://insight-mcp:8080`, `http://github-mcp:8080`.
- В `docker-compose.yml` сервис `backend` теперь ожидает успешный healthcheck всех HTTP MCP (`agent-ops-mcp`, `flow-ops-mcp`, `insight-mcp`, `github-mcp`), чтобы Spring AI не падал при инициализации клиента.
- При запуске backend на хостовой машине укажите:
  ```bash
  export AGENT_OPS_MCP_HTTP_BASE_URL=http://localhost:7091
  export FLOW_OPS_MCP_HTTP_BASE_URL=http://localhost:7092
  export INSIGHT_MCP_HTTP_BASE_URL=http://localhost:7093
  export GITHUB_MCP_HTTP_BASE_URL=http://localhost:7094
  export NOTES_MCP_HTTP_BASE_URL=http://localhost:7097
  export CODING_MCP_HTTP_BASE_URL=http://localhost:7098
  export CODING_MCP_HTTP_ENDPOINT=/mcp
  ```
  При необходимости измените порты (`AGENT_OPS_MCP_HTTP_PORT`, `FLOW_OPS_MCP_HTTP_PORT`, `INSIGHT_MCP_HTTP_PORT`, `GITHUB_MCP_HTTP_PORT`, `NOTES_MCP_HTTP_PORT`, `CODING_MCP_HTTP_PORT`) в `docker-compose.yml`.
- Перplexity продолжает работать через STDIO: убедитесь, что бинарь/скрипт `perplexity-mcp` доступен в `PATH`, либо переопределите `PERPLEXITY_MCP_CMD`.

## IDE и внешние клиенты

- MCP использует стандарт [Model Context Protocol](https://modelcontextprotocol.dev). Внутренние сервисы доступны по HTTP/SSE, а Perplexity — через STDIO.
- Для подключения к HTTP-серверам выберите "Custom MCP (HTTP)" и укажите:
  - Flow Ops: `URL=http://localhost:7092`, `Endpoint=/mcp`
  - Agent Ops: `URL=http://localhost:7091`, `Endpoint=/mcp`
  - Insight: `URL=http://localhost:7093`, `Endpoint=/mcp`
  - GitHub: `URL=http://localhost:7094`, `Endpoint=/mcp`
  - Coding: `URL=http://localhost:7098`, `Endpoint=/mcp`
- Для Perplexity используйте STDIO команду (`perplexity-mcp --api-key ...`) или собственный wrapper. IDE обычно позволяет указать произвольную команду запуска.
- Для защищённых сред вместо проброса портов используйте reverse proxy/SSH-туннель. MCP-серверы — обычные Spring Boot приложения, поэтому можно разворачивать их за ingress-контроллером или API Gateway. GitHub MCP требует Personal Access Token с правами `repo`, `read:org`, `read:checks`, переданный через переменную `GITHUB_PAT`.

### Repo RAG (GitHub MCP)
1. **После fetch:** `github.repository_fetch` возвращает `workspaceId` и ставит job в очередь индексатора. Следите за прогрессом через `repo.rag_index_status` (`status`, `progress`, `etaSeconds`, `lastError`). Ждём `ready=true`, иначе search вернёт пустой контекст.
2. **Быстрый поиск:** `repo.rag_search_simple` принимает только `rawQuery` и повторно использует репозиторий из последней `github.repository_fetch` (если индекс уже готов). Возвращает тот же DTO, что и `repo.rag_search` (`matches`, `summary`, `rawAnswer`, `appliedModules`, `warnings`). Если READY namespace отсутствует, инструмент вернёт инструкцию повторить fetch.
3. **Глобальный поиск:** `repo.rag_search_global` ищет по всей базе RAG (все READY namespace) и принимает те же параметры, что `repo.rag_search`, но без `repoOwner/repoName`. `matches[].metadata.repo_owner` / `repo_name` показывают, где найден чанк, `displayRepoOwner/displayRepoName` — лишь подписи для UI.
4. **AST readiness:** `repo.rag_index_status` возвращает поля `astReady`, `astSchemaVersion`, `astReadyAt`. Пока `astReady=false`, MCP автоматически переключает профили на линейных/parent соседей и добавляет в `warnings[]` запись `neighbor.call-graph-disabled`. Если нужно задействовать `neighborStrategy=CALL_GRAPH`, выполните `github.repository_fetch`, дождитесь `ready=true` и `astReady=true`. Для диагностик проверяйте, что `astSchemaVersion>=1` и время `astReadyAt` свежее последнего fetch.
> После применения миграций `github-rag-0005` (таблица `repo_rag_symbol_graph`) существующие namespace переходят на новый формат только после следующего `github.repository_fetch` и полного индексирования. Фонового backfill-а нет — обязательно повторите fetch для критичных репозиториев.

#### Апгрейд AST-шейпинга
- **Нет backfill-а.** Любая миграция AST/metadata применяется лишь на следующем полном `github.repository_fetch`. Если репозиторий важен для операторов — запланируйте повторный fetch сразу после выката backend-а.
- **Порядок действий:** (1) `github.repository_fetch` → дождитесь READY; (2) перепроверьте `repo.rag_index_status` — значения `astReady=true`, `astSchemaVersion>=1`, `astReadyAt` свежее времени fetch; (3) только после этого включайте профили с `neighborStrategy=CALL_GRAPH` либо операторские сценарии, которые зависят от `symbol_fqn/docstring/calls_*`.
- **Автовключение call graph.** Параметр `GITHUB_RAG_POST_NEIGHBOR_AUTO_CALL_GRAPH_ENABLED` (см. `github.rag.post-processing.neighbor.auto-call-graph-enabled`) позволяет backend-у автоматически переключать LINEAR/PARENT стратегии на `CALL_GRAPH`, когда namespace отмечен как AST-ready. Лимит для соседей задаётся `GITHUB_RAG_POST_NEIGHBOR_CALL_GRAPH_LIMIT`. На namespace без AST фича сама отключится и в `warnings[]` появится `neighbor.call-graph-disabled`.
- **Диагностика:** если `astReady=false`, но fetch уже завершён, проверьте логи `RepoRagIndexService` — там фиксируются ошибки загрузки Tree-sitter, пропуски файлов или превышение лимитов. Для принудительного переиндекса стоит повторить fetch (даже если `ready=true`) — scheduler пересоздаст индекс и call graph.
- **Чтение метаданных.** В ответе `repo.rag_search` появятся новые ключи `symbol_fqn`, `symbol_kind`, `symbol_visibility`, `symbol_signature`, `docstring`, `is_test`, `imports[]`, `calls_out[]`, `calls_in[]`, `ast_available`. Соседние чанки получают `neighborOfSpanHash`, `neighbor_relation`, `neighbor_symbol`. Ориентируйтесь на эти поля, чтобы объяснять пользователю, почему тот или иной фрагмент попал в контекст.
  | Поле | Что означает |
  | --- | --- |
  | `symbol_fqn` | Полное имя символа: `file_path` → `.` + `::` + имя контейнера/метода. |
  | `symbol_kind` / `symbol_visibility` | Тип (`class`, `method`, `function`, `def`, `func`) и модификатор (`public`, `private`, `internal`). |
  | `docstring` | Объединённые комментарии перед символом. Используйте их для поиска устаревшей документации. |
  | `is_test` | Признак, что сниппет относится к тестовому коду (используется Code-aware rerank). |
  | `calls_out[]` / `calls_in[]` | Ссылки на вызываемые/вызывающие символы — по ним строится `repo_rag_symbol_graph` и стратегия `neighborStrategy=CALL_GRAPH`. |
  | `ast_available` / `ast_version` | Флаг и версия AST-прохода; если `false`, namespace ещё не переиндексирован и call graph отключён. |
  | `neighborOfSpanHash` / `neighbor_relation` | Какой чанк был источником и каким типом соседства он расширен (`CALLS`, `CALLED_BY`, `LINEAR`). |
- **Пример `matches[].metadata`:**
  ```json
  {
    "file_path": "src/main/java/com/example/DemoService.java",
    "chunk_hash": "f24c3b9f...",
    "symbol_fqn": "src.main.java.com.example.DemoService::method process",
    "docstring": "Process incoming events",
    "is_test": false,
    "calls_out": ["helper"],
    "neighborOfSpanHash": "f82e7d8a",
    "neighbor_relation": "CALLS"
  }
  ```
  Если набор ключей пустой — значит namespace ещё не переиндексирован в AST-режиме.

#### Operator playbook: AST-aware этап

1. **Как понять, что всё сработало**
   - `repo.rag_index_status`: `ready=true`, `astReady=true`, `astSchemaVersion>=1`, `astReadyAt` свежее последнего fetch.
   - `repo.rag_search`: `matches[].metadata.ast_available=true`, заполнены `symbol_fqn`/`symbol_kind`, а в `appliedModules` появляются `post.neighbor-expand` и (для call graph) `neighborStrategy=CALL_GRAPH`.
   - `warnings[]` пусты или содержат только информативные записи (`profile:balanced`, `retrieval.overview-seed`) без `neighbor.call-graph-disabled`.
2. **Типичные ошибки**
   - `neighbor.call-graph-disabled` → namespace ещё проходит индексирование или AST не загрузился.
   - `Tree-sitter load failure` / `ast.degraded` в логах `github-mcp` → проблемы с нативными либами (`/app/treesitter/**`) или превышен `failureThreshold`.
   - `RepoRagSymbolService` логирует `Symbol graph lookup throttled` → service ограничил параллельные запросы, call graph вернёт неполные данные.
3. **Как чинить**
   - Выполнить `github.repository_fetch` → дождаться `ready=true`, `astReady=true`, затем повторить `repo.rag_search`.
   - Убедиться, что грамматики доступны: `git submodule update --init --recursive backend-mcp/treesitter && ./gradlew treeSitterBuild treeSitterVerify bootJar`, пересобрать Docker-образ (нужны только linux `.so`).
   - Временно переключить профиль/DTO на `neighborStrategy=LINEAR` или `PARENT_SYMBOL`, чтобы пользователи продолжали работу без call graph.
4. **Checklist перед релизом**
   - `./gradlew test --tests "*RepoRagIndexServiceMultiLanguageTest"` — фиксирует регрессии AST/call graph на фикстурах Java/TS/Python/Go.
   - UI/CLI e2e: fetch → rag_search (`neighborStrategy=CALL_GRAPH`), убедиться, что UI отображает `neighborOfSpanHash` и `appliedModules+=post.neighbor-expand`.
   - Сообщить операторам, какие namespace уже AST-ready, какие профили используют call graph и какие лимиты (`neighborLimit`, `maxContextTokens`) актуальны.

#### IDE-подобная навигация (контроллер → сервис → репозиторий)
Чтобы повторить сценарий «IDE показывает переходы между слоями», используйте графовые инструменты `repo.code_graph_neighbors` и `repo.code_graph_path` вместе с `repo.rag_search`:

1. **Проверка графа.** Выполните `repo.rag_index_status` и убедитесь, что `graphReady=true`, `graphSchemaVersion>=1`. Если не так — повторите `github.repository_fetch` и дождитесь завершения индексации.
2. **Найдите стартовый символ.** Через `repo.rag_search` (или `repo.rag_search_simple`) найдите контроллер/handler. Сохраните `symbol_fqn` из `matches[].metadata`.
3. **Просмотрите ближайших соседей.** Вызовите `repo.code_graph_neighbors` c `direction=OUTGOING` и `relation=CALLS`, чтобы увидеть сервис, который вызывает контроллер:
   ```json
   {
     "namespace": "repo:owner/demo",
     "symbolFqn": "com.demo.api.Controller#handle",
     "direction": "OUTGOING",
     "relation": "CALLS",
     "limit": 5,
     "depth": 2
   }
   ```
   Ответ содержит `nodes[]` (FQN, файлы, строки) и `edges[]` (тип связи, chunkHash) — аналог навигации по references в IDE.
4. **Постройте путь до репозитория.** Если нужно показать полный маршрут «контроллер → сервис → репозиторий», воспользуйтесь `repo.code_graph_path`:
   ```json
   {
     "namespace": "repo:owner/demo",
     "sourceSymbolFqn": "com.demo.api.Controller#handle",
     "targetSymbolFqn": "com.demo.repo.UserRepository#save",
     "maxDepth": 6
   }
   ```
   MCP вернёт кратчайший путь, который можно визуализировать в UI или процитировать оператору.
5. **Сопоставьте с выдачей поиска.** Запрос `repo.rag_search` автоматически добавит `graph_neighbors`/`graph_path` в `matches[].metadata`, если граф доступен. Если ключей нет или в `warnings[]` появилось `neighbor.graph-disabled`, значит граф ещё не готов.
6. **Диагностика.** Для локального smoke используйте `./gradlew test --tests "com.aiadvent.mcp.backend.github.rag.RepoRagSearchServiceGraphIntegrationTest"` — он поднимает Neo4j через Testcontainers, синхронизирует мини-репозиторий и подтверждает, что `graph_neighbors`/`graph_path` попадают в ответ `repo.rag_search`.

#### Troubleshooting Graph & Tree-sitter
1. **`graphReady=false` / `neighbor.graph-disabled`.** Проверьте `repo.rag_index_status`. Если `ready=true`, но `graphReady=false`, скорее всего `GraphSyncService` не успел завершиться. Повторите `github.repository_fetch` и дождитесь `graphReady=true`. Для одиночного namespace можно вызвать `RepoRagIndexService` с `graphLegacyTableEnabled=true`, чтобы временно вернуться на Postgres.
2. **Восстановление графа.** При подозрении на повреждённый граф выполните `docker compose stop github-mcp`, очистите Neo4j (или выберите новую базу), затем повторите `github.repository_fetch` для нужных namespace. Health-check `RepoRagSearchServiceGraphIntegrationTest` подтверждает, что код способен индексировать mini-repo и получать `graph_neighbors`/`graph_path` в ответе. Если тест падает, проверьте логи `GraphSyncService` — там указываются Cypher-ошибки и namespace.
3. **Tree-sitter native failure (`ast.degraded`).** Такие записи в логах означают, что `TreeSitterAnalyzer` не смог загрузить `libjava-tree-sitter.*` или языковую грамматику. Убедитесь, что перед запуском выполнены `git submodule update --init --recursive backend-mcp/treesitter` и `./gradlew treeSitterBuild treeSitterVerify`. Для macOS/Windows запускайте backend с `--enable-native-access=ALL-UNNAMED`. Smoke-тест `AstFileContextFactoryNativeModeTest` детектирует отсутствие библиотек и проверяет graceful fallback.
4. **CI отлавливает регрессии.** Job `backend-mcp-graph-smoke` в GitHub Actions гоняет `treeSitterVerify` и интеграционный тест поиска с Neo4j/Testcontainers. Если он упал — смотрите логи `RepoRagSearchServiceGraphIntegrationTest` (GraphSync) и `AstFileContextFactoryNativeModeTest` (недостающие грамматики).

5. **Расширенный поиск (`repo.rag_search` v4):**
   - Вход: `rawQuery`, `topK`, `topKPerQuery`, `minScore`, `minScoreByLanguage`, `history[]`, `previousAssistantReply`, `allowEmptyContext`, `useCompression`, `translateTo`, `multiQuery.enabled/queries/maxQueries`, `maxContextTokens`, `generationLocale`, `instructionsTemplate`, `filters.languages[]/pathGlobs[]`, `filterExpression`.
   - Настройки пост-обработки:
     * `codeAwareEnabled` (по умолчанию `true`) и `codeAwareHeadMultiplier` (дефолт `2.0`, максимум `4.0`) управляют code-aware переупорядочиванием головы списка документов.
     * `neighborRadius`, `neighborLimit`, `neighborStrategy` (`OFF|LINEAR|PARENT_SYMBOL|CALL_GRAPH`) задают расширение соседних чанков. Значения радиуса ограничены `github.rag.post-processing.neighbor.max-radius` (по умолчанию 5), лимит — `github.rag.post-processing.neighbor.max-limit` (по умолчанию 12, абсолютный потолок 400). Если поле не передано, берётся конфигурация `github.rag.post-processing.neighbor.*`.
   - MCP автоматически валидирует лимиты (`topK<=40`, `maxQueries<=6`, `maxContextTokens<=4000`); ошибки прилетают до вызова Spring AI.
   - Выход: `matches`, `augmentedPrompt`, `instructions`, `contextMissing`, `noResults`, `noResultsReason`, `appliedModules`, `warnings`, `summary`, `rawAnswer`. `responseChannel` (`summary|raw|both`) управляет генерацией summary/rawAnswer.
   - `appliedModules` отражает цепочку модулей (semantic chunking → compression → rewrite → translation → multi-query → post.code-aware → post.heuristic-rerank → post.neighbor-expand → context-budget → llm-компрессор). Если модуль отсутствует, он отключён или не внёс изменений.
   - Конфигурация:
     * `github.rag.rerank.code-aware.*` — веса `score.weight`/`span.weight`, `language-bonus.{lang}`, `symbol-priority.{class,method_private,...}`, списки `path-penalty.allowPrefixes`/`denyPrefixes` + `penaltyMultiplier`, лимиты `diversity.maxPerFile`/`maxPerSymbol`, дефолт и максимум для `codeAwareHeadMultiplier`.
     * `github.rag.post-processing.neighbor.*` — включение/отключение, дефолты радиуса/лимита, верхние границы (≤5 и ≤12 соответственно) и стратегия по умолчанию.
4. **Разбор ответов:** `instructions` — готовый system prompt, `augmentedPrompt` — текст, который пойдёт в LLM (сырые сниппеты). `summary` и `rawAnswer` формируются в зависимости от `responseChannel`. `matches[].metadata.generatedBySubQuery` объясняет, какой подзапрос вернул чанк, `neighborOfSpanHash` показывает, что сниппет расширен neighbor-процессором. Поля `symbol_fqn`, `docstring`, `calls_out`, `ast_available` появляются только после успешного AST-прохода; если они пустые, namespace ещё не переиндексирован. Все автоподстановки и fallback'и (например, `profile нормализован`, `repoOwner заполнен автоматически`, `overview retry`) всегда отражаются в `warnings[]` и дублируются в `appliedModules` (`profile:balanced`, `retrieval.low-threshold`, `retrieval.overview-seed`, `generation.summary`). Это позволяет оператору принимать решение, показывать ли предупреждение пользователю.
5. **Новый контракт (Wave 36/38):** инструменты `repo.rag_search`, `repo.rag_search_global`, `repo.rag_search_simple`, `repo.rag_search_simple_global` принимают только задокументированные поля. MCP сам подставляет owner/name, `displayRepo*`, профиль и лимиты, а все изменения сообщает в `warnings[]`. Клиенты не должны вручную пробрасывать `topK`, `multiQuery` и пр. — достаточно выбрать профиль (`conservative`/`balanced`/`aggressive` или кастомный), остальное делается автоматически.
6. **Наблюдаемость:** `appliedModules` всегда содержит элемент `profile:<name>` — по нему видно, какой пресет применился. Warnings также показывают автозамены (`profile нормализован до balanced`, `repoName заполнен автоматически`). Графики `repo_rag_queue_depth`, `repo_rag_index_duration`, `repo_rag_index_fail_total`, `repo_rag_embeddings_total` остаются актуальными: при очереди >5 или ≥3 fail подряд проверяйте `/var/tmp/aiadvent/mcp-workspaces` и зависшие job.
7. **Runbook:** логируйте `repoOwner/repoName`, `profile`, `appliedModules`, `contextMissing`, `noResults`. При ручной очистке workspace всегда повторяйте fetch → status → search. Если агенту нужен более агрессивный контекст, переключайте `profile`, а не пытаетесь пробросить `topK` вручную.

#### Профили параметров RAG
- `github.rag.default-profile` — имя профиля, который применяется по умолчанию (значение можно переопределять через `GITHUB_RAG_DEFAULT_PROFILE` или `application-prod.yaml`). Используйте его, чтобы мгновенно переключать стратегию между `conservative`, `balanced`, `aggressive` без изменений кода.
- `github.rag.parameter-profiles` — список стратегий. Каждый профиль задаёт `topK`, `topKPerQuery`, `minScore`, `minScoreByLanguage`, блок `multiQuery` (`enabled`, `queries`, `maxQueries`), `neighbor` (`strategy`, `radius`, `limit`) и настройки code-aware (`codeAwareEnabled`, `codeAwareHeadMultiplier`). Рабочий пример трёх профилей смотрите в `backend-mcp/src/main/resources/application-github.yaml`.
- Для локальных override используйте стандартный Spring синтаксис: `GITHUB_RAG_PARAMETER_PROFILES_0__NAME=custom`, `GITHUB_RAG_PARAMETER_PROFILES_0__TOP_K=6`, `GITHUB_RAG_PARAMETER_PROFILES_0__NEIGHBOR__STRATEGY=OFF` и т.д. После перезапуска MCP новый профиль доступен инструментам `repo.rag_*`.
- **Важно:** если агент попытается пробросить старые поля (`topK`, `neighborLimit`, `multiQuery` и т.д.), MCP сразу вернёт ошибку. Единственный поддерживаемый способ изменить поведением поиска — выбрать другой `profile` или обновить параметры профиля на сервере.

#### Нормализация входных DTO
- Все инструменты `repo.rag_*` теперь прогоняют входные данные через `RepoRagToolInputSanitizer`: строки триммятся, `neighbor*`, `multiQuery`, `generationLocale` получают дефолты из `application.yaml`, `filters.languages` приводятся к lower-case, а универсальные глобы (`*`, `**/*`) выкидываются.
- Если `repoOwner/repoName` (для `repo.rag_search`) или `displayRepo*` (для `repo.rag_search_global`) отсутствуют, санитайзер пытается использовать последний READY namespace (`github.repository_fetch`). При отсутствии готового индекса инструмент вернёт ошибку с подсказкой повторить fetch.
- Синонимы/локализации: `neighborStrategy` понимает `graph`, `callgraph`, `parent`, `off/disabled`; языковые фильтры — `py`, `js`, `c#`, `golang`; `translateTo` — `english/английский`, `russian/русский`. Диапазоны (`topK≤40`, `neighborLimit≤конфигурации`, `multiQuery.maxQueries≤6`) жёстко валидируются и при превышении приводятся к допустимому значению.
- `instructionsTemplate` работает в режиме «жёлтая карточка»: `{{query}}` автоматически заменяется на `{{rawQuery}}`, неизвестные плейсхолдеры удаляются. Все автоисправления возвращаются в `warnings[]` ответа (`repo.rag_search`, `repo.rag_search_global`, `repo.rag_search_simple`) и учитываются метрикой `repo_rag_tool_input_fix_total`.
- Если `astReady=false`, в `warnings[]` появится сообщение вида `neighbor.call-graph-disabled` — это не ошибка, а индикатор того, что call graph ещё строится для данного namespace.

> Подробнее о LEGO-модулях pipeline см. `docs/architecture/github-rag-modular.md`.

#### Когда вызывать `repo.rag_search_simple`
- Используется сразу после свежего `github.repository_fetch`, когда оператор или агент тестирует общий тон запроса («расскажи про архитектуру», «какие шаги сборки?») и пока не нужно фильтровать по языку/пути.
- Требования: хотя бы один READY namespace (иначе инструмент падает) и уверенность, что текущая сессия работает именно с последним fetch (иначе вызывайте обычный `repo.rag_search` с явными параметрами).
- Запрос содержит ровно одно поле `rawQuery`. MCP автоматически подставит repoOwner/repoName из fetch и включит дефолтные модули (compression, translation, multi-query).
- Возможные ответы:
  * `contextMissing=true` и `noResultsReason="CONTEXT_NOT_FOUND"` — индекс готов, но похожих чанков нет. Сообщите пользователю и предложите уточнить запрос.
  * Исключение «Нет активного репозитория» — агент забыл вызвать fetch/подождать READY; повторите `github.repository_fetch` → `repo.rag_index_status`.
- Рекомендации для LLM:
  1. Перед вызовом удостоверься, что пользователь явно говорил о последнем fetch. Если есть сомнения относительно нужного репозитория, переключайся на `repo.rag_search`.
  2. Если запрос требует фильтрации (например «покажи только .ts файлы»), простой инструмент не подойдёт — сразу используй расширенный вариант.

#### Когда вызывать `repo.rag_search_simple_global`
- Сценарии: «быстро посмотри по всей базе», когда ещё не выбраны owner/name или fetch не делали. Принимает только `rawQuery`.
- Не требует активного fetch: если его не было, инструмент всё равно выполнит `repo.rag_search_global`, а в UI подписи будут `global/global`. Если fetch READY, подписи подставляются автоматически, чтобы пользователь видел знакомый репозиторий.
- Логика поиска/генерации идентична `repo.rag_search_global`, но без необходимости заполнять длинный DTO. Исключительно для разведки и быстрых follow-up вопросов.

#### Когда вызывать `repo.rag_search_global`
- Сценарии разведки: «найди любой пример feature flag», «где описаны политики безопасности». Инструмент ищет сразу по всем READY namespace и возвращает список смешанных чанков.
- Вход: `rawQuery` + те же дополнительные параметры, что у `repo.rag_search` (фильтры, multi-query, maxContextTokens). Owner/name не нужны.
- В ответе обязательно проверяйте `matches[].metadata.repo_owner`/`repo_name`, чтобы понять источник. Эти значения стоит цитировать в последующих сообщениях.
- Если результат пустой, проверь `noResultsReason`: `INDEX_NOT_READY` означает, что база ещё не полная (подождите индексатор или уточните фильтры).
- Рекомендации:
  1. Используй глобальный поиск только тогда, когда пользователь явно просит «найти где угодно» или owner/name неизвестны. Для уточнённых задач в одном репозитории выбери `repo.rag_search`.
  2. Всегда напоминай пользователю, что ответ собран из разных репозиториев, и цитируй путь вместе с `repo_owner/repo_name`, чтобы избежать путаницы.

#### Когда вызывать `repo.rag_search`
- Это основной инструмент для всех сценариев: уточнённые запросы, фильтры по путь/языку, ручная настройка мульти-запросов или бюджетов токенов.
- Обязательные аргументы: `repoOwner`, `repoName`, `rawQuery`. Всё остальное — опциональные тюнинги. LLM должна передавать только необходимые override, чтобы не нарушать лимиты.
- Типичные параметры:
  * `filters.languages[] / pathGlobs[]` — если нужно ограничиться языком или подпапкой.
  * `multiQuery.enabled/queries/maxQueries` — когда пользователь просит «разбей вопрос на несколько аспектов».
  * `maxContextTokens` — при подготовке длинных ответов, чтобы сократить контекст.
  * `allowEmptyContext=false` — когда пользователь требует отказа при пустом индексе.
  * `instructionsTemplate` — если нужно переопределить системный промпт (например, попросить цитировать конкретным форматом).
- Ответ всегда содержит:
  * `matches[]` — список чанков (путь, сниппет, метаданные). Сохраняй их для последующих вызовов инструмента или генерации патча.
  * `augmentedPrompt` — тот же текст, с которым ContextualQueryAugmenter пойдёт в LLM; полезно передавать напрямую в генерацию.
  * `instructions` — готовый system prompt (учитывает шаблон/локаль).
  * `appliedModules` — подтверждает, какие этапы сработали (compression, multi-query, llm-compression и т.д.).
  * `noResults`/`contextMissing`/`noResultsReason` — помогают решать, что делать дальше (ожидать индекс, переформулировать запрос, собрать больше контекста).
- Рекомендации для LLM:
  1. Перед повторным вызовом старайся добавить короткую историю (`history[]` или `previousAssistantReply`), чтобы QueryTransformers понимали контекст диалога и подбирали правильные follow-up запросы.
  2. Никогда не превышай задокументированные лимиты (`topK<=40`, `maxQueries<=6`, `maxContextTokens<=4000`). При сомнении лучше опускай параметр — сервер подставит безопасный дефолт.
  3. Если пользователь явно попросил «дождись индексации», сначала проверяй `repo.rag_index_status`. Только после статуса READY вызывай `repo.rag_search`, иначе получишь пустой ответ или исключение.

## Примеры запросов

### Flow Ops
```
User: Покажи опубликованные flow и сравни версии 12 и 13 для `lead-qualification`.
Tool: flow_ops.list_flows → flow_ops.diff_flow_version → flow_ops.validate_blueprint
```

### Agent Ops
```
User: Создай черновой агент `demo-agent`, включи инструменты agent_ops.* и подготовь capability payload.
Tool: agent_ops.list_agents → agent_ops.register_agent → agent_ops.preview_dependencies
```

### Insight
```
User: Найди последние 10 сессий типа FLOW, затем покажи метрики для `sessionId=...`.
Tool: insight.recent_sessions → insight.fetch_metrics
```

### GitHub
```
User: Покажи открытые PR по ветке `feature/rework`, затем верни diff и проверки для PR #42.
Tool: github.list_pull_requests → github.get_pull_request → github.get_pull_request_diff → github.list_pull_request_checks
```

- `github.list_repository_tree` — выводит дерево файлов и директорий; `path` относительный (пустая строка → весь репозиторий), `recursive=true` включает подкаталоги. `maxDepth` допускается 1–10 (по умолчанию 3), `maxEntries` ограничивает результат (по умолчанию 500, верхний предел задаёт `github.backend.tree-max-entries`); при срезе ответ помечается `truncated=true`, `resolvedRef` содержит SHA дерева.
- `github.read_file` — загружает blob по относительному пути, уважает лимит `github.backend.file-max-size-bytes` (512 КБ по умолчанию). Возвращает base64 и `textContent`, если файл не бинарный.
- `github.list_pull_requests` — фильтры `state` (`open|closed|all`, регистронезависимо), `base`, `head`, сортировки `created|updated|popularity|long_running`, лимит 1–50 (по умолчанию 20). При достижении лимита выставляется `truncated=true`.
- `github.get_pull_request` — детальные метаданные (labels, assignee, reviewers/teams, merge state, maintainerCanModify, mergeCommitSha и т. п.).
- `github.get_pull_request_diff` — агрегированный unified diff по файлам; `maxBytes` ограничивает размер (по умолчанию 1 МиБ), `truncated=true` сигнализирует обрезку, `headSha` показывает текущий SHA ветки.
- `github.list_pull_request_comments` — раздельно issue- и review-комментарии; лимиты 0–200 (по умолчанию 50, `0` отключает выдачу), возвращает флаги `issueCommentsTruncated`/`reviewCommentsTruncated`.
- `github.list_pull_request_checks` — check run'ы и commit status'ы; лимиты 0–200 (по умолчанию 50), в ответе `overallStatus`, `headSha`, массивы с флагами `truncated`.
- `github.create_pull_request_comment` — issue- или review-комментарий; при указании `location` требуются файл и строка/диапазон/позиция, выполняется дедуп по body+координатам.
- `github.create_pull_request_review` — ревью с действием `APPROVE`/`REQUEST_CHANGES`/`COMMENT` или `PENDING`, поддерживает пакет `comments` (каждый с координатами), перед созданием ищет дубликаты.
- `github.submit_pull_request_review` — завершает черновое ревью; требует `reviewId`, проверяет обязательность `body` для `COMMENT` и `REQUEST_CHANGES`.
- `github.repository_fetch` — скачивает репозиторий в workspace (стратегия по умолчанию `ARCHIVE_WITH_FALLBACK_CLONE`), поддерживает `options` (`shallowClone`, `cloneTimeout`/`archiveTimeout`, `archiveSizeLimit`, `detectKeyFiles`). Возвращает `workspaceId`, путь, `resolvedRef`, `commitSha`, размеры скачивания и список ключевых файлов.
- `github.workspace_directory_inspector` — анализирует ранее скачанный workspace: `includeGlobs`/`excludeGlobs`, `maxDepth` (по умолчанию 4), `maxResults` (по умолчанию 400, максимум 2000), `includeHidden`, `detectProjects`. Возвращает список элементов, признаки Gradle/Maven/NPM и рекомендации по `projectPath`.
- `github.workspace_git_state` — **SAFE** инструмент. Принимает `workspaceId` и опции (`includeFileStatus`, `includeUntracked`, `maxEntries`, `includeSummaryOnly`). Отдаёт блок `branch{name,headSha,upstream,detached,ahead,behind}`, сводку `status{clean,staged,unstaged,untracked,conflicts}` и (опционально) список файлов (`path`, `previousPath`, `changeType`, staged/unstaged). Используйте перед `coding.generate_patch`/`coding.apply_patch_preview`, чтобы подтвердить ветку и чистоту workspace; при `clean=false` обязательно зафиксируйте решение в чате.
- `github.create_branch` — `MANUAL`. Требует `repository`, `workspaceId`, `branchName`; опционален `sourceSha`. Валидирует имя ветки, убеждается, что ветка не существует удалённо, создаёт удалённый ref и локальный checkout.
- `github.commit_workspace_diff` — `MANUAL`. Формирует commit из изменений workspace (`branchName`, `commitMessage`, `author{name,email}`), отклоняет пустой diff, возвращает SHA и список файлов со статистикой `additions/deletions`.
- `github.push_branch` — `MANUAL`. Публикует локальные коммиты (`force=false`), проверяет чистоту workspace, сообщает локальный и удалённый SHA и число отправленных коммитов.
- `github.open_pull_request` — `MANUAL`. Создаёт PR (`headBranch` ≠ `baseBranch`), валидирует названия веток, проверяет права на push, назначает reviewers/teams и возвращает ссылки и SHA базовой/целевой веток.
- `github.approve_pull_request` — `MANUAL`. Создаёт review с действием `APPROVE`; `body` опционален (при пустом тексте создаётся формальный approve).
- `github.merge_pull_request` — `MANUAL`. Выполняет merge (`MERGE|SQUASH|REBASE`), перед операцией проверяет `mergeable`, позволяет переопределить `commitTitle`/`commitMessage`, возвращает `mergeSha` и время выполнения.

#### Порядок инструментов (ветка → код → коммит → push → PR)
1. `github.repository_fetch` / `github.workspace_directory_inspector` — готовят workspace и подтверждают базовый SHA.
2. `github.create_branch` — создаёт локальную/удалённую ветку **до** внесения любых правок; в ответе фиксируются `branchName`, `sourceSha`, `workspaceId`.
3. `coding.generate_patch` → `coding.review_patch` — формируют изменения внутри новой ветки согласно инструкциям оператора.
4. `coding.apply_patch_preview` — dry-run (git apply + whitelisted команды). Без успешного dry-run коммит/пуш заблокированы.
5. `github.commit_workspace_diff` — фиксирует изменения в коммите; отклоняет пустой diff или «грязный» workspace.
6. `github.push_branch` — публикует ветку без force-параметров.
7. `github.open_pull_request` — создаёт PR; далее по необходимости `github.approve_pull_request` и `github.merge_pull_request`.

#### Чек-лист безопасного выпуска
- Запустите `github.workspace_git_state` сразу после `github.repository_fetch` или перед `coding.generate_patch`: если `status.clean=false` либо ветка не совпадает с ожиданиями, остановите поток и очистите workspace перед публикацией.
- Убедитесь, что workspace получен через `github.repository_fetch`, а последний dry-run (`coding.apply_patch_preview`) завершился успехом и зафиксирован в UI/чат-логе.
- Просмотрите diff (`git status`/`git diff`) в локальном workspace и подтвердите, что commit message отражает суть изменений и не содержит секретов.
- Перед `github.push_branch` проверьте, что ветка отсутствует на удалённом репо либо действительно должна быть обновлена; при необходимости удалите устаревшие локальные изменения (`git clean -fd`).
- После `github.open_pull_request` убедитесь, что head/base SHA отображаются в ответе инструмента, а статус проверок в GitHub зелёный до вызова `github.merge_pull_request`.
- В случае сбоев (конфликт, отклонение ревью) обновите состояние в чате и повторите подготовку патча; запрещено выполнять force-push — инструмент отклонит такой запрос.

#### Примеры JSON

```json
{
  "tool": "github.repository_fetch",
  "arguments": {
    "repository": {"owner": "sandbox-co", "name": "demo-service", "ref": "heads/main"},
    "requestId": "sync-123",
    "options": {
      "strategy": "ARCHIVE_WITH_FALLBACK_CLONE",
      "shallowClone": true,
      "detectKeyFiles": true
    }
  }
}
```

```json
{
  "tool": "github.create_branch",
  "arguments": {
    "repository": {"owner": "sandbox-co", "name": "demo-service", "ref": "heads/main"},
    "workspaceId": "workspace-1234",
    "branchName": "feature/improve-logging"
  }
}
```

```json
{
  "tool": "github.workspace_git_state",
  "arguments": {
    "workspaceId": "workspace-12345",
    "includeFileStatus": true,
    "includeUntracked": true,
    "maxEntries": 100
  }
}
```

```json
{
  "tool": "github.open_pull_request",
  "arguments": {
    "repository": {"owner": "sandbox-co", "name": "demo-service", "ref": "heads/main"},
    "headBranch": "feature/improve-logging",
    "baseBranch": "main",
    "title": "Improve logging around retry handler",
    "body": "## Summary\n- add structured logs\n- document retry policy",
    "reviewers": ["qa-automation"],
    "teamReviewers": ["platform"],
    "draft": false
  }
}
```

Ответ `github.open_pull_request` содержит `pullRequestNumber`, `headSha`, `baseSha`, которые нужно сохранить в журнале операции, чтобы при необходимости выполнить `github.approve_pull_request` или `github.merge_pull_request`.

#### Sandbox-сценарии
1. Создайте тестовую ветку: `github.create_branch` → `github.commit_workspace_diff` (правка README) → `github.push_branch`. Проверьте, что ветка появилась на GitHub и содержит только ожидаемые файлы.
2. Сформируйте PR: вызывайте `github.open_pull_request`, затем из веб-интерфейса убедитесь, что reviewer/teams назначены правильно. Для отклонения сценария уберите один из обязательных параметров — инструмент вернёт 4xx с расшифровкой.
3. Апрув/мердж: после обновления статуса проверок выполните `github.approve_pull_request` и `github.merge_pull_request`. В случае незелёного CI инструмент вернёт ошибку, которую нужно зафиксировать и устранить.

## Gradle MCP flow (`github-gradle-test-flow`)

Flow позволяет операторам запускать Gradle-тесты в Docker поверх любого репозитория GitHub. Его можно стартовать из раздела `Flows → github-gradle-test-flow` или прямо из чата, выбрав режим *Gradle tests*.

### Шаги и статусы

| Статус в UI | Инструмент | Детали |
|-------------|------------|--------|
| `fetching` | `github.repository_fetch` | Скачивает repo/ref, фиксирует `workspaceId`, `resolvedRef`, объём архива. При ошибке GitHub flow завершается со статусом `FAILED`. |
| `inspecting_workspace` | `github.workspace_directory_inspector` | Сканирует workspace (maxDepth=4, maxResults=400) и предлагает `projectPath`. Если найдено несколько Gradle-модулей, агент задаёт уточняющий вопрос. |
| `running_tests` | `docker.build_runner` | Монтирует workspace и Gradle cache, запускает `./gradlew <tasks>` или `gradle -p <path>`. В ответе — `exitCode`, `stdout`/`stderr` чанки, длительность и использованная команда. |
| `report_ready` | Flow завершён | Итоговый отчёт содержит резюме агента (PASSED/FAILED), tail логов и советы по устранению ошибок. |

### Минимальный сценарий

1. Пользователь вводит: «Запусти `test,check` для `sandbox-co/demo-service`, ветка `release/1.9`».
2. Flow создаёт `requestId=gradle-<timestamp>` и запускает `github.repository_fetch`.
3. `workspace_directory_inspector` находит `service-app` и предлагает использовать его как `projectPath`.
4. `docker.build_runner` вызывается с `tasks=["test","check"]`, `timeoutSeconds=900`, сеть отключена.
5. Flow событий: `fetching → inspecting_workspace → running_tests → report_ready`. Если `exitCode != 0`, агент возвращает статус `FAILED` и приложение подсвечивает кнопку «Retry step».

#### Пример диалога
```
Operator: Запусти gradle тесты для sandbox-co/demo-service, ветка release/1.9 — задачи test,check.
Flow: Статус fetching — готовлю workspace... (requestId=gradle-2025-11-13-01)
Flow: Статус inspecting_workspace — нашёл модули [service-app, service-worker]. Нужен service-app?
Operator: Да, выбираем service-app.
Flow: Статус running_tests — docker.build_runner exitCode=0, duration=145s. Готово!
```
Flow снабжает каждый шаг ссылкой на события; payload `step.phase` = `fetching|inspecting_workspace|running_tests`, что позволяет UI и внешним клиентам отображать прогресс.

### Рекомендации операторам

- Перед запуском уточняйте `tasks` и `projectPath`. Если путь известен заранее, укажите его в параметрах Flow, чтобы пропустить вопрос на шаге инспекции.
- В статусе `inspecting_workspace` проверяйте, что нужный модуль есть в списке `entries`. При отсутствии — повторите шаг с другими `includeGlobs`.
- При ошибке Gradle:
  1. Скопируйте tail stdout/stderr из отчёта (они уже сохранены в ответе `docker.build_runner`).
  2. Запустите «Retry step», добавив аргументы (`--rerun-tasks`, `--info`) либо переменные окружения (`env: {"CI":"true"}`).
  3. Если нужен доступ к сети (npm/yarn), временно установите `DOCKER_RUNNER_ENABLE_NETWORK=true` и задокументируйте причину.
- После успешного запуска добавьте ссылку на Flow в канал/тикет и сохраните `workspaceId` — по нему можно пересобрать артефакты или загрузить логи.
> Примечание: сеть в `docker-runner-mcp` теперь включена по умолчанию (`DOCKER_RUNNER_ENABLE_NETWORK=true`), чтобы Gradle мог скачивать зависимости. Для полностью изолированных прогонов выставьте переменную в `false` перед запуском сервиса.

### Примеры JSON

```json
{
  "tool": "docker.build_runner",
  "arguments": {
    "workspaceId": "workspace-12345",
    "projectPath": "service-app",
    "tasks": ["test", "check"],
    "arguments": ["--info"],
    "env": {"CI": "true"},
    "timeoutSeconds": 900
  }
}
```

```json
{
  "tool": "github.workspace_directory_inspector",
  "arguments": {
    "workspaceId": "workspace-12345",
    "includeGlobs": ["**/build.gradle*", "**/settings.gradle*"],
    "maxResults": 400,
    "detectProjects": true
  }
}
```

### Наблюдаемость

- `github_repository_fetch_*`, `workspace_inspection_*`, `docker_gradle_runner_*` доступны на `/actuator/prometheus` у сервисов `backend` и `docker-runner-mcp`.
- GitHub fetch, workspace inspector и Docker runner пишут структурированные события `gradle_mcp.fetch.*`, `gradle_mcp.inspect.*`, `gradle_mcp.docker.run.*` с `requestId`, идентификатором workspace, длительностью и кодами возврата — используйте их для диагностики инцидентов.
- `requestId` передавайте в параметрах Flow — он попадает в логи fetch/inspector и облегчит поиск следов в Kibana.
- Если `docker_gradle_runner_duration` стабильно растёт или `docker_gradle_runner_failure_total` > 3 за 15 минут, проверьте Docker-хост (disk space, доступность сокета) и параметры `DOCKER_RUNNER_IMAGE`, `DOCKER_RUNNER_TIMEOUT`.

### Notes
```
User: Сохрани заметку "Сводка звонка" и подбери похожие записи за последние встречи.
Tool: notes.save_note → notes.search_similar
```

- `notes.save_note` — сохраняет заметку, валидирует размеры (`title ≤ 160`, `content ≤ 4000`, `tags ≤ 25`), вычисляет SHA-256 контента для идемпотентности и индексирует документ в PgVector (`note_vector_store`).
- `notes.search_similar` — векторный поиск по заметкам пользователя с косинусным расстоянием. Поддерживает `topK` (1..50) и `minScore` (0..0.99). Метаданные (`user_namespace`, `user_reference`, `tags`) доступны в ответе.
- При необходимости можно указать `metadata` (JSON), который сохраняется вместе с заметкой и возвращается в результатах поиска.
- Финансовые/операционные ошибки возвращаются как 5xx (например, недоступность OpenAI). В логах `notes-mcp` ищите `NotesStorageException`/`NotesValidationException`.

### Coding
```
User: Сгенерируй исправление NullPointerException в `UserService`, покажи риски и выполни dry-run.
Tool: coding.generate_patch → coding.review_patch → coding.apply_patch_preview
```

- `coding.generate_patch` принимает `workspaceId`, инструкции и опциональные списки `targetPaths`, `forbiddenPaths`, `contextFiles`. Инструкции триммируются, длина ограничена 4000 символами; списки `targetPaths` и `forbiddenPaths` не должны пересекаться. Лимиты: diff ≤ 256 КБ, ≤ 25 файлов, контекст ≤ 256 КБ на файл (обрезка помечается в snippets).
- `coding.review_patch` подсвечивает риски, отсутствие тестов и миграции; аргумент `focus` поддерживает `risks|tests|migration` (регистр не важен, по умолчанию включены все). Инструмент обновляет флаг `requiresManualReview` для патча.
- `coding.apply_patch_preview` помечен как `MANUAL`: в UI/Telegram запускается только после явного подтверждения оператора. Выполняет `git apply --check`, временное применение diff, `git diff --stat`, затем откат. Выполняется только первая непустая команда из `commands` (поддерживаются `./gradlew`/`gradle`, пустые команды пропускаются), дефолтный таймаут 10 минут. Ответ содержит изменённые файлы, предупреждения, рекомендации и метрики (`gitApplyCheck`, `filesTouched`, `durationMs`).
- `coding.list_patches` возвращает все активные патчи workspace: статус (`generated`/`applied`/`discarded`), `requiresManualReview`, `hasDryRun`, `annotations`, `usage`, временные метки.
- `coding.discard_patch` помечен как `MANUAL`: удаляет выбранный патч из реестра и возвращает сводку последнего состояния.
- После создания ветки (`github.create_branch`) успешный dry-run разблокирует оставшиеся GitHub write-инструменты (`commit_workspace_diff` → `push_branch` → `open_pull_request`). При ошибках оператор получает список конфликтов/логов и может повторить генерацию патча.

**Claude Code CLI / GLM**
- CLI подключён к endpoint `https://api.z.ai/api/anthropic` (GLM-4.5/4.6). Ключ хранится в `ZHIPU_API_KEY`; убедитесь, что он определён перед запуском `coding-mcp`.
- Переключатели: `CODING_CLAUDE_ENABLED` (feature-flag), `CODING_CLAUDE_MODEL` (GLM профиль), `CODING_CLAUDE_MAX_RETRIES`, `CLAUDE_CODE_BIN`, `CLAUDE_CODE_TIMEOUT`.
- Проверка здоровья: `docker compose exec coding-mcp claude --version`. При ошибках генерации смотрите логи coding-mcp (`promptBytes/contextBytes/diffBytes`) и stderr CLI (уже маскируется).

```json
{
  "tool": "coding.generate_patch",
  "arguments": {
    "workspaceId": "notes-1",
    "instructions": "Добавь README и настройку линтера.",
    "targetPaths": ["docs/README.md", "config/lint.gradle.kts"],
    "forbiddenPaths": ["src/main/resources/secret.env"],
    "contextFiles": [
      {"path": "docs/index.md", "maxBytes": 20000},
      {"path": "config/settings.gradle.kts"}
    ]
  }
}
```

```json
{
  "tool": "coding.apply_patch_preview",
  "arguments": {
    "workspaceId": "notes-1",
    "patchId": "abc123",
    "commands": ["./gradlew test --info"],
    "dryRun": true,
    "timeout": "PT12M"
  }
}
```

## Безопасность

- Инструменты Flow Ops и Agent Ops имеют права на изменение production-конфигураций. Используйте API-токены с минимальными привилегиями (`*_BACKEND_API_TOKEN`) и ограничивайте доступ к контейнерам через firewall/ssh.
- Ограничьте сеть и аутентификацию HTTP MCP (mTLS, OAuth2 proxy). Минимум — закрыть порты 7091-7093 во внешнем фаерволе и экспонировать их через VPN.
- Логи MCP не должны содержать конфиденциальные данные; используйте централизованный сбор (stdout контейнера) и ротацию.

## Тестирование

- Unit: `McpCatalogServiceTest`, `McpHealthControllerTest`, `McpHealthIndicatorTest` покрывают доступность инструментария.
- Integration/e2e: при запуске `docker compose up` выполните `./gradlew test --tests *Mcp*` и UI Playwright-сценарии, которые включают MCP-инструменты в чате.
