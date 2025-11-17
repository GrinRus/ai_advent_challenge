package com.aiadvent.mcp.backend.github.rag.chunking;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class ChunkableFile {

  private final Path absolutePath;
  private final String relativePath;
  private final String language;
  private final String content;
  private final List<String> lines;
  private final LineIndex lineIndex;
  private final ParentSymbolResolver parentSymbolResolver;
  private final Supplier<AstFileContext> astContextSupplier;
  private transient volatile boolean astComputed;
  private transient AstFileContext cachedAstContext;

  public static ChunkableFile from(
      Path absolutePath, String relativePath, String language, String rawContent) {
    return from(absolutePath, relativePath, language, rawContent, () -> null);
  }

  public static ChunkableFile from(
      Path absolutePath,
      String relativePath,
      String language,
      String rawContent,
      Supplier<AstFileContext> astContextSupplier) {
    Objects.requireNonNull(absolutePath, "absolutePath");
    String normalized = normalize(rawContent);
    List<String> lines = splitLines(normalized);
    LineIndex lineIndex = new LineIndex(lines);
    ParentSymbolResolver resolver = new ParentSymbolResolver(lines);
    return new ChunkableFile(
        absolutePath,
        relativePath,
        language,
        normalized,
        Collections.unmodifiableList(lines),
        lineIndex,
        resolver,
        astContextSupplier != null ? astContextSupplier : () -> null);
  }

  private ChunkableFile(
      Path absolutePath,
      String relativePath,
      String language,
      String content,
      List<String> lines,
      LineIndex lineIndex,
      ParentSymbolResolver parentSymbolResolver,
      Supplier<AstFileContext> astContextSupplier) {
    this.absolutePath = absolutePath;
    this.relativePath = relativePath;
    this.language = language;
    this.content = content;
    this.lines = lines;
    this.lineIndex = lineIndex;
    this.parentSymbolResolver = parentSymbolResolver;
    this.astContextSupplier = astContextSupplier;
  }

  private static String normalize(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return value.replace("\r\n", "\n").replace('\r', '\n');
  }

  private static List<String> splitLines(String content) {
    if (content.isEmpty()) {
      return List.of();
    }
    String[] parts = content.split("\n", -1);
    return new ArrayList<>(Arrays.asList(parts));
  }

  public Path absolutePath() {
    return absolutePath;
  }

  public String relativePath() {
    return relativePath;
  }

  public String language() {
    return language;
  }

  public String content() {
    return content;
  }

  public List<String> lines() {
    return lines;
  }

  public LineIndex lineIndex() {
    return lineIndex;
  }

  public ParentSymbolResolver parentSymbolResolver() {
    return parentSymbolResolver;
  }

  public Optional<AstFileContext> astContext() {
    if (!astComputed) {
      synchronized (this) {
        if (!astComputed) {
          try {
            cachedAstContext = astContextSupplier.get();
          } catch (UnsupportedOperationException ex) {
            cachedAstContext = null;
          } finally {
            astComputed = true;
          }
        }
      }
    }
    return Optional.ofNullable(cachedAstContext);
  }
}
