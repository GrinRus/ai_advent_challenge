# GitHub RAG Indexing (Wave 30)

## Цели
- Автоматически индексировать содержимое любого репозитория сразу после успешного `github.repository_fetch`.
- Давать агентам и операторам единый namespace `repo:<owner>/<name>` для поиска релевантных фрагментов без привязки к конкретному fetch/workspace.
- Обеспечить устойчивую очередь индексатора с автоповторами, чтобы исключить ручные перезапуски.
- Предоставить инструменты `repo.rag_index_status` и `repo.rag_search`, которые можно использовать из flows/чатов без знания внутреннего устройства.

## Поток данных
1. `github.repository_fetch` завершился успешно и вернул `workspaceId`, `owner`, `name` и текущий `ref`.
2. `RepoRagIndexScheduler` регистрирует (или переиспользует) job `repo_rag_index_job` со статусом `QUEUED` и кладёт идентификатор в очередь.
3. `RepoRagIndexWorker` (асинхронный executor) вычитывает job, заполняет статус `RUNNING`, запускает `RepoRagIndexService`.
4. `RepoRagIndexService` обходит workspace через `TempWorkspaceService`, фильтрует бинарные/игнорируемые файлы, аккуратно режет текст на чанки, формирует `Document` для Spring AI `VectorStore` и отправляет их в `pgvector`.
5. При успехе job получает статус `SUCCEEDED`; при ошибке — `FAILED` и scheduler автоматически ставит retry с backoff, пока не будет превышен лимит (напр. 5 попыток).
6. MCP-инструменты поверх сервиса выдают прогресс/результаты и позволяют агентам делать similarity search.

## Namespace и хранение
- Базовый namespace: `repo:<owner>/<name>`. Он одинаков для всех fetch/workspaces и агрегирует последние данные.
- Атрибуты вроде `ref`, `fetchStartedAt` и checksum файлов сохраняются в `metadata` документа, но не участвуют в построении ключа namespace.
- Обновление того же репозитория просто перезаписывает чанки (по `chunk_hash`), поэтому важно хранить `chunk_hash` как deterministic ключ.

## Chunk-метаданные
Каждый `Document` содержит:
- `repo_owner`, `repo_name`
- `file_path`
- `chunk_index` (начиная с 0, внутри файла)
- `line_start`, `line_end`
- `chunk_hash` (sha256 или xxhash от нормализованного текста)
- `language` (определяется по расширению/linguist mapping)
- `summary` (короткое описание на основе первых строк)
- `source_ref` (последний известный ref — используется только для отладки и TTL)

Это позволяет быстро фильтровать чанки в UI, формировать breadcrumbs и перечитывать только изменившиеся файлы.

## Очередь и статусы
Таблица `repo_rag_index_job` хранит:
- `repo_owner`, `repo_name`
- `status` (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`)
- `attempt` и `max_attempts`
- `queued_at`, `started_at`, `completed_at`
- Счётчики файлов/чанков
- `last_error` JSON (свободный текст + stack summary)

Scheduler повторно поднимает `FAILED` job до тех пор, пока не исчерпан лимит. Как только job становится `SUCCEEDED`, новый fetch того же репозитория создаёт fresh job.

## Rerank & поиск
- `RepoRagSearchService` использует Spring AI `VectorStore#similaritySearch` по namespace + опциональные фильтры (`file_path glob`, `language`, `tag`).
- Если настроен `RerankModel` (OpenAI Responses или Anthropic), сервис дополнительно пересортировывает топ-N результатов.
- Ответ инструмента возвращает `snippets` (кусочки текста, метаданные и score), чтобы агенты могли быстро встраивать контекст в prompt.

## Инструменты MCP
### `repo.rag_index_status`
- Вход: `repoOwner`, `repoName`.
- Выход: статус job, процент прогресса (на основе processed_files / total_files), ETA, кол-во оставшихся попыток, последнее сообщение об ошибке.

### `repo.rag_search`
- Вход: `repoOwner`, `repoName`, `query`, `topK`, `rerankTopN`, опционально `filters` (например, файл/язык).
- Выход: список чанков с `path`, `summary`, `score`, `snippet`.

## Ограничения и настройки
- Игнор-листы: `.git`, `.github`, `node_modules`, `dist`, `build`, бинарные файлы > 1 MB, файлы из `.mcpignore` (если присутствует).
- Chunking: 1.5–2 KB текста или 150 строк, whichever is earlier.
- Параллелизм контролируется параметром `github.rag.max-concurrency`; worker должен следить, чтобы не превысить лимит CPU/IO.
- Размер одного репозитория ограничен общим лимитом `TempWorkspaceService` (по умолчанию 2 GiB).

## Конфигурация Spring / PgVector
- Профиль `github` в `backend-mcp` получает полноценный `DataSource`, `JpaRepositories` для RAG таблиц и `VectorStore` с алиасом `repoRagVectorStore`.
- Настройки embedding модели вынесены в `application-github.yaml` (`GITHUB_RAG_EMBEDDING_MODEL`, `GITHUB_RAG_EMBEDDING_DIMENSIONS`).
- Docker-compose подключает `github-mcp` к основному Postgres и прокидывает ключи OpenAI/Anthropic, если нужен rerank.

## Встраивание в flows
- `repo-fetcher` после `github.repository_fetch` показывает статус индексации (ожидание `SUCCEEDED`).
- `github-gradle-test-flow` и другие ассистенты запрашивают `repo.rag_search` для контекста перед генерацией патчей.
- UI в чате отображает прогресс и предупреждает пользователя, если индекс ещё строится.
