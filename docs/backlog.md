# Backlog

Цель проекта — AI Advent Challenge: активное развитие собственного AI-проекта.

Этот документ хранит список задач, которые предстоит реализовать в проекте.

## Формат
- `#` — крупные эпики.
- `##` — пользовательские истории или большие блоки работ.
- `- [ ]` — конкретные задачи, которые можно брать в работу.

## Wave 0 — базовая настройка проекта
### Backend (Java + Spring)
- [x] Код backend-проекта располагается в директории `backend/`.
- [x] Создать базовый Spring Boot сервис с REST контроллером-заглушкой.
- [x] Настроить конфигурацию через `application.yaml` + профили `local`/`prod`.
- [x] Настроить Gradle-проект с плагинами для проверок (`spotless`/`checkstyle` или `spotbugs`).
- [x] Реализовать базовые принципы разработки:
  - Использовать Clean Architecture: разделить слои controller/service/domain/persistence.
  - Включить здравый логгинг (`Slf4j`) и централизованный обработчик ошибок.
  - Писать модульные тесты с `JUnit 5` + `MockMvc`.
  - Для интеграционных тестов применять Testcontainers.
  - Описывать API в `OpenAPI/Swagger`.
- [x] Подготовить Dockerfile (OpenJDK 21 + слоистая сборка Spring).
- [x] Настроить миграции через Liquibase, интегрировать их в процесс сборки и деплоя.
- [x] Реализовать эндпоинт `/api/help`, который возвращает заглушку с текстовой подсказкой.
- [x] Зафиксировать, что все backend-эндпоинты публикуются с префиксом `/api` при доступе извне.

### Frontend
- [x] Код frontend-проекта располагается в директории `frontend/`.
- [x] Выбрать стек (варианты: `React + Vite`, `Vue + Vite`, `SvelteKit`); приоритизировать тот, что проще команде.
- [x] Сконфигурировать клиент для работы с REST API backend (axios/fetch с базовым клиентом).
- [x] Настроить компонентовую структуру (страница-заглушка + состояние подключения к backend).
- [x] Реализовать интеграцию: настроить окружение и эндпоинты для общения frontend ↔ backend, предусмотреть обработку ошибок и конфигурацию URL.
- [x] Добавить Dockerfile для frontend (node builder + static Nginx/serve слой).
- [x] Создать раздел `Help`, который обращается к `/api/help` и отображает результат; оставить заглушку на корневой странице.
- [x] Зафиксировать, что frontend работает на порту `4179` (в Docker и локально).

### Инфраструктура
- [x] Создать `docker-compose.yml`, который поднимает frontend и backend с нужными сетевыми алиасами.
- [x] Добавить сервис базы данных `postgres` c образом `pgvector/pgvector:pg15`, настроить volume для данных и переменные окружения.
- [x] Настроить `.env`/секреты для локальной разработки и деплоя.
- [x] Добавить GitHub Actions workflow: сборка backend, фронтенд, прогон тестов, билд контейнеров, деплой на VPS (например, через SSH + Docker Compose).
- [x] Документировать процесс запуска и деплоя в `README.md` или `docs/infra.md`.
- [x] Зафиксировать порты сервисов: backend — `8080`, frontend — `4179`; описать их в документации и `docker-compose.yml`.

## Wave 1 — интерактивный LLM-чат
### Backend
- [x] Подключить библиотеку Spring AI и настроить клиента для OpenAI-compatible провайдера z.ai (модель `glm-4.6`, базовый URL, ключ, параметры запросов).
- [x] Вынести настройки LLM (ключи, base URL, имя модели, опции стриминга) в `application.yaml` с возможностью переопределения через переменные окружения из `.env` и `docker-compose.yml`.
- [x] Добавить сервис домена чата, который проксирует запросы в LLM, обрабатывает стриминговые ответы (интерактивная передача токенов) и сохраняет историю диалогов в хранилище backend.
- [x] Реализовать SSE-эндпоинт на `/api/llm/chat/stream`, который принимает историю диалога и настройки запроса, стримит ответы LLM без дополнительной пост-обработки и не требует дополнительной авторизации.
- [x] Обновить OpenAPI/Swagger-описание и документацию (`docs/infra.md`/`README.md`) с инструкциями по запуску чата и используемым переменным окружения.

### Frontend
- [x] Добавить новую вкладку `LLM Chat` с формой отправки сообщений, списком истории диалога и подсветкой сообщений пользователя/модели.
- [x] Настроить подключение к SSE-эндпоинту backend, обрабатывать потоковые события, показывать токены по мере поступления и обрабатывать ошибки/разрывы соединения.
- [x] Обеспечить интерактивный ввод-вывод: отображать состояние запроса (loading/streaming), поддержать отмену запроса и повторную отправку без перезагрузки страницы.
- [x] Вынести адреса SSE и ключевые настройки фронтенда в конфигурацию окружения, синхронизировать переменные с `.env` и документацией.

### Тестирование и контроль качества
- [x] Добавить smoke-тесты/интеграционные проверки, покрывающие SSE-ручку и сохранение истории (например, MockMvc с Spring AI stub или контрактный тест).
- [x] Настроить минимальный e2e-сценарий или storybook/demo, чтобы проверить потоковый ответ от backend.
- [x] Обновить CI/CD сценарии при необходимости (секреты, прогон тестов).

#### Wave 1 gaps
- Покрытие SSE закрыто smoke и HTTP e2e тестами; дальнейшие улучшения можно выполнять по мере появления новых пользовательских сценариев.

**Решения и допущения**
- Провайдер LLM — z.ai, модель `glm-4.6`; настраиваем через совместимый OpenAI-клиент.
- SSE-эндпоинт открыт без дополнительной авторизации, опирается на существующую инфраструктуру защиты.
- История диалогов хранится на backend и доступна для восстановления контекста при следующих запросах.

## Wave 1.5 — Spring AI memory integration
### Цели
- [x] Перейти от самописного формирования истории в контроллере к стандартным `ChatMemory`-компонентам Spring AI, чтобы упростить поддержку и включить готовые политики хранения.
- [x] Сократить объём промпта при длинных сессиях, сохранив полное хранение истории в БД для аудита и отладки.

### Backend
- [x] Подключить `MessageWindowChatMemory` (или аналогичную реализацию) с конфигурируемым размером окна и зарегистрировать `MessageChatMemoryAdvisor` на уровне `ChatClient`.
- [x] Настроить `ChatMemoryRepository`, повторно используя таблицы `chat_session`/`chat_message` либо переведя хранение на `JdbcChatMemoryRepository`, и обеспечить миграцию текущих данных.
- [x] Обновить `ChatStreamController`, чтобы история подтягивалась через `ChatMemory.CONVERSATION_ID`, а не вручную через сервис.
- [x] Переработать `ChatService` (или вынести отдельный компонент), разделив ответственность: полная история в БД и управление окном памяти для LLM.

### Конфигурация и инструментирование
- [x] Вынести параметры памяти (размер окна, тип репозитория, таймаут очистки) в `application.yaml` с возможностью переопределения через переменные окружения.
- [x] Добавить диагностическое логирование/метрики вокруг `ChatMemory` (размер окна, количество сбросов), чтобы контролировать поведение в проде.

### Тестирование и контроль качества
- [x] Дополнить модульные тесты кейсами с длинными диалогами и убедиться, что в промпт попадает только окно сообщений, а полная история сохраняется в БД.
- [x] Обновить e2e-тесты стриминга: повторные запросы с одним `conversationId` должны поднимать память из Spring AI, а не из ручного списка.

### Документация
- [x] Обновить `docs/processes.md` и `docs/infra.md` с описанием новой схемы памяти, параметров конфигурации и сценариев эксплуатации (очистка, миграции, диагностика).

## Wave 2 — расширенная документация
- [x] Спроектировать структуру каталога `docs/`:
  - `docs/overview.md` — цели проекта, ключевые понятия, дорожная карта.
  - `docs/architecture/` — отдельные файлы для backend (`backend.md`), frontend (`frontend.md`), интеграции с LLM (`llm.md`) и общих диаграмм (`diagrams/` с исходниками PlantUML/Excalidraw).
  - `docs/processes.md` — принципы разработки, тестирование, правила ведения документации (без инструкции по запуску).
  - `docs/faq.md` — типовые вопросы и сценарии использования.
- [x] Обновить существующие материалы (`docs/infra.md`, `README.md`) под новую структуру и связать перекрёстными ссылками.
- [x] Зафиксировать требования к обновлению документации при изменениях (чек-лист в `docs/CONTRIBUTING.md` или отдельный раздел).

## Wave 3 — мультипровайдерный чат и выбор моделей
### Цели
- [x] Обеспечить возможность переключения между провайдерами z.ai и open.ai без изменения прикладного кода.
- [x] Дать пользователю выбор набора поддерживаемых моделей от «эконом» до «топовых» внутри каждого провайдера.
- [x] Стандартизировать создание и использование сущности `ChatClient` через выделенный сервис, уменьшая дублирование и связанные зависимости.

### Backend
- [x] Вынести текущую логику построения клиента LLM в сервис-фабрику (`ChatClientFactory` или `ChatProviderService`), инкапсулируя настройки HTTP, стриминга и токенов.
- [x] Отключить автоконфигурацию прототипного `ChatClient.Builder` (`spring.ai.chat.client.enabled=false`) и зарегистрировать именованные `ChatClient`-бины для каждого провайдера через фабрику.
- [x] Добавить слой абстракции `ProviderStrategy`/`ProviderAdapter`, реализующий унифицированный интерфейс для z.ai и open.ai, включая различия в API и параметрах моделей.
- [x] Расширить доменную модель чата: хранить в истории диалогов выбранный провайдер и модель, обновить миграции Liquibase.
- [x] Обновить сервисы и контроллеры, чтобы при запросе можно было явно передать `provider` и `model`, с дефолтами на уровне конфигурации.
- [x] Поддержать передачу `ChatOptions`/`ZhiPuAiChatOptions` в `Prompt` на каждый вызов, чтобы UI мог переопределять модель, температуру и лимиты токенов.
- [x] Реализовать гибридный фабричный метод, использующий `OpenAiApi#mutate` и `ZhiPuAiChatOptions` для сборки клиентов под разные базовые URL/ключи и fallback-сценарии.
- [x] Покрыть новую фабрику и стратегии модульными тестами; добавить интеграционный тест, проверяющий переключение провайдера (использовать стаб-реализации для каждого).

### Конфигурация и инфраструктура
- [x] Описать в `application.yaml` и профилях структуру настроек провайдеров: base URL, ключ, таймауты, лимиты токенов, список доступных моделей (id, отображаемое имя, оценочная стоимость).
- [x] Синхронизировать переменные окружения в `.env.example` и `docker-compose.yml`, обеспечить независимое управление ключами для обоих провайдеров.
- [x] Задокументировать поддерживаемые свойства `spring.ai.openai.*` и `spring.ai.zhipuai.*`, включая runtime-перекрытия `chat.options.*`, и добавить рекомендации по выбору моделей (`gpt-4o`, `gpt-4o-mini`, `GLM-4.6`, `GLM-4.5`, `GLM-4.5 Air` и др.).
- [x] Обновить документацию (`docs/infra.md`, `README.md`) с таблицей моделей: класс (budget/standard/pro), ориентировочная стоимость и рекомендуемые сценарии.
- [x] Зафиксировать требования по актуализации документации при добавлении новых провайдеров/моделей (инструкции в `docs/processes.md` или ADR).

### Frontend
- [x] Добавить UI-компонент для выбора провайдера и модели перед отправкой запроса, автоподстройка доступных моделей в зависимости от выбранного провайдера.
- [x] Отображать в интерфейсе текущий провайдер и модель в истории сообщений для упрощения отладки и поддержки.

### Тестирование и контроль качества
- [x] Расширить e2e-сценарии чата: переключение провайдера и модели, проверка сохранения истории и корректности ответа.
- [x] Настроить smoke-тесты в CI, которые выполняют запросы к обоим провайдерам с использованием моков или контрактов.

**Варианты подхода**
1. **Стратегия + фабрика (рекомендуется)** — единая `ChatClientFactory`, регистрирующая стратегии провайдеров через Spring (`@Component`/`@ConfigurationProperties`). Простой в сопровождении, позволяет быстро добавлять новые провайдеры, дружит с профилями и конфигурациями, повторно использует `ChatClient.builder(model)` и runtime-опции (`chatClient.prompt().options(...)`) для подстановки модели.
2. **Каталог провайдеров как конфиг** — хранить описания провайдеров и моделей в конфигурации или БД, динамически поднимать адаптеры на старте. Подходит, если планируется расширение списка провайдеров и нужен UI для управления.
3. **Унифицированный OpenAI-слой** — использовать один OpenAI-compatible клиент Spring AI с кастомизацией через `ClientCustomizer`. Минимальные изменения кода, но сложнее учесть несовместимости (стриминг, лимиты, специфичные параметры) и отдельные свойства `spring.ai.zhipuai.chat.options.*`.

## Wave 4 — Structured Sync Response
### Backend
- [x] Зафиксировать отдельный стек синхронных DTO: `ChatSyncRequest`, `StructuredSyncResponse`, вложенные `StructuredSyncAnswer`/`StructuredSyncItem`/`UsageStats`, `StructuredSyncStatus`, и задокументировать JSON пример в `docs/infra.md`.
- [x] Добавить `BeanOutputConverter<StructuredSyncResponse>` в конфигурацию (при необходимости `ParameterizedTypeReference` для коллекций) и описать, как получаем JSON Schema/format для промпта.
- [x] Реализовать новый `ChatSyncController` с POST `/api/llm/chat/sync`: валидация входа, регистрация `registerUserMessage`, вызов синхронного сервиса, возврат `StructuredSyncResponse`.
- [x] Создать `StructuredSyncService`, который выбирает провайдера/модель, собирает `ChatOptions`, вызывает `chatProviderService.chatClient(...).prompt()...call().entity(StructuredSyncResponse.class)` с `BeanOutputConverter`, прокидывает conversation/system контекст и регистрирует ответ ассистента.
- [x] Сохранить текущие стриминговые эндпоинты без изменений: никаких требований structured output для `/api/llm/chat/stream` и связанных опций.
- [x] Расширить адаптеры провайдеров для sync-режима: OpenAI — `responseFormat(JSON_SCHEMA)` + `strict=true`, ZhiPu — добавление `beanOutputConverter.getFormat()` в промпт и валидация десериализации.
- [x] Настроить ретраи (3 попытки, экспоненциальный backoff 250→1000 мс) на 429/5xx и ошибки схемы, при окончательном провале отдавать 422.
- [x] Обновить OpenAPI и `docs/infra.md`: новое API, требования к JSON, поведение провайдеров, без телеметрии на первом этапе.

