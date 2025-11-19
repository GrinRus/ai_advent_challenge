# MCP Tools Cheat Sheet (excerpt)

## Coding MCP

| Tool | Назначение | Основные параметры |
|------|------------|-------------------|
| `coding.generate_patch` | Генерирует diff через Claude CLI (GLM). Патч сохраняется в `PatchRegistry`, можно запускать review/apply/dry-run. | `workspaceId`, `instructions`, `targetPaths`, `forbiddenPaths`, `contextFiles[]`. Инструкции ≤ 4000 символов, diff ≤ 256 КБ. |
| `coding.generate_artifact` | Упрощённый генератор файлов на GPT-4o Mini. Возвращает JSON операций, применяет изменения и отдаёт `git diff`. Использует общие `OPENAI_*` переменные. | `workspaceId`, `instructions`, `targetPaths`, `forbiddenPaths`, `contextFiles[]`, `operationsLimit`. Ограничения по умолчанию: ≤ 8 файлов, ≤ 2000 строк и ≤ 200 КБ каждый. |
| `coding.review_patch` | Быстрое LLM-ревью (риски, тесты, миграции) поверх ранее созданного патча. | `workspaceId`, `patchId`, опционально `focus[]`. |
| `coding.apply_patch_preview` | Dry-run: `git apply --check`, временное применение diff, `git diff --stat`, откат + опциональный gradle-runner. | `workspaceId`, `patchId`, `commands[]`, `dryRun`, `timeout`. |
| `coding.list_patches` | Список активных патчей (метаданные, статус dry-run). | `workspaceId`. |
| `coding.discard_patch` | Удаляет патч из in-memory реестра. | `workspaceId`, `patchId`. |

> Совет: для быстрой генерации boilerplate используйте `coding.generate_artifact`, а для сложных правок, требующих ручного dry-run/review, оставайтесь на `coding.generate_patch`.
