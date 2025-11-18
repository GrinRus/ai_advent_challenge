# Инфраструктура проекта

# Навигация
- Архитектура компонентов описана в `docs/architecture/backend.md`, `docs/architecture/frontend.md` и `docs/architecture/llm.md`.
- Общие процессы и требования к документации — в `docs/processes.md` и `docs/CONTRIBUTING.md`.
- Ответы на частые вопросы вынесены в `docs/faq.md`.

## Сервисы и порты
- Backend (Spring Boot) — сервис `backend`, порт `8080`.
- Frontend (React + Vite + Nginx) — сервис `frontend`, порт `4179`.
- Postgres + pgvector — сервис `postgres`, порт `5434` (наружный порт; внутри контейнера используется `5432`).
- Redis (опционально для fallback-токенизации) — сервис `redis`, наружный порт `6380`, внутри контейнера `6379`.
- Внутренние MCP:
  - `agent-ops-mcp` — порт `7091` (HTTP MCP, профиль `agentops`).
  - `flow-ops-mcp` — порт `7092` (HTTP MCP, профиль `flowops`).
  - `insight-mcp` — порт `7093` (HTTP MCP, профиль `insight`).
  - `github-mcp` — порт `7094` (HTTP MCP, профиль `github`).
  - `docker-runner-mcp` — порт `7095` (HTTP MCP, профиль `docker`, инструмент `docker.build_runner`).
  - `notes-mcp` — порт `7097` (HTTP MCP, профиль `notes`, хранит заметки и эмбеддинги PgVector).
  - `coding-mcp` — порт `7098` (HTTP MCP, профиль `coding`, assisted coding flow).

## Docker Compose
Скопируйте `.env.example` в `.env`, откорректируйте значения переменных при необходимости. Для GitHub MCP обязательно задайте Personal Access Token (`GITHUB_PAT`) с правами `repo`, `read:org` и `read:checks`. Оформите процесс выдачи и ротации PAT согласно внутренним правилам безопасности (см. `docs/processes.md`).

Для локального запуска всех компонентов выполните:

```bash
docker compose up --build
```

Переменные окружения для backend (URL, логин, пароль БД) передаются через `APP_DB_*` и уже заданы в `docker-compose.yml`. При необходимости вынесите их в `.env` файл и подключите через ключ `env_file`.

Frontend контейнер проксирует все запросы `/api/*` на backend, поэтому приложение доступно по адресу `http://localhost:4179`, а API — через `http://localhost:4179/api`.

## Dev-only режим профилей
Для локальной отладки профилей предусмотрен упрощённый режим аутентификации. Он отключает проверку внешних провайдеров и ролей и позволяет вызывать профильные API напрямую.

1. В `.env` или переменных окружения задайте:
   ```bash
   PROFILE_DEV_ENABLED=true
   PROFILE_BASIC_TOKEN=dev-profile-token
   ```
2. Перезапустите backend. Пока флаг включен, все запросы к `/api/profile/*`, `/api/llm/*` и `/api/flows/*` могут передавать заголовок `X-Profile-Auth: dev-profile-token` и будут приняты без OAuth.
3. Клиент должен по-прежнему передавать `X-Profile-Key`/`X-Profile-Channel`; единственное отличие — токен заменяет настоящую сессию.
4. Опционально задайте `APP_PROFILE_DEV_LINK_TTL` (или `app.profile.dev.link-ttl` в `application.yaml`), чтобы контролировать TTL одноразовых ссылок для Telegram/CLI. Значение по умолчанию — `10m`.
5. Через фронтенд (баннер “Dev session”) или REST `POST /api/profile/{namespace}/{reference}/dev-link` можно выпустить одноразовый код. Эндпоинт и UI доступны только при активном dev-token и возвращают `{code, channel, expiresAt}` для ручной привязки Telegram/CLI.
6. Обязательно выключайте режим (`PROFILE_DEV_ENABLED=false`) перед публикацией или совместной разработкой: backend начнёт возвращать `403` для заголовка `X-Profile-Auth`, а все профили снова станут доступны только по реальной аутентификации.

Для smoke-проверки можно выполнить:
```bash
curl -H 'X-Profile-Key: web:demo' \
     -H 'X-Profile-Auth: dev-profile-token' \
     http://localhost:8080/api/profile/web/demo

# Получение dev-link
Выдаёт одноразовый код (по умолчанию живёт 10 минут), который можно отобразить в UI или отправить пользователю:

```bash
curl -X POST \
     -H 'X-Profile-Key: web:demo' \
     -H 'X-Profile-Auth: dev-profile-token' \
     http://localhost:8080/api/profile/web/demo/dev-link
```

Ответ:

```json
{
  "code": "XY2JK9PQ",
  "channel": "telegram",
  "expiresAt": "2025-02-05T18:20:00Z"
}
```
```
В ответе будет новый профиль-заглушка; PUT/POST с тем же заголовком доступны до тех пор, пока dev-режим активен.

## OAuth мониторинг и крон-джобы

Чтобы Wave 43 не требовал архитектурных правок, уже в Wave 42 фиксируем требования по фоновой обработке токенов:

1. **Конфигурация.** В `application.yaml` добавляем блок `app.oauth.refresh` с флагами `enabled`, `batch-size` и `lookahead` (например, 15 минут). Все значения можно переопределять через env (`APP_OAUTH_REFRESH_ENABLED=true`).
2. **Планировщик.** Spring `@Scheduled` job запускается каждые 60 секунд, выбирает до `batch-size` записей `user_identity`, у которых `expires_at < now + lookahead`. Запрос выполняется с `FOR UPDATE SKIP LOCKED`, чтобы несколько инстансов могли делить нагрузку.
3. **Метрики.**
   - `oauth_refresh_success_total{provider}`
   - `oauth_refresh_error_total{provider,reason}`
   - `oauth_refresh_duration_seconds{provider}` (Timer)
   - `oauth_tokens_expiring{provider}` (Gauge) — остаток токенов с expiry < 1ч.
4. **Алерты.** Grafana/Alertmanager отслеживают:
   - `oauth_refresh_error_total` рост >5% за 15 мин.
   - `oauth_tokens_expiring{provider}` > 0 при `refresh_success_rate < 0.95`.
   - Отсутствие job-метрик (`scrape_timestamp > 5m`) → проблема cron-джобы.
5. **Логи.** Каждое событие фиксируем структурированным сообщением `oauth_refresh status=success|error provider=... reason=... profileId=...` с корреляционным ID (`state/deviceId`).
6. **Fallback.** При постоянной ошибке (`invalid_grant`, `invalid_client`) job переводит identity в статус `REQUIRES_RELINK` и отправляет событие в audit, чтобы UI показал баннер «перепривязать».

Требования выше нужно использовать при реализации реальных провайдеров (VK/GitHub/Google) — интерфейсы и конфиги уже готовы.

