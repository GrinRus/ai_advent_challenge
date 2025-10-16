# Инфраструктура проекта

# Навигация
- Архитектура компонентов описана в `docs/architecture/backend.md`, `docs/architecture/frontend.md` и `docs/architecture/llm.md`.
- Общие процессы и требования к документации — в `docs/processes.md` и `docs/CONTRIBUTING.md`.
- Ответы на частые вопросы вынесены в `docs/faq.md`.

## Сервисы и порты
- Backend (Spring Boot) — сервис `backend`, порт `8080`.
- Frontend (React + Vite + Nginx) — сервис `frontend`, порт `4179`.
- Postgres + pgvector — сервис `postgres`, порт `5434` (наружный порт; внутри контейнера используется `5432`).

## Docker Compose
Скопируйте `.env.example` в `.env`, откорректируйте значения переменных при необходимости.

Для локального запуска всех компонентов выполните:

```bash
docker compose up --build
```

Переменные окружения для backend (URL, логин, пароль БД) передаются через `APP_DB_*` и уже заданы в `docker-compose.yml`. При необходимости вынесите их в `.env` файл и подключите через ключ `env_file`.

Frontend контейнер проксирует все запросы `/api/*` на backend, поэтому приложение доступно по адресу `http://localhost:4179`, а API — через `http://localhost:4179/api`.

## Настройка LLM-чата
- Backend использует Spring AI с фабрикой `ChatProviderService`, позволяющей переключаться между провайдерами без изменения прикладного кода. Конфигурация описана в `application.yaml` (`app.chat.providers`). По умолчанию активен `zhipu` (z.ai), параллельно доступен `openai`.
- Каждый провайдер хранит параметры подключения: базовый URL, ключ, таймауты, дефолтную модель и список доступных моделей с оценочной стоимостью. Все значения можно переопределить переменными окружения:
  - OpenAI: `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_DEFAULT_MODEL`, `OPENAI_TEMPERATURE`, `OPENAI_TOP_P`, `OPENAI_MAX_TOKENS`, `OPENAI_TIMEOUT`.
  - Zhipu (z.ai): `ZHIPU_BASE_URL`, `ZHIPU_COMPLETIONS_PATH`, `ZHIPU_API_KEY`, `ZHIPU_DEFAULT_MODEL`, `ZHIPU_TEMPERATURE`, `ZHIPU_TOP_P`, `ZHIPU_MAX_TOKENS`, `ZHIPU_TIMEOUT`.
  Переменные заданы в `.env.example` и автоматически прокидываются в контейнер backend через `docker-compose.yml`.
- Ориентировочные тарифы и сценарии использования:

  | Провайдер  | Модель        | Класс    | Стоимость (вход/выход, $ за 1K токенов) | Рекомендации |
  |------------|---------------|----------|------------------------------------------|--------------|
  | zhipu.ai   | GLM-4.6       | pro      | 0.0006 / 0.0022                          | расширенные сценарии, длинный контекст и код |
  | zhipu.ai   | GLM-4 Air     | standard | 0.0008 / 0.0008                          | основной ассистент, баланс качества и цены |
  | zhipu.ai   | GLM-4 Flash   | budget   | 0.0004 / 0.0004                          | черновые ответы, предпросмотры UI |
  | OpenAI     | GPT-4o Mini   | budget   | 0.00015 / 0.0006                         | быстрые пользовательские фичи, демо |
  | OpenAI     | GPT-4o        | pro      | 0.0012 / 0.0032                          | сложные задачи, критичные запросы |
- UI может передавать `provider` и `model` на каждый запрос. Эти значения сохраняются в истории (`chat_message.provider`, `chat_message.model`) и приходят обратно в SSE-события (`session`, `token`, `complete`, `error`) для упрощённой диагностики.
- Память LLM-чата управляется Spring AI `MessageWindowChatMemory` и хранится в таблице `chat_memory_message`:
  - `CHAT_MEMORY_WINDOW_SIZE` — размер скользящего окна сообщений, передаваемых модели (по умолчанию `20`).
  - `CHAT_MEMORY_RETENTION` — максимальное время простоя диалога до очистки окна (ISO-длительность, по умолчанию `PT6H`).
  - `CHAT_MEMORY_CLEANUP_INTERVAL` — периодичность фоновой задачи очистки (по умолчанию `PT30M`).
  - Метрики `chat_memory_evictions_total` и `chat_memory_conversations` доступны через Spring Boot Actuator.
- Для frontend достаточно установить `VITE_API_BASE_URL` (по умолчанию `/api`). SSE-подписка выполняется на эндпоинт `POST /api/llm/chat/stream`.

## Тестирование
- `./gradlew test` прогоняет smoke-тест `ChatStreamControllerIntegrationTest` на MockMvc и HTTP e2e-сценарий `ChatStreamHttpE2ETest`, проверяющие потоковые ответы и сохранение истории.
- Для локального запуска используйте JDK 21 (на macOS: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`), тесты работают на H2 и заглушенном Spring AI клиенте, поэтому не требуют реального LLM.

## GitHub Actions
Workflow `.github/workflows/ci.yml` выполняет следующие шаги:
1. Прогон backend-тестов (`./gradlew test`, Testcontainers).
2. Сборка frontend (`npm run build`).
3. Ручной деплой на VPS по событию `workflow_dispatch`: на сервере выполняется `git pull`, пересборка Docker-образов и запуск `docker compose`.

Для деплоя необходимо определить секреты:
- `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH` (и опционально `DEPLOY_PORT`).

На сервере должен существовать клон репозитория. Скрипт деплоя выполняет `git pull` и `docker compose up -d`.
