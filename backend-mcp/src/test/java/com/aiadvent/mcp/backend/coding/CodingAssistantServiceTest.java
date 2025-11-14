package com.aiadvent.mcp.backend.coding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.aiadvent.mcp.backend.coding.PatchGenerator;
import com.aiadvent.mcp.backend.coding.PatchPlanGenerator;
import com.aiadvent.mcp.backend.docker.DockerRunnerService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingAssistantServiceTest {

  @Test
  void generatePatchRegistersAnnotations(@TempDir Path workspaceDir) {
    TestHarness harness = createHarness(workspaceDir);

    CodingAssistantService.GeneratePatchResponse response =
        harness.service.generatePatch(
            new CodingAssistantService.GeneratePatchRequest(
                "workspace-id", "Implement feature", List.of(), List.of(), List.of()));

    assertNotNull(response.patchId());
    assertEquals("workspace-id", response.workspaceId());
    assertTrue(response.diff().contains("diff --git"));
    assertFalse(response.annotations().files().isEmpty());

    CodingPatch stored =
        harness.patchRegistry.get("workspace-id", response.patchId());
    assertEquals("Implement feature", stored.instructions());
    assertEquals(response.diff(), stored.diff());
    assertEquals(response.annotations().files(), stored.annotations().files());
  }

  @Test
  void reviewPatchFlagsMissingTests(@TempDir Path workspaceDir) {
    TestHarness harness = createHarness(workspaceDir);
    CodingAssistantService.GeneratePatchResponse response =
        harness.service.generatePatch(
            new CodingAssistantService.GeneratePatchRequest(
                "workspace-id", "Add service layer", List.of(), List.of(), List.of()));

    String patchId = response.patchId();
    harness.patchRegistry.update(
        "workspace-id",
        patchId,
        patch ->
            patch.withPatchContent(
                "Add App class",
                buildAddFileDiff(
                    "src/main/java/app/App.java",
                    List.of(
                        "package app;",
                        "",
                        "public class App {",
                        "  public void run() {}",
                        "}")),
                new PatchAnnotations(
                    List.of("src/main/java/app/App.java"), List.of(), List.of()),
                PatchUsage.empty()));

    CodingAssistantService.ReviewPatchResponse review =
        harness.service.reviewPatch(
            new CodingAssistantService.ReviewPatchRequest(
                "workspace-id", patchId, List.of("risks", "tests")));

    assertEquals("warnings", review.status());
    assertTrue(
        review.findings().stream()
            .anyMatch(finding -> finding.contains("Не обнаружены изменения в тестах")));
    assertTrue(review.annotations().files().contains("src/main/java/app/App.java"));
    assertTrue(review.annotations().risks().stream().anyMatch(msg -> msg.contains("тест")));
  }

  @Test
  void applyPatchPreviewAppliesDiffAndRecordsMetrics(@TempDir Path workspaceDir) throws Exception {
    initGitRepository(workspaceDir);
    TestHarness harness = createHarness(workspaceDir);

    CodingAssistantService.GeneratePatchResponse response =
        harness.service.generatePatch(
            new CodingAssistantService.GeneratePatchRequest(
                "workspace-id", "Add entrypoint", List.of(), List.of(), List.of()));

    String patchId = response.patchId();
    harness.patchRegistry.update(
        "workspace-id",
        patchId,
        patch ->
            patch.withPatchContent(
                "Update README headline",
                buildModifyDiff("README.md", "Initial README", "Initial README updated"),
                new PatchAnnotations(List.of("README.md"), List.of(), List.of()),
                PatchUsage.empty()));

    CodingAssistantService.ApplyPatchPreviewResponse preview =
        harness.service.applyPatchPreview(
            new CodingAssistantService.ApplyPatchPreviewRequest(
                "workspace-id", patchId, List.of(), true, null));

    assertTrue(preview.applied());
    assertEquals("applied_with_warnings", preview.preview().dryRunStatus());
    assertTrue(preview.preview().warnings().isEmpty());
    List<String> modifiedFiles = preview.preview().modifiedFiles();
    assertTrue(
        modifiedFiles.stream().anyMatch(entry -> entry.contains("README.md")),
        "modified files should include README update");
    assertEquals(
        1.0,
        harness.meterRegistry.get("coding_patch_attempt_total").counter().count(),
        0.0001);
    assertEquals(
        1.0,
        harness.meterRegistry.get("coding_patch_success_total").counter().count(),
        0.0001);
    assertEquals(
        0.0,
        harness.meterRegistry.get("coding_patch_compile_fail_total").counter().count(),
        0.0001);
    assertTrue(
        harness.patchRegistry.get("workspace-id", patchId).hasDryRun(),
        "registry should record successful dry-run");
    verifyNoInteractions(harness.dockerRunnerService);
  }

  @Test
  void listPatchesReturnsSummaries(@TempDir Path workspaceDir) {
    TestHarness harness = createHarness(workspaceDir);
    CodingAssistantService.GeneratePatchResponse response =
        harness.service.generatePatch(
            new CodingAssistantService.GeneratePatchRequest(
                "workspace-id", "Add controller", List.of(), List.of(), List.of()));

    CodingAssistantService.ListPatchesResponse list =
        harness.service.listPatches(new CodingAssistantService.ListPatchesRequest("workspace-id"));

    assertEquals(1, list.patches().size());
    CodingAssistantService.PatchSummary summary = list.patches().get(0);
    assertEquals(response.patchId(), summary.patchId());
    assertEquals("workspace-id", summary.workspaceId());
    assertEquals("generated", summary.status());
    assertFalse(summary.annotations().files().isEmpty());
  }

  @Test
  void discardPatchRemovesEntry(@TempDir Path workspaceDir) {
    TestHarness harness = createHarness(workspaceDir);
    CodingAssistantService.GeneratePatchResponse response =
        harness.service.generatePatch(
            new CodingAssistantService.GeneratePatchRequest(
                "workspace-id", "Cleanup temp files", List.of(), List.of(), List.of()));

    CodingAssistantService.DiscardPatchResponse discard =
        harness.service.discardPatch(
            new CodingAssistantService.DiscardPatchRequest("workspace-id", response.patchId()));

    assertEquals(response.patchId(), discard.patchId());
    assertEquals("discarded", discard.patch().status());
    assertTrue(
        harness
            .service
            .listPatches(new CodingAssistantService.ListPatchesRequest("workspace-id"))
            .patches()
            .isEmpty());
  }

  @Test
  void applyPatchPreviewReportsConflictsWhenGitCheckFails(@TempDir Path workspaceDir)
      throws Exception {
    initGitRepository(workspaceDir);
    TestHarness harness = createHarness(workspaceDir);

    CodingAssistantService.GeneratePatchResponse response =
        harness.service.generatePatch(
            new CodingAssistantService.GeneratePatchRequest(
                "workspace-id", "Modify README", List.of(), List.of(), List.of()));

    String patchId = response.patchId();
    harness.patchRegistry.update(
        "workspace-id",
        patchId,
        patch ->
            patch.withPatchContent(
                "Modify README with stale content",
                buildModifyDiff(
                    "README.md",
                    "Non existing line",
                    "Updated line"),
                new PatchAnnotations(List.of("README.md"), List.of(), List.of()),
                PatchUsage.empty()));

    CodingAssistantService.ApplyPatchPreviewResponse preview =
        harness.service.applyPatchPreview(
            new CodingAssistantService.ApplyPatchPreviewRequest(
                "workspace-id", patchId, List.of(), true, null));

    assertFalse(preview.applied());
    assertEquals("failed", preview.preview().dryRunStatus());
    assertFalse(preview.preview().warnings().isEmpty());
    assertTrue(
        preview.annotations().conflicts().stream()
            .anyMatch(msg -> msg.contains("patch failed")),
        "conflicts should contain patch failure details");
    assertEquals(
        1.0,
        harness.meterRegistry.get("coding_patch_attempt_total").counter().count(),
        0.0001);
    assertEquals(
        0.0,
        harness.meterRegistry.get("coding_patch_success_total").counter().count(),
        0.0001);
    assertFalse(
        harness.patchRegistry.get("workspace-id", patchId).hasDryRun(),
        "dry-run flag remains false when apply fails");
  }

  private TestHarness createHarness(Path workspacePath) {
    CodingAssistantProperties properties = new CodingAssistantProperties();
    PatchRegistry patchRegistry = new PatchRegistry(properties);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    TempWorkspaceService workspaceService = mock(TempWorkspaceService.class);
    WorkspaceFileService workspaceFileService = mock(WorkspaceFileService.class);
    DockerRunnerService dockerRunnerService = mock(DockerRunnerService.class);

    when(workspaceService.findWorkspace(anyString()))
        .thenAnswer(
            invocation -> {
              String workspaceId = invocation.getArgument(0);
              return Optional.of(
                  new TempWorkspaceService.Workspace(
                      workspaceId,
                      workspacePath,
                      Instant.now(),
                      Instant.now().plus(Duration.ofHours(1)),
                      null,
                      null,
                      null,
                      0L,
                      null,
                      List.of(),
                      null,
                      null,
                      null));
            });

    PatchGenerator patchGenerator = new PatchPlanGenerator();

    CodingAssistantService service =
        new CodingAssistantService(
            workspaceService,
            properties,
            workspaceFileService,
            patchRegistry,
            patchGenerator,
            dockerRunnerService,
            meterRegistry);

    return new TestHarness(service, patchRegistry, meterRegistry, dockerRunnerService);
  }

  private static String buildAddFileDiff(String path, List<String> lines) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("diff --git a/")
        .append(path)
        .append(" b/")
        .append(path)
        .append("\n")
        .append("new file mode 100644\n")
        .append("index 0000000..1111111\n")
        .append("--- /dev/null\n")
        .append("+++ b/")
        .append(path)
        .append("\n")
        .append("@@ -0,0 +1,")
        .append(lines.size())
        .append(" @@\n");
    for (String line : lines) {
      builder.append("+").append(line).append("\n");
    }
    return builder.toString();
  }

  private static String buildModifyDiff(String path, String from, String to) {
    return "diff --git a/"
        + path
        + " b/"
        + path
        + "\n"
        + "index 1111111..2222222 100644\n"
        + "--- a/"
        + path
        + "\n"
        + "+++ b/"
        + path
        + "\n"
        + "@@ -1 +1 @@\n"
        + "-"
        + from
        + "\n"
        + "+"
        + to
        + "\n";
  }

  private static void initGitRepository(Path directory) throws Exception {
    runGit(directory, "git", "init");
    runGit(directory, "git", "config", "user.name", "Test User");
    runGit(directory, "git", "config", "user.email", "test@example.com");
    Files.createDirectories(directory.resolve("src/main/java/app"));
    Files.writeString(directory.resolve("README.md"), "Initial README\n");
    runGit(directory, "git", "add", ".");
    runGit(directory, "git", "commit", "-m", "Initial commit");
  }

  private static void runGit(Path directory, String... command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        fail("Git command timed out: " + String.join(" ", command));
      }
      if (process.exitValue() != 0) {
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        fail("Git command failed (" + String.join(" ", command) + "): " + output);
      }
    } finally {
      process.destroy();
    }
  }

  private record TestHarness(
      CodingAssistantService service,
      PatchRegistry patchRegistry,
      MeterRegistry meterRegistry,
      DockerRunnerService dockerRunnerService) {}
}
