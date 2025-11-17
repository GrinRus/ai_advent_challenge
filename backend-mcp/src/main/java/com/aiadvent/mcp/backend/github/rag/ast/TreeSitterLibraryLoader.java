package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.io.IOException;
import java.io.InputStream;
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

  private static final Logger log = LoggerFactory.getLogger(TreeSitterLibraryLoader.class);

  private final GitHubRagProperties properties;
  private final ResourceLoader resourceLoader;
  private final TreeSitterPlatform platform;
  private final Map<String, LoadedLibrary> loadedLibraries = new ConcurrentHashMap<>();

  public TreeSitterLibraryLoader(GitHubRagProperties properties, ResourceLoader resourceLoader) {
    this.properties = properties;
    this.resourceLoader = resourceLoader;
    this.platform = TreeSitterPlatform.detect();
  }

  public Optional<LoadedLibrary> loadLanguage(String languageId) {
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
      System.load(tempFile.toAbsolutePath().toString());
      log.info(
          "Loaded Tree-sitter library {} for language {} ({}-{})",
          tempFile.toAbsolutePath(),
          language.id(),
          platform.os(),
          platform.arch());
      return new LoadedLibrary(language, tempFile);
    } catch (IOException | UnsatisfiedLinkError ex) {
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

  public record LoadedLibrary(TreeSitterLanguage language, Path path) {}
}
