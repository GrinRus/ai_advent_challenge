# MCP Coding Assistant RFC (Wave 24)

## Статус
- **Draft** — согласуется в рамках Wave 24.
- Обновлён: 2025-11-06 (Codex черновик, требует доработки совместно с владельцами продукта/архитектуры).

## Контекст
- Waves 0–23 подготовили основу MCP-экосистемы: GitHub fetch/inspection, Docker runner, repo analysis, Notes.
- Wave 24 должна замкнуть assisted-coding цикл: LLM предлагает правки, при необходимости проходит dry-run, публикует изменения в GitHub.
- Текущие пробелы: отсутствует профиль `coding` в `backend-mcp`, нет инструментов `coding.*`, GitHub MCP read-only.
- Экосистема уже опирается на `TempWorkspaceService`, `WorkspaceAccessService`, `docker-runner-mcp` и Spring AI tool calling (backend, FE, Telegram).

## Цели
- Дать MCP-инструменты `coding.generate_patch`, `coding.review_patch`, `coding.apply_patch_preview`, работающие поверх скачанного workspace (`github.repository_fetch` → `github.workspace_directory_inspector` → `workspace.read_file`).
- Обеспечить валидацию путей, лимитов diff и связь `patchId ↔ workspaceId`, чтобы оператор/LLM не выходил за пределы sandbox.
- Встроить dry-run/gradle-check поверх `DockerRunnerService` (опционально) и передавать логи обратно пользователю.
- Интегрировать coding MCP в чат-бэкенд (manual execution для apply, подтверждения в UI/Telegram).
- Задокументировать UX и политику безопасности/подтверждений для assisted-coding flow.
- Расширить GitHub MCP новыми write-инструментами (`create_branch`, `commit_workspace_diff`, `push_branch`, `open_pull_request`, `approve_pull_request`, `merge_pull_request`) с ручными подтверждениями и аудитом.

## Не входит в Wave 24
- Автоматический merge в основную ветку/auto-push без человека.
- Долговременное хранение историй патчей (beyond in-memory + audit журналов).
- Поддержка монореп/мультипроектов вне одного workspaceId (рассматривается позже).
- Интеграция с внешними issue-трекерами, feature-flag rollout.

## Пользовательские сценарии
Все сценарии используют существующий MCP-чейн: никаких дополнительных интерфейсов не создаём, новые инструменты зарегистрированы в каталоге MCP и автоматически становятся доступными в чатах (web UI и Telegram) так же, как текущие `github.*`, `docker.*`, `notes.*`.

1. **LLM-помощник в UI**  
   - Подготовка: оператор задаёт repo/ref → `github.repository_fetch` + `github.workspace_directory_inspector`.
   - Генерация: `coding.generate_patch` формирует diff и summary; при необходимости `coding.review_patch` подсвечивает риски.
   - Подтверждение: оператор изучает аннотации, и при необходимости запрашивает `coding.apply_patch_preview` (dry-run через docker Gradle выполняется только по отдельному запросу).
   - Публикация: при успешном dry-run (если запускался) flow последовательно вызывает `github.create_branch` → `github.commit_workspace_diff` → `github.push_branch` → `github.open_pull_request`; при одобрении оператор/LLM может использовать `github.approve_pull_request`, финальный merge — `github.merge_pull_request` после подтверждения. При пропуске dry-run оператор может инициировать публикацию сразу после ревью.
2. **Telegram чат**  
   - Аналогичный pipeline, через существующие inline-кнопки/команды бота: diff/аннотации и все новые действия (`dry-run`, `push`, `open PR`, `approve`, `merge`) вызываются как штатные MCP-инструменты и требуют явного подтверждения пользователя.
3. **Hybrid manual review**  
   - LLM генерирует патч, но человек запрашивает `coding.review_patch` перед возможным dry-run.
   - При критичных замечаниях патч дорабатывается (повтор `generate_patch`), иначе по решению оператора запускается dry-run или сразу публикация с подтверждениями как в сценариях 1/2.

