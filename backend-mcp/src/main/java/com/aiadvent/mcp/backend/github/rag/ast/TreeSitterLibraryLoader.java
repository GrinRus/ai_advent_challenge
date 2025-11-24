package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import io.github.treesitter.jtreesitter.Language;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TreeSitterLibraryLoader {

  private static final String CORE_LIBRARY_BASE = "java-tree-sitter";

  private static final Logger log = LoggerFactory.getLogger(TreeSitterLibraryLoader.class);

  private final GitHubRagProperties properties;
  private final ResourceLoader resourceLoader;
  private final TreeSitterPlatform platform;
  private final Map<String, LoadedLibrary> loadedLibraries = new ConcurrentHashMap<>();
  private volatile boolean coreLoaded = false;

  public TreeSitterLibraryLoader(GitHubRagProperties properties, ResourceLoader resourceLoader) {
    this.properties = properties;
    this.resourceLoader = resourceLoader;
    this.platform = TreeSitterPlatform.detect();
  }

  public Optional<LoadedLibrary> loadLanguage(String languageId) {
    ensureCoreLibraryLoaded();
    if (!properties.getAst().isEnabled()) {
      return Optional.empty();
    }
    if (!properties.getAst().getLanguages().contains(languageId)) {
      log.debug("Tree-sitter language {} is not enabled", languageId);
      return Optional.empty();
    }
    return Optional.ofNullable(
        loadedLibraries.computeIfAbsent(languageId, key -> loadInternal(languageId)));
  }

  /**
   * Ensures that tree-sitter runtime is available. For jtreesitter we rely on per-language shared
   * libraries that already link against the runtime, so this is a no-op.
   *
   * @return true if runtime can be used.
   */
  public boolean ensureCoreLibraryLoaded() {
    if (coreLoaded) {
      return true;
    }
    Resource resource = resolveCoreResource();
    if (resource == null || !resource.exists()) {
      log.warn(
          "Tree-sitter core library not found for platform {}-{} at configured path",
          platform.os(),
          platform.arch());
      return false;
    }
    try (InputStream input = resource.getInputStream()) {
      Path tempFile =
          Files.createTempFile("treesitter-core-", platform.libraryExtension());
      Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
      tempFile.toFile().deleteOnExit();
      SymbolLookup.libraryLookup(tempFile, Arena.global());
      coreLoaded = true;
      log.info(
          "Loaded Tree-sitter core library {} for platform {}-{}",
          tempFile.toAbsolutePath(),
          platform.os(),
          platform.arch());
      return true;
    } catch (IOException | UnsatisfiedLinkError | RuntimeException ex) {
      log.warn("Failed to load Tree-sitter core library: {}", ex.getMessage());
      return false;
    }
  }

  private LoadedLibrary loadInternal(String languageId) {
    TreeSitterLanguage language =
        TreeSitterLanguage.fromId(languageId)
            .orElse(null);
    if (language == null) {
      log.warn("Tree-sitter does not support language '{}', falling back to heuristics", languageId);
      return null;
    }
    Resource resource = resolveResource(language);
    if (resource == null || !resource.exists()) {
      log.warn(
          "Tree-sitter library for language={} (platform {}-{}/{}) not found at configured path",
          languageId,
          platform.os(),
          platform.arch(),
          language.resolveLibraryFile(platform));
      return null;
    }
    try (InputStream input = resource.getInputStream()) {
      Path tempFile =
          Files.createTempFile(
              "treesitter-" + language.id() + "-", platform.libraryExtension());
      Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
      tempFile.toFile().deleteOnExit();
      SymbolLookup lookup = SymbolLookup.libraryLookup(tempFile, Arena.global());
      String symbolName = "tree_sitter_" + language.id();
      Language tsLanguage = Language.load(lookup, symbolName);
      log.info(
          "Loaded Tree-sitter library {} for language {} ({}-{})",
          tempFile.toAbsolutePath(),
          language.id(),
          platform.os(),
          platform.arch());
      return new LoadedLibrary(language, tempFile, tsLanguage);
    } catch (IOException | UnsatisfiedLinkError | RuntimeException ex) {
      log.warn("Failed to load Tree-sitter library for {}: {}", languageId, ex.getMessage());
      return null;
    }
  }

  private Resource resolveResource(TreeSitterLanguage language) {
    String configuredPath = properties.getAst().getLibraryPath();
    String basePath;
    boolean classpath;
    if (!StringUtils.hasText(configuredPath) || configuredPath.startsWith("classpath:")) {
      classpath = true;
      basePath =
          StringUtils.hasText(configuredPath)
              ? configuredPath.substring("classpath:".length())
              : "treesitter";
    } else {
      classpath = false;
      basePath = configuredPath;
    }
    String resourcePath =
        normalize(basePath)
            + platform.os()
            + "/"
            + platform.arch()
            + "/"
            + language.resolveLibraryFile(platform);
    if (classpath) {
      return new ClassPathResource(resourcePath);
    }
    Path fullPath = Path.of(basePath, platform.os(), platform.arch(), language.resolveLibraryFile(platform));
    return new FileSystemResource(fullPath);
  }

  private Resource resolveCoreResource() {
    String configuredPath = properties.getAst().getLibraryPath();
    String basePath;
    boolean classpath;
    if (!StringUtils.hasText(configuredPath) || configuredPath.startsWith("classpath:")) {
      classpath = true;
      basePath =
          StringUtils.hasText(configuredPath)
              ? configuredPath.substring("classpath:".length())
              : "treesitter";
    } else {
      classpath = false;
      basePath = configuredPath;
    }
    String resourcePath =
        normalize(basePath)
            + platform.os()
            + "/"
            + platform.arch()
            + "/"
            + platform.libraryFileName(CORE_LIBRARY_BASE);
    if (classpath) {
      return new ClassPathResource(resourcePath);
    }
    Path fullPath =
        Path.of(basePath, platform.os(), platform.arch(), platform.libraryFileName(CORE_LIBRARY_BASE));
    return new FileSystemResource(fullPath);
  }

  private String normalize(String basePath) {
    String normalized = basePath;
    if (!normalized.endsWith("/")) {
      normalized = normalized + "/";
    }
    if (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  public record LoadedLibrary(TreeSitterLanguage language, Path path, Language languageHandle) {}
}
