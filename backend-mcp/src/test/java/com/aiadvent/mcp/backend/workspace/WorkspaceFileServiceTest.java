package com.aiadvent.mcp.backend.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class WorkspaceFileServiceTest {

  private WorkspaceFileService service;
  private TempWorkspaceService workspaceService;

  @TempDir Path workspaceDir;

  @BeforeEach
  void setUp() {
    workspaceService = Mockito.mock(TempWorkspaceService.class);
    service = new WorkspaceFileService(workspaceService);
    Instant now = Instant.now();
    TempWorkspaceService.Workspace workspace =
        new TempWorkspaceService.Workspace(
            "ws",
            workspaceDir,
            now,
            now.plusSeconds(60),
            "req",
            "repo/name",
            "main",
            0L,
            null,
            List.of(),
            null,
            null,
            null);
    Mockito.when(workspaceService.findWorkspace(Mockito.eq("ws")))
        .thenReturn(Optional.of(workspace));
  }

  @Test
  void writeFileCreatesAndOverwrites() throws IOException {
    WorkspaceFileService.FileWriteResult createResult =
        service.writeTextFile("ws", "src/Example.txt", "hello", WorkspaceFileService.WriteMode.CREATE, null, 2048);
    Path target = workspaceDir.resolve("src/Example.txt");
    assertTrue(Files.exists(target));
    assertTrue(createResult.created());
    assertEquals("hello", Files.readString(target));

    WorkspaceFileService.FileWriteResult overwriteResult =
        service.writeTextFile(
            "ws", "src/Example.txt", "updated", WorkspaceFileService.WriteMode.OVERWRITE, null, 2048);
    assertFalse(overwriteResult.created());
    assertEquals("updated", Files.readString(target));
  }

  @Test
  void insertBeforeMarker() throws IOException {
    Path file = workspaceDir.resolve("docs/readme.md");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "one\ntwo\nthree\n");

    WorkspaceFileService.FileWriteResult insertResult =
        service.writeTextFile(
            "ws",
            "docs/readme.md",
            "INSERT\n",
            WorkspaceFileService.WriteMode.INSERT,
            "three",
            2048);
    assertFalse(insertResult.created());
    String content = Files.readString(file);
    assertTrue(content.contains("INSERT\nthree"));
  }
}