## Telegram бот
- Управление включением: `TELEGRAM_BOT_ENABLED` (`true`/`false`, по умолчанию выключен).
- Креды бота: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`.
- Webhook URL и путь: `TELEGRAM_BOT_WEBHOOK_URL` (публичный HTTPS) и `TELEGRAM_BOT_WEBHOOK_PATH` (локальный путь, по умолчанию `/telegram/update`).
- Дополнительные параметры webhook: `TELEGRAM_BOT_WEBHOOK_SECRET`, `TELEGRAM_BOT_WEBHOOK_TIMEOUT` (таймаут соединения при регистрации).
- Распознавание голоса: `TELEGRAM_STT_ENABLED` (`true`/`false`), `TELEGRAM_STT_MODEL` (например, `gpt-4o-mini-transcribe`), `TELEGRAM_STT_FALLBACK_MODEL` (опционально, например, `whisper-1`), `TELEGRAM_STT_LANGUAGE` (ISO-код языка, дефолт `ru`).
- Список типов апдейтов: `TELEGRAM_BOT_ALLOWED_UPDATES` (через запятую, дефолт `message,callback_query`).
- Ограничение доступа по пользователям: `TELEGRAM_BOT_ALLOWED_USER_IDS` (через запятую список Telegram user id; пусто — без ограничений).

Состояние выбора модели, sampling и MCP пока хранится в памяти процесса бота; миграция в Redis запланирована в отдельных задачах Wave 27.

### Запуск GitHub MCP отдельно
GitHub MCP упакован в тот же образ, что и остальные MCP, и запускается с профилем `github`. Для локального тестирования достаточно:

```bash
docker compose up --build github-mcp
```

Параметры подключения GitHub MCP внутри Compose:

| Переменная | Назначение | Значение по умолчанию |
|------------|------------|------------------------|
| `GITHUB_MCP_HTTP_PORT` | прокинутый порт сервиса | `7094` |
| `SPRING_PROFILES_ACTIVE` | активный профиль Spring Boot | `github` |
| `GITHUB_API_BASE_URL` | базовый URL GitHub API | `https://api.github.com` |
| `GITHUB_PAT` | PAT с правами `repo`, `read:org`, `read:checks` | пусто, требуется задать в `.env` |
| `GITHUB_MCP_DB_URL/USER/PASSWORD` | Postgres для хранения `repo_rag_index_job` и PgVector | `jdbc:postgresql://postgres:5432/ai_advent` / `ai_advent` / `ai_advent` |
| `GITHUB_RAG_*` | настройки chunking, ретраев и rerank | см. `.env.example` |

### Запуск Docker runner MCP отдельно
Docker runner использует профиль `docker` и отвечает за инструмент `docker.build_runner`. По умолчанию он запускается в Compose вместе с остальными MCP, но его можно поднять отдельно:

```bash
docker compose up --build docker-runner-mcp
```

| Переменная | Назначение | Значение по умолчанию |
|------------|------------|------------------------|
| `DOCKER_MCP_HTTP_PORT` | проброшенный порт сервиса | `7095` |
| `DOCKER_RUNNER_WORKSPACE_ROOT` | путь к общему workspace (shared с backend/github-mcp) | `/var/tmp/aiadvent/mcp-workspaces` |
| `DOCKER_RUNNER_GRADLE_CACHE_PATH` | путь к Gradle cache | `/var/tmp/aiadvent/gradle-cache` |
| `DOCKER_RUNNER_IMAGE` | образ с Gradle tooling | `aiadvent/mcp-gradle-runner:latest` |
| `DOCKER_RUNNER_ENABLE_NETWORK` | включить сеть внутри контейнера | `true` (установите `false`, если нужен полностью офлайн режим) |
| `DOCKER_RUNNER_TIMEOUT` | таймаут выполнения по умолчанию | `PT15M` |
| `DOCKER_RUNNER_WORKSPACE_VOLUME`, `DOCKER_RUNNER_GRADLE_CACHE_VOLUME` | именованные volume'ы | не заданы (используются bind-монты) |

Требования:

1. Backend, GitHub MCP и docker-runner должны указывать одинаковый `workspaceRoot`, иначе `docker.build_runner` не найдёт файлы.
2. Хостовой пользователь должен иметь права на каталоги `workspace-root` и `gradle-cache`.
3. Контейнеру нужен доступ к `/var/run/docker.sock`; убедитесь, что сокет смонтирован и пользователь имеет право выполнять `docker run`. Если нужно запретить интернет-доступ, выставьте `DOCKER_RUNNER_ENABLE_NETWORK=false` (по умолчанию сеть включена, чтобы Gradle мог скачивать зависимости).

#### Repo RAG индексатор
- **Поток:** `github.repository_fetch` → запись job в `repo_rag_index_job` → `RepoRagIndexScheduler` асинхронно обходит workspace (игнор `.git`, `.github`, `node_modules`, `dist`, `build` + `.mcpignore`) и сохраняет чанки (по умолчанию 2048 Б / 160 строк) в `repo_rag_vector_store`.
- **Эмбеддинги:** управляются `GITHUB_RAG_EMBEDDING_MODEL` и `GITHUB_RAG_EMBEDDING_DIMENSIONS` (`text-embedding-3-small`, 1536). При смене модели пересоздайте таблицу.
- **Очередь и ретраи:** `GITHUB_RAG_MAX_CONCURRENCY` — параллельность воркеров; `GITHUB_RAG_MAX_ATTEMPTS` и `GITHUB_RAG_INITIAL_BACKOFF` — backoff ретраев. За метрики следят `repo_rag_queue_depth`, `repo_rag_index_duration`, `repo_rag_index_fail_total`, `repo_rag_embeddings_total`.
- **Heuristic rerank:** без внешней модели. Параметры `GITHUB_RAG_RERANK_TOP_N`, `GITHUB_RAG_RERANK_SCORE_WEIGHT`, `GITHUB_RAG_RERANK_LINE_SPAN_WEIGHT`, `GITHUB_RAG_MAX_SNIPPET_LINES` управляют сортировкой чанков по комбинации similarity score и длины фрагмента.
- **Инструменты:** `repo.rag_index_status` (MANUAL) и `repo.rag_search` зарегистрированы в backend каталоге и доступны агентам `repo-fetcher`, GitHub flow и чату (`app.chat.research.tools`). Перед тяжёлыми задачами проверяйте `status=SUCCEEDED`.

Backend автоматически подключает `github-mcp` по адресу `http://github-mcp:8080` (см. `GITHUB_MCP_HTTP_BASE_URL` в `docker-compose.yml`). Для локального запуска бэкенда вне Compose укажите:

```bash
export GITHUB_MCP_HTTP_BASE_URL=http://localhost:7094
export GITHUB_MCP_HTTP_ENDPOINT=/mcp
```

#### GitHub write-инструменты
- Все write-операции помечены как `MANUAL` и требуют подтверждения в UI/Telegram. Порядок действий всегда один: сначала `github.create_branch` (локальная ветка до любых правок), затем инструменты coding (`generate_patch`/`review_patch`/`apply_patch_preview`) для внесения изменений, после успешного dry-run или явного разрешения становятся доступны `github.commit_workspace_diff` → `github.push_branch`, и только затем `github.open_pull_request` (при необходимости `github.approve_pull_request`, `github.merge_pull_request`). Таким образом соблюдается цепочка «ветка → код → коммит → push → PR».
- Чёткая последовательность вызовов:  
  1. `github.create_branch` — MANUAL, проверяет имя и исходный SHA.  
  2. `coding.generate_patch`/`coding.review_patch`/`coding.apply_patch_preview` — вносят изменения и dry-run'ят их внутри созданной ветки.  
  3. `github.commit_workspace_diff` — сохраняет diff в коммите, отклоняет пустые/грязные рабочие каталоги.  
  4. `github.push_branch` — публикует ветку без force.  
  5. `github.open_pull_request` — создаёт PR, после чего при необходимости выполняются `github.approve_pull_request` и `github.merge_pull_request`.
