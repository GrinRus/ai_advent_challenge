package com.aiadvent.backend.chat.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Decorator for {@link ToolCallback} that logs tool input/output exchange.
 */
public class LoggingToolCallback implements ToolCallback {

  private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);

  private final ToolCallback delegate;

  public LoggingToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public String call(String input) {
    String toolName = resolveToolName();
    if (log.isDebugEnabled()) {
      log.debug("TOOL[{}] input: {}", toolName, input);
    }
    try {
      String result = delegate.call(input);
      if (log.isDebugEnabled()) {
        log.debug("TOOL[{}] output: {}", toolName, result);
      }
      return result;
    } catch (RuntimeException exception) {
      if (log.isDebugEnabled()) {
        log.debug("TOOL[{}] raised: {}", toolName, exception.getMessage(), exception);
      }
      throw exception;
    }
  }

  @Override
  public String call(String input, ToolContext toolContext) {
    String toolName = resolveToolName();
    if (log.isDebugEnabled()) {
      log.debug("TOOL[{}] input: {}", toolName, input);
    }
    try {
      String result = delegate.call(input, toolContext);
      if (log.isDebugEnabled()) {
        log.debug("TOOL[{}] output: {}", toolName, result);
      }
      return result;
    } catch (RuntimeException exception) {
      if (log.isDebugEnabled()) {
        log.debug("TOOL[{}] raised: {}", toolName, exception.getMessage(), exception);
      }
      throw exception;
    }
  }

  private String resolveToolName() {
    ToolDefinition definition = delegate.getToolDefinition();
    return definition != null ? definition.name() : "unknown";
  }
}
