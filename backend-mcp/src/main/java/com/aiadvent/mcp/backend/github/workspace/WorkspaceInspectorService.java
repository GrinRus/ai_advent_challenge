package com.aiadvent.mcp.backend.github.workspace;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceInspectorService {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceInspectorService.class);
  private static final int DEFAULT_MAX_DEPTH = 4;
  private static final int DEFAULT_MAX_RESULTS = 400;
  private static final int MAX_RESULTS_HARD_LIMIT = 2000;

  private final TempWorkspaceService workspaceService;
  private final MeterRegistry meterRegistry;
  private final Timer inspectionTimer;
  private final Counter inspectionSuccessCounter;
  private final Counter inspectionFailureCounter;
  private final DistributionSummary itemsCountSummary;

  public WorkspaceInspectorService(
      TempWorkspaceService workspaceService, @Nullable MeterRegistry meterRegistry) {
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    MeterRegistry registry = meterRegistry;
    if (registry == null) {
      registry = new SimpleMeterRegistry();
    }
    this.meterRegistry = registry;
    this.inspectionTimer = this.meterRegistry.timer("workspace_inspection_duration");
    this.inspectionSuccessCounter =
        this.meterRegistry.counter("workspace_inspection_success_total");
    this.inspectionFailureCounter =
        this.meterRegistry.counter("workspace_inspection_failure_total");
    this.itemsCountSummary =
        this.meterRegistry.summary("workspace_inspection_items_total");
  }

  public InspectWorkspaceResult inspectWorkspace(InspectWorkspaceRequest request) {
    Objects.requireNonNull(request, "request");
    String workspaceId =
        Optional.ofNullable(request.workspaceId()).map(String::trim).orElse("");
    if (workspaceId.isEmpty()) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }

    TempWorkspaceService.Workspace workspace =
        workspaceService.findWorkspace(workspaceId).orElseThrow(
            () -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));

    Path workspaceRoot = workspace.path();
    int maxDepth =
        Optional.ofNullable(request.maxDepth())
            .filter(value -> value > 0)
            .orElse(DEFAULT_MAX_DEPTH);
    int maxResults =
        Optional.ofNullable(request.maxResults())
            .filter(value -> value > 0)
            .map(value -> Math.min(value, MAX_RESULTS_HARD_LIMIT))
            .orElse(DEFAULT_MAX_RESULTS);
    EnumSet<WorkspaceItemType> allowedTypes =
        request.types() != null && !request.types().isEmpty()
            ? EnumSet.copyOf(request.types())
            : EnumSet.allOf(WorkspaceItemType.class);
    boolean includeHidden = Boolean.TRUE.equals(request.includeHidden());
    boolean detectProjects = !Boolean.FALSE.equals(request.detectProjects());

    List<PathMatcher> includeMatchers = compileMatchers(request.includeGlobs());
    List<PathMatcher> excludeMatchers = compileMatchers(request.excludeGlobs());
    Instant inspectedAt = Instant.now();
    Timer.Sample sample = Timer.start(meterRegistry);

    List<WorkspaceItem> items = new ArrayList<>();
    AtomicBoolean truncated = new AtomicBoolean(false);
    AtomicInteger processed = new AtomicInteger();
    List<String> warnings = new ArrayList<>();
    Map<ProjectType, List<String>> projectDirectories = new EnumMap<>(ProjectType.class);

    FileVisitor<Path> visitor =
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (dir.equals(workspaceRoot)) {
              return FileVisitResult.CONTINUE;
            }
            if (!includeHidden && isHidden(dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            String relative = relativePath(workspaceRoot, dir);
            if (relative.isEmpty()) {
              return FileVisitResult.CONTINUE;
            }
            if (!matches(relative, includeMatchers, excludeMatchers)) {
              return FileVisitResult.CONTINUE;
            }
            if (!allowedTypes.contains(WorkspaceItemType.DIRECTORY)) {
              return FileVisitResult.CONTINUE;
            }

            WorkspaceItem item = buildDirectoryItem(dir, attrs, relative, detectProjects);
            items.add(item);
            processed.incrementAndGet();
            registerProject(item, projectDirectories);

            if (items.size() >= maxResults) {
              truncated.set(true);
              warnings.add("Result set truncated to " + maxResults + " entries.");
              return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!allowedTypes.contains(WorkspaceItemType.FILE)) {
              return FileVisitResult.CONTINUE;
            }
            if (!includeHidden && isHidden(file)) {
              return FileVisitResult.CONTINUE;
            }
            String relative = relativePath(workspaceRoot, file);
            if (!matches(relative, includeMatchers, excludeMatchers)) {
              return FileVisitResult.CONTINUE;
            }
            if (".workspace.json".equals(relative)) {
              return FileVisitResult.CONTINUE;
            }
            WorkspaceItem item = buildFileItem(file, attrs, relative);
            items.add(item);
            processed.incrementAndGet();

            if (items.size() >= maxResults) {
              truncated.set(true);
              warnings.add("Result set truncated to " + maxResults + " entries.");
              return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            log.debug("Failed to access {}: {}", file, exc.getMessage());
            warnings.add("Failed to access " + file.getFileName() + ": " + exc.getMessage());
            return FileVisitResult.CONTINUE;
          }
        };

    try {
      Files.walkFileTree(
          workspaceRoot,
          EnumSet.noneOf(FileVisitOption.class),
          maxDepth,
          visitor);
      inspectionSuccessCounter.increment();
    } catch (IOException | RuntimeException ex) {
      inspectionFailureCounter.increment();
      throw new WorkspaceInspectionException(
          "Failed to inspect workspace " + workspaceId, ex);
    } finally {
      Duration duration = Duration.between(inspectedAt, Instant.now());
      sample.stop(inspectionTimer);
      itemsCountSummary.record(items.size());
    }

    Duration duration = Duration.between(inspectedAt, Instant.now());
    boolean multipleGradle =
        projectDirectories.getOrDefault(ProjectType.GRADLE, List.of()).size() > 1;
    String recommended =
        projectDirectories.getOrDefault(ProjectType.GRADLE, List.of()).stream()
            .findFirst()
            .orElse(null);

    return new InspectWorkspaceResult(
        workspaceId,
        workspaceRoot,
        List.copyOf(items),
        truncated.get(),
        List.copyOf(warnings),
        multipleGradle,
        recommended,
        processed.get(),
        duration,
        inspectedAt);
  }

  private List<PathMatcher> compileMatchers(@Nullable List<String> patterns) {
    if (patterns == null || patterns.isEmpty()) {
      return List.of();
    }
    List<PathMatcher> matchers = new ArrayList<>();
    for (String pattern : patterns) {
      if (pattern == null || pattern.trim().isEmpty()) {
        continue;
      }
      matchers.add(workspaceService.getWorkspaceRoot().getFileSystem().getPathMatcher("glob:" + pattern));
    }
    return matchers;
  }

  private boolean matches(
      String relativePath, List<PathMatcher> includes, List<PathMatcher> excludes) {
    Path path = Path.of(relativePath);
    boolean included =
        includes.isEmpty() || includes.stream().anyMatch(matcher -> matcher.matches(path));
    if (!included) {
      return false;
    }
    boolean excluded = excludes.stream().anyMatch(matcher -> matcher.matches(path));
    return !excluded;
  }

  private boolean isHidden(Path path) {
    try {
      return Files.isHidden(path) || path.getFileName().toString().startsWith(".");
    } catch (IOException ex) {
      return path.getFileName().toString().startsWith(".");
    }
  }

  private WorkspaceItem buildDirectoryItem(
      Path dir, BasicFileAttributes attrs, String relative, boolean detectProjects) {
    List<String> projectTypes = List.of();
    boolean hasGradleWrapper = false;
    if (detectProjects) {
      ProjectDetectionResult detection = detectProjectTypes(dir);
      projectTypes = detection.projectTypes();
      hasGradleWrapper = detection.hasGradleWrapper();
    }
    return new WorkspaceItem(
        normalizePath(relative),
        WorkspaceItemType.DIRECTORY,
        0L,
        isHidden(dir),
        Files.isSymbolicLink(dir),
        false,
        attrs.lastModifiedTime().toInstant(),
        projectTypes,
        hasGradleWrapper);
  }

  private WorkspaceItem buildFileItem(Path file, BasicFileAttributes attrs, String relative) {
    return new WorkspaceItem(
        normalizePath(relative),
        WorkspaceItemType.FILE,
        attrs.size(),
        isHidden(file),
        Files.isSymbolicLink(file),
        Files.isExecutable(file),
        attrs.lastModifiedTime().toInstant(),
        List.of(),
        false);
  }

  private ProjectDetectionResult detectProjectTypes(Path directory) {
    List<String> detected = new ArrayList<>();
    boolean gradleWrapper = false;
    try {
      if (Files.exists(directory.resolve("gradlew"))
          || Files.exists(directory.resolve("gradlew.bat"))) {
        gradleWrapper = true;
      }
      if (Files.exists(directory.resolve("settings.gradle"))
          || Files.exists(directory.resolve("settings.gradle.kts"))
          || Files.exists(directory.resolve("build.gradle"))
          || Files.exists(directory.resolve("build.gradle.kts"))) {
        detected.add(ProjectType.GRADLE.name().toLowerCase(Locale.ROOT));
      }
      if (Files.exists(directory.resolve("pom.xml"))) {
        detected.add(ProjectType.MAVEN.name().toLowerCase(Locale.ROOT));
      }
      if (Files.exists(directory.resolve("package.json"))) {
        detected.add(ProjectType.NPM.name().toLowerCase(Locale.ROOT));
      }
    } catch (Exception ex) {
      log.debug("Failed to detect project type for {}: {}", directory, ex.getMessage());
    }
    return new ProjectDetectionResult(List.copyOf(detected), gradleWrapper);
  }

  private void registerProject(WorkspaceItem item, Map<ProjectType, List<String>> projectDirectories) {
    if (item.projectTypes().isEmpty()) {
      return;
    }
    for (String type : item.projectTypes()) {
      ProjectType projectType = parseProjectType(type);
      projectDirectories
          .computeIfAbsent(projectType, ignored -> new ArrayList<>())
          .add(item.path());
    }
  }

  private ProjectType parseProjectType(String type) {
    try {
      return ProjectType.valueOf(type.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return ProjectType.UNKNOWN;
    }
  }

  private String relativePath(Path root, Path target) {
    return root.relativize(target).toString();
  }

  private String normalizePath(String path) {
    if (path == null) {
      return "";
    }
    return path.replace('\\', '/');
  }

  public record InspectWorkspaceRequest(
      String workspaceId,
      List<String> includeGlobs,
      List<String> excludeGlobs,
      Integer maxDepth,
      Integer maxResults,
      EnumSet<WorkspaceItemType> types,
      Boolean includeHidden,
      Boolean detectProjects) {}

  public record InspectWorkspaceResult(
      String workspaceId,
      Path workspacePath,
      List<WorkspaceItem> items,
      boolean truncated,
      List<String> warnings,
      boolean containsMultipleGradleProjects,
      String recommendedProjectPath,
      int totalMatches,
      Duration duration,
      Instant inspectedAt) {}

  public enum WorkspaceItemType {
    FILE,
    DIRECTORY
  }

  public enum ProjectType {
    GRADLE,
    MAVEN,
    NPM,
    UNKNOWN
  }

  public record WorkspaceItem(
      String path,
      WorkspaceItemType type,
      long sizeBytes,
      boolean hidden,
      boolean symlink,
      boolean executable,
      Instant lastModified,
      List<String> projectTypes,
      boolean hasGradleWrapper) {}

  private record ProjectDetectionResult(List<String> projectTypes, boolean hasGradleWrapper) {}

  public static class WorkspaceInspectionException extends RuntimeException {
    public WorkspaceInspectionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
