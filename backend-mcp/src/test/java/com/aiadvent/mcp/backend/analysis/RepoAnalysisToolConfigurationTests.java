package com.aiadvent.mcp.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.McpApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = McpApplication.class)
@ActiveProfiles("analysis")
class RepoAnalysisToolConfigurationTests {

  private static final Path WORKSPACE_ROOT = createTempDir("repo-analysis-workspace-");
  private static final Path STATE_ROOT = createTempDir("repo-analysis-state-");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("github.backend.workspace-root", () -> WORKSPACE_ROOT.toString());
    registry.add("repo.analysis.state-root", () -> STATE_ROOT.toString());
  }

  @Autowired
  @Qualifier("repoAnalysisToolCallbackProvider")
  private ToolCallbackProvider provider;

  @Test
  void repoAnalysisToolsExposedViaCallbackProvider() {
    Set<String> toolNames =
        Arrays.stream(provider.getToolCallbacks())
            .map(ToolCallback::getToolDefinition)
            .map(toolDefinition -> toolDefinition.name())
            .collect(Collectors.toSet());

    assertThat(toolNames)
        .containsExactlyInAnyOrder(
            "repo_analysis.scan_next_segment",
            "repo_analysis.aggregate_findings",
            "repo_analysis.list_hotspots");
  }

  @AfterAll
  static void cleanup() throws IOException {
    deleteRecursively(WORKSPACE_ROOT);
    deleteRecursively(STATE_ROOT);
  }

  private static Path createTempDir(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to create temp directory", ex);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignore) {
                  // ignore cleanup issues in test temp directories
                }
              });
    }
  }
}
