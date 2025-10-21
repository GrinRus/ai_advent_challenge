# Wave 8 — Fallback токенайзер и stream usage

Дата: 2025-10-21  
Статус: в `main`, ожидает rollout после полной верификации

## Контекст
- В стриминговых ответах OpenAI появился `stream_options.include_usage`, но не все провайдеры возвращают usage (например, zhipu.ai).
- Для консистентности биллинга добавлен fallback-подсчёт токенов через Spring AI + [jtokkit](https://github.com/knuddelsgmbh/jtokkit) и Redis-кеширование.
- SSE API теперь передаёт источник usage (`usageSource = native|fallback`), что требуется для отображения в UI и мониторинга отклонений.

## Решение
1. **Конфигурация провайдеров**
   - В `app.chat.providers.*.models` добавлено поле `usage.mode` (`native`, `fallback`, `auto`) и, при необходимости, `usage.fallback-tokenizer`.
   - Глобальные настройки `app.chat.token-usage.*` позволяют задавать дефолтный токенайзер и TTL кэша.

2. **TokenUsageEstimator**
   - Реализован сервис `DefaultTokenUsageEstimator`, использующий `EncodingRegistry` Spring AI и опциональный Redis (`TokenUsageCache`).
   - При отсутствии провайдерского usage рассчитываем токены, записываем источник `UsageSource.FALLBACK`, сохраняем результаты в Redis (MD5 ключи `chat:usage:<tokenizer>:segment:<hash>`).

3. **Streaming pipeline**
   - `ChatStreamController` запоминает исходный prompt и, на completion, вычисляет usage через `ChatProviderService`. Финальный SSE содержит `usageSource`.
   - `ChatService` сохраняет usage/cost в истории (поведение сохранено с Wave 6).
   - Метрики Micrometer фиксируют долю fallback (`chat.usage.*`) и задержки Redis-кеша (`chat.token.cache.*`), что позволяет настраивать алерты на рост расхождений.

4. **Инфраструктура**
   - В `docker-compose.yml` добавлен сервис `redis` (по умолчанию порт на хосте `6380`). Кэш выключен (`CHAT_TOKEN_USAGE_CACHE_ENABLED=false`), включается через env.
   - Документация (`docs/infra.md`, `docs/processes.md`) описывает сценарии включения, настройки Redis и требования к тестам.

## Альтернативы
- Использовать только local-кэш в памяти — отклонено: не подходит для горизонтального масштабирования и перезапусков.
- Сохранять usage в БД и переиспользовать — отклонено: усложняет консистентность, проще кэшировать и переоценивать.

## Риски
- Расхождение native vs fallback (>5 %) — требуется мониторинг, добавим метрики в следующих итерациях.
- Redis недоступен — `TokenUsageCache` автоматически деградирует в no-op и подсчёты выполняются заново (чуть выше latency).
- Новые модели без поддерживаемого токенизатора — требуется обновление конфигурации `usage.fallback-tokenizer`.

## Следующие шаги
- UI: отображение `Usage: native|fallback` и подсветка fallback случаев.
- Мониторинг: агрегировать hit/miss Redis, сравнивать native с fallback и готовить алерты.
