package com.aiadvent.backend.mcp.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mcp.catalog")
public class McpCatalogProperties {

  private final Map<String, ServerProperties> servers = new LinkedHashMap<>();

  public Map<String, ServerProperties> getServers() {
    return servers;
  }

  public static class ServerProperties {

    private String displayName;
    private String description;
    private List<String> tags = List.of();
    private String securityPolicy;

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(List<String> tags) {
      this.tags = tags != null ? List.copyOf(tags) : List.of();
    }

    public String getSecurityPolicy() {
      return securityPolicy;
    }

    public void setSecurityPolicy(String securityPolicy) {
      this.securityPolicy = securityPolicy;
    }
  }
}
