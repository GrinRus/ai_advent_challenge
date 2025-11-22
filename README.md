# AI Advent Challenge

AI Advent Challenge — инициативный проект по развитию интерактивного LLM-чата и инфраструктуры вокруг него.

## Быстрые ссылки
- `docs/overview.md` — стартовая точка документации.
- `docs/backlog.md` — дорожная карта и волны задач.
- `docs/architecture/` — архитектура backend, frontend и интеграции с LLM.
- `docs/infra.md` — инфраструктура, окружения и CI/CD.
- `docs/guides/telegram-bot.md` — UX, команды и настройки Telegram-бота.
- `docs/processes.md` — соглашения по разработке и тестированию.
- `docs/CONTRIBUTING.md` — чек-лист по поддержке документации.
- `docs/faq.md` — часто задаваемые вопросы.

## Tree-sitter грамматики (Wave 34)
- Грамматики (Java/Kotlin/TS/JS/Python/Go) подключены как submodule’ы в `backend-mcp/treesitter/*`. После клонирования репозитория обязательно выполните `git submodule update --init --recursive backend-mcp/treesitter` (или `git submodule update --init --recursive` для полного восстановления рабочей копии).
- Закреплённые commit hash и sha256 архива каждого субмодуля зафиксированы в `backend-mcp/gradle/libs.versions.toml`.
- Перед сборкой backend выполняйте `./gradlew treeSitterBuild treeSitterVerify bootJar` — Gradle сам соберёт `.so/.dylib/.dll`, сверит контрольные суммы и положит артефакты в `src/main/resources/treesitter/<os>/<arch>`.
- В runtime AST-пайплайн управляется конфигом `github.rag.ast.*` (см. `application-github.yaml`). Основные переменные окружения: `GITHUB_RAG_AST_ENABLED`, `GITHUB_RAG_AST_LANGUAGES`, `GITHUB_RAG_AST_LIBRARY_PATH`, `GITHUB_RAG_AST_FAILURE_THRESHOLD`.

## AST-aware indexing & call graph (Wave 34)

### Настройка окружения

| Переменная | Назначение |
| --- | --- |
| `GITHUB_RAG_AST_ENABLED` | Включает AST-проход (`true`/`false`). |
| `GITHUB_RAG_AST_LANGUAGES` | Список поддерживаемых языков (через запятую, дефолт: `java,kotlin,typescript,javascript,python,go`). |
| `GITHUB_RAG_AST_LIBRARY_PATH` | Путь до собранных либ (`classpath:treesitter` локально, `/app/treesitter` в Docker). |
| `GITHUB_RAG_AST_FAILURE_THRESHOLD` | Сколько подряд ошибок загрузки допускается до деградации. |

Перед сборкой backend выполните:

```bash
git submodule update --init --recursive backend-mcp/treesitter
cd backend-mcp
./gradlew treeSitterBuild treeSitterVerify bootJar
```

В Dockerfile мы вызываем `treeSitterBuild` перед `bootJar`, копируем артефакты `build/treesitter/linux/**` в `/app/treesitter` и задаём `GITHUB_RAG_AST_LIBRARY_PATH=/app/treesitter`.

## Neo4j code graph (Wave 45)
- Основной движок графа — Neo4j (локально — Testcontainers/bolt, в проде — Aura/кластер). Поддерживается fallback на таблицу `repo_rag_symbol_graph`, но после включения Neo4j весь call graph и IDE-подобная навигация строятся из него.
- Граф содержит `Repo → File → Symbol` узлы, а также ребра `CALLS`, `IMPLEMENTS`, `READS_FIELD`, `USES_TYPE`. У каждого символа фиксированный `symbol_fqn` вида `package.Class#method(argTypes)`.

