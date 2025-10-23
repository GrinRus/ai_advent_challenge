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
  - Contract/UI: JSON Schema валидация редактора, Playwright сценарии `create flow → publish → launch → monitor → export logs`.
  - Scheduler: для `FlowJobWorker` пишем unit-тесты (Mock `AgentOrchestratorService`, проверяем `processed|empty|error`) и smoke-интеграцию с включённым `@Scheduled` bean. Логи на INFO содержат `workerId`, результат и длительность; ошибки фиксируем на ERROR и проверяем Micrometer (`flow.job.poll.count/duration`).

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
