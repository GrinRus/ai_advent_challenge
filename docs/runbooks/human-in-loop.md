# Runbook: Human-in-the-loop запросы

## Контексты
- **Flow Workspace** — основное рабочее место оператора. Содержит список активных запросов, форму, подсказки и таймлайн.
- **API** — `GET /api/flows/{sessionId}/interactions` и `POST /api/flows/{sessionId}/interactions/{requestId}/respond|skip|auto|expire`. Все POST требуют заголовок `X-Chat-Session-Id`.
- **Метрики** — Micrometer (`flow_interaction_open`, `flow_interaction_wait_duration`, `flow_interaction_created|responded|auto_resolved|expired`) + heartbeat SSE `flow.events.delivered`.

## Быстрый старт (TL;DR)
1. Определить `sessionId`/`requestId` (из UI или по API).  
2. Сверить `chat_session_id` (панель «Статус» → поле *Chat session*).  
3. Выполнить действие:
   ```bash
   curl -X POST \
     -H "Content-Type: application/json" \
     -H "X-Chat-Session-Id: <chat_session_id>" \
     http://localhost:8080/api/flows/<sessionId>/interactions/<requestId>/respond \
     -d '{"chatSessionId":"<chat_session_id>","payload":{"comment":"approved manually"}}'
   ```
4. Проверить, что шаг вернулся в работу (`Flow Workspace` → статус шага сменился на `PENDING/RUNNING`, в таймлайне появилось событие `HUMAN_INTERACTION_RESPONDED`).

## Просмотр активных запросов
- **UI:** `Flows → Flow Workspace → Активные запросы`. Список сортируется по времени создания; бейдж в навигации показывает count.  
- **API:** `GET /api/flows/{sessionId}/interactions` → поле `active[]`. Сохраните ответ — он нужен в случае инцидентов.  
- **CLI:**  
  ```bash
  curl http://localhost:8080/api/flows/<sessionId>/interactions | jq '.active[] | {requestId, stepId, status, dueAt}'
  ```

## Переадресация/эскалация
1. Закрываем текущую заявку (respond/skip/auto) и документируем причину (поле `payload.comment`).  
2. Запускаем новый флоу/чат с новым `chat_session_id`. В описании шага указываем ссылку на предыдущую сессию.  
3. Если требуется передать контекст, используем `sharedContext` или добавляем комментарий в CRM/таск-трекере.

## Ручное автозавершение / истечение SLA
- **Auto-resolve** (системное завершение с указанием причины):
  ```bash
  curl -X POST \
    -H "Content-Type: application/json" \
    -H "X-Chat-Session-Id: <chat_session_id>" \
    http://localhost:8080/api/flows/<sessionId>/interactions/<requestId>/auto \
    -d '{"respondedBy":"<uuid-оператора>","source":"AUTO_POLICY","payload":{"reason":"Manual auto resolve"}}'
  ```
- **Истёк SLA** (фиксируем просрочку, шаг возвращается в очередь):
  ```bash
  curl -X POST \
    -H "Content-Type: application/json" \
    -H "X-Chat-Session-Id: <chat_session_id>" \
    http://localhost:8080/api/flows/<sessionId>/interactions/<requestId>/expire \
    -d '{"respondedBy":"<uuid-оператора>"}'
  ```
- Убедитесь, что в таймлайне есть события `HUMAN_INTERACTION_AUTO_RESOLVED` или `HUMAN_INTERACTION_EXPIRED`, а шаг перешёл в состояние `PENDING`.

## Реагирование на алерты
| Алерт | Действия |
| --- | --- |
| `flow_interaction_open` > N (порог зависит от команды) | Проверить список активных запросов; удостовериться, что операторы доступны; при необходимости включить ротацию/переадресацию. |
| 95-й перцентиль `flow_interaction_wait_duration` > SLA | Найти заявки `status=PENDING` с `dueAt` близким к текущему времени, проинформировать владельцев, увеличить покрытие операторов. |
| `flow_interaction_expired` растёт > 5% | Проверить загрузку операторов, наличие уведомлений (Slack/email), конфигурацию dueAt в шагах. |
| Сбой SSE (heartbeats не поступают) | Переключиться на long-poll (`GET /api/flows/{sessionId}`), проверить логи `FlowEventStreamController`, убедиться в доступности сервера. |

## Разбор инцидентов
1. Зафиксировать `sessionId`, `requestId`, `chat_session_id`, `step_id`.  
2. Собрать артефакты:
   - `GET /api/flows/{sessionId}/interactions` (в т.ч. history блок).  
   - `GET /api/flows/{sessionId}` — события + shared context.  
   - Логи backend: `HUMAN_INTERACTION_*`, `interactionCreated/interactionResolved`.  
   - Метрики Micrometer (prometheus `/actuator/prometheus`, фильтр `flow_interaction_*`).  
3. Проверить scheduler (`FlowInteractionExpiryScheduler`): в логах ожидаем сообщение `Expired N overdue interaction requests`.  
4. В случаях рассинхронизации канала убедиться, что клиенты отправляют заголовок `X-Chat-Session-Id`. Ошибки будут в логах с текстом `Provided chat session does not match interaction channel`.

## Обслуживание и профилактика
- Еженедельно проверять, что cron очистки (`flow_interaction_*` retention 30 дней) выполняется (смотреть job-логи).  
- Раз в спринт просматривать подсказки (`suggested_actions.filtered`) — если AI-рекомендации массово отбрасываются, скорректировать allowlist.  
- Перед релизом новой волны обновлять документацию (`docs/infra.md`, `docs/human-in-loop-scenarios.md`, `docs/processes.md`) и чек-лист тестирования.

## Шаблоны команд
<details>
<summary>Список активных заявок</summary>

```bash
curl http://localhost:8080/api/flows/${SESSION}/interactions | jq '.active'
```
</details>

<details>
<summary>Ответить от имени оператора</summary>

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "X-Chat-Session-Id: ${CHAT_SESSION}" \
  http://localhost:8080/api/flows/${SESSION}/interactions/${REQUEST}/respond \
  -d "{
    \"chatSessionId\": \"${CHAT_SESSION}\",
    \"respondedBy\": \"${OPERATOR}\",
    \"payload\": {
      \"comment\": \"Подтверждено вручную\",
      \"decision\": \"approve\"
    }
  }"
```
</details>

<details>
<summary>Получить историю закрытых заявок</summary>

```bash
curl http://localhost:8080/api/flows/${SESSION}/interactions | jq '.history[] | {requestId, status, response}'
```
</details>

