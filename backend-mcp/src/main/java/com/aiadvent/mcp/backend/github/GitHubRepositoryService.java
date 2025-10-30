package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GHTreeEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GitHubRepositoryService {

  private static final Logger log = LoggerFactory.getLogger(GitHubRepositoryService.class);

  private final GitHubClientExecutor executor;
  private final GitHubBackendProperties properties;

  private final Map<String, CachedTree> treeCache = new ConcurrentHashMap<>();
  private final Map<String, CachedFile> fileCache = new ConcurrentHashMap<>();

  GitHubRepositoryService(
      GitHubClientExecutor executor, GitHubBackendProperties properties) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  ListRepositoryTreeResult listRepositoryTree(ListRepositoryTreeInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    String normalizedPath = normalizePath(input.path());
    boolean recursive = Boolean.TRUE.equals(input.recursive());
    int requestedDepth = Optional.ofNullable(input.maxDepth()).orElse(properties.getTreeMaxDepth());
    int maxEntries = Optional.ofNullable(input.maxEntries()).orElse(properties.getTreeMaxEntries());
    maxEntries = Math.max(1, Math.min(maxEntries, properties.getTreeMaxEntries()));

    TreeData treeData = loadTree(repository, requestedDepth);
    FilteredTree filtered =
        filterTree(treeData.entries(), normalizedPath, recursive, maxEntries);
    boolean truncated = treeData.truncated() || filtered.reachedLimit();

    return new ListRepositoryTreeResult(
        repository, treeData.resolvedRef(), filtered.entries(), truncated);
  }

  ReadFileResult readFile(ReadFileInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    String normalizedPath = normalizePath(input.path());
    if (!StringUtils.hasText(normalizedPath)) {
      throw new IllegalArgumentException("File path must not be blank");
    }

    FileData data = loadFile(repository, normalizedPath);
    return new ReadFileResult(repository, data.ref(), data.file());
  }

  private TreeData loadTree(RepositoryRef repository, int requestedDepth) {
    int depth = Math.max(1, Math.min(10, requestedDepth));
    String cacheKey = treeCacheKey(repository, depth);
    CachedTree cached = treeCache.get(cacheKey);
    if (cached != null && !cached.isExpired()) {
      return cached.payload();
    }

    TreeData loaded = executor.execute(github -> fetchTree(github, repository, depth));
    treeCache.put(cacheKey, new CachedTree(loaded, expiry(properties.getTreeCacheTtl())));
    return loaded;
  }

  private FileData loadFile(RepositoryRef repository, String path) {
    String cacheKey = fileCacheKey(repository, path);
    CachedFile cached = fileCache.get(cacheKey);
    if (cached != null && !cached.isExpired()) {
      return cached.payload();
    }
    FileData loaded = executor.execute(github -> fetchFile(github, repository, path));
    fileCache.put(cacheKey, new CachedFile(loaded, expiry(properties.getFileCacheTtl())));
    return loaded;
  }

  private TreeData fetchTree(org.kohsuke.github.GitHub github, RepositoryRef repository, int depth) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      String ref = repository.ref();
      String commitSha = resolveCommitSha(repo, ref);
      GHTree tree = repo.getTreeRecursive(commitSha, depth);
      List<TreeEntry> entries =
          tree.getTree().stream()
              .map(entry -> toTreeEntry(repository, entry))
              .sorted(Comparator.comparing(TreeEntry::path))
              .toList();
      return new TreeData(repository.withRef(ref), commitSha, entries, tree.isTruncated());
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to fetch repository tree for %s".formatted(repository.fullName()), ex);
    }
  }

  private FileData fetchFile(org.kohsuke.github.GitHub github, RepositoryRef repository, String path) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      GHContent content = repo.getFileContent(path, repository.ref());
      if (content == null) {
        throw new GitHubClientException(
            "GitHub returned null content for %s/%s".formatted(repository.fullName(), path));
      }
      long fileSize = content.getSize();
      Long maxSize = properties.getFileMaxSizeBytes();
      if (maxSize != null && fileSize > maxSize) {
        throw new GitHubClientException(
            "File size %d exceeds configured limit %d".formatted(fileSize, maxSize));
      }
      byte[] rawBytes = readContent(content);
      String base64 = Base64.getEncoder().encodeToString(rawBytes);
      String text = decodeUtf8(rawBytes);
      RepositoryFile file =
          new RepositoryFile(
              content.getPath(),
              content.getSha(),
              fileSize,
              content.getEncoding(),
              content.getDownloadUrl(),
              base64,
              text);
      return new FileData(repository.withRef(repository.ref()), repository.ref(), file);
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to fetch file %s from %s".formatted(path, repository.fullName()), ex);
    }
  }

  private byte[] readContent(GHContent content) throws IOException {
    try (var stream = content.read()) {
      byte[] bytes = stream.readAllBytes();
      if (bytes.length > 0) {
        return bytes;
      }
    }
    String payload = content.getContent();
    if (!StringUtils.hasText(payload)) {
      return new byte[0];
    }
    if ("base64".equalsIgnoreCase(content.getEncoding())) {
      return Base64.getDecoder().decode(payload);
    }
    return payload.getBytes(StandardCharsets.UTF_8);
  }

  private RepositoryRef normalizeRepository(RepositoryRef repo) {
    if (repo == null) {
      throw new IllegalArgumentException("Repository reference must be provided");
    }
    if (!StringUtils.hasText(repo.owner()) || !StringUtils.hasText(repo.name())) {
      throw new IllegalArgumentException("Repository owner and name must be provided");
    }
    String owner = repo.owner().trim();
    String name = repo.name().trim();
    String ref = StringUtils.hasText(repo.ref()) ? repo.ref().trim() : "heads/main";
    return new RepositoryRef(owner, name, ref);
  }

  private String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return "";
    }
    String normalized = path.trim();
    if (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private TreeEntry toTreeEntry(RepositoryRef repository, GHTreeEntry entry) {
    String type = entry.getType();
    TreeEntryType entryType = mapType(type);
    return new TreeEntry(
        entry.getPath(),
        entryType,
        entry.getSha(),
        entry.getSize());
  }

  private FilteredTree filterTree(
      List<TreeEntry> entries, String path, boolean recursive, int maxEntries) {
    List<TreeEntry> filtered = new ArrayList<>();
    boolean reachedLimit = false;
    if (!StringUtils.hasText(path)) {
      for (TreeEntry entry : entries) {
        if (!recursive && entry.path().contains("/")) {
          continue;
        }
        filtered.add(entry);
        if (filtered.size() >= maxEntries) {
          reachedLimit = true;
          break;
        }
      }
      return new FilteredTree(List.copyOf(filtered), reachedLimit);
    }
    String prefix = path + "/";
    for (TreeEntry entry : entries) {
      if (!entry.path().equals(path) && !entry.path().startsWith(prefix)) {
        continue;
      }
      if (!recursive) {
        String relative = entry.path().equals(path) ? "" : entry.path().substring(prefix.length());
        if (relative.contains("/")) {
          continue;
        }
      }
      filtered.add(entry);
      if (filtered.size() >= maxEntries) {
        reachedLimit = true;
        break;
      }
    }
    return new FilteredTree(List.copyOf(filtered), reachedLimit);
  }

  private TreeEntryType mapType(String type) {
    if (!StringUtils.hasText(type)) {
      return TreeEntryType.UNKNOWN;
    }
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "tree" -> TreeEntryType.DIRECTORY;
      case "blob" -> TreeEntryType.FILE;
      case "commit" -> TreeEntryType.SUBMODULE;
      case "symlink" -> TreeEntryType.SYMLINK;
      default -> TreeEntryType.UNKNOWN;
    };
  }

  private String resolveCommitSha(GHRepository repository, String ref) throws IOException {
    if (!StringUtils.hasText(ref)) {
      GHRef defaultRef = repository.getRef("heads/" + repository.getDefaultBranch());
      return defaultRef.getObject().getSha();
    }
    String trimmed = ref.trim();
    if (trimmed.startsWith("refs/")) {
      GHRef ghRef = repository.getRef(trimmed);
      return ghRef.getObject().getSha();
    }
    if (trimmed.matches("[0-9a-fA-F]{40}")) {
      return trimmed;
    }
    try {
      GHRef ghRef = repository.getRef("heads/" + trimmed);
      return ghRef.getObject().getSha();
    } catch (IOException ex) {
      log.debug("Failed to resolve ref '{}' as branch, attempting commit lookup", trimmed, ex);
      return repository.getCommit(trimmed).getSHA1();
    }
  }

  private Duration safeTtl(Duration ttl, Duration defaultTtl) {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      return defaultTtl;
    }
    return ttl;
  }

  private Instant expiry(Duration ttl) {
    Duration effective = safeTtl(ttl, Duration.ofMinutes(1));
    return Instant.now().plus(effective);
  }

  private String decodeUtf8(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return "";
    }
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPORT);
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException ex) {
      log.debug("File content is not valid UTF-8: {}", ex.getMessage());
      return null;
    }
  }

  private String treeCacheKey(RepositoryRef repository, int depth) {
    return repository.fullName().toLowerCase(Locale.ROOT) + "@" + repository.ref() + ":d" + depth;
  }

  private String fileCacheKey(RepositoryRef repository, String path) {
    return repository.fullName().toLowerCase(Locale.ROOT) + "@" + repository.ref() + ":f:" + path;
  }

  record RepositoryRef(String owner, String name, String ref) {
    RepositoryRef withRef(String ref) {
      return new RepositoryRef(owner, name, ref);
    }

    String fullName() {
      return owner + "/" + name;
    }
  }

  record ListRepositoryTreeInput(
      RepositoryRef repository, String path, Boolean recursive, Integer maxDepth, Integer maxEntries) {}

  record ListRepositoryTreeResult(
      RepositoryRef repository, String resolvedRef, List<TreeEntry> entries, boolean truncated) {}

  record TreeEntry(String path, TreeEntryType type, String sha, long size) {}

  enum TreeEntryType {
    FILE,
    DIRECTORY,
    SUBMODULE,
    SYMLINK,
    UNKNOWN
  }

  record ReadFileInput(RepositoryRef repository, String path) {}

  record ReadFileResult(RepositoryRef repository, String resolvedRef, RepositoryFile file) {}

  record RepositoryFile(
      String path,
      String sha,
      long size,
      String encoding,
      String downloadUrl,
      String contentBase64,
      String textContent) {}

  private record TreeData(RepositoryRef repository, String resolvedRef, List<TreeEntry> entries, boolean truncated) {}

  private record FileData(RepositoryRef repository, String ref, RepositoryFile file) {}

  private record FilteredTree(List<TreeEntry> entries, boolean reachedLimit) {}

  private record CachedTree(TreeData payload, Instant expiresAt) {
    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }

  private record CachedFile(FileData payload, Instant expiresAt) {
    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }
}
