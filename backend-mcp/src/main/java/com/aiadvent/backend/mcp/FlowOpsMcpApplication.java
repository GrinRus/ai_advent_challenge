package com.aiadvent.backend.mcp;

import com.aiadvent.backend.mcp.flowops.FlowOpsBackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {"com.aiadvent.backend.mcp.flowops", "com.aiadvent.backend.mcp.config"})
@EnableConfigurationProperties(FlowOpsBackendProperties.class)
public class FlowOpsMcpApplication {

  public static void main(String[] args) {
    SpringApplication.run(FlowOpsMcpApplication.class, args);
  }
}