Во всех сценариях Wave 30 добавляет промежуточный шаг `repo.rag_index_status` → `repo.rag_search`. После fetch агент опрашивает статус индексатора, ждёт `SUCCEEDED` и только затем добавляет heuristic-rerankнутые чанки в prompt `coding.generate_patch`/`coding.review_patch`. Это снижает ручной поиск файлов и обеспечивает стабильный контекст на уровне репозитория (namespace `repo:<owner>/<name>`).

Wave 31 дополнил этот этап:
- `repo.rag_search` автоматически сжимает историю, переписывает и переводит запрос перед multi-query (не нужно менять подсказку агента) и позволяет отключить compression (`useCompression=false`), задать `topKPerQuery`, `multiQuery.maxQueries`, `maxContextTokens` (clamp ≤4000) напрямую из flow.
- Индексатор переключён на семантический chunking Spring AI: чанки выравниваются по классам/функциям, метаданные пополняются `parent_symbol`, `span_hash`, `overlap_lines`. Это упрощает ручной мердж соседних отрывков и расследования «почему попал именно этот файл».
- MCP возвращает `instructions`/`augmentedPrompt` и новые флаги `noResults`, `noResultsReason` — бот может показать оператору, что similarity search пуст, даже если `allowEmptyContext=true` позволил продолжить диалог.
- `appliedModules` логируется в telemetry и помогает понять, почему контекст пуст (например, `contextMissing=true` при включённом фильтре или отключённом translation).
- Для lightweight flow добавлен `repo.rag_search_simple`, который берёт последний READY namespace и пригоден для «fetch → спроси» сценариев.

## Архитектура

### Компоненты
- **Coding MCP Service (новый профиль `coding`)**
  - Пакет `com.aiadvent.mcp.backend.coding`.
  - Конфигурация: `@ComponentScan("...coding")`, `@EnableConfigurationProperties(CodingAssistantProperties)`; импортирует `TempWorkspaceService` для доступа к workspace.
  - Основные сервисы:
    - `CodingAssistantService` — бизнес-логика patch/review/apply.
    - `PatchRegistry` (in-memory + persistence hook) — хранение патчей, метаданных (workspaceId, files, risk score, timestamps).
    - `PatchDiffFormatter` — нормализация unified diff + подсветка конфликтов.
    - `GradlePreviewExecutor` (обёртка над `DockerRunnerService`) — запускает dry-run/проверки только по явному запросу оператора (по умолчанию отключён).
- **Инфраструктура**
  - Используем `WorkspaceAccessService` для чтения файлов и валидации путей.
  - Ограничение на размер diff (`maxDiffBytes`, default ≤ 256 КБ).
  - `DockerRunnerService` выполняет Gradle-пайплайн (команды configurable: `test`, `check`, `spotlessApply`, т.п.).
- **Backend интеграция**
  - Spring AI tool catalog (Liquibase) добавляет `coding.generate_patch`, `coding.review_patch`, `coding.apply_patch_preview`, а также новые GitHub write-инструменты (`github.create_branch`, `github.commit_workspace_diff`, `github.push_branch`, `github.open_pull_request`, `github.approve_pull_request`, `github.merge_pull_request`).
  - `application.yaml` backend подключает coding MCP через `spring.ai.mcp.client.streamable-http.connections.coding`, execution-mode: `MANUAL` для apply, `AUTO|MANUAL` — TBD для generate/review (по продуктовой политике).
  - Flow orchestrator: добавляет стадии `fetch`, `inspect`, `generate_patch`, `review`, `apply_preview`, `github.push+pr`.
- **UI/Telegram**
  - Клиентские изменения не требуются: новые инструменты регистрируются в MCP и становятся доступными через существующий чат-интерфейс (web UI и Telegram) с текущими механиками подтверждений.

### Инструменты и контракты

