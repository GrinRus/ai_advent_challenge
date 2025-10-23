# Flow Definition Schema (Wave 9.1)

Этот документ фиксирует актуальную структуру определения мультиагентного флоу, которую использует оркестратор (Wave 9.1). Конфигурации хранятся в PostgreSQL (`flow_definition.definition_jsonb`), редактируются через UI и валидируются на backend’е (`FlowDefinitionParser` + JSON-проверки).

## Общие правила

- Схема хранится в JSON (опционально импорт/экспорт YAML с эквивалентной структурой).
- Флоу исполняются только в синхронном режиме (`syncOnly=true`) — стриминговых шагов нет.
- Версионирование управляется полями `status` (`DRAFT|PUBLISHED`), `is_active` и историей (`flow_definition_history`).
- Идентификаторы `flowId`, `steps[].id`, `steps[].agentVersionId`, `transitions.*.next` — slug или UUID, уникальные внутри флоу.
- Каждый шаг обязан ссылаться на опубликованную активную версию агента (`agent_version`) через `agentVersionId`.

## Верхний уровень

| Поле             | Тип / описание                                                                                   |
|------------------|---------------------------------------------------------------------------------------------------|
| `flowId`         | `string` (slug/UUID) — логический идентификатор шаблона.                                          |
| `version`        | `integer` ≥ 1 — ручное или автоматическое инкрементирование.                                      |
| `metadata`       | `title`, `description`, `tags[]` — служебные поля для UI/поиска.                                   |
| `syncOnly`       | `boolean`, всегда `true`.                                                                           |
| `memory.sharedChannels[]` | Настройки долговременных каналов (`id`, `retentionVersions`, `retentionDays`).            |
| `triggers`       | Флаги включения запуска из UI/API.                                                                 |
| `defaults`       | Общий контекст (`context`) и дефолтные оверрайды (`overrides.options`).                            |
| `launchParameters[]` | Описание входных параметров при запуске (`name`, `type`, `required`, `description`).           |
| `steps[]`        | Упорядоченный список шагов (см. ниже).                                                             |
| `outputs`        | Настройки итогового payload’а (опционально).                                                       |

## Структура шага

```jsonc
{
  "id": "gather_requirements",            // slug/UUID
  "name": "Сбор требований",
  "agentVersionId": "0f9d...-uuid",       // ссылка на agent_version.id
  "prompt": "Ты бизнес-аналитик...",
  "overrides": {
    "temperature": 0.2,                   // опциональные числовые параметры
    "topP": 0.9,
    "maxTokens": 1024
  },
  "memoryReads": [
    { "channel": "context", "limit": 5 }
  ],
  "memoryWrites": [
    { "channel": "context", "mode": "AGENT_OUTPUT" },
    { "channel": "decision-log", "mode": "STATIC", "payload": { "event": "bootstrap" } }
  ],
  "transitions": {
    "onSuccess": { "next": "generate_solution", "complete": false },
    "onFailure": { "next": "abort_flow", "fail": true }
  },
  "maxAttempts": 1
}
```

Поля:

- `agentVersionId` — обязательная строка UUID. Backend проверяет, что версия существует, опубликована и принадлежит активному агенту (`FlowDefinitionService.validateAgentVersions`).
- `prompt` — системный промпт шага; при отсутствии используется `systemPrompt` из версии агента.
- `overrides.temperature/topP/maxTokens` — переопределения опций вызова для конкретного шага.
- `memoryReads[]` — список каналов памяти с лимитом сообщений (по умолчанию 10).
- `memoryWrites[]` — запись в память. `mode`: `AGENT_OUTPUT` (сохраняется ответ агента) или `STATIC` (фиксированный payload).
- `transitions.onSuccess` / `transitions.onFailure` — определяют следующее состояние. Если `next` пустой и `complete`/`fail` не переопределены, применяются дефолты (`completeOnSuccess=true`, `failFlowOnFailure=true`).
- `maxAttempts` — количество автоматических повторов шага (≥1).

