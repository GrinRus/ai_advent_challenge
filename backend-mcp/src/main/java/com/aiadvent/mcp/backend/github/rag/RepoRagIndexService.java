package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagVectorStoreAdapter;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
  private final GitHubRagProperties properties;

  public RepoRagIndexService(
      TempWorkspaceService workspaceService,
      RepoRagVectorStoreAdapter vectorStoreAdapter,
      GitHubRagProperties properties) {
    this.workspaceService = workspaceService;
    this.vectorStoreAdapter = vectorStoreAdapter;
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
    List<String> warnings = new ArrayList<>();
    Set<String> stalePaths = new HashSet<>(vectorStoreAdapter.listFilePaths(request.namespace()));

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

              List<Chunk> fileChunks;
              try {
                fileChunks = chunkFile(file);
              } catch (IOException ex) {
                appendWarning(
                    warnings, "Skipped broken file " + relativePath + ": " + ex.getMessage());
                log.warn("Failed to read {}: {}", relativePath, ex.getMessage());
                return FileVisitResult.CONTINUE;
              }

              List<Document> fileDocuments =
                  buildDocuments(fileChunks, relativePath, request);

              boolean hasChunks = !fileDocuments.isEmpty();
              try {
                vectorStoreAdapter.replaceFile(
                    request.namespace(), relativePath, fileDocuments);
                if (hasChunks) {
                  files.incrementAndGet();
                  chunks.addAndGet(fileDocuments.size());
                }
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

    return new IndexResult(files.get(), chunks.get(), List.copyOf(warnings));
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
      if (request.sourceRef() != null) {
        metadata.put("source_ref", request.sourceRef());
      }
      if (request.fetchedAt() != null) {
        metadata.put("fetched_at", request.fetchedAt().toString());
      }
      Document document =
          Document.builder()
              .id(buildChunkId(relativePath, i, chunk.hash()))
              .text(chunk.content())
              .metadata(metadata)
              .build();
      documents.add(document);
    }
    return documents;
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

  List<Chunk> chunkFile(Path file) throws IOException {
    List<Chunk> chunks = new ArrayList<>();
    int maxBytes = properties.getChunk().getMaxBytes();
    int maxLines = properties.getChunk().getMaxLines();
    int lineNumber = 0;
    int chunkStart = 1;
    String language = detectLanguage(file);

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
      StringBuilder builder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (builder.length() + line.length() + 1 > maxBytes || (lineNumber - chunkStart) >= maxLines) {
          if (builder.length() > 0) {
            chunks.add(toChunk(builder.toString(), chunkStart, lineNumber - 1, language));
            builder.setLength(0);
          }
          chunkStart = lineNumber;
        }
        builder.append(line).append('\n');
      }
      if (builder.length() > 0) {
        chunks.add(toChunk(builder.toString(), chunkStart, lineNumber, language));
      }
    }
    return chunks;
  }

  private Chunk toChunk(String content, int lineStart, int lineEnd, String language) {
    String trimmed = content.trim();
    String summary =
        trimmed.lines().limit(2).collect(Collectors.joining(" ")).trim();
    String hash = sha256(trimmed);
    return new Chunk(trimmed, lineStart, lineEnd, language, summary, hash);
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

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm is not available", ex);
    }
  }

  public record IndexRequest(
      String repoOwner,
      String repoName,
      String workspaceId,
      String namespace,
      String sourceRef,
      Instant fetchedAt) {}

  public record IndexResult(long filesProcessed, long chunksProcessed, List<String> warnings) {}

  private record Chunk(
      String content, int lineStart, int lineEnd, String language, String summary, String hash) {}
}
