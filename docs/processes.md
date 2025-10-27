# Процессы разработки

Документ описывает, как мы поддерживаем качество продукта, тестирование и документацию.

## Разработка
- Базовая ветка — `main`; для фич создавайте короткоживущие ветки с понятными именами.
- Коммиты атомарны, описывают изменения в утверждённом формате (`type: short summary`).
- Pull Request сопровождается ссылкой на задачу из `docs/backlog.md` или трекера.

## Тестирование
- Backend — запускайте `./gradlew test` и интеграционные сценарии перед PR.
- Frontend — `npm run test` (юнит-тесты) и визуальные проверки в Storybook/e2e по мере развития.
- При изменении LLM-клиента добавляйте регрессионные тесты (mock/stub), чтобы проверять модельные параметры и поток SSE.
- Изменения в памяти чата сопровождайте модульными и интеграционными проверками окна `ChatMemory` (SSE, восстановление диалога, очистка).
- Для plain sync (`/api/llm/chat/sync`) проверяйте сохранение текстовых сообщений, прокинутые usage/cost и обработку 429/5xx/пустых ответов.
- Для structured sync (`/api/llm/chat/sync/structured`) обязательны:
  - unit-тесты сериализации/десериализации и конфигурации `RetryTemplate` (проверяем попытки, статусы, exponential backoff);
  - интеграционные проверки сохранения `chat_message.structured_payload`, поведения при 422/429/5xx и лимита попыток.
- В e2e сценариях (Playwright/Cypress) проверяйте переключение вкладок чата, отображение структурированного ответа (summary/items/usage) и корректную обработку ошибок сервера.
- При работе с fallback-токенизацией добавляйте модульные тесты на `TokenUsageEstimator` и проверяйте кэш Redis (TTL, graceful degradation). В потоковых тестах фиксируйте значение `usageSource` (`native|fallback`), чтобы исключить регрессии в UI.
- Для оркестратора флоу:
  - Unit: state machine (`flow_session` статусы, ветвления `transitions`), обработка overrides, работа Memory adapters (`flow_memory_version`).
  - Integration: Testcontainers (Postgres + Redis) для очереди `flow_job` и событий `flow_event`, сценарии `start/pause/resume/cancel/retry`. Проверяйте SSE `/api/flows/{sessionId}/events/stream` и long-poll fallback (timeout, `sinceEventId`/`stateVersion`).
  - Contract/UI: JSON Schema валидация редактора, Playwright сценарии `create flow → publish → launch → monitor → export logs`, проверка telemetry snapshot (aggregate counters, shared context, progress bar) и экспорт событий.
- Для конструктора и миграции blueprint:
  - Unit: `FlowBlueprintValidator`, step validator и CLI (`FlowBlueprintMigrationService`), проверка dry-run/rollback.
  - Contract: `FlowDefinitionControllerV2IntegrationTest`, `AgentDefinitionControllerIntegrationTest`, фронтовые Zod-схемы (`flowDefinition.test.ts`, `apiClient.types.test.ts`) — фиксируем `blueprint_schema_version`, launch preview и reference endpoints.
  - При запуске миграций через CLI (`app.flow.migration.cli.*`) фиксируйте dry-run лог, подтверждение обновления и итоги (processed/updated/validationFailures).
- Scheduler: для `FlowJobWorker` пишем unit-тесты (Mock `AgentOrchestratorService`, проверяем `processed|empty|error`) и smoke-интеграцию с включённым `@Scheduled` bean. Логи на INFO содержат `workerId`, результат и длительность; ошибки фиксируем на ERROR и проверяем Micrometer (`flow.job.poll.count/duration`).

## Runbook: саммаризация истории