| Переменная | Назначение |
| --- | --- |
| `GITHUB_RAG_GRAPH_ENABLED` | Включает синхронизацию с Neo4j. |
| `GITHUB_RAG_GRAPH_URI` | Bolt/neo4j URI (`bolt://localhost:7687` по умолчанию). |
| `GITHUB_RAG_GRAPH_USERNAME` | Имя пользователя Neo4j. |
| `GITHUB_RAG_GRAPH_PASSWORD` | Пароль/секрет Neo4j. |
| `GITHUB_RAG_GRAPH_DATABASE` | Имя базы данных (обычно `neo4j`). |
| `GITHUB_RAG_GRAPH_LEGACY_TABLE_ENABLED` | Оставляет обновление Postgres-таблицы для fallback. |
| `GITHUB_RAG_GRAPH_SYNC_TIMEOUT` | Таймаут синхронизации одного файла. |
| `GITHUB_RAG_GRAPH_SYNC_RETRY_DELAY` | Пауза между повторными попытками sync. |
| `GITHUB_RAG_GRAPH_SYNC_BATCH_SIZE` | Размер батча `MERGE` операций при записи графа. |

При локальной разработке достаточно запустить `docker run neo4j` или Testcontainers — backend подключится к `bolt://localhost:7687`. Для продакшена настройте `GITHUB_RAG_GRAPH_*` переменные и убедитесь, что `repo.rag_index_status` показывает `graphReady=true` после переиндексации.

### Проверка работоспособности

- `repo.rag_index_status` возвращает `astReady`, `astSchemaVersion`, `astReadyAt`. Если `astReady=false`, call graph автоматически отключается (`neighbor.call-graph-disabled` в warnings).
- `repo.rag_search` должен возвращать `matches[].metadata.ast_available=true`, `symbol_fqn`, `symbol_kind`, `docstring`, `is_test`, `imports[]`, `calls_out[]`, `neighborOfSpanHash`.
- `appliedModules` содержит `post.neighbor-expand` и `neighborStrategy=CALL_GRAPH`, когда namespace AST-ready.
- Регрессии ловим тестом `./gradlew test --tests "*RepoRagIndexServiceMultiLanguageTest"` (использует мини-репозитории Java/TS/Python/Go из `backend-mcp/src/test/resources/mini-repos/**`).

