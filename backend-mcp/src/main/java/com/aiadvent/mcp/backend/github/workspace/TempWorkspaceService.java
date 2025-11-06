package com.aiadvent.mcp.backend.github.workspace;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class TempWorkspaceService implements InitializingBean, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(TempWorkspaceService.class);

  private static final String METADATA_FILE = ".workspace.json";

  private final GitHubBackendProperties properties;
  private final MeterRegistry meterRegistry;
  private final Counter createdCounter;
  private final Counter cleanupCounter;
  private final AtomicInteger activeGauge;
  private final ScheduledExecutorService cleanupExecutor;
  private final Map<String, WorkspaceRecord> workspaces;
  private final Path workspaceRoot;
  private final Duration ttl;
  private final Duration cleanupInterval;
  private final long sizeLimitBytes;

  public TempWorkspaceService(
      GitHubBackendProperties properties, @Nullable MeterRegistry meterRegistry) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.ttl =
        Optional.ofNullable(properties.getWorkspaceTtl()).orElse(Duration.ofHours(24));
    this.cleanupInterval =
        Optional.ofNullable(properties.getWorkspaceCleanupInterval())
            .orElse(Duration.ofMinutes(15));
    this.sizeLimitBytes =
        Optional.ofNullable(properties.getWorkspaceMaxSizeBytes())
            .filter(limit -> limit > 0)
            .orElse(2L * 1024 * 1024 * 1024); // default 2 GiB

    this.workspaceRoot =
        Optional.ofNullable(properties.getWorkspaceRoot())
            .map(Path::of)
            .orElse(Path.of("/var/tmp/aiadvent/mcp-workspaces"));

    MeterRegistry registry = meterRegistry;
    if (registry == null) {
      registry = new SimpleMeterRegistry();
    }
    this.meterRegistry = registry;
    this.createdCounter = this.meterRegistry.counter("github_workspace_created_total");
    this.cleanupCounter = this.meterRegistry.counter("github_workspace_cleanup_total");
    this.activeGauge = new AtomicInteger();
    this.meterRegistry.gauge("github_workspace_active", activeGauge);

    this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "workspace-cleanup");
      thread.setDaemon(true);
      return thread;
    });
    this.workspaces = new ConcurrentHashMap<>();
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    Files.createDirectories(workspaceRoot);
    loadExistingWorkspaces();
    scheduleCleanup();
  }

  @Override
  public void destroy() {
    cleanupExecutor.shutdownNow();
  }

  public Workspace createWorkspace(CreateWorkspaceRequest request) {
    Objects.requireNonNull(request, "request");
    String workspaceId = generateWorkspaceId();
    Path workspacePath = workspaceRoot.resolve(workspaceId).normalize();
    ensureUnderRoot(workspacePath);
    try {
      Files.createDirectories(workspacePath);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to create workspace directory %s".formatted(workspacePath), ex);
    }

    WorkspaceRecord record =
        WorkspaceRecord.newRecord(workspaceId, workspacePath, ttl, request);
    workspaces.put(workspaceId, record);
    activeGauge.incrementAndGet();
    writeMetadata(record);
    createdCounter.increment();
    log.info(
        "Created workspace {} for repo {} ref {} (requestId={}) at {}",
        workspaceId,
        record.sourceRepository(),
        record.sourceRef(),
        request.requestId(),
        workspacePath);
    return record.toWorkspace();
  }

  public Optional<Workspace> findWorkspace(String workspaceId) {
    if (workspaceId == null || workspaceId.isBlank()) {
      return Optional.empty();
    }
    WorkspaceRecord record = workspaces.get(workspaceId);
    if (record == null) {
      Path workspacePath = workspaceRoot.resolve(workspaceId);
      if (!Files.exists(workspacePath)) {
        return Optional.empty();
      }
      record = loadWorkspaceMetadata(workspaceId, workspacePath).orElse(null);
      if (record == null) {
        return Optional.empty();
      }
      workspaces.put(workspaceId, record);
      activeGauge.incrementAndGet();
    }
    record.touch();
    return Optional.of(record.toWorkspace());
  }

  public Path requireWorkspacePath(String workspaceId) {
    return findWorkspace(workspaceId)
        .map(Workspace::path)
        .orElseThrow(() -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));
  }

  public Workspace updateWorkspace(
      String workspaceId,
      @Nullable Long sizeBytes,
      @Nullable String commitSha,
      @Nullable List<String> keyFiles) {
    WorkspaceRecord record = workspaces.get(workspaceId);
    if (record == null) {
      throw new IllegalArgumentException("Unknown workspaceId: " + workspaceId);
    }
    if (sizeBytes != null) {
      record.setSizeBytes(sizeBytes);
    }
    if (commitSha != null) {
      record.setCommitSha(commitSha);
    }
    if (keyFiles != null) {
      record.setKeyFiles(keyFiles);
    }
    record.touch();
    writeMetadata(record);
    return record.toWorkspace();
  }

  public void cleanupExpired() {
    Instant now = Instant.now();
    List<String> expiredIds =
        workspaces.values().stream()
            .filter(record -> record.expiresAt().isBefore(now))
            .map(WorkspaceRecord::workspaceId)
            .collect(Collectors.toList());
    for (String workspaceId : expiredIds) {
      deleteWorkspace(workspaceId);
    }
  }

  public void deleteWorkspace(String workspaceId) {
    WorkspaceRecord record = workspaces.remove(workspaceId);
    Path workspacePath = workspaceRoot.resolve(workspaceId).normalize();
    ensureUnderRoot(workspacePath);
    try {
      if (Files.exists(workspacePath)) {
        deleteRecursively(workspacePath);
      }
    } catch (IOException ex) {
      log.warn("Failed to delete workspace {} at {}", workspaceId, workspacePath, ex);
    } finally {
      cleanupCounter.increment();
      activeGauge.updateAndGet(current -> Math.max(0, current - 1));
    }
  }

  public void ensureWithinLimit(long sizeBytes) {
    if (sizeBytes > sizeLimitBytes) {
      throw new IllegalStateException(
          "Workspace size %d exceeds configured limit %d".formatted(sizeBytes, sizeLimitBytes));
    }
  }

  public Path getWorkspaceRoot() {
    return workspaceRoot;
  }

  public long getSizeLimitBytes() {
    return sizeLimitBytes;
  }

  public Duration getTtl() {
    return ttl;
  }

  private void scheduleCleanup() {
    cleanupExecutor.scheduleAtFixedRate(
        () -> {
          try {
            cleanupExpired();
          } catch (Exception ex) {
            log.warn("Scheduled cleanup failed: {}", ex.getMessage(), ex);
          }
        },
        cleanupInterval.toSeconds(),
        cleanupInterval.toSeconds(),
        TimeUnit.SECONDS);
  }

  private void loadExistingWorkspaces() {
    try (var paths = Files.list(workspaceRoot)) {
      paths
          .filter(Files::isDirectory)
          .forEach(
              path -> {
                String workspaceId = path.getFileName().toString();
                loadWorkspaceMetadata(workspaceId, path)
                    .ifPresent(
                        record -> {
                          workspaces.put(workspaceId, record);
                          activeGauge.incrementAndGet();
                        });
              });
    } catch (IOException ex) {
      log.warn("Failed to scan existing workspaces in {}", workspaceRoot, ex);
    }
  }

  private Optional<WorkspaceRecord> loadWorkspaceMetadata(String workspaceId, Path workspacePath) {
    Path metadataPath = workspacePath.resolve(METADATA_FILE);
    if (!Files.exists(metadataPath)) {
      try {
        Instant createdAt = Files.readAttributes(workspacePath, BasicFileAttributes.class).creationTime().toInstant();
        WorkspaceRecord record =
            WorkspaceRecord.fromMetadata(
                workspaceId,
                workspacePath,
                ttl,
                createdAt,
                createdAt.plus(ttl),
                null,
                null,
                null,
                null,
                null);
        writeMetadata(record);
        return Optional.of(record);
      } catch (IOException ex) {
        log.warn("Unable to read metadata for workspace {}: {}", workspaceId, ex.getMessage());
        return Optional.empty();
      }
    }
    try (InputStream in = Files.newInputStream(metadataPath)) {
      WorkspaceMetadata metadata = WorkspaceMetadata.read(in);
      WorkspaceRecord record =
          WorkspaceRecord.fromMetadata(
              workspaceId,
              workspacePath,
              ttl,
              metadata.createdAt(),
              metadata.expiresAt(),
              metadata.requestId(),
              metadata.source(),
              metadata.sizeBytes(),
              metadata.commitSha(),
              metadata.keyFiles());
      if (record.expiresAt().isBefore(Instant.now())) {
        deleteWorkspace(workspaceId);
        return Optional.empty();
      }
      return Optional.of(record);
    } catch (IOException ex) {
      log.warn("Failed to read metadata for workspace {}: {}", workspaceId, ex.getMessage(), ex);
      return Optional.empty();
    }
  }

  private void writeMetadata(WorkspaceRecord record) {
    Path metadataPath = record.path().resolve(METADATA_FILE);
    WorkspaceMetadata metadata =
        new WorkspaceMetadata(
            record.createdAt(),
            record.expiresAt(),
            record.requestId(),
            new WorkspaceSource(record.sourceRepository(), record.sourceRef()),
            record.sizeBytes(),
            record.commitSha(),
            record.keyFiles());
    try (OutputStream out = Files.newOutputStream(metadataPath)) {
      WorkspaceMetadata.write(metadata, out);
    } catch (IOException ex) {
      log.warn("Failed to persist metadata for workspace {}: {}", record.workspaceId(), ex.getMessage(), ex);
    }
    ensureMetadataExcluded(record.path());
  }

  private void ensureMetadataExcluded(Path workspacePath) {
    Path gitDir = workspacePath.resolve(".git");
    if (!Files.isDirectory(gitDir)) {
      return;
    }
    Path excludeFile = gitDir.resolve("info").resolve("exclude");
    try {
      Path excludeDir = excludeFile.getParent();
      if (excludeDir != null) {
        Files.createDirectories(excludeDir);
      }
      List<String> existing = Collections.emptyList();
      if (Files.exists(excludeFile)) {
        existing = Files.readAllLines(excludeFile, StandardCharsets.UTF_8);
        boolean alreadyPresent =
            existing.stream().map(String::trim).anyMatch(line -> METADATA_FILE.equals(line));
        if (alreadyPresent) {
          return;
        }
      }
      boolean needsNewline = Files.exists(excludeFile) && Files.size(excludeFile) > 0;
      try (BufferedWriter writer =
          Files.newBufferedWriter(
              excludeFile,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND)) {
        if (needsNewline) {
          writer.newLine();
        }
        writer.write(METADATA_FILE);
        writer.newLine();
      }
    } catch (IOException ex) {
      log.debug(
          "Failed to add {} to git exclude for workspace {}: {}",
          METADATA_FILE,
          workspacePath,
          ex.getMessage());
    }
  }

  private void deleteRecursively(Path path) throws IOException {
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private void ensureUnderRoot(Path workspacePath) {
    Path normalized = workspacePath.toAbsolutePath().normalize();
    Path rootNormalized = workspaceRoot.toAbsolutePath().normalize();
    if (!normalized.startsWith(rootNormalized)) {
      throw new IllegalArgumentException(
          "Workspace path must reside under root " + workspaceRoot + " but was " + workspacePath);
    }
  }

  private String generateWorkspaceId() {
    return "workspace-" + UUID.randomUUID();
  }

  private static final class WorkspaceRecord {

    private final String workspaceId;
    private final Path path;
    private final Duration ttl;
    private final String requestId;
    private final String sourceRepository;
    private final String sourceRef;
    private final Instant createdAt;
    private Instant expiresAt;
    private volatile long sizeBytes;
    private volatile String commitSha;
    private volatile List<String> keyFiles;

    private WorkspaceRecord(
        String workspaceId,
        Path path,
        Duration ttl,
        String requestId,
        String sourceRepository,
        String sourceRef,
        Instant createdAt,
        Instant expiresAt,
        long sizeBytes,
        String commitSha,
        List<String> keyFiles) {
      this.workspaceId = workspaceId;
      this.path = path;
      this.ttl = ttl;
      this.requestId = requestId;
      this.sourceRepository = sourceRepository;
      this.sourceRef = sourceRef;
      this.createdAt = createdAt;
      this.expiresAt = expiresAt;
      this.sizeBytes = sizeBytes;
      this.commitSha = commitSha;
      this.keyFiles = keyFiles != null ? new ArrayList<>(keyFiles) : new ArrayList<>();
    }

    static WorkspaceRecord newRecord(
        String workspaceId, Path path, Duration ttl, CreateWorkspaceRequest request) {
      Instant now = Instant.now();
      return new WorkspaceRecord(
          workspaceId,
          path,
          ttl,
          request.requestId(),
          request.repositoryFullName(),
          request.ref(),
          now,
          now.plus(ttl),
          0L,
          null,
          Collections.emptyList());
    }

    static WorkspaceRecord fromMetadata(
        String workspaceId,
        Path path,
        Duration ttl,
        Instant createdAt,
        Instant expiresAt,
        String requestId,
        @Nullable WorkspaceSource source,
        @Nullable Long sizeBytes,
        @Nullable String commitSha,
        @Nullable List<String> keyFiles) {
      WorkspaceRecord record =
          new WorkspaceRecord(
              workspaceId,
              path,
              ttl,
              requestId,
              source != null ? source.repositoryFullName() : null,
              source != null ? source.ref() : null,
              createdAt,
              expiresAt != null ? expiresAt : createdAt.plus(ttl),
              sizeBytes != null ? sizeBytes : 0L,
              commitSha,
              keyFiles);
      return record;
    }

    String workspaceId() {
      return workspaceId;
    }

    Path path() {
      return path;
    }

    Instant createdAt() {
      return createdAt;
    }

    Instant expiresAt() {
      return expiresAt;
    }

    void touch() {
      this.expiresAt = Instant.now().plus(ttl);
    }

    String requestId() {
      return requestId;
    }

    String sourceRepository() {
      return sourceRepository;
    }

    String sourceRef() {
      return sourceRef;
    }

    long sizeBytes() {
      return sizeBytes;
    }

    void setSizeBytes(long sizeBytes) {
      this.sizeBytes = Math.max(sizeBytes, 0);
    }

    String commitSha() {
      return commitSha;
    }

    void setCommitSha(@Nullable String commitSha) {
      this.commitSha = commitSha;
    }

    List<String> keyFiles() {
      return keyFiles != null ? Collections.unmodifiableList(keyFiles) : Collections.emptyList();
    }

    void setKeyFiles(List<String> keyFiles) {
      this.keyFiles = keyFiles != null ? new ArrayList<>(keyFiles) : new ArrayList<>();
    }

    Workspace toWorkspace() {
      return new Workspace(
          workspaceId,
          path,
          createdAt,
          expiresAt,
          requestId,
          sourceRepository,
          sourceRef,
          sizeBytes,
          commitSha,
          keyFiles());
    }
  }

  public record Workspace(
      String workspaceId,
      Path path,
      Instant createdAt,
      Instant expiresAt,
      String requestId,
      String repositoryFullName,
      String ref,
      long sizeBytes,
      String commitSha,
      List<String> keyFiles) {}

  public record CreateWorkspaceRequest(
      String repositoryFullName, String ref, String requestId) {}

  private record WorkspaceMetadata(
      Instant createdAt,
      Instant expiresAt,
      String requestId,
      WorkspaceSource source,
      Long sizeBytes,
      String commitSha,
      List<String> keyFiles) {

    static WorkspaceMetadata read(InputStream inputStream) throws IOException {
      return WorkspaceMetadataSerde.MAPPER.readValue(inputStream, WorkspaceMetadata.class);
    }

    static void write(WorkspaceMetadata metadata, OutputStream outputStream) throws IOException {
      WorkspaceMetadataSerde.MAPPER.writeValue(outputStream, metadata);
    }
  }

  private record WorkspaceSource(String repositoryFullName, String ref) {}

  private static final class WorkspaceMetadataSerde {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules();

    private WorkspaceMetadataSerde() {}
  }
}