1. **Мониторинг.** Следим за метриками `chat_summary_runs_total`, `chat_summary_tokens_saved_total`, `chat_summary_duration_seconds`, `chat_summary_queue_size`, `chat_summary_queue_rejections_total`, `chat_summary_failures_total`, `chat_summary_failure_alerts_total` и flow-метриками `flow_summary_runs_total`, `flow_summary_duration_seconds`, `flow_summary_queue_size`, `flow_summary_queue_rejections_total`, `flow_summary_failures_total`, `flow_summary_failure_alerts_total`. Рост очереди без увеличения `runs_total` — повод поднять `...MAX_CONCURRENT_SUMMARIES` или `...MAX_QUEUE_SIZE`. Повторяющиеся failure alerts сигнализируют о деградации провайдера или неверной модели.
2. **Диагностика.** Логи уровня INFO содержат сообщения о постановке задач в очередь и её отказах, WARN фиксируют временные ошибки модели, ERROR появляется после трёх подряд неудач по одной сессии. Для flow-сессий сверяем `flow_memory_summary.source_version_start/end`, чтобы убедиться, что summary догнало текущее окно.
3. **Ручной пересчёт (chat).** Для отдельного диалога вызываем `POST /api/admin/chat/sessions/{sessionId}/summary/rebuild` с телом `{ "providerId": "openai", "modelId": "gpt-4o-mini" }`. Для массового пересчёта включаем `CHAT_MEMORY_SUMMARIZATION_BACKFILL_ENABLED=true`, задаём пороги `...MIN_MESSAGES`/`...BATCH_SIZE` и перезапускаем backend — job логирует количество обработанных сессий.
4. **Ручной пересчёт (flow).** Используем CLI (`APP_FLOW_SUMMARY_CLI_ENABLED=true` + `app.flow.summary.cli.*`) либо REST `/api/admin/flows/sessions/{sessionId}/summary/rebuild`. Перед массовым backfill временно отключаем алерты, после завершения проверяем `flow_memory_summary.summary_text`, `metadata.agentVersionId`, `ChatSession.summaryMetadata.updatedAt` и `FlowMemoryService.history()` (summary + хвост).
5. **Конфигурация каналов.** Все пользовательские и агентские сообщения дублируются в канал `conversation` (даже если DSL использует `shared`). FlowInteractionService после ответа оператора записывает payload в тот же канал и сразу триггерит summarizer, поэтому операторы/агенты всегда видят актуальное summary.
6. **SLO.** Среднее время ответа summarizer-а — <3 c, очередь <80% от `maxQueueSize`, не более 1% failure alerts за 15 минут. Если показатели выше, уведомляем on-call и при необходимости переключаем модель/провайдера или временно выключаем summarizer.
7. **Документация.** Любые изменения конфигурации фиксируем в `docs/infra.md` и README, а для прод-окружения сохраняем overrides в секрет-хранилище.
8. **Перезапуск воркера.** При зависании summarizer-а перезапускаем backend-под (воркер вшит в приложение) и убеждаемся, что после рестарта `flow_summary_queue_size` возвращается к 0. Параметры `app.chat.memory.summarization.maxConcurrentSummaries` и `...maxQueueSize` регулируют пропускную способность; при деградации модели временно переводим `app.chat.memory.summarization.enabled=false`.

## Runbook: конструктор флоу и агентов (Wave 12)

1. **Мониторинг.**  
   - Основные графики: `constructor_flow_blueprint_saves_total{action}` и `constructor_agent_version_saves_total{action}` (stacked area) + heatmap `constructor_user_events_total{event}` для активности по ролям.  
   - Алерты:  
     - `rate(constructor_validation_errors_total[5m]) > 5` — всплеск ошибок;  
     - `rate(constructor_validation_errors_total[5m]) / rate(constructor_user_events_total[5m]) > 0.2` при `rate(constructor_user_events_total[5m]) > 1` — массовое падение UX;  
     - `rate(constructor_validation_errors_total{stage="publish"}[5m]) > 0` — проблемы публикаций.
2. **Диагностика.**  
   - Смотрим лог `ConstructorAudit` (фильтр по `definitionId`/`versionId`). Повторяющиеся `constructor_validation_error stage=...` показывают, какая операция ломается.  
   - Проверяем свежие изменения: схемы `FlowBlueprint`, Zod-схемы frontend, настроенные фичи в `FlowDefinitionController`.
3. **Реакция.**  
   - `stage=create|update` — ошибки ввода или несовпадение DTO. Подтверждаем сценарий на UI, запускаем unit/contract тесты, при необходимости временно блокируем форму и сообщаем UX.  
   - `stage=publish` — откатываем последнюю публикацию (`flow_definition.status=DRAFT`, `agent_version.status=DEPRECATED`), документируем workaround и готовим hotfix.  
   - Массовые ошибки (>20% за 10 минут) — включаем read-only баннер, уведомляем Ops/Designer, фиксируем RCA.
