# Gradle MCP Pipeline

Этот документ описывает внутреннюю архитектуру пайплайна Gradle MCP: как инструменты `github.repository_fetch`, `github.workspace_directory_inspector` и `docker.gradle_runner` соединяются в flow `github-gradle-test-flow`, какие статусы видит оператор и какие метрики снимаются.

## Цели и ограничения
- **Повторяемость:** все шаги (fetch → inspect → docker-run → отчёт) выполняются детерминированно и могут перезапускаться с тем же `requestId`.
- **Изоляция:** GitHub workspace хранится на локальном диске (`/var/tmp/aiadvent/mcp-workspaces`), а Gradle запускается в Docker-образе `aiadvent/mcp-gradle-runner`.
- **Прозрачность:** каждый инструмент возвращает структурированный ответ, который UI отображает как промежуточный статус и итоговый отчёт.
- **Ограничения:** по умолчанию отключена сеть (`docker.runner.enable-network=false`), базовый таймаут – 15 минут, максимальный размер workspace – 2 ГиБ, размер архива – 512 МиБ.

## Поток `fetch → inspect → docker-run`

```
User input (repo/ref/tasks)
        │
        ▼
github-gradle-test-flow (Flow orchestrator)
        │
        ├─ Step fetch_workspace → github.repository_fetch
        │      • скачивает архив/clone
        │      • записывает metadata (.workspace.json) и keyFiles
        │      • публикует событие flow.started(fetching)
        │
        ├─ Step inspect_workspace → github.workspace_directory_inspector
        │      • сканирует workspace (maxDepth=4|maxResults=400)
        │      • определяет Gradle-проекты и рекомендует projectPath
        │      • Flow статус → inspecting_workspace
        │
        └─ Step run_gradle_tests → docker.gradle_runner
               • монтирует workspace + Gradle cache
               • запускает ./gradlew или gradle <tasks>
               • собирает stdout/stderr чанки, exitCode и длительность
               • Flow статус → running_tests → completed/failed
```

### Контракты инструментов

| Инструмент | Обязательные поля | Дополнительные поля | Статусы |
|------------|------------------|---------------------|---------|
| `github.repository_fetch` | `repository{owner,name,ref}` | `options.strategy`, `requestId`, `detectKeyFiles` | `fetching` |
| `github.workspace_directory_inspector` | `workspaceId` | `includeGlobs`, `maxDepth`, `detectProjects` | `inspecting_workspace` |
| `docker.gradle_runner` | `workspaceId`, `tasks[]` | `projectPath`, `arguments`, `env`, `timeoutSeconds` | `running_tests` / `failed` |

Flow публикует события `STEP_STARTED`, `STEP_COMPLETED`, `STEP_FAILED`. UI отображает прогресс-бар и текстовые статусы:

- `fetching` – workspace скачивается или переиспользуется.
- `inspecting_workspace` – список проектов и рекомендации для `projectPath`.
- `running_tests` – Docker контейнер выполняет Gradle.
- `report_ready` – итоговый отчёт доступен (exitCode, лог, советы).

## Службы и конфигурация

| Компонент | Назначение | Основные настройки |
|-----------|------------|--------------------|
| `GitHubRepositoryService` | fetch/clone, учёт лимитов и TTL workspace | `github.backend.workspace-root`, `workspace-ttl`, `archive-max-size-bytes` |
| `WorkspaceInspectorService` | рекурсивный обход workspace, определение Gradle проектов | `maxDepth`, `maxResults`, `detectProjects`, метрики `workspace_inspection_*` |
| `DockerRunnerService` | формирует `docker run`, монтирует workspace/cache, собирает логи | `docker.runner.workspace-root`, `gradle-cache-path`, `image`, `timeout`, `enable-network`, метрики `docker_gradle_runner_*` |
| `github-gradle-test-flow` | orchestrator (3 шага) + подсказки агентов | seed в Liquibase (`0101-seed-github-gradle-test-flow`) |