## Минимальный пример

```json
{
  "flowId": "solution-workshop",
  "version": 4,
  "metadata": {
    "title": "Solution Design Workshop",
    "description": "Флоу согласования требований и генерации решения"
  },
  "syncOnly": true,
  "launchParameters": [
    { "name": "problemStatement", "type": "string" },
    { "name": "constraints", "type": "array", "required": false }
  ],
  "steps": [
    {
      "id": "gather_requirements",
      "name": "Сбор требований",
      "agentVersionId": "aaaaaaaa-1111-2222-3333-444444444444",
      "prompt": "Ты бизнес-аналитик. Собери контекст и требования.",
      "memoryReads": [{ "channel": "context", "limit": 5 }],
      "memoryWrites": [{ "channel": "context", "mode": "AGENT_OUTPUT" }],
      "transitions": {
        "onSuccess": { "next": "generate_solution" },
        "onFailure": { "next": "abort_flow", "fail": true }
      }
    },
    {
      "id": "generate_solution",
      "name": "Проектирование решения",
      "agentVersionId": "bbbbbbbb-1111-2222-3333-444444444444",
      "memoryReads": [
        { "channel": "context", "limit": 5 },
        { "channel": "decision-log", "limit": 20 }
      ],
      "memoryWrites": [{ "channel": "decision-log", "mode": "AGENT_OUTPUT" }],
      "overrides": { "temperature": 0.2, "maxTokens": 1500 },
      "transitions": {
        "onSuccess": { "next": "review_solution" },
        "onFailure": { "next": "refine_solution", "fail": false }
      }
    },
    {
      "id": "review_solution",
      "name": "Ревью решения",
      "agentVersionId": "cccccccc-1111-2222-3333-444444444444",
      "transitions": { "onSuccess": { "complete": true }, "onFailure": { "next": "abort_flow" } }
    },
    {
      "id": "abort_flow",
      "name": "Прерывание",
      "agentVersionId": "dddddddd-1111-2222-3333-444444444444",
      "transitions": { "onSuccess": { "complete": true } }
    }
  ],
  "outputs": {
    "summaryFields": ["steps.generate_solution.output.summary"],
    "resultPayload": {
      "solution": "$steps.generate_solution.output.draft",
      "nextActions": "$steps.review_solution.output.recommendations"
    }
  }
}
```

## Требования к валидации

1. **Агенты** — все `agentVersionId` должны ссылаться на существующие версии со статусом `PUBLISHED`, а их `agent_definition` обязан быть активным. Backend возвращает `422 Unprocessable Entity` при нарушении.
2. **Последовательность шагов** — массив `steps` должен содержать минимум один элемент. `startStepId` должен ссылаться на существующий шаг (если не указан, UI/парсер используют первый шаг).
3. **JSON-поля** — редактор UI предоставляет текстовые поля для JSON (memory/transition overrides) с предварительной валидацией; backend дополнительно проверяет типы.
4. **Retry/maxAttempts** — в Wave 9.1 поддерживается только простая форма `maxAttempts`. Расширенные `retryPolicy`/`timeoutSec` из ранних ADR исключены.

## Интеграция с UI

- Страница `Flows / Definitions` строит форму на основе этих правил: шаги редактируются через выпадающий список опубликованных агентов, промпт и память редактируются inline.
- При сохранении UI собирает JSON через helper `buildFlowDefinition`, повторно валидирующий числовые и JSON-поля.
- Публикация флоу (`POST /api/flows/definitions/{id}/publish`) доступна только после успешного прохождения серверной проверки (активные агенты, валидные переходы).

## Связанные материалы

- [docs/infra.md](../infra.md) — архитектурный обзор оркестрации, API каталога агентов и UI разделов.  
- [docs/processes.md](../processes.md) — рабочие процедуры по онбордингу новых агентов/флоу.
