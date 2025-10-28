package com.aiadvent.mcp.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentops.backend")
public class AgentOpsBackendProperties {

  /**
   * Base URL of the existing backend REST API (e.g. http://localhost:8080).
   */
  private String baseUrl = "http://localhost:8080";

  /**
   * Optional API token that will be forwarded as Bearer auth header.
   */
  private String apiToken;

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getApiToken() {
    return apiToken;
  }

  public void setApiToken(String apiToken) {
    this.apiToken = apiToken;
  }
}