- `github.workspace_git_state` — SAFE-инструмент без подтверждения. Используйте перед `coding.generate_patch`/`coding.apply_patch_preview` и перед публикацией: он читает `.git` в локальном workspace, возвращает ветку (`branch.name`), HEAD, upstream, флаги `ahead/behind`, а также сводку staged/unstaged/untracked файлов. Если `clean=false`, агент обязан задокументировать решение продолжить.
- `github.create_branch` — валидация имени ветки, создание remote ref и локального checkout в workspace; отклоняет существующие ветки и несоответствие workspace/репозитория.
- `github.commit_workspace_diff` — собирает staged diff, контролирует размер (по `github.backend.commit-diff-max-bytes`), формирует commit с автором и возвращает статистику.
- `github.push_branch` — запрещает force-push, проверяет чистоту workspace, сообщает локальный и удалённый SHA, количество отправленных коммитов.
- `github.open_pull_request` — sanity-check head/base, опциональные reviewer логины и team slug'и, лимиты по количеству файлов/коммитов. Ответ содержит `htmlUrl`, head/base SHA и timestamp.
- `github.approve_pull_request` / `github.merge_pull_request` — организуют ручное review-одобрение и merge (`MERGE|SQUASH|REBASE`), проверяют mergeable state и возвращают merge SHA/статусы.
- Все операции логируют безопасность и аудит (`github_write_operation` category) и используют токен с правами `repo`, `read:org`, `read:checks`.
- **Операторский чек-лист**: перед публикацией выполните `coding.apply_patch_preview`, задокументируйте результат dry-run, убедитесь что commit message соответствует политикам команды, и подтвердите, что ветка отсутствует в удалённом репозитории. После `github.open_pull_request` сохраните `pullRequestNumber`/`headSha` для журнала операции и финальной телеметрии.
- **Sandbox-поток**: рекомендуемый репозиторий `sandbox/<project>-playground`. Минимальный сценарий: fetch → create_branch → commit_workspace_diff (обновление README) → push_branch → open_pull_request (draft) → approve → merge. Во время ручного теста убедитесь, что отказ от merge (например, незелёный CI) корректно генерирует ошибку 409.

#### Git workspace state (SAFE)
- Лимиты и таймауты управляются `github.backend.workspace-git-state-max-entries` (env `GITHUB_BACKEND_WORKSPACE_GIT_STATE_MAX_ENTRIES`, дефолт `200`), `github.backend.workspace-git-state-max-bytes` (`64KiB`) и `github.backend.workspace-git-state-timeout` (дефолт `PT20S`). Превышение лимитов помечается `truncated=true` и предупреждением.
- Ответ `github.workspace_git_state`:
  - `branch` — `name`, `headSha`, `resolvedRef`, `upstream`, `detached`, `ahead`, `behind`. Значения комбинируются из git status и метаданных, сохранённых при `github.repository_fetch`/`github.create_branch`.
  - `status` — `clean`, `staged`, `unstaged`, `untracked`, `conflicts`.
  - `files[]` (по желанию) — `path`, `previousPath`, `changeType`, `staged`, `unstaged`, `tracked`. Список обрезается после `maxEntries`.
  - `warnings[]` — причину обрезки/ошибки.
- Метрики: `github_workspace_git_state_success_total`, `github_workspace_git_state_failure_total`, `github_workspace_git_state_duration`. В логах фиксируется `workspaceId`, чистота и флаг `truncated`.
- При отсутствии `.git` инструмент возвращает управляемую ошибку с рекомендацией: повторить fetch со стратегией clone и создать ветку (`github.create_branch`), чтобы workspace стал полноценным git-репозиторием.
- **Примеры вызовов**: можно использовать HTTP MCP endpoint напрямую.
  ```bash
  curl -X POST "$GITHUB_MCP_HTTP_BASE_URL/mcp" \
       -H 'Content-Type: application/json' \
       -d '{
         "jsonrpc":"2.0",
         "id":"demo",
         "method":"tools/call",
         "params":{
           "name":"github.create_branch",
           "arguments":{
             "repository":{"owner":"sandbox-co","name":"demo-service","ref":"heads/main"},
             "workspaceId":"workspace-1234",
             "branchName":"feature/docs-refresh"
           }
         }
       }'
  ```
- **Интеграция со Spring AI**: backend использует `spring.ai.tool.method.MethodToolCallbackProvider` и конфигурацию `app.chat.research.tools.*`. Если необходимо включить новые write-инструменты в другой профиль, добавьте соответствующую запись c `execution-mode: MANUAL` и убедитесь, что операторский UI отображает подтверждения.

### Запуск Notes MCP
Notes MCP хранит заметки пользователей и векторные эмбеддинги для поиска похожего контента. Сервис работает на Spring Boot с профилем `notes`, использует общий Postgres (`pgvector`) и подключается к OpenAI для генерации эмбеддингов (модель `text-embedding-3-small`).

| Переменная | Назначение | Значение по умолчанию |
|------------|------------|------------------------|
| `NOTES_MCP_HTTP_PORT` | прокинутый порт сервиса | `7097` |
| `NOTES_MCP_HTTP_BASE_URL` | URL подключения backend | `http://notes-mcp:8080` |
| `NOTES_MCP_DB_URL` | строка подключения к Postgres | `jdbc:postgresql://postgres:5432/ai_advent` |
| `NOTES_MCP_DB_USER` / `NOTES_MCP_DB_PASSWORD` | креды базы данных | `ai_advent` / `ai_advent` |
| `NOTES_EMBEDDING_MODEL` / `NOTES_EMBEDDING_DIMENSIONS` | конфигурация модели эмбеддингов | `text-embedding-3-small` / `1536` |
| `NOTES_SEARCH_TOP_K` / `NOTES_SEARCH_MIN_SCORE` | дефолтные параметры поиска | `5` / `0.55` |

Backend подключает MCP через `spring.ai.mcp.client.streamable-http.connections.notes`. Для локального вызова вне Compose установите:

```bash
export NOTES_MCP_HTTP_BASE_URL=http://localhost:7097
export NOTES_MCP_HTTP_ENDPOINT=/mcp
```

Интерфейс MCP предоставляет инструменты `notes.save_note` и `notes.search_similar`, которые доступны в чате/flow и через API. Таблицы `note_entry` и `note_vector_store` управляются Liquibase (`backend-mcp/src/main/resources/db/changelog/notes`).

### Запуск Coding MCP
Coding MCP реализует assisted coding: генерацию патчей, эвристическое ревью и dry-run. Сервис запускается с профилем `coding`, использует `TempWorkspaceService` для работы с локальными репозиториями и Docker runner для dry-run.

| Переменная | Назначение | Значение по умолчанию |
|------------|------------|------------------------|
| `CODING_MCP_HTTP_PORT` | прокинутый порт сервиса | `7098` |
| `CODING_MCP_HTTP_BASE_URL` | URL подключения backend | `http://coding-mcp:8080` |
| `CODING_MCP_HTTP_ENDPOINT` | endpoint HTTP MCP | `/mcp` |

Для локального запуска вне Compose:

```bash
export CODING_MCP_HTTP_BASE_URL=http://localhost:7098
export CODING_MCP_HTTP_ENDPOINT=/mcp
```

Backend подключает сервис через `spring.ai.mcp.client.streamable-http.connections.coding`. Внутри инструменты работают с `git` и Docker, поэтому убедитесь, что различные утилиты (`git`, `./gradlew`) доступны внутри контейнера.

Инструменты:
- `coding.generate_patch` — создаёт diff, summary, usage, сохраняет `patchId` в in-memory реестр с TTL и контролирует лимиты (diff ≤ 256 КБ, ≤ 25 файлов).
- `coding.review_patch` — подсвечивает риски (build-файлы, TODO/FIXME, миграции), тестовые рекомендации и обновляет аннотации.
- `coding.apply_patch_preview` — **MANUAL** инструмент. После явного подтверждения выполняет `git apply --check`, временно накладывает diff, собирает `git diff --stat`, откатывает изменения и (если переданы whitelisted команды) запускает dry-run через Docker. Ответ содержит статус dry-run, список изменённых файлов, рекомендации, аннотации и метрики (`coding_patch_*`).

