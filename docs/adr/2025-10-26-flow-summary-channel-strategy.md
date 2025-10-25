# ADR: Стратегия каналов для flow-саммари (Wave 10.2)

**Дата:** 2025-10-26  
**Контекст:** Wave 10.2 требовала довести flow-саммаризацию до production-ready состояния. Главная проблема — пользовательские сообщения писались в канал `conversation`, а агентские ответы — в `shared` (или другие каналы из DSL). `FlowMemorySummarizerService` умел читать только `conversation`, поэтому summary строилось лишь по половине диалога. Одновременно требовалось обеспечить совместимость с HITL (interaction-пайплайн) и автоматический rebuild без touch DSL.

## Рассматриваемые варианты

1. **Channel unification (выбран)**  
   - Все пользовательские и агентские сообщения гарантированно дублируются в канал `conversation`.  
   - Оркестратор автоматически добавляет `conversation` в `memoryReads`, а `AgentOrchestratorService` зеркалирует `AGENT_OUTPUT` в `conversation`, даже если DSL использует другие каналы.  
   - Преимущества: упрощённый DX, одна точка входа для summarizer, не нужно бранчить FlowMemorySummarizerService, HitL автоматически получает summary + хвост.  
   - Недостатки: требуется миграция существующих определений (безопасно делаем на уровне оркестратора), сниженная гибкость для экзотических каналов.

2. **Multi-channel summaries**  
   - Summarizer принимает список каналов от DSL.  
   - Преимущества: максимальная гибкость.  
   - Недостатки: усложнение FlowMemorySummarizerService, необходимость конфигурировать каналы руками, риск пропустить канал и потерять контекст, усложнение FlowInteractionService.

3. **Hybrid**  
   - Дефолт `conversation`, whitelist дополнительных каналов.  
   - Преимущества: более плавный rollout.  
   - Недостатки: те же сложности конфигурации, необходимость синхронизации whitelist между backend и UI.

## Решение

Выбрали **Channel unification**:

- `FlowDefinitionParser` автоматически добавляет `conversation` в `memoryReads`, если канал не указан явно.  
- `AgentOrchestratorService` зеркалирует `AGENT_OUTPUT` в `conversation` (через `FlowMemoryService.append`) и сохраняет метаданные (`agentVersionId`, `stepAttempt`).  
- `FlowInteractionService` после ответов оператора пишет payload в `conversation` и триггерит summarizer, чтобы HITL-ответы появлялись в summary.  
- `FlowMemorySummarizerService` остаётся одно-канальным, но принимает список каналов только для ручного rebuild (`forceSummarize`).  
- UI, документация и runbook фиксируют, что `conversation` — канонический канал.

## Последствия

- Summarizer всегда получает пары «пользователь ↔ агент» и запускается автоматически перед каждым шагом и после HITL.  
- Простая интеграция CLI/REST rebuild: достаточно указать сессию и провайдера, канал по умолчанию `conversation`.  
- Любые специализированные каналы (shared context, telemetry) используются только как дополнительные источники, но не влияют на summary.  
- Документация (`docs/infra.md`, `docs/processes.md`, `docs/architecture/flow-definition.md`) обновлена, backlog Wave 10.2 отмечен.
