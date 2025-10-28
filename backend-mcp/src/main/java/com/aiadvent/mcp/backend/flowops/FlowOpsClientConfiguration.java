package com.aiadvent.mcp.backend.flowops;

import com.aiadvent.mcp.backend.config.FlowOpsBackendProperties;
import com.aiadvent.mcp.backend.config.ReactorClientHttpConnectorBuilder;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class FlowOpsClientConfiguration {

  @Bean
  public WebClient flowOpsWebClient(FlowOpsBackendProperties properties) {
    WebClient.Builder builder =
        WebClient.builder()
            .baseUrl(properties.getBaseUrl())
            .defaultHeader(HttpHeaders.ACCEPT, "application/json")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .clientConnector(
                new ReactorClientHttpConnectorBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(30))
                    .build());

    if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiToken().trim());
    }

    return builder.build();
  }
}

