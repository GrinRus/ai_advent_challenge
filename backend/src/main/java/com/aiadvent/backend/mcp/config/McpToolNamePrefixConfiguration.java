package com.aiadvent.backend.mcp.config;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

@Configuration
public class McpToolNamePrefixConfiguration {

  @Bean
  @Primary
  public McpToolNamePrefixGenerator deduplicatingMcpToolNamePrefixGenerator() {
    return new DeduplicatingToolNamePrefixGenerator();
  }

  private static final class DeduplicatingToolNamePrefixGenerator
      implements McpToolNamePrefixGenerator {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public String prefixedToolName(McpConnectionInfo connectionInfo, McpSchema.Tool tool) {
      String connectionName = extractConnectionName(connectionInfo);
      String sanitizedConnection = McpToolUtils.format(connectionName);
      String sanitizedTool = McpToolUtils.format(tool.name());
      String counterKey = sanitizedConnection + ":" + sanitizedTool;
      AtomicInteger counter = counters.computeIfAbsent(counterKey, key -> new AtomicInteger(0));
      int index = counter.getAndIncrement();
      if (index == 0) {
        return sanitizedTool;
      }
      return sanitizedTool + "_" + sanitizedConnection + "_" + index;
    }

    private String extractConnectionName(McpConnectionInfo connectionInfo) {
      if (connectionInfo == null) {
        return "mcp";
      }
      if (connectionInfo.initializeResult() != null
          && connectionInfo.initializeResult().serverInfo() != null
          && StringUtils.hasText(connectionInfo.initializeResult().serverInfo().name())) {
        return connectionInfo.initializeResult().serverInfo().name();
      }
      if (connectionInfo.clientInfo() != null
          && StringUtils.hasText(connectionInfo.clientInfo().name())) {
        return connectionInfo.clientInfo().name();
      }
      return "mcp";
    }
  }
}
