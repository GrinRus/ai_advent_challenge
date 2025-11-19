# Wave 44 — Simplified Coding Generator

## Ключевые изменения
- Добавлен инструмент `coding.generate_artifact`, который использует GPT-4o Mini для создания/редактирования файлов по инструкциям. Инструмент сразу применяет изменения в workspace, возвращает `git diff`, список операций и предупреждения.
- Профиль `coding` теперь включает `spring.ai.openai` и переиспользует глобальные `OPENAI_*` переменные (RAG/Notes). Новые параметры `coding.openai.*` позволяют ограничить температуру, maxTokens, количество операций и размеры файлов.
- `WorkspaceFileService` научился выполнять безопасные write-операции (`CREATE`, `OVERWRITE`, `APPEND`, `INSERT`) внутри sandbox с проверкой путей и маркеров.
- Добавлен `WorkspaceArtifactGenerator`: формирует промпт, парсит JSON ответа и контролирует лимиты. При отключённом OpenAI сервис выдаёт понятную ошибку.
- Метрики/тесты: новый `coding_artifact_generation_total`, unit-тест `WorkspaceFileServiceTest` покрывает сценарии записи/вставки.

## Как использовать
1. Поднимаем `backend-mcp` с профилем `coding`; OpenAI ключ берётся из `OPENAI_API_KEY`.
2. В чате/flow вызываем:
   ```jsonc
   {
     "workspaceId": "notes-1",
     "instructions": "создай docs/roadmap.md и обнови ProfileSettings.tsx",
     "targetPaths": ["docs/", "frontend/src/pages/ProfileSettings.tsx"],
     "contextFiles": [{"path": "docs/backlog.md", "maxBytes": 8192}],
     "operationsLimit": 4
   }
   ```
3. Инструмент вернёт summary, diff, warnings, а файлы появятся в workspace. Далее можно запустить `coding.apply_patch_preview` или сразу переходить к GitHub write-инструментам.
