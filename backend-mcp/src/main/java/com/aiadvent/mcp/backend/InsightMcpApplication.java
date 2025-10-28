package com.aiadvent.mcp.backend;

import com.aiadvent.mcp.backend.config.InsightBackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(
    scanBasePackages = {"com.aiadvent.backend.mcp.insight", "com.aiadvent.backend.mcp.config"})
@EnableConfigurationProperties(InsightBackendProperties.class)
public class InsightMcpApplication {

  public static void main(String[] args) {
    SpringApplication.run(InsightMcpApplication.class, args);
  }
}
