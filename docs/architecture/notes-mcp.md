# Notes MCP Architecture (Wave 28)

## Цели
- Сохранение пользовательских заметок через MCP-инструмент `notes.save_note`.
- Поиск похожих заметок через векторное хранилище (`notes.search_similar`).
- Интеграция с Spring AI и PgVector, минимизируя зависимости основного backend.

## Пользовательские сценарии
### Источники данных
- **Telegram**: заметка создаётся в чате; используем `chatId` как стабильный идентификатор.
- **Web FE**: заметка фиксируется из UI; идентификатор — `userId` (если авторизован) или `sessionId` (анонимный пользователь).

### Payload заметки
```jsonc
{
  "title": "string, <= 160 symbols",
  "content": "string, markdown/plain, <= 4000 symbols",
  "tags": ["string"],
  "metadata": {
    "source": "telegram|web|api|other",
    "language": "ru|en|..."
  },
  "userNamespace": "telegram|web",
  "userReference": "string (chatId/userId/sessionId)"
}
```

## Идентификация пользователей
- Поля `user_namespace` и `user_reference` обязательны, образуют уникальную пару.
- Telegram: `user_namespace=telegram`, `user_reference=<chatId>`.
- Web FE: `user_namespace=web`, `user_reference=<userId|sessionId>`.
- Возможность миграции между каналами: заметка может быть прочитана только в рамках своего namespace до введения общих правил.

## Контракт MCP инструментов
### `notes.save_note`
- **Request**
  ```jsonc
  {
    "title": "string",
    "content": "string",
    "tags": ["string"],
    "metadata": { "key": "value" },
    "userNamespace": "string",
    "userReference": "string",
    "sourceChannel": "string" // telegram/web/etc
  }
  ```
- **Response**
  ```jsonc
  {
    "noteId": "uuid",
    "createdAt": "ISO-8601",
    "embeddingProvider": "openai/text-embedding-3-small"
  }
  ```
- Идемпотентность: вычисляем SHA-256 хэш контента + user ключей.

### `notes.search_similar`
- **Request**
  ```jsonc
  {
    "query": "string",
    "userNamespace": "string",
    "userReference": "string",
    "topK": 5,
    "minScore": 0.55
  }
  ```
- **Response**
  ```jsonc
  {
    "matches": [
      {
        "noteId": "uuid",
        "title": "string",
        "content": "string",
        "score": 0.78,
        "tags": ["string"],
        "metadata": { "key": "value" },
        "createdAt": "ISO-8601",
        "updatedAt": "ISO-8601"
      }
    ]
  }
  ```
- Используем Spring AI `PgVectorStore#similaritySearch` с моделью `text-embedding-3-small`. Дополнительный реранкинг зарезервирован, но в MVP отключен.

## Архитектура развертывания
- Новый профиль `notes` в `backend-mcp`.
- Подключение к общему PostgreSQL (через отдельный schema `notes` или префикс таблиц).
- Используем Spring AI starters:
  - `spring-ai-starter-openai` (embedding client).
  - `spring-ai-pgvector-store`.
- Liquibase в `backend-mcp` управляет таблицами `note_entry`, `note_vector_store`.
- Основной backend:
  - Liquibase записи для MCP инструментов.
  - Регистрация инструментов в каталоге, без хранения данных.

## Ограничения и расширения
- Максимум 50 тегов, длина каждого ≤ 32 символов.
- Текущий размер эмбеддинга — 1536 (для `text-embedding-3-small`).
- Храним raw текст заметки и эмбеддинг; бинарные вложения не поддерживаются.
- Следующие итерации: реранкинг, REST UI для админки, observability метрики.