#### Claude Code CLI + GLM (z.ai)
- CLI устанавливается в контейнер (`npm install -g @anthropic-ai/claude-code`, Node 18). Для локального стенда повторите команды вручную или используйте официальный скрипт установки z.ai; проверяйте `claude --version` после обновлений.
- Ключ берётся из переменной `ZHIPU_API_KEY`, endpoint/модель задаются через `CODING_CLAUDE_BASE_URL` (по умолчанию `https://api.z.ai/api/anthropic`) и `CODING_CLAUDE_MODEL` (`GLM-4.6`, `GLM-4.5`, `GLM-4.5-Air`). Feature-flag `CODING_CLAUDE_ENABLED`, количество повторов `CODING_CLAUDE_MAX_RETRIES`, путь до бинаря `CLAUDE_CODE_BIN`, таймаут `CLAUDE_CODE_TIMEOUT`.
- Кеш npm монтируется в volume `~/.npm`. При обновлении CLI очищайте кеш и перезапускайте контейнер, чтобы подтянуть новую версию бинаря.
- В логах coding MCP фиксируются размеры промпта/дифа, stderr CLI маскируется. Для диагностики таймаутов включайте debug-логи и результирующий stdout/stderr CLI в Kubernetes/compose логах.

Успешный dry-run разблокирует в backend цепочку GitHub write-инструментов (`create_branch → commit_workspace_diff → push_branch → open_pull_request`). При ошибках dry-run оператор получает список конфликтов и логи, после чего может скорректировать инструкции и повторить генерацию.

### Redis для fallback-оценки токенов
- Сервис `redis` поднимается вместе с `docker compose` и хранит результаты подсчёта токенов для повторно используемых промптов. По умолчанию кэш выключен (`CHAT_TOKEN_USAGE_CACHE_ENABLED=false`), поэтому Redis безвреден при локальной разработке.
- Чтобы задействовать кэш, установите переменную `CHAT_TOKEN_USAGE_CACHE_ENABLED=true` и при необходимости скорректируйте `CHAT_TOKEN_USAGE_CACHE_TTL` (Duration, по умолчанию `PT15M`) и `CHAT_TOKEN_USAGE_CACHE_PREFIX`.
- Spring Boot использует стандартную автоконфигурацию Redis (`spring.data.redis.*`). В Docker хост переопределён значением `SPRING_DATA_REDIS_HOST=redis`, локально можно задать `localhost`.
- Требования к Redis-кешу: держите TTL в диапазоне 15–60 минут (дефолт `PT15M`), при необходимости ограничьте память командой `maxmemory` и политикой `allkeys-lru`. Отсутствие Redis не приводит к ошибкам — кэш автоматически деградирует в no-op, но подсчёт токенов будет выполняться чаще.

