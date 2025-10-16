# AI Advent Challenge

AI Advent Challenge — инициативный проект по развитию интерактивного LLM-чата и инфраструктуры вокруг него.

## Быстрые ссылки
- `docs/overview.md` — стартовая точка документации.
- `docs/backlog.md` — дорожная карта и волны задач.
- `docs/architecture/` — архитектура backend, frontend и интеграции с LLM.
- `docs/infra.md` — инфраструктура, окружения и CI/CD.
- `docs/processes.md` — соглашения по разработке и тестированию.
- `docs/CONTRIBUTING.md` — чек-лист по поддержке документации.
- `docs/faq.md` — часто задаваемые вопросы.

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
- **Structured Sync** — `POST /api/llm/chat/sync`, backend возвращает готовый JSON (`StructuredSyncResponse`) с заголовками `X-Session-Id` и `X-New-Session`. Ответ следует схеме Spring AI `BeanOutputConverter`, для OpenAI используется `responseFormat(JSON_SCHEMA)`/`strict=true`, для ZhiPu — формат подмешивается в промпт. Пример структуры и параметры ретраев описаны в `docs/infra.md`.

Обе формы принимают одинаковый payload (`sessionId`, `message`, `provider`, `model`, `options`). Если `sessionId` не передан, создаётся новый диалог.

## Пользовательские параметры sampling

- Панель «Параметры sampling» содержит sliders/inputs для `temperature`, `topP`, `maxTokens`. Значения подтягиваются из каталога провайдера и автоматически обновляются при смене модели.
- Изменённые числа передаются в `options.*` и выделяются в истории сообщений (чип `Temp … · TopP … · Max …` для ассистента).
- Кнопка «Сбросить к дефолтам» очищает overrides, поэтому блок `options` не попадает в запрос, а backend использует штатные значения из `app.chat.providers`.
- Логика сборки payload вынесена в `frontend/src/lib/chatPayload.ts` и прикрыта тестами: `npm run test:unit` (Vitest) и `npx playwright test sampling-overrides.spec.ts`.

## Репозитории и сервисы
- `backend/` — Spring Boot приложение (Java 21).
- `frontend/` — веб-клиент на React + Vite.
- `local-rag/` — вспомогательные утилиты и эксперименты с RAG (при необходимости).

Подробнее о организации кода и задачах — в документации.
