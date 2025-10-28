package com.aiadvent.mcp.backend;

import com.aiadvent.mcp.backend.config.InsightBackendProperties;
import com.aiadvent.mcp.backend.config.AgentOpsBackendProperties;
import com.aiadvent.mcp.backend.config.FlowOpsBackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(scanBasePackages = "com.aiadvent.mcp.backend.config")
@Import({McpApplication.InsightConfig.class, McpApplication.FlowOpsConfig.class, McpApplication.AgentOpsConfig.class})
public class McpApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }

    @Configuration
    @Profile("insight")
    @ComponentScan(basePackages = {
            "com.aiadvent.mcp.backend.insight", "com.aiadvent.mcp.backend.config"
    })
    @EnableConfigurationProperties(InsightBackendProperties.class)
    public class InsightConfig {
    }

    @Configuration
    @Profile("flowops")
    @ComponentScan(basePackages = {
            "com.aiadvent.mcp.backend.flowops", "com.aiadvent.mcp.backend.config"
    })
    @EnableConfigurationProperties(FlowOpsBackendProperties.class)
    public class FlowOpsConfig {
    }


    @Configuration
    @Profile("agentops")
    @ComponentScan(basePackages = {
            "com.aiadvent.mcp.backend.agentops", "com.aiadvent.mcp.backend.config"
    })
    @EnableConfigurationProperties(AgentOpsBackendProperties.class)
    public class AgentOpsConfig {
    }

}
