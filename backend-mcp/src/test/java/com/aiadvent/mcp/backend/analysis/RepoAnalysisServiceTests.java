package com.aiadvent.mcp.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.AggregateFindingsRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.FindingInput;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ListHotspotsRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanConfigOverrides;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentResponse;
import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.aiadvent.mcp.backend.config.RepoAnalysisProperties;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.CreateWorkspaceRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepoAnalysisServiceTests {

  private Path workspaceRoot;
  private Path stateRoot;
  private TempWorkspaceService workspaceService;
  private RepoAnalysisService service;

  @BeforeEach
  void setUp() throws Exception {
    workspaceRoot = Files.createTempDirectory("analysis-workspace-");
    stateRoot = Files.createTempDirectory("analysis-state-");

    GitHubBackendProperties gitProperties = new GitHubBackendProperties();
    gitProperties.setWorkspaceRoot(workspaceRoot.toString());
    gitProperties.setWorkspaceTtl(Duration.ofHours(1));
    gitProperties.setWorkspaceCleanupInterval(Duration.ofHours(1));

    workspaceService = new TempWorkspaceService(gitProperties, null);
    workspaceService.afterPropertiesSet();

    RepoAnalysisProperties repoProperties = new RepoAnalysisProperties();
    repoProperties.setStateRoot(stateRoot.toString());
    repoProperties.setSegmentMaxBytes(2048);

    RepoAnalysisStateStore stateStore = new RepoAnalysisStateStore(repoProperties);
    service = new RepoAnalysisService(repoProperties, stateStore, workspaceService);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (workspaceService != null) {
      workspaceService.destroy();
    }
    deleteRecursively(stateRoot);
    deleteRecursively(workspaceRoot);
  }

  @Test
  void scanAggregateAndHotspotsWorkflow() throws Exception {
    var workspace =
        workspaceService.createWorkspace(new CreateWorkspaceRequest("demo/repo", "main", "test"));
    Path root = workspace.path();

    Path javaDir = root.resolve("src/main/java/com/example");
    Files.createDirectories(javaDir);
    Files.writeString(javaDir.resolve("App.java"), sampleJavaContent());
    Files.writeString(root.resolve("README.md"), "# Demo\nUseful documentation\n");
    Files.createDirectories(root.resolve("build"));
    Files.writeString(root.resolve("build/generated.txt"), "ignored");
    Files.writeString(root.resolve(".mcpignore"), "build/\n");

    ScanConfigOverrides overrides =
        new ScanConfigOverrides(null, null, null, null, List.of("java"), null, null);
    ScanNextSegmentRequest firstScan =
        new ScanNextSegmentRequest(
            "analysis-1", workspace.workspaceId(), "", false, null, overrides);
    var firstResponse = service.scanNextSegment(firstScan);
    assertThat(firstResponse.completed()).isFalse();
    assertThat(firstResponse.segment()).isNotNull();
    assertThat(firstResponse.segment().path()).isEqualTo("src/main/java/com/example/App.java");
    assertThat(firstResponse.segment().truncated()).isTrue();
    assertThat(firstResponse.skippedFiles()).contains("README.md");

    int segmentCount = firstResponse.segment() != null ? 1 : 0;
    ScanNextSegmentResponse cursor = firstResponse;
    while (!cursor.completed()) {
      cursor = service.scanNextSegment(firstScan);
      if (cursor.segment() != null) {
        segmentCount++;
      }
    }
    assertThat(segmentCount).isGreaterThan(1);
    assertThat(cursor.segment()).isNull();
    assertThat(cursor.completed()).isTrue();

    AggregateFindingsRequest aggregateRequest =
        new AggregateFindingsRequest(
            "analysis-1",
            workspace.workspaceId(),
            List.of(
                new FindingInput(
                    "src/main/java/com/example/App.java",
                    12,
                    null,
                    "Null-safety",
                    "Проверить Optional перед использованием",
                    "HIGH",
                    List.of("bug"),
                    0.9)));
    var aggregateResponse = service.aggregateFindings(aggregateRequest);
    assertThat(aggregateResponse.totalFindings()).isEqualTo(1);
    assertThat(aggregateResponse.files()).hasSize(1);
    assertThat(aggregateResponse.files().get(0).path())
        .isEqualTo("src/main/java/com/example/App.java");

    var hotspots =
        service.listHotspots(new ListHotspotsRequest("analysis-1", workspace.workspaceId(), 5, true));
    assertThat(hotspots.hotspots()).hasSize(1);
    assertThat(hotspots.hotspots().get(0).findingCount()).isEqualTo(1);
  }

  @Test
  void scanHonorsMaxBytesLimit() throws Exception {
    var workspace =
        workspaceService.createWorkspace(new CreateWorkspaceRequest("demo/repo", "main", "limit"));
    Path root = workspace.path();

    Path srcDir = root.resolve("src/main/java/com/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Large.java"), sampleJavaContent());

    ScanConfigOverrides overrides =
        new ScanConfigOverrides(null, null, 256L, null, List.of("java"), null, null);
    ScanNextSegmentRequest scanRequest =
        new ScanNextSegmentRequest(
            "analysis-limit", workspace.workspaceId(), "", false, 128L, overrides);

    ScanNextSegmentResponse first = service.scanNextSegment(scanRequest);
    assertThat(first.completed()).isFalse();
    assertThat(first.segment()).isNotNull();
    assertThat(first.segment().truncated()).isTrue();
    assertThat(first.segment().bytes()).isLessThanOrEqualTo(128);

    ScanNextSegmentResponse second = service.scanNextSegment(scanRequest);
    assertThat(second.completed()).isFalse();
    assertThat(second.segment()).isNotNull();
    assertThat(second.segment().segmentIndex()).isEqualTo(2);
    assertThat(second.segment().startLine()).isGreaterThan(first.segment().endLine());

    ScanNextSegmentResponse cursor = second;
    int segments = 2;
    while (!cursor.completed()) {
      cursor = service.scanNextSegment(scanRequest);
      if (cursor.segment() != null) {
        segments++;
        assertThat(cursor.segment().bytes()).isLessThanOrEqualTo(128);
      }
    }

    assertThat(segments).isGreaterThan(2);
    assertThat(cursor.segment()).isNull();
    assertThat(cursor.completed()).isTrue();
  }

  @Test
  void scanResetRebuildsState() throws Exception {
    var workspace =
        workspaceService.createWorkspace(new CreateWorkspaceRequest("demo/repo", "main", "reset"));
    Path root = workspace.path();

    Path srcDir = root.resolve("src/main/java/com/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Reset.java"), sampleJavaContent());

    ScanConfigOverrides overrides =
        new ScanConfigOverrides(null, null, 256L, null, List.of("java"), null, null);
    ScanNextSegmentRequest scanRequest =
        new ScanNextSegmentRequest(
            "analysis-reset", workspace.workspaceId(), "", false, null, overrides);

    ScanNextSegmentResponse first = service.scanNextSegment(scanRequest);
    assertThat(first.segment()).isNotNull();
    assertThat(first.segment().segmentIndex()).isEqualTo(1);

    ScanNextSegmentResponse second = service.scanNextSegment(scanRequest);
    assertThat(second.segment()).isNotNull();
    assertThat(second.segment().segmentIndex()).isGreaterThan(1);

    ScanNextSegmentRequest resetRequest =
        new ScanNextSegmentRequest(
            "analysis-reset", workspace.workspaceId(), "", true, null, overrides);
    ScanNextSegmentResponse reset = service.scanNextSegment(resetRequest);

    assertThat(reset.segment()).isNotNull();
    assertThat(reset.segment().segmentIndex()).isEqualTo(1);
    assertThat(reset.segment().path()).isEqualTo(first.segment().path());
    assertThat(reset.processedSegments()).isEqualTo(1);
    assertThat(reset.skippedFiles()).containsExactlyElementsOf(first.skippedFiles());
  }

  private String sampleJavaContent() {
    StringBuilder builder = new StringBuilder();
    builder.append("package com.example;\n\n");
    builder.append("public class App {\n");
    for (int i = 0; i < 200; i++) {
      builder.append("  public void method" + i + "() {\n");
      builder.append("    System.out.println(\"line " + i + " - some descriptive payload to inflate size\");\n");
      builder.append("  }\n");
    }
    builder.append("}\n");
    return builder.toString();
  }

  private void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root).sorted(Comparator.reverseOrder())) {
      paths.forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
      });
    }
  }
}