### Диагностика и пример ответа
- При включённом AST-пайплайне `RepoRagIndexService` обогащает каждый chunk полями `symbol_fqn`, `symbol_kind`, `symbol_visibility`, `docstring`, `imports`, `calls_out`, `calls_in`, `is_test`, фиксирует версию схемы (`metadata_schema_version`) и выставляет `ast_available=true`. Эти данные используются `CodeAwareDocumentPostProcessor` и соседними стратегиями.
- Дополнительно строится call graph (`repo_rag_symbol_graph`), в котором сохраняются вызовы между символами (`relation=CALLS|CALLED_BY`). `NeighborChunkDocumentPostProcessor` может подтягивать соседей по `neighborStrategy=CALL_GRAPH`.
- Сервис `RepoRagSymbolService` отдаёт как «входящие» (`findCallGraphNeighbors`) так и «исходящие» (`findOutgoingEdges`) рёбра, кешируя результаты в Caffeine и ограничивая параллелизм (семафор + Micrometer метрики `github_rag_symbol_requests_total{type=...}` и `github_rag_symbol_writer_invocations_total`).
- Инструмент `repo.rag_index_status` теперь возвращает флаги `astReady`, `astSchemaVersion` и `astReadyAt`. Перед выдачей новых AST/neighbor-фичи оператор должен убедиться, что namespace переиндексирован и `astReady=true`; если флаг сброшен — выполните `github.repository_fetch` + дождитесь READY.
- Backfill отсутствует: существующие namespace получают AST/metadata/graph только после повторного `github.repository_fetch`. Для обновления любого репозитория потребуется заново скачать workspace и дождаться `astReady=true`.
- Для регрессии AST/graph-пайплайна заведены мини-репозитории в `backend-mcp/src/test/resources/mini-repos/{java,typescript,python,go}` и интеграционный тест `RepoRagIndexServiceMultiLanguageTest`. Он прогоняет `RepoRagIndexService` end-to-end для каждого языка и проверяет, что `symbol_fqn`, `ast_available`, `calls_out` и call graph появляются в метаданных/`repo_rag_symbol_graph`. Перед изменениями AST обязательно запускайте `./gradlew test --tests \"*RepoRagIndexServiceMultiLanguageTest\"`.
- Включение call graph можно автоматизировать через `GITHUB_RAG_POST_NEIGHBOR_AUTO_CALL_GRAPH_ENABLED` (см. `github.rag.post-processing.neighbor.auto-call-graph-enabled`). Лимит дополнительных соседей задаётся `GITHUB_RAG_POST_NEIGHBOR_CALL_GRAPH_LIMIT`, чтобы CALL_GRAPH расширение не «съедало» контекст.
- На стейдже/проде используйте только линуксовые библиотеки. Dockerfile собирает артефакты командой `./gradlew treeSitterBuild bootJar`, копирует `build/treesitter/linux/**` в `/app/treesitter` и задаёт `GITHUB_RAG_AST_LIBRARY_PATH=/app/treesitter`, чтобы рантайм грузил именно linux-вариант. После изменения грамматик пересоберите контейнеры.
- `github.rag.ast.*` управляет загрузкой грамматик, `github.rag.rerank.code-aware.*` описывает веса docstring/visibility/test метаданных, а `github.rag.post-processing.neighbor.*` включает авто-переключение `CALL_GRAPH`. На практике достаточно выставить только переменные окружения — Spring Boot подтянет YAML дефолты.
- Пример `matches[].metadata` с AST/neighbor полями:
```json
{
  "file_path": "src/main/java/com/example/DemoService.java",
  "chunk_hash": "f24c3b9f...",
  "symbol_fqn": "src.main.java.com.example.DemoService::method process",
  "symbol_kind": "method",
  "symbol_visibility": "public",
  "docstring": "Process incoming events",
  "is_test": false,
  "imports": ["import java.util.List;"],
  "calls_out": ["helper"],
  "neighborOfSpanHash": "f82e7d8a",
  "neighbor_relation": "CALLS"
}
```
`neighborOfSpanHash` указывает, что сниппет был расширен call-graph соседом для исходного `span_hash`. Если AST недоступен, поля просто отсутствуют.

## Repo RAG индексатор (Wave 31)
- После успешного `github.repository_fetch` backend запускает асинхронный job (`RepoRagIndexScheduler`), который обходит workspace, режет файлы на чанки (≈2 КиБ/160 строк) и сохраняет их в PgVector (`repo_rag_vector_store`). Параметры задаются через `GITHUB_RAG_*` (см. `.env.example`, `docs/infra.md`).
- `repo.rag_search` обновлён до v4: semantic chunking с `parent_symbol`/`span_hash`/`overlap_lines`, Compression/Rewrite/Translation QueryTransformers, `MultiQueryExpander` (с ограничением `maxQueries<=6`) и кастомный дедупликатор, который объединяет результаты подзапросов, помечает `generatedBySubQuery` и снимает дубликаты по `chunk_hash`. Поверх этого работают DocumentPostProcessor (heuristic rerank + context-budget + LLM-компрессор). Вход поддерживает `topKPerQuery`, `useCompression`, `multiQuery.maxQueries`, `maxContextTokens`, `filters.languages[]/pathGlobs[]`. Ответ содержит `instructions`, `augmentedPrompt`, `contextMissing`, `noResults`, `noResultsReason` и `appliedModules`.
- Добавлен инструмент `repo.rag_search_simple` — достаточно передать только `rawQuery`, MCP повторно использует репозиторий из последней `github.repository_fetch` и ожидает готовности индекса. Для разведочных запросов по всей базе доступен `repo.rag_search_global`, а для совсем лёгкого сценария — `repo.rag_search_simple_global`, который запускает глобальный поиск без заполнения DTO.
- Практическое руководство и диагностика описаны в `docs/guides/mcp-operators.md`, архитектурные детали модулей — в `docs/architecture/github-rag-modular.md`.
- Метрики `repo_rag_queue_depth`, `repo_rag_index_duration`, `repo_rag_index_fail_total`, `repo_rag_embeddings_total` отслеживаются через Micrometer; рекомендуется настраивать алёрты на рост очереди и повторные ошибки воркеров.

