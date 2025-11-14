# Operator Playbook

Практические инструкции для операторов AI Advent. Документ дополняет `docs/guides/mcp-operators.md` и фиксирует пошаговые сценарии реагирования.

## Workspace git state

### Когда запускать
- сразу после `github.repository_fetch`, чтобы убедиться, что workspace развёрнут как полноценный git-репозиторий;
- перед `coding.generate_patch` или `coding.apply_patch_preview` — подтверждаем ветку и отсутствие мусорных изменений;
- перед `github.commit_workspace_diff` / `github.push_branch` / `github.open_pull_request` — финальная проверка чистоты.

### Чек-лист
1. Запустите инструмент:
   ```json
   {
     "tool": "github.workspace_git_state",
     "arguments": {
       "workspaceId": "<workspace-id>",
       "includeFileStatus": true,
       "includeUntracked": true,
       "maxEntries": 200
     }
   }
   ```
2. Убедитесь, что `branch.name` совпадает с ожидаемой веткой (обычно `feature/...`).  
3. Проверьте `status.clean`. Если `true`, можно продолжать.
4. Если `clean=false`:
   - `staged > 0` → предупредите агента: нужно завершить/отменить предыдущий commit.
   - `unstaged > 0` → выполните `git checkout -- <file>` или обсудите с агентом необходимость изменений.
   - `untracked > 0` → либо добавьте файл в patch, либо удалите/игнорируйте.
5. При `branch.detached=true` остановите публикацию и запросите повторный `github.create_branch`.
6. Если `warnings[]` содержит `Result truncated...`, повторите вызов с меньшим количеством файлов (например, `maxEntries=50`) или очистите workspace.

### Примеры ответов
```json
{
  "workspaceId": "workspace-1234",
  "branch": {
    "name": "feature/improve-logging",
    "headSha": "4f5081c3...",
    "upstream": "origin/feature/improve-logging",
    "detached": false,
    "ahead": 1,
    "behind": 0
  },
  "status": {"clean": false, "staged": 1, "unstaged": 2, "untracked": 1, "conflicts": 0},
  "files": [
    {"path": "README.md", "changeType": "modified", "staged": true, "unstaged": false, "tracked": true},
    {"path": "notes/todo.md", "changeType": "untracked", "staged": false, "unstaged": true, "tracked": false}
  ],
  "truncated": false,
  "warnings": [],
  "inspectedAt": "2025-02-18T10:12:55Z",
  "durationMs": 430
}
```

### Типовые решения
| Ситуация | Действия |
| --- | --- |
| `clean=false`, `files[]` пуст (только summary) | Включите `includeFileStatus=true` и `maxEntries` ≥ 50, повторите вызов. |
| `.git` отсутствует | Повторите `github.repository_fetch` с `CheckoutStrategy=CLONE_WITH_SUBMODULES`, затем `github.create_branch`. |
| Команда агентом пропущена (dirty workspace) | Обновите чат/flow статус, попросите оператора очистить workspace или оформить manual patch. |
| `ahead/behind` не равны 0 перед push | Выполните `git pull --rebase origin <branch>` внутри workspace либо попросите агента синхронизировать базу. |
| `truncated=true` | Ограничьте `maxEntries`, закройте временные артефакты (`./gradlew` вывел build), повторите инструмент. |

### Решение инцидентов
1. Сохраните ответ инструмента с `workspaceId` и `branch`.  
2. Сравните `branch.headSha` с последним commit из `github.commit_workspace_diff`. Несовпадение → файл не был закоммичен.  
3. Проверьте, что `workspaceGitState` отображается в UI (чекбокс «Git state before publish»). При отсутствии — создайте тикет в Infra.  
4. При системных ошибках (`Workspace ... is missing .git metadata`) выполните `workspace.clean` (rm -rf workspace) и повторите fetch/branch.
