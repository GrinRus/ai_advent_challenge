# GitHub RAG Modular Pipeline (Wave 31)

## Цели
- Сделать `repo.rag_search` самодостаточным: клиент передает только «сырые» вводы (`rawQuery`, история, фильтры), а MCP сам решает, какие модули включать.
- Повысить полноту поиска: multi-query, компрессия истории и перевод на единый embedding-язык.
- Снизить стоимость и обеспечить прозрачность: каждое изменение фиксируется в `appliedModules`, лимиты и SLA задокументированы.
- Перенести chunking на семантические правила Spring AI (см. [Semantic splitters](https://docs.spring.io/spring-ai/reference/api/rag/document-splitters.html)) и отдавать дополнительную мета-информацию (`parent_symbol`, `span_hash`, `overlap_lines`).

## Wave 36 — Parameter governance
- **Profile registry (`GitHubRagProperties.parameterProfiles`)** — YAML/ENV описывают стратегии `conservative/balanced/aggressive`. Профиль содержит retrieval (`topK`, `minScore`, `multiQuery`), post-processing (`neighbor`, `codeAware*`, `maxContextTokens`) и generation лимиты. В ответе `appliedModules` всегда появляется `profile:<name>`.
- **Contract** — `repo.rag_search`, `repo.rag_search_global` принимают только `(owner?, name?, rawQuery, profile, conversationContext)`. Любая попытка пробросить «голые» параметры отклоняется санитайзером, поэтому сервер сам контролирует лимиты.
- **RagParameterGuard** — центральная точка клампов. Получает `ResolvedRagParameterProfile`, ограничивает значения (например, `topK<=40`, `neighborLimit<=12`), пишет структурированные логи `rag_parameter_guard {profile,field,requested,applied}` и возвращает предупреждения, которые видит оператор.
- **SearchService на планах** — `RepoRagSearchService` теперь получает `ResolvedSearchPlan` (готовый DTO от Guard) и не содержит разбросанных `resolveTopK()/neighborLimit()` проверок. Это упрощает дальнейшие изменения, т.к. достаточно скорректировать профиль.
- **Breaking change** — UI/агенты должны передавать `profile`. Для старых сценариев создаём профиль `legacy` с прежними значениями и включаем его через `default-profile`, но не поддерживаем старый DTO.

## Лего-модули
| Этап | Компонент | Назначение | Триггеры/ограничения |
|------|-----------|------------|-----------------------|
| Pre-Retrieval | `CompressionQueryTransformer` | Сжимает историю + follow-up запрос в standalone текст (до `github.rag.query-transformers.max-history-tokens` ≈ 1600 т.) | отключается, если история пуста или `github.rag.query-transformers.enabled=false` |
| Pre-Retrieval | `RewriteQueryTransformer` | Удаляет шум, перефразирует вопрос | всегда после compression |
| Pre-Retrieval | `TranslationQueryTransformer` | Переводит на язык embedding модели (по умолчанию `ru`, можно переопределить `translateTo`) | пропускается, если target совпадает с исходным языком |
| Retrieval | `MultiQueryExpander` + кастомный дедупликатор | Генерирует N подзапросов, каждый выполняет topK search, результаты склеиваются в порядке подзапросов и дедуплицируются по `chunk_hash`, фиксируя `generatedBySubQuery` | `github.rag.multi-query.enabled`, `multiQuery.queries<=maxQueries` |
| Post-Retrieval | Code-aware rerank (`CodeAwareDocumentPostProcessor`) | Перенастраивает голову списка (до `ceil(rerankTopN * codeAwareHeadMultiplier)`) с учётом языка запроса, типа символа, штрафов за `generated/` пути и лимитов `diversity.maxPerFile/maxPerSymbol` | `codeAwareEnabled=true`, `appliedModules+=post.code-aware`, веса и бонусы задаёт `github.rag.rerank.code-aware.*` |
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

### RepoRagToolInputSanitizer
- Перед построением `SearchCommand` все DTO (`repo.rag_search`, `repo.rag_search_global`, `repo.rag_search_simple`) проходят через `RepoRagToolInputSanitizer`. Он триммит строки, выставляет дефолты `neighbor*`, `multiQuery`, `generationLocale`, нормализует `filters.languages`, приводит `neighborStrategy`/`translateTo` к каноническим значениям и проверяет диапазоны (`topK<=40`, `neighborLimit<=max-limit`, `multiQuery.maxQueries<=6`).
- При отсутствии `repoOwner/repoName` (или `displayRepo*` в global-режиме) санитайзер пытается взять последний READY namespace через `GitHubRepositoryFetchRegistry`. Если индекс ещё не READY, инструмент сразу возвращает ошибку, чтобы агенты повторили fetch.
- `instructionsTemplate` работает в режиме «жёлтая карточка»: `{{query}}` автоматически заменяется на `{{rawQuery}}`, неизвестные плейсхолдеры удаляются. Все автоисправления складываются в `warnings[]` ответа и инкрементируют метрику `repo_rag_tool_input_fix_total`.

### Code-aware & Neighbor настройки
- `github.rag.rerank.code-aware.*` описывает поведение code-aware шага: веса `score/ span`, бонусы `language-bonus.{lang}` (например, `java=1.2`), `symbol-priority.{class,method_public,...}`, списки `path-penalty.allowPrefixes/denyPrefixes` с `penaltyMultiplier`, а также лимиты `diversity.maxPerFile` и `maxPerSymbol`. Клиент может временно отключить шаг (`codeAwareEnabled=false`) или расширить голову за счёт `codeAwareHeadMultiplier` (но не выше `max-head-multiplier`, по умолчанию 4.0).
- `github.rag.post-processing.neighbor.{enabled,default-radius,default-limit,max-radius,max-limit,strategy}` задаёт дефолтные значения для расширения соседних чанков. Параметры `neighborRadius`, `neighborLimit`, `neighborStrategy` в DTO позволяют переключаться между `OFF`, `LINEAR`, `PARENT_SYMBOL`, `CALL_GRAPH`, но сервер всё равно придерживается верхнего порога `max-limit` (и абсолютного хардкапа 400).
  Вставленные чанки помечаются `metadata.neighborOfSpanHash`, чтобы генерация/клиенты понимали, вокруг какого исходного span случилось расширение.

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
  "appliedModules": ["query.compression","retrieval.multi-query","post.llm-compression"],
  "warnings": ["repoOwner заполнен автоматически значением ai-advent/challenge"]
}
```
- `contextMissing=true` ⇒ UI показывает «Индекс не содержит подходящих документов». Если `allowEmptyContext=false`, MCP бросает `IllegalStateException`.
- `noResults=true` фиксирует ситуацию «векторный поиск ничего не вернул», даже если `allowEmptyContext=true` и генерация продолжилась. `noResultsReason` подсказка для операторов (`CONTEXT_NOT_FOUND`, `INDEX_NOT_READY`).
- `warnings[]` содержит список автоисправлений (нормализация плейсхолдеров, автозаполнение owner/name, ограничение лимитов). Пустой список означает, что параметры прошли без корректировок.
- `instructionsTemplate` поддерживает плейсхолдеры `{{rawQuery}}`, `{{repoOwner}}`, `{{repoName}}`, `{{locale}}`, `{{augmentedPrompt}}`.

## Когда использовать simple-версию
`repo.rag_search_simple` подходит для flow’ов уровня «github.repository_fetch → ждем READY → спроси про проект». MCP берёт репозиторий именно из последнего fetch (реестр обновляется после вызова инструмента) и возвращает полный DTO, чтобы не дублировать repoOwner/repoName в prompt. Ограничения:
- Требует хотя бы одного READY-репозитория (иначе ошибка).
- Не принимает фильтры — используется как быстрый старт перед более точным `repo.rag_search`.

`repo.rag_search_simple_global` — глобальный аналог: принимает только `rawQuery`, внутри запускает `repo.rag_search_global` с дефолтами и автоматически подставляет подписи `displayRepoOwner/displayRepoName`. Если fetch ещё не выполнялся, подписи остаются `global/global`, но инструмент не блокируется.

## Наблюдаемость
- Лог `appliedModules` + `rerankApplied` дают быстрый ответ на вопрос «сработал ли конкретный этап».
- Для incident-review достаточно свериться с `github.rag.*` конфигами и `appliedModules`: если не пришёл `query.translation`, значит модель решила, что язык уже подходящий.
