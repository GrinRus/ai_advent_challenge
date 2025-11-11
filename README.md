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

## Repo RAG индексатор (Wave 31)
- После успешного `github.repository_fetch` backend запускает асинхронный job (`RepoRagIndexScheduler`), который обходит workspace, режет файлы на чанки (≈2 КиБ/160 строк) и сохраняет их в PgVector (`repo_rag_vector_store`). Параметры задаются через `GITHUB_RAG_*` (см. `.env.example`, `docs/infra.md`).
- `repo.rag_search` обновлён до v4: semantic chunking с `parent_symbol`/`span_hash`/`overlap_lines`, Compression/Rewrite/Translation QueryTransformers, `MultiQueryExpander` (с ограничением `maxQueries<=6`), `ConcatenationDocumentJoiner` и цепочка DocumentPostProcessor (heuristic rerank + context-budget + LLM-компрессор). Вход поддерживает `topKPerQuery`, `useCompression`, `multiQuery.maxQueries`, `maxContextTokens`, `filters.languages[]/pathGlobs[]`. Ответ содержит `instructions`, `augmentedPrompt`, `contextMissing`, `noResults`, `noResultsReason` и `appliedModules`.
- Добавлен инструмент `repo.rag_search_simple` — достаточно передать только `rawQuery`, MCP автоматически подставит последний READY namespace после `github.repository_fetch`.
- Практическое руководство и диагностика описаны в `docs/guides/mcp-operators.md`, архитектурные детали модулей — в `docs/architecture/github-rag-modular.md`.
- Метрики `repo_rag_queue_depth`, `repo_rag_index_duration`, `repo_rag_index_fail_total`, `repo_rag_embeddings_total` отслеживаются через Micrometer; рекомендуется настраивать алёрты на рост очереди и повторные ошибки воркеров.

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
- `backend/` — Spring Boot приложение (Java 21).
- `backend-mcp/` — HTTP MCP серверы (Agent Ops, Flow Ops, Insight, GitHub, Notes).
- `frontend/` — веб-клиент на React + Vite.
- `local-rag/` — вспомогательные утилиты и эксперименты с RAG (при необходимости).

Подробнее о организации кода и задачах — в документации.