**JSON ответа (черновик)**
```json
{
  "requestId": "4bd0f474-78e8-4ffd-a990-3aa54f0704c3",
  "status": "success",
  "provider": {
    "type": "ZHI_PU",
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

### Frontend
- [x] Добавить новую вкладку `Structured` на странице чата, переключающуюся между стриминговым и синхронным режимами.
- [x] Реализовать форму отправки в синхронный эндпоинт с отображением состояния загрузки и ошибок.
- [x] Отрисовать структурированный ответ: summary, список `items` (карточки), блок статистики `usage`, технические метаданные (provider, latency).
- [x] Обновить клиентский слой API, добавить типы/интерфейсы под новую структуру ответа.
- [x] Написать e2e сценарий (Playwright / Cypress) на создание structured-запроса и проверку UI.

### Документация
- [x] Обновить `README.md` и `docs/infra.md` с описанием нового режима, ссылками на Spring AI `BeanOutputConverter`/`ChatClient` и примерами конфигурации `spring.ai.openai.chat.options.response-format`.
- [x] Добавить в `docs/processes.md` рекомендации по тестированию/наблюдаемости: проверка JSON Schema, fallback при провале автоконвертера, метрики latency и token usage.
- [x] Подготовить follow-up (persist structured answers, retries для schema violations) и зафиксировать отдельными задачами в backlog.

## Wave 4.1 — Structured Sync Follow-up
### Backend
- [x] Liquibase: добавить колонку `structured_payload JSONB` в `chat_message`, создать индекс по `session_id, created_at`, обновить `db.changelog-master.yaml`.
- [x] Persistence слой: расширить сущности/DTO/mapper для работы с новой колонкой, обеспечить безопасную сериализацию/десериализацию (fail-safe при несовпадении схемы).
- [x] StructuredSyncService: сохранять structured payload вместе с текстовым сообщением, логировать версию схемы и статус десериализации.
- [x] Retry конфигурация: расширить `ChatProvidersProperties` секцией `retry` (attempts, initialDelayMs, multiplier, retryableStatuses), генерировать `RetryTemplate` на основании настроек и покрыть оба провайдера.
- [x] Наблюдаемость и ошибки: добавить структурированное логирование количества попыток, итоговой причины остановки и метрик по retry.
- [x] Тесты: unit для сериализации и retry, интеграционные для сохранения payload (включая 422/429/5xx сценарии и ограничение по макс. попыткам).

### Frontend
- [x] API клиента: подтягивать structured payload в истории, предусмотреть backwards compatibility с пустыми значениями.
- [x] UI: отображать карточки structured ответа рядом с текстом, добавить fallback, если payload невалиден.
- [x] Пользовательские тесты: e2e сценарий загрузки истории structured сессии и визуальной регрессии карточек.

### Документация
- [x] Обновить `docs/infra.md`: описать новую колонку, пример блока `retry`, требования к логам.
- [x] Обновить `docs/processes.md`: чек-лист к тестированию сериализации, ретраев и визуального слоя.

## Wave 5 — Клиентские параметры LLM из фронтенда
### Backend
- [x] Усилить тесты `ChatStreamController`/`StructuredSyncService`: зафиксировать, что при передаче `options.temperature/topP/maxTokens` они доходят до `ChatProviderAdapter`, а при отсутствии значений используются дефолты из `ChatProvidersProperties`.
- [x] Расширить OpenAPI/REST документацию (`application.yaml`/`docs/infra.md` ссылкой) описанием новых опциональных полей `options.*` в теле запроса, привести примеры payload с и без overrides.

### Frontend
- [x] Добавить в UI управления (sliders/inputs) для `temperature`, `topP`, `maxTokens` в обоих режимах (`stream` и `structured`), инициализировать их значениями из каталога провайдера; предусмотреть кнопку сброса к дефолтам.
- [x] Централизовать сборку payload: если пользователь оставил поля пустыми или сбросил значения, не отправлять ключи в `options`, чтобы backend продолжал применять конфиг по умолчанию.
- [x] Обновить `LLMChat` стейт, чтобы смена провайдера/модели перезаполняла опции актуальными дефолтами, при этом не прерывая текущую сессию; отображать активные параметры рядом с сообщением ассистента.

### Тестирование
- [x] Добавить unit-тесты на сборщик payload (оба режима) — проверка передачи изменённых значений и fallback при `undefined`.
- [x] E2E (Playwright): сценарий с изменением параметров, проверкой фактической отправки/отображения и обратного перехода к дефолтам без ошибок.

### Документация
- [x] Дополнить `docs/architecture/frontend.md` и `README.md` разделом о пользовательских настройках запроса, указать диапазоны допустимых значений и поведение по умолчанию.

## Wave 6 — Расширение пула моделей ChatGPT
### Аналитика
- [x] Зафиксировать актуальную матрицу моделей OpenAI (июль 2024 — октябрь 2025): `gpt-5-nano` (~$0.05 / $0.40 за 1M input/output токенов — суперэконом), `gpt-4o-mini` (~$0.15 / $0.60 — дешёвая и быстрая), `gpt-5` (~$1.25 / $10.00 — флагман); описать сильные стороны, ограничения и доступные режимы (стриминг, мультимодальность).
- [x] Расширить обзор альтернативных провайдеров: добавить GLM-линейку (`glm-4-32b-0414-128k` — контекст 128K, flat-pricing ~$0.10 / $0.10 за 1M токенов), сравнить с текущим `glm-4.6`, зафиксировать источники цен и ограничения по API.
- [x] Обновить раздел выбора моделей в `docs/infra.md` и wiki, добавив рекомендации по сегментации (суперэконом, value, флагман, альтернативные GLM) и триггеры для переключения между ними.

### Backend
- [x] Расширить `ChatProvidersProperties` и каталог моделей OpenAI/GLM: добавить `gpt-5-nano`, `gpt-4o-mini`, `gpt-5`, а также `glm-4-32b-0414-128k` с дефолтными параметрами и поддержкой overrides (цены за 1K токенов $0.0001 input/output, контекст 128K).
- [x] Обновить `ChatProviderRegistry`/`ChatProviderService`, чтобы новые модели корректно отображались в списке, имели fallback на прежние значения и валидацию совместимости со streaming/structured режимами (для GLM предусмотреть только sync).
- [x] Добавить unit и интеграционные тесты, которые проверяют выбор каждой новой модели и happy-path запросы, а также блокируют неподдерживаемые режимы и превышение лимитов токенов; обновить тестовые стабы (OpenAI, GLM) с разной стоимостью.
- [x] Добавить миграцию Liquibase и расширить `ChatMessage`: сохранить `prompt_tokens/completion_tokens/total_tokens`, рассчитанную стоимость (input/output) и валюту; обновить JPA-энтити, репозиторий и `ChatService`, использовать цены за 1K токенов из `ChatProvidersProperties.Model.pricing`.
- [x] Обновить `StructuredSyncService` и стриминговый пайплайн (`ChatStreamController`/`ChatProviderService`): извлекать `ChatResponseMetadata.usage`, рассчитывать стоимость одного сообщения, сохранять её вместе с историей; для SSE добавить расширенный payload финального события (usage + cost) и fallback, если провайдер метаданные не прислал.
- [x] Реализовать `SessionUsageService` и REST-эндпоинт (`GET /api/llm/sessions/{id}/usage`), формирующий агрегаты по диалогу (разбивка по сообщениям, суммарные токены/стоимость), отдающий валюту и источники цены; покрыть сервис unit-/integration-тестами и обновить `StubChatClientState` для передачи usage в тестах.

### Frontend
- [x] Расширить UI выбора модели: сгруппировать модели по сегментам (`Economy: gpt-5-nano`, `Value: gpt-4o-mini`, `Flagship: gpt-5`, `Alt: glm-4-32b-0414-128k`), вывести подсказки по стоимости, контексту и ограничениям (стриминг vs только sync).
- [x] Обновить клиентский слой: гарантировать, что `options` формируются с учётом специфики `gpt-5-nano` (урезанные лимиты), `gpt-4o-mini` (большой контекст) и `glm-4-32b-0414-128k` (flat pricing, 128K), и что дефолты подхватываются из конфигурации.
- [x] Расширить состояние `LLMChat`: обрабатывать usage/cost из SSE и sync-ответов, хранить агрегаты по текущей сессии, отображать подсказку по тарифу из каталога моделей.
- [x] Добавить UI-блок в карточке диалога (header/side summary) с количеством токенов и стоимостью для текущего диалога и последнего сообщения; предусмотреть форматирование валюты, подсветку перерасхода, обновить e2e/визуальные тесты.

### Документация и эксплуатация
- [x] Добавить в `docs/processes.md` рекомендации по контролю затрат (алерты в OpenAI/GLM usage, квоты по сегментам, лимиты токенов для дорогих моделей).
- [x] Подготовить CHANGELOG/релизную заметку Wave 6 с сравнением сегментов и планом постепенного включения `gpt-5`/`glm-4` с fallback на более дешёвые варианты.
- [x] Обновить `docs/infra.md` и `docs/processes.md`: формула расчёта стоимости (Input/Output per 1K → фактический счёт), нюансы округления, требования к конфигурации `pricing` для OpenAI и GLM, гайды по мониторингу и алертам.

## Wave 7 — Разделение sync и structured API
### Backend
- [x] Ввести отдельный сервис синхронных текстовых ответов (`SyncChatService`): использовать `ChatProviderService` и `ChatService` для plain-вызова без `BeanOutputConverter`, сформировать новый DTO `ChatSyncResponse` (текст, провайдер, задержка, usage/cost), сохранять ассистентские сообщения без `structuredPayload`.
- [x] Переписать `ChatSyncController`: эндпоинт `POST /api/llm/chat/sync` должен возвращать `ChatSyncResponse`, выставлять заголовки `X-Session-Id`/`X-New-Session`, проверять поддержку синхронного режима через новый флаг `syncEnabled` в `ChatProvidersProperties.Model` и его обработку в `ChatProviderRegistry`.
- [x] Вынести структурированный поток в отдельный `StructuredSyncController` с маршрутом `POST /api/llm/chat/sync/structured`, переиспользовать `StructuredSyncService`, добавить при необходимости временный alias для старого пути и явно пометить его как устаревший.
- [x] Вынести общую логику подготовки `Prompt`/`ChatOptions`, регистрации сообщений, подсчёта `UsageCostEstimate` и конфигурации retry в переиспользуемый компонент для обоих sync-сервисов, чтобы устранить дублирование и расхождения в поведении.
- [x] Обновить конфигурацию провайдеров: добавить свойство `sync-enabled` в YAML, актуализировать `structured-enabled` для моделей (например, `glm-4-32b-0414-128k` — только sync), синхронизировать значения в `application.yaml`, `.env.example` и документации.

### Тестирование
- [x] Разделить интеграционные сценарии: переписать `ChatSyncControllerIntegrationTest` под plain sync (сохранение текста, подсчёт usage/cost, retry на 429/5xx) и вынести существующие проверки JSON-схемы в новый `StructuredSyncControllerIntegrationTest`.
- [x] Добавить unit-тесты для `SyncChatService` (успех, пустой ответ, обработка исключений) и обновить `StructuredSyncServiceTest` после выделения общей логики.
- [x] Расширить тестовый стенд (`StubChatClientState`, `StubChatClientConfiguration`): научить отличать plain/structured ответы, задавать usage для sync-вызова, проверять корректный выбор модели и флагов `syncEnabled`/`structuredEnabled`.

### Frontend
- [x] Обновить `frontend/src/lib/apiClient.ts`: вынести `CHAT_STRUCTURED_SYNC_URL`, добавить `requestSync()` с типом `ChatSyncResponse`, переключить `requestStructuredSync()` на новый путь и скорректировать типы ответов.
- [x] Переработать страницу `LLMChat`: расширить табы до `'stream' | 'sync' | 'structured'`, реализовать UI для мгновенного текстового ответа (контент, latency, usage/cost, повторное использование `sessionId`), скрывать табы по флагам `syncEnabled`/`structuredEnabled`.
- [x] Обновить хранение и отображение сообщений: разделить plain/structured ответы, исключить попадание `structured` payload в обычные сообщения, добавить сообщения об ошибках для sync (429, 502, timeout) и синхронизировать e2e/визуальные тесты.

### Документация
- [x] Обновить `README.md` и `docs/infra.md`: описать различия стримингового, plain sync и structured режимов, новые маршруты API (`/sync` vs `/sync/structured`), примеры запросов/ответов и матрицу поддерживаемых режимов по моделям.
- [x] Актуализировать `docs/processes.md`: дополнить чек-листы тестирования (валидация JSON-схемы, деградация до plain sync), добавить инструкции по включению/отключению режимов через конфигурацию.
- [x] Обновить описание API в OpenAPI/Swagger (теги, `@Operation`), задокументировать устаревание старого пути и убедиться, что Swagger UI отображает оба эндпоинта отдельно.

## Wave 8 — Usage в стриминге и fallback токенайзер
### Аналитика
- [x] Зафиксировать поддержку `stream_options.include_usage` по провайдерам (OpenAI, Azure OpenAI, zhipu.ai) и документировать ограничения (финальный чанк, отсутствие метрик и т.д.).
- [x] Подготовить техническое решение по fallback-токенизации на базе `jtokkit`: источники словарей, политика обновлений, лицензирование.
- [x] Определить требования к Redis-кешу для токенизации (TTL, размер, eviction-политика), задокументировать вариант отказа от кеша и мониторинг нагрузки.

### Backend
- [x] OpenAI: гарантировать передачу `stream-usage` (конфиги + `OpenAiChatOptions.streamUsage(true)`), адаптировать пайплайн на финальный usage-чанк, добавить интеграционный тест.
- [x] Реализовать `TokenUsageEstimator` с использованием `jtokkit`: модуль расчёта токенов + конфигурируемый Redis-кеш (TTL, отключение, мониторинг).
- [x] Добавить конфигурацию провайдеров для fallback (переключатель native/fallback, выбор модели токенайзера).
- [x] Интегрировать fallback в стриминговый конвейер (ZhiPu и другие провайдеры без usage), логировать источник значения (native vs. fallback), пробрасывать информацию до API.
- [x] Добавить контрактные/юнит-тесты для оценки токенов (эталонные промпты, сравнение с native usage), покрыть сценарии кеширования Redis.

### Frontend
- [x] Обновить UI стримингового чата: добавить лейбл `Usage: native|fallback` и отображение расчётных значений/стоимости, учесть отсутствие данных.

### Инфраструктура и документация
- [x] Добавить Redis в `docker-compose.yml`, настроить окружение (`.env`, `application.yaml`), описать запуск/локальные переменные.
- [x] Обновить `docs/infra.md` и `docs/processes.md`: описать `jtokkit`, Redis-кеш, использование `stream-usage`, ограничения по провайдерам и отображение источника usage на фронтенде.
- [x] Подготовить ADR/раздел в `docs/releases` с фиксацией выбора `jtokkit` + Redis и сценариями масштабирования.
- [x] Добавить метрики и алерты для контроля расхождения между native usage и fallback, а также состояния Redis (hit/miss, латентность).

## Wave 9 — Оркестрация мультиагентных флоу
### Архитектура и продукт
- [x] Сформировать детальные пользовательские сценарии для мультиагентных флоу (инициализация из UI и внешнего API, управляемые шаги, остановка/повтор).
  - UI запуск: пользователь выбирает шаблон флоу, настраивает агентов/память, наблюдает прогресс по шагам, может поставить флоу на паузу, остановить или перезапустить с точки останова.
  - API запуск: внешняя система вызывает `POST /api/flows/{flowId}/start` с payload контекста, получает `sessionId`, отслеживает статус через `GET /api/flows/{sessionId}` и может инициировать остановку/повтор через управляемые эндпоинты.
  - Управление шагами: поддержка manual step approval, пропуска шагов, повторного выполнения отдельного агента при ошибках или модификации входного контекста.
  - Остановка/повтор: сценарии graceful cancel, rollback контекста, перезапуск флоу с сохранением истории и возможности сравнения результатов.
- [x] Подготовить high-level архитектуру оркестрации: компоненты Orchestrator Service, каталог агентов, слой памяти, события и API взаимодействия.
  - Orchestrator Service: модуль запуска/координации шагов, очередь задач на основе PostgreSQL job-таблицы (JSONB payload), стратегия обработки ошибок (retry/fallback) и трассировка контекста.
  - Agent Catalog: реестр агентов и моделей в PostgreSQL (идентификаторы, версии, системные промпты, поддерживаемые режимы, лимиты токенов/стоимости) с кэшированием через Redis при необходимости.
  - Memory Layer: shared/isolated каналы поверх PostgreSQL JSONB (версионное хранение состояний, TTL/очистка через batch), адаптер сериализации/десериализации контекста.
  - Event Bus: события `flow.started`, `step.started/completed/failed`, `flow.paused/resumed/stopped` фиксируются в PostgreSQL (`flow_event` JSONB), опциональная публикация в Redis Pub/Sub для расширений.
  - API взаимодействия: REST маршруты для запуска/контроля, polling для статусов поверх агрегатов из PostgreSQL, базовый audit trail.
  - Job Queue: выделенный `JobQueuePort` с дефолтной реализацией `PostgresJobQueueAdapter` (`flow_job`: `id`, `flow_session_id`, `payload_jsonb`, `status`, `retry_count`, `scheduled_at`, `locked_at`, `locked_by`, `created_at`, `updated_at`), использование `FOR UPDATE SKIP LOCKED`, подготовка альтернативного адаптера (например, Redis Streams) для мягкого перехода.
  - Memory Versioning: таблица `flow_memory_version` (`id` BIGSERIAL, `flow_session_id` UUID, `channel` VARCHAR, `version` BIGINT, `data_jsonb` JSONB, `parent_version_id` BIGINT NULL, `created_at` TIMESTAMP, `created_by_step_id` UUID), индекс по (`flow_session_id`, `channel`, `version` DESC), колонка `current_version` в `flow_session`, фоновый batch для очистки старых версий (например, старше 30 дней, оставляя последние 10).
  - Event Log: единая таблица `flow_event` (`id` BIGSERIAL, `flow_session_id` UUID, `step_id` UUID, `event_type` VARCHAR, `status` VARCHAR, `payload_jsonb` JSONB, `cost` NUMERIC, `tokens_prompt` INT, `tokens_completion` INT, `trace_id` VARCHAR, `span_id` VARCHAR, `created_at` TIMESTAMP), покрыта индексами по (`flow_session_id`, `created_at`) и (`step_id`, `created_at`).
  - Polling API: long-poll контроллер с таймаутом 25 с; клиент передаёт `sinceEventId` и `lastKnownStateVersion`, сервер ждёт обновлений либо таймаута, возвращает `flowState` (версия+snapshot) и `events` (список новых `flow_event`), поддержка `nextSinceEventId`.
  - Telemetry & Correlation: обязательно прокидывать `requestId`, `flowId`, `sessionId`, `stepId`, `jobUid`, `memoryVersion`, `trace_id`/`span_id` во все логи, метрики и записи БД; привязать Micrometer/OTel экспорт к тем же идентификаторам.
- [x] Описать формат конфигурации флоу (YAML/JSON): порядок агентов, зависимости шагов, условия ветвления, передаваемый контекст и опции запуска, гарантия синхронного режима вызовов, хранение в БД с возможностью редактирования через UI.
  - Базовая структура: `flowId`, `version`, `title`, `description`, `sync: true`, `memory` (shared/isolated настройки), `triggers` (`ui`, `api`), `defaults.context`.
  - Шаги: массив `steps[]` с полями `id`, `name`, `agentRef`, `systemPrompt`, `inputBindings` (mapping из контекста/предыдущих шагов), `memoryRead`, `memoryWrite`, `overrides` (`model`, `options`), `retryPolicy`, `timeoutSec`.
  - Ветвления: секция `transitions` внутри шага (`onSuccess`, `onFailure`, `conditions[]` с выражениями), поддержка финальных состояний `complete`, `abort`.
  - Запускаемый контекст: `launchParameters` (перечень требуемых входов, типы, валидаторы), `contextTransforms` (правила обогащения shared памяти).
  - Хранение: таблица `flow_definition` в PostgreSQL (`id` UUID, `name`, `version`, `status`, `definition_jsonb`, `is_active`, `created_at`, `updated_at`, `updated_by`, `published_at`), поддержка статусов `draft/published`.
  - UI-редактор: CRUD API (`GET/POST/PUT/PATCH /api/flows/definitions`), сохранение черновиков, предпросмотр JSON, валидация через JSON Schema (draft 2020-12), история изменений.
  - Пример JSON-конфигурации (с шагами `gather_requirements`, `generate_solution`, `review`) и соответствующая YAML-репрезентация для документации.
  - Валидация синхронности: обязательное `syncOnly: true` на уровне агента/шага, запрещаем стриминговые режимы в схеме.

### Backend
- [x] Реализовать `AgentOrchestratorService`, принимающий `FlowDefinition` и стартовый `OrchestrationRequest`, управляющий выполнением шагов и маршрутизацией ответов.
  - Интерфейс `AgentOrchestratorService.start(flowId, launchContext)` возвращает `FlowSession`.
  - Использует `JobQueuePort` для постановки step-джобов, обрабатывает `flow_event` и обновляет `flow_memory_version`.
  - Переиспользует `AbstractSyncService`/`ChatProviderService` для retry, подсчёта токенов, регистрации сообщений; orchestration слой управляет только последовательностью шагов.
  - Интегрирует Spring AI advisors (`CallAdvisorChain`/`StreamAdvisor`) для телеметрии, memory hints и маршрутизации tool-вызовов.
  - Включает state machine: `PENDING → RUNNING → PAUSED/FAILED/COMPLETED/ABORTED`.
  - Поддерживает ручные команды (`pause`, `resume`, `cancel`, `retryStep`) через `FlowControlService`.
- [x] Создать модель `AgentDefinition` с параметрами провайдера (модель, базовые опции, системные промпты, ограничения), поддержкой overrides и версионированием.
  - Таблицы `agent_definition` (метаданные), `agent_version` (версионированные настройки), `agent_capability`.
  - Поля: `providerType`, `model`, `systemPrompt`, `defaultOptions`, `syncOnly`, `maxTokens`, `costProfile`.
  - ToolBinding: перечень Spring AI `@Tool` методов/endpoint’ов и правил вызова, хранится вместе с версией агента.
  - CRUD API для управления агентами + публикация версии, интеграция с кэшом Redis.
- [x] Реализовать Postgres-бэкенд для `ChatMemory` Spring AI (shared/isolated каналы) и адаптировать текущие сервисы сообщений.
  - Интерфейсы `MemoryChannelReader`, `MemoryChannelWriter` → thin wrapper над `ChatMemory`/`ChatMemoryRepository`.
  - Хранение опирается на `flow_memory_version`, поддерживает optimistic locking и чтение предыдущих версий.
- [x] Спроектировать и реализовать persistence для шаблонов флоу и runtime-сессий: таблицы, Liquibase-миграции, репозитории, аудит статусов шагов.
  - Таблицы: `flow_session`, `flow_step_execution`, `flow_memory_version`, `flow_event`, `flow_job`, `flow_definition_history`.
  - Репозитории Spring Data + custom queries (SKIP LOCKED) для очереди.
  - Liquibase changelog с индексами и ограничениями.
- [x] Подготовить расширение `ChatProviderService` для мультиагентных вызовов с переиспользованием Spring AI `ChatClient`.
  - Новый метод `chatSyncWithOverrides(agentDefinition, inputContext, overrides)` поверх `chatClient().prompt()`.
  - Поддержка передачи `memoryRead`/`memoryWrite` через `ChatMemory` advisors, настройка `RetryTemplate` per agent.
  - Расширение `CallAdvisorChain`/`StreamAdvisorChain` дополнительными адаптерами (telemetry, memory hydration, circuit breakers), конфигурируется на уровне агента.
  - Метрики usage/cost (native или fallback) возвращаются вместе с ответом и транслируются в существующие Micrometer метрики.
- [x] Сохранять и отдавать через API полный контекст запроса/ответа каждого шага (prompt, параметры, финальный output, метаданные с usage/токенами и стоимостью) с контролем доступа и ретеншена.
  - REST `GET /api/flows/{sessionId}/steps/{stepId}` → DTO с prompt/output/options/usage/cost/traceId.
  - Политики ретеншена: хранить 30 дней, очистка batch-job’ом.
  - Обеспечить audit trail и логи доступа.
- [x] Реализовать детальный лог и телеметрию оркестрации (начало/конец шага, вход/выход агента, ошибки, fallback) с трассировкой запросов.
  - Структурированные логи (JSON) с корреляцией, интеграция с Micrometer/OTel.
  - Метрики: `flow_sessions_active`, `flow_step_duration`, `flow_retry_count`, `flow_cost_usd`.
- [x] Реализовать фонового воркера очереди `flow_job`: Spring `@Scheduled` компонент вызывает `AgentOrchestratorService.processNextJob(...)`.
  - Параметры (`enabled`, `pollDelay`, `maxConcurrency`) задаются через конфигурацию; `workerId` формируется из имени узла/потока.
  - Обеспечить корректную обработку ошибок, idempotent shutdown и совместную работу нескольких инстансов сервиса.
  - Зарегистрировать Micrometer метрики `flow.job.poll.count`/`flow.job.poll.duration` (тег `result=processed|empty|error`) и алерты на рост ошибок или пустых выборок.
- [x] Покрыть оркестратор юнит- и интеграционными тестами (ветвления, разные типы памяти, провайдеры, ошибки).
  - Unit: state machine, transitions, memory access, overrides.
  - Integration: Testcontainers (Postgres, Redis), сценарии success/failure/pause/resume.
  - Контрактные тесты API (`/start`, `/control`, `/status`) + snapshot тесты JSON.

### Frontend и API
- [x] Расширить REST API: `POST /api/flows/{flowId}/start` и `GET /api/flows/{sessionId}` для получения статуса шагов, контекста и результатов агентов.
  - `POST /api/flows/{flowId}/start`: тело `{ "parameters": {...}, "overrides": {...} }`, ответ `FlowSessionDto` с `sessionId`, `status`, `startedAt`.
  - `GET /api/flows/{sessionId}`: long-poll с параметрами `sinceEventId`, `stateVersion`; возвращает `flowState`, `events`, `telemetry`.
  - `POST /api/flows/{sessionId}/control`: команды `pause`, `resume`, `cancel`, `retryStep`.
  - SSE `/api/flows/{sessionId}/events/stream`: переиспользовать инфраструктуру `SseEmitter`, отдавать incremental события; long-poll — обязательный fallback.
- [x] Добавить UI-конфигуратор флоу (визуальный порядок агентов, выбор моделей, промптов, настроек памяти) и просмотр активных/исторических запусков.
  - Раздел `Flows / Definitions`: таблица с фильтрами, кнопки `Create`, `Edit`, `Publish`.
  - Редактор: форма на основе JSON Schema (React JSON Schema Form), drag&drop упорядочивание шагов, предпросмотр YAML.
  - История версий + diff, кнопка дубликата (`Duplicate flow`).
- [x] Поддержать отображение шага оркестрации в выделенном разделе (Flow workspace): статус, промежуточные результаты агента, общий контекст, быстрые ссылки на связанные чат-сессии.
  - Экран `Flow Workspace`: панель статуса (progress bar, текущий step, latency, cost), список шагов с expandable карточками (`prompt`, `output`, usage, метрики).
  - Контекстный sidebar: `Shared memory`, `Launch parameters`, `Telemetry`.
  - Ссылки на чат-сессии (если шаг создаёт связь с `sessionId` чата).
- [x] Реализовать обязательный статус-фид флоу на UI (progress tracker, текущий шаг, финальный итог) и вывод сохранённых запросов/ответов агента с ключевыми метаданными, включая usage/токены и стоимость.
  - Компонент `FlowTimeline`: incremental rendering событий из long-poll API.
  - Метаданные: usage native/fallback, стоимость, модель, duration, retries, traceId.
  - Экспорт логов шага (JSON/Markdown) для поддержки.
- [x] Добавить отдельный раздел `Flows` (отдельный маршрут/экран) для инициализации и мониторинга agentic flow; настроить навигацию из основного чата и обратно, исключив смешение с режимами `stream/sync/structured`.
  - Маршруты: `/flows`, `/flows/definitions`, `/flows/definitions/:id`, `/flows/sessions`, `/flows/sessions/:sessionId`.
  - Навигация: link из главного меню, breadcrumbs внутри раздела, кнопка «Launch Flow» из чатового UI.
  - Состояния ошибок/empty states, skeleton loaders для long-poll.
- [x] Реализовать настройки запуска флоу на UI: форма выбора шаблона, ввод параметров, обзор шагов перед запуском, отображение ожидаемых затрат (estimated cost/tokens).
- [x] Покрыть фронтенд тестами: unit (React Testing Library) для ключевых компонентов, e2e (Playwright) для сценариев создания флоу, запуска, паузы/возобновления, просмотра истории.

### Документация и процессы
- [x] Обновить `docs/infra.md` и `docs/processes.md`: описать архитектуру оркестратора, модель данных, форматы конфигураций и политики памяти.
- [x] Подготовить ADR о выборе подхода к мультиагентной оркестрации, включая анализ альтернатив и рисков.
- [ ] Задокументировать чек-лист тестирования флоу (позитивные/негативные ветки, сбои провайдеров, повторные запуски, деградация к single-agent, проверка synchrony/usage/токенов и стоимости).
- [ ] Описать гайд по эксплуатации для поддержки: мониторинг, алерты, обновление шаблонов флоу, инструкция по добавлению нового агента/модели.

## Wave 9.1 — Каталог агентов и редактор флоу
### Backend
- [x] Добавить REST API каталога агентов: `GET /api/agents/definitions`, `GET /api/agents/definitions/{id}`, `POST /api/agents/definitions` (identifier, displayName, description, active), `PUT /api/agents/definitions/{id}` и `PATCH` для переключения статуса.
- [x] Реализовать эндпоинты версий: `GET /api/agents/definitions/{id}/versions`, `POST /api/agents/definitions/{id}/versions` (providerType, providerId, modelId, systemPrompt, defaultOptions, toolBindings, syncOnly, maxTokens) и `POST /api/agents/versions/{versionId}/publish`/`deprecate`.
- [x] Провести валидацию входных данных (поддерживаемые `ChatProviderType`, существование модели, maxTokens, формат JSON-полей).
- [x] Протоколировать `createdBy`/`updatedBy` и сохранять связанный список `agent_capability` при выпуске версий.
- [x] Обновить `FlowDefinitionService` и `FlowLaunchPreviewService`: отдавать 422 при использовании неактивной или черновой версии, возвращать в DTO имя агента, модель и системный промпт.
- [x] Покрыть новые сервисы и контроллеры unit-/интеграционными тестами: создание определения → версия → публикация → использование во флоу.

### Frontend
- [x] Создать раздел `Flows / Agents`: список определений с фильтрами, форма создания/редактирования (identifier, displayName, description, active).
- [x] Реализовать UI управления версиями: таблица с состояниями, кнопки `Create version`/`Publish`, форма выбора провайдера и модели, редактор systemPrompt и JSON блоков (`defaultOptions`, `toolBindings`) с валидацией.
- [x] Расширить редактор флоу: вместо raw JSON добавить конструктор шагов с обязательным выбором `agentVersionId` из списка опубликованных версий, подсвечивать ошибки для отсутствующих агентов при импорте JSON.
- [x] При создании/редактировании флоу отображать имя агента и модель в превью шага, блокировать сохранение, пока каждый шаг не содержит выбранную версию; обновить `apiClient.ts` новым набором методов каталога.
- [x] Добавить кеширование и debounce запросов списка агентов, unit/e2e тесты на выбор версии и создание агента из UI.

### Документация и процессы
- [x] Обновить `docs/infra.md` и `docs/architecture/flow-definition.md`: описать каталог агентов, формат API, статусную модель версий и требования при создании флоу.
- [x] Дополнить `docs/processes.md` чек-листом онбординга агента (создание definition, выпуск версии, smoke-тест, публикация).
- [x] Подготовить релизную заметку Wave 9.1 (CHANGELOG) с пользовательскими сценариями: создание агента, назначение его во флоу, ограничения и roadmap на Wave 9.2.

## Wave 9.2 — Стабилизация запуска и рабочего места операторов
### Backend
- [x] Расширить `FlowStartRequest`/`FlowStartResponse`: добавить per-run overrides, сохранять launch-параметры в `FlowSession`, прокидывать launch/overrides в `AgentInvocationRequest` и payload `flow_event`.
- [x] Уточнить перенос контекста между шагами:
  - [x] зафиксировать канонический способ передачи launch-параметров: хранить их в `FlowSession.launchParameters` и прокидывать в `AgentInvocationRequest` отдельным блоком (например, `launchContext`), не смешивая с `sharedContext`;
  - [x] адаптировать flow-definition (настройка `memoryWrites`/`memoryReads`), чтобы выводы агентов автоматически попадали во вход следующей задачи.
- [x] Исправить паузу: `FlowControlService.pause` должен блокировать обработку jobs, пока сессия в `PAUSED`, а `FlowJobWorker`/`AgentOrchestratorService` обязаны проверять статус перед запуском шага.
- [x] Учесть `FlowStepTransitions.onFailure`/`failFlowOnFailure`, ввести состояние ожидания (`WAITING_STEP_APPROVAL`), добавить команды `approveStep`/`skipStep`, поддержать частичные ретраи без автоматического `FAILED`.
- [x] Реализовать фактическую обработку `onFailure`: при ошибке шага переходить на объявленный `next`, если он задан, вместо безусловного завершения сессии.
- [x] Обновить `GET /api/flows/{sessionId}`/SSE: включить telemetry snapshot, traceId/spanId, сведения о shared memory, привести фактический JSON к документации.
- [x] Заполнять trace/span в `FlowEvent`, внедрить retention-политику памяти (последние 10 версий + TTL 30 дней), зарегистрировать Micrometer метрики, заявленные для Wave 9.
### Frontend
- [x] Обновить экран запуска: позволить задавать launch overrides (temperature/topP/maxTokens), показывать итоговый payload и предупреждения перед стартом.
- [x] Переработать редактор определений: заменить raw textarea на форму по JSON Schema с предпросмотром YAML и diff, поддержать сравнение версий.
- [x] Реализовать полноценный `Flow Workspace`: progress bar и этапы, expandable карточки с usage/cost, sidebar для shared memory/телеметрии, таймлайн событий с SSE, экспорт логов шага.
- [x] Доработать мониторинг сессии: заменить список событий на потоковый таймлайн с прогрессом шагов, текущей памятью и телеметрией, синхронизированный через long-poll/SSE.
### Документация и тестирование
- [x] Обновить `docs/infra.md`/`docs/processes.md`/ADR в соответствии с новым API и рабочими сценариями операторов.
- [x] Добавить интеграционные тесты очереди/воркера и SSE, e2e-сценарии запуска → паузы → ручного утверждения/скипа шага, smoke-проверку retention памяти.

## Wave 10 — Human-in-the-loop запросы агентов в UI
### Продукт и UX
- [x] Сформировать сценарии human-in-the-loop для агентов: запрос уточнений, подтверждение действий, ввод параметров; описать user journey во Flow workspace, из чатового UI и для фоново запущенных флоу.
  - Зафиксировать SLA на ответ, fallback при отсутствии пользователя (auto-cancel, автоответ по умолчанию, эскалация).
- [x] Определить UX-паттерны отображения интерактивных запросов: основной сценарий — side panel во Flow Workspace с inline-карточкой в таймлайне, навигационный бейдж + toast; push/web уведомления подключаются для неактивных пользователей; чатовый UI показывает bubble с CTA «Открыть форму».
- [x] Зафиксировать модель маршрутизации по `chat_session_id`: id создаётся при запуске и остаётся неизменным, все `flow_interaction_request` жёстко привязаны к нему; для эскалаций требуется запуск новой чат-сессии вместо reassignment (см. `docs/human-in-loop-scenarios.md`).

### Архитектура
- [x] Определить каноническую модель `AgentUiRequest` (id, stepId, agentId, тип, payloadSchema, dueAt, suggestedActions) и жизненный цикл (`pending`, `answered`, `expired`, `autoResolved`) с привязкой к `flow_session`/`flow_event` (см. `docs/architecture/human-in-loop.md`).
- [x] Обновить state machine оркестратора: добавить состояния `WAITING_USER_INPUT`/`BLOCKED_WAITING_USER`, правила возврата в `RUNNING`, таймауты, автоматические решения и команды управления (`docs/architecture/human-in-loop.md`).
- [x] Зафиксировать подход к доставке запросов: гибрид SSE событий и чатового bubble; вариант глобальной шины откладываем на Wave 10.2 (`docs/architecture/human-in-loop.md`).
- [x] Зафиксировать стратегию `suggestedActions`: rule-based минимум в `AgentVersion`, LLM-рекомендации с allowlist-фильтром, аналитические подсказки в следующих волнах (`docs/architecture/human-in-loop.md`).

### Backend
- [x] Расширить модель данных: таблицы `flow_interaction_request` и `flow_interaction_response`, индексы, миграции, аудит доступа.
- [x] Реализовать `FlowInteractionService`, обновить `AgentOrchestratorService` для генерации запросов, публикации событий и блокировки шага до ответа (`FlowInteractionService`, `FlowInteractionController`).
- [x] Добавить REST-эндпоинты: `GET /api/flows/{sessionId}/interactions`, `POST /api/flows/{sessionId}/interactions/{requestId}/respond`, `POST /api/flows/{sessionId}/interactions/{requestId}/skip`/`auto`.
- [x] Интегрировать с `FlowControlService`: автоматическое auto-resolve при cancel, возобновление очередей после ответа (`FlowInteractionService`, `FlowControlService`).
- [x] Валидация `payloadSchema`: добавить `FlowInteractionSchemaValidator` (тип/format, enum, required) и проверку входных payload в `FlowInteractionService`.
- [x] Настроить телеметрию и алерты: `FlowTelemetryService` фиксирует счётчики созданных/закрытых запросов, gauge открытых, таймер ожидания; алерты подвяжем на новые метрики.

### Frontend и API
- [x] Расширить клиент `apiClient`: добавлены методы для CRUD интеракций и помощник `subscribeToFlowEvents` для SSE `flow`/`heartbeat`.
- [x] Реализовать UI-компоненты Flow workspace: список активных запросов, side-panel с формой и подсказками, отображение ответа.
- [x] Добавить механизмы уведомлений: бейджи в навигации/чат UI, toast и desktop уведомления, подсветка активных запросов в списках.
- [ ] Покрыть сценарии тестами: unit (формы, стейт-менеджмент), e2e (создание запроса, ответ пользователя, таймаут, автозавершение, параллельные ответы), визуальные снапшоты.
- [ ] Реализовать виджеты для указанных типов `payloadSchema` (multiselect, date/datetime picker, file upload, JSON-редактор) и отобразить подсказки из `suggestedActions`.
- [x] Отобразить `suggestedActions`: базовый набор из конфигурации шага + дополнительные рекомендации, полученные от LLM и помеченные как «рекомендации».

### Документация и процессы
- [x] Обновить `docs/infra.md` и `docs/processes.md`: описать поток человек↔агент, схемы данных, SLA и таймауты, best practices по UX copy.
- [x] Подготовить runbook для поддержки: просмотр и переадресация запросов, ручное автозавершение, реагирование на алерты, разбор инцидентов.
- [x] Описать примеры конфигурации: как агент объявляет необходимость `requiresUserInput`, схемы форм (JSON Schema), шаблоны промптов, рекомендации по хранению персональных данных.
- [x] Зафиксировать чек-лист тестирования human-in-the-loop: ответы с задержкой, повторные запросы, конкурирующие ответы, проверка RBAC и аудита.

## Wave 10.1 — LLM-саммаризация длинных диалогов
Цель: при переполнении окна контекста автоматически агрегировать историю в саммари через LLM, уменьшая шум и сохраняя ключевые факты для следующих запросов.

### Data
- [x] Liquibase: создать таблицу `chat_memory_summary` (`id`, `session_id`, `summary_text`, `source_range`, `token_count`, `metadata`) и индексы по `session_id`.
- [x] Добавить столбцы `summary_until_order` в `chat_session` и `summary_metadata JSONB`, чтобы хранить прогресс саммаризации и версию схемы.
- [x] Для `flow_interaction_request`/`flow_step_execution` предусмотреть аналогичную таблицу `flow_memory_summary`, чтобы унифицировать подход во флоу.
- [x] Расширить схему summary полями `language`, `step_id`, `agent_version_id`, `attempt_range`, чтобы привязывать summary к конкретным шагам и учитывать локализацию.
- [x] Подготовить repeatable-скрипт бэкфилла: вычислять сессии с количеством сообщений > порога и формировать стартовые саммари.

### Backend
- [x] Расширить `ChatMemoryProperties` параметрами `summarization.enabled`, `summarization.triggerTokenLimit`, `summarization.targetTokenCount`, `summarization.model`.
- [x] По умолчанию использовать модель `OPENAI:gpt-4o-mini` для генерации саммари, допускается переопределение через конфигурацию.
- [x] Реализовать `ChatMemorySummarizerService`: считать токены истории, формировать prompt для LLM, сохранять результат с ссылкой на диапазон сообщений; итоговое summary описывает полезную информацию из пары «пользователь ↔ LLM».
- [x] Обновить `DatabaseChatMemoryRepository` и `MessageWindowChatMemory`: при чтении включать релевантные саммари и остаток сообщений, при записи — запускать асинхронный воркер саммаризации, чтобы summary всегда отдавались агенту в рантайме.
- [x] Интегрировать воркер с `FlowInteractionService`/`FlowMemoryService`, чтобы крупные payload'ы агрегировались в саммари без потери фактов.
- Уточнение: суммаризация выполняется для пар «вход пользователя (input payload шага) ↔ ответ агента». Результат добавляется в рантайм-окно `FlowMemoryService`, чтобы агент видел summary сразу после записи, без доп. запросов.
- Триггер — та же preflight-проверка по числу токенов перед каждым вызовом агента; пороги reuse `app.chat.memory.summarization.*`.
- Логику можно реюзать из `ChatMemorySummarizerService`, передавая flow-сообщения в том же формате.
- План реализации:
  1. Расширить `flow_memory_version`: добавить колонки `source_type` (`USER_INPUT`, `AGENT_OUTPUT`), `step_id`, `agent_version_id`, `attempt`, чтобы фиксировать происхождение каждой записи; обновить `MemoryWriteInstruction`/`AgentOrchestratorService`, чтобы user input и agent output записывались с корректной ролью.
  2. Завести абстрактный `FlowMemorySummarizerService`, который использует эти роли для оценки токенов и собирает summary + tail в `flow_memory_summary`; итогом переписывает хвост канала (как в чате).
  3. В `FlowMemoryService` и `AgentInvocationService` встроить общий конвейер: preflight по токенам перед вызовом агента, запись summary при превышении лимита, чтение «summary + tail» в рантайме.
  4. Переиспользовать существующий `ChatMemorySummarizerService` (модель `openai:gpt-4o-mini`, текущие ретраи/метрики), передавая flow-пары как `Message`.
- [x] Добавить ручной endpoint/CLI-команду для пересаммаризации (пересчёт при смене модели/порога).
- [x] Перед каждым вызовом LLM внедрить preflight-проверку размера промпта: если история + overrides превышают `triggerTokenLimit`, инициировать саммаризацию и повторно собрать окно до отправки (токены считаются перед каждым вызовом).
- [x] Реализовать отказоустойчивость саммаризации: ретраи 3× с backoff, fallback к исходному окну при недоступности LLM, сохранение исходных сообщений до успешного summary, алерт на повторяющиеся ошибки.
- [x] Создать единую обёртку preflight для всех клиентов LLM (`ChatService`, `SyncChatService`, `StructuredSyncService`, потоковые контроллеры), чтобы исключить обход проверки.
- [x] Ограничить параллелизм саммаризаций (worker pool, configurable `maxConcurrentSummaries`), добавить метрику длины очереди и обработку отказов.
- [x] Защитить воркер от «коротких» переполнений: если сообщений меньше размера окна, но токены превышают лимит, саммари теперь строится по всей истории с сохранением минимального хвоста.

### Observability & Docs
- [x] Метрики: `chat_summary_runs_total`, `chat_summary_tokens_saved`, `chat_summary_duration_seconds`, отдельные алерты при провалах саммаризации.
- [x] Обновить `docs/infra.md` и `docs/processes.md`: описать конфигурацию, стратегию триггеров, ограничения (задержки, максимальный размер), обязательное логирование факта саммаризации (модель, размер до/после, диапазон сообщений).
- [x] Добавить runbook: как пересоздать саммари, откатить изменения, диагностировать ошибки LLM; указать, что процесс полностью автоматический без ручного редактирования.
- [x] Обновить README с описанием влияния саммаризации на вопросы поддержки и поиск по истории.
- [x] Документировать стратегию ретенций, лимитов параллелизма и алертов (preflight пропуски, fallback, queue length) в `docs/infra.md`.

### QA & Tests
- [x] Unit-тесты `ChatMemorySummarizerService`: формирование промпта, разбор ответа, защита от пустых или невалидных саммари.
- [x] Интеграционные тесты `DatabaseChatMemoryRepository`: summary + хвост, перезапись окна `saveAll`.
- [x] Интеграционные тесты `FlowInteractionService`: сценарии перехода порога, включение саммари в ответ, повторная саммаризация.
- [x] Smoke-тест в CI: создать длинный диалог, убедиться, что preflight-проверка включает саммари, окно урезается до целевого размера, а ключевые факты остаются в summary.
- [x] Проверить, что логирование саммаризации корректно работает в unit-/интеграционных тестах (размеры, модель, успешность/ошибки).

## Wave 10.2 — Завершение rollout саммаризации во флоу
Цель: довести flow-саммаризацию до продакшн-готовности — автоматический запуск без ручных правок DSL, полные метаданные и наблюдаемость, единый UX для операторов/агентов.
 
### Backend
- [x] Нормализовать канал, в котором живёт «пользователь ↔ агент»: либо всегда дублировать оба сообщения в `conversation`, либо расширить `FlowMemorySummarizerService` так, чтобы он принимал любой канал из `memoryReads` и помечал summary в `metadata.summary=true`.
- [x] Независимо от выбранного подхода гарантировать, что `AgentInvocationService.triggerFlowSummaries` срабатывает перед каждым вызовом агента без дополнительных настроек в DSL (дефолтно добавляем `conversation` в `memoryReads`).
- [x] Исправить записи памяти: `recordUserPrompt` и блок сохранения AGENT_OUTPUT должны писать в один и тот же канал, чтобы summary описывали пары, а не только пользовательский input.
- [x] При сохранении summary в `flow_memory_summary`/`chat_memory_summary` заполнять `agent_version_id`, `language`, `attempt_start/end`, `metadata.schemaVersion`, а также обновлять `ChatSession.summaryMetadata` (прогресс и модель).
- [x] Добавить API/CLI для форсированного пересчёта flow-summaries (аналог admin-эндпоинта для чатов) с ограничением по сессиям/каналам.
- [x] Интегрировать `FlowInteractionService` и воркер: при создании/ответе на interaction pipeline обязан пересобирать окно «summary + хвост» и публиковать его операторам/агентам.
- [x] GAP: **Flow summaries пока обрезаны и не запускаются по умолчанию.** Пользовательские сообщения всегда складываются в канал `conversation`, тогда как `AGENT_OUTPUT` попадает в каналы из DSL (`shared` по умолчанию), а `FlowMemorySummarizerService` умеет читать только `conversation`. В итоге summary строятся только из пользовательских реплик, а когда шаг не читает `conversation`, `triggerFlowSummaries` вообще не вызывается.
- [x] GAP: **Нет связи HITL ↔ память ↔ summary.** `FlowInteractionService` никак не записывает payload ответов/авто‑резолвов обратно в память и не дергает summarizer, поэтому длинные формы и ответы операторов не попадают в «summary + хвост», хотя того требует дизайн 10.2.
- [x] GAP: **Метаданные summary не заполняются.** В `FlowMemorySummarizerService.persistSummary` и `ChatMemorySummarizerService.persistSummary` остаются `null` для `agent_version_id`, `language`, диапазонов попыток и `metadata.schemaVersion`, а `ChatSession.summaryMetadata` никогда не обновляется — аналитика и прогресс, описанные в волне, недоступны.
- [x] GAP: **Нет ручного восстановления flow-саммари.** Админ-эндпоинт реализован только для чатов (`/api/admin/chat/...`), у FlowSummaries нет ни REST, ни CLI для rebuild/backfill, которые зафиксированы в требованиях.
- [x] GAP: **Отсутствует наблюдаемость flow-саммари.** В сервис не подтянут `MeterRegistry`, нет метрик `flow_summary_runs_total`, queue size/rejections и alert counters, поэтому алерты волны 10.2 невозможно подключить.
- [x] GAP: **MessageChatMemoryAdvisor мешает внутренним LLM-вызовам.** Summarizer и backfill ходят в `ChatClient` с advisor-цепочкой, из-за чего в `chat_memory` попадают служебные запросы с `conversationId=default` и валятся предупреждения. Нужно выделить отдельный client без `MessageChatMemoryAdvisor` для внутренних вызовов или научить advisor пропускать такие сценарии.
- [x] GAP: **Тестов продакшн-уровня нет.** Имеются только unit-тесты на сервисы памяти; сценарии из волны (длинный flow → summary, interaction → summary, ручной rebuild, гонки воркеров) не покрыты даже частично.

### Observability & QA
- [x] Ввести отдельные метрики с лейблом `scope=flow`: `flow_summary_runs_total`, `flow_summary_duration_seconds`, `flow_summary_queue_size`, `flow_summary_queue_rejections_total`, `flow_summary_failure{,_alerts}_total`.
- [x] Настроить алерты на переполнение очереди, >3 подряд ошибок по одной сессии и отсутствие `summary_metadata` дольше N минут при включённом воркере.
- [x] Добавить интеграционные тесты:
  - длинный flow превышает лимит → появляется запись в `flow_memory_summary`, а `FlowMemoryService.history()` возвращает summary + tail;
  - FlowInteraction после ответа возобновляет шаг с учётом summary;
  - ручной ребилд обновляет summary и метаданные.
- [x] Смоук-тест для конкурирующих summarizer-джобов (параллельные сессии) — проверяем семафор и очередь.

### Документация и процессы
- [x] Обновить `docs/infra.md`, `docs/processes.md`, `docs/architecture/flow-definition.md` — описать выбранную стратегию каналов, формат `summary_metadata`, ручные процедуры (rebuild/backfill) и SLO.
- [x] Дополнить runbook инструкциями для операторов: как убедиться, что summary приклеилось к interaction, как перезапустить воркер, какие параметры менять при деградации.
- [x] Зафиксировать решение по выбору варианта (канон. канал vs мультиканальный режим) в ADR с описанием компромиссов:
  1. **Channel unification** — минимум ветвлений, простой DX, но требует миграции существующих flow на `conversation`.
  2. **Multi-channel summaries** — гибко для специализированных каналов, но сложнее history + фронта.
  3. **Гибрид** — дефолт `conversation` + whitelist иных каналов в DSL, даёт постепенный rollout ценой дополнительной конфигурации.

## Wave 10.3 — Stabilisation flow/chat summarisation
### Backend
- [x] Убрать привязку flow-summarizer'а к chat window: `FlowMemorySummarizerService` теперь вычисляет хвост динамически (как максимум из 4 сообщений и половины истории), поэтому даже при retention в 10 версий preflight строит план и summary появляется без дополнительных настроек.
- [x] В `ChatMemorySummarizerService.persistSummary` записывать реальный подсчёт токенов: теперь `tokenUsageEstimator` считает токены итогового summary и значение попадает в `chat_memory_summary.token_count`, что делает отчёты об экономии корректными.

### Observability & Resilience
- [x] Обеспечить управляемую очередь и метрики для flow-саммари: `FlowMemorySummarizerService` теперь отправляет план в ограниченную очередь `ThreadPoolExecutor` с метриками `flow_summary_queue_size`, `flow_summary_active_jobs` и счётчиком дропа при переполнении, а сами задачи ждут свободный слот вместо немедленного отказа.

## Wave 11 — Strict typing rollout
### Backend
- [x] Заменить `Map<String, Object>` в `ChatProviderService.chatSyncWithOverrides` и `AgentInvocationService` на явный DTO для advisor параметров, обеспечить валидацию содержимого.
- [x] В `DatabaseChatMemoryRepository` отказаться от десериализации в `Map.class`: определить типизированную модель metadata и централизованный маппер.
- [x] Для `FlowSession` и связанных сущностей заменить поля `JsonNode` (`launchParameters`, `sharedContext`, `launchOverrides`, `telemetry`) на value-объекты с описанной схемой и конверторами.
- [x] Типизировать JSON-поля домена (`AgentVersion.defaultOptions/toolBindings/costProfile`, `FlowDefinition.definition`, `FlowDefinitionHistory.definition`, `FlowStepExecution.*Payload/usage/cost`, `FlowEvent.payload`, `AgentCapability.payload`, `ChatMessage.structuredPayload`) через отдельные value-объекты и конвертеры, исключив «сырые» `JsonNode`.
- [x] Обновить трассировку/логирование после типизации, чтобы не полагаться на произвольные JSON-структуры.
- [x] FlowSession value objects: определить модели (`FlowLaunchParameters`, `FlowSharedContext`, `FlowOverrides`, `FlowTelemetrySnapshot`), реализовать `AttributeConverter`/`JsonColumn<T>` и мигрировать `FlowSession`, `FlowStartResponse`, `FlowStatusService`.
- [x] Agent payloads: создать DTO (`AgentDefaultOptions`, `AgentToolBinding`, `AgentCostProfile`, `AgentCapabilityPayload`) + конвертеры, внедрить в `AgentVersion`, `AgentCapability` и сервисы чтения/записи.
- [x] Flow execution payloads: добавить `FlowStepInputPayload`, `FlowStepOutputPayload`, `UsageCostPayload` и `FlowEventPayload` + mapper-сервис (`FlowPayloadMapper`), переписать `AgentOrchestratorService`/`FlowStatusService`/`FlowEventRepository`.
- [x] Тесты конвертеров/mapper'ов: покрыть round-trip сериализацию и схемы дефолтов (JUnit).

**Финальный подход Backend:** берём *Value Object + Converter* как базовый слой типизации и сразу выносим сериализацию в mapper-сервисы (`FlowPayloadMapper`, `AgentOptionsMapper`). Генерацию DTO из схем планируем после стабилизации моделей, но текущий rollout от неё не зависит.
### Frontend
- [x] Уточнить типы в `frontend/src/lib/apiClient.ts`: вместо `unknown` описать структуры `defaultOptions`, `toolBindings`, `costProfile`, `launchParameters`, `sharedContext`, `FlowEvent.payload` и др.
- [x] Переписать `FlowAgents` и другие потребители API так, чтобы парсинг JSON (например, `parseOptionalJson`) возвращал строго типизированные объекты с проверкой схемы.
- [x] Перестроить `FlowDefinitionForm`/`FlowLaunch` стейт: вместо `Record<string, unknown>` и массовых `JSON.parse` использовать строгие интерфейсы (`FlowDefinitionDraft`, `FlowLaunchPayload`) + валидаторы.
- [x] Обновить обработку ответов `requestSync`/`requestStructuredSync`: валидировать JSON (type guards/zod) перед приведениями `as`, чтобы поймать несовместимые схемы.
- [x] Добавить unit-тесты/типовые проверки для новых типов, чтобы предотвратить регрессии строгой типизации.
- [x] Создать модуль `frontend/src/lib/types/flow.ts` c доменными интерфейсами (`FlowDefinitionDraft`, `AgentOptions`, `FlowEventPayload` и т. д.) и `zod`-схемами/type guards.
- [x] Переписать `apiClient.ts` на новые типы и guards: распарсивать ответы через `safeParse`, выбрасывать ошибки при несовпадении схемы.
- [x] FlowAgents: заменить `parseOptionalJson` на typed builder + `zod`-валидацию, хранить `VersionFormState` в терминах новых DTO и адаптеров.
- [x] FlowDefinitionForm/FlowLaunch: ввести `deserialize/serialize` адаптеры для форм-стейта, убрать прямой `JSON.parse`.
- [x] Тесты: добавить unit-тесты на guards и адаптеры (например, `flowDefinitionForm.adapters.test.ts`, `apiClient.types.test.ts`).

**Финальный подход Frontend:** реализуем ручные доменные модели + `zod` type guards и state-adapters для форм. Когда backend предоставит стабильные схемы, подключим генерацию типов поверх уже существующих guards.
### Инженерные требования
- [x] Внедрить правило строгой типизации во всех сервисах: на backend использовать явные DTO/валидаторы и исключать неявные маппинги, на frontend включить строгий режим TypeScript и типизацию API.
- [x] Настроить линтеры и проверки CI, блокирующие появление нестрого типизированных конструкций (`any`, `Map<String,Object>` и т. п.).

## Wave 12 — Typed flow builder и конструктор агентов
Цель: объединить создание агентов и флоу в одну типизированную воронку и избавиться от ручного JSON в UI/БЭК. Сейчас редакторы опираются на сырые структуры (`frontend/src/pages/FlowAgents.tsx:234-302`, `frontend/src/pages/FlowDefinitions.tsx:700-755`, `frontend/src/lib/flowDefinitionForm.ts:15-138`), а backend хранит схему в `JsonNode` (`backend/src/main/java/com/aiadvent/backend/flow/domain/AgentVersion.java:55-168`, `backend/src/main/java/com/aiadvent/backend/flow/domain/FlowDefinition.java:41-117`) и парсит лишь минимальное подмножество (`backend/src/main/java/com/aiadvent/backend/flow/config/FlowDefinitionParser.java:24-194`). Wave 11 вводит строгие DTO; здесь строим UX и сервисы поверх них.

### Product & UX
- [x] Описать end-to-end journey «создать агента → собрать flow → запустить»: роли, happy path/ошибки, возвраты из Flow launch preview, зависимости от статусов версий.
  - Зафиксировано в `docs/wave12-agent-flow-journey.md`: роли (Designer/Orchestrator/Ops), шаги от конструктора до запуска, happy path и матрица ошибок/зависимостей.
- [x] Подготовить UI kit/wireframes для визуального конструктора агентов и flow builder (многошаговый wizard, панели памяти/переходов, inline preview затрат), опираясь на текущие экраны `FlowDefinitions.tsx` и `FlowLaunch.tsx`.
  - Черновик UI-kit и wireframes описан в `docs/wave12-agent-flow-ui-kit.md`: шаги wizard, компоненты (provider selector, prompt editor, tooling, cost), состояния (validate, warnings, RBAC), layout flow builder.
- [x] Обновить критерии приемки и чек-лист тестирования нового UX (валидации, undo/redo, RBAC, совместная работа нескольких операторов).
  - Черновик критериев и чек-листов задокументирован в `docs/wave12-ux-acceptance.md`: покрытие конструктора, flow builder, ops, RBAC/коллаборации.

### Backend & API
- [x] Реализовать `FlowBlueprint` value-объекты и репозиторий: заменить хранение `FlowDefinition.definition` (`backend/src/main/java/com/aiadvent/backend/flow/domain/FlowDefinition.java:41-117`) и ручной парсинг `FlowDefinitionParser` (`backend/src/main/java/com/aiadvent/backend/flow/config/FlowDefinitionParser.java:24-194`) на типизированную схему с явными блоками `metadata`, `launchParameters`, `memory`, `steps`.
- [x] Добавить `FlowBlueprintCompiler`, который преобразует blueprint в runtime `FlowStepConfig`/`Memory*Config`, и подключить его в `AgentOrchestratorService` (`backend/src/main/java/com/aiadvent/backend/flow/service/AgentOrchestratorService.java:107-211`) и `FlowLaunchPreviewService` (`backend/src/main/java/com/aiadvent/backend/flow/service/FlowLaunchPreviewService.java:47-190`), включая кеширование и валидацию ссылок.
- [x] Ввести typed-конфигурацию агента (`AgentInvocationOptions`, `ToolBinding`, `CostProfile`) вместо `JsonNode` полей `AgentVersion` (`backend/src/main/java/com/aiadvent/backend/flow/domain/AgentVersion.java:55-168`) и `Map<String,Object>` advisor параметров в `AgentInvocationService` (`backend/src/main/java/com/aiadvent/backend/flow/service/AgentInvocationService.java:114-178`); реализовать `AgentConstructorService` + REST API для генерации и валидации конфигураций.
  - Подход Option 1: полный рефактор домена без обратной совместимости — удаляем legacy JSON-поля `default_options/tool_bindings/cost_profile`, добавляем колонку `agent_invocation_options` и таблицы `tool_definition`/`tool_schema_version`; существующие данные очищаем, агенты/flows пересоздаём через новый конструктор.
  - `AgentInvocationOptions` (Option B): блоки `Provider` (type/id/model/mode), `Prompt` (system/templateId/variables[]), `MemoryPolicy` (channels[], retentionDays, maxEntries, summarizationStrategy, overflowAction), `RetryPolicy` (maxAttempts, initialDelayMs, multiplier, retryableStatuses[], timeoutMs, overallDeadlineMs, jitterMs), `AdvisorSettings` (telemetry/audit/routing параметрами), `Tooling` (binding DTO), `CostProfile` (input/output per 1K tokens, currency, optional latencyFee/fixedFee).
  - Каталог инструментов (Option C): `tool_definition` хранит `id`, `code`, `display_name`, `description`, `provider_hint`, `call_type`, `tags[]`, `capabilities[]`, `cost_hint`, `icon_url`, `default_timeout_ms`, ссылку на актуальную `tool_schema_version`; `tool_schema_version` содержит `id`, `tool_code`, `version`, `request_schema`, `response_schema`, `schema_checksum`, `examples[]`, `mcp_server`, `mcp_tool_name`, `transport`, `auth_scope`, `created_at`.
  - REST: новые DTO/эндпоинты `AgentConstructorService` работают только с `AgentInvocationOptions`; `POST /preview` (Option C) отдаёт `proposed` конфигурацию, diff по полям, рассчитанные cost/latency метрики и покрытие инструментов; валидация/превью выполняются по backend-шаблонам (создание/изменение/редактирование), без обращения к внешним копиям документации в рантайме.
  - Миграции: Liquibase — drop legacy колонки, create новые таблицы/колонку, truncate старые записи; после деплоя заливаем статичный seed (Option A) через SQL/JSON с примерным каталогом (`openai-gpt-4o`, `perplexity_search`, `perplexity_deep_research`), агентами (`demo-openai-chat`, `perplexity-research`) и flow `demo-lead-qualify`.
- [x] Выпустить API v2 для flow builder/агентного конструктора: `GET/PUT /api/flows/definitions/{id}` и `FlowLaunchPreviewResponse` возвращают `FlowBlueprint`, добавить эндпоинт валидации шага, справочники memory каналов/interaction-схем, новые команды в `AgentDefinitionController`; сохранить обратную совместимость через feature-flag.

### Frontend
- [x] Переписать `Flows / Agents` в режим конструктора: убрать JSON-текстовые поля (`frontend/src/pages/FlowAgents.tsx:234-302`), добавить формы для provider/model, temperature/topP/maxTokens, справочники tool binding и cost profile, превью capability payload’ов и проверки схем.
- [ ] Перенести `Flows / Definitions` на typed blueprint: устранить `raw`/`memoryReadsText`/`transitionsText` из `flowDefinitionForm` (`frontend/src/lib/flowDefinitionForm.ts:15-138`) и редактора (`frontend/src/pages/FlowDefinitions.tsx:700-755`), добавить визуальные редакторы launch parameters, shared memory, transitions, HITL-конфигураций и связь с cost preview.
- [ ] Добавить inline создание/публикацию агента прямо из редактора шага (modal поверх `Flows / Definitions`), синхронизировать список версий без перезагрузки и блокировать сохранение шага без валидированного `agentVersionId`.
- [x] Обновить `apiClient.ts` (`frontend/src/lib/apiClient.ts:42-200`) и стейт менеджмент Flow UI на новые типы: сгенерировать TS-модели из backend схемы (`FlowBlueprint`, `AgentVersionConfig`), добавить runtime-валидацию и удалить `unknown`/ручной JSON парсинг.

### Data & Migration
- [x] Liquibase: добавить новые структуры хранения (`blueprint_schema_version`, таблицы для agent config/tool binding), мигрировать существующие `flow_definition` и `agent_version` записи, пересчитать checksum и обновить ограничения.
- [x] Написать миграционный job/CLI, который конвертирует legacy JSON в blueprint/typed config, валидирует через `FlowBlueprintCompiler`, поддерживает dry-run/rollback и health-check, блокирующий сохранение старого формата.

### QA & Observability
- [x] Расширить unit/integration тесты: контракты Flow builder API ↔ TS типов, интеграционные сценарии `FlowDefinitionController`/`AgentDefinitionController`, нагрузочные тесты сохранения blueprint и генерации `FlowLaunchPreview`.
- [x] Добавить телеметрию и аудит: метрики сохранений blueprint/агентов, ошибки валидации, пользовательские события конструктора; настроить алерты и дашборды.

### Документация
- [x] Обновить `docs/architecture/flow-definition.md`, `docs/infra.md` и `docs/processes.md`: описать новый blueprint, API v2, конструктор агентов, миграцию и чек-лист тестирования; добавить запись в CHANGELOG/runbook с планом отката.

## Wave 13 — Perplexity MCP интеграция для живого ресёрча
Цель: подключить Perplexity через Model Context Protocol, чтобы агенты и чат выполняли live-research шаги, возвращали цитаты и делились контекстом с downstream-логикой. Волна добавляет поддержку MCP-инструментов поверх существующих LLM провайдеров и даёт готовые сценарии использования в flow и чатах.

### Backend
- [x] Подключить Spring AI MCP: добавить зависимость `org.springframework.ai:spring-ai-starter-mcp-client` в `backend/build.gradle`, зафиксировать конфигурацию `spring.ai.mcp.client.*` (STDIO transport через `npx -y @perplexity-ai/mcp-server`, таймаут 120s, переменные окружения) и обновить документацию по секретам.
- [x] Развести каталог MCP-инструментов: создать отдельные записи `perplexity_search` и `perplexity_deep_research` с нужными `mcp_tool_name`, схемами запросов/ответов и подсказками по тарифам.
- [x] Реализовать мягкое подключение MCP-инструментов к любому `ChatProviderAdapter`: расширить `ChatProviderConfiguration`/адаптеры OpenAI и ZhiPu логикой регистрации инструментов через `ToolCallbackProvider` без новых значений `ChatProviderType`.
- [x] Обновить `ChatProviderService` и `AgentInvocationService`: при наличии `AgentInvocationOptions.ToolBinding` с MCP-инструментом поднимать (и переиспользовать) долгоживущий STDIO-клиент, регистрировать выбранный tool и формировать payload с пользовательским запросом в поле `query` (без локали), сохраняя текущую модель подсчёта usage/cost.

### Flow Orchestration
- [x] Добавить шаблон агента `perplexity-research` в `AgentCatalogService`: системная подсказка, дефолтный `toolBinding` на `perplexity_search` и базовые лимиты, плюс опция переключения на `perplexity_deep_research`, чтобы flow-конструктор мог выбрать исследовательский шаг без ручного JSON.
- [x] Обновить `AgentOrchestratorService` и `FlowInteractionService`: поддержать шаги с MCP-инструментами (выбор `perplexity_search`/`perplexity_deep_research` при ручном рестарте, логирование выбранного tool), не меняя структуру shared/memory каналов.

### Chat Experience
- [x] Добавить поддержку поля `mode` в теле запросов `SyncChatService`, `StructuredSyncService` и `ChatStreamController`: при значении `research` подключать MCP-инструмент к выбранному провайдеру/модели и оставлять пространство для других режимов.
- [x] Обновить frontend-клиента (`frontend/src/lib/apiClient.ts`, `LLMChat`) и UI: дать пользователю переключатель research-режима, прокидывать `mode` в тело запросов stream/sync/structured и обрабатывать новые значения.
- [x] Обновить structured/sync UI и обработчики: рендерить строгий JSON-ответ Perplexity (summary/items/sources) и подсветку источников без жёсткой зависимости от `extensions["research"]`.

### Observability & Resilience
- [x] Реализовать `HealthIndicator` MCP-подключения: проверка запуска STDIO-процесса, handshake с tool list, метрики `perplexity_mcp_latency`/`perplexity_mcp_errors_total`.
- [ ] Добавить интеграционные тесты с mock MCP-сервером (WireMock/Testcontainers) для сценариев `AgentInvocationService`, `ChatProviderService` и research-шагов оркестратора.

### Infrastructure & Docs
- [x] Обновить `.env.example`/`.env` и `backend/src/main/resources/application.yaml`: добавить переменные для STDIO-команды (`PERPLEXITY_MCP_CMD`, `PERPLEXITY_API_KEY`, `PERPLEXITY_TIMEOUT_MS`), описать требования к Node/npm.
- [x] Обновить `docs/infra.md`/`docs/processes.md`: инструкция по запуску встроенного MCP-сервера (`npx @perplexity-ai/mcp-server`), ограничения по ресурсам и безопасности, чек-лист для ops.
- [x] Обновить `docs/architecture/flow-definition.md`, `docs/infra.md`, `docs/processes.md` и ADR: схема интеграции, поток данных, сценарии использования (flow research, chat research, summary enrichment), SLA и runbook.

## Wave 14 — Typed blueprint runtime polish
### Backend
 - [x] Заменить `JsonNode` в `FlowBlueprintStep` на типизированные value-объекты (`FlowStepOverrides`, `FlowInteractionDraft`, `FlowMemoryReadDraft`, `FlowMemoryWriteDraft`, `FlowStepTransitionsDraft`), обновить `FlowDefinitionParser`/`FlowBlueprintCompiler` под новую модель и покрыть round-trip тестами сериализации.
- [x] Применять memory-политику blueprint: при запуске сессии создавать каналы по `memory.sharedChannels`, прокидывать retention-настройки в `FlowMemoryService` и добавлять интеграционные проверки сохранения/очистки истории.
- [x] Добавить валидацию `schemaVersion` при сохранении флоу: проверять совместимость, отклонять неподлежащее схеме значение и логировать предупреждения в телеметрию.
- [x] Обновить `FlowBlueprintValidator` и `FlowBlueprintCompiler`, чтобы работать с новыми DTO без ручного JSON, и внедрить централизованный набор ошибок/предупреждений.
- [x] Расширить `FlowDefinitionParser`/`FlowDefinitionDocument`, чтобы прокидывать `memory.sharedChannels` и retention-политику до уровня оркестратора/инвокеров.
- [x] Параметризовать `FlowMemoryService` и все вызовы `append(...)` под blueprint-retention (оркестратор, интеракции, ручные записи).
- [x] Автоматизировать управление `schemaVersion`: выставлять целевую версию при сохранении/публикации, мигрировать текущие `flow_definition`/`history` записи и синхронизировать с историей.

### Frontend
- [x] Починить загрузку flow definition в UI: скорректировать zod-схемы/адаптеры, чтобы поддерживать пустые `memoryReads` и числовые `maxAttempts`, и добавить fallback для старого формата (`frontend/src/lib/types/flowDefinition.ts`, `frontend/src/lib/apiClient.ts`).
- [x] Расширить отображение формы так, чтобы некорректные данные подсвечивались inline, а ошибки сериализации детализировались в toast/логах (`frontend/src/pages/FlowDefinitions.tsx`).

### Тесты и документация
- [x] Расширить unit/integration тесты (`FlowBlueprintValidator`, `FlowDefinitionController`, `FlowMemoryService`) под новые DTO и memory-политику.
- [x] Обновить `docs/architecture/flow-definition.md` и `docs/infra.md` описанием typed step-модели, ретеншена каналов и правил обновления `schemaVersion`.
- [x] Добавить отдельные unit-тесты `FlowBlueprintValidator` на ветки ошибок (неизвестные агенты, конфликт версий, невалидные переходы).
- [x] Реализовать интеграционный тест `FlowMemoryService` (retention/cleanup) и e2e сценарий публикации флоу с кастомными каналами памяти.
- [x] Дополнить frontend-vitest покрытие: проверить serialise/deserialize адаптеров на typed step DTO и memory retention.

## Wave 15 — MCP Research Testing & Stability
### Backend
- [ ] Обновить unit-тесты (`AgentInvocationServiceTest` и др.) с поддержкой `McpToolBindingService`, покрыть передачу tool callbacks и tool codes.
- [ ] Добавить stub-конфигурацию (`SyncMcpToolCallbackProvider`) для интеграционных тестов без запуска STDIO.
- [ ] Интеграционные тесты `SyncChatService`/`StructuredSyncService` в режиме `research` (инструменты, structured payload, сохранение сообщений).
- [ ] Интеграционный сценарий оркестратора (AgentInvocationService / flow) с research-режимом и валидацией `selectedTools`.
- [ ] Smoke-тест health-indicator `perplexityMcp`.

### Test Infrastructure
- [ ] Вспомогательные утилиты для сборки `ToolCallback`/`ToolDefinition`, настройка Gradle-профиля под stub MCP.

### Observability
- [ ] Проверки метрик `perplexity_mcp_latency`/`perplexity_mcp_errors_total` в тестовом окружении (SimpleMeterRegistry).

### Документация
- [ ] Обновить `docs/processes.md` с требованиями к тестированию research-режима и stub MCP.

## Wave 16 — Spring AI MCP servers
Цель: построить и внедрить собственные STDIO MCP-серверы на Spring AI, чтобы расширить набор инструментов для оркестратора, операторов и аналитики без зависимости от внешних провайдеров.

### Кандидаты MCP
- `flow-ops-mcp` — доступ к `FlowDefinitionService`, `FlowBlueprintValidator` и пайплайну публикации: просмотр, сравнение версий, форс-валидация и запуск публикаций/rollback.
- `insight-mcp` — аналитика чатов/флоу: чтение summary из `ChatMemoryService` и `FlowMemoryService`, поиск конверсаций, метрики latency/ошибок из `Telemetry`.
- `agent-ops-mcp` — self-service для `AgentCatalogService`/`AgentConstructorService`: регистрация новых агентов, изменение статусов, клонирование конфигураций и диагностика зависимостей инструментов.

### Backend
- [x] Создать отдельный Gradle-модуль `backend-mcp` (Spring Boot 3 + `spring-ai-mcp-spring-boot-starter`) с STDIO-лаунчером и Dockerfile.
- [x] Реализовать `flow-ops-mcp`: инструменты `list_flows`, `diff_flow_version`, `validate_blueprint`, `publish_flow`, `rollback_flow` с обращением к существующим сервисам и аудитом через `ChatLoggingSupport`.
- [x] Реализовать `insight-mcp`: инструменты `recent_sessions`, `fetch_summary`, `search_memory`, `fetch_metrics` c использованием `FlowMemoryService`, `ChatMemoryService`, `TelemetryService`.
- [x] Реализовать `agent-ops-mcp`: стартовая итерация — `list_agents`, `register_agent`, `preview_dependencies` (валидация `ToolBinding`, structured ответы для UI); последующие итерации — `clone_agent`, `update_status`, расширенные проверки.
- [x] Интегрировать `backend-mcp` с основным `backend`-приложением: регистрация STDIO-клиентов, health-indicator'ы, security policy и wiring новых `ToolSchemaVersion` в `McpToolBindingService`.
- [x] Рефакторинг perplexity-специфичных сервисов (`ChatResearchToolBindingService`, `PerplexityMcpHealthIndicator`, payload overrides) в обобщённую MCP-инфраструктуру с выбором сервера, метриками по тегам и поддержкой разных наборов инструментов.
- [x] Настроить конфигурацию `spring.ai.mcp.client` для подключения новых STDIO-серверов, обновить `McpToolBindingService` и биндить новые `ToolSchemaVersion`.
- [x] Добавить Liquibase-миграции для схем/описаний инструментов (`tool_schema_version`, `tool_definition`) и seed-агентов по аналогии с `perplexity_search`/`perplexity_deep_research`.
- [x] Обновить `AgentCatalogService` и связанные DTO, чтобы шаблоны агентов поддерживали новые MCP-инструменты, конфигурацию overrides и capability payload'ы.
- [x] Расширить `ChatInteractionMode`/`ChatSyncRequest`/`ChatStreamRequest`, чтобы UI мог запрашивать конкретные MCP toolsets (`requestedToolCodes`), и прокинуть их до `AgentInvocationService`.
- [ ] Завершить канал обновлений health (`/api/mcp/events` SSE): добавить `latencyMs` в payload и реализовать fallback `GET /api/mcp/health`.
- [x] Перевести `backend-mcp` сервисы с STDIO на потоковый HTTP MCP: включить WebFlux приложение (`spring.main.web-application-type`), реализовать HTTP эндпоинты `stream`/`call` через `spring-ai-mcp-server` с SSE/ndjson выдачей, удалить STDIO launcher'ы/скрипты, обновить профили `application-*.yaml` и health-индикаторы, добавить smoke-тесты WebTestClient на handshake/stream (Perplexity остаётся STDIO).
- [x] Адаптировать backend под смешанный MCP транспорт: оставить `spring.ai.mcp.client.stdio` для Perplexity, добавить `streamable-http.connections` для внутренних серверов, обновить `McpToolBindingService`/health-индикаторы и Liquibase-модификации, покрыть unit/integration тестами.
- [ ] Актуализировать предупреждения логгера MCP: убрать упоминание только Perplexity в `McpToolBindingService`.

### Frontend
- [x] Расширить chat UI для подключения MCP-инструментов: мультивыбор серверов (Perplexity, Agent Ops и др.), отображение статуса health и отправка `requestedToolCodes` вместе с режимом чата.
- [x] Отрисовывать в карточках ответа бейджи активных MCP-инструментов, раскрывать structured payload (ID агента, ссылки на flow и т.п.), обрабатывать ошибки STDIO с подсказками пользователю.
- [x] Обновить API-клиент: поддержать новые поля (инструменты MCP, статусы доступности, capability hints) в DTO чата и агентов, кешировать выбор на время сессии.
- [x] Интегрировать SSE-поток/периодический polling health: обновление UI-индикаторов и отключение недоступных MCP-инструментов в режиме реального времени.

- [x] Покрыть MCP-сервера unit- и integration-тестами: handshake, списки инструментов, negative-case для валидации и отсутствующих записей.
- [x] Добавить end-to-end тесты `AgentInvocationService`/`FlowOrchestrator` с использованием новых MCP-инструментов через STDIO stub.
- [x] Расширить `SyncMcpToolCallbackProvider` stub/тестовую конфигурацию на несколько серверов, покрыть health-indicator'ы и backward-совместимость с `perplexity` сценариями.
- [ ] Обновить smoke/contract тесты UI и API, чтобы проверять выбор MCP-инструментов, передачи `requestedToolCodes` и обработку деградаций HTTP MCP (timeouts, health down).
- [ ] Добавить backend smoke/contract тесты для `/api/mcp/catalog`, SSE `/api/mcp/events` с `latencyMs` и сценариев выбора MCP-инструментов.
- [ ] Расширить тестовое покрытие под HTTP MCP: WebTestClient smoke для `backend-mcp` HTTP эндпоинтов, интеграции backend конфигурации (HTTP-only) и e2e сценарии stream/fallback с проверкой деградаций.

### Infrastructure & Ops
- [x] Добавить сервисы MCP в `docker-compose.yml`, описать переменные окружения (`FLOW_MCP_*`, `INSIGHT_MCP_*`, `AGENT_OPS_MCP_*`) и healthchecks.
- [ ] Перенастроить метрики (`*_mcp_latency`, `*_mcp_errors_total`) на динамические теги, привязать их к каждому MCP-серверу и добавить отдельные actuator health endpoints.
- [x] Обновить `docker-compose.yml` и деплой пайплайны под HTTP MCP: выделить порты для `agent-ops`/`flow-ops`/`insight`, прокинуть `*_MCP_HTTP_BASE_URL` и `*_MCP_TRANSPORT`, перенастроить healthcheck на HTTP эндпоинты и убрать STDIO wrapper'ы/переменные.

- [x] Обновить `docs/architecture/flow-definition.md`, `docs/infra.md`, `docs/processes.md` описанием новых MCP-серверов, доступных инструментов и сценариев (flow ops, agent ops, observability).
- [x] Добавить гайды для операторов/аналитиков: как подключить MCP к IDE/клиенту, пример диалогов и ограничения по безопасности.
- [x] Переписать текущий раздел про Perplexity MCP в `docs/infra.md` на общую платформу MCP, описать запуск собственных серверов, метрики, health-checkи и режим работы stdio.
- [ ] Обновить документацию под HTTP MCP: `docs/infra.md`, `docs/guides/mcp-operators.md`, `docs/processes.md` и README с новыми переменными окружения, схемой backend ⇔ MCP (HTTP-only) и сценариями миграции/отката без STDIO.

## Wave 17 — GitHub MCP интеграция
### MCP Server
- [x] Реализовать GitHub MCP сервер в `backend-mcp`: настроить пат-токен, клиент GitHub REST/GraphQL, конфигурацию профилей и health endpoints на базе `org.kohsuke:github-api` (актуальная стабильная версия 1.330, перед внедрением перепроверить).
- [x] Настроить аутентификацию GitHub MCP через Personal Access Token (scopes `repo`, `read:org`, `read:checks`) и обновить конфигурацию.
- [x] Добавить методы `github.list_repository_tree` и `github.read_file`: выбор репозитория/ветки, выдача структуры и содержимого файлов с лимитами, кешированием и нормализацией кодировок.
- [x] Добавить инструменты `github.list_pull_requests`, `github.get_pull_request`, `github.get_pull_request_diff`, `github.list_pull_request_comments`, `github.list_pull_request_checks`: поддержать фильтры, отдачу diff/metadata, комментарии и статусы проверок.
- [x] Реализовать инструменты `github.create_pull_request_comment`, `github.create_pull_request_review` и `github.submit_pull_request_review`: публикация комментариев, выставление `APPROVE`/`REQUEST_CHANGES`, обработка rate limit и идемпотентности.
- [x] Интегрировать сервер `github` в `McpCatalogService`/конфигурацию backend: поддержать выбор инструмента, обновить health-checkи и регистрацию capability hints.
- [x] Добавить профиль `github` в `McpApplication`, компонент-скан пакета GitHub и `@ConfigurationProperties` для GitHub API (базовый `https://api.github.com`, таймауты, headers).
- [x] Создать конфигурацию `application-github.yaml`: MCP server (endpoint `/mcp`, keep-alive), параметры токен-менеджера (lifetime, cache TTL) и инструкции/description.
- [x] Подключить `org.kohsuke:github-api` и нужные HTTP/ratelimit зависимости в `backend-mcp/build.gradle`, зафиксировать версию и возможные exclude.
- [x] Упростить токен-менеджер до работы с PAT без JWT/installation токенов.

