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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
    Set<String> detectedPackageManagers = new LinkedHashSet<>();
    Set<String> detectedProjectTypes = new LinkedHashSet<>();
    InfrastructureAccumulator infrastructureAccumulator = new InfrastructureAccumulator();

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
            detectedPackageManagers.addAll(item.packageManagers());
            detectedProjectTypes.addAll(item.projectTypes());
            infrastructureAccumulator.merge(item.infrastructureFlags());

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
            detectedPackageManagers.addAll(item.packageManagers());
            infrastructureAccumulator.merge(item.infrastructureFlags());

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

    String requestId = workspace.requestId();
    try {
      Files.walkFileTree(
          workspaceRoot,
          EnumSet.noneOf(FileVisitOption.class),
          maxDepth,
          visitor);
      inspectionSuccessCounter.increment();
    } catch (IOException | RuntimeException ex) {
      inspectionFailureCounter.increment();
      log.warn(
          "gradle_mcp.inspect.failed requestId={} workspaceId={} message={}",
          normalizeRequestId(requestId),
          workspaceId,
          ex.getMessage());
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

    InspectWorkspaceResult result =
        new InspectWorkspaceResult(
            workspaceId,
            workspaceRoot,
            List.copyOf(items),
            truncated.get(),
            List.copyOf(warnings),
            multipleGradle,
            recommended,
            processed.get(),
            duration,
            inspectedAt,
            infrastructureAccumulator.toFlags(),
            List.copyOf(detectedPackageManagers),
            List.copyOf(detectedProjectTypes));
    log.info(
        "gradle_mcp.inspect.completed requestId={} workspaceId={} durationMs={} items={} truncated={}",
        normalizeRequestId(requestId),
        workspaceId,
        duration.toMillis(),
        processed.get(),
        truncated.get());
    return result;
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
      List<String> packageManagers = detection.packageManagers();
      InfrastructureFlags infrastructure = detectInfrastructure(relative, true);
      return new WorkspaceItem(
          normalizePath(relative),
          WorkspaceItemType.DIRECTORY,
          0L,
          isHidden(dir),
          Files.isSymbolicLink(dir),
          false,
          attrs.lastModifiedTime().toInstant(),
          projectTypes,
          hasGradleWrapper,
          packageManagers,
          infrastructure);
    }
    InfrastructureFlags infrastructure = detectInfrastructure(relative, true);
    return new WorkspaceItem(
        normalizePath(relative),
        WorkspaceItemType.DIRECTORY,
        0L,
        isHidden(dir),
        Files.isSymbolicLink(dir),
        false,
        attrs.lastModifiedTime().toInstant(),
        projectTypes,
        hasGradleWrapper,
        List.of(),
        infrastructure);
  }

  private WorkspaceItem buildFileItem(Path file, BasicFileAttributes attrs, String relative) {
    String normalized = normalizePath(relative);
    InfrastructureFlags infrastructure = detectInfrastructure(normalized, false);
    List<String> packageManagers = detectPackageManagersFromFile(normalized);
    return new WorkspaceItem(
        normalized,
        WorkspaceItemType.FILE,
        attrs.size(),
        isHidden(file),
        Files.isSymbolicLink(file),
        Files.isExecutable(file),
        attrs.lastModifiedTime().toInstant(),
        List.of(),
        false,
        packageManagers,
        infrastructure);
  }

  private InfrastructureFlags detectInfrastructure(String relativePath, boolean directory) {
    if (!StringUtils.hasText(relativePath)) {
      return InfrastructureFlags.EMPTY;
    }
    String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
    String name = normalized;
    int lastSlash = normalized.lastIndexOf('/');
    if (lastSlash >= 0) {
      name = normalized.substring(lastSlash + 1);
    }
    boolean terraform =
        name.endsWith(".tf")
            || name.endsWith(".tfvars")
            || (directory && name.contains("terraform"))
            || normalized.contains("/terraform/");
    boolean helm =
        name.equals("chart.yaml")
            || normalized.contains("/helm/")
            || normalized.startsWith("helm/")
            || normalized.contains("/charts/")
            || (directory && name.contains("helm"));
    boolean compose =
        name.equals("docker-compose.yml")
            || name.equals("docker-compose.yaml")
            || name.equals("compose.yml")
            || name.equals("compose.yaml");
    boolean dbMigrations =
        normalized.contains("/migrations/")
            || normalized.endsWith("/migrations")
            || normalized.contains("db/migrations")
            || normalized.contains("database/migrations");
    boolean featureFlags = normalized.contains("feature-flags") || normalized.contains("feature_flags");
    if (!terraform && !helm && !compose && !dbMigrations && !featureFlags) {
      return InfrastructureFlags.EMPTY;
    }
    return new InfrastructureFlags(terraform, helm, compose, dbMigrations, featureFlags);
  }

  private List<String> detectPackageManagersFromFile(String relativePath) {
    if (!StringUtils.hasText(relativePath)) {
      return List.of();
    }
    String normalized = relativePath.replace('\\', '/');
    int lastSlash = normalized.lastIndexOf('/');
    String name = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    String lower = name.toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "package-lock.json" -> List.of("npm");
      case "yarn.lock" -> List.of("yarn");
      case "pnpm-lock.yaml", "pnpm-lock.yml" -> List.of("pnpm");
      case "poetry.lock" -> List.of("poetry");
      default -> List.of();
    };
  }

  private ProjectDetectionResult detectProjectTypes(Path directory) {
    Set<String> detected = new LinkedHashSet<>();
    Set<String> packageManagers = new LinkedHashSet<>();
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
      if (Files.exists(directory.resolve("Cargo.toml"))) {
        detected.add(ProjectType.RUST.name().toLowerCase(Locale.ROOT));
      }
      if (Files.exists(directory.resolve("go.mod"))) {
        detected.add(ProjectType.GO.name().toLowerCase(Locale.ROOT));
      }
      if (Files.exists(directory.resolve("pyproject.toml"))
          || Files.exists(directory.resolve("requirements.txt"))
          || Files.exists(directory.resolve("Pipfile"))
          || Files.exists(directory.resolve("poetry.lock"))) {
        detected.add(ProjectType.PYTHON.name().toLowerCase(Locale.ROOT));
      }
      if (Files.exists(directory.resolve("package-lock.json"))) {
        packageManagers.add("npm");
      }
      if (Files.exists(directory.resolve("yarn.lock"))) {
        packageManagers.add("yarn");
      }
      if (Files.exists(directory.resolve("pnpm-lock.yaml"))
          || Files.exists(directory.resolve("pnpm-lock.yml"))) {
        packageManagers.add("pnpm");
      }
      if (Files.exists(directory.resolve("poetry.lock"))) {
        packageManagers.add("poetry");
      }
    } catch (Exception ex) {
      log.debug("Failed to detect project type for {}: {}", directory, ex.getMessage());
    }
    return new ProjectDetectionResult(
        List.copyOf(detected), gradleWrapper, List.copyOf(packageManagers));
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

  private String normalizeRequestId(String requestId) {
    return requestId != null && !requestId.isBlank() ? requestId : "n/a";
  }

  private static class InfrastructureAccumulator {
    private boolean terraform;
    private boolean helm;
    private boolean compose;
    private boolean dbMigrations;
    private boolean featureFlags;

    void merge(InfrastructureFlags flags) {
      if (flags == null) {
        return;
      }
      terraform = terraform || flags.hasTerraform();
      helm = helm || flags.hasHelm();
      compose = compose || flags.hasCompose();
      dbMigrations = dbMigrations || flags.hasDbMigrations();
      featureFlags = featureFlags || flags.hasFeatureFlags();
    }

    InfrastructureFlags toFlags() {
      return new InfrastructureFlags(terraform, helm, compose, dbMigrations, featureFlags);
    }
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
      Instant inspectedAt,
      InfrastructureFlags infrastructureFlags,
      List<String> packageManagers,
      List<String> projectTypes) {}

  public enum WorkspaceItemType {
    FILE,
    DIRECTORY
  }

  public enum ProjectType {
    GRADLE,
    MAVEN,
    NPM,
    RUST,
    GO,
    PYTHON,
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
      boolean hasGradleWrapper,
      List<String> packageManagers,
      InfrastructureFlags infrastructureFlags) {}

  public record InfrastructureFlags(
      boolean hasTerraform,
      boolean hasHelm,
      boolean hasCompose,
      boolean hasDbMigrations,
      boolean hasFeatureFlags) {
    static final InfrastructureFlags EMPTY = new InfrastructureFlags(false, false, false, false, false);
  }

  private record ProjectDetectionResult(
      List<String> projectTypes, boolean hasGradleWrapper, List<String> packageManagers) {}

  public static class WorkspaceInspectionException extends RuntimeException {
    public WorkspaceInspectionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
