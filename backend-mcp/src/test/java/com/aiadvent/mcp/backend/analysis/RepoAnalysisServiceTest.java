package com.aiadvent.mcp.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentResponse;
import com.aiadvent.mcp.backend.config.RepoAnalysisProperties;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InfrastructureFlags;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceRequest;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceResult;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.WorkspaceItem;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.WorkspaceItemType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

class RepoAnalysisServiceTest {

  @TempDir Path tempDir;

  private RepoAnalysisService service;
  private RepoAnalysisProperties properties;
  private RepoAnalysisStateStore stateStore;
  private TempWorkspaceService workspaceService;
  private WorkspaceInspectorService inspectorService;
  private Path workspacePath;

  @BeforeEach
  void setUp() throws IOException {
    properties = new RepoAnalysisProperties();
    Path stateRoot = tempDir.resolve("state");
    Files.createDirectories(stateRoot);
    properties.setStateRoot(stateRoot.toString());
    properties.setSegmentMaxBytes(1024);
    stateStore = new RepoAnalysisStateStore(properties);

    workspaceService = mock(TempWorkspaceService.class);
    inspectorService = mock(WorkspaceInspectorService.class);

    workspacePath = tempDir.resolve("workspace/ws1");
    Files.createDirectories(workspacePath);
    Path projectPath = workspacePath.resolve("src/main");
    Files.createDirectories(projectPath);
    Files.writeString(projectPath.resolve("App.java"), "class App { void run() {} }");

    when(workspaceService.requireWorkspacePath("ws1")).thenReturn(workspacePath);

    InspectWorkspaceResult inspectorResult =
        new InspectWorkspaceResult(
            "ws1",
            workspacePath,
            List.of(
                new WorkspaceItem(
                    "src/main",
                    WorkspaceItemType.DIRECTORY,
                    0,
                    false,
                    false,
                    false,
                    Instant.now(),
                    List.of("code"),
                    false,
                    List.of("npm"),
                    InfrastructureFlags.EMPTY)),
            false,
            List.of(),
            false,
            null,
            1,
            Duration.ofMillis(10),
            Instant.now(),
            InfrastructureFlags.EMPTY,
            List.of("npm"),
            List.of("code"));

    when(inspectorService.inspectWorkspace(ArgumentMatchers.any(InspectWorkspaceRequest.class)))
        .thenReturn(inspectorResult);

    service =
        new RepoAnalysisService(
            properties,
            stateStore,
            workspaceService,
            inspectorService,
            new SimpleMeterRegistry());
  }

  @Test
  void generatesFinalReportAndSummary() {
    ScanNextSegmentRequest request =
        new ScanNextSegmentRequest("a1", "ws1", null, false, null, null);

    ScanNextSegmentResponse first = service.scanNextSegment(request);
    assertThat(first.completed()).isFalse();
    assertThat(first.summary()).isNull();

    ScanNextSegmentResponse second = service.scanNextSegment(request);
    assertThat(second.completed()).isTrue();
    assertThat(second.summary()).isNotNull();
    assertThat(second.summary().reportJsonPath()).isNotBlank();

    Path jsonReport = Path.of(second.summary().reportJsonPath());
    Path mdReport = Path.of(second.summary().reportMarkdownPath());
    assertThat(Files.exists(jsonReport)).isTrue();
    assertThat(Files.exists(mdReport)).isTrue();
    assertThat(second.summary().totalFindings()).isZero();
  }
}
