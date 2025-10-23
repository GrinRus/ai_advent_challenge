# Human-in-the-loop сценарии для мультиагентных флоу

Дата: 2025-10-24  
Аудитория: продукт, поддержка, фронтенд, бэкенд

## Цели и предпосылки
- Агент не может завершить шаг без уточнений / подтверждений пользователя.
- Пользователь должен видеть контекст запроса (что хочет агент, входные данные, последствия) и предоставить ответ/решение.
- Оркестратор (`AgentOrchestratorService`) приостанавливает выполнение шага и ожидает действий от человека.

Документ описывает три ключевых маршрута: работа внутри Flow Workspace, ответы из чатового интерфейса и обработка фоновых флоу.

## Сценарии во Flow Workspace

### 1. Уточнение входных данных
1. Агент на шаге `collect_context` формирует запрос к пользователю (`requiresUserInput=true`, запрошенные поля: список параметров).
2. Оркестратор создаёт запис
   - `flow_interaction_request`: `agentId`, `stepExecutionId`, `payloadSchema` (JSON Schema), `dueAt`.
   - `flow_event` c типом `human_interaction_required`.
3. Состояние сессии переключается в `WAITING_USER_INPUT`; `FlowJobWorker` не берёт новые джобы.
4. В Flow Workspace появляется карточка:
   - заголовок шага + агент;
   - описание запроса;
   - форма, сгенерированная по `payloadSchema` (поддерживаем типы `string`, `number`, `enum`, `array`, `object`).
5. Поддержка/оператор вводит значения и нажимает **Submit**.
6. API `POST /api/flows/{sessionId}/interactions/{requestId}/respond` сохраняет ответ (`flow_interaction_response`), обновляет `flow_step_execution.inputPayload`.
7. Оркестратор возобновляет очередь: статус шага → `RUNNING`, сессии → `RUNNING`, в job queue добавляется retry со свежим payload.
8. UI показывает уведомление об успешной передаче. Карточка переходит в раздел «History».

### 2. Подтверждение критического действия
1. Агент предлагает выполнить действие (например, «отменить подписку»). `suggestedActions`: `approve`, `decline`.
2. Flow Workspace открывает модальное окно с предупреждением, стоимостью или SLA.
3. Пользователь:
   - выбирает `approve` → POST `/skip` или `/respond` с `{"action":"approve"}`;
   - выбирает `decline` → агент получает отказ, шаг отмечается как отклонённый, оркестратор идёт по ветке `onFailure`.
4. Отказ фиксируется в `flow_event` и audit trail; новая ветка может либо завершить флоу, либо вернуть запрос на уточнение.

### 3. Auto-resolve по таймауту
1. В `flow_interaction_request.dueAt` указано время истечения SLA (например, 30 минут).
2. `FlowJobWorker` запускает периодическую проверку (еб ещё один scheduler) и при превышении дедлайна:
   - создаёт системный ответ (`action="auto"` с дефолтным payload);
   - выставляет статус `autoResolved`;
   - отправляет уведомление в Flow Workspace и `flow_event`.
3. В UI карточка помечается как auto-resolved (с серым бейджем, указанием ответственного и временем).

## Сценарии в Chat UI

### 4. Встроенный запрет на продолжение диалога
1. Агент, работающий в чате (режим `stream/sync`), ставит флаг `requiresReply`.
2. `ChatStreamController` обнаруживает запрос и преобразует его в `flow_interaction_request`, привязанный к чатовой сессии (`chat_session_id`).
3. В чат UI рендерится bubble:
   > «Agent X требует подтверждения. Подробнее… [Открыть форму]».
4. Пользователь кликает → компонент Flow sidebar открывает форму.
5. Ответ сериализуется и как `interaction_response`, и как чат-сообщение (для журналирования истории).

### 5. Минимальный ответ из чата
- Простые да/нет можно обрабатывать прямо в bubble (кнопки «Approve», «Decline»), вызывающие соответствующие REST-методы.
- После ответа карточка исчезает и чат продолжает диалог.

## Фоновые флоу и уведомления

### 6. Запрос без активной вкладки
- Пользователь не находится в Flow Workspace.
- Срабатывает system notification (например, email/webhook/Slack) о запросе с ссылкой `/flows/sessions/{id}?request={requestId}`.
- Предусмотреть badge в навигации, счётчик активных запросов, push/desktop уведомления.

### 7. Делегирование/переадресация
- В карточке присутствует кнопка **Reassign** с выбором другого специалиста/группы.
- API `POST /api/flows/{sessionId}/interactions/{requestId}/assign` обновляет `assignee`, логирует событие и уведомляет новую группу.

### 8. Ручное автозавершение (support runbook)
- Оператор может вызвать `POST /auto` без запроса пользователя (например, при инциденте).
- Вносится в history с пометкой «Force auto-resolve» и именем оператора.

## SLA и статусы
- `pending` – запрос создан, ожидает ответа.
- `answered` – пользователь ответил, оркестратор возобновил шаг.
- `expired` – SLA истёк, требуется ручное вмешательство поддержки.
- `autoResolved` – автоматический ответ (по политике или явной команде).
- SLA и политики зависят от типа запроса:
  - «Уточнение параметров» — 30 минут, после истечения → автозаполнение дефолтами.
  - «Подтверждение критического действия» — 15 минут; при отсутствии ответа шаг блокируется и помечается на отчёте.

## Требования к API и фронтенду
- `GET /api/flows/{sessionId}/interactions` → список активных + последние N закрытых запросов (для history панели).
- `POST /respond` принимает JSON, валидирует против `payloadSchema`.
- `POST /skip`/`/auto` для отказа / auto-resolve.
- `POST /assign` принимает `{ assigneeUserId, reason }`.
- SSE/long-poll события включают `interactionId`, `status`, `assignee`, `dueAt`, чтобы UI мог обновить badge без доп. запросов.
- UI-стейты:
  - список активных запросов в навигации (badge count);
  - карточка с form + metadata + похожие примеры;
  - history (последние 20 запросов).

## Наблюдаемость и поддержка
- Метрики: `flow.interaction.active`, `flow.interaction.latency`, `flow.interaction.auto_resolved`, `flow.interaction.error`.
- Логи на INFO: `interactionCreated`, `interactionResponded`, `interactionAutoResolved`, `interactionExpired`.
- Runbook:
  - проверить badge count / список активных в Flow Workspace;
  - при истечении SLA — force auto-resolve или переадресация;
  - при системных ошибках — см. `flow-service` логи (search `interactionId`).

## Открытые вопросы
- RBAC: карта «кто может отвечать за какой тип флоу». Нужно разработать matrix (owner, backup, escalation).
- История: сколько хранить закрытых запросов? Предлагается аналогично `flow_event` — 30 дней.
- Набор типов форм (`payloadSchema`): MVP — базовые поля (text, textarea, number, select, checkbox).
- Автоматический подбор suggested actions (approve/decline) — требуется дизайн UX.