> ⚙️ Подсчёт токенов реализован через [`EncodingRegistry`](https://docs.spring.io/spring-ai/reference/api/chatclients/openai.html#_token_usage) Spring AI и библиотеку [jtokkit](https://github.com/knuddelsgmbh/jtokkit). Словари jtokkit поставляются на базе публичных токенизаторов OpenAI (лицензия Apache 2.0). Для большинства OpenAI-совместимых моделей используйте `cl100k_base`; для кастомных моделей выбирайте ближайший доступный словарь или зарегистрируйте собственный через `EncodingRegistry.register*`. Переменная `CHAT_TOKEN_USAGE_TOKENIZER` задаёт дефолт.

## Настройка LLM-чата
- Backend использует Spring AI с фабрикой `ChatProviderService`, позволяющей переключаться между провайдерами без изменения прикладного кода. Конфигурация описана в `application.yaml` (`app.chat.providers`). По умолчанию активен `zhipu` (z.ai), параллельно доступен `openai`.
- Каждый провайдер хранит параметры подключения: базовый URL, ключ, таймауты, дефолтную модель и список доступных моделей с оценочной стоимостью. Все значения можно переопределить переменными окружения:
  - OpenAI: `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_DEFAULT_MODEL`, `OPENAI_TEMPERATURE`, `OPENAI_TOP_P`, `OPENAI_MAX_TOKENS`, `OPENAI_TIMEOUT`.
  - Zhipu (z.ai): `ZHIPU_BASE_URL`, `ZHIPU_COMPLETIONS_PATH`, `ZHIPU_API_KEY`, `ZHIPU_DEFAULT_MODEL`, `ZHIPU_TEMPERATURE`, `ZHIPU_TOP_P`, `ZHIPU_MAX_TOKENS`, `ZHIPU_TIMEOUT`.
  Переменные заданы в `.env.example` и автоматически прокидываются в контейнер backend через `docker-compose.yml`.
- Для каждой модели теперь задаются три флага режима: `streaming-enabled`, `sync-enabled`, `structured-enabled`. Plain sync контроллер использует `syncEnabled`, структурированный — `structuredEnabled`. Модели вроде `glm-4-32b-0414-128k` имеют `streamingEnabled=false`, `structuredEnabled=false`, но допускают plain sync (`syncEnabled=true`).
- Для каждой модели можно указать стратегию подсчёта usage:
  - `usage.mode = native` — доверяем usage, который возвращает провайдер (подходит для OpenAI, где Spring AI отдаёт usage в финальном `stream_options.include_usage` чанке).
  - `usage.mode = fallback` — провайдер usage не возвращает, используем jtokkit-оценку; обязательно укажите `usage.fallback-tokenizer`.
  - `usage.mode = auto` — если провайдер вернул usage, используем native; иначе fallback.
  Дополнительно можно настроить глобальные дефолты в секции `app.chat.token-usage` (`default-tokenizer`, TTL кэша и т.д.).
- Поддержка `stream_options.include_usage` по провайдерам:
  - **OpenAI / Azure OpenAI** — поддерживается полностью (Spring AI включает опцию через `OpenAiChatOptions.streamUsage(true)`), usage приходит в финальном SSE чанке.
  - **zhipu.ai** — API не возвращает usage в stream-ответе; система всегда использует fallback-токенизацию.
  - Для кастомных OpenAI-совместимых провайдеров повторно используйте ту же конфигурацию и проверяйте наличие финального usage перед включением `native` режима.
- SSE-эндпоинт `/api/llm/chat/stream` теперь отправляет в финальном событии поле `usageSource` со значениями `native` или `fallback`. Это значение следует прокинуть на фронтенд, чтобы пользователь видел источник расчёта.
- Каждому провайдеру можно задать стратегию ретраев через секцию `retry` в `application.yaml` (параметры также доступны как env-переменные `*_RETRY_*`). Структура:

  ```yaml
  app:
    chat:
      providers:
        openai:
          retry:
            attempts: 3            # максимум попыток
            initial-delay: 250ms   # стартовый backoff (Duration)
            multiplier: 2.0        # коэффициент при экспоненциальном росте
            retryable-statuses: 429,500,502,503,504
  ```

  RetryTemplate собирается на лету для каждого провайдера: WebClient ошибки сравниваются со списком статусов, ошибки схемы (`422`) также участвуют в ретраях до исчерпания попыток. Финальный статус/причина логируются в `StructuredSyncService`.
- Матрица тарифов и характеристик (актуализация: июль–октябрь 2025). Цены указаны в USD за 1K токенов, контекст — токены входа, вывод — ожидаемый максимум completion:

  | Провайдер | Модель                | Сегмент     | Контекст / вывод | Стоимость (in / out) | Стриминг | Ключевые сценарии |
  |-----------|-----------------------|-------------|------------------|----------------------|----------|-------------------|
  | zhipu.ai  | GLM-4.5 Air           | budget      | 32K / —          | 0.0002 / 0.0011      | ✅        | дешёвые ответы, предпросмотры UI |
  | zhipu.ai  | GLM-4.5               | standard    | 65K / —          | 0.00035 / 0.00155    | ✅        | основной ассистент, баланс цена/качество |
  | zhipu.ai  | GLM-4.6               | pro         | 81K / —          | 0.0006 / 0.0022      | ✅        | длинный контекст, код, аналитика |
  | zhipu.ai  | GLM-4-32B-0414-128K   | flagship    | 128K / —         | 0.0001 / 0.0001      | ❌ (sync) | обработка больших документов, flat-pricing |
  | OpenAI    | GPT-5 Nano            | economy     | 128K / 8K        | 0.00005 / 0.0004     | ✅        | фоновые задачи, уведомления |
  | OpenAI    | GPT-4o Mini           | value       | 128K / 16K       | 0.00015 / 0.0006     | ✅        | быстрые пользовательские сценарии |
  | OpenAI    | GPT-4o                | pro         | 128K / 16K       | 0.0025 / 0.01        | ✅        | мультимодальные и высокоточные ответы |
  | OpenAI    | GPT-5                 | flagship    | 200K / 32K       | 0.00125 / 0.01       | ✅        | продвинутый анализ, сложные цепочки |

  > Данные синхронизированы с публичными прайс-листами OpenAI и Zhipu. Проверяйте лимиты и стоимость в личных кабинетах перед выкатыванием в прод.

- Spring AI возвращает usage-метаданные через `ChatResponseMetadata.getUsage()` (см. [Spring AI Chat Metadata](https://docs.spring.io/spring-ai/reference/api/chat.html#metadata)). Мы используем эти данные для расчёта стоимости и сохранения статистики. Если провайдер usage не отдаёт, поля остаются пустыми.
- UI может передавать `provider` и `model` на каждый запрос. Эти значения сохраняются в истории (`chat_message.provider`, `chat_message.model`) и приходят обратно в SSE-события (`session`, `token`, `complete`, `error`) для упрощённой диагностики.
- Память LLM-чата управляется Spring AI `MessageWindowChatMemory` и хранится в таблице `chat_memory_message`:
  - `CHAT_MEMORY_WINDOW_SIZE` — размер скользящего окна сообщений, передаваемых модели (по умолчанию `20`).
  - `CHAT_MEMORY_RETENTION` — максимальное время простоя диалога до очистки окна (ISO-длительность, по умолчанию `PT6H`).
  - `CHAT_MEMORY_CLEANUP_INTERVAL` — периодичность фоновой задачи очистки (по умолчанию `PT30M`).
  - Метрики `chat_memory_evictions_total` и `chat_memory_conversations` доступны через Spring Boot Actuator.
- Для frontend достаточно установить `VITE_API_BASE_URL` (по умолчанию `/api`). SSE-подписка выполняется на эндпоинт `POST /api/llm/chat/stream`.
- Отслеживание токенов и стоимости:
  - Потоковые события `event:complete` содержат новые поля `usage` и `cost`, чтобы UI мог обновлять статистику без дополнительных запросов.
  - Все ответы ассистента сохраняются вместе с usage/стоимостью в `chat_message` (колонки `prompt_tokens`, `completion_tokens`, `total_tokens`, `input_cost`, `output_cost`, `currency`).
  - Для аналитики и биллинга доступен REST-эндпоинт `GET /api/llm/sessions/{id}/usage`, который возвращает список сообщений и агрегаты по диалогу.
  - Расчёт стоимости выполняется на основе прайсинга, заданного в `app.chat.providers.*.models.[modelId].pricing`; единица измерения — USD за 1K токенов.

Все LLM-эндпоинты (`/stream`, `/sync`, `/sync/structured`) принимают опциональный блок `options` с параметрами sampling. Если оставить его пустым или удалить ключи, сервис применит значения по умолчанию из `app.chat.providers.<provider>`. Диапазоны: `temperature` — `0..2`, `topP` — `0..1`, `maxTokens` ≥ `1`.

```json
// Запрос без overrides (используются дефолты провайдера)
{
  "message": "Give me a project status update",
  "provider": "openai",
  "model": "gpt-4o"
}

// Запрос с переопределёнными sampling-настройками
{
  "message": "Summarize the backlog risks",
  "provider": "zhipu",
  "model": "glm-4.6",
  "options": {
    "temperature": 0.2,
    "topP": 0.9,
    "maxTokens": 400
  }
}
```

Подробная схема доступна в OpenAPI (`/v3/api-docs`, swagger-ui настраивается через `backend/src/main/resources/application.yaml`).

### Саммаризация длинных диалогов

- Префлайт-обёртка `ChatSummarizationPreflightManager` срабатывает перед каждым обращением к LLM (стриминг, plain sync, structured sync, flow-агенты). Она оценивает размер будущего промпта (`TokenUsageEstimator`) и, при превышении `summarization.trigger-token-limit`, ставит задачу в очередь.
- Рабочий пул `ChatMemorySummarizerService` ограничен параметрами `summarization.max-concurrent-summaries` и `summarization.max-queue-size`. Пока задача ждёт в очереди, метрика `chat_summary_queue_size` отражает её длину, а отфутболенные задачи считаются в `chat_summary_queue_rejections_total`.
- Основные переменные окружения (см. `.env.example`):
  - `CHAT_MEMORY_SUMMARIZATION_ENABLED` — включение механизма.
  - `CHAT_MEMORY_SUMMARIZATION_TRIGGER` и `CHAT_MEMORY_SUMMARIZATION_TARGET` — порог и целевой размер промпта (в токенах).
  - `CHAT_MEMORY_SUMMARIZATION_MODEL` — идентификатор `provider:model` (по умолчанию `openai:gpt-4o-mini`).
  - `CHAT_MEMORY_SUMMARIZATION_MAX_CONCURRENT` — число параллельных запросов к summarizer-модели.
  - `CHAT_MEMORY_SUMMARIZATION_MAX_QUEUE_SIZE` — длина очереди задач до отказа.
  - `CHAT_MEMORY_SUMMARIZATION_BACKFILL_ENABLED`, `..._MIN_MESSAGES`, `..._BATCH_SIZE`, `..._MAX_ITERATIONS` — настройки повторяемого бэкфилла (при включении сервис запустит утилиту и будет пересобирать summary батчами).
- Надёжность: каждая задача делает до 3 попыток с backoff 250→2000 мс. При нескольких подряд сбоях по сессии логируется alert, а счётчики `chat_summary_failures_total`/`chat_summary_failure_alerts_total` позволяют настроить алерты в Grafana.
- Набор метрик: `chat_summary_runs_total`, `chat_summary_duration_seconds`, `chat_summary_tokens_saved_total`, `chat_summary_queue_size`, `chat_summary_queue_rejections_total`, `chat_summary_failures_total`, `chat_summary_failure_alerts_total`.
- Flow-память использует тот же механизм (`FlowMemorySummarizerService`), summary сохраняются в `flow_memory_summary`, а при чтении `FlowMemoryService` автоматически собирает «summary + хвост» канала.
- Ручной пересчёт: `POST /api/admin/chat/sessions/{sessionId}/summary/rebuild` (нужно указать `providerId` и `modelId`). Массовый бэкфилл запускается установкой `CHAT_MEMORY_SUMMARIZATION_BACKFILL_ENABLED=true` и рестартом backend — после окончания лог сообщает количество обработанных сессий.

### Синхронный структурированный ответ
- Новый plain sync эндпоинт `POST /api/llm/chat/sync` возвращает `ChatSyncResponse`: текстовую completion, метаданные провайдера/модели, задержку и usage/cost. Формат запроса повторяет стриминг (`ChatSyncRequest`). При отсутствии `sessionId` создаётся новая сессия, её идентификатор возвращается в заголовке `X-Session-Id`; флаг `X-New-Session` сигнализирует о создании диалога.
- Структурированный sync-эндпоинт перемещён на `POST /api/llm/chat/sync/structured`. Он по-прежнему принимает `ChatSyncRequest`, но отвечает `StructuredSyncResponse` и требует поддержки `structuredEnabled` у модели.
- Backend использует `BeanOutputConverter<StructuredSyncResponse>` из Spring AI: для OpenAI схема пробрасывается через `responseFormat(JSON_SCHEMA)` и `strict=true`, для остальных провайдеров (например, ZhiPu) генерация схемы подмешивается в промпт.
- Запрос проходит через `StructuredSyncService`, который собирает RetryTemplate на основе конфигурации провайдера, повторяет вызов на указанных статусах/ошибках схемы и логирует номер попытки/причину остановки. Финальный 422 отдается при окончательном несоответствии JSON.
- Ответ имеет фиксированную структуру `StructuredSyncResponse` и включает технические поля (`provider.type`, `provider.model`, `usage`) и полезную нагрузку в `answer`. Пример:
- Сериализованный ответ сохраняется в `chat_message.structured_payload` (тип `JSONB`), что позволяет фронтенду получать карточки истории из базы. Колонка индексирована по `(session_id, created_at)` для быстрых выборок.

```json
{
  "requestId": "4bd0f474-78e8-4ffd-a990-3aa54f0704c3",
  "status": "success",
  "provider": {
    "type": "ZHIPUAI",
    "model": "glm-4.6"
  },
  "answer": {
    "summary": "Краткое описание ответа.",
    "items": [
      {
        "title": "Основная рекомендация",
        "details": "Расширенный текст ответа.",
        "tags": ["insight", "priority"]
      }
    ],
    "confidence": 0.82
  },
  "usage": {
    "promptTokens": 350,
    "completionTokens": 512,
    "totalTokens": 862
  },
  "latencyMs": 1240,
  "timestamp": "2024-05-24T10:15:30Z"
}
```

**Пример plain ChatSyncResponse**
```json
{
  "requestId": "a8f902c2-8af1-4c96-bafd-2699d713ba42",
  "content": "Здесь краткий ответ ассистента.",
  "provider": {
    "type": "OPENAI",
    "model": "gpt-4o-mini"
  },
  "usage": {
    "promptTokens": 128,
    "completionTokens": 256,
    "totalTokens": 384
  },
  "cost": {
    "input": 0.00002,
    "output": 0.00015,
    "total": 0.00017,
    "currency": "USD"
  },
  "latencyMs": 420,
  "timestamp": "2025-10-21T09:30:12Z"
}
```

### Perplexity MCP (live research)
- Провайдер Perplexity подключается через STDIO (на той же платформе Spring AI, что и Flow/Agent/Insight MCP). Backend использует `spring.ai.mcp.client.stdio.connections.perplexity`, который вызывает команду `PERPLEXITY_MCP_CMD` и передаёт ключ `PERPLEXITY_API_KEY` в окружение процесса.
- Требования:
  - Доступный бинарь/скрипт `perplexity-mcp` (часто это `npx -y @perplexity-ai/mcp-server`) в `PATH` backend-а или контейнера.
  - Ключ API Perplexity (`PERPLEXITY_API_KEY`) в окружении, где запускается STDIO-процесс.
  - Таймаут клиента настраивается переменной `PERPLEXITY_TIMEOUT_MS` (duration, дефолт `120s`).
- При старте backend создаёт STDIO-подключение и кеширует список инструментов (`perplexity_search`, `perplexity_deep_research`). Метрики выводятся в Micrometer:
  - `perplexity_mcp_latency` — длительность health check.
  - `perplexity_mcp_errors_total` — количество ошибок при опросе провайдера.
- Actuator предоставляет `perplexityMcp` health-indicator (`GET /actuator/health/perplexityMcp`). Возможные статусы:
  - `UP` — инструменты доступны; в деталях возвращаются `toolCount`, `tools`, `latencyMs`.
  - `UNKNOWN` — MCP отключён (`PERPLEXITY_MCP_ENABLED=false`) или провайдер не поднят.
  - `DOWN` — ошибка запуска/handshake (в ответе будет поле `error`).
- Для локальной проверки:
  ```bash
  export PERPLEXITY_API_KEY=... # ваш ключ
  npx -y @perplexity-ai/mcp-server --api-key "$PERPLEXITY_API_KEY"
  ```
  После старта backend отправьте запрос с `mode: "research"` на `/api/llm/chat/sync` или `/api/llm/chat/stream`, чтобы задействовать инструменты Perplexity.
- В production окружении запускайте STDIO-процесс через supervisor/systemd или Docker и ограничивайте доступ к бинарю и секретам (mTLS/SSH, secrets manager).

### Встроенные MCP-серверы (Flow Ops / Agent Ops / Insight)

- Каждый сервер собирается модулем `backend-mcp` и запускается как streamable HTTP приложение. Чтобы использовать его локально, достаточно поднять контейнеры через `docker compose`. Сервисы `agent-ops-mcp`, `flow-ops-mcp` и `insight-mcp` используют один образ и отличаются активным профилем/главным классом (`SPRING_MAIN_APPLICATION_CLASS`).
- Базовые переменные окружения:
  - `*_MCP_HTTP_BASE_URL` — адрес HTTP MCP для backend-а (например, `http://agent-ops-mcp:8080` в docker-среде или `http://localhost:7091` локально).
  - `*_MCP_HTTP_ENDPOINT` — путь endpoint-а (по умолчанию `/mcp`).
  - `*_BACKEND_BASE_URL` — адрес REST API основного backend-а (в docker-среде указывает на `http://backend:8080`).
  - `*_BACKEND_API_TOKEN` — опциональные bearer-токены, прокидываемые MCP при обращении к backend.
- `docker-compose.yml` пробрасывает порты 7091–7093 на хост; при необходимости добавьте healthcheck `curl -f http://localhost:7091/actuator/health`.
- Для ручного тестирования выполните HTTP-запрос:

  ```bash
  curl -X POST http://localhost:7091/mcp \
    -H 'content-type: application/json' \
    -d '{"method":"ping","params":{}}'
  docker compose logs -f flow-ops-mcp
  ```
- Подробное руководство для операторов и аналитиков находится в `docs/guides/mcp-operators.md`.

## Оркестрация мультиагентных флоу

### Архитектура и исполнение
- Оркестратор реализован сервисом `AgentOrchestratorService`. Он читает опубликованные определения из `flow_definition`, строит `FlowDefinitionDocument` и управляет шагами через `JobQueuePort`. Для каждого шага формируется JSON payload (`FlowJobPayload`), который попадает в очередь `flow_job`. Очередь хранит:
  - `payload_jsonb` — сериализованный контекст шага (id/stepId/attempt/memory).
  - `status` (`PENDING|RUNNING|FAILED|COMPLETED`), `retry_count`, `scheduled_at`, `locked_at`, `locked_by`.
  - индексы по `status`, `scheduled_at` и внешние ключи на `flow_session` и `flow_step_execution`.
- Обработку очереди запускает Spring-компонент `FlowJobWorker`:
  - `@Scheduled(fixedDelayString = "${app.flow.worker.poll-delay:500}")` вызывает `AgentOrchestratorService.processNextJob(workerId)`.
  - Конкурентность регулируется отдельным `ExecutorService` (по умолчанию фиксированное число потоков). Параметры (`enabled`, `poll-delay`, `max-concurrency`, `worker-id-prefix`) настраиваются через `app.flow.worker.*`.
  - Для каждой итерации логируем `workerId`, результат (`processed|empty|error`) и длительность; в Micrometer попадают `flow.job.poll.count` и `flow.job.poll.duration` с тегом `result`. Эти метрики используются для алертов на рост ошибок или пустых выборок.

-### Модель данных
- Каталог агентов (таблицы `agent_definition`, `agent_version`, `agent_capability`) хранит системные промпты, ограничения (`syncOnly`, `maxTokens`), typed-конфигурацию `agent_invocation_options` (`provider`, `prompt`, `memoryPolicy`, `retryPolicy`, `advisorSettings`, `tooling`, `costProfile`) и список возможностей (`capability`, произвольный JSON payload). Флаг `is_active` на уровне `agent_definition` автоматически включается при публикации новой версии.
  - REST API каталога:  
    - `GET /api/agents/definitions` — список определений с последними версиями.  
    - `GET /api/agents/definitions/{id}` — детали + список версий.  
    - `POST /api/agents/definitions` — создание определения (требует `createdBy`).  
    - `PUT /api/agents/definitions/{id}` / `PATCH /api/agents/definitions/{id}` — обновление параметров и статуса (`updatedBy`).  
    - `POST /api/agents/definitions/{id}/versions` — новый черновик версии (обязателен `createdBy`, `providerId`, `modelId`, `systemPrompt`).  
    - `POST /api/agents/versions/{versionId}/publish` — публикация с возможностью обновить capabilities; `POST /api/agents/versions/{versionId}/deprecate` — вывод из эксплуатации.  
  - UI (`Flows / Agents`) использует кэшированный запрос каталога, чтобы не перегружать API при навигации; кэш сбрасывается после любой мутации (`invalidateAgentCatalogCache()` в `apiClient.ts`).
- Флоу:
  - `flow_definition` — черновики и опубликованные версии. Поля: `id`, `name`, `version`, `status`, `definition` (typed `FlowBlueprint`), `blueprint_schema_version`, `is_active`, `updated_by`, `published_at`.
  - `flow_definition_history` — снимки версий с `change_notes`, автором и зафиксированным `blueprint_schema_version`.
  - `flow_session` — запуски (`PENDING`, `RUNNING`, `PAUSED`, `FAILED`, `COMPLETED`, `ABORTED`), `launch_parameters`, `shared_context`, `current_step_id`, `current_memory_version`.
  - `flow_step_execution` — состояние шага (attempt, prompt, input/output, usage/cost, timestamps).
  - `flow_event` — журнал (`event_type`, `status`, `payload_jsonb`, `usage/cost`, `trace_id`, `span_id`) для SSE и аудита.
  - `flow_memory_version` — shared/isolated память, версии и TTL. Wave 14: ретеншен (`retentionVersions`/`retentionDays`) читается из blueprint; при отсутствии настроек применяются дефолты (10 версий, 30 дней) и фиксируются при каждом append.

### Наблюдаемость flow саммари
- `FlowMemorySummarizerService` отдаёт отдельные метрики с тегом `scope=flow`: `flow_summary_runs_total`, `flow_summary_duration_seconds`, `flow_summary_queue_size`, `flow_summary_queue_rejections_total`, `flow_summary_failures_total`, `flow_summary_failure_alerts_total`. Экспортируйте Micrometer в Prometheus/OTLP и добавьте теги `providerId`/`channel`, если нужен более детальный анализ.
- Рекомендуемые алерты: `flow_summary_queue_size` превышает настроенный `maxConcurrentSummaries` более 3 минут (застрявший воркер); рост `flow_summary_failure_alerts_total` (>0 за последние 5 минут) указывает на деградацию провайдера; отсутствие новых записей `flow_memory_summary`/`chat_session.summary_metadata.updatedAt` дольше допустимого окна (например, 15 минут) сигнализирует о зациклившихся сессиях. Последний сценарий удобно проверять SQL/Prometheus-правилом по `max(now() - summary.created_at)`.
- Для ручных перезапусков держим `/api/admin/flows/sessions/{sessionId}/summary/rebuild` и CLI (`app.flow.summary.cli.*`). При активации CLI обязательно задавайте `session-id`, `provider-id`, `model-id` и убедитесь, что алерты выключены на время массового backfill, чтобы избежать ложных срабатываний.
- Для миграции легаси-флоу на типизированные blueprints предусмотрен CLI (`app.flow.migration.cli.*`): по умолчанию он работает в `dry-run` режиме, умеет ограничиваться списком `definition-ids` и обновляет как текущие определения, так и историю версий. Запуски фиксируйте в журнале изменений.

### Формат flow definition
- Blueprint описан value-объектом `FlowBlueprint` (см. `docs/architecture/flow-definition.md`): включает `schemaVersion`, `metadata`, `launchParameters`, `memory.sharedChannels` с ретеншеном и массив `steps[]`.
- `steps[]` — типизированные записи `FlowBlueprintStep` (id, name, `agentVersionId`, `prompt`, `overrides`, `interaction`, `memoryReads`, `memoryWrites`, `transitions`, `maxAttempts`).
- API V2 (`app.flow.api.v2-enabled=true`) возвращает blueprint как есть (`FlowDefinitionResponseV2`, `FlowLaunchPreviewResponseV2`); V1 сохраняет обратную совместимость с JSON-структурами.

## Мониторинг конструктора (Wave 14)
- `ConstructorTelemetryService` в backend инкрементирует Micrometer-счётчики для всех операций конструктора:
  - `constructor_flow_blueprint_saves_total{action=create|update|publish}` — успешные сохранения/публикации флоу.
  - `constructor_agent_definition_saves_total{action=create|update|status}` и `constructor_agent_version_saves_total{action=create|update|publish|deprecate}` — активность каталога агентов.
  - `constructor_validation_errors_total{domain,stage}` — ошибки валидации или конфликты (422/409/404). Домены: `flow_blueprint`, `agent_definition`, `agent_version`; stage соответствует операции (`create`, `update`, `publish`, `status`, `deprecate`).
  - `constructor_user_events_total{event}` — агрегированные пользовательские действия (для heatmap активности).
- Рекомендуемые Prometheus-алерты:
  - `rate(constructor_validation_errors_total[5m]) > 5` — всплеск ошибок конструктора.
  - `rate(constructor_validation_errors_total[5m]) / rate(constructor_user_events_total[5m]) > 0.2` при `rate(constructor_user_events_total[5m]) > 1` — деградация UX (каждый пятый запрос завершается ошибкой).
  - Отдельный alert на `constructor_validation_errors_total{stage="publish"}` помогает ловить регрессии в продакшн-публикациях.
- Дашборды:
  - Stacked area по `constructor_flow_blueprint_saves_total` и `constructor_agent_version_saves_total` (tag `action`) — использование конструктора по типу операций.
  - Таблица Top-K `constructor_validation_errors_total` по `domain/stage` для обнаружения горячих точек.
  - Bar chart `constructor_user_events_total` c сравнением офисных/выходных дней — поддержка capacity planning.
- Аудит:
  - Логгер `ConstructorAudit` (включён в `application.yaml`) печатает события в формате `event=agent_version_save action=publish actor=ops@aiadvent.dev definitionId=... versionId=...`. Пробросьте поток в ELK/SIEM и настройте поиск по `actor`/`definitionId` при расследовании инцидентов.
  - Для локальной отладки можно увеличить уровень (`logging.level.ConstructorAudit=DEBUG`) и наблюдать payload прямо в консоли.

### Политики памяти и конфигурации
- Настройки памяти (`app.chat.memory.*`) определяют размер окна (`window-size`), TTL (`retention`) и интервал очистки (`cleanup-interval`). Shared/isolated каналы инжектируются через Spring AI `MessageChatMemoryAdvisor`.
- Blueprint-уровень (`memory.sharedChannels`) дополняет эти значения на уровне флоу: `FlowMemoryService` объединяет системные дефолты с per-channel overrides и применяет их при очистке версии памяти.
- Настройки воркера флоу:

```yaml
app:
  flow:
    worker:
      enabled: true          # отключает воркер (для однократных запусков/отладки)
      poll-delay: PT0.5S     # задержка между итерациями
      max-concurrency: 1     # количество потоков в executor
      worker-id-prefix: ""   # кастомный префикс для логов/метрик
```

### API управления флоу
- `POST /api/flows/{flowId}/start` — запускает сессию и возвращает `FlowSessionDto`.
- `POST /api/flows/{sessionId}/control` — `pause`, `resume`, `cancel`, `retryStep`.
- `GET /api/flows/{sessionId}` — long-poll (параметры `sinceEventId`, `stateVersion`) со snapshot и новыми `flow_event`.
- `GET /api/flows/{sessionId}/events/stream` — SSE-стрим на `SseEmitter` с heartbeats и graceful close (long-poll — обязательный fallback).
- `GET /api/flows/{sessionId}/steps/{stepId}` — детальный просмотр шага (prompt, overrides, output, usage/cost, traceId).

- Advisors Spring AI (`CallAdvisorChain`, `StreamAdvisorChain`) инжектируют во все вызовы `requestId`, `flowId`, `sessionId`, `stepId`, `memoryVersion` и регистрируют Micrometer/OTel метрики (`flow_step_duration`, `flow_retry_count`, `flow_cost_usd`). Отдельный advisor отвечает за прокидывание `ChatMemory.CONVERSATION_ID` в агенты.
- Frontend содержит раздел `Flows`:
  - `Flows / Agents` — каталог определений: создание/редактирование метаданных, управление версиями (создание, публикация, депрекация, capabilities), просмотр связанных моделей и истории авторов.
  - `Flows / Definitions` — визуальный редактор флоу: конструктор шагов с выбором опубликованных `agentVersionId`, настройкой промптов/памяти/переходов и автоматической валидацией JSON; история версий отображает diff и change notes.
  - `Flow Workspace` — мониторинг сессии (progress bar, текущий step, latency/cost, usage source, retries), агрегированная телеметрия (`stepsCompleted`, `stepsFailed`, `retriesScheduled`, `totalCostUsd`, `promptTokens`, `completionTokens`, `lastUpdated`), collapsible shared context. Компонент `FlowTimeline` подписывается на long-poll/SSE, отображает `traceId`/`spanId`, usage/cost и позволяет экспортировать логи шага в JSON.

### Human-in-the-loop взаимодействия
- **Домен и хранения.**  
  - `flow_interaction_request` фиксирует канал маршрутизации (`chat_session_id`), связанный шаг (`flow_step_execution_id`), версию агента, тип запроса, SLA (`due_at`), JSON Schema формы (`payload_schema`) и подсказки (`suggested_actions`). Перед сохранением подсказки проходят санитарную обработку: rule-based элементы остаются, AI/analytics рекомендaции фильтруются allowlist-ом, запрещённые кандидаты попадают в `filtered`.  
  - `flow_interaction_response` хранит payload ответа, источник (`USER|AUTO_POLICY|SYSTEM`) и инициатора (`responded_by`), что позволяет строить аудит.
- **Сервисный слой.**  
  - `FlowInteractionService.ensureRequest` переводит шаг в `WAITING_USER_INPUT`, сессию — в `WAITING_USER_INPUT`, публикует `HUMAN_INTERACTION_REQUIRED` и останавливает очередь, пока не придёт ответ.  
  - `FlowInteractionExpiryScheduler` (параметр `app.flow.interaction.expiry-check-delay`, дефолт `PT1M`) закрывает просроченные заявки, создаёт событие `HUMAN_INTERACTION_EXPIRED` и возвращает шаг в очередь.  
  - `FlowControlService.cancel` и `FlowInteractionService.autoResolvePendingRequests` обеспечивают автоматическое закрытие заявок при отмене флоу.
- **API и проверки.**  
  - `GET /api/flows/{sessionId}/interactions` — список активных и исторических заявок.  
  - `POST /api/flows/{sessionId}/interactions/{requestId}/respond|skip|auto|expire` — требуют заголовок `X-Chat-Session-Id`, совпадающий с каналом из запроса; тело валидируется `FlowInteractionSchemaValidator` с поддержкой Spring AI форматов (`date`, `date-time`, `binary`, `json`).  
  - Стриминг событий — через `GET /api/flows/{sessionId}/events/stream` (SSE) и long-poll `GET /api/flows/{sessionId}`.  
  - Чатовый режим (Spring AI `ChatClient`) проставляет `requiresReply` → `ChatSyncController` создаёт `flow_interaction_request`, отображая bubble с CTA в UI.
- **Фронтенд.**  
  - Панель операторов отображает rule-based действия отдельным блоком, AI/analytics рекомендации — с бейджем “AI”; кнопка «Применить» переносит значения в форму, используя схему (`interactionSchema.ts`).  
  - Хедер `X-Chat-Session-Id` автоматически подставляется при любом POST запросе из UI, поэтому несогласованные каналы отклоняются на backend.
- **Наблюдаемость.**  
  - Micrometer: `flow_interaction_created`, `flow_interaction_responded`, `flow_interaction_auto_resolved`, `flow_interaction_expired`, gauge `flow_interaction_open`, timer `flow_interaction_wait_duration`.  
  - Логи содержат `interactionId`, `stepId`, `sessionId`, тип действия; WARN/ERROR применяются для неуспешных ответов и просрочек.  
  - При необходимости аномалий заводите оповещения на превышение открытых заявок или долю просроченных (threshold SLA маппится на `due_at`).
- **SLA и ретеншн.**  
  - SLA задаётся полем `interaction.dueInMinutes` в JSON определения шага; обновление не требует миграций.  
  - История заявок и ответов хранится 30 дней, очистка описана в runbook (см. `docs/runbooks/human-in-loop.md`).  
  - Для интеграции с внешними уведомлениями используйте `flow_event` и webhook-адаптер (`FlowNotificationService` roadmap).
- **Ссылки на Spring AI.**  
  - Поведение форм Align с [Spring AI Chat Client](https://docs.spring.io/spring-ai/reference/api/chatclients/index.html); поля `defaultOptions` и overrides управляют температурой, topP и лимитом токенов как для стандартных шагов, так и для human-in-the-loop веток.

## Тестирование
- `./gradlew test` прогоняет smoke-тест `ChatStreamControllerIntegrationTest` на MockMvc и HTTP e2e-сценарий `ChatStreamHttpE2ETest`, проверяющие потоковые ответы и сохранение истории.
- Для локального запуска используйте JDK 21 (на macOS: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`), тесты работают на H2 и заглушенном Spring AI клиенте, поэтому не требуют реального LLM.

## GitHub Actions
Workflow `.github/workflows/ci.yml` выполняет следующие шаги:
1. Прогон backend-тестов (`./gradlew test`, Testcontainers).
2. Сборка frontend (`npm run build`).
3. Ручной деплой на VPS по событию `workflow_dispatch`: на сервере выполняется `git pull`, пересборка Docker-образов и запуск `docker compose`.

Для деплоя необходимо определить секреты:
- `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH` (и опционально `DEPLOY_PORT`).

На сервере должен существовать клон репозитория. Скрипт деплоя выполняет `git pull` и `docker compose up -d`.
