package com.aiadvent.mcp.backend.agentops;

import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class AgentOpsTools {

  private final AgentOpsClient client;

  public AgentOpsTools(AgentOpsClient client) {
    this.client = client;
  }

  @Tool(
      name = "agent_ops.list_agents",
      description =
          "Возвращает список зарегистрированных агентов. "
              + "Поддерживает фильтрацию по подстроке и признаку активности.")
  public List<AgentSummary> listAgents(ListAgentsInput input) {
    return client.listAgents(input == null ? new ListAgentsInput(null, null, null) : input);
  }

  @Tool(
      name = "agent_ops.register_agent",
      description =
          "Создает новую запись агента в каталоге. "
              + "Требует поля identifier, displayName и createdBy.")
  public RegisterAgentResult registerAgent(RegisterAgentInput input) {
    return client.registerAgent(input);
  }

  @Tool(
      name = "agent_ops.preview_dependencies",
      description =
          "Возвращает зависимости агента: версии, провайдеры, привязанные MCP-инструменты. "
              + "Можно указать definitionId (UUID) или identifier.")
  public PreviewDependenciesResult previewDependencies(PreviewDependenciesInput input) {
    return client.previewDependencies(input);
  }
}
