package com.aiadvent.backend.chat.logging;

import com.aiadvent.backend.chat.config.ChatLoggingProperties;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * Centralizes optional chat/tool logging decoration so that it can be applied consistently.
 */
public class ChatLoggingSupport {

  private final ChatLoggingProperties properties;
  private final Map<ChatModel, ChatModel> modelCache = new ConcurrentHashMap<>();
  private final Map<ToolCallback, ToolCallback> toolCallbackCache = new ConcurrentHashMap<>();
  private final Map<ToolCallingManager, ToolCallingManager> toolManagerCache = new ConcurrentHashMap<>();

  public ChatLoggingSupport(ChatLoggingProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  public ChatModel decorateModel(ChatModel delegate) {
    if (delegate == null || delegate instanceof LoggingChatModel || !properties.getModel().isEnabled()) {
      return delegate;
    }
    return modelCache.computeIfAbsent(delegate, key -> new LoggingChatModel(key, properties.getModel().isLogCompletion()));
  }

  public ToolCallback decorateToolCallback(ToolCallback delegate) {
    if (delegate == null || delegate instanceof LoggingToolCallback || !properties.getTools().isEnabled()) {
      return delegate;
    }
    return toolCallbackCache.computeIfAbsent(delegate, LoggingToolCallback::new);
  }

  public List<ToolCallback> decorateToolCallbacks(List<ToolCallback> callbacks) {
    if (callbacks == null || callbacks.isEmpty() || !properties.getTools().isEnabled()) {
      return callbacks;
    }
    return callbacks.stream().map(this::decorateToolCallback).collect(Collectors.toUnmodifiableList());
  }

  public ToolCallingManager decorateToolCallingManager(ToolCallingManager delegate) {
    if (delegate == null || delegate instanceof LoggingToolCallingManager || !properties.getTools().isEnabled()) {
      return delegate;
    }
    return toolManagerCache.computeIfAbsent(delegate, LoggingToolCallingManager::new);
  }
}
