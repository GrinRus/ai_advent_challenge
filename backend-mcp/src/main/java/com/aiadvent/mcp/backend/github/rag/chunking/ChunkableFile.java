package com.aiadvent.mcp.backend.github.rag.chunking;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ChunkableFile(
    Path absolutePath,
    String relativePath,
    String language,
    String content,
    List<String> lines,
    LineIndex lineIndex,
    ParentSymbolResolver parentSymbolResolver) {

  public static ChunkableFile from(
      Path absolutePath, String relativePath, String language, String rawContent) {
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
        resolver);
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
}