### Backend & LLM
- [x] Реализовать стартовый агент/шаг flow для резолва GitHub URL (repo/branch/PR): через LLM определяет намерение пользователя, валидирует контракт следующего агентa, при необходимости запрашивает уточнение (WAITING_USER_INPUT), парсит ссылку, нормализует owner/repo/ref, определяет тип цели (repo vs PR) и записывает `githubTarget` в `sharedContext.current`.
- [x] Обновить Liquibase: зарегистрировать сервер `github`, инструменты и flow `github-analysis-flow` с capability hints.

### Security & Governance
- [x] Задокументировать процесс выпуска Personal Access Token (scopes `repo`, `read:org`, `read:checks`), владельцев и SLA ротации (GitHub App больше не используется).
- [x] Настроить хранение PAT в `.env`/секретах `docker-compose`/CI, описать процедуру ротации и оповещения.

### Infrastructure & Ops
- [x] Добавить переменные окружения GitHub (`GITHUB_PAT`) в `.env` / `.env.example` и прокинуть их в `docker-compose.yml`.
- [x] Добавить сервис GitHub MCP в `docker-compose.yml`: образ `backend-mcp`, `SPRING_PROFILES_ACTIVE=github`, порт, токены из `.env`, healthcheck по аналогии с остальными MCP.
- [x] Дополнить документацию/README: сборка образа GitHub MCP, запуск `docker compose up github-mcp`, переменные окружения, логирование.

