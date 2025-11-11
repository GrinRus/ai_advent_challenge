# GitHub RAG Modular Pipeline (Wave 31)

## Цели
- Сделать `repo.rag_search` самодостаточным: клиент передает только «сырые» вводы (`rawQuery`, история, фильтры), а MCP сам решает, какие модули включать.
- Повысить полноту поиска: multi-query, компрессия истории и перевод на единый embedding-язык.
- Снизить стоимость и обеспечить прозрачность: каждое изменение фиксируется в `appliedModules`, лимиты и SLA задокументированы.
- Перенести chunking на семантические правила Spring AI (см. [Semantic splitters](https://docs.spring.io/spring-ai/reference/api/rag/document-splitters.html)) и отдавать дополнительную мета-информацию (`parent_symbol`, `span_hash`, `overlap_lines`).

## Лего-модули
| Этап | Компонент | Назначение | Триггеры/ограничения |
|------|-----------|------------|-----------------------|
| Pre-Retrieval | `CompressionQueryTransformer` | Сжимает историю + follow-up запрос в standalone текст (до `github.rag.query-transformers.max-history-tokens` ≈ 1600 т.) | отключается, если история пуста или `github.rag.query-transformers.enabled=false` |
| Pre-Retrieval | `RewriteQueryTransformer` | Удаляет шум, перефразирует вопрос | всегда после compression |
| Pre-Retrieval | `TranslationQueryTransformer` | Переводит на язык embedding модели (по умолчанию `ru`, можно переопределить `translateTo`) | пропускается, если target совпадает с исходным языком |
| Retrieval | `MultiQueryExpander` + кастомный дедупликатор | Генерирует N подзапросов, каждый выполняет topK search, результаты склеиваются в порядке подзапросов и дедуплицируются по `chunk_hash`, фиксируя `generatedBySubQuery` | `github.rag.multi-query.enabled`, `multiQuery.queries<=maxQueries` |
| Post-Retrieval | Heuristic rerank (`DocumentPostProcessor`) | Сортирует топ-N по взвешенному score/span (`github.rag.rerank`) | меняет только head списка |
| Post-Retrieval | `ContextWindowBudgetPostProcessor` | Срезает список по лимиту токенов (`maxContextTokens`, ≥256) | минимум 1 документ всегда сохраняется |
| Post-Retrieval | LLM Snippet Compressor | ChatClient (`gpt-4o-mini`, T=0.1) сжимает первые 6 сниппетов до `maxSnippetLines`, сохраняя ключевые факты | включается, если `github.rag.post-processing.llm-compression-enabled=true` и сниппет длиннее лимита |

### Семантический chunking и метаданные
- `SemanticCodeChunker` использует эвристики по языкам (классы, `def`, `function`, doc-комментарии), поверх `ParentSymbolResolver`. Он включает соседние блоки через `github.rag.chunking.overlap-lines`, поэтому Post-Retrieval может получить «хвост» предыдущего символа, даже если similarity поиск вернул середину функции.
- Для языков без правил или отключенного режима автоматически включается линейный chunker. Настройки совпадают с `spring.ai` (см. [Query transformers](https://docs.spring.io/spring-ai/reference/api/rag/query-transformers.html)).
- Каждый документ получает дополнительные поля метаданных: `parent_symbol` (класс/метод/def), `span_hash` (SHA-256 от `file_path:lineStart:lineEnd`) и `overlap_lines` (сколько строк унаследовано от предыдущего чанка). Эти поля помогают в QA/observability и позволяют post-processing/generation подтягивать соседние куски при формировании подсказки.

## SLA и лимиты
- `topK<=40`, `topKPerQuery<=40`, `rerankTopN<=40`.
- Multi-query: `queries<=github.rag.multi-query.max-queries` (по умолчанию 3) и `maxQueries<=6`. MCP обрезает запрос, если клиент превысил лимит.
- `maxContextTokens` можно занижать, но MCP не позволит поставить значения <256 или >`github.rag.post-processing.max-context-tokens` (по умолчанию 4000).
- `useCompression=false` отключает `CompressionQueryTransformer`, что полезно для коротких follow-up запросов, но SLA 120 сек. сохраняется только при валидных параметрах.
- Время выполнения инструмента прежнее — до 120 секунд. Multi-query и LLM-компрессия добавляют ~2–4 c при включении.

## Ответ инструмента v4
```json
{
  "matches": [...],
  "rerankApplied": true,
  "augmentedPrompt": "Готовый prompt от ContextualQueryAugmenter",
  "instructions": "Готовая подсказка для агента",
  "contextMissing": false,
  "noResults": false,
  "noResultsReason": null,
  "appliedModules": ["query.compression","retrieval.multi-query","post.llm-compression"]
}
```
- `contextMissing=true` ⇒ UI показывает «Индекс не содержит подходящих документов». Если `allowEmptyContext=false`, MCP бросает `IllegalStateException`.
- `noResults=true` фиксирует ситуацию «векторный поиск ничего не вернул», даже если `allowEmptyContext=true` и генерация продолжилась. `noResultsReason` подсказка для операторов (`CONTEXT_NOT_FOUND`, `INDEX_NOT_READY`).
- `instructionsTemplate` поддерживает плейсхолдеры `{{rawQuery}}`, `{{repoOwner}}`, `{{repoName}}`, `{{locale}}`, `{{augmentedPrompt}}`.

## Когда использовать simple-версию
`repo.rag_search_simple` подходит для flow’ов уровня «github.repository_fetch → ждем READY → спроси про проект». MCP берёт репозиторий именно из последнего fetch (реестр обновляется после вызова инструмента) и возвращает полный DTO, чтобы не дублировать repoOwner/repoName в prompt. Ограничения:
- Требует хотя бы одного READY-репозитория (иначе ошибка).
- Не принимает фильтры — используется как быстрый старт перед более точным `repo.rag_search`.

## Наблюдаемость
- Лог `appliedModules` + `rerankApplied` дают быстрый ответ на вопрос «сработал ли конкретный этап».
- Для incident-review достаточно свериться с `github.rag.*` конфигами и `appliedModules`: если не пришёл `query.translation`, значит модель решила, что язык уже подходящий.
