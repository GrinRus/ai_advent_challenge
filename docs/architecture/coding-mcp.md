# Coding MCP Architecture Notes (Wave 44)

## Overview
- Coding MCP теперь включает два режима генерации:
  1. **Diff-пайплайн (Claude CLI / PatchGenerator)** — основной assisted-coding flow с регистрацией патчей, dry-run и GitHub публикацией.
  2. **Упрощённый генератор артефактов (`coding.generate_artifact`)** — быстрый способ создать/обновить файлы напрямую. Использует GPT-4o Mini через общие переменные `OPENAI_*` (подхватываются в `application-coding.yaml` через `spring.ai.openai`).
- Новый сервис `WorkspaceArtifactGenerator` готовит промпт с инструкциями/target/forbidden/context, парсит JSON `{summary, operations[], warnings[]}` и обеспечивает детерминированные лимиты (≤ 8 файлов, ≤ 2000 строк/200 КБ каждый).
- `WorkspaceFileService` расширен write-операциями (`CREATE`, `OVERWRITE`, `APPEND`, `INSERT`), что позволяет безопасно применять изменения внутри workspace (проверка путей, маркеров и лимитов).

## Flow `coding.generate_artifact`
1. Chat/Flow вызывает инструмент с payload:
   ```jsonc
   {
     "workspaceId": "notes-1",
     "instructions": "создай docs/roadmap.md и обнови ProfileSettings.tsx",
     "targetPaths": ["docs/", "frontend/src/pages/ProfileSettings.tsx"],
     "forbiddenPaths": ["backend/"],
     "contextFiles": [{"path": "docs/backlog.md", "maxBytes": 16384}],
     "operationsLimit": 4
   }
   ```
2. `CodingAssistantService` валидирует инструкции/пути, подкачивает контекст и вызывает `WorkspaceArtifactGenerator`.
3. LLM возвращает JSON с операциями. Каждая операция конвертируется в `WorkspaceFileService.WriteMode`. Если путь попадает в forbidden-list или превышает лимиты, запрос завершается ошибкой.
4. После записи файлов собирается `git diff`, `git diff --name-status` и `git status --short`. Результат возвращается оператору вместе с warnings и списком операций.
5. Оператор может сразу продолжить (`coding.apply_patch_preview` или GitHub write-инструменты), т.к. файлы уже присутствуют в workspace.

## Metrics & observability
- Новый счётчик `coding_artifact_generation_total` отражает количество успешных запросов `coding.generate_artifact`.
- Сервис логирует размеры инструкций и список затронутых файлов (INFO), а ошибки парсинга JSON/путей выбрасываются как 4xx для MCP клиента.

## Safety
- Все операции проходят через `WorkspaceFileService`: путь должен находиться внутри workspace, marker вставки (`insertBefore`) должен присутствовать в файле, бинарные payload запрещены.
- Ограничения можно подкрутить через `coding.openai.*` (модель, температура, maxTokens, maxOperations, maxFileLines, maxFileBytes).
- Инструмент не регистрирует патч в `PatchRegistry`, чтобы изменения сразу оставались в файловой системе. Dry-run перед публикацией рекомендуется выполнять через `coding.apply_patch_preview`.