4. **Пост-мортем.**  
   - Обновляем раздел «Телеметрия и аудит конструктора» в `docs/architecture/flow-definition.md` и `docs/infra.md` конкретными выводами.  
   - Добавляем регрессионные тесты (`FlowDefinitionControllerV2IntegrationTest`, `AgentDefinitionControllerIntegrationTest`, frontend Zod-схемы) или дополнительную валидацию в `FlowBlueprintValidator`/`AgentCatalogService`.

## Документация оркестратора
- При изменении схемы `flow_definition`/`flow_job`/`flow_memory_version` обновляйте раздел «Оркестрация» в `docs/infra.md` (архитектура, форматы JSON, политика памяти, worker-параметры).
- Политики памяти, TTL и очистки фиксируем в `docs/infra.md` + `.env.example`. При изменениях обязательно добавляйте ссылку на миграции и cron/batch.
- В `docs/processes.md` поддерживаем чек-лист: что нужно обновить при добавлении нового шага/транзишена/агента (def JSON, миграции, UI, телеметрия, тесты). Каждое изменение должно проходить ревизию архитектурой и обновлять ADR при необходимости.

## CI/CD
- Workflow `.github/workflows/ci.yml` обязан проходить без модификации.
- Секреты деплоя хранятся в GitHub Secrets; локальные `.env` не коммитим.
- При обновлении пайплайнов обязательно документируйте изменения в `docs/infra.md`.

## Документация
- Перед слиянием проверяйте, что затронутые разделы обновлены.
- Если появился новый раздел, добавьте ссылку в `docs/overview.md`.
- В `docs/CONTRIBUTING.md` содержится чек-лист по проверке документации.
- При добавлении LLM-провайдеров или моделей обязательно синхронизируйте `application.yaml`, `.env.example`, `docker-compose.yml` и описание в `docs/infra.md` (таблица моделей, переменные окружения).
- При обновлении конфигураций флоу убедитесь, что `docs/infra.md` и `docs/backlog.md` отражают структуру `flow_definition`, `flow_event`, TTL памяти и API /flows. Новые схемы включайте в ADR.

### Чек-лист онбординга агента (Wave 9.1)
1. **Создание определения** — `POST /api/agents/definitions` (identifier, displayName, описание, `createdBy`). Согласовать slug и назначение в backlog.
2. **Добавление версии** — `POST /api/agents/definitions/{id}/versions` с `providerId`, `modelId`, `systemPrompt`, при необходимости `defaultOptions`/`toolBindings`/`costProfile`, `capabilities[]`, `createdBy`.
3. **Публикация** — `POST /api/agents/versions/{versionId}/publish` (`updatedBy`, актуализированные `capabilities`). Проверить, что определение стало активным и видно в UI `Flows / Agents`.
4. **Интеграция в флоу** — обновить шаблон через `Flows / Definitions`, выбрать новую версию в конструкторе шагов, прогнать smoke (`FlowLaunchPreviewService.preview`, `POST /api/flows/{flowId}/start`).
5. **Документация и тесты** — синхронизировать `docs/infra.md`, `docs/architecture/flow-definition.md`, добавить юнит/интеграционные и UI-тесты (`npm run test`, e2e при необходимости).

## Наблюдаемость
- При включении structured sync (`/sync/structured`) логируйте latency и значения `promptTokens/completionTokens/totalTokens`, чтобы быть готовыми к интеграции метрик в последующих волнах.
- Ошибки схемы фиксируйте на уровне WARN/ERROR с указанием провайдера, модели и `requestId`; при повторных провалах добавляйте fallback-инструкцию или эскалацию.
- StructuredSyncService уже пишет DEBUG/INFO с номером попытки и причиной остановки (429/5xx, schema). Не гасите эти логи в проде — по ним строится отчёт о стабильности провайдеров.
- Для внешних провайдеров без строгого JSON Schema внимательно анализируйте ретраи: указывайте в логах счётчик попыток и причину остановки.
- Стоимость и usage:
  - Spring AI отдаёт usage через `ChatResponseMetadata.getUsage()` — проверяйте, что провайдер возвращает значения, иначе стоимость будет равна `null`.
  - После релиза проверяйте `GET /api/llm/sessions/{id}/usage` и сравнивайте with billing dashboard провайдера; расхождения >5 % фиксируйте как баг.
  - Следите за валютой (`currency` из `ChatProvidersProperties.Model.pricing`). Если провайдер переключается на другую валюту, обновляйте конфигурацию и документацию.
  - В фронтенде не доверяйте кешу: при смене сессии запрашивайте usage повторно (см. `fetchSessionUsage`).
  - Если провайдер не возвращает usage, контролируйте fallback: в логах и API должно отображаться `usageSource=fallback`, а Redis-хит/мисс собирается метриками (см. Wave 8).
  - Метрики Micrometer: `chat.usage.native.count`/`chat.usage.fallback.count` (теги `provider`, `model`), `chat.usage.fallback.delta.tokens` (тег `segment = total|prompt|completion`) и `chat.token.cache.requests`/`chat.token.cache.latency` (тег `result = hit|miss|error|write|write_error`). Настройте алерты на рост доли `result=error` и абсолютное значение `fallback.delta`.
