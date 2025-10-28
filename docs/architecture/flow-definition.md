# Flow Definition Blueprint (Wave 14)

Wave 14 закрепил типизированный runtime (`FlowBlueprint` и DTO шагов), добавил управление retention политиками памяти и автоматическое слежение за `schemaVersion`. Этот документ фиксирует актуальную схему, валидацию и API-контракты.

## Хранение и версионирование

- Таблица `flow_definition` содержит поля:
  - `definition JSONB` — сериализованный `FlowBlueprint`;
  - `blueprint_schema_version INT` — актуальная версия схемы (>=1);
  - статус (`DRAFT|PUBLISHED`), признак активности (`is_active`), историю изменений.
- `FlowDefinitionService` при `create/update/publish` нормализует и поднимает `schemaVersion` до `FlowBlueprintSchemaVersion.CURRENT`, а входные значения валидируются на совместимость; попытка записать устаревшую или неподдерживаемую версию отклоняется 422 ответом и пишется в телеметрию конструктора.
- История `flow_definition_history` хранит неизменяемые снапшоты той же структуры, включая `blueprint_schema_version`.
- CLI `app.flow.migration.cli.*` конвертирует легаси-записи в `FlowBlueprint`, поддерживает dry-run, выборочную миграцию и обновление истории.

## Структура `FlowBlueprint`

```jsonc
{
  "schemaVersion": 2,
  "metadata": {
    "title": "Lead Qualification",
    "description": "Gather context and run research",
    "tags": ["sales", "research"]
  },
  "startStepId": "gather_context",
  "syncOnly": true,
  "launchParameters": [
    {
      "name": "company",
      "type": "string",
      "required": true,
      "description": "Target company name",
      "schema": { "type": "string", "minLength": 1 },
      "defaultValue": null
    }
  ],
  "memory": {
    "sharedChannels": [
      { "id": "shared", "retentionVersions": 5, "retentionDays": 7 }
    ]
  },
  "steps": [
    {
      "id": "gather_context",
      "name": "Gather Context",
      "agentVersionId": "1c9a...-uuid",
      "prompt": "Collect lead profile using the provided parameters.",
      "overrides": {
        "temperature": 0.3,
        "maxTokens": 600
      },
      "interaction": {
        "type": "INPUT_FORM",
        "title": "Missing context",
        "payloadSchema": { "$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object" },
        "dueInMinutes": 15
      },
      "memoryReads": [
        { "channel": "shared", "limit": 5 }
      ],
      "memoryWrites": [
        { "channel": "shared", "mode": "AGENT_OUTPUT" }
      ],
      "transitions": {
        "onSuccess": { "next": "perform_research" },
        "onFailure": { "fail": true }
      },
      "maxAttempts": 2
    }
  ]
}
```

### Поля верхнего уровня

| Поле | Описание |
|------|----------|
| `schemaVersion` | Версия DSL. При отсутствии интерпретируется как `1`. |
| `metadata` | `title`/`description`/`tags`. Используются UI и поиском. |
| `startStepId` | Идентификатор первого шага. Если не задан, используется первый элемент `steps`. |
| `syncOnly` | Синхронный режим (стриминг для флоу появится в последующих волнах). |
| `launchParameters[]` | Типизированные параметры запуска (имя, описание, JSON Schema, дефолт). |
| `memory.sharedChannels[]` | Каналы с ретеншеном по версиям/дням. `FlowMemoryService` автоматически добавляет каналы `conversation`/`shared`, объединяет дубликаты и применяет настроенный ретеншен при каждой записи. |
| `steps[]` | Упорядоченный список шагов (см. ниже). |

### Шаг (`FlowBlueprintStep`)

- `agentVersionId` — UUID опубликованной версии агента. `FlowBlueprintValidator` проверяет, что версия существует, имеет статус `PUBLISHED`, а соответствующий агент активен.
- `prompt` — текстовый промпт шага. Если пуст, используется `systemPrompt` агента.
- `overrides` — частичный `ChatRequestOverrides` (`temperature`, `topP`, `maxTokens`).
- `interaction` — настройки HITL (`FlowInteractionConfig`): тип (`INPUT_FORM|APPROVAL|...`), JSON Schema формы, подсказки и SLAs.
- `memoryReads` / `memoryWrites` — конфигурация чтения и записи памяти (канал, лимит, режим `AGENT_OUTPUT|USER_INPUT|STATIC`).
- `transitions` — `onSuccess`/`onFailure` c указанием следующего шага и флагов `complete|fail`.
- `maxAttempts` — целое ≥1; ретраи с шагом backoff реализуются на уровне оркестратора.

## Runtime: память и ретеншен

- Blueprint-конфигурация памяти (`FlowMemoryConfig`) прокладывается до оркестратора при запуске сессии. `AgentOrchestratorService` вызывает `FlowMemoryService.initializeSharedChannels`, передавая собранные `sharedChannels` из документа.
- Если инициализация не выполнялась (например, при прямых append-операциях), `FlowMemoryService` извлекает shared-каналы напрямую из blueprint, добавляет дефолтные `conversation` и `shared`, а затем кеширует политику в памяти.
- Для каждого канала учитываются два ограничения: `retentionVersions` (количество последних версий, которое хранится) и `retentionDays` (TTL). Отсутствующие или невалидные значения заменяются дефолтами `FlowMemoryChannelConfig.DEFAULT_RETENTION_VERSIONS (=10)` и `DEFAULT_RETENTION_TTL (=P30D)`.
- Очистка истории выполняется при каждом `append(...)`: сервис удаляет записи, чьи версии выпали из окна, и записи со временем создания старше вычисленного `cutoff`.
- Интеграционные тесты (`FlowMemoryServiceTest`, `FlowDefinitionControllerV2IntegrationTest`) проверяют, что retentions из blueprint действительно применяются и доступны через REST API.