### Code-aware rerank & neighbor expansion (Wave 33)
- Пост-обработка `repo.rag_search` расширена модулем `CodeAwareDocumentPostProcessor`, который учитывает язык запроса, видимость/тип символа, штрафы за пути (`github.rag.rerank.code-aware.path-penalty.*`) и диверсификацию (`diversity.maxPerFile`/`maxPerSymbol`). Входные параметры `codeAwareEnabled` и `codeAwareHeadMultiplier` доступны и в инструментах, и в конфигурации (`github.rag.rerank.code-aware.*`).
- Для стабилизации контекста добавлен `NeighborChunkDocumentPostProcessor`. Он подтягивает соседние чанки без повторного обращения к Vector Store: `LINEAR` (±radius), `PARENT_SYMBOL`, `CALL_GRAPH`. Параметры (`neighborRadius`, `neighborLimit`, `neighborStrategy`) контролируются через `github.rag.post-processing.neighbor.*`. Выданные фрагменты помечаются `metadata.neighborOfSpanHash`, а факт вставки виден в `appliedModules+=post.neighbor-expand`.
- Хранение связей «кто кого вызывает» выполняет таблица `repo_rag_symbol_graph` (change set `github-rag-0005`). Существующие namespace продолжают работать в прежнем формате до следующего `github.repository_fetch`/индексации — фонового backfill-а нет, поэтому после обновления схемы обязательно перезапустите fetch.

### Free-form coverage & dual responses (Wave 37)
- Профили RAG получили параметры `minScoreFallback`, `minScoreClassifier`, `overviewBoostKeywords[]`. Для overview-запросов (`что за проект`, `описание проекта`, README-наброски) Guard переводит план в режим `LOW_THRESHOLD`, `repo.rag_search` выполняет повторный проход с более низким порогом и форсированным multi-query. В `appliedModules` появятся `retrieval.low-threshold` и `retrieval.overview-seed`, а в `warnings[]` — уведомление о fallback.
- DTO инструментов поддерживает `responseChannel=summary|raw|both`. По умолчанию возвращаются два канала: `summary` (краткое резюме из `prompts/github-rag-summary.st`) и `rawAnswer` (полный augmented prompt). Клиенты могут скрывать один из каналов или отдавать оба в UI.
- Ответ теперь всегда содержит `warnings[]`, которые агрегируют автокоррекции санитайзера и fallback-события сервиса; их удобно сопоставлять с appliedModules и логами `rag_parameter_guard`.

## Telegram бот (Wave 27)

- Настройки окружения перечислены в `docs/infra.md` (`TELEGRAM_*`). Быстрый старт:
  ```bash
  export TELEGRAM_BOT_TOKEN=...            # токен из BotFather
  export TELEGRAM_BOT_USERNAME=ai_advent_bot
  export TELEGRAM_BOT_WEBHOOK_URL=https://<domain>/telegram/update
  export TELEGRAM_BOT_WEBHOOK_SECRET=<случайная-строка>
  docker compose up --build backend
  ```
- После запуска выполните `POST /api/telegram/webhook/register` (планируется автоматизация) либо используйте BotFather для задания webhook.
- Команды `/start`, `/new`, `/menu`, голосовые сообщения и inline-меню описаны в `docs/guides/telegram-bot.md`.
- STT использует OpenAI Omni (`gpt-4o-mini-transcribe`) с опциональным fallback (`TELEGRAM_STT_FALLBACK_MODEL`).

## LLM-провайдеры