## Wave 18 — Универсализация MCP-инструментов
### Backend
- [ ] Ослабить зависимость `McpToolBindingService` от каталога: разрешить выполнение инструментов, которые отсутствуют в `ToolDefinitionRepository`, если MCP-провайдер вернул валидный `ToolCallback`.
- [ ] Перенести логику подстановки `query`/`messages`/доп. полей из Java-кода в метаданные схемы/overrides, избавиться от `if`-веток по конкретным названиям инструментов.
- [ ] Удалить специальную нормализацию/обогащение payload для Perplexity (`perplexity_search`, `perplexity_research`) из `McpToolBindingService`, заменить на конфигурируемые overrides на стороне схемы.
- [ ] Поддержать произвольные транспорты MCP (например, WebSocket/SSE) через конфигурацию, а не жёсткий whitelist `http-stream`/`stdio`.
- [ ] Разрешить API задавать собственный `query` и не отбрасывать его в `QueryOverridingToolCallback`, оставить только защиту от `locale`.
- [ ] Смягчить GitHub-валидацию: логировать проблемы payload'а, но не блокировать вызовы при наличии дополнительных полей и необязательных секций.
- [ ] Перестать требовать непустой `userQuery` для резолва инструментов, корректно обрабатывать action-инструменты без текстового запроса.

