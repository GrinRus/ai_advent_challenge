package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.chunking.Chunk;
import com.aiadvent.mcp.backend.github.rag.chunking.ChunkableFile;
import com.aiadvent.mcp.backend.github.rag.chunking.RepoRagChunker;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagVectorStoreAdapter;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RepoRagIndexService {

  private static final Logger log = LoggerFactory.getLogger(RepoRagIndexService.class);
  private static final int MAX_WARNINGS = 20;
  private static final Set<String> BINARY_EXTENSIONS =
      Set.of(
          "png",
          "jpg",
          "jpeg",
          "gif",
          "bin",
          "exe",
          "dll",
          "so",
          "dylib",
          "class",
          "jar",
          "zip",
          "tar",
          "gz",
          "pdf");

  private final TempWorkspaceService workspaceService;
  private final RepoRagVectorStoreAdapter vectorStoreAdapter;
  private final RepoRagFileStateRepository fileStateRepository;
  private final RepoRagChunker chunker;
  private final GitHubRagProperties properties;

  public RepoRagIndexService(
      TempWorkspaceService workspaceService,
      RepoRagVectorStoreAdapter vectorStoreAdapter,
      RepoRagFileStateRepository fileStateRepository,
      RepoRagChunker chunker,
      GitHubRagProperties properties) {
    this.workspaceService = workspaceService;
    this.vectorStoreAdapter = vectorStoreAdapter;
    this.fileStateRepository = fileStateRepository;
    this.chunker = chunker;
    this.properties = properties;
  }

  public IndexResult indexWorkspace(IndexRequest request) {
    Path root =
        workspaceService
            .findWorkspace(request.workspaceId())
            .map(TempWorkspaceService.Workspace::path)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unknown workspaceId: " + request.workspaceId()))
            .toAbsolutePath()
            .normalize();

    AtomicLong files = new AtomicLong();
    AtomicLong chunks = new AtomicLong();
    AtomicLong filesSkipped = new AtomicLong();
    AtomicLong filesDeleted = new AtomicLong();
    List<String> warnings = new ArrayList<>();
    Map<String, RepoRagFileStateEntity> stateByPath =
        fileStateRepository.findByNamespace(request.namespace()).stream()
            .collect(Collectors.toMap(RepoRagFileStateEntity::getFilePath, Function.identity()));
    Set<String> stalePaths = new HashSet<>(vectorStoreAdapter.listFilePaths(request.namespace()));
    stalePaths.addAll(stateByPath.keySet());

    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              Path relative = root.relativize(dir.toAbsolutePath().normalize());
              if (relative.toString().isEmpty()) {
                return FileVisitResult.CONTINUE;
              }
              String dirName = dir.getFileName().toString();
              if (shouldSkipDirectory(dirName)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (!attrs.isRegularFile() || Files.isHidden(file) || Files.isSymbolicLink(file)) {
                return FileVisitResult.CONTINUE;
              }
              Path relative = root.relativize(file.toAbsolutePath().normalize());
              String relativePath = relative.toString().replace('\\', '/');
              stalePaths.remove(relativePath);
              if (isBinaryFile(file)) {
                appendWarning(warnings, "Skipped binary file " + relativePath);
                return FileVisitResult.CONTINUE;
              }

              byte[] rawBytes;
              try {
                rawBytes = Files.readAllBytes(file);
              } catch (IOException ex) {
                appendWarning(
                    warnings, "Skipped file (unable to read) " + relativePath + ": " + ex.getMessage());
                log.warn("Failed to read {}: {}", relativePath, ex.getMessage());
                return FileVisitResult.CONTINUE;
              }

              String fileHash = hashBytes(rawBytes);
              RepoRagFileStateEntity existingState = stateByPath.get(relativePath);
              if (existingState != null && fileHash.equals(existingState.getFileHash())) {
                filesSkipped.incrementAndGet();
                return FileVisitResult.CONTINUE;
              }

              String content;
              try {
                content = decodeUtf8(rawBytes);
              } catch (CharacterCodingException ex) {
                appendWarning(
                    warnings, "Skipped non-text file " + relativePath + ": " + ex.getMessage());
                log.warn("Failed to decode {}: {}", relativePath, ex.getMessage());
                return FileVisitResult.CONTINUE;
              }

              ChunkableFile chunkableFile =
                  ChunkableFile.from(
                      file,
                      relativePath,
                      detectLanguage(file),
                      content);

              List<Chunk> fileChunks;
              try {
                fileChunks = chunker.chunk(chunkableFile);
              } catch (RuntimeException ex) {
                appendWarning(
                    warnings,
                    "Skipped file (chunking failed) " + relativePath + ": " + ex.getMessage());
                log.warn("Failed to chunk {}: {}", relativePath, ex.getMessage());
                return FileVisitResult.CONTINUE;
              }

              List<Document> fileDocuments =
                  buildDocuments(fileChunks, relativePath, request);

              boolean hasChunks = !fileDocuments.isEmpty();
              try {
                if (hasChunks) {
                  vectorStoreAdapter.replaceFile(
                      request.namespace(), relativePath, fileDocuments);
                  upsertFileState(
                      request.namespace(),
                      relativePath,
                      fileHash,
                      fileDocuments.size(),
                      stateByPath);
                  files.incrementAndGet();
                  chunks.addAndGet(fileDocuments.size());
                } else {
                  vectorStoreAdapter.deleteFile(request.namespace(), relativePath);
                  deleteFileState(request.namespace(), relativePath, stateByPath);
                  filesDeleted.incrementAndGet();
                }
                stalePaths.remove(relativePath);
              } catch (RuntimeException ex) {
                appendWarning(
                    warnings,
                    "Failed to store chunks for " + relativePath + ": " + ex.getMessage());
                log.warn("Failed to store chunks for {}: {}", relativePath, ex.getMessage());
              }
              return FileVisitResult.CONTINUE;
            }
          });
      for (String stalePath : stalePaths) {
        vectorStoreAdapter.deleteFile(request.namespace(), stalePath);
        deleteFileState(request.namespace(), stalePath, stateByPath);
        filesDeleted.incrementAndGet();
      }
      log.info(
          "Indexed repo {} with {} files and {} chunks (namespace={})",
          request.repoOwner(),
          files.get(),
          chunks.get(),
          request.namespace());
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to index workspace " + request.workspaceId(), ex);
    }

    return new IndexResult(
        files.get(), chunks.get(), filesSkipped.get(), filesDeleted.get(), List.copyOf(warnings));
  }

  private List<Document> buildDocuments(
      List<Chunk> fileChunks, String relativePath, IndexRequest request) {
    if (fileChunks.isEmpty()) {
      return List.of();
    }
    List<Document> documents = new ArrayList<>(fileChunks.size());
    for (int i = 0; i < fileChunks.size(); i++) {
      Chunk chunk = fileChunks.get(i);
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("namespace", request.namespace());
      metadata.put("repo_owner", request.repoOwner());
      metadata.put("repo_name", request.repoName());
      metadata.put("file_path", relativePath);
      metadata.put("chunk_index", i);
      metadata.put("line_start", chunk.lineStart());
      metadata.put("line_end", chunk.lineEnd());
      metadata.put("chunk_hash", chunk.hash());
      metadata.put("language", chunk.language());
      metadata.put("summary", chunk.summary());
      metadata.put("overlap_lines", chunk.overlapLines());
      metadata.put("span_hash", hashSpan(relativePath, chunk));
      if (StringUtils.hasText(chunk.parentSymbol())) {
        metadata.put("parent_symbol", chunk.parentSymbol());
      }
      if (request.sourceRef() != null) {
        metadata.put("source_ref", request.sourceRef());
      }
      if (request.fetchedAt() != null) {
        metadata.put("fetched_at", request.fetchedAt().toString());
      }
      Document document =
          Document.builder()
              .id(buildChunkId(relativePath, i, chunk.hash()))
              .text(chunk.text())
              .metadata(metadata)
              .build();
      documents.add(document);
    }
    return documents;
  }

  private void upsertFileState(
      String namespace,
      String relativePath,
      String fileHash,
      int chunkCount,
      Map<String, RepoRagFileStateEntity> cache) {
    RepoRagFileStateEntity entity =
        cache.computeIfAbsent(
            relativePath,
            path -> {
              RepoRagFileStateEntity fresh = new RepoRagFileStateEntity();
              fresh.setNamespace(namespace);
              fresh.setFilePath(path);
              return fresh;
            });
    entity.setFileHash(fileHash);
    entity.setChunkCount(chunkCount);
    RepoRagFileStateEntity saved = fileStateRepository.save(entity);
    cache.put(relativePath, saved);
  }

  private void deleteFileState(
      String namespace, String relativePath, Map<String, RepoRagFileStateEntity> cache) {
    fileStateRepository.deleteByNamespaceAndFilePath(namespace, relativePath);
    cache.remove(relativePath);
  }

  private String hashFile(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(file)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return bytesToHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm is not available", ex);
    }
  }

  private String buildChunkId(String relativePath, int chunkIndex, String chunkHash) {
    String source = relativePath + ":" + chunkIndex + ":" + chunkHash;
    return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private boolean shouldSkipDirectory(String dirName) {
    if (!StringUtils.hasText(dirName)) {
      return false;
    }
    String lower = dirName.toLowerCase(Locale.ROOT);
    return properties.getIgnore().getDirectories().stream()
        .map(entry -> entry.toLowerCase(Locale.ROOT))
        .anyMatch(lower::equals);
  }

  private boolean isBinaryFile(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
      if (BINARY_EXTENSIONS.contains(ext)) {
        return true;
      }
    }
    byte[] buffer = new byte[1024];
    try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
      int read = input.read(buffer);
      if (read <= 0) {
        return false;
      }
      int nonPrintable = 0;
      for (int i = 0; i < read; i++) {
        int b = buffer[i] & 0xFF;
        if (b == 0) {
          return true;
        }
        if (b < 0x09 || (b > 0x0A && b < 0x20)) {
          nonPrintable++;
        }
      }
      return nonPrintable > read * 0.3;
    } catch (IOException ex) {
      log.debug("Unable to inspect file {}, treating as binary: {}", file, ex.getMessage());
      return true;
    }
  }

  private void appendWarning(List<String> warnings, String warning) {
    if (warnings.size() >= MAX_WARNINGS) {
      return;
    }
    warnings.add(warning);
  }

  private String detectLanguage(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    if (dot <= 0) {
      return "plain";
    }
    String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
    return switch (ext) {
      case "java" -> "java";
      case "kt", "kts" -> "kotlin";
      case "js" -> "javascript";
      case "ts", "tsx" -> "typescript";
      case "py" -> "python";
      case "rs" -> "rust";
      case "go" -> "go";
      case "rb" -> "ruby";
      case "php" -> "php";
      case "cs" -> "csharp";
      case "cpp", "cxx", "hpp", "h" -> "cpp";
      case "json" -> "json";
      case "yml", "yaml" -> "yaml";
      case "md" -> "markdown";
      case "gradle" -> "gradle";
      default -> "plain";
    };
  }

  private String hashBytes(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(data);
      return bytesToHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm is not available", ex);
    }
  }

  private String hashSpan(String relativePath, Chunk chunk) {
    String payload = relativePath + ":" + chunk.lineStart() + ":" + chunk.lineEnd();
    return hashBytes(payload.getBytes(StandardCharsets.UTF_8));
  }

  private String decodeUtf8(byte[] data) throws CharacterCodingException {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPORT);
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    return decoder.decode(ByteBuffer.wrap(data)).toString();
  }

  private String bytesToHex(byte[] hash) {
    StringBuilder builder = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      builder.append(String.format("%02x", b));
    }
    return builder.toString();
  }

  public record IndexRequest(
      String repoOwner,
      String repoName,
      String workspaceId,
      String namespace,
      String sourceRef,
      String commitSha,
      long workspaceSizeBytes,
      Instant fetchedAt) {}

  public record IndexResult(
      long filesProcessed,
      long chunksProcessed,
      long filesSkipped,
      long filesDeleted,
      List<String> warnings) {}
}
