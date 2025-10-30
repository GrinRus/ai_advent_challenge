# MCP Servers Guide for Operators & Analysts

Этот документ описывает, как подключаться к внутренним MCP-серверам (Flow Ops, Agent Ops, Insight, GitHub), запускать их локально и безопасно пользоваться инструментами.

## Запуск инфраструктуры

1. Установите Docker Desktop (или совместимый движок), убедитесь, что `docker compose` доступен из CLI.
2. Склонируйте репозиторий и скопируйте `.env.example` → `.env`. Отредактируйте блок `*_MCP_*`, если хотите подключаться к staging/prod backend-у.
3. Поднимите окружение:
 ```bash
  docker compose up --build backend frontend agent-ops-mcp flow-ops-mcp insight-mcp github-mcp
  ```
  - `backend` — основной REST API.
  - `agent-ops-mcp`, `flow-ops-mcp`, `insight-mcp`, `github-mcp` — HTTP MCP-сервера (Spring Boot, streamable transport). Контейнеры слушают порт `8080`; на хост по умолчанию пробрасываются порты `7091`, `7092`, `7093`, `7094`.
  - Переменные `AGENT_OPS_BACKEND_BASE_URL`, `FLOW_OPS_BACKEND_BASE_URL`, `INSIGHT_BACKEND_BASE_URL`, `GITHUB_API_BASE_URL` указывают базовый URL API бэкенда/внешнего сервиса; по умолчанию — `http://backend:8080` для внутренних MCP и `https://api.github.com` для GitHub.

## Подключение backend → MCP

Backend использует `spring.ai.mcp.client.streamable-http.connections.<server>` для внутренних сервисов и `spring.ai.mcp.client.stdio.connections.perplexity` для Perplexity. Для HTTP каждый сервер описывается парой `url` + `endpoint` (по умолчанию `/mcp`).

- Backend внутри docker-compose автоматически получает адреса `http://agent-ops-mcp:8080`, `http://flow-ops-mcp:8080`, `http://insight-mcp:8080`, `http://github-mcp:8080`.
- При запуске backend на хостовой машине укажите:
  ```bash
  export AGENT_OPS_MCP_HTTP_BASE_URL=http://localhost:7091
  export FLOW_OPS_MCP_HTTP_BASE_URL=http://localhost:7092
  export INSIGHT_MCP_HTTP_BASE_URL=http://localhost:7093
  export GITHUB_MCP_HTTP_BASE_URL=http://localhost:7094
  ```
  При необходимости измените порты (`AGENT_OPS_MCP_HTTP_PORT`, `FLOW_OPS_MCP_HTTP_PORT`, `INSIGHT_MCP_HTTP_PORT`, `GITHUB_MCP_HTTP_PORT`) в `docker-compose.yml`.
- Перplexity продолжает работать через STDIO: убедитесь, что бинарь/скрипт `perplexity-mcp` доступен в `PATH`, либо переопределите `PERPLEXITY_MCP_CMD`.

## IDE и внешние клиенты

- MCP использует стандарт [Model Context Protocol](https://modelcontextprotocol.dev). Внутренние сервисы доступны по HTTP/SSE, а Perplexity — через STDIO.
- Для подключения к HTTP-серверам выберите "Custom MCP (HTTP)" и укажите:
  - Flow Ops: `URL=http://localhost:7092`, `Endpoint=/mcp`
  - Agent Ops: `URL=http://localhost:7091`, `Endpoint=/mcp`
  - Insight: `URL=http://localhost:7093`, `Endpoint=/mcp`
  - GitHub: `URL=http://localhost:7094`, `Endpoint=/mcp`
- Для Perplexity используйте STDIO команду (`perplexity-mcp --api-key ...`) или собственный wrapper. IDE обычно позволяет указать произвольную команду запуска.
- Для защищённых сред вместо проброса портов используйте reverse proxy/SSH-туннель. MCP-серверы — обычные Spring Boot приложения, поэтому можно разворачивать их за ingress-контроллером или API Gateway. GitHub MCP требует корректно настроенный GitHub App либо PAT токен, заданные через переменные окружения.

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
- Ограничьте сеть и аутентификацию HTTP MCP (mTLS, OAuth2 proxy). Минимум — закрыть порты 7091-7093 во внешнем фаерволе и экспонировать их через VPN.
- Логи MCP не должны содержать конфиденциальные данные; используйте централизованный сбор (stdout контейнера) и ротацию.

## Тестирование

- Unit: `McpCatalogServiceTest`, `McpHealthControllerTest`, `McpHealthIndicatorTest` покрывают доступность инструментария.
- Integration/e2e: при запуске `docker compose up` выполните `./gradlew test --tests *Mcp*` и UI Playwright-сценарии, которые включают MCP-инструменты в чате.
