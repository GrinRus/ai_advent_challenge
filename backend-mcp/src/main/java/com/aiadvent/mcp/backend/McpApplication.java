package com.aiadvent.mcp.backend;

import com.aiadvent.mcp.backend.config.InsightBackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@SpringBootApplication
@Import({McpApplication.InsightConfig.class, DevScanConfig.class, ProdScanConfig.class})
public class McpApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }

    @Configuration
    @Profile("insight")
    @ComponentScan(basePackages = {
            "com.aiadvent.backend.mcp.insight", "com.aiadvent.backend.mcp.config"
    })
    @EnableConfigurationProperties(InsightBackendProperties.class)
    public class InsightConfig {
    }

    @Configuration
    @Profile("flowops")
    @ComponentScan(basePackages = {
            "com.aiadvent.backend.mcp.flowops", "com.aiadvent.backend.mcp.config"
    })
    @EnableConfigurationProperties(FlowOpsBackendProperties.class)
    public class FlowOpsConfig {
    }


    @Configuration
    @Profile("agentops")
    @ComponentScan(basePackages = {
            "com.aiadvent.backend.mcp.agentops", "com.aiadvent.backend.mcp.config"
    })
    @EnableConfigurationProperties(AgentOpsBackendProperties.class)
    public class AgentOpsConfig {
    }

}