| Провайдер  | Модель        | Класс    | Стоимость (вход/выход, $ за 1K токенов) | Рекомендации |
|------------|---------------|----------|------------------------------------------|--------------|
| zhipu.ai   | GLM-4.6       | pro      | 0.0006 / 0.0022                          | расширенные сценарии, длинный контекст и код |
| zhipu.ai   | GLM-4.5       | standard | 0.00035 / 0.00155                        | основной ассистент, баланс качества и цены |
| zhipu.ai   | GLM-4.5 Air   | budget   | 0.0002 / 0.0011                          | черновые ответы, предпросмотры UI |
| OpenAI     | GPT-4o Mini   | budget   | 0.00015 / 0.0006                         | быстрые пользовательские фичи, демо |
| OpenAI     | GPT-4o        | pro      | 0.0012 / 0.0032                          | сложные задачи, критичные запросы |

Подробная схема конфигурации (`app.chat.providers`) и инструкции по переменным окружения — в `docs/infra.md`.

## Режимы работы чата

- **Streaming** — `POST /api/llm/chat/stream`, сервер отправляет SSE-события (`session`, `token`, `complete`, `error`). Подходит для повседневного диалога и быстрого обратного ответа.
- **Sync** — `POST /api/llm/chat/sync`, backend возвращает plain `ChatSyncResponse` (текст, provider/model, usage/cost, latency) с заголовками `X-Session-Id` и `X-New-Session`.
- **Structured Sync** — `POST /api/llm/chat/sync/structured`, backend возвращает JSON (`StructuredSyncResponse`) по схеме Spring AI `BeanOutputConverter`. Для OpenAI подключается `responseFormat(JSON_SCHEMA)`/`strict=true`, для ZhiPu формат подмешивается в промпт. Подробности и примеры находятся в `docs/infra.md`.

Обе формы принимают одинаковый payload (`sessionId`, `message`, `provider`, `model`, `options`). Если `sessionId` не передан, создаётся новый диалог.

## Пользовательские параметры sampling

- Панель «Параметры sampling» содержит sliders/inputs для `temperature`, `topP`, `maxTokens`. Значения подтягиваются из каталога провайдера и автоматически обновляются при смене модели.
- Изменённые числа передаются в `options.*` и выделяются в истории сообщений (чип `Temp … · TopP … · Max …` для ассистента).
- Кнопка «Сбросить к дефолтам» очищает overrides, поэтому блок `options` не попадает в запрос, а backend использует штатные значения из `app.chat.providers`.
- Логика сборки payload вынесена в `frontend/src/lib/chatPayload.ts` и прикрыта тестами: `npm run test:unit` (Vitest) и `npx playwright test sampling-overrides.spec.ts`.

## Саммаризация длинных диалогов

- Перед каждым запросом к LLM срабатывает единая префлайт-проверка: если ожидаемый промпт превышает `CHAT_MEMORY_SUMMARIZATION_TRIGGER`, задача попадает в очередь summarizer-а.
- Пул воркеров ограничен `CHAT_MEMORY_SUMMARIZATION_MAX_CONCURRENT`, очередь — `CHAT_MEMORY_SUMMARIZATION_MAX_QUEUE_SIZE`. Метрики `chat_summary_queue_size` и `chat_summary_queue_rejections_total` помогают отслеживать насыщение.
- Устойчивость: до трёх попыток с backoff 250→2000 мс, счётчики `chat_summary_failures_total` и `chat_summary_failure_alerts_total` сигнализируют о проблемах провайдера.
- Flow-память использует тот же механизм (`FlowMemorySummarizerService`), summary сохраняются в `flow_memory_summary`, а чтение каналов всегда отдаёт «summary + хвост».
- Ручной пересчёт — `POST /api/admin/chat/sessions/{id}/summary/rebuild`; массовый бэкфилл активируется `CHAT_MEMORY_SUMMARIZATION_BACKFILL_ENABLED=true`.

## Репозитории и сервисы
- `backend/` — Spring Boot приложение (Java 22).
- `backend-mcp/` — HTTP MCP серверы (Agent Ops, Flow Ops, Insight, GitHub, Notes).
- `frontend/` — веб-клиент на React + Vite.
- `local-rag/` — вспомогательные утилиты и эксперименты с RAG (при необходимости).

Подробнее о организации кода и задачах — в документации.
