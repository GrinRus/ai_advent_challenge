# Wave 36 — RAG Parameter Governance

## Overview
- `repo.rag_search` и `repo.rag_search_global` теперь принимают только `repoOwner?`, `repoName?`, `rawQuery`, `profile`, `conversationContext`. Любые дополнительные поля приводят к ошибке `Unsupported parameter`.
- Все retrieval/post-processing настройки берутся из профилей `github.rag.parameter-profiles[]`, а `appliedModules` содержит `profile:<name>`.
- `RagParameterGuard` централизует клампы (`topK`, `multiQuery`, `neighbor*`, `codeAware*`) и логирует структурированные события, поэтому операторы видят, когда запрос был урезан.

## Operator Actions
1. Выберите базовый профиль (`conservative`, `balanced`, `aggressive`) через `github.rag.default-profile`. Для миграции старых сценариев создайте профиль `legacy` с прежними значениями.
2. Обновите UI/агентов: при вызове `repo.rag_search` всегда передавайте `profile`. Клиенты, которые отправят старые поля (`topK`, `neighborLimit` и т.д.), получат ошибку.
3. Отслеживайте предупреждения в ответе инструмента: санитайзер сообщает об автозамене `repoOwner/repoName`, Guard сообщает о клампах параметров.

## Developer Notes
- Примеры профилей находятся в `backend-mcp/src/main/resources/application-github.yaml`.
- Документация для операторов обновлена в `docs/guides/mcp-operators.md`, архитектурные детали — в `docs/architecture/github-rag-modular.md`.
- Тесты: `RepoRagToolInputSanitizerTest`, `RagParameterGuardTest`, `RepoRagSearchServiceTest`, `RepoRagToolsTest`.