### Монтаж и изоляция
- Если контейнеру разрешено `--volumes-from self`, workspace и cache разделяются с backend. В противном случае `docker.runner.workspace-volume` и `docker.runner.gradle-cache-volume` задают именованные volume'ы, либо используется прямое монтирование `-v host:path`.
- `GRADLE_USER_HOME` передаётся в контейнер (по умолчанию `/gradle-cache`).
- В `env` запрещено переопределять критические переменные (`PATH`, `HOME`), но можно добавлять токены внешних сервисов.

## Обработка ошибок

| Сценарий | Поведение |
|----------|-----------|
| Ошибка fetch (HTTP/Git) | step → `failed`, Flow завершаетcя со статусом `FAILED`, UI показывает ссылку на лог GitHub MCP. |
| Workspace не найден | `docker.gradle_runner` выбрасывает `IllegalArgumentException`, Step переводится в `FAILED`. |
| Таймаут Docker | `docker runner` прерывает процесс, статус `failed`, stdout/stderr содержат заметку `timed out after N seconds`. |
| Непустой exitCode | Flow фиксирует `FAILED`, в отчёт выводятся tail логов и рекомендации агента. Пользователь может скорректировать параметры и перезапустить Flow. |

## Наблюдаемость и метрики

- **GitHub fetch:** `github_repository_fetch_duration`, `*_success_total`, `*_failure_total`, `*_download_bytes`, `*_workspace_bytes`. `requestId` логируется в `TempWorkspaceService`.
- **Inspector:** `workspace_inspection_duration`, `workspace_inspection_items_total`, `*_success_total`, `*_failure_total`, плюс флаг `truncated`.
- **Docker runner:** `docker_gradle_runner_duration`, `docker_gradle_runner_duration_ms`, `docker_gradle_runner_success_total`, `docker_gradle_runner_failure_total`. В событиях flow записывается `dockerCommand` и `runnerExecutable`.
- **Flow:** `FlowTimeline` фиксирует состояния `fetching/inspecting_workspace/running_tests`, exitCode и ссылку на workspaceId.

Метрики доступны через `actuator/prometheus` у `backend` и `docker-runner-mcp`. При расследовании указывайте `requestId` (передаётся в `github.repository_fetch`) — он попадает в логи fetch, inspector и flow.

## Чек-лист эксплуатационной готовности

1. **Докер-образ** `aiadvent/mcp-gradle-runner` собран и доступен (можно проверить через `docker compose run mcp-gradle-runner-builder ./gradlew --version`).
2. **Workspace root** смонтирован в backend и docker-runner (`/var/tmp/aiadvent/mcp-workspaces`), права 0775.
3. **Gradle cache** общая (`/var/tmp/aiadvent/gradle-cache`), доступна контейнеру.
4. **Monitoring**: дашборд строит графики `*_duration`, `*_success_total`, `*_failure_total`, alert — >3 fail подряд или timeout > 10 мин.
5. **Runbook**: оператор знает, как перезапустить Flow с тем же repo/ref, как очистить workspace и как предоставить логи (stdout/stderr возвращаются чанками в ответе `docker.gradle_runner`).
6. **Security**: `DOCKER_RUNNER_ENABLE_NETWORK` оставлять `false` по умолчанию; если включаем, фиксируем причину в change log.

## Примеры вызовов

```json
{
  "tool": "docker.gradle_runner",
  "arguments": {
    "workspaceId": "workspace-12345",
    "projectPath": "service-app",
    "tasks": ["test", "check"],
    "arguments": ["--info"],
    "env": {"CI": "true"},
    "timeoutSeconds": 600
  }
}
```

```json
{
  "tool": "github.workspace_directory_inspector",
  "arguments": {
    "workspaceId": "workspace-12345",
    "includeGlobs": ["**/build.gradle*", "**/settings.gradle*"],
    "maxResults": 400,
    "detectProjects": true
  }
}
```

Эти ответы сохраняются в flow событиях и отображаются пользователю. Любое отклонение (например, превышение лимитов) должно быть задокументировано в `docs/guides/mcp-operators.md`.
