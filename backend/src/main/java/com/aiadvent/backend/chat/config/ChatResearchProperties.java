package com.aiadvent.backend.chat.config;

import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.chat.research")
public class ChatResearchProperties {

  private String systemPrompt =
      """
      You are a meticulous research analyst. Use the configured MCP tools to collect current \
      information, enrich answers with concise insights, and always cite numbered sources like [1].
      """;

  private String structuredAdvice =
      """
      При необходимости вызывай подключенные MCP-инструменты, чтобы получить актуальные данные. \
      Включай ссылки на источники в поле `details`, оформляя их в формате [n], где n соответствует \
      номеру источника. Если инструмент возвращает несколько ссылок, добавь краткое пояснение каждой.
      """;

  private List<ToolBindingProperties> tools = List.of();
  private List<String> defaultToolCodes = List.of();

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public void setSystemPrompt(String systemPrompt) {
    if (StringUtils.hasText(systemPrompt)) {
      this.systemPrompt = systemPrompt;
    }
  }

  public String getStructuredAdvice() {
    return structuredAdvice;
  }

  public void setStructuredAdvice(String structuredAdvice) {
    if (StringUtils.hasText(structuredAdvice)) {
      this.structuredAdvice = structuredAdvice;
    }
  }

  public List<ToolBindingProperties> getTools() {
    return tools != null ? tools : List.of();
  }

  public void setTools(List<ToolBindingProperties> tools) {
    this.tools = tools != null ? List.copyOf(tools) : List.of();
  }

  public List<String> getDefaultToolCodes() {
    return defaultToolCodes != null ? defaultToolCodes : List.of();
  }

  public void setDefaultToolCodes(List<String> defaultToolCodes) {
    this.defaultToolCodes =
        defaultToolCodes != null ? defaultToolCodes.stream().filter(StringUtils::hasText).map(String::trim).toList() : List.of();
  }

  public static class ToolBindingProperties {

    private String code;
    private Integer schemaVersion;
    private AgentInvocationOptions.ExecutionMode executionMode;
    private Map<String, Object> requestOverrides;

    public String getCode() {
      return code != null ? code.trim() : null;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String code() {
      return getCode();
    }

    public Integer getSchemaVersion() {
      return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
      this.schemaVersion = schemaVersion;
    }

    public AgentInvocationOptions.ExecutionMode getExecutionMode() {
      return executionMode;
    }

    public void setExecutionMode(AgentInvocationOptions.ExecutionMode executionMode) {
      this.executionMode = executionMode;
    }

    public Map<String, Object> getRequestOverrides() {
      return requestOverrides;
    }

    public void setRequestOverrides(Map<String, Object> requestOverrides) {
      this.requestOverrides = requestOverrides;
    }

    public int schemaVersion() {
      return schemaVersion != null ? schemaVersion : 1;
    }

    public AgentInvocationOptions.ExecutionMode executionMode() {
      return executionMode != null ? executionMode : AgentInvocationOptions.ExecutionMode.AUTO;
    }

    public Map<String, Object> requestOverrides() {
      return requestOverrides != null ? requestOverrides : Map.of();
    }
  }
}
