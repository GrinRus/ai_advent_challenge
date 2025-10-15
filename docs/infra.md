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
- Backend использует Spring AI и OpenAI-совместимый провайдер z.ai (`glm-4.6`). Настроить подключение можно через переменные окружения:
  - `LLM_BASE_URL` — базовый URL API провайдера (по умолчанию `https://api.z.ai`).
  - `LLM_CHAT_COMPLETIONS_PATH` — относительный путь к чат-эндпоинту (по умолчанию `/api/paas/v4/chat/completions`).
  - `LLM_API_KEY` — ключ доступа (обязателен).
  - `LLM_MODEL`, `LLM_TEMPERATURE`, `LLM_TOP_P`, `LLM_MAX_TOKENS` — параметры запроса к модели.
- Память LLM-чата управляется Spring AI `MessageWindowChatMemory` и хранится в таблице `chat_memory_message`:
  - `CHAT_MEMORY_WINDOW_SIZE` — размер скользящего окна сообщений, передаваемых модели (по умолчанию `20`).
  - `CHAT_MEMORY_RETENTION` — максимальное время простоя диалога до очистки окна (ISO-длительность, по умолчанию `PT6H`).
  - `CHAT_MEMORY_CLEANUP_INTERVAL` — периодичность фоновой задачи очистки (по умолчанию `PT30M`).
  - Метрики `chat_memory_evictions_total` и `chat_memory_conversations` доступны через Spring Boot Actuator.
- Эти параметры можно задать в `.env` и они автоматически попадут в контейнер backend через `docker-compose.yml`.
- Для frontend достаточно установить `VITE_API_BASE_URL` (по умолчанию `/api`). SSE-подписка выполняется на эндпоинт `POST /api/llm/chat/stream`, который возвращает события `session`, `token`, `complete`, `error`.

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
