# MCP Servers Guide for Operators & Analysts

Этот документ описывает, как подключаться к внутренним MCP-серверам (Flow Ops, Agent Ops, Insight), запускать их локально и безопасно пользоваться инструментами.

## Запуск инфраструктуры

1. Установите Docker Desktop (или совместимый движок), убедитесь, что `docker compose` доступен из CLI.
2. Склонируйте репозиторий и скопируйте `.env.example` → `.env`. Отредактируйте блок `*_MCP_*`, если хотите подключаться к staging/prod backend-у.
3. Поднимите окружение:
   ```bash
   docker compose up --build backend frontend agent-ops-mcp flow-ops-mcp insight-mcp
   ```
   - `backend` — основной REST API.
   - `agent-ops-mcp`, `flow-ops-mcp`, `insight-mcp` — STDIO MCP-сервера (Spring Boot). Healthcheck следит, что Java-процесс жив.
   - Переменные `AGENT_OPS_BACKEND_BASE_URL`, `FLOW_OPS_BACKEND_BASE_URL`, `INSIGHT_BACKEND_BASE_URL` указывают базовый URL API; по умолчанию — `http://backend:8080` внутри compose-сети.

## Подключение backend → MCP

Backend вызывает команду из `spring.ai.mcp.client.stdio.connections.<server>.command`. Для работы с контейнерами доступны два варианта:

### 1. Backend на хостовой машине

Создайте исполняемые скрипты, которые пробрасывают STDIO в контейнер.

```bash
#!/usr/bin/env bash
exec docker exec -i ai-advent-agent-ops-mcp java \
  -Dspring.profiles.active=agentops \
  -cp /app/app.jar com.aiadvent.backend.mcp.AgentOpsMcpApplication
```

Поместите файл в PATH и укажите `AGENT_OPS_MCP_CMD=agent-ops-mcp.sh` (аналогично для Flow/Insight). Команда будет вызываться напрямую из backend.

### 2. Backend внутри Docker

В docker-compose backend не имеет доступа к docker socket, поэтому STDIO запускает MCP в том же контейнере. Обновите `backend` образ, добавив wrapper-скрипты и Supervisor, либо используйте sidecar-контейнер и подключение по TCP (например, через `socat`). Простейший вариант — запуск backend локально (`./gradlew bootRun`), пока MCP работают в контейнерах.

## IDE и внешние клиенты

- MCP использует стандарт [Model Context Protocol](https://modelcontextprotocol.dev). Большинство IDE-расширений (Cursor, Continue, Windsurf) поддерживают локальные STDIO-серверы.
- Для подключения выберите "Custom MCP" и укажите команду запуска:
  - Flow Ops: `docker exec -i ai-advent-flow-ops-mcp java -jar /app/app.jar`
  - Agent Ops: `docker exec -i ai-advent-agent-ops-mcp java -jar /app/app.jar`
  - Insight: `docker exec -i ai-advent-insight-mcp java -jar /app/app.jar`
- Для защищённых сред вместо `docker exec` можно использовать SSH-туннель и запускать jar напрямую на сервере.

## Примеры запросов

### Flow Ops
```
User: Покажи опубликованные flow и сравни версии 12 и 13 для `lead-qualification`.
Tool: flow_ops.list_flows → flow_ops.diff_flow_version → flow_ops.validate_blueprint
```

### Agent Ops
```
User: Создай черновой агент `demo-agent`, включи инструменты agent_ops.* и подготовь capability payload.
Tool: agent_ops.list_agents → agent_ops.register_agent → agent_ops.preview_dependencies
```

### Insight
```
User: Найди последние 10 сессий типа FLOW, затем покажи метрики для `sessionId=...`.
Tool: insight.recent_sessions → insight.fetch_metrics
```

## Безопасность

- Инструменты Flow Ops и Agent Ops имеют права на изменение production-конфигураций. Используйте API-токены с минимальными привилегиями (`*_BACKEND_API_TOKEN`) и ограничивайте доступ к контейнерам через firewall/ssh.
- Логи MCP не должны содержать конфиденциальные данные; перенаправляйте STDIO в файлы/ELK с ротацией.
- При работе из IDE держите в голове, что MCP-команда выполняется локально: не храните токены в публичных dotfiles.

## Тестирование

- Unit: `McpCatalogServiceTest`, `McpHealthControllerTest`, `McpHealthIndicatorTest` покрывают доступность инструментария.
- Integration/e2e: при запуске `docker compose up` выполните `./gradlew test --tests *Mcp*` и UI Playwright-сценарии, которые включают MCP-инструменты в чате.
