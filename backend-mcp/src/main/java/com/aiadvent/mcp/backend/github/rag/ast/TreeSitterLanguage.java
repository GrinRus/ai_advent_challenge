package com.aiadvent.mcp.backend.github.rag.ast;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maps human-readable language ids to the compiled Tree-sitter library names.
 */
public enum TreeSitterLanguage {
  JAVA("java", "tree-sitter-java"),
  KOTLIN("kotlin", "tree-sitter-kotlin"),
  TYPESCRIPT("typescript", "tree-sitter-typescript"),
  JAVASCRIPT("javascript", "tree-sitter-javascript"),
  PYTHON("python", "tree-sitter-python"),
  GO("go", "tree-sitter-go");

  private static final Map<String, TreeSitterLanguage> INDEX =
      Stream.of(values())
          .collect(Collectors.toUnmodifiableMap(TreeSitterLanguage::id, language -> language));

  private final String id;
  private final String libraryBaseName;

  TreeSitterLanguage(String id, String libraryBaseName) {
    this.id = id;
    this.libraryBaseName = libraryBaseName;
  }

  public String id() {
    return id;
  }

  public String libraryBaseName() {
    return libraryBaseName;
  }

  public String resolveLibraryFile(TreeSitterPlatform platform) {
    String baseName = libraryBaseName;
    if (libraryBaseName.contains("/")) {
      baseName = libraryBaseName.substring(libraryBaseName.lastIndexOf('/') + 1);
    }
    return platform.libraryFileName(baseName);
  }

  public static Optional<TreeSitterLanguage> fromId(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String key = value.trim().toLowerCase(Locale.ROOT);
    return Optional.ofNullable(INDEX.get(key));
  }
}
