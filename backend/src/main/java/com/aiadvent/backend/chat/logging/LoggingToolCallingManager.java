package com.aiadvent.backend.chat.logging;

import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Decorator for {@link ToolCallingManager} that logs resolution and execution details.
 */
public class LoggingToolCallingManager implements ToolCallingManager {

  private static final Logger log = LoggerFactory.getLogger(LoggingToolCallingManager.class);

  private final ToolCallingManager delegate;

  public LoggingToolCallingManager(ToolCallingManager delegate) {
    this.delegate = delegate;
  }

  @Override
  public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
    if (log.isDebugEnabled()) {
      log.debug(
          "TOOL-MANAGER resolve definitions: names={} callbacks={}",
          chatOptions != null ? chatOptions.getToolNames() : List.of(),
          chatOptions != null ? chatOptions.getToolCallbacks().stream()
              .map(callback -> callback.getToolDefinition().name())
              .collect(Collectors.toList())
              : List.of());
    }
    List<ToolDefinition> definitions = delegate.resolveToolDefinitions(chatOptions);
    if (log.isDebugEnabled()) {
      log.debug(
          "TOOL-MANAGER resolved definitions: {}",
          definitions.stream().map(ToolDefinition::name).collect(Collectors.toList()));
    }
    return definitions;
  }

  @Override
  public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
    if (log.isDebugEnabled()) {
      log.debug("TOOL-MANAGER execute: prompt={}, response={}", prompt, chatResponse);
    }
    ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
    if (log.isDebugEnabled()) {
      log.debug(
          "TOOL-MANAGER result: returnDirect={}, history={}",
          result != null && result.returnDirect(),
          result != null ? result.conversationHistory() : null);
    }
    return result;
  }
}
