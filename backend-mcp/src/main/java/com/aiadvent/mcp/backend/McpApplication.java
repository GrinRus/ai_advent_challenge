package com.aiadvent.mcp.backend;

import com.aiadvent.mcp.backend.config.AgentOpsBackendProperties;
import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.aiadvent.mcp.backend.config.InsightBackendProperties;
import com.aiadvent.mcp.backend.config.FlowOpsBackendProperties;
import com.aiadvent.mcp.backend.config.DockerRunnerProperties;
import com.aiadvent.mcp.backend.config.RepoAnalysisProperties;
import com.aiadvent.mcp.backend.config.NotesBackendProperties;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(scanBasePackages = "com.aiadvent.mcp.backend.config")
@Import({
  McpApplication.InsightConfig.class,
  McpApplication.FlowOpsConfig.class,
  McpApplication.AgentOpsConfig.class,
  McpApplication.GitHubConfig.class,
  McpApplication.DockerRunnerConfig.class,
  McpApplication.RepoAnalysisConfig.class,
  McpApplication.NotesConfig.class
})
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

    @Configuration
    @Profile("github")
    @ComponentScan(basePackages = {
            "com.aiadvent.mcp.backend.github", "com.aiadvent.mcp.backend.config"
    })
    @EnableConfigurationProperties(GitHubBackendProperties.class)
    public class GitHubConfig {
    }

    @Configuration
    @Profile("docker")
    @ComponentScan(basePackages = {
            "com.aiadvent.mcp.backend.docker",
            "com.aiadvent.mcp.backend.config"
    })
    @EnableConfigurationProperties({DockerRunnerProperties.class, GitHubBackendProperties.class})
    @Import(TempWorkspaceService.class)
    public class DockerRunnerConfig {
    }

    @Configuration
    @Profile("analysis")
    @ComponentScan(basePackages = {
            "com.aiadvent.mcp.backend.analysis",
            "com.aiadvent.mcp.backend.config"
    })
    @EnableConfigurationProperties({RepoAnalysisProperties.class, GitHubBackendProperties.class})
    @Import(TempWorkspaceService.class)
    public class RepoAnalysisConfig {
    }

    @Configuration
    @Profile("notes")
    @ComponentScan(basePackages = {
            "com.aiadvent.mcp.backend.notes",
            "com.aiadvent.mcp.backend.config"
    })
    @EnableConfigurationProperties(NotesBackendProperties.class)
    public class NotesConfig {
    }

}