#### `coding.generate_patch`
- **Request**
  ```jsonc
  {
    "workspaceId": "uuid",
    "instructions": "string (<= 4000)",
    "targetPaths": ["src/..."],        // опц. фильтр каталогов/файлов
    "forbiddenPaths": ["docs/secret"], // опц. дополнительный deny-list
    "contextFiles": [
      { "path": "src/App.java", "maxBytes": 16384 }
    ]
  }
  ```
- **Response**
  ```jsonc
  {
    "patchId": "uuid",
    "summary": "bullet list of changes",
    "diff": "unified diff",
    "annotations": {
      "files": [{"path": "...", "changedLines": 42}],
      "risks": ["migration script touched", "needs qa"],
      "conflicts": ["Potential conflict with existing TODO markers"]
    },
    "usage": {"promptTokens": 1234, "completionTokens": 567},
    "createdAt": "ISO-8601"
  }
  ```
- **Логика**
  - Проверка workspaceId, доступ к файлам (read-only).
  - Перед вызовом LLM собираем контекст (ограничение по размеру); используется дефолтная конфигурация провайдера/модели из сервиса.
  - Патч парсится и валидируется: не допускаются бинарники, файлы за пределами workspace, превышение лимитов.
  - Diff сохраняется в `PatchRegistry` с TTL (например, 2 часа) и флагами: `generated`, `requiresReview`.

#### `coding.review_patch`
- **Request**
  ```jsonc
  {
    "patchId": "uuid",
    "workspaceId": "uuid",
    "focus": ["risks", "tests", "migration"]
  }
  ```
- **Response**
  ```jsonc
  {
    "patchId": "uuid",
    "status": "ok|warnings|blocked",
    "findings": [
      {"type": "risk", "severity": "high", "message": "...", "file": "src/..."}
    ],
    "testingRecommendations": ["run ./gradlew test"],
    "nextSteps": ["request dry-run (если требуется)", "add unit tests"]
  }
  ```
- **Логика**
  - Загружает diff + контекст из `PatchRegistry`.
  - Вызывает LLM с промптом code review (использует стандартные настройки сервиса); фиксирует находки.
  - Может обновить `PatchRegistry` (например, выставить `approvalRequired=true` при severity≥high).

#### `coding.apply_patch_preview`
- **Request**
  ```jsonc
  {
    "patchId": "uuid",
    "workspaceId": "uuid",
    "commands": ["./gradlew test"], // опционально, список whitelisted команд
    "dryRun": true,
    "timeout": "PT10M"
  }
  ```
- **Response**
  ```jsonc
  {
    "patchId": "uuid",
    "workspaceId": "uuid",
    "applied": true,
    "preview": {
      "workspaceDiff": "git diff --stat",
      "detectedConflicts": [],
      "lintWarnings": [
        {"file": "src/...","line": 12,"message": "..."}
      ]
    },
    "gradle": {
      "executed": true,
      "exitCode": 0,
      "logs": "base64|truncated",
      "durationMs": 74213
    },
    "metrics": {
      "patchApplyMs": 450,
      "dockerRunMs": 73000
    },
    "completedAt": "ISO-8601"
  }
  ```
- **Логика**
  - `apply_patch_preview` вызывается только по явному запросу оператора; инструмент помечен `MANUAL`.
  - Применяет diff копией в workspace (git apply — без commit), затем откатывает изменения, если `dryRun=true`.
  - При `dryRun=true` и непустом `commands` → запускает whitelisted проверки через `DockerRunnerService.runGradle`; если `dryRun=false`, команды игнорируются.
  - Возвращает логи (ограничение по размеру, base64 при бинарных символах) и агрегированную статистику.

Дополнительно см. раздел «GitHub MCP Write Operations» для контрактов новых инструментов публикации (`github.create_branch`, `github.commit_workspace_diff`, `github.push_branch`, `github.open_pull_request`, `github.approve_pull_request`, `github.merge_pull_request`).

