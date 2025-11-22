# Архитектура backend

Backend реализован на Spring Boot (Java 22) и следует принципам Clean Architecture. Основные слои и зависимости описаны ниже.

## Слои и зависимости
- **Controller слой** (`backend/src/main/java/com/aiadvent/backend/*/controller`) — выставляет REST и SSE эндпоинты, делегирует бизнес-логику сервисам.
- **Service слой** (`.../service`) — инкапсулирует использование доменных моделей и orchestrates обращения к внешним сервисам (LLM-клиент, хранилища).
- **Domain слой** (`.../domain`) — содержит бизнес-объекты, модели и интерфейсы репозиториев.
- **Persistence слой** (`.../persistence`) — адаптеры к базе данных и другим хранилищам (Spring Data, Liquibase).

Зависимости двигаются направленно: controller → service → domain → persistence. Общая конфигурация и общие компоненты лежат в `backend/src/main/java/com/aiadvent/backend/config`.

## Поток обработки чата
1. SSE запрос попадает в `ChatStreamController`.
2. Сервис `ChatStreamService` собирает историю диалога, подготавливает запрос к Spring AI.
3. Spring AI клиента вызывает провайдера z.ai и стримит ответы.
4. Репозитории сохраняют события диалога и метаданные.

## Конфигурация
- Параметры LLM вынесены в `application.yaml` с поддержкой профилей `local` и `prod`.
- Liquibase управляет схемой (см. `backend/src/main/resources/db/changelog/`).
- Для тестов используется комбинация MockMvc, Testcontainers и заглушек Spring AI.

## Диаграммы и дополнительные материалы
Графические схемы backend размещаются в `docs/architecture/diagrams`. При обновлениях диаграмм добавляйте исходники (PlantUML, Excalidraw) и экспорт.

## Flow summaries
- Админский REST-эндпоинт `/api/admin/flows/sessions/{sessionId}/summary/rebuild` заставляет `FlowMemorySummarizerService` пересчитать summary для указанных каналов и провайдера (по умолчанию канал `conversation`).
- Для офлайновых перезапусков добавлен CLI-раннер (`app.flow.summary.cli.*`): достаточно передать `session-id`, `provider-id`, `model-id` и (опционально) список каналов, после чего при старте приложения будет инициирован пересчёт.
- Результаты взаимодействий (HITL) записываются в канал `conversation` через `FlowInteractionService` и сразу прогоняются через summarizer, поэтому операторы и агенты всегда видят окно «summary + хвост» без ручного вмешательства.
- Наблюдаемость обеспечивается отдельными метриками `flow_summary_runs_total`, `flow_summary_duration_seconds`, `flow_summary_queue_size`, `flow_summary_queue_rejections_total`, `flow_summary_failures_total`, `flow_summary_failure_alerts_total`, чтобы отличать health flow-саммаризации от чатового воркера и строить алерты.
