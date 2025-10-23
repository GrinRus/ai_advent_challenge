# ADR: Архитектура мультиагентной оркестрации на Spring + Postgres job queue

Дата: 2025-10-24  
Статус: Draft

## Контекст

Wave 9 требует платформу для запуска мультиагентных флоу, где каждый шаг обращается к LLM через Spring AI `ChatClient`, использует `ChatMemory` и может останавливаться/возобновляться по командам пользователя. Ключевые требования из backlog и docs/infra:

- параллельные/последовательные цепочки шагов с сохранением состояния (`flow_session`, `flow_step_execution`, `flow_event`, `flow_memory_version`);
- возможность горизонтального масштабирования воркеров без привязки к конкретному JVM-инстансу;
- прозрачный контроль ретраев, паузы, отмены и ручных команд (`FlowControlService`);
- минимальная инфраструктура (reuse Postgres + Spring Stack) и совместимость с Spring AI (advisors, chat memory, tool bindings).

Мы рассмотрели три подхода:

1. **Синхронный orchestrator внутри HTTP-запроса.** Каждый шаг выполняется сразу, пока запрос не завершится.
2. **Внешний workflow engine (Temporal / Camunda / Airflow).** Оркестрация делегируется сторонней платформе.
3. **Встроенный job queue поверх Postgres** с `AgentOrchestratorService` + Spring `@Scheduled` worker (текущее решение).

## Решение

Мы выбрали **вариант 3** — Postgres job queue с собственным сервисом оркестрации.

### Основные элементы

- `AgentOrchestratorService`
  - читает опубликованные `FlowDefinition`, парсит их в `FlowDefinitionDocument` и создаёт `FlowStepExecution` для каждого шага;
  - формирует `FlowJobPayload` и ставит задачи в очередь через `JobQueuePort` (дефолт — `PostgresJobQueueAdapter`);
  - использует Spring AI `ChatClient`/`ChatProviderService` для вызова LLM и `ChatMemory` для shared/isolated каналов.

- `flow_job` таблица в Postgres
  - поля: `payload_jsonb`, `status`, `retry_count`, `scheduled_at`, `locked_at`, `locked_by`, FK на `flow_session` и `flow_step_execution`;
  - доступ реализован через `FOR UPDATE SKIP LOCKED`, что позволяет нескольким воркерам безопасно брать задания.

- `FlowJobWorker`
  - Spring-компонент с `@Scheduled(fixedDelayString = "${app.flow.worker.poll-delay:PT0.5S}")`;
  - использует настраиваемый `ExecutorService` (по умолчанию fixed thread pool) и вызывает `AgentOrchestratorService.processNextJob(workerId)`;
  - конфигурируется параметрами `app.flow.worker.enabled`, `poll-delay`, `max-concurrency`, `worker-id-prefix`;
  - пробрасывает Micrometer метрики `flow.job.poll.count` и `flow.job.poll.duration` (тег `result = processed|empty|error`) и логирует исход событий.

- Управляющие API: `POST /api/flows/{flowId}/start`, `POST /api/flows/{sessionId}/control`, `GET /api/flows/{sessionId}` (long‑poll), `GET /api/flows/{sessionId}/events/stream` (SSE) опираются на очередь и state machine `FlowSessionStatus`.

### Почему это работает

- 100% reuse Spring/AOT-совместимых компонентов: `@EnableScheduling`, Spring Data JPA, Spring AI advisors. Не требуется дополнительный брокер/кластер.
- Масштабирование = добавление инстансов backend-приложения. Они будут конкурировать за `flow_job` через `FOR UPDATE SKIP LOCKED`.
- Простая доставка: только Postgres + Spring; DevOps не нужно поддерживать отдельный workflow engine.
- Queue позволяет реализовать паузу/отмену/ретраи (через `FlowControlService` и `retry_count`) без блокировки HTTP-потока.

## Альтернативы и почему мы их отклонили

| Альтернатива | Плюсы | Минусы / Почему отказались |
| --- | --- | --- |
| **Синхронный orchestrator в запросе** | Минимум кода, нет фоновых воркеров | Нельзя ждать LLM/человека в одном запросе; нет `pause/resume`; при сбое HTTP теряем контекст; блокирует thread pool |
| **Temporal / Camunda / Airflow** | Полноценные workflow DSL, visualization, retries из коробки | Требует отдельной инфраструктуры, DevOps/лицензионных затрат, сложной интеграции с Spring AI и нашей моделью памяти, увеличивает time-to-delivery Wave 9 |
| **Postgres + LISTEN/NOTIFY** | Push без поллинга | Усложняет транзакции (нельзя легко ретраить), сложнее контролировать concurrency, требует дополнительного worker-слоя всё равно |

## Последствия

### Плюсы
- Полный контроль над state machine и хранением контекста (`flow_event`, `flow_step_execution`, `flow_memory_version`).
- Возможность адаптировать retry-стратегию (экспоненциальная задержка, ручные команды) на уровне `AgentOrchestratorService`.
- Простая интеграция с `FlowControlService` и human-in-the-loop сценариями (в будущем можно добавлять `WAITING_USER_INPUT`).
- Метрики и трассировки доступны через Micrometer/OTel; легко строить алерты (worker idle, errors).

### Минусы / Риски
- Job queue держится на Postgres → нужно следить за ростом таблицы (retention/cleanup) и вакуумом.
- При большом количестве параллельных воркеров возможна конкуренция за connection pool — требуется настройка `maxPoolSize`.
- Нужен мониторинг планировщика (`@EnableScheduling`): при ошибке воркер может перестать забирать задания — необходимо алертить по `result=error` и `processed=0`.
- Повышенные требования к интеграционным тестам (Testcontainers Postgres + фоновые воркеры).

### Митигирующие меры
- Конфигурация `app.flow.worker.*` позволяет выключить воркер, настроить задержки и concurrency.
- Запланирована очистка `flow_job`/`flow_event` (batch jobs) и алерты на размеры таблиц.
- Unit-test (`FlowJobWorkerTest`) и интеграционный smoke покрывают сценарии `processed|empty|error`.
- Документация (docs/infra.md, docs/processes.md) фиксирует архитектуру, параметры, обязанности по поддержке.

## next steps
- Реализовать human-in-the-loop слой (`flow_interaction_*`) поверх той же очереди.
- Подготовить runbook для поддержки (мониторинг, ручной retry, интервалы очистки).
- Рассмотреть экспоненциальный backoff в `FlowJobWorker` при пустой очереди/ошибках (улучшение backlog).
