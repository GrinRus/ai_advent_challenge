# Telegram бот: CI и тестирование

## Интеграционные тесты (план)
1. **Mock Telegram API** — поднять WireMock/MockServer, эмулировать `getFile`, `setWebhook`, `sendMessage`.
2. **HTTP обращение бота** — использовать Spring Boot `@SpringBootTest` + `MockMvc` для отправки `POST /telegram/update` с:
   - текстовым сообщением; ожидаем вызов SyncChatService и ответ с метаданными.
   - голосовым сообщением (Voice payload) + заглушка на `getFile` → проверка транскрипции (подменить `OpenAiAudioTranscriptionModel` тестовым бинном).
3. **Callback сценарии** — `callback_query` для каждой ветки меню (`set-provider`, `set-model`, `set-mode`, `toggle-tool`, `sampling:*`, `action:new`, `action:stop`, `action:repeat`, `result:details`, `result:structured`).
4. **Stop/Repeat** — симулировать долгий `SyncChatService` (Future) и убедиться, что `/action:stop` отменяет выполнение, `/action:repeat` повторяет prompt.
5. **Structured payload** — подменить SyncChatService ответом с `structured` и проверить наличие кнопки и карточки «Структура».

## Набор метрик/логов
- `telegram_updates_total{type=message|voice|callback}` — счётчик входящих апдейтов.
- `telegram_requests_in_flight`/`telegram_request_duration` — мониторинг активных sync-вызовов (включая отмены).
- `telegram_stt_seconds_total` и `telegram_stt_failures_total` — оборудование STT.
- `telegram_callbacks_failures_total{action=...}` — ошибки обработки inline-кнопок.
- Логи: `INFO` для ключевых событий (`processing`, `completed`), `WARN` для сбоев Telegram API/STT.

## CI-пайплайн (минимум)
1. Добавить шаг `docker compose -f docker-compose.yml config` для проверки env.
2. Запуск интеграционных тестов (`./gradlew test --tests *Telegram*`).
3. Вёрка Webhook URL/токена из секретов (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_WEBHOOK_URL`, `TELEGRAM_BOT_WEBHOOK_SECRET`).
4. Проверка `setWebhook` (опционально) через e2e или smoke-тест в отдельном job.

## TODO
- Реализовать мок Telegram API и интеграционные тесты (Wave 27 backlog).
- Завести Grafana dashboard с вышеперечисленными метриками.
- Подготовить smoke-сценарий CLI: `./scripts/telegram-test.sh "Привет"` (отправляет запрос через Bot API).
