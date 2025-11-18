# Flow personalization guide

Этот документ описывает, как использовать профили пользователей в assisted-coding и flow сценариях, чтобы тон, язык и привычки находились в одном источнике правды и автоматически подмешивались в промпты Spring AI.

## 1. Заголовок `X-Profile-Key`

Все вызовы `/api/llm/*` и `/api/flows/*` обязаны передавать заголовок `X-Profile-Key` в формате `namespace:reference`. Примеры:

```http
X-Profile-Key: web:demo_user
X-Profile-Channel: web
```

- **namespace** — канал или продукт (`web`, `telegram`, `cli`).
- **reference** — уникальный идентификатор в пределах канала (`username`, Chat ID и т.д.).
- **X-Profile-Channel** — опционально, если нужно запросить overrides для конкретного интерфейса (web/telegram).
- Контроллеры backend валидируют, что path-параметры совпадают с заголовком. Несовпадающий ключ → `400 Bad Request`.

## 2. Dev-only режим

Для локального тестирования без OAuth:

1. Установите переменные окружения:
   ```bash
   PROFILE_DEV_ENABLED=true
   PROFILE_BASIC_TOKEN=dev-profile-token
   ```
2. Передавайте заголовок `X-Profile-Auth: dev-profile-token` вместе с `X-Profile-Key`.
3. Для Telegram/CLI можно выпустить одноразовый dev-link: на фронтенде откройте баннер “Dev session” и нажмите “Создать dev-link” или выполните `POST /api/profile/{ns}/{ref}/dev-link` (требует dev-token). TTL задаётся через `app.profile.dev.link-ttl` (по умолчанию 10 минут).
4. Режим включает фильтр `ProfileDevAuthFilter`, который разрешает запросы и помечает их как dev-only. Выключайте флаг перед деплоем.
5. Проверка: `curl -H 'X-Profile-Key: web:demo' -H 'X-Profile-Auth: dev-profile-token' http://localhost:8080/api/profile/web/demo`.

## 3. Persona snippets в Spring AI

`SyncChatService`, `StructuredSyncService` и `AgentInvocationService` автоматически получают профиль через `ProfileContextHolder` (его устанавливает `ProfileContextFilter` или, в случае Telegram, сам сервис). Сниппет строится `ProfilePromptService` и добавляется к system prompt’у, когда включён фичефлаг. Чтобы отключить:

```yaml
app:
  profile:
    prompts:
      enabled: false
```

## 4. Telegram и CLI каналы

- Telegram вызывает `UserProfileService.resolveProfile("telegram", userId)` при первом сообщении, log’и фиксируют `profile_created/profile_updated/identity_attached`.
- Для CLI/других каналов используйте `ProfileHandleService.resolveOrCreateHandle(namespace, reference, supplier)` чтобы обеспечить idempotent привязку.
- Любой асинхронный обработчик обязан оборачивать вызов в `ProfileContextHolder.set(...)` и очищать контекст после завершения, иначе persona может не примениться.

## 5. ETag / If-Match

Профили версионированы (`@Version`). API `/api/profile` возвращает заголовок `ETag: W/"<version>"`. Для безопасного обновления отправляйте `If-Match`:

```http
PUT /api/profile/web/demo
ETag: W/"5"
If-Match: W/"5"
```

При несовпадении backend вернёт `412 Precondition Failed`.

## 6. UX флоу «привязать внешний провайдер»

Пока реальная реализация VK OAuth запланирована на Wave 43, UX и API-контракты фиксируем уже сейчас:

1. Web UI отправляет `POST /api/profile/{ns}/{ref}/identities/{provider}/authorize` — backend генерирует `state`, `deviceId`, `codeChallenge` и возвращает `authorizationUrl + expiresAt`.
2. UI выводит модалку с кнопкой «Открыть провайдера» и подсказкой для Telegram: `state` и `deviceId` можно использовать, чтобы открыть ссылку на другом устройстве.
3. После клика UI открывает `authorizationUrl` в новом окне и запускает polling `GET /api/profile/{ns}/{ref}/identities/status?provider=vk&state=...` (интервал 2 сек, таймаут 2 мин). Telegram бот делает тоже самое во время linking.
4. Когда backend завершает обмен токена и сохраняет `user_identity`, ответ status меняется на `linked`, фронтенд перезагружает профиль и показывает toast «Аккаунт привязан».
5. При `error` UI отображает сообщение, предлагает «Повторить» (генерация нового state).

Обязательные поля ответов:
```json
{
  "authorizationUrl": "https://id.vk.ru/authorize?state=...",
  "state": "abc123",
  "deviceId": "dev-xyz",
  "expiresAt": "2025-02-10T12:00:00Z"
}
```

Polling status возвращает `{"status":"pending|linked|error","message":"..."}`. Telegram по завершении получает webhook-event `identity_linked` и уведомляет пользователя.

## 7. Инвалидация кеша и метрики

- `profile_resolve_seconds`, `user_profile_cache_hit_total`, `user_profile_cache_miss_total`, `profile_identity_total` доступны в Micrometer.
- Любое обновление вызывает `ProfileChangedEvent` → Redis pub/sub → сброс локальных Caffeine кешей.
- Для ручного сброса можно удалить ключ `profile:cache:<namespace>:<reference>` из Redis или вызвать будущий `ProfileAdminController` (см. backlog Wave 42).

## 7. Identity и OAuth задел

- `POST /api/profile/{namespace}/{reference}/identities` добавляет запись (`provider`,`externalId`), лимит — 5. Duplicate provider/externalId запрещён.
- Dev сценарии для привязки провайдеров можете тестировать через заглушки CLI/Telegram (`/link_vk`) с dev-tokenом, пока VK OAuth не реализован.

## 8. Админ-функции (roadmap Wave 42)

- В работе `ProfileAdminController` и `RoleAssignmentService`: REST `/api/admin/roles`, audit log, микросервисные метрики.
- UI «Admin / Roles» будет отображать профили, фильтровать по namespace и поддерживать выдачу/снятие ролей в реальном времени.

## 9. Быстрый чек-лист перед релизом

1. Убедитесь, что все клиенты (web, CLI, Telegram) выставляют `X-Profile-Key` и при необходимости `X-Profile-Auth` в dev-режиме.
2. Persona snippets включены только там, где это ожидаемо (`ENABLE_PROFILE_PROMPTS`).
3. Логи `profile_created/profile_updated/identity_attached` видны и отправляются в наблюдаемость.
4. Метрики доступны в Prometheus/Grafana, алерты на деградацию кеша настроены.
5. Документация (`docs/backlog.md`, `docs/infra.md`, текущий гайд) обновлена со ссылками на последние изменения.
