# GitHub RAG Modular Pipeline (Wave 31)

## Цели
- Сделать `repo.rag_search` самодостаточным: клиент передает только «сырые» вводы (`rawQuery`, история, фильтры), а MCP сам решает, какие модули включать.
- Повысить полноту поиска: multi-query, компрессия истории и перевод на единый embedding-язык.
- Снизить стоимость и обеспечить прозрачность: каждое изменение фиксируется в `appliedModules`, лимиты и SLA задокументированы.

## Лего-модули
| Этап | Компонент | Назначение | Триггеры/ограничения |
|------|-----------|------------|-----------------------|
| Pre-Retrieval | `CompressionQueryTransformer` | Сжимает историю + follow-up запрос в standalone текст (до `github.rag.query-transformers.max-history-tokens` ≈ 1600 т.) | отключается, если история пуста или `github.rag.query-transformers.enabled=false` |
| Pre-Retrieval | `RewriteQueryTransformer` | Удаляет шум, перефразирует вопрос | всегда после compression |
| Pre-Retrieval | `TranslationQueryTransformer` | Переводит на язык embedding модели (по умолчанию `ru`, можно переопределить `translateTo`) | пропускается, если target совпадает с исходным языком |
| Retrieval | `MultiQueryExpander` | Генерирует N подзапросов, каждый выполняет topK search, результат объединяется `ConcatenationDocumentJoiner` | `github.rag.multi-query.enabled`, `multiQuery.queries<=maxQueries` |
| Post-Retrieval | Heuristic rerank (`DocumentPostProcessor`) | Сортирует топ-N по взвешенному score/span (`github.rag.rerank`) | меняет только head списка |
| Post-Retrieval | `ContextWindowBudgetPostProcessor` | Срезает список по лимиту токенов (`maxContextTokens`, ≥256) | минимум 1 документ всегда сохраняется |
| Post-Retrieval | LLM Snippet Compressor | ChatClient (`gpt-4o-mini`, T=0.1) сжимает первые 6 сниппетов до `maxSnippetLines`, сохраняя ключевые факты | включается, если `github.rag.post-processing.llm-compression-enabled=true` и сниппет длиннее лимита |

## SLA и лимиты
- `topK<=40`, `rerankTopN<=40`, `multiQuery.queries<=github.rag.multi-query.max-queries` (дефолт 6).
- `maxContextTokens` по умолчанию 4000, можно занижать в запросе (но не менее 256).
- Время выполнения инструмента прежнее — до 120 секунд. Multi-query и LLM-компрессия добавляют ~2–4 c при включении.

## Ответ инструмента v3
```json
{
  "matches": [...],
  "rerankApplied": true,
  "augmentedPrompt": "Запрос + сжатый контекст",
  "instructions": "Готовая подсказка для агента",
  "contextMissing": false,
  "appliedModules": ["query.compression","retrieval.multi-query","post.llm-compression"]
}
```
- `contextMissing=true` ⇒ UI показывает «Индекс не содержит подходящих документов». Если `allowEmptyContext=false`, MCP бросает `IllegalStateException`.
- `instructionsTemplate` поддерживает плейсхолдеры `{{rawQuery}}`, `{{repoOwner}}`, `{{repoName}}`, `{{locale}}`, `{{augmentedPrompt}}`.

## Когда использовать simple-версию
`repo.rag_search_simple` подходит для flow’ов уровня «fetch → ждем READY → спроси про проект». MCP берёт последний READY namespace (`RepoRagNamespaceState.lastIndexedAt`) и возвращает полный DTO, чтобы не дублировать repoOwner/repoName в prompt. Ограничения:
- Требует хотя бы одного READY-репозитория (иначе ошибка).
- Не принимает фильтры — используется как быстрый старт перед более точным `repo.rag_search`.

## Наблюдаемость
- Лог `appliedModules` + `rerankApplied` дают быстрый ответ на вопрос «сработал ли конкретный этап».
- Для incident-review достаточно свериться с `github.rag.*` конфигами и `appliedModules`: если не пришёл `query.translation`, значит модель решила, что язык уже подходящий.

