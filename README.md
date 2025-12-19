# AI Advent Challenge

AI Advent Challenge — инициативный проект интерактивного LLM‑чата: веб‑клиент, Telegram‑бот и набор MCP‑сервисов для работы с кодом, GitHub и агентными сценариями.

## Что за проект и зачем он нужен
- Универсальный LLM‑чат с несколькими режимами (stream, sync, structured) и поддержкой pluggable провайдеров (OpenAI, zhipu.ai и др.).
- RAG вокруг GitHub: индексация репозиториев, AST-aware разбивка, call graph и поиск по коду.
- Конструктор флоу/агентов, который позволяет собирать цепочки шагов и управлять ими из UI или Telegram.
- Два клиента: веб‑UI для полноценной работы и Telegram‑бот для быстрых запросов и голосового ввода.

## Архитектура и используемые технологии
- **Backend** — Spring Boot (Java 22), Clean Architecture (controller → service → domain → persistence), Spring AI для LLM, Liquibase, Postgres + pgvector, опционально Redis для кеша токенов. Включены RAG‑модули (Tree‑sitter AST, Neo4j call graph), конструктор флоу и MCP‑интеграции.
- **MCP‑сервисы** (`backend-mcp/`) — HTTP MCP (GitHub, coding, agent/flow/insight/notes). Для AST понадобятся сабмодули в `backend-mcp/treesitter`.
- **Frontend** — React + Vite (TypeScript), SSE‑клиент для стрима, собирается в статический бандл и отдаётся Nginx‑ом.
- **Инфраструктура** — Docker Compose поднимает backend, frontend, Postgres, Redis и MCP; опционально Neo4j для графа вызовов. Поддержан локальный профиль с Ollama/vLLM.
- **Паттерны** — многопровайдерный слой `app.chat.providers`, SSE‑стриминг, sync/structured ответы, гибкая пост‑обработка (sampling overrides, neighbor expansion).

## Развертывание

### Быстрый старт (Docker Compose, prod‑профиль)
1) Скопируйте `.env.example` → `.env` и заполните ключи: минимум `OPENAI_API_KEY` или `ZHIPU_API_KEY`; для GitHub MCP — `GITHUB_PAT`; для бота — `TELEGRAM_*`.
2) Запустите стек:
   ```bash
   docker compose up --build
   ```
3) UI будет доступен на `http://localhost:4179`, API проксируется как `/api`.

### Локальная разработка
- **Локальный профиль с Ollama/vLLM**  
  ```bash
  cp docs/env/local.env .env
  docker compose -f docker-compose.yml -f docker-compose.local.yaml up --build
  ```
- **Backend/Frontend вне контейнеров**  
  Поднимите базу и Redis:
  ```bash
  docker compose -f docker-compose.yml -f docker-compose.local.yaml up -d postgres redis
  ```
  Backend:  
  ```bash
  APP_DB_URL=jdbc:postgresql://localhost:5434/ai_advent \
  SPRING_DATA_REDIS_HOST=localhost \
  SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
  ```
  Frontend:  
  ```bash
  cd frontend
  npm install
  VITE_API_BASE_URL=/api npm run dev
  ```
- **AST/Tree‑sitter** (нужно, если трогаете GitHub RAG AST):  
  ```bash
  git submodule update --init --recursive backend-mcp/treesitter
  cd backend-mcp && ./gradlew treeSitterBuild treeSitterVerify bootJar
  ```

### Деплой на сервер
Используется тот же compose. На целевой машине: `git pull && docker compose up -d --build` с prod‑значениями в `.env`. Подробнее — `docs/infra.md`.

## Настройка провайдеров и моделей
- Конфигурация лежит в `app.chat.providers` (Spring). Каждый запрос может указывать `provider` и `model`; UI и Telegram дают переключение из меню.
- **OpenAI**: `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_DEFAULT_MODEL`, `OPENAI_TEMPERATURE/TOP_P/MAX_TOKENS`, `OPENAI_TIMEOUT`.
- **Zhipu (z.ai)**: `ZHIPU_BASE_URL`, `ZHIPU_COMPLETIONS_PATH`, `ZHIPU_API_KEY`, `ZHIPU_DEFAULT_MODEL`, `ZHIPU_TEMPERATURE/TOP_P/MAX_TOKENS`, `ZHIPU_TIMEOUT`.
- Режимы моделей (stream/sync/structured) задаются в конфиге; usage считается либо по нативным данным, либо через fallback токенизацию (см. `docs/infra.md`).

## Минимальный набор переменных окружения
- **База данных**: `APP_DB_URL`, `APP_DB_USERNAME`, `APP_DB_PASSWORD` (по умолчанию соответствуют сервису `postgres` из compose).
- **LLM**: один из ключей `OPENAI_API_KEY` или `ZHIPU_API_KEY` + дефолтные модели/URLs выше.
- **Frontend**: `VITE_API_BASE_URL=/api` (установлен в `.env.example`).
- **Опционально**: `GITHUB_PAT` для GitHub MCP, `TELEGRAM_*` для бота, `GITHUB_RAG_GRAPH_*`/Neo4j для call graph, `PROFILE_DEV_ENABLED/PROFILE_BASIC_TOKEN` для dev‑режима профилей. Полный список — в `.env.example` и `docs/infra.md`.

## Как зайти в UI
- После `docker compose up --build`: `http://localhost:4179` (прокси на backend `/api`). Backend слушает `8080`, но прямой доступ не нужен.
- Для dev‑режима профилей можно включить `PROFILE_DEV_ENABLED=true` и `PROFILE_BASIC_TOKEN=...`; тогда запросы требуют заголовок `X-Profile-Auth`, UI показывает баннер «Dev session».

## Telegram‑бот
1) В `.env` выставьте `TELEGRAM_BOT_ENABLED=true`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_BOT_WEBHOOK_URL` (публичный HTTPS) и `TELEGRAM_BOT_WEBHOOK_SECRET`. Голос: `TELEGRAM_STT_MODEL`, `TELEGRAM_STT_FALLBACK_MODEL` (опционально), `TELEGRAM_STT_LANGUAGE`.
2) Запустите backend (compose или вручную).
3) Зарегистрируйте webhook:
   ```bash
   curl -X POST http://localhost:8080/api/telegram/webhook/register
   ```
4) Команды: `/start`, `/new`, `/menu`; inline‑меню для выбора провайдера/модели/режима/MCP/sampling. Голосовые сообщения транскрибируются и отправляются как текст.
Подробнее про UX — `docs/guides/telegram-bot.md`.

## Дополнительные материалы
- Обзор: `docs/overview.md`
- Архитектура: `docs/architecture/`
- Инфраструктура и переменные: `docs/infra.md`
- Бэклог: `docs/backlog.md`
- Гайд по Telegram: `docs/guides/telegram-bot.md`
