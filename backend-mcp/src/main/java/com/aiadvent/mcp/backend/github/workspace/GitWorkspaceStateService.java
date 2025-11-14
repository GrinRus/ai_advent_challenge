package com.aiadvent.mcp.backend.github.workspace;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.Workspace;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GitWorkspaceStateService {

  private static final Logger log = LoggerFactory.getLogger(GitWorkspaceStateService.class);
  private static final int DEFAULT_MAX_ENTRIES = 200;
  private static final long DEFAULT_MAX_BYTES = 64 * 1024L;
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

  private final TempWorkspaceService workspaceService;
  private final GitHubBackendProperties properties;
  private final MeterRegistry meterRegistry;
  private final Timer timer;
  private final Counter successCounter;
  private final Counter failureCounter;

  public GitWorkspaceStateService(
      TempWorkspaceService workspaceService,
      GitHubBackendProperties properties,
      @Nullable MeterRegistry meterRegistry) {
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    this.properties = Objects.requireNonNull(properties, "properties");
    MeterRegistry registry = meterRegistry;
    if (registry == null) {
      registry = new SimpleMeterRegistry();
    }
    this.meterRegistry = registry;
    this.timer = this.meterRegistry.timer("github_workspace_git_state_duration");
    this.successCounter = this.meterRegistry.counter("github_workspace_git_state_success_total");
    this.failureCounter = this.meterRegistry.counter("github_workspace_git_state_failure_total");
  }

  public WorkspaceGitStateResult inspect(WorkspaceGitStateRequest request) {
    Objects.requireNonNull(request, "request");
    String workspaceId = Optional.ofNullable(request.workspaceId()).map(String::trim).orElse("");
    if (workspaceId.isEmpty()) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    Workspace workspace =
        workspaceService
            .findWorkspace(workspaceId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));
    Path gitDir = workspace.path().resolve(".git");
    if (!Files.isDirectory(gitDir)) {
      throw new WorkspaceGitStateException(
          "Workspace "
              + workspaceId
              + " is missing .git metadata. Re-run github.repository_fetch with clone fallback and create a branch.");
    }

    boolean summaryOnly = Boolean.TRUE.equals(request.includeSummaryOnly());
    boolean includeFileStatus =
        summaryOnly ? false : !Boolean.FALSE.equals(request.includeFileStatus());
    boolean includeUntracked =
        summaryOnly ? false : !Boolean.FALSE.equals(request.includeUntracked());
    int maxEntries = clampMaxEntries(request.maxEntries());
    long maxBytes =
        Optional.ofNullable(properties.getWorkspaceGitStateMaxBytes())
            .filter(limit -> limit > 0)
            .orElse(DEFAULT_MAX_BYTES);
    Duration timeout =
        Optional.ofNullable(properties.getWorkspaceGitStateTimeout())
            .filter(t -> !t.isNegative() && !t.isZero())
            .orElse(DEFAULT_TIMEOUT);

    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("status");
    command.add("--porcelain=v2");
    command.add("--branch");
    command.add(includeUntracked ? "--untracked-files=normal" : "--untracked-files=no");

    Instant inspectedAt = Instant.now();
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      ProcessResult result = runGit(workspace.path(), timeout, command);
      long outputBytes = result.output().getBytes(StandardCharsets.UTF_8).length;
      boolean truncatedByBytes = outputBytes > maxBytes;
      GitStatusSnapshot snapshot =
          parseStatus(
              result.output(),
              workspace,
              includeFileStatus,
              maxEntries,
              summaryOnly,
              truncatedByBytes);
      Duration duration = Duration.between(inspectedAt, Instant.now());
      sample.stop(timer);
      successCounter.increment();
      log.info(
          "github_workspace_git_state_completed workspaceId={} clean={} files={} truncated={}",
          workspaceId,
          snapshot.status().clean(),
          snapshot.files().size(),
          snapshot.truncated());
      return new WorkspaceGitStateResult(
          workspaceId,
          snapshot.branch(),
          snapshot.status(),
          snapshot.files(),
          snapshot.truncated(),
          snapshot.warnings(),
          inspectedAt,
          duration.toMillis());
    } catch (IOException | InterruptedException ex) {
      failureCounter.increment();
      throw new WorkspaceGitStateException("Failed to read git status: " + ex.getMessage(), ex);
    } catch (WorkspaceGitStateException ex) {
      failureCounter.increment();
      sample.stop(timer);
      throw ex;
    }
  }

  private GitStatusSnapshot parseStatus(
      String output,
      Workspace workspace,
      boolean includeFiles,
      int maxEntries,
      boolean summaryOnly,
      boolean truncatedByBytes) {
    BranchStateBuilder branchBuilder =
        new BranchStateBuilder(
            workspace.branchName(), workspace.commitSha(), workspace.resolvedRef(), workspace.upstreamBranch());
    StatusAccumulator accumulator = new StatusAccumulator();
    List<FileStatusEntry> files = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    boolean truncated = truncatedByBytes;

    try (BufferedReader reader = new BufferedReader(new StringReader(output))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("# ")) {
          branchBuilder.consumeMetadata(line.substring(2));
          continue;
        }
        if (!includeFiles) {
          continue;
        }
        if (summaryOnly) {
          continue;
        }
        if (line.isBlank()) {
          continue;
        }
        char indicator = line.charAt(0);
        switch (indicator) {
          case '1':
          case '2':
            FileStatusEntry tracked = parseTrackedEntry(line, indicator, accumulator);
            if (tracked != null) {
              if (files.size() < maxEntries) {
                files.add(tracked);
              } else {
                truncated = true;
              }
            }
            break;
          case 'u':
            accumulator.conflicts++;
            FileStatusEntry conflict = parseConflictEntry(line);
            if (conflict != null) {
              if (files.size() < maxEntries) {
                files.add(conflict);
              } else {
                truncated = true;
              }
            }
            break;
          case '?':
            accumulator.untracked++;
            if (files.size() < maxEntries) {
              files.add(
                  new FileStatusEntry(
                      safePath(line.substring(2)),
                      null,
                      "untracked",
                      false,
                      true,
                      false));
            } else {
              truncated = true;
            }
            break;
          default:
            break;
        }
      }
    } catch (IOException ex) {
      throw new WorkspaceGitStateException("Unable to parse git status output", ex);
    }

    if (truncated && warnings.isEmpty()) {
      warnings.add("Result truncated due to entry or size limits.");
    }
    return new GitStatusSnapshot(
        branchBuilder.build(),
        accumulator.toSummary(),
        List.copyOf(files),
        truncated,
        List.copyOf(warnings));
  }

  private FileStatusEntry parseTrackedEntry(
      String line, char indicator, StatusAccumulator accumulator) {
    if (line.length() < 4) {
      return null;
    }
    char stagedCode = line.charAt(2);
    char worktreeCode = line.charAt(3);
    boolean staged = stagedCode != '.';
    boolean unstaged = worktreeCode != '.';
    if (staged) {
      accumulator.staged++;
    }
    if (unstaged) {
      accumulator.unstaged++;
    }
    int tabIndex = line.indexOf('\t');
    if (tabIndex < 0) {
      return null;
    }
    String pathSpec = line.substring(tabIndex + 1);
    String path = safePath(pathSpec);
    String previousPath = null;
    if (indicator == '2' && pathSpec.indexOf('\0') >= 0) {
      String[] renameParts = pathSpec.split("\0");
      if (renameParts.length >= 2) {
        previousPath = safePath(renameParts[0]);
        path = safePath(renameParts[renameParts.length - 1]);
      }
    }
    String changeType = resolveChangeType(stagedCode, worktreeCode, indicator);
    return new FileStatusEntry(path, previousPath, changeType, staged, unstaged, true);
  }

  private FileStatusEntry parseConflictEntry(String line) {
    int tabIndex = line.indexOf('\t');
    String path = tabIndex >= 0 ? safePath(line.substring(tabIndex + 1)) : line;
    return new FileStatusEntry(path, null, "unmerged", true, true, true);
  }

  private String resolveChangeType(char stagedCode, char worktreeCode, char indicator) {
    char effective = stagedCode != '.' ? stagedCode : worktreeCode;
    switch (effective) {
      case 'M':
        return "modified";
      case 'A':
        return "added";
      case 'D':
        return "deleted";
      case 'R':
        return "renamed";
      case 'C':
        return "copied";
      case 'U':
        return "unmerged";
      default:
        return indicator == '?' ? "untracked" : "unknown";
    }
  }

  private ProcessResult runGit(Path workspacePath, Duration timeout, List<String> command)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workspacePath.toFile());
    builder.redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().putIfAbsent("LC_ALL", "C");
    Process process = builder.start();
    boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new WorkspaceGitStateException(
          "git status timed out after " + timeout.toSeconds() + " seconds");
    }
    String stdout;
    try (var in = process.getInputStream()) {
      stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (process.exitValue() != 0) {
      throw new WorkspaceGitStateException("git status failed: " + stdout.trim());
    }
    return new ProcessResult(process.exitValue(), stdout);
  }

  private int clampMaxEntries(@Nullable Integer requested) {
    int defaultLimit =
        Optional.ofNullable(properties.getWorkspaceGitStateMaxEntries())
            .filter(value -> value > 0)
            .orElse(DEFAULT_MAX_ENTRIES);
    if (requested == null || requested <= 0) {
      return defaultLimit;
    }
    return Math.min(requested, defaultLimit);
  }

  private String safePath(String candidate) {
    if (candidate == null) {
      return "";
    }
    return candidate.replace('\0', '/');
  }

  private static final class BranchStateBuilder {

    private String metadataBranch;
    private String metadataHead;
    private String metadataResolved;
    private String metadataUpstream;

    private String gitHead;
    private String gitOid;
    private String gitUpstream;
    private Integer ahead;
    private Integer behind;
    private boolean detached;

    BranchStateBuilder(
        @Nullable String metadataBranch,
        @Nullable String metadataHead,
        @Nullable String metadataResolved,
        @Nullable String metadataUpstream) {
      this.metadataBranch = metadataBranch;
      this.metadataHead = metadataHead;
      this.metadataResolved = metadataResolved;
      this.metadataUpstream = metadataUpstream;
    }

    void consumeMetadata(String line) {
      if (!StringUtils.hasText(line)) {
        return;
      }
      if (line.startsWith("branch.oid")) {
        this.gitOid = extractValue(line);
      } else if (line.startsWith("branch.head")) {
        String value = extractValue(line);
        if ("(detached)".equalsIgnoreCase(value)) {
          detached = true;
        } else {
          gitHead = value;
        }
      } else if (line.startsWith("branch.upstream")) {
        gitUpstream = extractValue(line);
      } else if (line.startsWith("branch.ab")) {
        String value = extractValue(line);
        String[] parts = value.split(" ");
        if (parts.length == 2) {
          ahead = parseDelta(parts[0]);
          behind = parseDelta(parts[1]);
        }
      }
    }

    WorkspaceGitStateResult.BranchState build() {
      String name = StringUtils.hasText(gitHead) ? gitHead : metadataBranch;
      boolean isDetached =
          detached || (!StringUtils.hasText(name) && StringUtils.hasText(resolveHeadSha()));
      return new WorkspaceGitStateResult.BranchState(
          name,
          resolveHeadSha(),
          StringUtils.hasText(metadataResolved) ? metadataResolved : null,
          StringUtils.hasText(gitUpstream) ? gitUpstream : metadataUpstream,
          isDetached,
          ahead,
          behind);
    }

    private String resolveHeadSha() {
      if (StringUtils.hasText(gitOid) && !"unknown".equalsIgnoreCase(gitOid)) {
        return gitOid;
      }
      return metadataHead;
    }

    private String extractValue(String line) {
      int space = line.indexOf(' ');
      return space >= 0 ? line.substring(space + 1).trim() : "";
    }

    private Integer parseDelta(String token) {
      if (!StringUtils.hasText(token)) {
        return null;
      }
      String normalized = token.replace('+', ' ').replace('-', ' ').trim();
      try {
        return Integer.parseInt(normalized);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
  }

  private static final class StatusAccumulator {
    private int staged;
    private int unstaged;
    private int untracked;
    private int conflicts;

    StatusSummary toSummary() {
      boolean clean = staged == 0 && unstaged == 0 && untracked == 0 && conflicts == 0;
      return new StatusSummary(clean, staged, unstaged, untracked, conflicts);
    }
  }

  private record GitStatusSnapshot(
      WorkspaceGitStateResult.BranchState branch,
      StatusSummary status,
      List<FileStatusEntry> files,
      boolean truncated,
      List<String> warnings) {}

  private record ProcessResult(int exitCode, String output) {}

  public record WorkspaceGitStateRequest(
      String workspaceId,
      Boolean includeFileStatus,
      Boolean includeUntracked,
      Integer maxEntries,
      Boolean includeSummaryOnly) {}

  public record WorkspaceGitStateResult(
      String workspaceId,
      BranchState branch,
      StatusSummary status,
      List<FileStatusEntry> files,
      boolean truncated,
      List<String> warnings,
      Instant inspectedAt,
      long durationMs) {

    public record BranchState(
        String name,
        String headSha,
        String resolvedRef,
        String upstream,
        boolean detached,
        @Nullable Integer ahead,
        @Nullable Integer behind) {}
  }

  public record StatusSummary(
      boolean clean, int staged, int unstaged, int untracked, int conflicts) {}

  public record FileStatusEntry(
      String path,
      @Nullable String previousPath,
      String changeType,
      boolean staged,
      boolean unstaged,
      boolean tracked) {}

  public static class WorkspaceGitStateException extends RuntimeException {
    public WorkspaceGitStateException(String message) {
      super(message);
    }

    public WorkspaceGitStateException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
