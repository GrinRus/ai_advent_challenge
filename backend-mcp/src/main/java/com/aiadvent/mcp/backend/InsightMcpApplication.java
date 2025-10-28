package com.aiadvent.mcp.backend;

import com.aiadvent.mcp.backend.config.InsightBackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(
    scanBasePackages = {"com.aiadvent.mcp.backend.insight", "com.aiadvent.mcp.backend.config"})
@EnableConfigurationProperties(InsightBackendProperties.class)
public class InsightMcpApplication {

  public static void main(String[] args) {
    SpringApplication.run(InsightMcpApplication.class, args);
  }
}
