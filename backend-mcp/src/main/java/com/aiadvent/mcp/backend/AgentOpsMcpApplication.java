package com.aiadvent.mcp.backend;

import com.aiadvent.mcp.backend.config.AgentOpsBackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {"com.aiadvent.backend.mcp.agentops", "com.aiadvent.backend.mcp.config"})
@EnableConfigurationProperties(AgentOpsBackendProperties.class)
public class AgentOpsMcpApplication {

  public static void main(String[] args) {
    SpringApplication.run(AgentOpsMcpApplication.class, args);
  }
}
