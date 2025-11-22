package com.aiadvent.mcp.backend.github.rag.chunking;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Heuristic chunker for code files that tries to align chunks with function/class boundaries
 * and doc-comments. Falls back to line-based chunking if the language is not supported or the
 * semantic feature is disabled.
 */
public final class SemanticCodeChunker implements ChunkingStrategy {

  private static final Set<String> SUPPORTED_LANGUAGES =
      Set.of(
          "java",
          "kotlin",
          "javascript",
          "typescript",
          "python",
          "go",
          "rust",
          "ruby",
          "php",
          "csharp",
          "cpp");

  private final LineChunkingStrategy fallback;

  public SemanticCodeChunker(LineChunkingStrategy fallback) {
    this.fallback = Objects.requireNonNull(fallback, "fallback");
  }

  @Override
  public List<Chunk> chunk(ChunkingContext context) {
    if (!context.config().getSemantic().isEnabled() || !supportsLanguage(context.file().language())) {
      return fallbackChunk(context);
    }
    List<Chunk> chunks = buildSemanticChunks(context);
    if (chunks.isEmpty()) {
      return fallbackChunk(context);
    }
    return chunks;
  }

  private List<Chunk> buildSemanticChunks(ChunkingContext context) {
    List<String> lines = context.file().lines();
    if (lines.isEmpty()) {
      return List.of();
    }
    int overlap = Math.max(0, context.config().getOverlapLines());
    int maxLines = Math.max(1, context.config().getLine().getMaxLines());
    int minChunkLines = Math.min(32, Math.max(4, maxLines / 5));
    List<Chunk> chunks = new ArrayList<>();
    List<String> buffer = new ArrayList<>();
    int chunkStartLine = 1;
    int overlapFromPrevious = 0;

    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (shouldStartNewChunk(line, context.file().language(), buffer.size(), minChunkLines)) {
        overlapFromPrevious = appendChunk(chunks, buffer, chunkStartLine, context, overlapFromPrevious);
        int preserved = Math.min(overlap, buffer.size());
        buffer = new ArrayList<>(buffer.subList(Math.max(buffer.size() - preserved, 0), buffer.size()));
        chunkStartLine = index + 1 - preserved;
        overlapFromPrevious = preserved;
      }

      buffer.add(line);
      if (buffer.size() >= maxLines) {
        overlapFromPrevious = appendChunk(chunks, buffer, chunkStartLine, context, overlapFromPrevious);
        int preserved = Math.min(overlap, buffer.size());
        buffer = new ArrayList<>(buffer.subList(Math.max(buffer.size() - preserved, 0), buffer.size()));
        chunkStartLine = index + 1 - preserved;
        overlapFromPrevious = preserved;
      }
    }

    appendChunk(chunks, buffer, chunkStartLine, context, overlapFromPrevious);
    return chunks;
  }

  private int appendChunk(
      List<Chunk> target,
      List<String> buffer,
      int chunkStartLine,
      ChunkingContext context,
      int overlapFromPrevious) {
    if (buffer.isEmpty()) {
      return overlapFromPrevious;
    }
    String text = String.join("\n", buffer);
    int chunkEndLine = chunkStartLine + buffer.size() - 1;
    AstSymbolMetadata astMetadata =
        context.file().astContext().flatMap(ctx -> ctx.symbolForRange(chunkStartLine, chunkEndLine)).orElse(null);
    Chunk chunk =
        Chunk.from(
            text,
            chunkStartLine,
            chunkEndLine,
            context.file().language(),
            context.file().parentSymbolResolver(),
            overlapFromPrevious,
            astMetadata);
    if (chunk != null) {
      target.add(chunk);
    }
    return 0;
  }

  private boolean shouldStartNewChunk(String line, String language, int currentLines, int minChunkLines) {
    String symbol = ParentSymbolResolver.detectSymbol(line);
    if (StringUtils.hasText(symbol)) {
      return true;
    }
    if (currentLines < minChunkLines) {
      return false;
    }
    String trimmed = line == null ? "" : line.trim();
    if (!StringUtils.hasText(trimmed)) {
      return false;
    }
    if (isDocCommentBoundary(trimmed, language)) {
      return true;
    }
    String normalized = language == null ? "" : language.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("python")) {
      return trimmed.startsWith("# ") || trimmed.startsWith("##");
    }
    return false;
  }

  private boolean isDocCommentBoundary(String trimmed, String language) {
    String normalized = language == null ? "" : language.toLowerCase(Locale.ROOT);
    if (trimmed.startsWith("/**") || trimmed.startsWith("/*!")) {
      return true;
    }
    if (trimmed.startsWith("///")) {
      return true;
    }
    if ("ruby".equals(normalized) && trimmed.startsWith("=begin")) {
      return true;
    }
    if ("python".equals(normalized) && (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''") )) {
      return true;
    }
    return false;
  }

  private boolean supportsLanguage(String language) {
    if (!StringUtils.hasText(language)) {
      return false;
    }
    return SUPPORTED_LANGUAGES.contains(language.toLowerCase(Locale.ROOT));
  }

  private List<Chunk> fallbackChunk(ChunkingContext context) {
    return fallback.chunk(new ChunkingContext(context.file(), context.config(), GitHubRagProperties.Strategy.LINE));
  }
}