### Docs & Enablement
- [ ] Обновить разделы MCP в архитектурной документации: описать новый механизм кастомизации payload'ов, поддержку дополнительных транспортов и правила overrides.
- [ ] Зафиксировать изменения в Wave 18 changelog: свобода выбора инструментов из API, расширяемые транспорты, мягкая валидация GitHub payload'ов.

## Wave 19 — GitHub MCP анализ и покрытие
### Backend & LLM
- [ ] Запускать `github-analysis-flow` через существующий flow-оркестратор: шаги получают контекст резолвера, вызывают GitHub MCP (tree/file/diff) и агрегируют статические проверки + LLM summary/риски.
- [ ] Добавить flow/agent pipeline `github-analysis-flow`: старт по URL, шаги «fetch tree», «prefetch files», «LLM repo summary», «LLM risk scoring», «generate recommendations», сохранение результатов и structured payload.
- [ ] Добавить пайплайн анализа pull request: сбор diff/metadata через GitHub MCP, подготовка промптов, генерация рекомендаций LLM и вычисление сигналов (компоненты, TODO, риски).
- [ ] Обновить `AgentInvocationService`/flow сценарии для вызова GitHub MCP методов, маршрутизации ссылок (репо vs PR) и последующей обработки результатов LLM.
- [ ] Определить канонический payload для LLM анализа: список файлов с diff (unified), агрегированные метрики, summary вводных и сигналов, сохранить схему в shared context и документации.

### Security & Governance
- [ ] Добавить детализированные логи (без отдельного аудита) для действий MCP: вызовы методов, создание комментариев, approve/request changes, обработка rate limit.

### Tests & QA
- [ ] Покрыть GitHub MCP unit/integration тестами: handshake, tree/file чтение, pull request инструменты (list/get/diff/comments/checks) и действия (create comment/review/submit) с mock GitHub API.
- [ ] Протестировать управление PAT: корректное чтение из секретов, обновление без рестарта и обработка просроченного токена.
- [ ] Unit/интеграционные тесты стартового резолвера: валидные репо/PR ссылки, невалидные входы, ветка WAITING_USER_INPUT и запись `githubTarget` в shared context.
- [ ] Добавить backend-тесты `github-analysis-flow`: сценарии по ссылке на репо/ветку/PR, корректность шагов, агрегирование LLM summary/рисков, идемпотентность по URL и переиспользование данных из shared context.
- [ ] Обновить unit/интеграционные тесты каталога и binding: ожидать сервер `github`, проверять sanitize и статусы доступности.
- [ ] Unit-тесты ручной сборки unified diff: заголовки `diff --git`, `index`, `@@` соответствуют patch'ам GitHub API.

### Docs & Enablement
- [ ] Обновить `docs/guides/mcp-operators.md`/`docs/infra.md` описанием GitHub MCP, требуемых прав и сценариев использования.
- [ ] Документировать контракт payload'ов (`repository/ref/requestId/pullRequest/location`), ограничения (только github.com, без fallback) и требования к токенам.


## Wave 20 — UX для GitHub Analysis Flow
Цель: довести GitHub Analysis Flow до полноценного пользовательского опыта: запуск из чата и Flow каталога, понятные статусы, детализированная карточка результата и стабильная интеграция с MCP-инструментами GitHub.

### Backend & LLM
- [ ] Доработать `McpToolBindingService`/`GitHubResolverService`: парсинг ссылок на репо/ветку/PR, заполнение `repository/ref/pullRequest`, генерация `requestId`, формирование unified diff (`diff --git`/`@@`) и прокидка лимитов по размеру/файлам.
- [ ] Расширить `AgentOrchestratorService`/`FlowInteractionService`: потоковые статусы `fetch_metadata` → `fetch_tree` → `analysis` → `recommendations`, повторное использование данных при рестарте, graceful деградация при недоступности GitHub MCP.
- [ ] Обновить сохранение результатов анализа: структурированный payload (summary, risks, рекомендации, ссылки, метрики, артефакты), идемпотентность по URL/PR, история запусков и возможность повторного просмотра.
- [ ] Добавить в backend DTO и OpenAPI-схемы для новых полей (`analysisStatus`, `retries`, `recommendationBlocks`, `actions`) и обеспечить совместимость с существующими клиентами.

### Frontend & UX
- [ ] Обновить панель MCP: отдельный блок GitHub сервера с типизированными полями (URL, ref/PR, режим анализа), подсказками по формату и кнопкой запуска `github-analysis-flow`.
- [ ] В чате отобразить прогресс `github-analysis-flow`: шаги, статусы, ошибки/ретраи, возможность перезапуска и отображение времени этапов без переключения на FlowSessions.
- [ ] Реализовать карточку результата: summary по анализу, риски/рекомендации, список файлов с подсветкой изменений, ссылки на PR/комментарии, действия approve/request changes.
- [ ] Расширить фронтенд API-клиент/DTO: новые поля payload (`summary`, `risks`, `recommendations`, `files`, `metrics`, `links`, `reviewStatus`, `stepProgress`), типы для статусов и временных метрик.
- [ ] Добавить UI-тесты/e2e: запуск анализа из чата и панели, отображение прогресса, публикация комментария и approve/request changes через GitHub MCP.

### Agents & Flow
- [x] Создать агента `github-analysis-runner`, оборачивающего GitHub MCP инструменты анализа: системная подсказка с описанием шагов, требуемых параметров и ограничений.
- [ ] Обновить `github-analysis-flow` в каталоге: подробное описание, типизированный ввод (repo URL, target type `branch|pull_request`, optional commit/ref, глубина анализа), требования к подготовке данных.
- [ ] Настроить чат/flow интеграцию: свободный ввод пользователя → заполнение параметров flow → запуск → отображение (reuse компонентов Wave 21).

### Observability & QA
- [ ] Логировать метрики по этапам анализа (fetch tree, diff build, LLM analysis, рекомендаций), коды возврата GitHub MCP, количество файлов, время на обработку, ретраи.
- [ ] Написать интеграционные тесты backend: успешный анализ PR, пустой diff, слишком большой diff, невалидная ссылка, повторный запуск с кешированием, деградация MCP.
- [ ] Обновить Flow e2e сценарии и контракты UI: прогон через chat/панель, проверка отображения статусов, действий approve/request changes, graceful handling ошибок.

### Документация & Enablement
- [ ] Обновить `docs/guides/mcp-operators.md`, `docs/infra.md`, `docs/processes.md`: структура нового UX, описание flow, параметры ввода, ограничения по GitHub MCP, порядок запуска и восстановления.
- [ ] Добавить changelog/enablement заметку: сценарии использования, подготовка PAT, лимиты, рекомендации по проверке результатов и откату.

## Wave 21 — Spring AI MCP tool для GitHub Gradle тестов
Цель: предоставить набор MCP-инструментов, которые совместно выполняют Gradle-тесты для GitHub-проектов в изолированном Docker-окружении. Ключевые требования: **никаких запусков Gradle на хосте**, независимость инструментов (GitHub-операции отдельно, Docker-раннер отдельно) и чистое разделение ответственности на уровне конфигурации и оркестрации.

