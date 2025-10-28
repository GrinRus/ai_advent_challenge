package com.aiadvent.mcp.backend;

import com.aiadvent.mcp.backend.flowops.FlowOpsBackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {"com.aiadvent.mcp.backend.flowops", "com.aiadvent.mcp.backend.config"})
@EnableConfigurationProperties(FlowOpsBackendProperties.class)
public class FlowOpsMcpApplication {

  public static void main(String[] args) {
    SpringApplication.run(FlowOpsMcpApplication.class, args);
  }
}
