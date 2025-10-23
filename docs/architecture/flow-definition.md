# Flow Definition Schema

Документ описывает формат конфигурации мультиагентных флоу, используемый оркестратором (Wave 9). Схема предназначена для хранения в PostgreSQL (`flow_definition.definition_jsonb`) с возможностью редактирования из UI.

## Общие требования
- Формат хранения — JSON, допускается импорт/экспорт в YAML с эквивалентной структурой.
- Все флоу работают исключительно в синхронном режиме (`syncOnly: true`), стриминговые вызовы запрещены.
- Конфигурация версионируется: рабочая версия `published`, черновики — `draft`.
- Все идентификаторы (`flowId`, `step.id`, `agentRef`, `transition.target`) — строковые UUID или slug, уникальные внутри флоу.

## JSON Schema (draft 2020-12)
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://aiadvent.dev/schema/flow-definition.json",
  "title": "FlowDefinition",
  "type": "object",
  "required": ["flowId", "version", "metadata", "syncOnly", "steps"],
  "properties": {
    "flowId": {
      "type": "string",
      "pattern": "^[a-z0-9-]{3,64}$",
      "description": "Уникальный идентификатор флоу."
    },
    "version": {
      "type": "integer",
      "minimum": 1
    },
    "metadata": {
      "type": "object",
      "required": ["title", "description"],
      "properties": {
        "title": { "type": "string", "maxLength": 120 },
        "description": { "type": "string", "maxLength": 2000 },
        "tags": {
          "type": "array",
          "items": { "type": "string" },
          "uniqueItems": true
        }
      },
      "additionalProperties": false
    },
    "syncOnly": {
      "type": "boolean",
      "const": true
    },
    "memory": {
      "type": "object",
      "properties": {
        "sharedChannels": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["id"],
            "properties": {
              "id": { "type": "string" },
              "retentionVersions": { "type": "integer", "minimum": 1, "default": 10 },
              "retentionDays": { "type": "integer", "minimum": 1, "default": 30 }
            },
            "additionalProperties": false
          },
          "default": []
        }
      },
      "additionalProperties": false
    },
    "triggers": {
      "type": "object",
      "properties": {
        "ui": { "type": "boolean", "default": true },
        "api": { "type": "boolean", "default": true }
      },
      "additionalProperties": false
    },
    "defaults": {
      "type": "object",
      "properties": {
        "context": { "type": "object" },
        "overrides": {
          "type": "object",
          "properties": {
            "model": { "type": "string" },
            "options": {
              "type": "object",
              "properties": {
                "temperature": { "type": "number", "minimum": 0, "maximum": 2 },
                "topP": { "type": "number", "minimum": 0, "maximum": 1 },
                "maxTokens": { "type": "integer", "minimum": 1 }
              },
              "additionalProperties": true
            }
          },
          "additionalProperties": false
        }
      },
      "additionalProperties": false
    },
    "launchParameters": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name", "type"],
        "properties": {
          "name": { "type": "string" },
          "type": { "type": "string", "enum": ["string", "number", "boolean", "object", "array"] },
          "required": { "type": "boolean", "default": true },
          "description": { "type": "string" },
          "validation": { "type": "object" }
        },
        "additionalProperties": false
      },
      "default": []
    },
    "steps": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["id", "name", "agentRef", "inputBindings", "transitions"],
        "properties": {
          "id": { "type": "string", "pattern": "^[a-z0-9-]{3,64}$" },
          "name": { "type": "string" },
          "agentRef": { "type": "string" },
          "systemPrompt": { "type": "string" },
          "inputBindings": {
            "type": "object",
            "additionalProperties": {
              "type": "string",
              "description": "JSONPath/SpEL выражение относительно контекста или вывода предыдущих шагов."
            }
          },
          "memory": {
            "type": "object",
            "properties": {
              "read": {
                "type": "array",
                "items": { "type": "string" },
                "default": []
              },
              "write": {
                "type": "array",
                "items": { "type": "string" },
                "default": []
              }
            },
            "additionalProperties": false
          },
          "overrides": {
            "type": "object",
            "properties": {
              "model": { "type": "string" },
              "options": { "$ref": "#/$defs/chatOptions" }
            },
            "additionalProperties": false
          },
          "retryPolicy": {
            "type": "object",
            "properties": {
              "maxAttempts": { "type": "integer", "minimum": 1, "default": 3 },
              "backoffMs": { "type": "integer", "minimum": 0, "default": 250 },
              "multiplier": { "type": "number", "minimum": 1, "default": 2 }
            },
            "additionalProperties": false
          },
          "timeoutSec": { "type": "integer", "minimum": 1, "default": 60 },
          "transitions": {
            "type": "object",
            "required": ["onSuccess", "onFailure"],
            "properties": {
              "onSuccess": { "$ref": "#/$defs/transitionList" },
              "onFailure": { "$ref": "#/$defs/transitionList" }
            },
            "additionalProperties": false
          },
          "annotations": { "type": "object" }
        },
        "additionalProperties": false
      }
    },
    "outputs": {
      "type": "object",
      "properties": {
        "summaryFields": {
          "type": "array",
          "items": { "type": "string" }
        },
        "resultPayload": {
          "type": "object",
          "additionalProperties": { "type": "string" }
        }
      },
      "additionalProperties": false
    }
  },
  "$defs": {
    "transitionList": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["target"],
        "properties": {
          "target": { "type": "string" },
          "condition": { "type": "string", "description": "SpEL/JSONata условие." }
        },
        "additionalProperties": false
      },
      "default": []
    },
    "chatOptions": {
      "type": "object",
      "properties": {
        "temperature": { "type": "number", "minimum": 0, "maximum": 2 },
        "topP": { "type": "number", "minimum": 0, "maximum": 1 },
        "maxTokens": { "type": "integer", "minimum": 1 },
        "presencePenalty": { "type": "number", "minimum": -2, "maximum": 2 },
        "frequencyPenalty": { "type": "number", "minimum": -2, "maximum": 2 }
      },
      "additionalProperties": true
    }
  },
  "additionalProperties": false
}
```

## Пример JSON-конфигурации
```json
{
  "flowId": "solution-workshop",
  "version": 3,
  "metadata": {
    "title": "Solution Design Workshop",
    "description": "Флоу согласования требований и генерации решения",
    "tags": ["workshop", "design"]
  },
  "syncOnly": true,
  "memory": {
    "sharedChannels": [
      { "id": "context" },
      { "id": "decision-log", "retentionVersions": 20 }
    ]
  },
  "triggers": { "ui": true, "api": true },
  "defaults": {
    "context": { "locale": "ru-RU" },
    "overrides": {
      "model": "glm-4-32b-0414-128k",
      "options": { "temperature": 0.2 }
    }
  },
  "launchParameters": [
    { "name": "problemStatement", "type": "string", "required": true },
    { "name": "constraints", "type": "array", "required": false }
  ],
  "steps": [
    {
      "id": "gather_requirements",
      "name": "Сбор требований",
      "agentRef": "research-analyst",
      "systemPrompt": "Ты бизнес-аналитик. Собери контекст и требования.",
      "inputBindings": {
        "problem": "$launch.problemStatement",
        "constraints": "$launch.constraints"
      },
      "memory": { "read": ["context"], "write": ["context", "decision-log"] },
      "overrides": { "options": { "temperature": 0.1 } },
      "retryPolicy": { "maxAttempts": 2, "backoffMs": 500 },
      "timeoutSec": 90,
      "transitions": {
        "onSuccess": [{ "target": "generate_solution" }],
        "onFailure": [{ "target": "abort_flow" }]
      }
    },
    {
      "id": "generate_solution",
      "name": "Проектирование решения",
      "agentRef": "solution-architect",
      "inputBindings": {
        "requirements": "$steps.gather_requirements.output.summary",
        "context": "$memory.context"
      },
      "memory": { "read": ["context"], "write": ["decision-log"] },
      "transitions": {
        "onSuccess": [
          {
            "target": "review_solution",
            "condition": "$output.confidence >= 0.75"
          },
          { "target": "refine_solution" }
        ],
        "onFailure": [{ "target": "refine_solution" }]
      }
    },
    {
      "id": "refine_solution",
      "name": "Уточнение решения",
      "agentRef": "solution-architect",
      "inputBindings": {
        "draft": "$steps.generate_solution.output.draft",
        "feedback": "$steps.review_solution.output.feedback"
      },
      "memory": { "read": ["context", "decision-log"], "write": ["decision-log"] },
      "overrides": { "options": { "temperature": 0.3 } },
      "transitions": {
        "onSuccess": [{ "target": "review_solution" }],
        "onFailure": [{ "target": "abort_flow" }]
      }
    },
    {
      "id": "review_solution",
      "name": "Ревью решения",
      "agentRef": "human-review",
      "inputBindings": {
        "solution": "$steps.generate_solution.output.draft"
      },
      "memory": { "write": ["decision-log"] },
      "transitions": {
        "onSuccess": [{ "target": "complete" }],
        "onFailure": [{ "target": "refine_solution" }]
      }
    },
    {
      "id": "abort_flow",
      "name": "Прерывание",
      "agentRef": "system",
      "inputBindings": {},
      "transitions": { "onSuccess": [], "onFailure": [] }
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

### YAML-представление (фрагмент)
```yaml
flowId: solution-workshop
version: 3
metadata:
  title: Solution Design Workshop
  description: Флоу согласования требований и генерации решения
syncOnly: true
steps:
  - id: gather_requirements
    name: "Сбор требований"
    agentRef: research-analyst
    systemPrompt: "Ты бизнес-аналитик. Собери контекст и требования."
    inputBindings:
      problem: $launch.problemStatement
      constraints: $launch.constraints
    transitions:
      onSuccess:
        - target: generate_solution
      onFailure:
        - target: abort_flow
```

## CRUD API для UI-редактора
| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/flows/definitions` | Список флоу (фильтры `status`, `tag`, поиск). |
| `POST` | `/api/flows/definitions` | Создание черновика; тело соответствует JSON Schema. |
| `GET` | `/api/flows/definitions/{flowId}` | Получение последней версии (draft/published). |
| `PUT` | `/api/flows/definitions/{flowId}` | Полное обновление черновика. |
| `PATCH` | `/api/flows/definitions/{flowId}` | Частичное обновление (JSON Patch). |
| `POST` | `/api/flows/definitions/{flowId}/publish` | Публикация версии (инкремент `version`, установка `status=published`). |
| `GET` | `/api/flows/definitions/{flowId}/history` | История версий. |

### Ответ (пример `GET /api/flows/definitions/{flowId}`)
```json
{
  "flowId": "solution-workshop",
  "status": "draft",
  "version": 3,
  "updatedAt": "2024-07-30T12:05:41Z",
  "updatedBy": "user:grigory",
  "definition": { "...": "см. конфигурацию выше" }
}
```

## Валидация и миграции
- JSON Schema валидируется на backend (например, `everit` или `networknt` validator) и в UI.
- Для миграций версий предусматривается скрипт трансформации (н-р, `FlowDefinitionMigrator`), обновляющий структуру при изменениях схемы.
- Liquibase: создаётся таблица `flow_definition` и вспомогательные индексы (`status`, `updated_at`, `tags` через GIN).

## Связанные таблицы
- `flow_definition_tag(flow_id UUID, tag TEXT)` — для фильтрации.
- `flow_definition_history(flow_definition_id UUID, version INT, definition_jsonb JSONB, created_at TIMESTAMP, created_by TEXT)` — хранение прошлых версий.

## Следующие шаги
1. Реализовать JSON Schema валидатор в backend слой (Spring Boot).
2. Создать форму редактирования на новом UI-разделе `Flows`.
3. Подготовить миграции Liquibase и репозитории для хранения определений.
