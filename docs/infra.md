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

### Модель данных
- Каталог агентов (таблицы `agent_definition`, `agent_version`, `agent_capability`) хранит системные промпты, дефолтные опции Spring AI (`ChatProviderType`, `modelId`, `defaultOptions`), ограничения (`syncOnly`, `maxTokens`) и описания `toolBindings` (список методов `@Tool`). Начиная с Wave 9.1 фиксируем аудитные поля `created_by`/`updated_by` и список возможностей (`capability`, произвольный JSON payload). Флаг `is_active` на уровне `agent_definition` автоматически включается при публикации новой версии.
  - REST API каталога:  
    - `GET /api/agents/definitions` — список определений с последними версиями.  
    - `GET /api/agents/definitions/{id}` — детали + список версий.  
    - `POST /api/agents/definitions` — создание определения (требует `createdBy`).  
    - `PUT /api/agents/definitions/{id}` / `PATCH /api/agents/definitions/{id}` — обновление параметров и статуса (`updatedBy`).  
    - `POST /api/agents/definitions/{id}/versions` — новый черновик версии (обязателен `createdBy`, `providerId`, `modelId`, `systemPrompt`).  
    - `POST /api/agents/versions/{versionId}/publish` — публикация с возможностью обновить capabilities; `POST /api/agents/versions/{versionId}/deprecate` — вывод из эксплуатации.  
  - UI (`Flows / Agents`) использует кэшированный запрос каталога, чтобы не перегружать API при навигации; кэш сбрасывается после любой мутации (`invalidateAgentCatalogCache()` в `apiClient.ts`).
- Флоу:
  - `flow_definition` — черновики и опубликованные версии. Поля: `id`, `name`, `version`, `status`, `definition_jsonb`, `is_active`, `updated_by`, `published_at`.
  - `flow_definition_history` — снимки версий с `change_notes` и автором.
  - `flow_session` — запуски (`PENDING`, `RUNNING`, `PAUSED`, `FAILED`, `COMPLETED`, `ABORTED`), `launch_parameters`, `shared_context`, `current_step_id`, `current_memory_version`.
  - `flow_step_execution` — состояние шага (attempt, prompt, input/output, usage/cost, timestamps).
  - `flow_event` — журнал (`event_type`, `status`, `payload_jsonb`, `usage/cost`, `trace_id`, `span_id`) для SSE и аудита.
  - `flow_memory_version` — shared/isolated память, версии и TTL. Держим последние 10 версий и чистим записи старше 30 дней батчем.

### Формат flow definition
- JSON содержит `startStepId`, массив `steps[]` (id, name, `agentVersionId`, `prompt`, `overrides`, `memoryReads`, `memoryWrites`, `transitions`, `maxAttempts`).
- `memoryReads` описывают канал (`channel`, `limit`). `memoryWrites` — канал/режим (`AGENT_OUTPUT|STATIC`) и сериализованный payload (для STATIC).
- `transitions` поддерживает `onSuccess.next`, `onSuccess.complete`, `onFailure.next`, `onFailure.fail`, а также дополнительные ветвления (`conditions[]`) в расширениях.

### Политики памяти и конфигурации
- Настройки памяти (`app.chat.memory.*`) определяют размер окна (`window-size`), TTL (`retention`) и интервал очистки (`cleanup-interval`). Shared/isolated каналы инжектируются через Spring AI `MessageChatMemoryAdvisor`.
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
  - `Flow Workspace` — мониторинг сессии (progress bar, текущий step, latency/cost, usage source, retries), expandable карточки шагов, отображение shared памяти и параметров запуска. Компонент `FlowTimeline` подписывается на long-poll/SSE и позволяет экспортировать логи шага в JSON/Markdown.

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