### Валидации и лимиты
- **Diff size**: ≤ 256 КБ (configurable `coding.max-diff-bytes`).
- **Files per patch**: ≤ 25 (configurable).
- **Context read**: тот же лимит, что `WorkspaceAccessService` (`maxBytes ≤ 2 МБ`).
- **Workspace isolation**: запрет абсолютных путей, следим что patch не выходит за `workspaceRoot`.
- **LLM**: ограничение подсказки (prompt) ≤ 16 КБ, completion ≤ 12 КБ (fine-tune via `llmOptions`).

### Хранение состояния
- `PatchRegistry` хранит:
  - `patchId`, `workspaceId`, `author` (chat session/user), `instructions`.
  - Флаги: `status` (`generated`, `applied`, `discarded`), `requiresManualReview`, `hasDryRun`.
  - `diff`, `summary`, `annotations`.
  - TTL (configurable, default 24h) + возможность ручного удаления (`coding.discard_patch` — опционально).
- Опционально persistence (Redis/Postgres) — не входит в Wave 24, но интерфейс должен позволять внедрение.

### Безопасность и контроль доступа
- Наследуем требования GitHub MCP: PAT хранится в `.env`, но coding MCP не делает сетевых вызовов.
- Ограничиваем команды dry-run: whitelist (Gradle, npm, pytest). Любой произвольный скрипт запрещён.
- Логирование:
  - `coding_patch_attempt_total`, `coding_patch_success_total`, `coding_patch_compile_fail_total`.
  - DTO логи без секретов; при отображении пользователю маскируем токены/пароли.
## Интеграция с Assisted Coding Flow
1. `github.repository_fetch` — подготовка workspace.
2. `github.workspace_directory_inspector` — выбор `projectPath`.
3. `coding.generate_patch` — получить diff и summary.
4. `coding.review_patch` — (опц.) выявить риски.
5. При необходимости оператор запускает `coding.apply_patch_preview` (dry-run выполняется только по запросу).
6. Если dry-run успешно завершён или шаг был пропущен по решению оператора, разблокируем GitHub MCP write-инструменты:
   - `github.create_branch`
   - `github.commit_workspace_diff`
   - `github.push_branch`
   - `github.open_pull_request`
7. Ревью и контроль качества → `github.set_pr_status` / `github.raise_veto`.

## GitHub MCP Write Operations
- **Цель**: обеспечить полный цикл публикации изменений — от создания ветки до merge PR — из автоматизированного flow с ручными подтверждениями.
- **Новые операции GitHub MCP** (реализуются в `GitHubRepositoryService`, экспортируются через `GitHubTools`/`GitHubWorkspaceTools`):
  - `github.create_branch`
    - Request: `{ workspaceId, repository{owner,name,ref}, branchName, sourceSha }`.
    - Валидации: branchName против whitelist/pattern, отсутствие существующей ветки, проверка права записи.
    - Действие: создаёт ветку на удалённом репозитории, но не пушит изменения.
  - `github.commit_workspace_diff`
    - Request: `{ workspaceId, branchName, author{name,email}, commitMessage }`.
    - Логика: вычисляет diff в workspace, собирает staged files, создаёт commit (на базе temp git repo); отклоняет при пустом diff или превышении лимитов.
    - Возвращает `commitSha`, список файлов, статистику.
  - `github.push_branch`
    - Request: `{ repository, branchName, force: false }`.
    - Требует строгий запрет `force=true`; проверяет конфликты и размер.
  - `github.open_pull_request`
    - Request: `{ repository, headBranch, baseBranch, title, body, reviewers? }`.
    - Возвращает `prNumber`, ссылки, head/base SHA.
    - Выполняет sanity-check: head != base, наличие ветки, размер diff.
  - `github.approve_pull_request`
    - Request: `{ repository, number, body? }`.
    - Действие: создаёт review с действием `APPROVE`; используются токены сервисного аккаунта с правом review.
  - `github.merge_pull_request`
    - Request: `{ repository, number, mergeMethod, commitTitle?, commitMessage? }`.
    - Проверки: статус CI/required checks, отсутствие конфликтов, согласованный merge method (`squash|rebase|merge`).
