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
2. **Быстрый поиск:** `repo.rag_search_simple` принимает только `rawQuery` и повторно использует репозиторий из последней `github.repository_fetch` (если индекс уже готов). Это защищает от выбора «чужого» репозитория, но требует сначала вызвать fetch и дождаться READY статуса.
3. **Глобальный поиск:** `repo.rag_search_global` ищет по всей базе RAG (все READY namespace). Поддерживает те же параметры, что `repo.rag_search`, но не требует `repoOwner/repoName`. В ответе `matches[].metadata.repo_owner` / `repo_name` подсказывают, где найден фрагмент.
4. **Расширенный поиск (`repo.rag_search` v4):**
   - Вход: `rawQuery`, `topK`, `topKPerQuery`, `minScore`, `minScoreByLanguage`, `history[]`, `previousAssistantReply`, `allowEmptyContext`, `useCompression`, `translateTo`, `multiQuery.enabled/queries/maxQueries`, `maxContextTokens`, `generationLocale`, `instructionsTemplate`, `filters.languages[]/pathGlobs[]`, `filterExpression`.
   - MCP автоматически валидирует лимиты (`topK<=40`, `maxQueries<=6`, `maxContextTokens<=4000`); ошибки прилетают до вызова Spring AI.
   - Выход: `matches`, `augmentedPrompt`, `instructions`, `contextMissing`, `noResults`, `noResultsReason`, `appliedModules`. Если `noResults=true`, similarity search ничего не вернул (даже если генерация разрешила пустой контекст).
   - `appliedModules` отражает цепочку модулей (semantic chunking → compression → rewrite → translation → multi-query → context-budget → llm-компрессор). Если модуль отсутствует, он отключён или не внёс изменений.
4. **Разбор ответов:** `instructions` — готовый system prompt (учитывает `generationLocale` и шаблон). `augmentedPrompt` теперь отражает исходный output `ContextualQueryAugmenter` (запрос + контекст, как он пойдёт в LLM), поэтому можно буквально увидеть, что будет отправлено модели до подстановки шаблонов. `matches[].metadata.generatedBySubQuery` помогает понять, какой подзапрос вернул чанк; дополнительно доступны `parent_symbol`, `span_hash`, `overlap_lines`.
5. **Наблюдаемость:** графики `repo_rag_queue_depth`, `repo_rag_index_duration`, `repo_rag_index_fail_total`, `repo_rag_embeddings_total`. При очереди >5 или ≥3 fail подряд проверяйте свободное место `/var/tmp/aiadvent/mcp-workspaces` и наличие зависших job.
6. **Runbook:** логируйте `repoOwner/repoName`, `appliedModules`, `contextMissing`, `noResults`, `maxContextTokens`. При ручной очистке workspace всегда повторяйте fetch → status → search.

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
- `github.create_branch` — `MANUAL`. Требует `repository`, `workspaceId`, `branchName`; опционален `sourceSha`. Валидирует имя ветки, убеждается, что ветка не существует удалённо, создаёт удалённый ref и локальный checkout.
- `github.commit_workspace_diff` — `MANUAL`. Формирует commit из изменений workspace (`branchName`, `commitMessage`, `author{name,email}`), отклоняет пустой diff, возвращает SHA и список файлов со статистикой `additions/deletions`.
- `github.push_branch` — `MANUAL`. Публикует локальные коммиты (`force=false`), проверяет чистоту workspace, сообщает локальный и удалённый SHA и число отправленных коммитов.
- `github.open_pull_request` — `MANUAL`. Создаёт PR (`headBranch` ≠ `baseBranch`), валидирует названия веток, проверяет права на push, назначает reviewers/teams и возвращает ссылки и SHA базовой/целевой веток.
- `github.approve_pull_request` — `MANUAL`. Создаёт review с действием `APPROVE`; `body` опционален (при пустом тексте создаётся формальный approve).
- `github.merge_pull_request` — `MANUAL`. Выполняет merge (`MERGE|SQUASH|REBASE`), перед операцией проверяет `mergeable`, позволяет переопределить `commitTitle`/`commitMessage`, возвращает `mergeSha` и время выполнения.

#### Чек-лист безопасного выпуска
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
- Если dry-run завершился успешно, backend разблокирует GitHub write-инструменты (`create_branch` → `commit_workspace_diff` → `push_branch` → `open_pull_request`). При ошибках оператор получает список конфликтов/логов и может повторить генерацию патча.

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
