package com.aiadvent.backend.chat.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;

class PerplexityMcpHealthIndicatorTest {

  @Test
  void healthIsUnknownWhenProviderMissing() {
    MeterRegistry registry = new SimpleMeterRegistry();
    ObjectProvider<SyncMcpToolCallbackProvider> provider = new StaticObjectProvider<>(null);

    PerplexityMcpHealthIndicator indicator =
        new PerplexityMcpHealthIndicator(provider, registry);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
  }

  @Test
  void healthUpWhenToolsAvailable() {
    MeterRegistry registry = new SimpleMeterRegistry();
    SyncMcpToolCallbackProvider syncProvider = mock(SyncMcpToolCallbackProvider.class);
    ToolCallback callback = mock(ToolCallback.class);
    ToolDefinition definition = mock(ToolDefinition.class);
    when(definition.name()).thenReturn("perplexity_search");
    when(callback.getToolDefinition()).thenReturn(definition);
    when(syncProvider.getToolCallbacks()).thenReturn(new ToolCallback[] {callback});

    ObjectProvider<SyncMcpToolCallbackProvider> provider =
        new StaticObjectProvider<>(syncProvider);
    PerplexityMcpHealthIndicator indicator =
        new PerplexityMcpHealthIndicator(provider, registry);

    var health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("toolCount", 1)
        .containsEntry("tools", Arrays.asList("perplexity_search"));
    assertThat(registry.find("perplexity_mcp_latency").timer().count()).isEqualTo(1);
  }

  @Test
  void healthDownWhenProviderThrows() {
    MeterRegistry registry = new SimpleMeterRegistry();
    SyncMcpToolCallbackProvider syncProvider = mock(SyncMcpToolCallbackProvider.class);
    when(syncProvider.getToolCallbacks()).thenThrow(new IllegalStateException("boom"));

    ObjectProvider<SyncMcpToolCallbackProvider> provider =
        new StaticObjectProvider<>(syncProvider);
    PerplexityMcpHealthIndicator indicator =
        new PerplexityMcpHealthIndicator(provider, registry);

    var health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("error");
    assertThat(registry.find("perplexity_mcp_errors_total").counter().count()).isEqualTo(1.0);
  }

  private static final class StaticObjectProvider<T> implements ObjectProvider<T> {
    private final T value;

    private StaticObjectProvider(T value) {
      this.value = value;
    }

    @Override
    public T getObject(Object... args) {
      if (value == null) {
        throw new IllegalStateException("No bean available");
      }
      return value;
    }

    @Override
    public T getObject() {
      return getObject(new Object[0]);
    }

    @Override
    public T getIfAvailable() {
      return value;
    }

    @Override
    public T getIfUnique() {
      return value;
    }

    @Override
    public void ifAvailable(java.util.function.Consumer<T> consumer) {
      if (value != null) {
        consumer.accept(value);
      }
    }

    @Override
    public void ifUnique(java.util.function.Consumer<T> consumer) {
      if (value != null) {
        consumer.accept(value);
      }
    }
  }
}