- **Точки подтверждения**:
  - Создание ветки/commit/push требуют подтверждения оператора перед фактическим пушем.
  - `open_pull_request` и `approve_pull_request` выполняются после dry-run и ручного обзора (если dry-run пропущен — после явного подтверждения оператора).
  - `merge_pull_request` доступен только после явного сигнала пользователя; backend хранит флаг подтверждения.
- **Интеграция с flow**:
  1. После успешного `apply_patch_preview` → `github.create_branch`.
  2. `github.commit_workspace_diff` фиксирует изменения.
  3. `github.push_branch` публикует ветку.
  4. `github.open_pull_request` создаёт PR.
  5. При необходимости LLM/оператор вызывают `github.approve_pull_request`.
  6. `github.merge_pull_request` завершается вручную либо после позитивного review.

## План внедрения
1. **RFC Approval** — согласовать с владельцами MCP и безопасности.
2. **Coding MCP bootstrap**
   - Добавить профиль `coding` (Spring config, properties).
   - Создать скелет `CodingAssistantService`, `PatchRegistry`.
3. **Инструменты**
   - Реализовать `generate_patch`, `review_patch`, `apply_patch_preview`.
   - Регистрация в MCP каталоге (Liquibase).
4. **Dry-run через Docker runner**
   - Подключить `DockerRunnerService`, реализовать командный whitelist.
5. **Backend & UI**
   - Настроить tool catalog/backend `application.yaml`.
   - FE/TG UX (кнопки подтверждения, отображение diff, логи dry-run по запросу).
6. **Документация**
   - Обновить `docs/guides/mcp-operators.md`, `docs/infra.md`.
   - Описать troubleshooting, лимиты, контроль доступа.
7. **Testing**
   - Unit: валидации, PatchRegistry, diff parsing.
   - Integration: end-to-end на temp workspace, сценарии с запуском/пропуском docker dry-run, ошибки (timeout, invalid diff).
   - Smoke: минимальный сценарий generate→apply→discard.

## Открытые вопросы
- ~~**LLM провайдеры**: используем существующие OpenAI/ZhiPu или выделяем отдельные модели (Anthropic, local)? Требует оценки стоимости/доступности.~~  
  Используем те же LLM, что уже доступны через MCP (стандартная конфигурация OpenAI/ZhiPu), дополнительных провайдеров не планируется.
- ~~**Persistence**: достаточно in-memory/TTL или нужен Redis для устойчивости? (Telegram и FE могут пересоздавать запросы).~~  
  Для MVP остаёмся на in-memory `PatchRegistry` с TTL; устойчивое хранилище не требуется.
- ~~**Approval UX**: где хранится факт ручного подтверждения? Нужна ли подпись оператора/второй фактор?~~  
  Подтверждения фиксируются внутри текущей чат-сессии (web UI или Telegram); отдельное хранение/второй фактор не требуется.
- ~~**Patch discard/reapply**: нужен ли отдельный инструмент `coding.discard_patch`, `coding.list_patches`.~~  
  Добавляем в roadmap вспомогательные инструменты `coding.discard_patch` (сброс сессии) и `coding.list_patches` (диагностика активных патчей); реализация после MVP.
- ~~**Security review**: допускается ли запуск docker runner с сетью? Нужно ли отдельное требование по network isolation.~~  
  Используем текущую минимальную конфигурацию docker runner (без дополнительного сетевого доступа); отдельные требования по isolation не вводим.
- ~~**Telemetry**: где агрегируем usage/cost? Включаем ли в биллинг пользователя?~~  
  Используем существующую инфраструктуру логов/метрик; отдельные метрики или биллинг под assisted coding не добавляем.
- ~~**Localization**: ответы и аннотации должны быть двуязычными (ru/en)? Решить до UI финализации.~~  
  Для MVP достаточно русской локализации; поддержку EN рассмотрим позже при необходимости.
