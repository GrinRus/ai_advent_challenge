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

## Docker Compose
Скопируйте `.env.example` в `.env`, откорректируйте значения переменных при необходимости.

Для локального запуска всех компонентов выполните:

```bash
docker compose up --build
```

Переменные окружения для backend (URL, логин, пароль БД) передаются через `APP_DB_*` и уже заданы в `docker-compose.yml`. При необходимости вынесите их в `.env` файл и подключите через ключ `env_file`.

Frontend контейнер проксирует все запросы `/api/*` на backend, поэтому приложение доступно по адресу `http://localhost:4179`, а API — через `http://localhost:4179/api`.

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