## Валидация

- Сервис `FlowBlueprintValidator`:
  - нормализует blueprint через `FlowBlueprintCompiler` (структурные проверки, ссылки на существующие шаги);
  - проверяет, что все `agentVersionId` валидны и опубликованы;
  - возвращает `FlowValidationIssue` с кодами ошибок, включая `BLUEPRINT_ERROR` для некорректных переходов/структурных сбоев;
  - возвращает список `FlowValidationIssue` (errors/warnings) для UI.
- CLI `app.flow.migration.cli.*` использует тот же валидатор, поддерживает параметры `dry-run`, `include-history` и остановку при ошибке.

## API и Feature Flags

- `GET /api/flows/definitions/{id}` — по умолчанию возвращает JSON (для обратной совместимости). При `app.flow.api.v2-enabled=true` отдаёт `FlowDefinitionResponseV2` с `FlowBlueprint`.
- `PUT /api/flows/definitions/{id}` — принимает JSON/blueprint в зависимости от feature-flag. UI работает с V2.
- `GET /api/flows/definitions/{id}/launch-preview` — V2 включает `FlowLaunchPreviewResponseV2` с blueprint и детальным расчётом (агенты, стоимость по шагам).
- `GET /api/flows/definitions/reference/memory-channels` и `/reference/interaction-schemes` — справочники для конструктора.
- `POST /api/flows/definitions/validation/step` — step validator; возвращает ошибки и предупреждения для конкретного шага.

## Телеметрия и аудит конструктора

- `FlowDefinitionService` и `AgentCatalogService` фиксируют все операции конструктора через `ConstructorTelemetryService`.
- Основные счётчики (Micrometer):
  - `constructor_flow_blueprint_saves_total{action=create|update|publish}` — успешные сохранения/публикации blueprint.
  - `constructor_agent_definition_saves_total{action=create|update|status}` — изменения карточки агента.
  - `constructor_agent_version_saves_total{action=create|update|publish|deprecate}` — операции с версиями агента.
  - `constructor_validation_errors_total{domain=flow_blueprint|agent_definition|agent_version,stage=...}` — любые ошибки валидации или конфликтов (422/409/404) в сервисах конструктора.
  - `constructor_user_events_total{event=flow_blueprint_create|agent_version_publish|...}` — агрегированный поток пользовательских действий для построения графиков активности.
- Лёгкий аудит пишется в отдельный логгер `ConstructorAudit` (см. `application.yaml`). Формат: `event=... action=... actor=... id=...`, что позволяет отправлять поток в SIEM/ELK и строить расследования.
- При добавлении новых операций достаточно вызвать один из методов `ConstructorTelemetryService` —计 etrics и аудит обновятся автоматически.

## Инструменты разработчика

- **Migration CLI:** `app.flow.migration.cli.enabled=true`. Основные параметры:
  - `dry-run` — по умолчанию true, только логирует изменения;
  - `definition-ids` — список UUID через запятую, иначе обрабатываются все флоу;
  - `include-history` — обновлять `flow_definition_history`;
  - `fail-on-error` — прерывать миграцию при первой ошибке.
- **Контроль версий схемы:** изменение структуры blueprint сопровождаем bump `schemaVersion` и обновление мигратора.

## MCP-сервисы, связанные с флоу

- Внутренний сервер **Flow Ops MCP** предоставляет набор инструментов `flow_ops.*` (list/diff/validate/publish/rollback). Сервис живёт в модуле `backend-mcp` и развёртывается как streamable HTTP приложение (`Spring Main Class: com.aiadvent.mcp.backend.FlowOpsMcpApplication`).
- Через Spring AI backend может включить Flow Ops для сценариев оркестратора. В `application.yaml` описан HTTP-коннекшн `spring.ai.mcp.client.streamable-http.connections.flowops`, который указывает на `FLOW_OPS_MCP_HTTP_BASE_URL` и endpoint `/mcp`.
- При разработке флоу убедитесь, что:
  - версия агента, указанная в шаге, имеет нужные MCP-инструменты (в каталоге агента появилось capability `flow.ops.tools`);
  - JSON-схемы инструментов синхронизированы с `tool_schema_version` (Liquibase миграции Wave 16 создают записи для `flow_ops.*`).
- Для тестов используйте stub `SyncMcpToolCallbackProvider`: он должен возвращать `flow_ops.*` вместе с `perplexity_search`, чтобы проверить backward-совместимость research-режима (см. `backend/src/test/java/com/aiadvent/backend/mcp/service/McpCatalogServiceTest`).

## Связанные документы

- `docs/infra.md` — флаги CLI, общая инфраструктура, SLA по перезапуску CLI.
- `docs/processes.md` — чек-лист тестирования конструктора и уведомления для миграций.
- `docs/backlog.md` (Wave 14) — статус задач по typed-конструктору, retention и миграции.
