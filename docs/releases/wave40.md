# Wave 40 — Workspace Git State Tool

## Summary
Wave 40 закрывает давний навык assisted-coding: теперь MCP умеет показывать актуальную ветку и состояние git внутри локального workspace без ручного `git status`. Это снижает количество ручных команд и даёт LLM/операторам единый источник правды перед генерацией и публикацией патчей.

## Highlights
- `TempWorkspaceService` хранит git-метаданные (`branchName`, `headSha`, `resolvedRef`, `upstream`) начиная с `github.repository_fetch` и синхронизирует их после `create_branch`, `commit_workspace_diff`, `push_branch`.
- Новый сервис `GitWorkspaceStateService` выполняет `git status --porcelain=v2 --branch`, объединяет результат с метаданными и контролирует лимиты (≤200 файлов, ≤64 КБ, таймаут 20 с). Метрики: `github_workspace_git_state_success_total/failure_total`, `..._duration`.
- MCP-инструмент `github.workspace_git_state` (execution-mode=SAFE) зарегистрирован в каталоге и доступен чатам/flow. Ответ включает `branch`, `status`, список файлов и предупреждения `truncated`.
- Spring AI bindings обновлены: инструмент включён в каталог backend (`execution-mode: SAFE`), описан в документации (guides/infra/RFC), добавлен чек-лист “перед генерацией патча → workspace_git_state”.
- Unit-тесты покрывают чистый и "грязный" workspace, а также ошибку при отсутствии `.git`.

## Operator & LLM UX
1. После `github.repository_fetch` → `github.workspace_git_state`. Если `status.clean=false`, flow автоматически просит оператора очистить workspace.
2. Перед `coding.apply_patch_preview` UI показывает ссылку "Последний git state" (branch, HEAD, staged/untracked counts), что облегчает диагностику.
3. В документации добавлено описание JSON-вызовов и рекомендации (включая лимиты и поведение при `truncated=true`).

## Configuration
- Новые проперти `github.backend.workspace-git-state-max-entries`, `...-max-bytes`, `...-timeout` (env: `GITHUB_BACKEND_WORKSPACE_GIT_STATE_MAX_*`). Значения по умолчанию: `200`, `64KiB`, `PT20S`.
- Чек-листы и операторские инструкции обновлены: `github.workspace_git_state` теперь обязательный шаг перед публикацией.
