package com.aiadvent.mcp.backend.analysis;

import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.AggregateFindingsRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.AggregateFindingsResponse;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.FileFindings;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.FindingInput;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.Hotspot;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ListHotspotsRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ListHotspotsResponse;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanConfigOverrides;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentResponse;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.Segment;
import com.aiadvent.mcp.backend.config.RepoAnalysisProperties;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InfrastructureFlags;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceRequest;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceResult;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.WorkspaceItemType;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RepoAnalysisService {

private static final Logger log = LoggerFactory.getLogger(RepoAnalysisService.class);

private static final int SEGMENT_HASH_HISTORY = 500;

  private static final Pattern COMPLEXITY_KEYWORDS =
      Pattern.compile("\\b(if|for|while|case|catch|switch|when|except)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern SQL_KEYWORDS =
      Pattern.compile(
          "\\b(select|insert|update|delete|join|where|having|group by|order by)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern COLLECTION_KEYWORDS =
      Pattern.compile(
          "ConcurrentHashMap|CopyOnWriteArrayList|computeIfAbsent|synchronized|Atomic",
          Pattern.CASE_INSENSITIVE);

  private static final String[] SECURITY_KEYWORDS =
      new String[] {"sql", "injection", "xss", "auth", "csrf", "crypto", "secrets"};
  private static final String[] PERFORMANCE_KEYWORDS =
      new String[] {"slow", "latency", "allocate", "allocation", "throughput", "perf"};
  private static final String[] CORRECTNESS_KEYWORDS =
      new String[] {"null", "race", "overflow", "deadlock", "incorrect", "bug"};

  private static final Map<String, Integer> SEVERITY_WEIGHTS =
      Map.ofEntries(
          Map.entry("CRITICAL", 5),
          Map.entry("BLOCKER", 5),
          Map.entry("HIGH", 4),
          Map.entry("ERROR", 4),
          Map.entry("FAIL", 4),
          Map.entry("MEDIUM", 3),
          Map.entry("WARN", 3),
          Map.entry("WARNING", 3),
          Map.entry("LOW", 2),
          Map.entry("INFO", 1),
          Map.entry("HINT", 1),
          Map.entry("UNKNOWN", 0));

  private final RepoAnalysisProperties properties;
  private final RepoAnalysisStateStore stateStore;
  private final TempWorkspaceService workspaceService;
  private final WorkspaceInspectorService workspaceInspectorService;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  RepoAnalysisService(
      RepoAnalysisProperties properties,
      RepoAnalysisStateStore stateStore,
      TempWorkspaceService workspaceService,
      WorkspaceInspectorService workspaceInspectorService) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    this.workspaceInspectorService =
        Objects.requireNonNull(workspaceInspectorService, "workspaceInspectorService");
  }

  ScanNextSegmentResponse scanNextSegment(ScanNextSegmentRequest request) {
    validateScanRequest(request);
    String analysisId = request.analysisId().trim();
    Lock lock = locks.computeIfAbsent(analysisId, key -> new ReentrantLock());
    lock.lock();
    try {
      Path workspaceRoot = workspaceService.requireWorkspacePath(request.workspaceId());
      Path projectRoot = resolveProjectPath(workspaceRoot, request.projectPath());

      RepoAnalysisState state =
          loadOrCreateState(analysisId, request, workspaceRoot, projectRoot);

      if (Boolean.TRUE.equals(request.reset())) {
        state = initializeState(analysisId, request, workspaceRoot, projectRoot);
      }

      if (!Objects.equals(state.getWorkspaceId(), request.workspaceId())) {
        state = initializeState(analysisId, request, workspaceRoot, projectRoot);
      }

      long segmentLimit = determineSegmentLimit(request, state);

      RepoAnalysisState.FileCursor cursor = state.pollPending();
      if (cursor == null) {
        stateStore.save(state);
        return new ScanNextSegmentResponse(
            analysisId,
            request.workspaceId(),
            request.projectPath(),
            true,
            null,
            0,
            state.getProcessedSegments(),
            List.copyOf(state.getWarnings()),
            List.copyOf(state.getSkippedFiles()),
            Instant.now());
      }

      SegmentResult segmentResult =
          readSegment(
              cursor,
              workspaceRoot,
              segmentLimit,
              state.getConfig().getSegmentMaxBytes(),
              state);

      state.incrementProcessedSegments();

      if (segmentResult.completed()) {
        cursor.setCompleted(true);
        state.addProcessed(cursor);
      } else {
        state.addPendingFirst(cursor);
      }

      state.touch();
      stateStore.save(state);

      RepoAnalysisModels.SegmentMetadata segmentMetadata =
          toSegmentMetadata(state.getWorkspaceMetadata());

      Segment segment =
          new Segment(
              segmentResult.key(),
              cursor.getPath(),
              segmentResult.segmentIndex(),
              cursor.getTotalSegments(),
              segmentResult.lineStart(),
              segmentResult.lineEnd(),
              segmentResult.bytesRead(),
              segmentResult.truncated(),
              segmentResult.content(),
              segmentResult.summary(),
              segmentResult.readAt(),
              segmentResult.hash(),
              segmentResult.duplicate(),
              segmentResult.tags(),
              segmentMetadata);

      return new ScanNextSegmentResponse(
          analysisId,
          request.workspaceId(),
          request.projectPath(),
          false,
          segment,
          state.getPending().size(),
          state.getProcessedSegments(),
          List.copyOf(state.getWarnings()),
          List.copyOf(state.getSkippedFiles()),
          Instant.now());
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read next segment: " + ex.getMessage(), ex);
    } finally {
      lock.unlock();
    }
  }

  AggregateFindingsResponse aggregateFindings(AggregateFindingsRequest request) {
    validateAggregateRequest(request);
    String analysisId = request.analysisId().trim();
    Lock lock = locks.computeIfAbsent(analysisId, key -> new ReentrantLock());
    lock.lock();
    try {
      Optional<RepoAnalysisState> optionalState = stateStore.load(analysisId);
      if (optionalState.isEmpty()) {
        log.debug("Aggregate request for unknown analysisId {}: returning empty response", analysisId);
        return new AggregateFindingsResponse(
            analysisId, request.workspaceId(), 0, 0, List.of(), Instant.now());
      }
      RepoAnalysisState state = optionalState.get();
      if (!Objects.equals(state.getWorkspaceId(), request.workspaceId())) {
        log.warn(
            "Workspace mismatch for analysis {} (expected {}, got {}) - returning empty response",
            analysisId,
            state.getWorkspaceId(),
            request.workspaceId());
        return new AggregateFindingsResponse(
            analysisId, request.workspaceId(), 0, 0, List.of(), Instant.now());
      }

      int newFindings = appendFindings(state, request.findings());
      state.touch();
      stateStore.save(state);

      List<FileFindings> files = aggregateFiles(state.getFindings());
      return new AggregateFindingsResponse(
          analysisId,
          request.workspaceId(),
          state.getFindings().size(),
          newFindings,
          files,
          Instant.now());
    } finally {
      lock.unlock();
    }
  }

  ListHotspotsResponse listHotspots(ListHotspotsRequest request) {
    validateHotspotRequest(request);
    String analysisId = request.analysisId().trim();
    Lock lock = locks.computeIfAbsent(analysisId, key -> new ReentrantLock());
    lock.lock();
    try {
      Optional<RepoAnalysisState> optionalState = stateStore.load(analysisId);
      if (optionalState.isEmpty()) {
        log.debug("Hotspot request for unknown analysisId {}: returning empty list", analysisId);
        return new ListHotspotsResponse(analysisId, request.workspaceId(), List.of(), Instant.now());
      }
      RepoAnalysisState state = optionalState.get();
      if (!Objects.equals(state.getWorkspaceId(), request.workspaceId())) {
        log.warn(
            "Workspace mismatch for analysis {} (expected {}, got {}) - returning empty hotspot list",
            analysisId,
            state.getWorkspaceId(),
            request.workspaceId());
        return new ListHotspotsResponse(analysisId, request.workspaceId(), List.of(), Instant.now());
      }
      int limit = Optional.ofNullable(request.limit()).filter(value -> value > 0).orElse(10);
      boolean includeDetails = Boolean.TRUE.equals(request.includeDetails());
      List<Hotspot> hotspots = buildHotspots(state.getFindings(), limit, includeDetails);
      return new ListHotspotsResponse(
          analysisId, request.workspaceId(), hotspots, Instant.now());
    } finally {
      lock.unlock();
    }
  }

  private void validateScanRequest(ScanNextSegmentRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!StringUtils.hasText(request.analysisId())) {
      throw new IllegalArgumentException("analysisId must not be blank");
    }
    if (!StringUtils.hasText(request.workspaceId())) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
  }

  private void validateAggregateRequest(AggregateFindingsRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!StringUtils.hasText(request.analysisId())) {
      throw new IllegalArgumentException("analysisId must not be blank");
    }
    if (!StringUtils.hasText(request.workspaceId())) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
  }

  private void validateHotspotRequest(ListHotspotsRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!StringUtils.hasText(request.analysisId())) {
      throw new IllegalArgumentException("analysisId must not be blank");
    }
    if (!StringUtils.hasText(request.workspaceId())) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
  }

  private Path resolveProjectPath(Path workspaceRoot, String projectPath) {
    if (!StringUtils.hasText(projectPath)) {
      return workspaceRoot;
    }
    Path resolved = workspaceRoot.resolve(projectPath).normalize();
    if (!resolved.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException("projectPath must be within workspace");
    }
    if (!Files.exists(resolved)) {
      throw new IllegalArgumentException("projectPath does not exist: " + projectPath);
    }
    if (!Files.isDirectory(resolved)) {
      throw new IllegalArgumentException("projectPath must point to a directory: " + projectPath);
    }
    return resolved;
  }

  private RepoAnalysisState loadOrCreateState(
      String analysisId,
      ScanNextSegmentRequest request,
      Path workspaceRoot,
      Path projectRoot)
      throws IOException {
    if (Boolean.TRUE.equals(request.reset())) {
      stateStore.delete(analysisId);
      return initializeState(analysisId, request, workspaceRoot, projectRoot);
    }
    return stateStore
        .load(analysisId)
        .orElseGet(
            () -> {
              try {
                return initializeState(analysisId, request, workspaceRoot, projectRoot);
              } catch (IOException ex) {
                throw new IllegalStateException("Failed to initialize analysis", ex);
              }
            });
  }

  private RepoAnalysisState initializeState(
      String analysisId,
      ScanNextSegmentRequest request,
      Path workspaceRoot,
      Path projectRoot)
      throws IOException {
    RepoAnalysisState state = new RepoAnalysisState();
    state.setAnalysisId(analysisId);
    state.setWorkspaceId(request.workspaceId());
    state.setProjectPath(normalizeProjectPath(request.projectPath()));
    state.setConfig(buildConfig(request.configOverrides()));
    Instant now = Instant.now();
    state.setCreatedAt(now);
    state.setUpdatedAt(now);
    state.setWorkspaceMetadata(captureWorkspaceMetadata(request.workspaceId()));

    RepoAnalysisIgnoreMatcher ignoreMatcher = RepoAnalysisIgnoreMatcher.load(workspaceRoot, projectRoot);
    List<RepoAnalysisState.FileCursor> cursors =
        discoverFiles(workspaceRoot, projectRoot, state.getConfig(), ignoreMatcher, state);
    for (RepoAnalysisState.FileCursor cursor : cursors) {
      state.addPending(cursor);
    }
    stateStore.save(state);
    return state;
  }

  private String normalizeProjectPath(String projectPath) {
    if (!StringUtils.hasText(projectPath)) {
      return "";
    }
    String trimmed = projectPath.trim();
    if (trimmed.startsWith("./")) {
      trimmed = trimmed.substring(2);
    }
    return trimmed.replace('\\', '/');
  }

  private RepoAnalysisState.Config buildConfig(ScanConfigOverrides overrides) {
    RepoAnalysisState.Config config = new RepoAnalysisState.Config();
    config.setMaxDepth(
        clamp(overrides != null ? overrides.maxDepth() : null, properties.getMaxDepth(), 1, 32));
    config.setMaxFileBytes(
        clampLong(
            overrides != null ? overrides.maxFileBytes() : null, properties.getMaxFileBytes(), 1024L,
            10L * 1024 * 1024));
    config.setSegmentMaxBytes(
        clampLong(
            overrides != null ? overrides.segmentMaxBytes() : null,
            properties.getSegmentMaxBytes(),
            2048L,
            256L * 1024));
    config.setIncludeHidden(overrides != null && Boolean.TRUE.equals(overrides.includeHidden())
        ? true
        : properties.isIncludeHidden());
    config.setFollowSymlinks(properties.isFollowSymlinks());
    config.setIncludeExtensions(
        overrides != null && overrides.includeExtensions() != null
            ? overrides.includeExtensions()
            : properties.getIncludeExtensions());
    config.setExcludeExtensions(
        overrides != null && overrides.excludeExtensions() != null
            ? overrides.excludeExtensions()
            : properties.getExcludeExtensions());
    config.setExcludeDirectories(
        overrides != null && overrides.excludeDirectories() != null
            ? overrides.excludeDirectories()
            : properties.getExcludeDirectories());
    config.setAdvancedMetricsEnabled(properties.isEnableAdvancedMetrics());
    return config;
  }

  private RepoAnalysisState.WorkspaceMetadata toWorkspaceMetadata(InspectWorkspaceResult result) {
    if (result == null) {
      return null;
    }
    RepoAnalysisState.WorkspaceMetadata metadata = new RepoAnalysisState.WorkspaceMetadata();
    metadata.setProjectTypes(distinctList(result.projectTypes()));
    metadata.setPackageManagers(distinctList(result.packageManagers()));
    metadata.setInfrastructureFlags(mapInfrastructureFlags(result.infrastructureFlags()));
    return metadata;
  }

  private RepoAnalysisModels.SegmentMetadata toSegmentMetadata(
      RepoAnalysisState.WorkspaceMetadata metadata) {
    if (metadata == null) {
      return null;
    }
    return new RepoAnalysisModels.SegmentMetadata(
        List.copyOf(metadata.getProjectTypes()),
        List.copyOf(metadata.getPackageManagers()),
        toModelInfrastructureFlags(metadata.getInfrastructureFlags()));
  }

  private RepoAnalysisState.InfrastructureFlags mapInfrastructureFlags(
      InfrastructureFlags flags) {
    if (flags == null) {
      return new RepoAnalysisState.InfrastructureFlags();
    }
    return new RepoAnalysisState.InfrastructureFlags(
        flags.hasTerraform(),
        flags.hasHelm(),
        flags.hasCompose(),
        flags.hasDbMigrations(),
        flags.hasFeatureFlags());
  }

  private RepoAnalysisModels.InfrastructureFlags toModelInfrastructureFlags(
      RepoAnalysisState.InfrastructureFlags flags) {
    if (flags == null) {
      return null;
    }
    return new RepoAnalysisModels.InfrastructureFlags(
        flags.isHasTerraform(),
        flags.isHasHelm(),
        flags.isHasCompose(),
        flags.isHasDbMigrations(),
        flags.isHasFeatureFlags());
  }

  private List<String> distinctList(List<String> source) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    return source.stream()
        .filter(StringUtils::hasText)
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }

  private RepoAnalysisState.WorkspaceMetadata captureWorkspaceMetadata(String workspaceId) {
    if (!StringUtils.hasText(workspaceId)) {
      return null;
    }
    try {
      InspectWorkspaceResult result =
          workspaceInspectorService.inspectWorkspace(
              new InspectWorkspaceRequest(
                  workspaceId,
                  null,
                  null,
                  3,
                  400,
                  EnumSet.of(WorkspaceItemType.FILE, WorkspaceItemType.DIRECTORY),
                  false,
                  true));
      return toWorkspaceMetadata(result);
    } catch (Exception ex) {
      log.debug("Failed to capture workspace metadata for {}: {}", workspaceId, ex.getMessage());
      return null;
    }
  }

  private int clamp(Integer override, int defaultValue, int min, int max) {
    int value = defaultValue;
    if (override != null && override > 0) {
      value = override;
    }
    if (value < min) {
      return min;
    }
    return Math.min(value, max);
  }

  private long clampLong(Long override, long defaultValue, long min, long max) {
    long value = defaultValue;
    if (override != null && override > 0) {
      value = override;
    }
    if (value < min) {
      return min;
    }
    return Math.min(value, max);
  }

  private long determineSegmentLimit(ScanNextSegmentRequest request, RepoAnalysisState state) {
    long limit = state.getConfig().getSegmentMaxBytes();
    if (request.maxBytes() != null && request.maxBytes() > 0) {
      limit = Math.min(limit, request.maxBytes());
    }
    return Math.max(64, limit);
  }

  private List<RepoAnalysisState.FileCursor> discoverFiles(
      Path workspaceRoot,
      Path projectRoot,
      RepoAnalysisState.Config config,
      RepoAnalysisIgnoreMatcher ignoreMatcher,
      RepoAnalysisState state)
      throws IOException {
    List<RepoAnalysisState.FileCursor> cursors = new ArrayList<>();
    EnumSet<FileVisitOption> options = EnumSet.noneOf(FileVisitOption.class);
    if (config.isFollowSymlinks()) {
      options.add(FileVisitOption.FOLLOW_LINKS);
    }
    Set<Path> visited = new HashSet<>();
    EnumMap<RepoFileType, Integer> perTypeCounters = new EnumMap<>(RepoFileType.class);
    Files.walkFileTree(
        projectRoot,
        options,
        config.getMaxDepth(),
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (!config.isIncludeHidden() && Files.isHidden(dir) && !dir.equals(projectRoot)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (!dir.equals(projectRoot) && ignoreMatcher.isIgnored(dir, true)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (!dir.equals(projectRoot)) {
              Path rel = projectRoot.relativize(dir);
              String name = rel.getFileName().toString();
              if (config.getExcludeDirectories().stream()
                  .anyMatch(
                      excluded -> excluded.equalsIgnoreCase(name) || rel.toString().contains(excluded))) {
                return FileVisitResult.SKIP_SUBTREE;
              }
            }
            if (!visited.add(dir.toRealPath())) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isDirectory()) {
              return FileVisitResult.CONTINUE;
            }
            if (!config.isIncludeHidden() && Files.isHidden(file)) {
              return FileVisitResult.CONTINUE;
            }
            if (ignoreMatcher.isIgnored(file, false)) {
              state.addSkippedFile(relPath(workspaceRoot, file));
              return FileVisitResult.CONTINUE;
            }
            Path relToProject = projectRoot.relativize(file);
            String extension = extractExtension(relToProject.getFileName().toString());
            if (!config.getIncludeExtensions().isEmpty()
                && !config.getIncludeExtensions().contains(extension)) {
              state.addSkippedFile(relPath(workspaceRoot, file));
              return FileVisitResult.CONTINUE;
            }
            if (config.getExcludeExtensions().stream()
                .anyMatch(ext -> ext.equalsIgnoreCase(extension))) {
              state.addSkippedFile(relPath(workspaceRoot, file));
              return FileVisitResult.CONTINUE;
            }
            long size = Files.size(file);
            if (size == 0) {
              state.addSkippedFile(relPath(workspaceRoot, file));
              return FileVisitResult.CONTINUE;
            }
            if (size > config.getMaxFileBytes()) {
              state.addWarning(
                  "Skipped large file "
                      + relPath(workspaceRoot, file)
                      + " ("
                      + size
                      + " bytes exceeds "
                      + config.getMaxFileBytes()
                      + ")");
              state.addSkippedFile(relPath(workspaceRoot, file));
              return FileVisitResult.CONTINUE;
            }
            if (isBinary(file)) {
              state.addWarning("Skipped binary file " + relPath(workspaceRoot, file));
              state.addSkippedFile(relPath(workspaceRoot, file));
              return FileVisitResult.CONTINUE;
            }
            RepoAnalysisState.FileCursor cursor = new RepoAnalysisState.FileCursor();
            cursor.setPath(relPath(workspaceRoot, file));
            cursor.setSizeBytes(size);
            cursor.setBinary(false);
            cursor.setOffset(0);
            cursor.setSegmentIndex(0);
            cursor.setLineOffset(0);
            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
            cursor.setLastModified(lastModified);
            int totalSegments =
                (int)
                    Math.max(
                        1,
                        Math.ceil(
                            (double) size
                                / Math.max(1024, config.getSegmentMaxBytes())));
            cursor.setTotalSegments(totalSegments);
            RepoFileType fileType = classifyFile(relToProject, extension);
            if (isTypeLimitReached(fileType, perTypeCounters)) {
              state.addWarning(
                  "Skipped "
                      + relPath(workspaceRoot, file)
                      + " due to per-type limit "
                      + fileType);
              state.addSkippedFile(relPath(workspaceRoot, file));
              return FileVisitResult.CONTINUE;
            }
            cursor.setFileType(fileType.name());
            cursor.setPriorityWeight(
                computePriorityWeight(
                    fileType, size, lastModified, config.getMaxFileBytes()));
            cursors.add(cursor);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            state.addWarning(
                "Failed to inspect "
                    + relPath(workspaceRoot, file)
                    + ": "
                    + (exc != null ? exc.getMessage() : "unknown error"));
            return FileVisitResult.CONTINUE;
          }
        });
    return cursors;
  }

  private String relPath(Path workspaceRoot, Path file) {
    return workspaceRoot
        .relativize(file)
        .toString()
        .replace('\\', '/');
  }

  private boolean isBinary(Path file) {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      byte[] buffer = in.readNBytes(1024);
      if (buffer.length == 0) {
        return false;
      }
      int control = 0;
      for (byte b : buffer) {
        int value = b & 0xFF;
        if (value == 0) {
          return true;
        }
        if (value < 0x09 || (value > 0x0A && value < 0x20)) {
          control++;
        }
      }
      return control > buffer.length * 0.3;
    } catch (IOException ex) {
      log.debug("Failed to detect binary type for {}: {}", file, ex.getMessage());
      return false;
    }
  }

  private SegmentResult readSegment(
      RepoAnalysisState.FileCursor cursor,
      Path workspaceRoot,
      long limit,
      long configuredLimit,
      RepoAnalysisState state)
      throws IOException {
    Path file = workspaceRoot.resolve(cursor.getPath()).normalize();
    if (!file.startsWith(workspaceRoot)) {
      throw new IllegalStateException("Segment path escaped workspace: " + cursor.getPath());
    }
    if (!Files.exists(file)) {
      throw new IllegalStateException("File no longer exists: " + cursor.getPath());
    }

    long bytesToRead = Math.min(limit, configuredLimit);
    long bytesRead = 0;
    byte[] buffer = new byte[(int) Math.min(bytesToRead, 64 * 1024)];
    ByteBuffer byteBuffer = ByteBuffer.allocate((int) bytesToRead);
    try (InputStream input = Files.newInputStream(file)) {
      long skipped = 0;
      while (skipped < cursor.getOffset()) {
        long step = input.skip(cursor.getOffset() - skipped);
        if (step <= 0) {
          break;
        }
        skipped += step;
      }
      while (bytesRead < bytesToRead) {
        int requested = (int) Math.min(buffer.length, bytesToRead - bytesRead);
        int read = input.read(buffer, 0, requested);
        if (read == -1) {
          break;
        }
        byteBuffer.put(buffer, 0, read);
        bytesRead += read;
      }
    }
    byteBuffer.flip();
    byte[] data = new byte[byteBuffer.remaining()];
    byteBuffer.get(data);
    String content = decodeToUtf8(data);
    int linesRead = countLines(content);
    int lineStart = cursor.getLineOffset() + 1;
    int lineEnd = cursor.getLineOffset() + linesRead;
    cursor.setLineOffset(cursor.getLineOffset() + linesRead);
    cursor.setOffset(cursor.getOffset() + bytesRead);
    cursor.setSegmentIndex(cursor.getSegmentIndex() + 1);
    boolean completed = cursor.getOffset() >= cursor.getSizeBytes() || bytesRead < bytesToRead;
    if (completed) {
      cursor.setCompleted(true);
    }
    List<String> heuristicsTags = List.of();
    if (state.getConfig().isAdvancedMetricsEnabled()) {
      heuristicsTags = analyzeComplexity(content).tags();
      if (!heuristicsTags.isEmpty()) {
        state.recordHeuristics(cursor.getPath(), heuristicsTags);
      }
    }
    String summary = summarize(content);
    if (!heuristicsTags.isEmpty()) {
      summary = appendTags(summary, heuristicsTags);
    }
    String contentHash = hashSegment(cursor.getPath(), content);
    boolean duplicate = false;
    if (contentHash != null) {
      boolean registered = state.registerSegmentHash(contentHash, SEGMENT_HASH_HISTORY);
      duplicate = !registered;
    }
    return new SegmentResult(
        "%s:%s#%d"
            .formatted(
                cursor.getPath(),
                cursor.getSizeBytes(),
                cursor.getSegmentIndex()),
        cursor.getSegmentIndex(),
        bytesRead,
        lineStart,
        lineEnd,
        summary,
        content,
        !completed,
        completed,
        Instant.now(),
        contentHash,
        duplicate,
        heuristicsTags);
  }

  private String decodeToUtf8(byte[] data) {
    if (data.length == 0) {
      return "";
    }
    var decoder = StandardCharsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPLACE);
    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
    try {
      return decoder.decode(ByteBuffer.wrap(data)).toString();
    } catch (CharacterCodingException ex) {
      return new String(data, StandardCharsets.UTF_8);
    }
  }

  private int countLines(String content) {
    if (content == null || content.isEmpty()) {
      return 0;
    }
    int lines = 1;
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        lines++;
      }
    }
    return lines;
  }

  private String summarize(String content) {
    if (!StringUtils.hasText(content)) {
      return "";
    }
    String summary =
        Stream.of(content.split("\n"))
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .limit(3)
        .collect(Collectors.joining(" "));
    if (summary.length() <= 240) {
      return summary;
    }
    return summary.substring(0, 240);
  }

  private String appendTags(String summary, List<String> tags) {
    if (tags == null || tags.isEmpty()) {
      return summary;
    }
    String base = StringUtils.hasText(summary) ? summary : "";
    return base + " [" + String.join(",", tags) + "]";
  }

  private ComplexityHints analyzeComplexity(String content) {
    if (!StringUtils.hasText(content)) {
      return ComplexityHints.EMPTY;
    }
    String normalized = content;
    int complexity = 0;
    var keywordMatcher = COMPLEXITY_KEYWORDS.matcher(normalized);
    while (keywordMatcher.find()) {
      complexity++;
    }
    int maxDepth = 0;
    int depth = 0;
    for (int i = 0; i < normalized.length(); i++) {
      char ch = normalized.charAt(i);
      if (ch == '{' || ch == '(') {
        depth++;
        if (depth > maxDepth) {
          maxDepth = depth;
        }
      } else if (ch == '}' || ch == ')') {
        depth = Math.max(0, depth - 1);
      }
    }
    boolean hasSql = SQL_KEYWORDS.matcher(normalized).find();
    boolean hasCollections = COLLECTION_KEYWORDS.matcher(normalized).find();
    Set<String> tags = new LinkedHashSet<>();
    if (complexity >= 25 || maxDepth >= 6) {
      tags.add("maintainability");
    }
    if (hasSql || hasCollections || complexity >= 35) {
      tags.add("performance");
    }
    return new ComplexityHints(List.copyOf(tags));
  }

  private String hashSegment(String path, String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      if (path != null) {
        digest.update(path.getBytes(StandardCharsets.UTF_8));
      }
      digest.update((byte) 0x00);
      if (content != null) {
        digest.update(content.getBytes(StandardCharsets.UTF_8));
      }
      return Base64.getEncoder().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      log.debug("Unable to compute segment hash: {}", ex.getMessage());
      return null;
    }
  }

  private List<String> mergeTags(List<String> original, List<String> heuristics) {
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    if (original != null) {
      for (String tag : original) {
        if (StringUtils.hasText(tag)) {
          tags.add(tag.trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    if (heuristics != null) {
      for (String tag : heuristics) {
        if (StringUtils.hasText(tag)) {
          tags.add(tag.trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    if (tags.isEmpty()) {
      return List.of();
    }
    return List.copyOf(tags);
  }

  private String buildFindingSignature(
      String path, Integer line, String summary, String severity) {
    return "%s|%s|%s|%s"
        .formatted(
            path != null ? path : "n/a",
            line != null ? line : "n/a",
            severity != null ? severity : "n/a",
            summary != null ? summary.trim() : "");
  }

  private String classifyCategory(String summary, List<String> tags) {
    StringBuilder builder = new StringBuilder();
    if (StringUtils.hasText(summary)) {
      builder.append(summary.toLowerCase(Locale.ROOT)).append(' ');
    }
    if (tags != null) {
      tags.stream()
          .filter(StringUtils::hasText)
          .forEach(tag -> builder.append(tag.toLowerCase(Locale.ROOT)).append(' '));
    }
    String haystack = builder.toString();
    if (containsAny(haystack, SECURITY_KEYWORDS)) {
      return "security";
    }
    if (containsAny(haystack, PERFORMANCE_KEYWORDS)) {
      return "performance";
    }
    if (containsAny(haystack, CORRECTNESS_KEYWORDS)) {
      return "correctness";
    }
    return "maintainability";
  }

  private boolean containsAny(String content, String[] keywords) {
    if (!StringUtils.hasText(content) || keywords == null) {
      return false;
    }
    String normalized = content.toLowerCase(Locale.ROOT);
    for (String keyword : keywords) {
      if (normalized.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  private int appendFindings(
      RepoAnalysisState state, List<FindingInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      return 0;
    }
    int added = 0;
    for (FindingInput input : inputs) {
      if (input == null || !StringUtils.hasText(input.path())) {
        continue;
      }
      String normalizedPath = normalizeFindingPath(input.path());
      String severity = normalizeSeverity(input.severity());
      if (isDuplicate(state, normalizedPath, input.line(), input.summary(), severity)) {
        continue;
      }
      RepoAnalysisState.RepoFinding finding = new RepoAnalysisState.RepoFinding();
      finding.setId(java.util.UUID.randomUUID().toString());
      finding.setPath(normalizedPath);
      finding.setLine(input.line());
      finding.setEndLine(input.endLine());
      finding.setTitle(trimToNull(input.title()));
      String normalizedSummary = trimToNull(input.summary());
      finding.setSummary(normalizedSummary);
      finding.setSeverity(severity);
      List<String> mergedTags = mergeTags(input.tags(), state.resolveHeuristics(normalizedPath));
      finding.setTags(mergedTags);
      finding.setCategory(classifyCategory(normalizedSummary, mergedTags));
      finding.setScore(input.score());
      finding.setSegmentKey(null);
      finding.setRecordedAt(Instant.now());
      state.addFinding(finding);
      added++;
    }
    return added;
  }

  private boolean isDuplicate(
      RepoAnalysisState state,
      String path,
      Integer line,
      String summary,
      String severity) {
    String normalizedSummary = trimToNull(summary);
    boolean exists =
        state.getFindings().stream()
        .anyMatch(
            finding ->
                Objects.equals(finding.getPath(), path)
                    && Objects.equals(finding.getLine(), line)
                    && Objects.equals(finding.getSummary(), normalizedSummary)
                    && Objects.equals(finding.getSeverity(), severity));
    if (exists) {
      return true;
    }
    String signature = buildFindingSignature(path, line, normalizedSummary, severity);
    return !state.registerFindingSignature(signature);
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String normalizeFindingPath(String path) {
    String trimmed = path.trim();
    if (trimmed.startsWith("./")) {
      trimmed = trimmed.substring(2);
    }
    return trimmed.replace('\\', '/');
  }

  private String normalizeSeverity(String severity) {
    if (!StringUtils.hasText(severity)) {
      return "INFO";
    }
    String normalized = severity.trim().toUpperCase(Locale.ROOT);
    if (SEVERITY_WEIGHTS.containsKey(normalized)) {
      return normalized;
    }
    return switch (normalized) {
      case "ERRORS" -> "ERROR";
      case "WARNINGS" -> "WARNING";
      default -> "UNKNOWN";
    };
  }

  private List<FileFindings> aggregateFiles(List<RepoAnalysisState.RepoFinding> findings) {
    if (findings.isEmpty()) {
      return List.of();
    }
    return findings.stream()
        .collect(Collectors.groupingBy(RepoAnalysisState.RepoFinding::getPath))
        .entrySet()
        .stream()
        .map(entry -> toFileFindings(entry.getKey(), entry.getValue()))
        .sorted(
            Comparator.comparingDouble(FileFindings::score)
                .reversed()
                .thenComparing(FileFindings::findingCount, Comparator.reverseOrder()))
        .limit(200)
        .toList();
  }

  private FileFindings toFileFindings(String path, List<RepoAnalysisState.RepoFinding> findings) {
    int count = findings.size();
    String worstSeverity =
        findings.stream()
            .map(RepoAnalysisState.RepoFinding::getSeverity)
            .max(Comparator.comparingInt(this::severityWeight))
            .orElse("INFO");
    double score =
        findings.stream()
            .mapToDouble(
                finding ->
                    finding.getScore() != null
                        ? finding.getScore()
                        : severityWeight(finding.getSeverity()))
            .max()
            .orElse(0.0);
    List<String> tags =
        findings.stream()
            .flatMap(finding -> finding.getTags().stream())
            .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
            .filter(tag -> !tag.isEmpty())
            .distinct()
            .limit(8)
            .toList();
    List<String> highlights =
        findings.stream()
            .map(RepoAnalysisState.RepoFinding::getSummary)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .limit(5)
            .toList();
    return new FileFindings(path, count, worstSeverity, score, tags, highlights);
  }

  private List<Hotspot> buildHotspots(
      List<RepoAnalysisState.RepoFinding> findings, int limit, boolean includeDetails) {
    if (findings.isEmpty()) {
      return List.of();
    }
    return findings.stream()
        .collect(Collectors.groupingBy(RepoAnalysisState.RepoFinding::getPath))
        .entrySet()
        .stream()
        .map(
            entry -> {
              List<RepoAnalysisState.RepoFinding> items = entry.getValue();
              int count = items.size();
              String severity =
                  items.stream()
                      .map(RepoAnalysisState.RepoFinding::getSeverity)
                      .max(Comparator.comparingInt(this::severityWeight))
                      .orElse("INFO");
              double score =
                  items.stream()
                      .mapToDouble(
                          item ->
                              item.getScore() != null
                                  ? item.getScore()
                                  : severityWeight(item.getSeverity()))
                      .max()
                      .orElse(0.0);
              String category =
                  items.stream()
                      .map(RepoAnalysisState.RepoFinding::getCategory)
                      .filter(StringUtils::hasText)
                      .collect(Collectors.groupingBy(cat -> cat, Collectors.counting()))
                      .entrySet()
                      .stream()
                      .max(Map.Entry.comparingByValue())
                      .map(Map.Entry::getKey)
                      .orElse("maintainability");
              double priority = severityWeight(severity) * Math.max(1, count) + score;
              List<String> tags =
                  items.stream()
                      .flatMap(item -> item.getTags().stream())
                      .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                      .filter(tag -> !tag.isEmpty())
                      .distinct()
                      .limit(8)
                      .toList();
              List<String> highlights =
                  includeDetails
                      ? items.stream()
                          .map(RepoAnalysisState.RepoFinding::getSummary)
                          .filter(Objects::nonNull)
                          .map(String::trim)
                          .filter(line -> !line.isEmpty())
                          .limit(5)
                          .toList()
                      : List.of();
              return new Hotspot(
                  entry.getKey(), severity, count, score, priority, category, highlights, tags);
            })
        .sorted(
            Comparator.comparingDouble(Hotspot::priority)
                .reversed()
                .thenComparing(Hotspot::findingCount, Comparator.reverseOrder()))
        .limit(limit)
        .toList();
  }

  private int severityWeight(String severity) {
    return SEVERITY_WEIGHTS.getOrDefault(
        severity != null ? severity.toUpperCase(Locale.ROOT) : "INFO", 0);
  }

  private RepoFileType classifyFile(Path relative, String extension) {
    String normalized = relative.toString().toLowerCase(Locale.ROOT);
    String ext = extension != null ? extension.toLowerCase(Locale.ROOT) : "";
    if (normalized.startsWith("src/test")
        || normalized.contains("/test/")
        || normalized.endsWith("test")
        || normalized.contains("/spec")) {
      return RepoFileType.TEST;
    }
    if (normalized.contains("infra")
        || normalized.contains("deploy")
        || normalized.contains("docker")
        || normalized.contains("helm")
        || normalized.contains("k8s")
        || ext.equals("tf")
        || ext.equals("helm")
        || ext.equals("yaml") && normalized.contains("helm")
        || ext.equals("yml") && normalized.contains("helm")) {
      return RepoFileType.INFRA;
    }
    if (normalized.contains("doc") || ext.equals("md") || ext.equals("rst") || ext.equals("adoc")) {
      return RepoFileType.DOC;
    }
    return RepoFileType.CODE;
  }

  private boolean isTypeLimitReached(
      RepoFileType type, EnumMap<RepoFileType, Integer> counters) {
    RepoAnalysisProperties.FileTypePriority priority = priorityConfig(type);
    int limit = priority != null ? priority.getMaxFiles() : 0;
    int current = counters.getOrDefault(type, 0);
    if (limit > 0 && current >= limit) {
      return true;
    }
    counters.put(type, current + 1);
    return false;
  }

  private RepoAnalysisProperties.FileTypePriority priorityConfig(RepoFileType type) {
    RepoAnalysisProperties.Priorities priorities = properties.getPriorities();
    if (priorities == null) {
      return defaultPriority();
    }
    return switch (type) {
      case TEST -> priorities.getTest();
      case INFRA -> priorities.getInfra();
      case DOC -> priorities.getDoc();
      case CODE -> priorities.getCode();
    };
  }

  private RepoAnalysisProperties.FileTypePriority defaultPriority() {
    RepoAnalysisProperties.FileTypePriority fallback = new RepoAnalysisProperties.FileTypePriority();
    fallback.setWeight(1.0);
    fallback.setMaxFiles(0);
    return fallback;
  }

  private double computePriorityWeight(
      RepoFileType fileType, long sizeBytes, Instant lastModified, long maxFileBytes) {
    RepoAnalysisProperties.FileTypePriority priority = priorityConfig(fileType);
    double base = priority != null && priority.getWeight() > 0 ? priority.getWeight() : 1.0d;
    double sizeFactor = 1.0d;
    if (maxFileBytes > 0L) {
      sizeFactor = 1.0d + Math.min(1.0d, (double) sizeBytes / (double) maxFileBytes);
    }
    double recencyFactor = computeRecencyFactor(lastModified);
    double weight = base * sizeFactor * recencyFactor;
    return Math.max(0.1d, weight);
  }

  private double computeRecencyFactor(Instant lastModified) {
    if (lastModified == null) {
      return 1.0d;
    }
    long ageDays = Math.max(0, Duration.between(lastModified, Instant.now()).toDays());
    if (ageDays <= 3) {
      return 1.6d;
    }
    if (ageDays <= 14) {
      return 1.3d;
    }
    if (ageDays <= 60) {
      return 1.1d;
    }
    if (ageDays >= 365) {
      return 0.8d;
    }
    return 1.0d;
  }

  private String extractExtension(String filename) {
    int idx = filename.lastIndexOf('.');
    if (idx == -1 || idx == filename.length() - 1) {
      return "";
    }
    return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
  }

  private record SegmentResult(
      String key,
      int segmentIndex,
      long bytesRead,
      int lineStart,
      int lineEnd,
      String summary,
      String content,
      boolean truncated,
      boolean completed,
      Instant readAt,
      String hash,
      boolean duplicate,
      List<String> tags) {}

  private record ComplexityHints(List<String> tags) {
    private static final ComplexityHints EMPTY = new ComplexityHints(List.of());
  }

  private enum RepoFileType {
    CODE,
    TEST,
    INFRA,
    DOC
  }
}
