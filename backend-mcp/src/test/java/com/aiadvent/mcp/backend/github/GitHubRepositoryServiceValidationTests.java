package com.aiadvent.mcp.backend.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitFileChange;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GitHubRepositoryServiceValidationTests {

  private GitHubRepositoryService service;

  @BeforeEach
  void setUp() {
    GitHubClientExecutor executor = mock(GitHubClientExecutor.class, Mockito.RETURNS_DEEP_STUBS);
    GitHubBackendProperties properties = new GitHubBackendProperties();
    TempWorkspaceService workspaceService = mock(TempWorkspaceService.class);
    GitHubTokenManager tokenManager = mock(GitHubTokenManager.class);
    service = new GitHubRepositoryService(executor, properties, workspaceService, tokenManager, null);
  }

  @Test
  void validateBranchName_acceptsValidReference() throws Exception {
    String result = invoke("validateBranchName", String.class, "feature/new-feature");
    assertThat(result).isEqualTo("feature/new-feature");
  }

  @Test
  void validateBranchName_rejectsDotsAndSpaces() {
    assertThatThrownBy(() -> invoke("validateBranchName", String.class, "feature..invalid"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not contain '..'");
    assertThatThrownBy(() -> invoke("validateBranchName", String.class, "feature branch"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden characters");
  }

  @Test
  void validateBranchName_rejectsEmptySegment() {
    assertThatThrownBy(() -> invoke("validateBranchName", String.class, "feature//test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty path components");
  }

  @Test
  void sanitizeCommitMessage_trimsAndReturnsValue() throws Exception {
    String result =
        invoke("sanitizeCommitMessage", String.class, "  chore: update documentation  ");
    assertThat(result).isEqualTo("chore: update documentation");
  }

  @Test
  void sanitizeCommitMessage_rejectsBlankOrOversized() {
    assertThatThrownBy(() -> invoke("sanitizeCommitMessage", String.class, "   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
    String oversized = "x".repeat(2001);
    assertThatThrownBy(() -> invoke("sanitizeCommitMessage", String.class, oversized))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds 2000 characters");
  }

  @Test
  void ensureDiffWithinLimits_acceptsDiffWithinLimit() throws Exception {
    RepositoryRef ref = new RepositoryRef("acme", "demo", "heads/main");
    invoke(
        "ensureDiffWithinLimits",
        new Class<?>[] {RepositoryRef.class, String.class, String.class},
        ref,
        "feature/demo",
        "diff --git a/file.txt b/file.txt\n+hello\n");
  }

  @Test
  void ensureDiffWithinLimits_rejectsOversizedDiff() {
    RepositoryRef ref = new RepositoryRef("acme", "demo", "heads/main");
    byte[] payload = new byte[2 * 1024 * 1024];
    String diff = new String(payload, StandardCharsets.UTF_8);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invoke(
                "ensureDiffWithinLimits",
                new Class<?>[] {RepositoryRef.class, String.class, String.class},
                ref,
                "feature/demo",
                diff));
  }

  @Test
  void parseCommitFiles_handlesRenames() throws Exception {
    String output = "M\tREADME.md\nR100\told.java\tnew.java\n";
    List<CommitFileChange> changes =
        invoke("parseCommitFiles", String.class, output);
    assertThat(changes)
        .extracting(CommitFileChange::status, CommitFileChange::path, CommitFileChange::previousPath)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple("M", "README.md", null),
            org.assertj.core.api.Assertions.tuple("R100", "new.java", "old.java"));
  }

  @Test
  void summarizeNumstat_calculatesTotals() throws Exception {
    String numstat = "10\t2\tsrc/Main.java\n0\t5\tdocs/README.md\n";
    Object stats = invoke("summarizeNumstat", String.class, numstat);
    int filesChanged = (int) stats.getClass().getMethod("filesChanged").invoke(stats);
    int additions = (int) stats.getClass().getMethod("additions").invoke(stats);
    int deletions = (int) stats.getClass().getMethod("deletions").invoke(stats);

    assertThat(filesChanged).isEqualTo(2);
    assertThat(additions).isEqualTo(10);
    assertThat(deletions).isEqualTo(7);
  }

  @SuppressWarnings("unchecked")
  private <T> T invoke(String methodName, Class<?> parameterType, Object argument)
      throws Exception {
    return (T) invoke(methodName, new Class<?>[] {parameterType}, argument);
  }

  @SuppressWarnings("unchecked")
  private <T> T invoke(String methodName, Class<?>[] parameterTypes, Object... args)
      throws Exception {
    Method method = GitHubRepositoryService.class.getDeclaredMethod(methodName, parameterTypes);
    method.setAccessible(true);
    try {
      return (T) method.invoke(service, args);
    } catch (InvocationTargetException ex) {
      if (ex.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw ex;
    }
  }
}
