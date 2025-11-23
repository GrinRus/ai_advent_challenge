package com.aiadvent.mcp.backend.github.rag.ast;

import io.github.treesitter.jtreesitter.NativeLibraryLookup;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native library loader for Tree-sitter. Loads core + language libs from either filesystem
 * (github.rag.ast.library-path or GITHUB_RAG_AST_LIBRARY_PATH) or classpath resources
 * under treesitter/<os>/<arch>/libtree-sitter-*.*
 *
 * Registered via META-INF/services/io.github.treesitter.jtreesitter.NativeLibraryLookup so
 * jtreesitter can discover it through ServiceLoader.
 */
public class TreeSitterNativeLibraryLookup implements NativeLibraryLookup {

  private static final Logger log = LoggerFactory.getLogger(TreeSitterNativeLibraryLookup.class);

  private static final List<String> LIBRARIES =
      List.of(
          "tree-sitter",
          "java-tree-sitter",
          "tree-sitter-java",
          "tree-sitter-kotlin",
          "tree-sitter-typescript",
          "tree-sitter-javascript",
          "tree-sitter-python",
          "tree-sitter-go");

  private static final AtomicBoolean loaded = new AtomicBoolean(false);

  @Override
  public SymbolLookup get(Arena arena) {
    if (loaded.compareAndSet(false, true)) {
      loadAll();
    }
    // Libraries are already loaded into the classloader, so return loaderLookup.
    return SymbolLookup.loaderLookup();
  }

  private void loadAll() {
    String configured =
        firstNonEmpty(
            System.getProperty("github.rag.ast.library-path"),
            System.getenv("GITHUB_RAG_AST_LIBRARY_PATH"),
            "classpath:treesitter");
    String os = normalizeOs();
    String arch = normalizeArch();
    if (configured.startsWith("classpath:")) {
      String base = configured.substring("classpath:".length());
      loadFromClasspath(base, os, arch);
    } else {
      loadFromFilesystem(Paths.get(configured), os, arch);
    }
  }

  private void loadFromFilesystem(Path baseDir, String os, String arch) {
    List<String> loadedPaths = new ArrayList<>();
    for (String lib : LIBRARIES) {
      String fileName = System.mapLibraryName(lib);
      Path candidate = baseDir.resolve(os).resolve(arch).resolve(fileName);
      if (!Files.exists(candidate)) {
        log.debug("Tree-sitter library missing on filesystem: {}", candidate);
        continue;
      }
      System.load(candidate.toAbsolutePath().toString());
      loadedPaths.add(candidate.toString());
    }
    if (loadedPaths.isEmpty()) {
      log.warn(
          "No Tree-sitter libraries loaded from filesystem path {} (os={}, arch={})",
          baseDir,
          os,
          arch);
    } else {
      log.info("Loaded Tree-sitter libraries from filesystem: {}", loadedPaths);
    }
  }

  private void loadFromClasspath(String base, String os, String arch) {
    List<String> loadedPaths = new ArrayList<>();
    ClassLoader cl = TreeSitterNativeLibraryLookup.class.getClassLoader();
    for (String lib : LIBRARIES) {
      String fileName = System.mapLibraryName(lib);
      String resourcePath = normalize(base) + os + "/" + arch + "/" + fileName;
      try (InputStream input = cl.getResourceAsStream(resourcePath)) {
        if (input == null) {
          log.debug("Tree-sitter library missing in classpath: {}", resourcePath);
          continue;
        }
        Path temp = Files.createTempFile("treesitter-" + lib + "-", extractExt(fileName));
        temp.toFile().deleteOnExit();
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        System.load(temp.toAbsolutePath().toString());
        loadedPaths.add(resourcePath + "->" + temp);
      } catch (IOException ex) {
        log.warn("Failed to load Tree-sitter library {} from classpath: {}", resourcePath, ex.getMessage());
      }
    }
    if (loadedPaths.isEmpty()) {
      log.warn(
          "No Tree-sitter libraries loaded from classpath base {} (os={}, arch={})",
          base,
          os,
          arch);
    } else {
      log.info("Loaded Tree-sitter libraries from classpath: {}", loadedPaths);
    }
  }

  private String normalize(String base) {
    String normalized = base;
    if (!normalized.endsWith("/")) {
      normalized = normalized + "/";
    }
    if (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  private String extractExt(String fileName) {
    int idx = fileName.lastIndexOf('.');
    return idx >= 0 ? fileName.substring(idx) : "";
  }

  private String normalizeOs() {
    String osName = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
    if (osName.contains("mac") || osName.contains("darwin")) {
      return "macos";
    }
    if (osName.contains("win")) {
      return "windows";
    }
    return "linux";
  }

  private String normalizeArch() {
    String archName = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
    if (archName.contains("aarch64") || archName.contains("arm64")) {
      return "arm64";
    }
    if (archName.contains("x86_64") || archName.contains("amd64")) {
      return "x86_64";
    }
    if (archName.contains("x86") || archName.contains("i386")) {
      return "x86";
    }
    return archName;
  }

  private String firstNonEmpty(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }
}