- Для флоу используйте структурированные JSON-логи и OTel спаны на события `flow.started`, `step.started`, `step.completed`, `flow.paused/resumed/stopped`. Отслеживайте дашборды по метрикам `flow_sessions_active`, `flow_step_duration`, `flow_retry_count`, `flow_cost_usd` и по доле `usageSource=fallback`. При сбоях фиксируйте `flow_session_id`, `step_id` и `job_uid` для восстановления.

## Управление задачами
- Волны (Wave) фиксируются в `docs/backlog.md`.
- По завершении задачи отмечайте статус и указывайте дату/версию, если критично.
- Крупные изменения сопровождайте коротким пост-мортем или заметками в `docs/overview.md`.

## Human-in-the-loop
- **Перед добавлением шага:**  
  1. Настроить `interaction` в JSON-файле флоу (`type`, `title`, `description`, `payloadSchema`, `dueInMinutes`, `suggestedActions`).  
  2. Прогнать схему через `FlowInteractionSchemaValidator` (поддерживаемые форматы: `date`, `date-time`, `binary`, `json`, `textarea`, `radio`, `toggle`).  
  3. Rule-based подсказки заносим в `allow` и `ruleBased`, AI/analytics — в `llm`/`analytics`, чтобы sanitizer не показал элементы вне allowlist.
- **UX и SLA:**  
  - Заголовок ≤ 60 символов, описание содержит контекст и ожидаемое действие.  
  - Обязательные поля форм помечаем `required`, добавляем пример/placeholder.  
  - SLA (`dueInMinutes`) согласовываем с бизнесом, фиксируем в описании шага и runbook; auto-expire обязан корректно отрабатывать через scheduler.
- **Проверка PR:**  
  - Backend: unit (`FlowInteractionService`, `FlowInteractionSchemaValidator`, `SuggestedActionsSanitizer`), интеграционные тесты `/respond|skip|auto|expire` с `X-Chat-Session-Id`, проверка `FlowInteractionExpiryScheduler`.  
  - Frontend: unit (форма + подсказки), e2e (оператор отвечает, пропускает, SLA истёк), визуальные снапшоты панели.  
  - Документация: обновить `docs/infra.md`, `docs/human-in-loop-scenarios.md`, `docs/runbooks/human-in-loop.md` и данный документ.
- **Поддержка:**  
  - Руководствуется runbook’ом (`docs/runbooks/human-in-loop.md`) для поиска, переадресации и ручного автозавершения заявок.  
  - Следит за дашбордами `flow_interaction_open`, `flow_interaction_wait_duration`, долей `status=EXPIRED`, корректностью внешних уведомлений (Slack/email/webhook).  
  - При эскалации закрывает заявку, запускает новую сессию с другим `chat_session_id`, ссылку на старую фиксирует в комментарии.

## Чек-лист тестирования human-in-the-loop
- Backend unit: `FlowInteractionService.ensureRequest/recordResponse/autoResolve/expire`, `FlowInteractionSchemaValidator`, `SuggestedActionsSanitizer`.  
- Backend integration: REST (`respond|skip|auto|expire`) с корректным/некорректным `X-Chat-Session-Id`, `FlowInteractionExpiryScheduler`, `FlowControlService.cancel` → `autoResolvePendingRequests`.  
- Frontend: unit (генерация формы, применение подсказок, локальная валидация), e2e (ответ оператора, пропуск/auto-resolve), визуальные тесты панели и блока suggested actions.  
- Документация: `docs/infra.md`, `docs/human-in-loop-scenarios.md`, `docs/runbooks/human-in-loop.md`, текущий файл.  
- Наблюдаемость: алерты на `flow_interaction_*`, heartbeat SSE, проверка интеграций уведомлений.
