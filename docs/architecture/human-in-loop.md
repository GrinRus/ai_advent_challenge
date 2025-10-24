# Human-in-the-loop Architecture (Wave 10)

Дата: 2025-10-25  
Аудитория: backend, frontend, infra, продуктовые команды

## Цели
- Приостановить выполнение шага мультиагентного флоу до участия пользователя.
- Передавать управление конкретным пользователям через неизменяемый `chat_session_id`.
- Обеспечить наблюдаемость и отказоустойчивость очередей при остановке/возобновлении.

## Каноническая модель

### `flow_interaction_request`
| Поле | Тип | Описание |
| --- | --- | --- |
| `id` | UUID | Первичный идентификатор запроса |
| `flow_session_id` | UUID FK | Ссылка на активную сессию флоу |
| `step_execution_id` | UUID FK | Шаг, который запросил взаимодействие |
| `chat_session_id` | UUID | Канал маршрутизации; совпадает с сессией чата и не меняется |
| `agent_id` | UUID | Идентификатор агента (из `agent_version`) |
| `type` | enum | Тип запроса (`INPUT_FORM`, `APPROVAL`, `CONFIRMATION`, `REVIEW`, …) |
| `title` | text | Короткий заголовок для UI |
| `description` | text | Подробности запроса |
| `payload_schema` | jsonb | JSON Schema формы (поддерживаем типы из `docs/human-in-loop-scenarios.md`) |
| `suggested_actions` | jsonb | Список действий с источником (`ruleBased`, `llm`, `analytics`) |
| `due_at` | timestamptz | Дедлайн SLA |
| `status` | enum | `PENDING`/`ANSWERED`/`EXPIRED`/`AUTO_RESOLVED` |
| `created_at/updated_at` | timestamptz | Аудит |

### `flow_interaction_response`
| Поле | Тип | Описание |
| --- | --- | --- |
| `id` | UUID | Идентификатор ответа |
| `request_id` | UUID FK | Ссылка на запрос |
| `chat_session_id` | UUID | Канал, подтвердивший ответ; должен совпадать с запросом |
| `responded_by` | UUID | Пользователь или сервис-аккаунт |
| `payload` | jsonb | Данные формы / выбранное действие |
| `source` | enum | `USER`, `AUTO_POLICY`, `SYSTEM` |
| `created_at` | timestamptz | Время ответа |

Для хранения системных автодействий используем тот же ответ с `source=SYSTEM` и `responded_by` = служебный пользователь.

## Связь с Flow сущностями
- `FlowSession` получает новое состояние `WAITING_USER_INPUT`.
- `FlowStepExecution` хранит `interaction_request_id` пока ожидание активно.
- `FlowEvent` получает типы: `HUMAN_INTERACTION_REQUIRED`, `HUMAN_INTERACTION_RESPONDED`, `HUMAN_INTERACTION_EXPIRED`, `HUMAN_INTERACTION_AUTO_RESOLVED`.

## State Machine

### FlowSessionStatus
```
NEW → RUNNING → WAITING_USER_INPUT → RUNNING → (завершение)
```
- Переход в `WAITING_USER_INPUT` инициирует `AgentOrchestratorService` после создания `flow_interaction_request`.
- Возврат в `RUNNING` происходит при записи ответа (`FlowInteractionService.respond`) либо автоматическом резолве.
- Команды `pause`, `cancel` остаются валидными из `FluxControlService`; при `cancel` все открытые запросы получают статус `AUTO_RESOLVED` с причиной.

### FlowStepExecutionStatus
```
RUNNING → BLOCKED_WAITING_USER → RUNNING/FAILED/COMPLETED
```
- В состоянии `BLOCKED_WAITING_USER` воркер не поднимает задачу повторно, но сохраняет payload для retrigger.
- После ответа генерируется новая job (`FlowJobWorker.enqueueRetry`) с обновлённым input.

### Очередь
- `FlowJobWorker` проверяет перед обработкой, что ни сессия, ни шаг не находятся в `WAITING_USER_INPUT`.
- Дополнительный `Scheduler` проверяет `due_at` и вызывает `autoResolve` при истечении SLA.

## Доставка и API

### Выбранный подход
Комбинируем вариант A и B из backlog:
1. **SSE/long-poll канал `/api/flows/{sessionId}/events/stream`** — доставляет события `humanInteractionRequired` и `humanInteractionStatusChanged` в Flow Workspace.
2. **Чатовая интеграция** — `ChatStreamController` при флаге `requiresReply` создаёт `flow_interaction_request` и отправляет bubble с CTA, используя тот же `chat_session_id`.

Эскалация и повторная маршрутизация выполняются через запуск новой чат-сессии (см. `docs/human-in-loop-scenarios.md`), тогда как первый запрос остаётся read-only.

### REST API
- `GET /api/flows/{sessionId}/interactions` — активные и последние закрытые запросы.
- `POST /api/flows/{sessionId}/interactions/{requestId}/respond` — принимает payload, валидирует против `payload_schema`.
- `POST /api/flows/{sessionId}/interactions/{requestId}/auto` — системный автозавершение.
- `POST /api/flows/{sessionId}/interactions/{requestId}/expire` — ручная фиксация истечения SLA (support runbook).
- Все POST требуют `X-Chat-Session-Id` или экв. токен для валидации.

### Sequence (упрощённо)
1. Шаг агента вызывает `FlowInteractionService.createRequest(...)`.
2. Сервис сохраняет `flow_interaction_request`, публикует `FlowEvent`.
3. `AgentOrchestratorService` переводит сессию и шаг в ожидающее состояние, возвращает управление воркеру.
4. UI получает событие, показывает форму; пользователь отвечает.
5. `respond` валидирует schema, создаёт `flow_interaction_response`, переводит статусы, планирует retry job.
6. Воркер возобновляет обработку шага с дополненным payload.

## Наблюдаемость
- Метрики:
  - `flow.interaction.open` (gauge по `status=PENDING`).
  - `flow.interaction.wait.duration` (timer от создания до закрытия).
  - `flow.interaction.auto_resolved.count`.
- Логи: `interactionCreated`, `interactionResponded`, `interactionExpired`, `interactionAutoResolved`.
- Tracing: `FlowJobWorker` добавляет span атрибут `interactionId`, `chatSessionId`, `interactionStatus`.

## Сопровождение и SLA
- SLA по типам запроса хранится в `AgentVersion` и передаётся в `due_at`.
- Retention: запросы и ответы активны 30 дней, после чего архивируются батч-задачей.
- Runbook: см. `docs/human-in-loop-scenarios.md` для UX/поддержки, текущий документ — для инженеров.

## Стратегия `suggestedActions`
- **Rule-based минимум**: каждое определение шага в `AgentVersion` указывает обязательный набор действий (`approve`, `decline`, `retry`, и т.п.) с текстами кнопок и допустимыми параметрами. Эти действия всегда отображаются и проходят строгую валидацию на backend.
- **LLM-рекомендации**: агент может вернуть дополнительный список предложений. Backend фильтрует их по allowlist (разрешённые действия и параметры), маркирует источником `llm` и визуально отделяет от rule-based блока. Если проверка не проходит, предложение отбрасывается и логируется.
- **Аналитические подсказки (Wave 10.x)**: в backlog сохраняем план агрегировать успешные ответы и предлагать top-N действий для схожих шагов. Реализацию переносим на последующие итерации, чтобы не усложнять rollout Wave 10.
