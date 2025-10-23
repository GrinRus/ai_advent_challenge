# ADR: Доставка статуса мультиагентных флоу через SSE с long-poll fallback

Дата: 2025-10-23  
Статус: Draft

## Контекст

Wave 9 вводит оркестратор мультиагентных флоу. Пользователю в UI `Flow Workspace` и внешним интеграциям нужны:

- непрерывный прогресс (current step, события `step.started/completed`, стоимость, usage);
- поддержка управляемых команд (`pause`, `resume`, `cancel`, `retryStep`) с быстрым откликом;
- совместимость с окружениями, где Server-Sent Events (SSE) недоступны (корпоративные прокси, legacy SDK).

Backlog требует long-poll контроллер с таймаутом 25 с и incremental события для таймлайна. Также необходимо единое хранилище событий (`flow_event`) и версионное состояние (`flow_state.version`), чтобы клиент мог догружать только новые записи.

## Решение

1. **Основной канал — SSE**  
   - Эндпоинт `GET /api/flows/{sessionId}/events/stream` возвращает `text/event-stream`.  
   - По умолчанию таймаут отсутствует; сервер отправляет heartbeats каждые 15 с.  
   - Типы событий: `flow.started`, `step.started`, `step.completed`, `step.failed`, `flow.paused`, `flow.resumed`, `flow.cancelled`, `flow.completed`, `flow.failed`. Payload сериализуется в JSON и включает `eventId`, `flowSessionId`, `stepId`, `stateVersion`, `status`, `usage`, `cost`, `traceId`, `spanId`, `issuedAt`.  
   - При потере соединения клиент повторно подключается с заголовком `Last-Event-ID` → сервер возвращает события после указанного `eventId`.

2. **Обязательный fallback — long-poll**  
   - Эндпоинт `GET /api/flows/{sessionId}` принимает параметры `sinceEventId` и `stateVersion`, держит соединение до 25 с и возвращает:  
     ```json
     {
       "nextSinceEventId": "...",
       "state": {
         "version": 42,
         "status": "RUNNING",
         "currentStepId": "...",
         "sharedContext": { ... },
         "telemetry": {
           "stepsCompleted": 3,
           "stepsFailed": 1,
           "retriesScheduled": 2,
           "totalCostUsd": 0.23,
           "promptTokens": 540,
           "completionTokens": 240,
           "lastUpdated": "2025-10-23T18:27:25Z"
         }
       },
       "events": [ ... ]
     }
     ```  
   - Если за период ожидания нет новых данных, возвращается `204 No Content` (UI повторяет запрос).  
   - Long-poll используется фронтендом при отсутствии SSE (feature detection) и внешними интеграциями.

3. **Единый бэкендовый слой**  
   - `FlowEventStreamService` читает из `flow_event` и формирует единый DTO.  
   - SSE и long-poll контроллеры используют одну и ту же выборку (SSE — курсор в БД, long-poll — запрос batched событий).  
   - Механизм back-pressure: максимум 100 событий в одном ответе; при большем количестве сервер завершает ответ и клиент делает повторный запрос.

4. **Обработка таймаутов и ошибок**  
   - SSE: при таймауте (например, клиент не читает) соединение закрывается, отправляется финальное `event: error` с описанием.  
   - Long-poll: при ошибке возвращаем HTTP-статус и JSON {"message": "..."}; клиент повторяет запрос с экспоненциальной задержкой.  
   - Для `409 Conflict` (устаревшая `stateVersion`) клиент обязан запросить полный snapshot (`sinceEventId=null`).

5. **Наблюдаемость**  
   - Метрики Micrometer: `flow.stream.connections`, `flow.stream.reconnects`, `flow.events.delivered`, `flow.longpoll.timeouts`.  
   - Логи содержат `flowSessionId`, `eventId`, `connectionType` (`sse|longpoll`), `usageSource`.

## Альтернативы

| Вариант | Плюсы | Минусы |
| --- | --- | --- |
| **WebSocket** | Двунаправленная связь, минимальный latency | требует stateful backend/шардирования, усложняет балансировку и security review; не нужен двунаправленный канал |
| **Kafka/Webhook** | Надёжная доставка, можно подписывать внешние сервисы | требует отдельной инфраструктуры, сложнее для браузера, нет мгновенного UX |
| **Только long-poll** | Простая реализация, один протокол | увеличенный latency, лишняя нагрузка на БД/HTTP, хуже UX |

## Последствия

- Клиентский код должен уметь переключаться между SSE и long-poll (feature detection по `EventSource`).  
- Необходимо тестовое покрытие:  
  - unit для разбиения событий и heartbeats;  
  - интеграционные тесты с MockMvc/WebTestClient на SSE и long-poll таймауты;  
  - e2e сценарии Playwright с имитацией потери соединения.  
- В инфраструктуре нужно открыть `text/event-stream` на Nginx и настроить timeouts (`proxy_read_timeout` ≥ 60 с).  
- Документация (docs/infra.md, docs/processes.md) уже содержит описание API и требований к логам/метрикам.
