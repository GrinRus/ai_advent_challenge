# Wave 37 — RAG Free-form Coverage & Dual Responses

## Overview
- `repo.rag_search` и `repo.rag_search_global` автоматически выполняют повторный поиск при «overview» запросах: Guard опускает `minScore` до fallback-значения, форсирует multi-query и добавляет подсказки по README/backlog. В `appliedModules` появляются теги `retrieval.low-threshold` и `retrieval.overview-seed`, а в `warnings[]` — сообщение об автоперезапуске.
- Ответ инструмента теперь содержит два канала: `summary` (краткое резюме из нового шаблона `prompts/github-rag-summary.st`) и `rawAnswer` (прежний augmentedPrompt). Клиенты могут управлять выдачей через поле `responseChannel=summary|raw|both`.
- `warnings[]` агрегирует как исправления санитайзера, так и fallback-события сервиса, чтобы оператор видел причину пониженных порогов и автоподстановок. AppliedModules и логи `rag_parameter_guard` позволяют отслеживать, когда Guard урезал параметры профиля.

## Operator Actions
1. Обновите UI/агентов: добавьте опциональное поле `responseChannel` и отобразите оба канала, если ответ возвращается с `summary`+`rawAnswer`.
2. Следите за `warnings[]` и `appliedModules`: появление `retrieval.low-threshold`/`retrieval.overview-seed` означает, что запрос попал в fallback-ветку; при необходимости скорректируйте wording пользователя вместо ручного понижения `minScore`.
3. Логи `rag_parameter_guard` продолжают фиксировать клампы профиля; их значения теперь удобнее сопоставлять с warnings, чтобы быстрее диагностировать слишком «широкие» запросы.

## Developer Notes
- Конфигурация профилей (`github.rag.parameter-profiles[]`) расширена полями `min-score-fallback`, `min-score-classifier`, `overview-boost-keywords[]`. Aggressive профиль включает детектор overview-запросов из коробки.
- GenerationService использует две PromptTemplate (основную и summary) на базе Spring AI ContextualQueryAugmenter, поэтому любые изменения в `prompts/github-rag-context.st`/`prompts/github-rag-summary.st` автоматически подхватываются.
- Тесты обновлены: `RepoRagSearchServiceTest`, `RepoRagToolsTest/IT`, `RepoRagGenerationServiceTest`, `RagParameterGuardTest` и `RepoRagToolInputSanitizerTest` покрывают fallback, response-channel и summary/raw поля.