### GitHub Integration Tool
- [x] Расширить `GitHubRepositoryService`/`GitHubTools` и зарегистрировать самостоятельный MCP-инструмент `github_repository_fetch` в том же GitHub MCP-сервере, что и текущие интеграции: вход — repo/ref/checkout strategy, выход — ссылка на подготовленный архив/каталог в локальном storage. Зафиксировать DTO запроса/ответа и описание в `@Tool` (назначение, ограничения, примеры).
- [x] Обновить `GitHubRepositoryService` методами `fetchRepositoryTo(Path target, GitFetchOptions)` с поддержкой archive download (ZIP/TAR), fallback на git clone при необходимости сабмодулей/LFS, реиспользуя PAT из конфигурации.
- [x] Добавить ограничители для GitHub-интеграции (максимальный размер архива, таймаут загрузки, маскирование токена) и заголовки источника (`repo/ref/commitSha`) с сохранением метаданных в промежуточном хранилище (`TempWorkspaceService`).
- [x] Реализовать `TempWorkspaceService`, который создаёт изолированные временные директории (`Files.createTempDirectory` с префиксом, ограничением размера и TTL в 24 часа), хранит метаданные (`workspaceId`, `repo/ref`, `requestId`), использует общий tmp root (`/var/tmp/aiadvent/mcp-workspaces`) и гарантирует очистку по TTL или по расписанию.
- [x] Возвращать из инструмента расширенный ответ: `workspaceId`, абсолютный путь, размер выгрузки, время скачивания, commit SHA, список ключевых файлов; фиксировать события/метрики (`download_time_ms`, `download_size_bytes`, ошибки таймаута/лимита`).

### Workspace Inspector Tool
- [x] Создать отдельный MCP-инструмент `workspace_directory_inspector` (в GitHub MCP-сервере): вход — `workspaceId`, опциональные фильтры (glob-паттерны, глубина, типы файлов), выход — список директорий/файлов с метаданными (`path`, `type`, `size`, `detectedProjectType`, `hasGradleWrapper` и т.д.).
- [x] Реализовать сервис обхода workspace: защищённая навигация внутри `/var/tmp/aiadvent/mcp-workspaces/{id}`, фильтрация по glob/regex, определение Gradle-проектов (`settings.gradle`, `build.gradle`, `gradlew`), детектирование других типов (Maven, npm и т.п.) без привязки к Gradle.
- [x] Возвращать детализированный ответ: массив найденных элементов, флаги (`containsMultipleGradleProjects`, `recommendedProjectPath`), предупреждения (превышение глубины/количества результатов), время выполнения. Логировать метрики (`inspection_time_ms`, количество элементов, ошибки доступа).

### Docker Runner Tool
- [x] Поднять отдельный MCP-сервер `docker-runner`, зарегистрировать на нём инструмент `docker_gradle_runner` и описать схему запроса/ответа (workspaceId/path, tasks, env, таймауты, exitCode, stdout/stderr, артефакты).
- [x] Реализовать инструмент: принимать workspace из GitHub fetcher, проверять принадлежность каталога `/var/tmp/aiadvent/mcp-workspaces/{id}`, формировать команду `docker run --rm -v <workspace>:/workspace -w /workspace <image> ./gradlew <tasks>` с fallback на `gradle` при отсутствии wrapper, возвращать статус + логи.
- [x] Настроить упаковку workspace: монтирование временного каталога на контейнер (`-v tempDir:/workspace`), выделенный volume для Gradle cache (`-v /var/tmp/aiadvent/gradle-cache:/gradle-cache`), конфиг `GRADLE_USER_HOME`, лимиты по CPU/RAM/диску и таймаут выполнения.
- [x] Подготовить Docker-образ `aiadvent/mcp-gradle-runner` (каталог `backend-mcp/docker/`): слои JDK 21, Gradle 8.x, утилиты диагностики, entrypoint-скрипт, публикация через CI.
- [x] Добавить потоковое чтение stdout/stderr (chunked события MCP), обрезку логов по размеру и маскирование секретов; управление lifecycle workspace оставить за `TempWorkspaceService` (cleanup по TTL).
- [x] Поддержать параметр `projectPath` (относительный путь внутри workspace) и валидацию: runner должен запускать Gradle из выбранного каталога, а при отсутствии пути — использовать root проекта, определённый fetch-инструментом (если единственный).

### Backend Integration
- [x] Зарегистрировать новые инструменты в основном backend (`McpCatalogService`, `McpToolBindingService`): выдать им уникальные codes, добавить детальные описания прямо в Spring MCP-аннотациях (назначение, вход/выход, ограничения) и схемы входа/выхода, разрешить выбор через UI/flows.
- [x] Обновить `AgentOrchestratorService`/flow сценарии: обеспечить доступ к обоим инструментам, прокидывать `requestId`/workspace между вызовами и описать порядок использования в подсказках LLM.

### Agents & Flow
- [x] Создать специализированного агента `repo-fetcher`, оборачивающего `github_repository_fetch`: системная подсказка описывает необходимые входные параметры (repo URL, ref, strategy), ожидаемые артефакты (`workspaceId`, метаданные) и ошибки.
- [x] Создать специализированного агента `gradle-test-runner`, который оборачивает только `docker_gradle_runner`: системная подсказка описывает входные аргументы (workspaceId/projectPath/tasks/env), валидацию данных и формат ответа.
- [x] Создать агента `workspace-navigator`, отвечающего за вызовы `workspace_directory_inspector` и помощь пользователю в выборе `projectPath` (вопросы для уточнения, вывод списка найденных проектов).
- [x] Сконструировать новый flow `github-gradle-test-flow` со свободным вводом: сбор параметров (repo/ref/tasks), последовательный запуск инструментов (`github_repository_fetch` → `workspace_directory_inspector` → `docker_gradle_runner`) и отображение итогового отчёта.

## Wave 22 — Завершение Gradle MCP инструментов
Цель: завершить документацию, тестирование и операционную готовность Gradle MCP пайплайна, подготовить flow к использованию операторами.

### Требования & Архитектура
- [ ] Зафиксировать архитектуру в `docs/architecture/mcp-gradle.md`: поток `fetch → package → docker-run → отчёт`, контракты между инструментами, последовательность статусов, координацию на стороне LLM/flow.

### Workspace Inspector Tool
- [ ] Обновить документацию и каталоги MCP: описать инструмент, привести примеры запросов (поиск Gradle, поиск произвольных файлов), уточнить порядок использования (fetch → inspect → docker run) для много-репозиторных сценариев.

### Docker Runner Tool
- [ ] Написать интеграционный тест `DockerMcpApplicationTests`: поднять временный workspace с Gradle-проектом, выполнить `docker_gradle_runner`, убедиться в корректной передаче логов, статуса и таймаутов.

### Agents & Flow
- [ ] Написать e2e сценарий flow: пользователь вводит repo/ref, flow вызывает инструменты в нужном порядке, отображаются промежуточные статусы и финальный отчёт.
- [ ] Документировать использование агентов и flow в `docs/guides/mcp-operators.md`: примеры диалогов, порядок вызовов, ограничения и рекомендации.

### Observability & QA
- [ ] Логировать метрики по каждому инструменту отдельно (GitHub fetch, Workspace inspector, Docker run) с корреляцией через `requestId`: время, объём данных, коды возврата, ошибки.
- [ ] Написать интеграционные тесты: успешный прогон sample Gradle-проекта, ошибка сборки в Docker, таймаут контейнера, несуществующий репозиторий, превышение лимита размера архива.
- [ ] Обновить Flow e2e сценарии: проверка отображения промежуточных статусов (`fetching`, `inspecting_workspace`, `running_tests`), координация последовательных вызовов инструментов на стороне flow и graceful degradation при сбоях.

### Документация & Enablement
- [ ] Обновить `docs/guides/mcp-operators.md` и `docs/infra.md`: описать новые инструменты, их независимость, параметры Docker-окружения и требования к PAT.
- [ ] Дополнить документацию детальными карточками каждого инструмента (назначение, поля входа/выхода, типичные сценарии, ограничения) и примером JSON-вызовов, чтобы LLM/операторы могли корректно их использовать.

## Wave 23 — Расширенный анализ скачанных репозиториев
Цель: поставить `repo_analysis_mcp` для офлайн-обработки workspace, включая устойчивый обход и агрегирование результатов без вмешательства оператора.

### MCP Codebase Analyzer
- [x] Спроектировать `repo_analysis_mcp`: определить стратегии обхода больших проектов (итеративное чтение, фильтрация бинарей, управление глубиной) и формат накопления находок с учётом лимитов контекста LLM.
- [x] Реализовать инструменты `scan_next_segment`, `aggregate_findings`, `list_hotspots`: обеспечить сохранение состояния между вызовами, выдачу кратких резюме и ссылок на файлы/строки.
- [x] Работать поверх локального workspace, подготовленного `github_mcp` (`github_repository_fetch`): принимать `workspaceId`/`projectPath`, проверять наличие каталога и избегать сетевых операций.
- [x] Добавить конфигурацию порогов (размер файла, типы артефактов), поддержку исключений `.mcpignore` и механизмы повторного запуска с чекпойнта.
- [x] Покрыть модульными/интеграционными тестами: прогон на малом репо, сценарий превышения лимита, сброс состояния анализа.
- [x] Добавить MCP-инструмент `workspace.read_file` для чтения файлов из скачанного workspace с ограничением размера и поддержкой бинарных данных.
- [x] Интегрировать `repo_analysis_mcp` с основным backend: передать инструменты в оркестратор, организовать доступ к состоянию и метрикам.

## Wave 24 — Расширенные MCP-инструменты: ассистент и GitHub
Цель: замкнуть assisted-coding цикл — LLM формирует патчи поверх локального workspace, оператор подтверждает dry-run и публикует изменения в GitHub с ручными подтверждениями, аудитом и метриками.

### MCP Coding Assistant
- [x] Провести desk-research через Perplexity: собрать MCP/AGI-инструменты патч-генерации, оценить API/лицензии, подготовить shortlist reuse-кандидатов и зафиксировать ограничения.
- [x] Подготовить RFC (`reuse` vs собственный `code_patch_mcp`): целевой UX (чат/flow), обязанности сервисов, сценарии безопасности/отката, политика подтверждений и лимиты объёмов.
- [x] Поднять профиль `coding` в `backend-mcp`: подключить `@ComponentScan`/`@EnableConfigurationProperties`, переиспользовать `TempWorkspaceService`, добавить сервис `coding-mcp` в docker-compose и env.
- [x] Оценить интеграцию `WorkspaceAccessService`: вынесли чтение файлов в общий `WorkspaceFileService`, который переиспользуют `github` и `coding` профили.
- [x] Реализовать in-memory `PatchRegistry` с TTL: связка `patchId↔workspaceId`, статусы (`generated|applied|discarded`), атрибуты `requiresManualReview`, `hasDryRun`, переиспользовать `TempWorkspaceService`.
- [x] Реализовать инструменты:
  - [x] `coding.generate_patch` — валидация путей, лимиты diff ≤ 256 КБ и ≤ 25 файлов, сохранение diff/summary/annotations в `PatchRegistry`.
  - [x] `coding.review_patch` — поддержка фокусов (`risks|tests|migration`), проверка статуса патча, возврат рекомендаций и следующего шага.
  - [x] `coding.apply_patch_preview` — инструмент `MANUAL`: `git apply` внутри workspace, dry-run через `DockerRunnerService` (whitelist Gradle/npm/pytest), маскирование логов, base64 для бинарных артефактов.
  - [x] Настроить валидации и ограничения: запрет абсолютных путей, ограничение контекстных чтений `WorkspaceAccessService`, лимиты prompt/completion, блокировка не-whitelisted команд.
  - [x] Формировать ответы с аннотациями (modified files, конфликтные hunks, оценка риска, usage), отражать статус dry-run и рекомендации.
  - [x] Отправлять метрики и аудит: `coding_patch_attempt_total`, `coding_patch_success_total`, `coding_patch_compile_fail_total`, структурированные логи без секретов.
- [x] Зарегистрировать инструменты в MCP-каталоге (Liquibase), указать `execution-mode=MANUAL` для `coding.apply_patch_preview`.
- [x] Подключить инструменты к backend (chat/flow): bindings, ручные подтверждения dry-run в UI/Telegram, разблокировка GitHub write-инструментов после подтверждения.
- [x] Покрыть тестами: unit (валидации, Registry), интеграция (generate→review→apply с dry-run/timeout/invalid diff), smoke (apply без dry-run).
- [x] Обновить документацию (`docs/guides/mcp-operators.md`, `docs/infra.md`): UX assisted coding, политика подтверждений, лимиты, troubleshooting.
- [x] (Post-MVP) Зафиксировать roadmap-пункты `coding.list_patches` / `coding.discard_patch` для диагностики активных патчей и ручного сброса.

### GitHub MCP Expansion
- [x] Реализовать write-операции в `GitHubRepositoryService`:
  - `createBranch` — whitelist branchName, проверка существующей ветки и прав записи.
  - `commitWorkspaceDiff` — формирование commit из diff workspace, отклонение пустого diff и превышения лимитов.
  - `pushBranch` — запрет `force`, проверка конфликтов и размера.
  - `openPullRequest` — sanity-check head/base, лимит diff, возврат `prNumber`, `headSha`, `baseSha`.
  - `approvePullRequest` — review `APPROVE` сервисным аккаунтом.
  - `mergePullRequest` — проверка статуса CI, поддержка `squash|rebase|merge`.
- [x] Экспортировать инструменты через `GitHubTools`/`GitHubWorkspaceTools`, нормализовать ответы (SHA, ссылки, статусы, vetos), настроить ручные подтверждения.
- [x] Добавить структурированные логи и аудит write-операций; усиленные ограничения (лимиты архивов, маскирование токенов, rollback) вынести в отдельный backlog.
- [x] Обновить MCP-каталог и backend (`app.mcp.catalog`, `app.chat.research.tools`): `execution-mode=MANUAL` для write-операций, разблокировка после dry-run.
- [x] Протестировать e2e: sandbox-репозиторий ветка → commit → push → PR → approve → merge; негативы (конфликт, запрет force-push, veto).
- [x] Unit-тесты: сериализация запросов, обработка ошибок GitHub API, откат workspace при исключениях.
- [x] Документация (`docs/guides/mcp-operators.md`, `docs/infra.md`): чек-лист безопасного использования, примеры JSON, сценарии sandbox.

### Assisted Coding Flow (FE/TG → MCP)
1. Пользователь задаёт репозиторий/задачу → `github.repository_fetch` + `github.workspace_directory_inspector` готовят workspace.
2. `coding.generate_patch` возвращает diff и summary; по запросу `coding.review_patch` подсвечивает риски/tests/migrations.
3. Оператор вручную подтверждает `coding.apply_patch_preview` (dry-run через Docker по whitelist-командам, доступно только при явном запросе).
4. После успешного dry-run или явного решения пропустить его разблокируются `github.create_branch` → `github.commit_workspace_diff` → `github.push_branch` (каждый шаг требует отдельного подтверждения).
5. `github.open_pull_request` создаёт PR; при необходимости подключаются анализаторы (`github.get_pull_request_diff/comments/checks`), а `github.approve_pull_request` и `github.merge_pull_request` доступны только после ручного подтверждения и проверки статуса проверок.
6. Результаты ревью фиксируются через `github.set_pr_status` / `github.raise_veto`; цикл повторяется до merge.

## Wave 26 — Комплексный аудит скачанных репозиториев
### Repo Analysis Service
- [ ] Реализовать предсканирование файлов: сохранять в `RepoAnalysisState.FileCursor` размеры, время модификации, принадлежность к типу (код/тест/инфра) и вычислять приоритет очереди по весам риска вместо лексикографической сортировки.
- [ ] Добавить поддержку эвристик сложности: подсчитывать цикломатику, уровень вложенности, подозрительные SQL/коллекции и помечать сегменты тегами `performance`, `maintainability`.
- [ ] Вести хэши/сигнатуры сегментов и найденных проблем, чтобы отслеживать повторяющиеся находки между анализами и пропускать дубликаты.
- [ ] Расширить `RepoAnalysisProperties` конфигурацией весов приоритизации, лимитов на анализ per-тип файла и опцией включения расширенных метрик.

### Инструменты статического анализа и CI
- [ ] Интегрировать профили запуска lint/тест-команд: Gradle (`test`, `check`, `spotbugs`), Maven (`test`, `verify`), npm/yarn (`test`, `lint`), Go (`go test`, `go vet`), Python (`pytest`, `ruff`).
- [ ] Расширить `DockerRunnerService` поддержкой выбора образа/скрипта по типу проекта и возможностью параллельно возвращать логи/артефакты статических анализаторов.
- [ ] Добавить хранение артефактов проверки (JUnit XML, отчёты lint) рядом с workspace и публикацию ссылки в ответе инструмента.
- [ ] Настроить fallback-скрипты для проектов без известных билд-систем (например, `make test`, `./gradlew` autodetect) с тайм-аутами и метриками.

### Workspace Inspector & метаданные
- [ ] Расширить `WorkspaceInspectorService` распознаванием дополнительных типов проектов (Rust/Cargo, Python/Poetry, Go modules), сбором версии рантайма, списка ключевых зависимостей и наличия CI/CD конфигов.
- [ ] Дополнить ответ инспектора признаками инфраструктуры (Terraform, Helm, Docker Compose), миграций БД и feature flags для составления чек-листов корректности.
- [ ] Экспортировать собранные метаданные в `repo_analysis.scan_next_segment` для формирования таргетированных подсказок агенту.

### Агрегация находок и отчётность
- [ ] Обновить `RepoAnalysisService.aggregateFindings` для автоклассификации проблем в категории `security`, `correctness`, `performance`, `maintainability` и расширить `Hotspot` новыми метриками (приоритет, источник).
- [ ] При завершении анализа автоматически вызывать `repo_analysis.list_hotspots`, формировать итоговый отчёт (JSON + Markdown) и сохранять его в state с ссылкой на артефакты проверок.
- [ ] Добавить публикацию краткого статуса анализа (кол-во критических/высоких проблем, покрытие проверок) в метрики/логи MCP.

## Wave 27 — Telegram чат-бот с функционалом FE
### Принятые решения
- **Финальная доставка ответов**: для Telegram используем только sync-вызовы (`POST /api/llm/chat/sync` через `SyncChatService`), поскольку стриминг недоступен в клиенте. Бот формирует payload по `buildChatPayload`, ожидает завершения ответа и отображает финальную карточку. Для диагностики допускаем fallback-эндпоинт (фасад над stream), но он не требуется в MVP.
- **Переключение моделей**: не сохраняем выбор между обращениями, предоставляем свободный выбор провайдера/модели и добавляем явную кнопку «Новый диалог» для сброса состояния.
- **Сессии**: используем общий UUID `chat_session`, чтобы диалоги Telegram и веб-UI могли разделять историю; одна Telegram-беседа соответствует одной сессии до явного действия «Новый диалог».
- **Структурированные ответы**: отображаем Markdown-карточку с заголовком summary и таблицами `details`/`sources`; inline-кнопка «Подробнее» отправляет usage/cost отдельным сообщением, команда `/json` возвращает сырой payload при необходимости.
- **Хранение пользовательских параметров**: текущее состояние выбора модели, режима, sampling и подключённых MCP-серверов храним в памяти процесса Telegram-бота (in-memory store) с возможностью последующей миграции в Redis при горизонтальном масштабировании.
- **STT**: приоритетная обработка голосовых сообщений через OpenAI Omni (GPT-4o / GPT-4o mini omni). Допускаем fallback (например, Whisper) лишь при деградации основного канала.
- **MCP**: в Telegram доступны все MCP-сервера из каталога без дополнительных фильтров.
- **Остановка ответа**: отдельная inline-кнопка отменяет доставку текущего ответа пользователю без принудительного завершения запроса на бэкенде.
### Продуктовые сценарии и UX
- [x] Зафиксировать фичи фронтенд-чата: выбор провайдера/модели с сегментами, переключение режимов Streaming/Sync/Structured, overrides sampling-параметров, выбор MCP-серверов, отображение usage/стоимости, кнопки «Новый диалог»/остановки стрима, ссылки на Flow UI (`frontend/src/pages/LLMChat.tsx:2380`, `frontend/src/pages/LLMChat.tsx:2403`, `frontend/src/pages/LLMChat.tsx:2520`, `frontend/src/pages/LLMChat.tsx:2536`, `frontend/src/pages/LLMChat.tsx:2960`).
- [x] Описать, какие элементы UI FE переносятся в Telegram напрямую, а какие требуют адаптации (например, сегменты моделей, sampling sliders, вывод structured card), и какие данные показываем в инлайн-кнопках/сообщениях; итогом должно быть зафиксированное решение по доставке финального ответа и отображению structured payload. Выбранная модель — зеркалирование веб-UI: сегменты провайдеров, моделей, режимов и sampling повторяются в inline-клавиатуре с навигацией по меню.
- [x] Описать пользовательские сценарии Telegram-бота: onboarding, выбор модели/инструментов, отправка сообщений, получение результатов, fallback на ошибки. Принято использовать автоматический мастер (модель → режим → инструменты) перед первым запросом, с последующим переходом к обычному диалогу.
- [x] Согласовать UX-команды и команды бота (`/start`, переключатели моделей, help) и обновить спецификацию.
- [x] Спроектировать inline-меню выбора моделей, кнопку «Начать диалог заново» и UX для мультимодальных сообщений (текст + голос) в выбранной конфигурации главного меню: «Модель», «Инструменты», «Sampling», «Новый диалог», «Голос».

### Backend и интеграция с Telegram API
- [x] Настроить Telegram Bot API: регистрация бота, конфигурация токена и режима (webhook) через переменные окружения.
- [x] Подключить библиотеку `org.telegram:telegrambots` и настроить Spring-конфигурацию бота (webhook) с обработчиками апдейтов.
- [x] Реализовать сервис бота в backend: обработка апдейтов, хранение состояния пользователя (chatId → текущая сессия, выбранная модель, sampling overrides, подключённые инструменты) в памяти процесса бота по текущей FE-схеме (локальное состояние, сброс по «Новому диалогу»), с интерфейсом для будущей миграции в Redis.
- [x] Интегрировать вызовы существующего чат-сервиса: проксировать запросы, обеспечивать поддержку инструментов и стриминга ответов.
- [x] Выбрать стратегию доставки финального ответа (sync уже принят, при необходимости описать fallback-фасад) и закрепить реализацию в адаптере Telegram.
- [x] Добавить обработку edge-case'ов: rate limit, время ожидания, ошибки API LLM/инструментов, логирование и трейсинг для диалогов.
- [x] Реализовать webhook-приёмник апдейтов Telegram, обеспечить валидацию подписи и обработку повторных доставок.
- [x] Добавить inline-кнопки для выбора моделей/инструментов и команду сброса контекста, синхронизировать состояние с backend-сессией.
- [x] Реализовать управление режимами Streaming/Sync/Structured и sampling-параметрами (temperature, topP, maxTokens) через inline-кнопки/команды, мапируя их на `buildChatPayload` (`frontend/src/pages/LLMChat.tsx:2520`, `frontend/src/pages/LLMChat.tsx:2644`), и сохранять текущие настройки в in-memory сторе.
- [x] Формировать ответы Telegram с метаданными (провайдер, модель, инструменты, usage/cost) и снабдить их действиями «Показать детали»/«Повторить запрос», чтобы сохранить прозрачность UI (`frontend/src/pages/LLMChat.tsx:2403`, `frontend/src/pages/LLMChat.tsx:2800`).
- [x] Добавить возможность остановки стрима и повторной отправки запроса из Telegram (callback-кнопки/команды), поддерживая обновление sessionId и history (`frontend/src/pages/LLMChat.tsx:2982`).
- [x] Поддержать голосовые сообщения: конвертация через STT, выбор модели обработки, возврат текста/ответа пользователю.

### Инфраструктура и тестирование
- [x] Обновить `.env.example`, зафиксировать хранение Telegram-токенов и настроек в `.env`.
- [x] Настроить интеграционные тесты/контуры, эмулирующие Telegram апдейты и проверяющие end-to-end сценарии (выбор модели, инструменты, ответы).
- [x] Покрыть unit/contract-тестами callback-обработчики inline-кнопок (переключение провайдера/модели, режимов, остановка стрима, повтор, голосовые сообщения).

### Документация и релиз
- [x] Обновить `docs/infra.md`, `README.md` и product-гайды: настройка бота, управление токенами, сценарии использования, ограничения.

## Wave 28 — MCP заметки с RAG и поиском похожего контента
### Продукт и архитектура
- [x] Зафиксировать сценарии заметок: структура payload (заголовок, текст, теги/метаданные), ограничения размера, политика обновлений/удалений и требования к latency при поиске похожего; описать MVP и расширения (реранкинг, шаринг). → `docs/architecture/notes-mcp.md`
- [x] Определить модель идентификации пользователей: заметки должны храниться с учётом контекста источника (Telegram — `chatId`, FE — `userId`/`sessionId`), предусмотреть namespace/tenant-ключ, стратегию миграции и правила доступа между каналами. → `docs/architecture/notes-mcp.md`
- [x] Подготовить контракт запросов/ответов для инструментов `notes.save_note` и `notes.search_similar`: схемы JSON, поля уверенности/score, параметры idempotency, расширяемые поля для будущего реранкинга; зафиксировать использование OpenAI `text-embedding-3-small` как базовой модели эмбеддингов. → `docs/architecture/notes-mcp.md`
- [x] Зафиксировать архитектуру: хранение заметок и эмбеддингов реализуется в отдельном `notes-mcp` сервисе (часть `backend-mcp`), основной backend только регистрирует MCP инструменты и прокидывает конфигурацию. → `docs/architecture/notes-mcp.md`

### Backend (Spring Boot)
- [x] Обновить конфигурацию MCP каталога: записи Liquibase для `tool_schema_version`/`tool_definition` (`notes.save_note`, `notes.search_similar`), описания `app.mcp.catalog`, добавить bindings в `app.chat.research.tools` (execution-mode `MANUAL`). → `backend/src/main/resources/db/changelog/db.changelog-master.yaml`, `backend/src/main/resources/application.yaml`

### Backend MCP (notes profile)
- [x] Добавить профиль `notes` в `McpApplication`: `@ComponentScan`, `@EnableConfigurationProperties(NotesBackendProperties)`, настроить DataSource/`Liquibase` на общий Postgres. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/McpApplication.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/notes/config/NotesConfiguration.java`, `backend-mcp/src/main/resources/application-notes.yaml`
- [x] Liquibase (backend-mcp): создать таблицы `note_entry` (UUID, title, content, tags jsonb, metadata jsonb, user_namespace, user_reference, source_channel, created_at/updated_at) и `note_vector_store` (note_id FK, embedding vector, embedding_provider, embedding_dimensions, metadata jsonb, created_at), индексы по `note_id`, `user_namespace`, `tags`. → `backend-mcp/src/main/resources/db/changelog/notes/db.changelog-master.yaml`
- [x] Добавить зависимости Spring AI (`spring-ai-pgvector-store`, embedding starter) и сконфигурировать `EmbeddingModel`, `PgVectorStore`, параметры `notes.rag.*` (модель `openai/text-embedding-3-small`, размер эмбеддингов, topK, включение реранкинга). → `backend-mcp/build.gradle`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/notes/config/NotesConfiguration.java`
- [x] Реализовать доменный слой (`NoteEntity`, репозитории, `NotesService`) с транзакционным сохранением заметки, записью `Document` в PgVectorStore, идемпотентностью по хэшу контента и логированием embedding ошибок. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/notes/persistence/NoteEntity.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/notes/persistence/NoteRepository.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/notes/service/NotesService.java`
- [x] Реализовать `NoteSearchService`: запрос векторного стора, нормализация score, подготовка hook'ов под будущий `RerankModel` (Spring AI); fallback на полнотекстовый поиск в MVP не требуется. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/notes/service/NoteSearchService.java`
- [x] Реализовать `NotesTools` с методами `notes.save_note` и `notes.search_similar`, валидацией входа (обязательные поля, лимиты topK), настройкой реранкинг-параметров (пока отключены), регистрацией через `MethodToolCallbackProvider`. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/notes/tool/NotesTools.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/notes/tool/NotesToolConfiguration.java`

### Инфраструктура и конфигурация
- [x] Добавить сервис `notes-mcp` в `docker-compose.yml`, подключить общий Postgres/Redis (при необходимости), пробросить переменные `NOTES_MCP_DB_URL`/`NOTES_MCP_DB_USER`/`NOTES_MCP_DB_PASSWORD`, параметры embedding модели, API ключи; настроить healthcheck и логирование. → `docker-compose.yml`
- [x] Обновить `.env.example`, `application.yaml`/`application-prod.yaml` и профили backend-mcp описанием embedding-провайдера, размеров векторов, параметров поиска/реранкинга; задокументировать миграции и расширение `pgvector`. → `.env.example`, `backend/src/main/resources/application.yaml`, `docs/infra.md`
- [x] Настроить миграции/Testcontainers в backend-mcp: ensure `pgvector` расширение создаётся, добавить smoke-сценарий запуска notes-mcp в локальном compose и базовый health-check. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/notes/NotesServiceIntegrationTest.java`

### Тестирование и документация
- [x] Интеграционные тесты notes-mcp: сохранение заметки, повторное сохранение (идемпотентность), поиск похожего (vector match + fallback), проверка сериализации метаданных и graceful деградации embeddings. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/notes/NotesServiceIntegrationTest.java`
- [x] Контрактные тесты между backend ↔ notes-mcp (MCP): успешный вызов обоих инструментов, таймауты, обработка 4xx/5xx, корректный mapping score и noteId. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/notes/NotesServiceIntegrationTest.java`
- [x] Обновить документацию (`docs/infra.md`, `docs/guides/mcp-operators.md`, README): описание сервиса notes-mcp, формат заметок, параметры поиска, процесс настройки embedding моделей, подсказки по эксплуатации и мониторингу (метрики insert/search). → `docs/infra.md`, `docs/guides/mcp-operators.md`, `README.md`, `docs/architecture/notes-mcp.md`

## Wave 29 — Claude CLI Patch Generation
Цель: заменить заглушку Patch Plan на полноценную генерацию diff'ов через Claude Code CLI и встроить новый генератор в существующий assisted-coding pipeline (coding → GitHub write).

### Backend (backend-mcp)
- [ ] Ввести интерфейс `PatchGenerator` и реализацию `ClaudeCliPatchGenerator`, которая собирает инструкции/target/forbidden/context, вызывает `claude` как подпроцесс и возвращает summary/diff/usage/annotations (учитывая лимиты `coding.max-*`). → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/coding/*`
- [ ] Добавить `ClaudeCliService`: поиск бинаря, валидация (`claude --version`), сбор env (`ANTHROPIC_API_KEY`, `CLAUDE_CODE_BIN`), конфигурация таймаутов и нормализованный запуск через `ProcessBuilder`. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/coding`
- [ ] Использовать `WorkspaceFileService` для подготовки контекста (snippets → временный каталог или stdin), не выходя за лимиты чтения и сохраняя ссылки на файлы. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/workspace/WorkspaceFileService.java`
- [ ] Перенастроить `CodingAssistantService` на новую реализацию генератора, обеспечить нормализацию diff (отфильтровать бинарные блоки, отсечь файлы вне workspace), обновлять `PatchRegistry`/метрики в случае ошибок CLI. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/coding/CodingAssistantService.java`, `PatchRegistry.java`
- [ ] Обработать usage/аннотации из CLI-ответа: пробрасывать оценку риска, modified files, ограничения dry-run, логировать предупреждения. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/coding/CodingAssistantService.java`
- [ ] Покрыть тестами: unit на `ClaudeCliService` (успех, таймаут, stderr), unit на `ClaudeCliPatchGenerator` (валидации, лимиты, парсинг diff), интеграционный сценарий generate→review→apply c mock CLI. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/coding/*`

### Инфраструктура и конфигурация
- [ ] Добавить переменные `CLAUDE_CODE_BIN`, `CLAUDE_CODE_TIMEOUT`, `ANTHROPIC_API_KEY` в `.env.example`, `application-coding.yaml`, описать fallback автоматического поиска бинаря. → `.env.example`, `backend-mcp/src/main/resources/application-coding.yaml`
- [ ] Обновить `docker-compose.yml`: смонтировать Node/npm cache, добавить healthcheck `claude --version`, задокументировать зависимости контейнера `coding-mcp`. → `docker-compose.yml`
- [ ] Создать раздел в `docs/infra.md` про установку Claude CLI в dev/prod окружениях (Node 18+, npm install -g, хранение API ключей). → `docs/infra.md`

### Наблюдаемость и безопасность
- [ ] Расширить метрики: `coding_patch_generation_duration`, `coding_claude_cli_fail_total`, логировать структуру prompt size (без контента) и размер diff. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/coding/CodingAssistantService.java`
- [ ] Добавить feature-flag/конфиг для быстрого отключения Claude CLI (fallback к patch plan) при деградации, зафиксировать политику ретраев и маскирование stderr в логах. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/coding/CodingAssistantProperties.java`, `application-coding.yaml`
- [ ] Обновить `docs/guides/mcp-operators.md` и `docs/architecture/coding-assistant-rfc.md`: новый генератор, требования к CLI, сценарий отладки (stdout/stderr, tmp-папка контекста). → `docs/guides/mcp-operators.md`, `docs/architecture/coding-assistant-rfc.md`

### Assisted Coding Flow
- [ ] Провести e2e прогоны (web UI + Telegram): `github.repository_fetch` → `coding.generate_patch` (Claude CLI) → `coding.review_patch` → `coding.apply_patch_preview` → GitHub write-инструменты; задокументировать чек-лист оператора и негативные сценарии (CLI timeout, превышение лимита diff). → `frontend`, `docs/guides/mcp-operators.md`

## Wave 30 — GitHub Workspace RAG Indexing
Цель: автоматически индексировать скачанные репозитории в PgVector после `github.repository_fetch`, чтобы агенты могли получать реранкнутый контекст по каждому файлу проекта в одном namespace на уровне репозитория.

### Продукт и архитектура
- [x] Зафиксировать архитектуру GitHub RAG индексации: событие `github.repository_fetch` → очередь `repo_rag_index_job` → обход workspace → PgVector namespace `repo:<owner>/<name>`, политика TTL/idempotency и требования к latency; описать обязательность асинхронной задачи и инструменты `repo.rag_index_status`/`repo.rag_search`. → `docs/architecture/github-rag-indexing.md`
- [x] Нормализовать формат chunk-метаданных (repo owner/name, filePath, chunkIndex, chunkHash, language, summary) и правила повторного запуска индексации при повторных fetch'ах/обновлениях; приложить диаграмму взаимодействия агентов/flow с асинхронной задачей. → `docs/architecture/github-rag-indexing.md`

### Backend MCP (GitHub) — индексатор
- [x] Подключить Postgres и embedding модель к профилю `github`: переопределить `spring.autoconfigure.exclude`, описать `spring.datasource`/`spring.jpa`/`spring.liquibase`, секцию `spring.ai.openai.embedding` и `github.rag.*` (chunk & glob limits, parallelism, namespace). Обновить `.env.example` и сервис `github-mcp` в `docker-compose.yml` (`depends_on: postgres`) новыми переменными (`GITHUB_MCP_DB_URL/USER/PASSWORD`, `GITHUB_RAG_EMBEDDING_MODEL`, `GITHUB_RAG_MAX_CHUNK_BYTES`, `GITHUB_RAG_MAX_CONCURRENCY`, `GITHUB_RAG_NAMESPACE_PREFIX`). → `backend-mcp/src/main/resources/application.yaml`, `backend-mcp/src/main/resources/application-github.yaml`, `.env.example`, `docker-compose.yml`
- [x] Liquibase-чейнджлог для repo RAG: создать таблицы `repo_rag_index_job` (repo_owner, repo_name, status, queued/started/completed timestamps, file/chunk counters, error_payload) и `repo_rag_vector_store` (chunk_id UUID PK, namespace, file_path, chunk_index, content, metadata JSONB, embedding VECTOR), добавить `CREATE EXTENSION IF NOT EXISTS "vector"` и индексы по namespace + path. → `backend-mcp/src/main/resources/db/changelog/github/db.changelog-rag.yaml`, `backend-mcp/src/main/resources/application-github.yaml`
- [x] Реализовать JPA/DAO слой индексации: `RepoRagIndexJobEntity`, `RepoRagDocumentEntity`, репозитории и адаптер к `VectorStore` с namespace/hash конвертерами; обеспечить методы поиска активного job по owner/name и очистку старых записей. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/persistence/*`
- [x] Реализовать `RepoRagIndexService`: обход workspace через `TempWorkspaceService`, игнор `.git`/`node_modules`/бинарные файлы, chunking по размерам и строкам, генерация `Document` с текстом и метаданными (repo owner/name, filePath, chunkIndex, lineStart/lineEnd, chunkHash) и запись в PgVector; обновлять job метрики и логировать прогресс/ошибки. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagIndexService.java`
- [x] Добавить `RepoRagIndexScheduler` и `RepoRagIndexWorker`: после успешного `GitHubRepositoryService.fetchRepository` создавать/возобновлять job, ставить его в очередь, ограничивать параллелизм, добавлять retry/backoff и отмену при удалении workspace; внедрить вызов scheduler'а из `GitHubRepositoryService` и публиковать метрики (`repo_rag_index_duration`, `repo_rag_queue_depth`). → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagIndexScheduler.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/GitHubRepositoryService.java`
- [x] Реализовать идемпотентность по файлам и дедупликацию chunk'ов: добавить таблицу `repo_rag_file_state` (namespace, file_path, file_hash, chunk_count, updated_at), заполнять её при индексировании, перед обходом workspace сверять текущий `sha256` файла с таблицей и пропускать неизменившиеся файлы; в `RepoRagVectorStoreAdapter` реализовать точечную замену chunk'ов по file_path и удаление устаревших записей. Обновить Liquibase, `RepoRagIndexService` (подсчёт хэша, ветка skip, счётчики job), `RepoRagIndexScheduler` (метрики пропущенных файлов) и тесты (`RepoRagIndexServiceTest`, интеграции) на сценарии «без изменений», «частичный апдейт», «удалённый файл`.
- [x] Вынести метаинформацию namespace в таблицу `repo_rag_namespace_state`: хранить owner/repo, последнюю `source_ref`, commit SHA, текущий `workspaceId`, накопленные счётчики файлов/чанков, timestamps индексации и флаг `ready`. Обновлять запись в конце job, использовать её в `RepoRagStatusService` и `RepoRagSearchService` для мгновенного ответа, а также при очистке workspace. Требуется новая миграция, репозиторий/DAO, обновление сервисов и интеграционные тесты (повторный fetch, смена ref, удаление workspace).

### Backend MCP (GitHub) — инструменты поиска и статус
- [x] Реализовать `RepoRagSearchService`: similarity search по namespace (repo owner/name) с настройками `topK`, `minScore`, `rerankTopN`, формировать snippets и confidence, применять rerank на основе текущих score/метаданных без отдельной модели. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchService.java`
- [x] Реализовать `RepoRagStatusService`: выдача статуса job (PENDING/RUNNING/SUCCEEDED/FAILED), прогресса (files/chunks processed, ETA), автоматических retry при сбоях и чтение последних ошибок; использовать для инструментов и операторских API. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagStatusService.java`
- [x] Зарегистрировать MCP-инструменты `repo.rag_index_status` и `repo.rag_search`: DTO с валидацией repo owner/name, фиксированным namespace (`repo:<owner>/<name>`), полями `query`, `topK`, `rerankTopN`; добавить описания, примеры и ограничения (макс. chunk size, ожидание READY). → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagTools.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagToolConfiguration.java`
- [x] Обновить heuristics-конфигурацию: описать параметры `rerankTopN`, сортировку по `score/line_span`, лимиты snippet'ов, fallback без внешней модели; добавить unit-тесты `RepoRagSearchService` с mock `VectorStore`. → `backend-mcp/src/main/resources/application-github.yaml`, `.env.example`, `backend-mcp/src/test/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchServiceTest.java`

### Backend (core), агенты и UI
- [x] Добавить записи в каталог инструментов backend: `tool_schema_version`/`tool_definition`/`tool_binding` для `repo.rag_index_status` и `repo.rag_search`, указать сервер `github` и execution mode (MANUAL), включить инструменты в `app.chat.research.tools` и blueprint'ы, чтобы агенты автоматически ожидали индекса. → `backend/src/main/resources/db/changelog/db.changelog-master.yaml`, `backend/src/main/resources/application.yaml`

### Observability & QA
- [x] Метрики и алёрты: `repo_rag_index_duration`, `repo_rag_index_fail_total`, `repo_rag_embeddings_total`, `repo_rag_queue_depth`, traceId=requestId+repo; экспортировать через Micrometer/Prometheus и описать алёрты на рост очереди или массовые отказы. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/*`
- [x] Покрыть тестами: unit (`RepoRagChunkerTest`, `RepoRagIndexServiceTest`, `RepoRagStatusServiceTest`) и интеграции с Testcontainers (успешный прогон, повторное индексирование того же репозитория, ошибка embeddings). Добавить e2e сценарий `github.repository_fetch` → `repo.rag_index_status` → `repo.rag_search` в автоматизированный suite и задокументировать шаги. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/github/rag/*`, `docs/guides/mcp-operators.md`

### Документация и релиз
- [x] Обновить `docs/infra.md`, `README.md` и `.env.example`: настройка repo RAG индексатора, требования к Postgres/pgvector, переменные `GITHUB_RAG_*`, лимиты на размер workspace и процедуры перезапуска job. → `docs/infra.md`, `README.md`, `.env.example`
- [x] Дополнить `docs/guides/mcp-operators.md` runbook'ом: как отслеживать прогресс (poll `repo.rag_index_status`), как интерпретировать поля ответа, как использовать `repo.rag_search` для отбора релевантного контекста с heuristic rerank и как реагировать на ошибки индексации. → `docs/guides/mcp-operators.md`
- [x] Расширить `docs/architecture/coding-assistant-rfc.md` новым e2e-кейсом (`github.repository_fetch` → ожидание индекса → `repo.rag_search` → `coding.generate_patch`) и описать, как отсортированные чанки (без отдельной модели) влияют на подсказки агента. → `docs/architecture/coding-assistant-rfc.md`

## Wave 31 — Modular GitHub RAG Flows
Цель: внедрить модульный Pre-/Retrieval-/Post-/Generation pipeline на компонентах Spring AI, чтобы повысить полноту поиска, управляемость LLM-подсказок и прозрачность для операторов.

### Исправления по итогам ревью
  - [x] Привязать `repo.rag_search_simple` к репозиторию из последнего `github.repository_fetch`, а не к глобальному `findLatestReady`, чтобы избегать утечки контекста между пользователями. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagTools.java`, `RepoRagNamespaceStateService`
  - [x] Возвращать настоящий augmented prompt из `ContextualQueryAugmenter`: сейчас `RepoRagGenerationService` подставляет отформатированный список сниппетов в поле `augmentedPrompt`, а итоговый prompt уходит в `instructions`. Требуется swap + regression-тест. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagGenerationService.java`, `RepoRagSearchService`
  - [x] Либо реально задействовать `ConcatenationDocumentJoiner` в Retrieval-пайплайне, либо обновить документацию, чтобы описание совпадало с реализацией, которая выполняет merge вручную. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagRetrievalPipeline.java`, `docs/architecture/github-rag-modular.md`, `README.md`
  - [x] Прокинуть `rerankTopN` из запроса в `HeuristicDocumentPostProcessor` (сейчас используется только конфиг), иначе контракт `repo.rag_search` v4 нарушен. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchService.java`, `HeuristicDocumentPostProcessor`
  - [x] Расширить тесты (query transformers, multi-query дедупликация, post-processing, generation, e2e `repository_fetch → rag_index_status → rag_search`), как было заявлено в Wave 31. Сейчас покрыт только базовый `RepoRagSearchServiceTest`. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/github/rag/*`

### Продукт и архитектура
  - [x] Обновить `docs/architecture/github-rag-indexing.md` и вынести отдельный раздел `docs/architecture/github-rag-modular.md` с описанием LEGO-подобных модулей (QueryTransformer, MultiQueryExpander, DocumentJoiner, DocumentPostProcessor, ContextualQueryAugmenter), схемой включения/отключения модулей и требованиями к latency/стоимости. В описании `repo.rag_search` сразу зафиксировать, что инструмент принимает «сырой» запрос и сам решает, какие этапы задействовать (история, перевод, мульти-запрос, пр.); клиенту не нужно подстраиваться — MCP сам выбирает стратегию.
  - [x] Описать два варианта инструмента: `repo.rag_search_simple` (единственный параметр `rawQuery`, репозиторий подтягивается из последней `github.repository_fetch`, pipeline сам включает компрессию/перевод/multi-query; response = расширенный формат с `instructions`, `augmentedPrompt`, `contextMissing`, `appliedModules`) и `repo.rag_search` v2 (расширенный DTO с полями Wave 31: `history`, `previousAssistantReply`, `allowEmptyContext`, `translateTo`, `filters` declarative + raw expression, `multiQuery`, лимиты по `topK/rerankTopN/maxContextTokens`, `generationLocale`, `instructionsTemplate`). В каталоге MCP и описании инструментов зафиксировать верхние границы (`topK<=40`, `multiQuery<=maxQueries`, `maxContextTokens<=serverLimit`, SLA 120 s) и чётко объяснить различие между версиями. → `docs/architecture/github-rag-modular.md`, `docs/guides/mcp-operators.md`, `backend/src/main/resources/db/changelog/db.changelog-master.yaml`
  - [x] Зафиксировать пользовательские сценарии (агенты, flows, операторы), матрицу параметров (`useCompression`, `translateTo`, `multiQueryK`, `allowEmptyContext`, `filterExpression`), политики безопасности (лимиты по языкам, макс. число подзапросов, макс. токены) и в явном виде указать верхние границы, которые нельзя превышать при override на уровне запроса. → `docs/architecture/github-rag-modular.md`, `docs/guides/mcp-operators.md`

### Backend MCP (GitHub) — RAG pipeline
  - [x] Добавить pre-retrieval слой: `CompressionQueryTransformer`, `RewriteQueryTransformer`, `TranslationQueryTransformer` на отдельном `ChatClient.Builder` (temperature=0, модель из дешёвой линейки ChatGPT, например `gpt-4o-mini`). По умолчанию `TranslationQueryTransformer` переводит на русский, но `translateTo` можно переопределить; `GitHubRagProperties`/`application-github.yaml`/`.env.example` нужно расширить (`enabled`, `maxHistoryTokens`, `defaultTargetLanguage`). → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/config/GitHubRagProperties.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchService.java`, `backend-mcp/src/main/resources/application-github.yaml`, `.env.example`
  - [x] Внедрить `MultiQueryExpander` + `ConcatenationDocumentJoiner` в Retrieval слое: генерируем N подзапросов, каждый выполняет собственный similarity search с `topK` на подзапрос, затем объединяем и дедуплицируем по `metadata.chunk_hash`; после дедупа режем общий список до глобального лимита и помечаем, какие саб-запросы породили конкретный чанк (`generatedBySubQuery`). Добавить конфиг `github.rag.multi-query.{enabled,defaultQueries,maxQueries}` и валидацию параметров запроса (не больше maxQueries, fallback к дефолту). → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchService.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagRetrievalPipeline.java`, `backend-mcp/src/main/resources/application-github.yaml`
  - [x] Реализовать Post-Retrieval процессинг через Spring AI `DocumentPostProcessor`: текущий `RepoRagSearchReranker` становится фасадом, который вызывает цепочку пост-процессоров (heuristic rerank по score/span, ContextWindowBudget, LLM-компрессор сниппетов). `rerankApplied=true`, если хотя бы один модуль изменил порядок или обрезал список. Предусмотреть точку расширения под внешние модели. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/postprocessing/*`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/HeuristicRepoRagSearchReranker.java`
- [x] Добавить Generation слой (`ContextualQueryAugmenter`) с кастомными шаблонами (`resources/prompts/github-rag-context.st`, `github-rag-empty-context.st`), настройкой `allowEmptyContext`, обязательными инструкциями (цитирование путей, запрет галлюцинаций) и опцией локализации вывода (RU по умолчанию, переопределяется через `translateTo`). В ответе инструмента возвращать `augmentedPrompt`, `instructions`, `appliedModules`, а при отсутствии контекста выставлять `contextMissing=true`, `noResultsReason="CONTEXT_NOT_FOUND"` и сообщение «Индекс не содержит подходящих документов». → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchService.java`, `backend-mcp/src/main/resources/prompts/*`, `backend-mcp/src/main/resources/application-github.yaml`
- [x] Протянуть новые параметры в конфигурацию `SearchCommand`: `topKPerQuery`, `multiQuery`, `maxContextTokens`, `minScoreByLanguage`, `allowEmptyContext`, `filterExpression`, `filters.languages`, `filters.pathGlobs`, `rerankStrategy`; все параметры валидируются по макс. значениям (зафиксированы в описании инструмента). Обновить DTO/валидацию и описать fallback’и (если параметр не передан — берём дефолт из конфига). → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchService.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchService.SearchCommand`
- [x] Переработать слой чанкинга: выделить `ChunkingStrategy` (line/byte/token/semantic) с адаптером под Spring AI `DocumentSplitter`, добавить overlap (строки/токены), сохранение line offsets, parent symbol и детерминированных `chunk_id`. Конфигурацию (`github.rag.chunking.*`) структурировать по стратегиям; при недоступности семантического режима автоматически откатываться на line-based без предупреждений. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/chunking/*`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagIndexService.java`, `backend-mcp/src/main/resources/application-github.yaml`
- [x] Добавить «semantic chunking» режим для кода: эвристики по каждому языку (поиск сигнатур/классов, `def`/`function`, комментарии), возможность подтягивать соседние чанки для ответа и расширение метаданных (`parent_symbol`, `span_hash`, `overlap_lines`). При отсутствии AST/правил для языка автоматически fallback к line-based. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/chunking/SemanticCodeChunker.java`, `backend-mcp/src/test/java/com/aiadvent/mcp/backend/github/rag/ChunkingStrategyTest.java`, `docs/architecture/github-rag-modular.md`

### Backend (core) и MCP инструменты
- [x] Расширить `repo.rag_search` контракт дополнительными полями (`useCompression`, `translateTo`, `multiQuery`, `filters.languages[]`, `filters.pathGlobs[]`, `filterExpression`, `allowEmptyContext`, `maxContextTokens`, `maxQueries`, `topKPerQuery`). В описании инструмента явно указать максимальные значения (совпадают с конфигом) и то, что MCP автоматически выбирает стратегию. DTO/ответы дополняем полями `appliedModules`, `augmentedPrompt`, `noResults`, `noResultsReason`. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagTools.java`, `backend/src/main/resources/db/changelog/db.changelog-master.yaml`
- [x] Добавить поддержку пользовательских фильтров: декларативный формат (`languages`, `pathGlobs`) преобразуем в запрос (namespace + language фильтры попадают в FilterExpression, glob’ы дофильтровываем в памяти), а для продвинутых случаев допускаем raw expression (приоритизируем declarative). Прокинуть фильтры в `VectorStoreDocumentRetriever` через advisor context `FILTER_EXPRESSION`. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSearchService.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagTools.java`

### Observability и тестирование
- [x] Сохранить SLA инструмента (timeout 120 c) без добавления новых метрик: акцент на валидации лимитов и оптимизации параллельных вызовов, но без интеграции с MeterRegistry.
- [x] Покрыть юнит-тестами каждый модуль (стабовые ChatClient/LLM без реальных сетевых вызовов, multi-query дедупликация, post-processor) и интеграциями, которые гоняют полный цикл `repo.rag_search` с флагами on/off; добавить regression-набор на lost-in-the-middle и мультиязычные запросы. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/github/rag/*`

### Документация и enablement
- [x] Обновить `docs/guides/mcp-operators.md`, `docs/architecture/coding-assistant-rfc.md`, `README.md`: параметры новых фич, примеры CLI/чата, чек-листы расследования (как понять, что сработали QueryTransformers, сколько подзапросов ушло, что делать при пустом контексте — в т.ч. сообщение «Индекс не содержит подходящих документов»). Добавить ссылку на Spring AI Modular RAG доки (`local-rag/spring-ai/docs.spring.io/...`) для справки и явно задокументировать SLA 120 c и лимиты `maxQueries`/`maxContextTokens`. → `docs/guides/mcp-operators.md`, `docs/architecture/coding-assistant-rfc.md`, `README.md`

## Wave 33 — Code-aware rerank & neighbor context
Цель: перестроить пост-обработку `repo.rag_search`, чтобы она понимала структуру кода и умела расширять выдачу близлежащими чанками без повторного запроса в Vector Store. Это снизит количество промахов в top‑K, улучшит покрытия class/function-level запросов и даст более стабильный контекст для генерации. Порядок выполнения: (1) обновлённый реранк пайплайн, (2) neighbor expansion, (3) AST-aware indexing & metadata (открывает стратегию `CALL_GRAPH`), (4) persistence/tests/docs.

### Backend MCP — rerank pipeline
- [x] Добавить `CodeAwareDocumentPostProcessor`, который считает оценку `score * languageBonus * symbolPriority * pathPenalty * diversityWeight` на голове списка (берём `ceil(rerankTopN * codeAwareHeadMultiplier)`). Приоритеты: классы/публичные методы выше приватных, штраф за пути `generated`, `testdata`, `node_modules`, бонус при совпадении языка запроса. Если порядок изменился — добавляем `appliedModules+=post.code-aware`. Конфиг `github.rag.rerank.code-aware.*` описывает веса, лимиты per-file/per-symbol и allow/deny списки. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/postprocessing/CodeAwareDocumentPostProcessor.java`, `HeuristicRepoRagSearchReranker.java`, `backend-mcp/src/main/resources/application-github.yaml`
- [x] Прокинуть параметры `codeAwareEnabled` (default true) и `codeAwareHeadMultiplier` (default 2.0, максимум 4.0) в `RepoRagPostProcessingRequest`, `RepoRagSearchService.SearchCommand`, DTO инструментов и валидацию. Конфиг `github.rag.rerank.code-aware.*` должен явно включать: `language-bonus.{lang}` (double), `symbol-priority.{class,method_private,...}`, `path-penalty.denyPrefixes[]`, `path-penalty.allowPrefixes[]`, `diversity.maxPerFile`, `diversity.maxPerSymbol`, `score.weight`, `span.weight`. Документацию дополняем описанием новых опций и примерами значений. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagPostProcessingRequest.java`, `RepoRagSearchService.java`, `RepoRagTools.java`, `docs/guides/mcp-operators.md`, `docs/architecture/github-rag-modular.md`

### Neighbor chunk expansion
- [x] Реализовать `NeighborChunkDocumentPostProcessor`: источник данных — прямой запрос в `repo_rag_vector_store` через `RepoRagDocumentRepository.findByNamespaceAndFilePathAndChunkIndexIn` + новый `RepoRagDocumentMapper`, который восстанавливает `Document` (метадата, score, text). Добавленные элементы помечаем `metadata.neighborOfSpanHash`, не дублируем `chunk_hash`, соблюдаем глобальный лимит (по умолчанию 6) и дописываем `appliedModules+=post.neighbor-expand`, если хотя бы один сосед вставлен. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/postprocessing/NeighborChunkDocumentPostProcessor.java`, `RepoRagDocumentRepository.java`, `RepoRagDocumentMapper.java`, `RepoRagSearchReranker.java`
- [x] Поддержать стратегии `OFF`/`LINEAR`/`PARENT_SYMBOL`/`CALL_GRAPH`: `LINEAR` — ±radius по индексу, `PARENT_SYMBOL` — все чанки с тем же `symbol_fqn`, `CALL_GRAPH` — вызванные/вызывающие символы (включается только после завершения блока «AST-aware indexing & metadata» и появления `RepoRagSymbolService`). Для отсутствующих соседей логируем debug и идём дальше без ошибок. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/postprocessing/NeighborChunkDocumentPostProcessor.java`, `RepoRagSymbolService.java`
- [x] Расширить конфиг и DTO: `github.rag.post-processing.neighbor.{enabled,default-radius,default-limit,max-radius=5,max-limit=12,strategy}` + параметры `neighborRadius`, `neighborLimit`, `neighborStrategy` в `RepoRagPostProcessingRequest`, `RepoRagSearchService`, `RepoRagTools`. Валидация должна отклонять значения выше лимита (400) и документировать дефолты. → `backend-mcp/src/main/resources/application-github.yaml`, `RepoRagPostProcessingRequest.java`, `RepoRagSearchService.java`, `RepoRagTools.java`, README/docs

### Persistence, тесты и документация
- [x] Добавить Liquibase-изменения: таблица `repo_rag_symbol_graph`, возможное обновление `metadata` default. Описать rollback и оставить замечание, что существующие namespace переходят на новый формат только после повторного fetch/index (никаких фоновых backfill). → `backend-mcp/src/main/resources/db/changelog/github/db.changelog-rag.yaml`
- [x] Расширить слой persistence: `RepoRagSymbolGraphEntity`/Repository + интеграция с `RepoRagSymbolService`, чтобы стратегия `neighborStrategy=CALL_GRAPH` использовала реальные данные (fallback допускается только до окончания Wave 34). → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/persistence/*`, `RepoRagSymbolService.java`
- [x] Расширить тестовое покрытие: unit-тесты `CodeAwareDocumentPostProcessor`, `NeighborChunkDocumentPostProcessor`, интеграции `RepoRagSearchService`/`RepoRagTools` с проверкой `appliedModules`, лимитов, валидаций и CALL_GRAPH. Добавить smoke-тест CLI/CI, который убеждается, что neighbor параметры документированы и не ломают SLA. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/github/rag/*`
- [x] Обновить README: перечислить новые параметры (`codeAwareEnabled`, `codeAwareHeadMultiplier`, `neighborRadius/Limit/Strategy`), привести пример ответа с `appliedModules+=post.neighbor-expand` и `metadata.neighborOfSpanHash`, сослаться на Spring AI docs. → `README.md`, `docs/guides/mcp-operators.md`, `docs/architecture/github-rag-modular.md`


## Wave 34 — AST-aware indexing & metadata
### AST-aware indexing & metadata
- [ ] Подключить Tree-sitter как единый движок разбора кода: добавить модуль `TreeSitterAnalyzer`, который через Gradle task `treeSitterBuild` собирает pinned грамматики (Java/Kotlin/TS/JS/Python/Go) и складывает `.so/.dylib/.dll` по `os/arch` в `src/main/resources/treesitter/**`. Источник грамматик: (а) git submodules на официальные репозитории tree-sitter-lang с фиксированным коммитом, (б) архивы с release-тэгов, скачиваемые Gradle’ом с checksum. Во время `bootJar` копируем либы рядом с jar, а в рантайме загружаем их через `System.load`, читая конфиг `github.rag.ast.{enabled,languages[],libraryPath}`. В Docker образ кладём только linux-вариант; при ошибке загрузки автоматически фолбэкаемся на существующую эвристику без алертов. → `backend-mcp/build.gradle`, `backend-mcp/Dockerfile`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/ast/*`, `backend-mcp/src/main/resources/application-github.yaml`
  - [ ] Завести git submodules (или Gradle artifact-downloader) на грамматики `tree-sitter-{java,kotlin,typescript,javascript,python,go}` и зафиксировать commit hash + checksum в `gradle/libs.versions.toml`.
  - [ ] Добавить Gradle-задачу `treeSitterBuild`, которая собирает `.so/.dylib/.dll` в `build/treesitter/<os>/<arch>` и кладёт их в `src/main/resources/treesitter/**` при `processResources`.
  - [ ] Реализовать `TreeSitterAnalyzer` (SPI + JNI loader): конфиг `github.rag.ast.*`, lazy `System.load`, health-проба и автоматический возврат к эвристикам при N consecutive failures.
  - [ ] Обновить `bootJar`/`Dockerfile`, чтобы jar получал только нужные либы, а linux-вариант копировался в рантайм-образ; в README описать необходимость пересборки грамматик при смене ОС.
  - [ ] Добавить smoke-check (Gradle `treeSitterVerify` + CI job), что библиотеки реально загружаются на Linux и macOS и печатают версии грамматик.
- [ ] Расширить `ChunkableFile`/`SemanticCodeChunker`: до начала чанкинга вызываем `TreeSitterAnalyzer` и обогащаем `Chunk` метаданными `symbol_fqn`, `symbol_kind`, `symbol_signature`, `symbol_visibility`, `docstring`, `is_test`, `imports[]`, `calls_out[]`, `calls_in[]`. В `RepoRagIndexService` сериализуем поля в `metadata` JSON и помечаем `ast_available`. При падении парсинга — логируем debug и используем текущий `ParentSymbolResolver`. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/chunking/*`, `RepoRagIndexService.java`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/persistence/RepoRagDocumentEntity.java`
  - [ ] Расширить `Chunk` record и `RepoRagDocumentEntity.metadata` schema, добавить mapper из AST модели → `ChunkMetadata`.
  - [ ] В `ChunkableFile` и `SemanticCodeChunker` внедрить `TreeSitterAnalyzer`: один проход по AST даёт список символов, далее chunker использует точные границы, FQN и docstring.
  - [ ] Интегрировать в `RepoRagIndexService`: проставлять `ast_available`, сериализовать массивы `imports/calls_*`, сохранять `symbol_*` и `is_test`. Нужна миграция JSON схемы и метки версии.
  - [ ] Добавить graceful fallback: если язык не поддержан или AST не загрузился, логируем `debug` + счётчик и используем прежний `ParentSymbolResolver`.
- [ ] Построить отдельный индекс вызовов: таблица `repo_rag_symbol_graph` (`id`, `namespace`, `file_path`, `symbol_fqn`, `symbol_kind`, `chunk_hash`, `referenced_symbol_fqn`, `relation`, `created_at`) + сервис `RepoRagSymbolService`, который выдает «где объявлен символ» и «какие чанки его вызывают». Добавить индексы по `(namespace,symbol_fqn)` и `(namespace,referenced_symbol_fqn)`. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/persistence/*`, `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagSymbolService.java`, Liquibase
  - [ ] Дополнить Liquibase changelog: новая таблица + on-delete каскад по namespace, индексы и rollback.
  - [ ] В `RepoRagIndexService` после сохранения чанков строить граф: для каждого `calls_out` вставлять `symbol_fqn -> referenced_symbol_fqn`, при переиндексации файла удалять старые рёбра.
  - [ ] Расширить `RepoRagSymbolService`: методы `findSymbolDefinition`, `findOutgoingEdges`, батч-кэширование и троттлинг для массовых lookup.
  - [ ] Добавить CLI/observability hook (log + metric), который показывает сколько call-edges создано и сколько запросов в минуту обслуживает сервис.
- [ ] Зафиксировать, что новая AST/metadata логика применяется только к свежим индексам: никаких фоновых backfill-job. Обновить документацию, чтобы операторы понимали, что уже проиндексированные namespace остаются в старом формате до следующего fetch/index. → `docs/guides/mcp-operators.md`, `docs/architecture/github-rag-modular.md`
  - [ ] Добавить флаг `namespace_state.astSchemaVersion` (или `ast_ready_at`), выставлять его только при полном переиндексе.
  - [ ] В `repo.rag_index_status` возвращать поле «AST schema»/`astReady`, чтобы UI/операторы видели состояние.
  - [ ] Задокументировать процедуру апгрейда: «запусти github.repository_fetch + дождись READY», описать, что смешанные namespace допустимы.
- [ ] Обновить pipeline пост-обработки: `NeighborChunkDocumentPostProcessor` и code-aware шаги умеют запрашивать call graph/символы через `RepoRagSymbolService`. Добавить стратегию `neighborChunkStrategy=CALL_GRAPH`, которая по сведению о вызове подтягивает реализацию вызываемого метода и помечает выдачу `metadata.neighborOfSpanHash`. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/postprocessing/*`, `application-github.yaml`
  - [ ] Расширить `RepoRagPostProcessingRequest`/DTO, валидировать `CALL_GRAPH` только если namespace AST-ready.
  - [ ] В `NeighborChunkDocumentPostProcessor` загружать соседи по `chunk_hash` (а не только по index), помечать relation (`CALLS`/`CALLED_BY`) и добавлять их в `metadata`.
  - [ ] Переписать `CodeAwareDocumentPostProcessor` так, чтобы новые поля (`symbol_kind`, `symbol_visibility`, `docstring`, `is_test`) участвовали в score и diversity.
  - [ ] В конфиг `github.rag.post-processing.neighbor.*` добавить флаги для автоматического включения CALL_GRAPH, когда call graph доступен, и лимиты на дополнительные чанки.
- [ ] Тесты и enablement: интеграционные тесты индексации (мини-проекты Java/TS/Python) с проверкой `symbol_fqn`/call graph, smoke-тест загрузки Tree-sitter либ в CI, документация о новых полях (`symbol_fqn`, `calls_out`, `neighborOfSpanHash`) и конфиге `github.rag.ast.*`. → `backend-mcp/src/test/java/com/aiadvent/mcp/backend/github/rag/*`, `docs/guides/mcp-operators.md`, `docs/architecture/github-rag-modular.md`, `README.md`
  - [ ] Добавить фикстуры «mini-repo» для Java/TS/Python/Go, прогонять `RepoRagIndexService` end-to-end, проверять AST метаданные и call graph.
  - [ ] Unit-тесты `TreeSitterAnalyzer` (mock grammar loader), `NeighborChunkDocumentPostProcessor` (CALL_GRAPH), `CodeAwareDocumentPostProcessor` (новые веса).
  - [ ] CI: job `./gradlew treeSitterVerify test` на Linux + macOS, smoke run `TreeSitterAnalyzer.load()` чтобы предотвратить регрессии загрузки.
  - [ ] Обновить e2e-suites (CLI/Playwright) — сценарий: fetch → index → rag_search с `neighborStrategy=CALL_GRAPH`, проверяем `appliedModules` и `neighborOfSpanHash`.
- [ ] Обновить документацию (`docs/architecture/github-rag-modular.md`, `docs/guides/mcp-operators.md`, `README.md`): описать новый AST-пайплайн, call graph, параметры `codeAware*`, `neighbor*`, конфиг `github.rag.ast.*`, пример ответа с `neighborOfSpanHash` и новыми метаданными. Добавить раздел по operator-playbook (как понять, что step работал). → `docs/architecture/github-rag-modular.md`, `docs/guides/mcp-operators.md`, `README.md`
  - [ ] Диаграмма AST-aware пайплайна (fetch → tree-sitter → chunking → vector store → call graph) + таблица поддерживаемых языков/ограничений.
  - [ ] Operator playbook: признаки успешного AST шага (флаг `ast_available`, `appliedModules`), типичные ошибки загрузки либ и как их чинить.
  - [ ] README: новая секция «AST-aware indexing», примеры env-конфига `github.rag.ast.*`, команды для пересборки грамматик, обновление примеров `repo.rag_search` с `neighborOfSpanHash`.

## Wave 35 — RAG input normalization layer
Цель: научить MCP самоисправлять «свободные» запросы от LLM/агентов, чтобы инструменты RAG всегда получали валидный DTO и не падали от некорректных плейсхолдеров или параметров.

- [x] Добавить сервис `RepoRagToolInputSanitizer`, который принимает DTO (`RepoRagSearchInput`, `RepoRagGlobalSearchInput`, `RepoRagSimpleSearchInput`) и возвращает нормализованные значения: трим строк, дефолты для `neighbor*`, `multiQuery`, `generationLocale`, фильтры. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagToolInputSanitizer.java`
  - [x] Дополнительно нормализовать `RepoRagSearchFilters`: приводить `languages` к lower-case, выкидывать пустые/универсальные глобы (`"**/*"`, пустые строки) и не передавать фильтр дальше, если после очистки ограничений нет.
- [x] Встроить детектор/исправитель плейсхолдеров `instructionsTemplate`: `{{query}}` → `{{rawQuery}}`, неизвестные переменные удаляются или выдают user-friendly ошибку. → `RepoRagToolInputSanitizer`, `RepoRagSearchService.renderInstructions`
  - [x] Дефолтный режим — «жёлтая карточка»: незнакомые плейсхолдеры удаляются, а в ответе `RepoRagSearchResponse`/`repo.rag_*` добавляется `warnings[]` с перечислением исправлений.
- [x] Добавить словарь синонимов/локализаций для enum-полей (`neighborStrategy`, `translateTo`, языки фильтров) + валидатор диапазонов (topK, neighborLimit, multiQuery). → `RepoRagToolInputSanitizer`
- [x] Реализовать автозаполнение owner/name: при пустых значениях использовать последний READY namespace (`GitHubRepositoryFetchRegistry`) либо `displayRepo*` (для global). → `RepoRagToolInputSanitizer`, `RepoRagTools`
- [x] Интегрировать санитайзер в `RepoRagTools` перед формированием `SearchCommand`, добавить метрики/логи о применённых исправлениях. → `RepoRagTools`, `docs/architecture/github-rag-modular.md`
- [x] Покрыть санитайзер unit-тестами и обновить документацию: список поддерживаемых плейсхолдеров, правила нормализации, типовые автозамены. → `backend-mcp/src/test/java/...`, `docs/guides/mcp-operators.md`
- [x] Добавить обязательные тесты на всю новую логику нормализации (санитайзер, обновлённые фильтры, warnings): unit + интеграционные smoke-прогоны `repo.rag_*`, которые подтверждают автокоррекцию и выдачу предупреждений.

## Wave 36 — RAG parameter governance & operator presets
Цель: централизовать управление параметрами RAG, дать операторам явные профили (`conservative`/`balanced`/`aggressive`) и телеметрию их применения, при этом сохранить серверный контроль над граничными значениями.

### Option 1 — Profile-driven DTO и строгие стратегии
- [ ] **Новый внешний контракт**: оставляем только `repoOwner?`, `repoName?`, `rawQuery`, `profile` и служебный `conversationContext` (history/previousAssistantReply) в DTO `RepoRagSearchInput` / `RepoRagGlobalSearchInput`. Любая попытка передать legacy-поля (`topK`, `neighbor*`, `multiQuery` и т.д.) режется на уровне санитайзера с предупреждением «используй profile». Простые инструменты (`rag_search_simple`, `_simple_global`) вызывают тот же API, передавая `profile=defaultProfile`.
- [ ] **Профили как источник правды**: добавляем `GitHubRagProperties.parameterProfiles[]` с POJO `RagParameterProfile` (retrieval + post-processing + generation секции). Профиль сериализуется в `ResolvedSearchPlan`, кешируется в `RagParameterProfileRegistry` и упоминается в ответах `appliedModules += "profile:<name>"`.
- [ ] **Sanitizer → Strategy resolver**: `RepoRagToolInputSanitizer` теперь отвечает за (а) авто-подстановку owner/name через `GitHubRepositoryFetchRegistry`, (б) нормализацию `profile` (alias → canonical name, fallback к `defaultProfile`), (в) сбор диагностик, если профиль не найден или запрещён. Он больше не занимается числовыми клампами — этим занимается `RagParameterGuard` поверх уже резолвленной стратегии.
- [ ] **SearchService упрощается**: `RepoRagSearchService.SearchCommand` несёт `ResolvedSearchPlan plan`, а не набор primitive-полей. Методы `resolveTopK/neighborLimit/...` удаляются; вместо этого сервис просто исполняет план и сообщает `RagParameterGuard` о фактических значениях. Контролируемая часть (filters/pathGlobs, history) остаётся как есть.
- [ ] **RagParameterGuard**: guard получает план и реальные ограничения из `GitHubRagProperties` (maxTopK, maxNeighborLimit, maxContextTokens). Если план выходит за рамки, guard клампит значения, логирует структурированное событие и добавляет warning в ответ.
- [ ] **Миграция**: фаза 1 — вводим новые DTO (например, `RepoRagSearchInputV2`) и включаем их для MCP, но держим слой совместимости, который маппит старые поля → временный профиль `legacy`. Фаза 2 (после стабилизации) — удаляем legacy-код, оставляя только profile-driven API. Каждая фаза фиксируется в `CHANGELOG` и `docs/guides/mcp-operators.md` (таблица профилей + инструкция по переопределению через env).
- [ ] **Тест-пирамида**: unit (`RepoRagToolInputSanitizerTest`, `GitHubRagPropertiesTest`, `RagParameterGuardTest`), интеграция (`RepoRagSearchServiceTest` с mock profile, REST smoke `RepoRagToolsIT`), e2e (`repo.rag_search_simple`, `repo.rag_search` с кастомным профилем, global без профиля). Все сценарии проверяют `appliedModules` и новые warnings.

- [x] Конфигурационные профили: добавить в `GitHubRagProperties` блок `parameterProfiles`, где для каждого профиля задаём topK/topKPerQuery, minScore/minScoreByLanguage, multiQuery (enable + queries/maxQueries), neighbor (strategy/radius/limit) и code-aware настройки; завести `defaultProfile`. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/config/GitHubRagProperties.java`, `backend-mcp/src/main/resources/application.yaml`
  - [x] Реализовать POJO `RagParameterProfile`, биндинг списка профилей и валидацию при старте (уникальные имена, значения в допустимых диапазонах). Добавить unit-тест `GitHubRagPropertiesTest` на парсинг и дефолты.
  - [x] В `GitHubRagProperties` хранить предвычисленные `Map<String, RagParameterProfile>` + метод `resolveProfile(String)` с fallback на `defaultProfile`, кидать `IllegalStateException`, если профиль не найден и не указан дефолт.
  - [x] Прописать в `application.yaml` пример трёх профилей и задокументировать, как их переопределять через `application-prod.yaml`/env (`docs/guides/mcp-operators.md`).
- [ ] Новые DTO и инструментальный слой: заменить `RepoRagSearchInput`/`RepoRagGlobalSearchInput` на минимальный контракт (`repoOwner?`, `repoName?`, `rawQuery`, `profile`, `conversationContext`). Любые дополнительные поля считаем ошибкой. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RepoRagTools.java`, `RepoRagToolInputSanitizer.java`
  - [ ] `RepoRagToolInputSanitizer` авто-подставляет owner/name из последнего READY namespace, нормализует `profile` (synonyms → canonical) и выбрасывает/репортит legacy-поля с предупреждением «используй profile».
  - [ ] Ответ `RepoRagSearchResponse` всегда содержит `appliedModules += "profile:<name>"`, а `RepoRagTools` прокидывает профиль в MDC для трассировки и telco.
  - [ ] `RepoRagSearchService.SearchCommand` принимает `ResolvedSearchPlan plan`, историю и metadata; никаких отдельных параметров вроде `topK`/`neighborLimit` не остаётся.
- [ ] Совместимость и миграция: ввести временный слой `LegacyRagInputAdapter`, который переводит старые DTO (если они приходят из UI/старых агентов) в профиль `legacy`, и удалить его после стабилизации. Покрыть adapter smoke-тестом и описать cut-over в CHANGELOG.
- [ ] Серверные ограничения: добавить отдельный компонент `RagParameterGuard`, который будет фиксировать, какие параметры были отклонены/зажаты. → `backend-mcp/src/main/java/com/aiadvent/mcp/backend/github/rag/RagParameterGuard.java`, `RepoRagToolInputSanitizer.java`, `RepoRagSearchService.java`
  - [ ] Guard должен предоставлять методы `clampTopK`, `clampNeighborLimit`, `sanitizeMultiplier` и т.д., которые возвращают итоговое значение + reason; существующие проверки из `RepoRagSearchService` постепенно переводим на этот сервис, чтобы все граничные правила централизовались.
  - [ ] На основании guard-событий отправлять структурированные логи (JSON через `log.info`) и добавлять предупреждения пользователю, если запрос был урезан (topK > limit, neighborLimit > limit). Это поможет операторам видеть, когда профиль был недостаточен.
- [ ] Документация и тесты: обновить operator guide и архитектурный документ, добавить e2e-тест сценариев (`repo.rag_search_simple`, `repo.rag_search` с профилем, global без профиля). → `docs/guides/mcp-operators.md`, `docs/architecture/github-rag-modular.md`, `backend-mcp/src/test/java/com/aiadvent/mcp/backend/github/rag/*`
  - [ ] Тесты: unit на `RepoRagToolInputSanitizer` (профили + предупреждения), интеграционный `RepoRagSearchServiceTest` с фиктивным профилем `aggressive`, smoke-тест REST-интеграции (используя `RepoRagTools`) с проверкой `appliedModules`.
  - [ ] Документация: таблица профилей с назначением, инструкция по добавлению нового профиля и best practices (когда можно менять topK/neighborLimit), раздел про метрики guard.
  - [ ] CHANGELOG записи и operator playbook: как включать профиль из UI/CLI, как читать warnings/metrics, как откатиться к `defaultProfile`.
