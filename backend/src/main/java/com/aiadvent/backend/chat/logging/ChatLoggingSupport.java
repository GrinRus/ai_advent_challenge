package com.aiadvent.backend.chat.logging;

import com.aiadvent.backend.chat.config.ChatLoggingProperties;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * Centralizes optional chat/tool logging decoration so that it can be applied consistently.
 */
public class ChatLoggingSupport {

  private final ChatLoggingProperties properties;

  public ChatLoggingSupport(ChatLoggingProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  public ChatModel decorateModel(ChatModel delegate) {
    if (delegate == null || !properties.getModel().isEnabled() || delegate instanceof LoggingChatModel) {
      return delegate;
    }
    return new LoggingChatModel(delegate, properties.getModel().isLogCompletion());
  }

  public ToolCallback decorateToolCallback(ToolCallback delegate) {
    if (delegate == null || !properties.getTools().isEnabled() || delegate instanceof LoggingToolCallback) {
      return delegate;
    }
    return new LoggingToolCallback(delegate);
  }

  public List<ToolCallback> decorateToolCallbacks(List<ToolCallback> callbacks) {
    if (callbacks == null || callbacks.isEmpty() || !properties.getTools().isEnabled()) {
      return callbacks;
    }
    return callbacks.stream().map(this::decorateToolCallback).collect(Collectors.toUnmodifiableList());
  }
}
