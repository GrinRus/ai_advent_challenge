package com.aiadvent.mcp.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.McpApplication;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.AggregateFindingsRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.AggregateFindingsResponse;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.FindingInput;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentResponse;
import com.aiadvent.mcp.backend.config.RepoAnalysisProperties;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InfrastructureFlags;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class RepoAnalysisIntegrationTest {

  @TempDir Path tempDir;

  private RepoAnalysisService service;

  @BeforeEach
  void setUp() throws IOException {
    RepoAnalysisProperties properties = new RepoAnalysisProperties();
    Path stateRoot = tempDir.resolve("state");
    Files.createDirectories(stateRoot);
    properties.setStateRoot(stateRoot.toString());
    properties.setSegmentMaxBytes(256);

    RepoAnalysisStateStore store = new RepoAnalysisStateStore(properties);
    TempWorkspaceService workspaceService = Mockito.mock(TempWorkspaceService.class);
    WorkspaceInspectorService inspectorService = Mockito.mock(WorkspaceInspectorService.class);

    Path workspacePath = tempDir.resolve("workspace/ws1");
    Files.createDirectories(workspacePath);
    Path projectPath = workspacePath.resolve("app");
    Files.createDirectories(projectPath);
    Files.writeString(projectPath.resolve("Main.java"), "class App { }" + System.lineSeparator() +
        "void test() { if(true) { System.out.println(\"hi\"); } }\n");

    TempWorkspaceService.Workspace workspace =
        new TempWorkspaceService.Workspace(
            "ws1",
            workspacePath,
            Instant.now(),
            Instant.now().plus(Duration.ofHours(1)),
            "req1",
            "owner/repo",
            "main",
            0L,
            null,
            List.of(),
            null,
            null,
            null);

    when(workspaceService.requireWorkspacePath("ws1")).thenReturn(workspacePath);
    when(workspaceService.findWorkspace("ws1")).thenReturn(Optional.of(workspace));

    InspectWorkspaceResult inspectorResult =
        new InspectWorkspaceResult(
            "ws1",
            workspacePath,
            List.of(
                new WorkspaceItem(
                    "app",
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
            Duration.ofMillis(5),
            Instant.now(),
            InfrastructureFlags.EMPTY,
            List.of("npm"),
            List.of("code"));

    when(inspectorService.inspectWorkspace(Mockito.any())).thenReturn(inspectorResult);

    service =
        new RepoAnalysisService(
            properties,
            store,
            workspaceService,
            inspectorService,
            new SimpleMeterRegistry());
  }

  @Test
  void fullAnalysisFlowGeneratesSummaryAndReport() {
    ScanNextSegmentRequest request =
        new ScanNextSegmentRequest("analysis1", "ws1", "app", false, null, null);

    ScanNextSegmentResponse segment = service.scanNextSegment(request);
    assertThat(segment.completed()).isFalse();
    assertThat(segment.segment()).isNotNull();

    AggregateFindingsResponse aggregateResponse =
        service.aggregateFindings(
            new AggregateFindingsRequest(
                "analysis1",
                "ws1",
                List.of(
                    new FindingInput(
                        "app/Main.java",
                        1,
                        1,
                        "test",
                        "Potential issue",
                        "HIGH",
                        List.of("performance"),
                        1.0))));

    assertThat(aggregateResponse.totalFindings()).isEqualTo(1);

    ScanNextSegmentResponse completion = service.scanNextSegment(request);
    assertThat(completion.completed()).isTrue();
    assertThat(completion.summary()).isNotNull();
    assertThat(completion.summary().reportJsonPath()).isNotBlank();
    assertThat(completion.summary().totalFindings()).isEqualTo(1);
  }
}
