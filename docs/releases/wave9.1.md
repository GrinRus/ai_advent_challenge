# Wave 9.1 — Agent Catalog & Flow Builder

Дата: 2025‑02‑?? (уточните при публикации).

## Ключевые изменения

### Backend
- Реализован REST-каталог агентов (`/api/agents/**`): CRUD определений, управление версиями (создание, публикация, депрекация), хранение capabilities и аудитных полей `createdBy`/`updatedBy`.
- `FlowDefinitionService` и `FlowLaunchPreviewService` проверяют, что шаги используют только активные опубликованные `agentVersionId`, и возвращают расширенные метаданные (имя агента, модель, системный промпт).
- Liquibase миграция добавила `created_by`/`updated_by` в `agent_definition`/`agent_version`, а UI/бэкенд сохраняют эти значения.

### Frontend
- Новый раздел `Flows / Agents`: список определений с фильтрами, формы создания/редактирования, управление версиями (создать → опубликовать → депрецировать), редактор capabilities.
- Конструктор флоу (`Flows / Definitions`) полностью переведён на форму: выбор `agentVersionId` из каталога, визуальное редактирование промптов, памяти и переходов, автоматическая валидация JSON.
- `apiClient.ts` получил методы каталога и кэширование запросов; добавлены unit‑тесты для преобразования схемы (`flowDefinitionForm`).

## Миграции / DevOps
- Liquibase changeSet `0010-add-agent-audit-fields` (добавляет audit-поля).
- Обновлены `docs/infra.md`, `docs/architecture/flow-definition.md`, `docs/processes.md`.

## Тестирование
- `./gradlew test`
- `npm run test:unit`
- Ручная проверка UI: `Flows / Agents` (создание → публикация → депрецирование), `Flows / Definitions` (добавление шагов и публикация).

## Следующие шаги
- Wave 9.2: расширение управления сессиями (pause/resume/approve), telemetry snapshot в API и UI Flow Workspace.
- Завершить релизную заметку датой и добавить ссылку в `docs/overview.md` или корпоративный Notion (по процессу компании).
